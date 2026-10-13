package com.mirage.spike

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.ZonedDateTime

/** Actual downloaded model + production prompt/parser. These are language-quality gates, not stubs. */
class NaturalLanguageModelAcceptanceTest {
    @Test fun realModelUnderstandsContextualFieldInputs() = runBlocking { evaluate("field") }
    @Test fun realModelUnderstandsWholePlansAndCorrections() = runBlocking { evaluate("plan") }

    private suspend fun evaluate(schema: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        LocalLanguageModel.configure(instrumentation.targetContext)
        assumeTrue("Download the verified model before the native language-quality suite", LocalLanguageModel.ready)
        val cases = JSONArray(instrumentation.context.assets.open("natural-language-cases.json").bufferedReader().use { it.readText() })
        val failures = mutableListOf<String>()
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            if (case.getString("schema") != schema) continue
            val id = case.getString("id")
            val began = android.os.SystemClock.elapsedRealtime()
            val passed = try {
                if (schema == "field") {
                    val result = NaturalLanguageInput.interpretFieldAt(case.getString("text"), case.getString("label"), case.getString("kind"),
                        case.getString("current"), case.optString("context"), ZonedDateTime.parse("2026-10-12T09:00:00-07:00[America/Phoenix]"))
                    if (case.optBoolean("question")) meaningfulQuestion(result.question, case) && result.value.isEmpty()
                    else result.question.isBlank() && if (case.has("value")) result.value.equals(case.getString("value"), true)
                    else containsTerms(result.value, case.getJSONArray("valueTerms"))
                } else {
                    val result = NaturalLanguageInput.interpretPlan(case.getString("text"), case.getString("context"))
                    if (case.optBoolean("question")) meaningfulQuestion(result.question, case) && result.edits.isEmpty()
                    else result.question.isBlank() && matchesEdits(result.edits, case.getJSONArray("edits"))
                }
            } catch (_: Exception) { false }
            if (!passed) failures += id
            // Synthetic case IDs and timing only; never log an actual user's field or transcript.
            android.util.Log.i("MirageModelTest", "case=$id passed=$passed elapsed_ms=${android.os.SystemClock.elapsedRealtime()-began}")
            // A failed case already blocks this engine. Passing requires the complete corpus.
            if (!passed) break
        }
        assertTrue("Native language-quality failures: ${failures.joinToString()}", failures.isEmpty())
    }

    private fun containsTerms(value: String, terms: JSONArray): Boolean = (0 until terms.length()).all { value.contains(terms.getString(it), true) }

    private fun meaningfulQuestion(question: String, case: JSONObject): Boolean = question.isNotBlank() &&
        !question.trim().trimEnd('.', '?', '!').equals(case.getString("text").trim().trimEnd('.', '?', '!'), true)

    private fun matchesEdits(actual: List<PlanEdit>, expected: JSONArray): Boolean {
        if (actual.size != expected.length()) return false
        return actual.indices.all { index ->
            val wanted: JSONObject = expected.getJSONObject(index)
            val got = actual[index]
            got.op == wanted.getString("op") &&
                (!wanted.has("target") || got.target == wanted.getString("target")) &&
                (!wanted.has("place") || got.place == wanted.getString("place")) &&
                (!wanted.has("placeTerms") || containsTerms(got.place, wanted.getJSONArray("placeTerms"))) &&
                (!wanted.has("minutes") || got.minutes == wanted.getInt("minutes")) &&
                (!wanted.has("value") || got.value == wanted.getString("value"))
        }
    }
}
