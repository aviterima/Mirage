package com.mirage.spike

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NaturalLanguageInputTest {
    private fun edit(op: String, target: String = "", place: String = "", minutes: Number = -1, value: String = "") =
        JSONObject().put("op", op).put("target", target).put("place", place).put("minutes", minutes).put("value", value)
    private fun plan(vararg edits: JSONObject, question: String = "") = JSONObject().put("edits", JSONArray(edits.toList())).put("question", question).toString()
    private fun field(value: String, question: String = "") = JSONObject().put("value", value).put("question", question).toString()

    @Test fun verbatimNeverNeedsModelInitializationOrChangesCharacters() = runTest {
        val exact = "  EXAMPLE-key:/AbC+9=\n"
        assertEquals(FieldInterpretation(exact), NaturalLanguageInput.interpretField(exact, "API key", "verbatim", ""))
    }

    @Test fun parsedProposalPreservesOrderedContextReferencesAndExplicitZeroStay() {
        val result = NaturalLanguageInput.parsePlan(plan(edit("add_stop", place="saved:fixture-home", minutes=0),
            edit("set_mode", "stop:2", value="WALK"), edit("move_stop", "stop:100", value="1")))
        assertEquals(listOf("add_stop", "set_mode", "move_stop"), result.edits.map { it.op })
        assertEquals("saved:fixture-home", result.edits[0].place)
        assertEquals(0, result.edits[0].minutes)
        assertEquals("stop:100", result.edits[2].target)
    }

    @Test fun clarificationCannotCarryPartialPlanOrFieldChanges() {
        assertThrows(IllegalArgumentException::class.java) { NaturalLanguageInput.parsePlan(plan(edit("remove_stop", "stop:1"), question="Which hotel?")) }
        assertThrows(IllegalArgumentException::class.java) { NaturalLanguageInput.parseField(field("90", "How long?"), "minutes") }
        assertEquals(PlanInterpretation(emptyList(), "Which hotel?"), NaturalLanguageInput.parsePlan(plan(question="Which hotel?")))
        assertEquals(FieldInterpretation("", "AM or PM?"), NaturalLanguageInput.parseField(field("", "AM or PM?"), "datetime"))
    }

    @Test fun rejectsExecutionUnknownKeysAndIrrelevantParameters() {
        assertThrows(Exception::class.java) { NaturalLanguageInput.parsePlan(plan(edit("start"))) }
        assertThrows(Exception::class.java) { NaturalLanguageInput.parsePlan(plan(edit("save", value="Private"))) }
        assertThrows(IllegalArgumentException::class.java) { NaturalLanguageInput.parsePlan(plan(edit("remove_stop", "stop:1", place="invented"))) }
        assertThrows(IllegalArgumentException::class.java) { NaturalLanguageInput.parsePlan(plan(edit("set_mode", "stop:1", value="HOVER"))) }
        assertThrows(IllegalArgumentException::class.java) { NaturalLanguageInput.parsePlan(plan(edit("add_stop", place="query").put("lat", 33))) }
    }

    @Test fun rejectsInvalidNumbersAndReferencesWithoutCoercion() {
        listOf(-2, 1441, 1.5).forEach { minutes ->
            assertThrows(IllegalArgumentException::class.java) { NaturalLanguageInput.parsePlan(plan(edit("set_stay", "stop:1", minutes=minutes))) }
        }
        listOf("stop:0", "stop:101", "stop:-1", "hotel").forEach { ref ->
            assertThrows(IllegalArgumentException::class.java) { NaturalLanguageInput.parsePlan(plan(edit("remove_stop", ref))) }
        }
        assertThrows(IllegalArgumentException::class.java) { NaturalLanguageInput.parsePlan(plan(edit("set_stay", "stop:1", minutes=-1))) }
    }

    @Test fun boundsEditsAdditionsAndInputWithoutTruncating() {
        assertThrows(IllegalArgumentException::class.java) { NaturalLanguageInput.parsePlan(plan(*Array(13) { edit("clear_departure") })) }
        assertThrows(IllegalArgumentException::class.java) { NaturalLanguageInput.parsePlan(plan(*Array(9) { edit("add_stop", place="A") })) }
        assertThrows(IllegalArgumentException::class.java) { NaturalLanguageInput.checkInput("x".repeat(NaturalLanguageInput.MAX_TEXT_CHARS+1), "") }
        assertThrows(IllegalArgumentException::class.java) { NaturalLanguageInput.checkInput("A", "x".repeat(NaturalLanguageInput.MAX_CONTEXT_CHARS+1)) }
        assertThrows(IllegalArgumentException::class.java) { NaturalLanguageInput.parsePlan(plan()) }
    }

    @Test fun validatesFieldUnitsAndRealLocalCalendarDates() {
        assertEquals("90", NaturalLanguageInput.parseField(field("90"), "minutes").value)
        assertEquals("2.5", NaturalLanguageInput.parseField(field("2.5"), "miles").value)
        assertEquals("2026-10-13T18:15", NaturalLanguageInput.parseField(field("2026-10-13T18:15"), "datetime").value)
        listOf("1.5", "ninety", "1441", "-1").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { NaturalLanguageInput.parseField(field(value), "minutes") }
        }
        assertThrows(Exception::class.java) { NaturalLanguageInput.parseField(field("2026-02-30T18:15"), "datetime") }
        assertThrows(IllegalArgumentException::class.java) { NaturalLanguageInput.parsePlan(plan(edit("set_departure", value="tomorrow at six"))) }
    }
}
