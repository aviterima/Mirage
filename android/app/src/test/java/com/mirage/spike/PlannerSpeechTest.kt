package com.mirage.spike

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PlannerSpeechTest {
    @After fun cleanup() { PlannerSpeech.cancel() }

    @Test fun cancelledDictationCannotRestoreOldWords() {
        val request = PlannerSpeech.begin(Field.END)
        PlannerSpeech.partial(request, "old destination")
        PlannerSpeech.cancel()
        PlannerSpeech.finish(request, "old destination")
        assertNull(PlannerSpeech.state.value.target)
        assertEquals("", PlannerSpeech.state.value.transcript)
        assertFalse(PlannerSpeech.state.value.complete)
    }

    @Test fun switchingFieldsRejectsThePreviousRecording() {
        val old = PlannerSpeech.begin(Field.START)
        val current = PlannerSpeech.begin(Field.END)
        PlannerSpeech.finish(old, "old start")
        assertTrue(PlannerSpeech.isActive(current))
        PlannerSpeech.finish(current, "Home")
        assertEquals(Field.END, PlannerSpeech.state.value.target)
        assertEquals("Home", PlannerSpeech.state.value.transcript)
    }

    @Test fun completedWordsAreConsumedOnceAndLatePartialsAreIgnored() {
        val request = PlannerSpeech.begin(Field.START)
        PlannerSpeech.finish(request, "Office")
        PlannerSpeech.partial(request, "incorrect late result")
        assertEquals("Office", PlannerSpeech.state.value.transcript)
        PlannerSpeech.consume(request)
        PlannerSpeech.finish(request, "duplicate")
        assertNull(PlannerSpeech.state.value.target)
        assertFalse(PlannerSpeech.state.value.complete)
    }

    @Test fun ordinaryPlaceNamesStaySearchableAndRepliesStayExplicit() {
        assertFalse(isPlannerInstruction("Western Digital (Irvine)"))
        assertFalse(isPlannerInstruction("Home"))
        assertTrue(isPlannerInstruction("drive to Home"))
        assertTrue(isPlannerInstruction("add Office"))
        assertTrue(isPlannerReply("yes"))
        assertTrue(isPlannerReply("one"))
        assertTrue(isPlannerReplyPrefix("conf"))
        assertFalse(isPlannerReply("yesterday"))
        assertFalse(isPlannerReplyPrefix("Western"))
    }

    @Test fun finishRequestKeepsListeningUntilServiceReturnsCapturedWords() {
        val id = PlannerSpeech.begin(Field.END, "Visit Home")
        PlannerSpeech.partial(id, "then Office")
        PlannerSpeech.requestFinish(id)
        assertTrue(PlannerSpeech.state.value.active)
        assertTrue(PlannerSpeech.state.value.finishRequested)
        PlannerSpeech.finish(id, "then Office for thirty minutes")
        assertEquals("Visit Home", PlannerSpeech.state.value.prefix)
        assertEquals("then Office for thirty minutes", PlannerSpeech.state.value.transcript)
        assertTrue(PlannerSpeech.state.value.complete)
        assertFalse(PlannerSpeech.state.value.active)
    }

    @Test fun interruptionPreservesPartialAndRejectsLateServiceCompletion() {
        val id = PlannerSpeech.begin(Field.START)
        PlannerSpeech.partial(id, "from Home actually")
        PlannerSpeech.interrupt(id)
        PlannerSpeech.finish(id, "late words")
        assertEquals("from Home actually", PlannerSpeech.state.value.transcript)
        assertTrue(PlannerSpeech.state.value.interrupted)
        assertTrue(PlannerSpeech.state.value.complete)
    }
}
