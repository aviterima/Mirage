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
}
