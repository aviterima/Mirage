package com.mirage.spike

import com.mirage.spike.engine.Fix
import org.junit.Assert.*
import org.junit.Test

class FusedLocationMonitorTest {
    private val second = FusedLocationMonitor.SECOND_NANOS
    private val start = 100 * second

    @Test fun continuingTripHoldsTheExactPriorOutputUntilNewRouteEmits() {
        val moving = Fix(33.5, -112.0, 20f, 90f, 4f, altitudeM = 12.0, progress = 0.5f, remainingSec = 30)
        val held = continuationHold(MockStatus(running = true, lastFixMillis = 100L), moving)!!
        assertEquals(moving.lat, held.lat, 0.0)
        assertEquals(moving.lng, held.lng, 0.0)
        assertEquals(moving.altitudeM, held.altitudeM, 0.0)
        assertEquals(0f, held.speedMps)
        assertEquals(-1f, held.progress)
        assertEquals(-1, held.remainingSec)
        assertEquals(20f, moving.speedMps)
    }

    @Test fun stoppedStartingOrNeverEmittedSessionsCannotReviveAnOldAnchor() {
        val old = Fix(33.5, -112.0, 0f, 0f, 4f)
        assertNull(continuationHold(MockStatus(lastFixMillis = 100L), old))
        assertNull(continuationHold(MockStatus(starting = true, lastFixMillis = 100L), old))
        assertNull(continuationHold(MockStatus(running = true), old))
        assertNull(continuationHold(MockStatus(running = true, lastFixMillis = 100L), null))
    }

    @Test fun failedInitialEnableRetriesAndRequestsAreCoalesced() {
        val monitor = FusedLocationMonitor(start)
        assertTrue(monitor.beginRecovery(start))
        assertFalse(monitor.beginRecovery(start + second))
        monitor.recoveryCompleted(false, start + second)
        assertFalse(monitor.beginRecovery(start + second))
        assertTrue(monitor.beginRecovery(start + 2 * second))
        assertFalse(monitor.modeReady)
    }

    @Test fun repeatedFailuresBackOffButNeverBeyondThirtySeconds() {
        val monitor = FusedLocationMonitor(start)
        var now = start
        for (delay in listOf(2L, 4L, 8L, 16L, 30L, 30L)) {
            assertTrue(monitor.beginRecovery(now))
            monitor.recoveryCompleted(false, now)
            assertFalse(monitor.beginRecovery(now + delay * second - 1))
            now += delay * second
        }
        assertTrue(monitor.beginRecovery(now))
    }

    @Test fun successfulWriteAloneDoesNotClaimVerifiedOutput() {
        val monitor = enabledMonitor()
        monitor.accepted(start + second, start + second)
        assertFalse(monitor.healthy(start + second))
        assertEquals("Waiting to verify simulated phone location", monitor.issue(start + second))
        monitor.observe(true, start + second, start + second)
        assertTrue(monitor.healthy(start + second))
        assertNull(monitor.issue(start + second))
    }

    @Test fun realLocationDuringInitialSetupIsNotReportedAsALeak() {
        val monitor = FusedLocationMonitor(start)
        assertFalse(monitor.observe(false, start + second, start + second))
        assertTrue(monitor.beginRecovery(start + second))
        assertFalse(monitor.observe(false, start + 2 * second, start + 2 * second))
        assertFalse(monitor.leakSeen)
        monitor.recoveryCompleted(true, start + 3 * second)
        assertTrue(monitor.observe(false, start + 4 * second, start + 4 * second))
        assertTrue(monitor.leakSeen)
    }

    @Test fun cachedPreSessionStaleFutureAndOutOfOrderRealFixesDoNotCountAsLeaks() {
        val monitor = enabledMonitor()
        assertFalse(monitor.observe(false, start - 1, start))
        assertFalse(monitor.observe(false, start + second, start + 7 * second))
        assertFalse(monitor.observe(false, start + 3 * second, start + 2 * second))
        monitor.accepted(start + 2 * second, start + 2 * second)
        assertTrue(monitor.observe(true, start + 2 * second, start + 2 * second))
        assertFalse(monitor.observe(false, start + second, start + 2 * second))
        assertFalse(monitor.leakSeen)
        assertTrue(monitor.healthy(start + 2 * second))
    }

