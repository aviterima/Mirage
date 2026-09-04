package com.mirage.spike

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Fallback map centre until the device's real location is known (SF Ferry Building). */
const val START_LAT = 37.7955
const val START_LNG = -122.3937

enum class Health { GREEN, AMBER, RED }

/**
 * Health semantics:
 *  - GREEN  = we are emitting and the OS last-known fix is our mock (no leakage).
 *  - AMBER  = re-asserting after a provider drop, or a real (non-mock) fix was seen.
 *  - RED    = cannot mock (app not selected as the mock-location app, or stopped).
 */
data class MockStatus(
    val running: Boolean = false,
    val mockAppSelected: Boolean = false,
    val health: Health = Health.RED,
    val lat: Double = START_LAT,
    val lng: Double = START_LNG,
    val speedMps: Float = 0f,
    val bearingDeg: Float = 0f,
    /** 0..1 through the current leg, or -1 while holding a point. */
    val progress: Float = -1f,
    /** Seconds left in the current leg, or -1 when unknown. */
    val remainingSec: Int = -1,
    val emittedCount: Long = 0,
    /** Wall-clock time of the most recent fix pushed to the OS. */
    val lastFixMillis: Long = 0L,
    val reassertCount: Long = 0,
    val leakSeen: Boolean = false,
    val message: String = "Idle",
    /** What is playing: Route / Flight / Itinerary / Static. */
    val label: String = "",
    val stepLabel: String = "",
)

/** Process-wide status bridge between the service and the UI. */
object MockState {
    private val _status = MutableStateFlow(MockStatus())
    val status: StateFlow<MockStatus> = _status.asStateFlow()

    fun update(block: (MockStatus) -> MockStatus) {
        _status.value = block(_status.value)
    }

    fun reset() {
        _status.value = MockStatus()
    }
}
