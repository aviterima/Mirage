package com.mirage.spike

import com.mirage.spike.engine.ActivityKind
import org.junit.Assert.*
import org.junit.Test

class SimulationStatusTest {
    private val now = 100_000L
    private val healthy = MockStatus(running = true, health = Health.GREEN, lastFixMillis = now, lastFusedFixMillis = now)
    @Test fun inactiveAndStartingAreExplicit() {
        assertTrue(simulationStatusText(MockStatus(), ActivityKind.TRAVELING, now).startsWith("SIMULATION OFF"))
        assertTrue(simulationStatusText(MockStatus(starting = true), ActivityKind.TRAVELING, now).startsWith("STARTING"))
    }
    @Test fun activeRequiresBothProvidersAndFreshOutput() {
        assertTrue(simulationStatusText(healthy, ActivityKind.TRAVELING, now).startsWith("ACTIVE"))
        for (status in listOf(healthy.copy(lastFixMillis = 0), healthy.copy(lastFusedFixMillis = 0),
            healthy.copy(lastFixMillis = now - 6000), healthy.copy(lastFusedFixMillis = now - 6000),
            healthy.copy(health = Health.AMBER), healthy.copy(signalDropped = true))) {
            assertTrue(simulationStatusText(status, ActivityKind.TRAVELING, now).startsWith("NEEDS ATTENTION"))
        }
    }
    @Test fun pauseStayAndArrivalKeepSimulationMeaningClear() {
        assertTrue(simulationStatusText(healthy.copy(paused = true), ActivityKind.TRAVELING, now).startsWith("PAUSED"))
        assertTrue(simulationStatusText(healthy, ActivityKind.STAYING, now).startsWith("STAYING"))
        assertEquals("HOLDING · simulation remains on", simulationStatusText(healthy, ActivityKind.HOLDING, now))
    }
    @Test fun recordedReplayDistinguishesMovementGapsPauseAndUnconfirmedOutput() {
        val replay = healthy.copy(label = "Replay · Saturday", progress = 0.4f)
        assertEquals("ACTIVE · recording replay", simulationStatusText(replay, ActivityKind.IDLE, now))
        assertEquals("REPLAY GAP · holding last recorded location", simulationStatusText(
            replay.copy(stepLabel = "Recording gap · holding last location"), ActivityKind.IDLE, now))
        assertTrue(simulationStatusText(replay.copy(paused = true), ActivityKind.IDLE, now).startsWith("PAUSED"))
        assertTrue(simulationStatusText(replay.copy(lastFixMillis = 0), ActivityKind.IDLE, now).startsWith("NEEDS ATTENTION"))
        assertEquals("HOLDING · simulation remains on", simulationStatusText(replay.copy(progress = -1f), ActivityKind.HOLDING, now))
    }
}