    @Test fun freshRealFixImmediatelyRemovesHealthyStatusAndRequestsRecovery() {
        val monitor = enabledMonitor()
        monitor.accepted(start + second, start + second)
        monitor.observe(true, start + second, start + second)
        assertTrue(monitor.observe(false, start + 2 * second, start + 2 * second))
        assertTrue(monitor.leakSeen)
        assertFalse(monitor.healthy(start + 2 * second))
        assertTrue(monitor.realLocationObserved(start + 2 * second))
        assertTrue(monitor.beginRecovery(start + 2 * second))
    }

    @Test fun recoveryRequiresANewWriteAndReadbackAndKeepsHistoricalLeakFlag() {
        val monitor = enabledMonitor()
        monitor.observe(false, start + 2 * second, start + 2 * second)
        assertTrue(monitor.beginRecovery(start + 2 * second))
        monitor.recoveryCompleted(true, start + 3 * second)
        monitor.accepted(start + second, start + 3 * second)
        assertFalse(monitor.observe(true, start + 2 * second, start + 3 * second))
        assertFalse(monitor.healthy(start + 3 * second))
        monitor.accepted(start + 4 * second, start + 4 * second)
        monitor.observe(true, start + 4 * second, start + 4 * second)
        assertTrue(monitor.healthy(start + 4 * second))
        assertTrue(monitor.leakSeen)
        assertNull(monitor.issue(start + 4 * second))
    }

    @Test fun silentReadbackGivesInitialGraceThenRetriesInsteadOfRemainingGreen() {
        val monitor = enabledMonitor()
        monitor.accepted(start + second, start + second)
        assertFalse(monitor.beginRecovery(start + 4 * second))
        assertTrue(monitor.beginRecovery(start + 5 * second))
        assertFalse(monitor.healthy(start + 5 * second))
    }

    @Test fun writeFailureReentersRecoveryAndFreshnessUsesMonotonicTime() {
        val monitor = enabledMonitor()
        monitor.accepted(start + second, start + second)
        monitor.observe(true, start + second, start + second)
        assertTrue(monitor.healthy(start + 6 * second))
        assertFalse(monitor.healthy(start + 6 * second + 1))
        monitor.writeFailed()
        assertTrue(monitor.beginRecovery(start + 7 * second))
    }

    @Test fun steadyHealthyOutputDoesNotRepeatedlyClearFusedCache() {
        val monitor = enabledMonitor()
        for (elapsed in 1L..120L) {
            val now = start + elapsed * second
            monitor.accepted(now, now)
            monitor.observe(true, now, now)
            assertFalse(monitor.beginRecovery(now))
        }
    }

    @Test fun stopWaitsForPendingEnableAndDisablesAfterItCompletes() {
        val coordinator = SerializedMockModeSwitch()
        val operations = mutableListOf<Boolean>()
        val completions = mutableListOf<(Boolean) -> Unit>()
        val apply: (Boolean, (Boolean) -> Unit) -> Unit = { enabled, done ->
            operations += enabled
            completions += done
        }
        coordinator.request(true, apply)
        coordinator.request(false, apply)
        assertEquals(listOf(true), operations)
        completions[0](true)
        assertEquals(listOf(true, false), operations)
        completions[1](true)
    }

    @Test fun rapidRestartKeepsOnlyNewestQueuedModeWhileIgnoringOldSessionResult() {
        val coordinator = SerializedMockModeSwitch()
        val operations = mutableListOf<Boolean>()
        val completions = mutableListOf<(Boolean) -> Unit>()
        val apply: (Boolean, (Boolean) -> Unit) -> Unit = { enabled, done ->
            operations += enabled
            completions += done
        }
        var currentGeneration = 1
        var ready = false
        coordinator.request(true, apply) { if (currentGeneration == 1) ready = it }
        coordinator.request(false, apply)
        currentGeneration = 2
        coordinator.request(true, apply) { if (currentGeneration == 2) ready = it }
        completions[0](true)
        assertFalse(ready)
        assertEquals(listOf(true, true), operations)
        completions[1](true)
        assertTrue(ready)
    }

