package com.mirage.spike

/**
 * Monotonic, session-local evidence for the phone's fused output. An accepted write is
 * not a readback, and neither proves what a separate app or a car head unit displays.
 * Accessed on the main thread by the service and its Play services callbacks.
 */
internal class FusedLocationMonitor(private val startedNanos: Long) {
    var modeReady = false
        private set
    var leakSeen = false
        private set
    var recoveryInFlight = false
        private set
    private var modeReadyNanos = 0L
    private var acceptedNanos = 0L
    private var observedNanos = 0L
    private var observedMock = false
    private var recoveryAttempts = 0
    private var nextRecoveryNanos = 0L

    fun observe(mock: Boolean, fixNanos: Long, nowNanos: Long): Boolean {
        // Ignore cached pre-session, out-of-order, future and stale observations.
        if (modeReadyNanos == 0L || !fresh(fixNanos, nowNanos) || fixNanos < startedNanos || fixNanos <= observedNanos ||
            (modeReady && fixNanos < modeReadyNanos)) return false
        observedNanos = fixNanos
        observedMock = mock
        if (!mock) leakSeen = true
        if (healthy(nowNanos)) recoveryAttempts = 0
        return true
    }

    fun accepted(fixNanos: Long, nowNanos: Long) {
        if (!modeReady || fixNanos < modeReadyNanos || !fresh(fixNanos, nowNanos)) return
        acceptedNanos = maxOf(acceptedNanos, fixNanos)
        if (healthy(nowNanos)) recoveryAttempts = 0
    }

    fun writeFailed() { modeReady = false }

    fun healthy(nowNanos: Long): Boolean = modeReady && fresh(acceptedNanos, nowNanos) &&
        observedMock && fresh(observedNanos, nowNanos)

    fun realLocationObserved(nowNanos: Long): Boolean = !observedMock && fresh(observedNanos, nowNanos)

    fun issue(nowNanos: Long): String? = when {
        realLocationObserved(nowNanos) -> "Phone reported real location — restoring simulation"
        !modeReady -> "Restoring Google location feed"
        !fresh(acceptedNanos, nowNanos) -> "Waiting for Google location feed confirmation"
        !observedMock || !fresh(observedNanos, nowNanos) -> "Waiting to verify simulated phone location"
        else -> null
    }

    /** Coalesce requests; repeated failures back off from 2 seconds up to 30 seconds. */
    fun beginRecovery(nowNanos: Long): Boolean {
        if (recoveryInFlight || nowNanos < nextRecoveryNanos) return false
        val graceExpired = nowNanos - modeReadyNanos >= FRESH_NANOS
        if (modeReady && !realLocationObserved(nowNanos) && (!graceExpired || healthy(nowNanos))) return false
        recoveryInFlight = true
        modeReady = false
        recoveryAttempts = minOf(recoveryAttempts + 1, 5)
        val delaySeconds = minOf(2L shl (recoveryAttempts - 1), 30L)
        nextRecoveryNanos = nowNanos + delaySeconds * SECOND_NANOS
        return true
    }

    fun recoveryCompleted(success: Boolean, nowNanos: Long) {
        recoveryInFlight = false
        modeReady = success
        if (success) {
            modeReadyNanos = nowNanos
            // setMockMode(true) clears the fused cache. Verify a new write and readback.
            acceptedNanos = 0L
            observedNanos = 0L
            observedMock = false
        }
    }

    companion object {
        const val SECOND_NANOS = 1_000_000_000L
        const val FRESH_NANOS = 5 * SECOND_NANOS
        fun fresh(fixNanos: Long, nowNanos: Long): Boolean =
            fixNanos > 0L && nowNanos - fixNanos in 0..FRESH_NANOS
    }
}

/**
 * Serializes asynchronous mode changes, including Stop during an unfinished enable.
 * Only the newest queued intent is needed; the current operation must finish first.
 * This prevents a late enable from being issued after its matching disable.
 */
internal class SerializedMockModeSwitch(private val nowNanos: () -> Long = { 0L }) {
    private data class Request(
        val id: Long,
        val enabled: Boolean,
        val apply: (Boolean, (Boolean) -> Unit) -> Unit,
        val completed: (Boolean) -> Unit,
    )
    private var active: Request? = null
    private var activeSince = 0L
    private var pending: Request? = null
    private var latest: Request? = null
    private var sequence = 0L
    private var urgentInFlight = false
    private var urgentFor = -1L

    fun request(enabled: Boolean, apply: (Boolean, (Boolean) -> Unit) -> Unit, completed: (Boolean) -> Unit = {}) {
        val request = Request(++sequence, enabled, apply, completed)
        latest = request
        pending = request
        drain()
        checkTimeout()
    }

    fun overdue(): Boolean = active != null && nowNanos() - activeSince >= PendingLocationRequest.TIMEOUT_NANOS

    /**
     * Google mode Tasks cannot be cancelled. Never overlap enables. If Stop waits
     * behind an overdue enable, issue one urgent disable and retain the queued
     * disable to run again after that enable settles. New enables also wait for
     * the urgent disable, so an old disable cannot land after a new enable.
     */
    fun checkTimeout() {
        val current = active ?: return
        val desired = latest ?: return
        if (!overdue() || !current.enabled || desired.enabled || urgentInFlight || urgentFor == desired.id) return
        urgentFor = desired.id
        urgentInFlight = true
        desired.completed(false) // Unknown old operation: reset is not yet confirmed.
        desired.apply(false) {
            urgentInFlight = false
            // The old enable remains active until it actually completes. drain()
            // will then apply the newest intent, which may now be a new Start.
            drain()
        }
    }

    private fun drain() {
        if (active != null || urgentInFlight) return
        val request = pending ?: return
        pending = null
        active = request
        activeSince = nowNanos()
        request.apply(request.enabled) { success ->
            active = null
            request.completed(success)
            drain()
        }
    }
}

/** A timeout retires callback authority, not the non-cancellable platform Task. */
internal class PendingLocationRequest {
    private var sequence = 0L
    private var startedNanos = 0L
    private var pending = false

    fun begin(nowNanos: Long): Long? {
        if (pending && nowNanos - startedNanos < TIMEOUT_NANOS) return null
        pending = true
        startedNanos = nowNanos
        return ++sequence
    }

    fun isCurrent(token: Long): Boolean = token == sequence

    fun complete(token: Long): Boolean {
        if (!isCurrent(token) || !pending) return false
        pending = false
        return true
    }

    fun invalidate() { sequence++; pending = false }

    companion object { const val TIMEOUT_NANOS = 5_000_000_000L }
}
