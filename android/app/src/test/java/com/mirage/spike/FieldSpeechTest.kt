package com.mirage.spike

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class FieldSpeechTest {
    @After fun cleanUp() { FieldSpeech.cancel() }

    @Test fun successivePhrasesAndRevisedPartialRemainOneEditableInstruction() {
        val words = DictationTranscript()
        assertEquals("call this", words.partial("call this"))
        assertEquals("call this Tuesday", words.segment("call this Tuesday"))
        assertEquals("call this Tuesday actually", words.partial("actually"))
        assertEquals("call this Tuesday actually Wednesday", words.partial("actually Wednesday"))
        assertEquals("call this Tuesday actually Wednesday", words.segment("actually Wednesday"))
        assertEquals("call this Tuesday actually Wednesday and stay for an hour", words.partial("and stay for an hour"))
        words.clear()
        assertEquals("", words.text())
    }

    @Test fun doneRequestsCompletionWithoutDiscardingWordsOrApplyingAnAction() {
        val id = FieldSpeech.begin("planner.name", prefix = "call it Tuesday")
        FieldSpeech.partial(id, "call it Tuesday actually Wednesday")
        FieldSpeech.requestFinish(id)
        assertTrue(FieldSpeech.state.value.active)
        assertTrue(FieldSpeech.state.value.finishRequested)
        assertEquals("call it Tuesday actually Wednesday", FieldSpeech.state.value.transcript)
        FieldSpeech.finish(id)
        assertFalse(FieldSpeech.state.value.active)
        assertEquals("call it Tuesday actually Wednesday", FieldSpeech.results.value["planner.name"]?.transcript)
        assertFalse(FieldSpeech.results.value["planner.name"]!!.interrupted)
    }

    @Test fun changingOwnersRetainsInterruptedWordsAndRejectsLateResults() {
        val first = FieldSpeech.begin("library.item.first.name")
        FieldSpeech.partial(first, "Lunch with")
        val second = FieldSpeech.begin("library.item.second.name")
        assertTrue(FieldSpeech.results.value["library.item.first.name"]!!.interrupted)
        assertEquals("Lunch with", FieldSpeech.results.value["library.item.first.name"]!!.transcript)
        FieldSpeech.finish(first, "late old words")
        FieldSpeech.partial(second, "Dinner")
        assertEquals("Dinner", FieldSpeech.state.value.transcript)
        assertEquals("Lunch with", FieldSpeech.results.value["library.item.first.name"]!!.transcript)
        FieldSpeech.interrupt(second)
        FieldSpeech.consume(first)
        assertFalse(FieldSpeech.results.value.containsKey("library.item.first.name"))
        assertEquals("Dinner", FieldSpeech.results.value["library.item.second.name"]!!.transcript)
    }

    @Test fun startingNewPlannerClearsItsQueuedResultsButPreservesOtherEditors() {
        val live = FieldSpeech.begin("live.talk")
        FieldSpeech.finish(live, "pause")
        val completedPlanner = FieldSpeech.begin("planner.stay")
        FieldSpeech.finish(completedPlanner, "one hour")
        val activePlanner = FieldSpeech.begin("planner.name")
        FieldSpeech.partial(activePlanner, "Weekend")
        FieldSpeech.setReviewing("planner.stay", true)
        FieldSpeech.setReviewing("library.rename", true)
        FieldSpeech.cancelPlanner()
        FieldSpeech.finish(activePlanner, "obsolete weekend")
        assertNull(FieldSpeech.state.value.key)
        assertEquals(setOf("live.talk"), FieldSpeech.results.value.keys)
        assertEquals("pause", FieldSpeech.results.value["live.talk"]?.transcript)
        assertEquals(setOf("library.rename"), FieldSpeech.reviewing.value)
    }

    @Test fun cancellingOneEditorDoesNotEraseAnotherPendingResult() {
        val first = FieldSpeech.begin("library.first")
        FieldSpeech.finish(first, "One")
        val secret = FieldSpeech.begin("setup.key", sensitive = true)
        FieldSpeech.partial(secret, "literal Private-Value")
        assertTrue(FieldSpeech.state.value.sensitive)
        FieldSpeech.cancel("setup.key")
        FieldSpeech.finish(secret, "late secret")
        assertEquals("", FieldSpeech.state.value.transcript)
        assertFalse(FieldSpeech.results.value.containsKey("setup.key"))
        assertEquals("One", FieldSpeech.results.value["library.first"]?.transcript)
    }
}
