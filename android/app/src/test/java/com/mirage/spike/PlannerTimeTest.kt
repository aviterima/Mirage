package com.mirage.spike

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PlannerTimeTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    @Test fun normalLocalTimeUsesTheSelectedZone() {
        assertEquals(Instant.parse("2026-10-20T20:00:00Z").toEpochMilli(), parsePlannerTime("2026-10-20T13:00", zone))
    }
    @Test fun clockChangeGapAndRepeatedHourNeedAnExplicitDifferentTime() {
        assertTrue(runCatching { parsePlannerTime("2026-03-08T02:30", zone) }.isFailure)
        assertTrue(runCatching { parsePlannerTime("2026-11-01T01:30", zone) }.isFailure)
    }
    @Test fun unreviewedSpeechCannotBecomeADeadline() {
        assertTrue(runCatching { parsePlannerTime("tomorrow at nine", zone) }.isFailure)
        assertTrue(runCatching { parsePlannerTime("2026-02-30T09:00", zone) }.isFailure)
    }
}
