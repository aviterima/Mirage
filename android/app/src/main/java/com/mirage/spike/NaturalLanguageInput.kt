package com.mirage.spike

import android.content.Context
import com.mirage.spike.engine.TravelMode
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class FieldInterpretation(val value: String, val question: String = "")
data class PlanEdit(val op: String, val target: String = "", val place: String = "", val minutes: Int = -1, val value: String = "")
data class PlanInterpretation(val edits: List<PlanEdit>, val question: String = "")

/** Proposes values only. The caller resolves places, reviews the proposal and owns every mutation. */
object NaturalLanguageInput {
    const val MAX_TEXT_CHARS = 5_000
    const val MAX_CONTEXT_CHARS = 18_000
    const val MAX_EDITS = 12
    private val kinds = setOf("text", "name", "aliases", "place", "minutes", "miles", "datetime", "verbatim")
    private val stopReference = Regex("stop:(?:[1-9][0-9]?|100)")
    private val localDateTime = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}")
    @Volatile private var fieldInstructions = ""
    @Volatile private var planInstructions = ""

    internal fun configure(context: Context) {
        if (fieldInstructions.isBlank()) fieldInstructions = context.assets.open("natural-field-prompt.txt").bufferedReader().use { it.readText() }
        if (planInstructions.isBlank()) planInstructions = context.assets.open("natural-plan-prompt.txt").bufferedReader().use { it.readText() }
    }

    suspend fun interpretField(text: String, label: String, kind: String, currentValue: String, context: String = ""): FieldInterpretation =
        interpretFieldAt(text, label, kind, currentValue, context, ZonedDateTime.now())

    internal suspend fun interpretFieldAt(text: String, label: String, kind: String, currentValue: String, context: String, localNow: ZonedDateTime): FieldInterpretation {
        require(kind in kinds) { "This field does not support interpretation." }
        // Credentials and other exact input must never be inserted into a model prompt.
        if (kind == "verbatim") return FieldInterpretation(text)
        checkInput(text, context)
        require(label.length <= 160 && currentValue.length <= MAX_TEXT_CHARS) { "The field context is too long. Shorten it before reviewing." }
        check(fieldInstructions.isNotBlank()) { "Open voice setup before reviewing an instruction." }
        val data = "Field: ${JSONObject.quote(label)}\nValue format: ${fieldFormat(kind)}\nCurrent value: ${JSONObject.quote(currentValue)}\n" +
            "Local now: ${localNow.format(DateTimeFormatter.ISO_ZONED_DATE_TIME)}\nContext: $context\nInstruction: ${JSONObject.quote(text)}"
        return parseField(LocalLanguageModel.interpretStructured(fieldInstructions, data, "field"), kind)
    }

    suspend fun interpretPlan(text: String, context: String): PlanInterpretation {
        checkInput(text, context)
        check(planInstructions.isNotBlank()) { "Open voice setup before reviewing an instruction." }
        val data = "Context: $context\nInstruction: ${JSONObject.quote(text)}"
        return parsePlan(LocalLanguageModel.interpretStructured(planInstructions, data, "plan"))
    }

    internal fun checkInput(text: String, context: String) {
        require(text.isNotBlank()) { "Enter an instruction to review." }
        require(text.length <= MAX_TEXT_CHARS) { "This instruction is too long. Shorten it before reviewing; none of it has been applied." }
        require(context.length <= MAX_CONTEXT_CHARS) { "This plan has too much context for one review. Use the individual fields." }
    }

    internal fun fieldFormat(kind: String): String = when (kind) {
        "minutes" -> "Whole minutes, 0 to 1440. Convert hours to minutes. Return only digits."
        "miles" -> "Decimal miles, 0 to 10000. Convert spoken numbers to digits. No units in the value."
        "datetime" -> "Local date/time YYYY-MM-DDTHH:mm. Resolve relative dates from context or local now. Ask if AM/PM or date is ambiguous."
        "aliases" -> "Comma-separated aliases. Retain current aliases when adding another."
        "place" -> "Place search query. Keep the intended place name and city; no coordinates or invented city."
        "name" -> "The intended name as text."
        else -> "The intended field content as text."
    }

    internal fun parseField(raw: String, kind: String): FieldInterpretation {
        require(kind in kinds && kind != "verbatim")
        val obj = objectResult(raw, setOf("value", "question"))
        val value = string(obj, "value", 512)
        val question = string(obj, "question", 400)
        if (question.isNotBlank()) {
            require(value.isBlank()) { "Clarification must not also change the field." }
            return FieldInterpretation("", question)
        }
        when (kind) {
            "minutes" -> require(Regex("(?:0|[1-9][0-9]{0,3})").matches(value) && value.toInt() in 0..1440) { "Review a stay between 0 and 1440 minutes." }
            "miles" -> require(Regex("(?:0|[1-9][0-9]{0,4})(?:\\.[0-9]{1,6})?").matches(value) && value.toDouble() in 0.0..10000.0) { "Review a distance between 0 and 10000 miles." }
            "datetime" -> validateDateTime(value)
        }
        return FieldInterpretation(value)
    }

    internal fun parsePlan(raw: String): PlanInterpretation {
        val obj = objectResult(raw, setOf("edits", "question"))
        val question = string(obj, "question", 400)
        val edits = obj.getJSONArray("edits")
        require(edits.length() <= MAX_EDITS) { "Review at most $MAX_EDITS changes at a time." }
        if (question.isNotBlank()) {
            require(edits.length() == 0) { "Clarification must not also change the plan." }
            return PlanInterpretation(emptyList(), question)
        }
        require(edits.length() > 0) { "No planning changes were understood. Please clarify what should change." }
        val parsed = (0 until edits.length()).map { index ->
            val edit = edits.getJSONObject(index)
            require(edit.keys().asSequence().toSet() == setOf("op", "target", "place", "minutes", "value")) { "Unrecognized plan format." }
            val op = string(edit, "op", 32)
            val target = string(edit, "target", 32)
            val place = string(edit, "place", 240)
            val value = string(edit, "value", 160)
            require(edit.get("minutes") is Number) { "Stay must be a whole number of minutes." }
            val minutes = edit.getInt("minutes")
            require(edit.getDouble("minutes") == minutes.toDouble() && minutes in -1..1440) { "Stay must be between 0 and 1440 minutes." }
            val isStop = stopReference.matches(target)
            when (op) {
                "set_start" -> require(target in setOf("", "start") && place.isNotBlank() && minutes == -1 && value.isBlank())
                "set_destination" -> require(target in setOf("", "destination") && place.isNotBlank() && value.isBlank())
                "add_stop" -> require((target in setOf("", "end") || isStop) && place.isNotBlank() && value.isBlank())
                "replace_stop" -> require(isStop && place.isNotBlank() && value.isBlank())
                "remove_stop" -> require(isStop && place.isBlank() && minutes == -1 && value.isBlank())
                "move_stop" -> require(isStop && place.isBlank() && minutes == -1 && Regex("(?:[1-9][0-9]?|100)").matches(value))
                "set_stay" -> require((isStop || target == "destination") && place.isBlank() && minutes >= 0 && value.isBlank())
                "set_mode" -> require((isStop || target == "destination") && place.isBlank() && minutes == -1 && value in TravelMode.entries.map { it.name })
                "set_departure" -> { require(target.isBlank() && place.isBlank() && minutes == -1); validateDateTime(value) }
                "clear_departure" -> require(target.isBlank() && place.isBlank() && minutes == -1 && value.isBlank())
                else -> error("Unsupported planning change.")
            }
            PlanEdit(op, target, place, minutes, value)
        }
        require(parsed.count { it.op == "add_stop" } <= 8) { "Add at most eight stops in one review." }
        return PlanInterpretation(parsed)
    }

    private fun objectResult(raw: String, keys: Set<String>): JSONObject {
        val text = raw.trim()
        require(text.length <= 16_000 && text.startsWith("{") && text.endsWith("}")) { "The response was incomplete. Review the instruction again." }
        return JSONObject(text).also { require(it.keys().asSequence().toSet() == keys) { "Unrecognized response format." } }
    }

    private fun string(obj: JSONObject, key: String, max: Int): String {
        require(obj.get(key) is String) { "Unrecognized response format." }
        return obj.getString(key).trim().also { require(it.length <= max) { "The proposed value is too long." } }
    }

    private fun validateDateTime(value: String) {
        require(localDateTime.matches(value)) { "Specify a full local date and time, including AM or PM." }
        LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }
}