    @Test fun failedOperationDoesNotPreventQueuedDisable() {
        val coordinator = SerializedMockModeSwitch()
        val operations = mutableListOf<Boolean>()
        var finish: ((Boolean) -> Unit)? = null
        val apply: (Boolean, (Boolean) -> Unit) -> Unit = { enabled, done -> operations += enabled; finish = done }
        coordinator.request(true, apply)
        coordinator.request(false, apply)
        finish!!(false)
        assertEquals(listOf(true, false), operations)
    }

    @Test fun pendingWriteExpiresAndLateCallbackCannotCompleteItsReplacement() {
        val pending = PendingLocationRequest()
        val first = pending.begin(start)!!
        assertNull(pending.begin(start + 4 * second))
        val replacement = pending.begin(start + 5 * second)!!
        assertFalse(pending.complete(first))
        assertNull(pending.begin(start + 6 * second))
        assertTrue(pending.complete(replacement))
        assertNotNull(pending.begin(start + 6 * second))
    }

    @Test fun sessionOrRecoveryInvalidationRetiresOldWriteAndObserverCallbacks() {
        val pending = PendingLocationRequest()
        val old = pending.begin(start)!!
        pending.invalidate()
        assertFalse(pending.isCurrent(old))
        val current = pending.begin(start + second)!!
        assertFalse(pending.complete(old))
        assertTrue(pending.complete(current))
        assertTrue(pending.isCurrent(current)) // Successful registered observer may deliver updates.
        pending.invalidate()
        assertFalse(pending.isCurrent(current))
    }

    @Test fun overdueEnableAllowsUrgentStopButResetStaysUnconfirmedUntilOrderedDisable() {
        var now = start
        val coordinator = SerializedMockModeSwitch { now }
        val operations = mutableListOf<Boolean>()
        val completions = mutableListOf<(Boolean) -> Unit>()
        val stopResults = mutableListOf<Boolean>()
        val apply: (Boolean, (Boolean) -> Unit) -> Unit = { enabled, done -> operations += enabled; completions += done }
        coordinator.request(true, apply)
        coordinator.request(false, apply) { stopResults += it }
        now += 5 * second
        coordinator.checkTimeout()
        assertTrue(coordinator.overdue())
        assertEquals(listOf(true, false), operations)
        assertEquals(listOf(false), stopResults)
        completions[1](true) // Best effort is insufficient while the old enable is unresolved.
        assertEquals(listOf(false), stopResults)
        completions[0](true)
        assertEquals(listOf(true, false, false), operations)
        completions[2](true)
        assertEquals(listOf(false, true), stopResults)
    }

    @Test fun restartAfterUrgentStopWaitsForOldDisableAndReconcilesToNewEnable() {
        var now = start
        val coordinator = SerializedMockModeSwitch { now }
        val operations = mutableListOf<Boolean>()
        val completions = mutableListOf<(Boolean) -> Unit>()
        val apply: (Boolean, (Boolean) -> Unit) -> Unit = { enabled, done -> operations += enabled; completions += done }
        coordinator.request(true, apply)
        now += 5 * second
        coordinator.request(false, apply)
        coordinator.request(true, apply)
        completions[0](true) // Old enable finishes, but Stop's urgent disable is still pending.
        assertEquals(listOf(true, false), operations)
        completions[1](true)
        assertEquals(listOf(true, false, true), operations)
        completions[2](true)
        coordinator.checkTimeout()
        assertEquals(listOf(true, false, true), operations)
    }

    @Test fun lateOldEnableAfterUrgentStopAndRestartDoesNotDisableNewSession() {
        var now = start
        val coordinator = SerializedMockModeSwitch { now }
        val operations = mutableListOf<Boolean>()
        val completions = mutableListOf<(Boolean) -> Unit>()
        val apply: (Boolean, (Boolean) -> Unit) -> Unit = { enabled, done -> operations += enabled; completions += done }
        coordinator.request(true, apply)
        now += 5 * second
        coordinator.request(false, apply)
        completions[1](true)
        coordinator.request(true, apply)
        completions[0](true)
        assertEquals(listOf(true, false, true), operations)
        completions[2](true)
        assertEquals(listOf(true, false, true), operations)
    }

    private fun enabledMonitor() = FusedLocationMonitor(start).also {
        assertTrue(it.beginRecovery(start))
        it.recoveryCompleted(true, start)
    }
}
