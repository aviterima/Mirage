package com.mirage.spike.engine

/** Core domain models shared by the routing/motion engine, service, and UI. */

data class LatLng(val lat: Double, val lng: Double)

enum class TravelMode(val apiValue: String) {
    DRIVE("driving"),
    BIKE("bicycling"),
    WALK("walking"),
    /** Emulated point-to-point flight (great circle); no routing API involved. */
    FLY("flight"),
}

enum class Realism { CONSTANT, REALISTIC, BUSY }

data class RouteSpec(
    val origin: LatLng,
    val destination: LatLng,
    val waypoints: List<LatLng> = emptyList(),
    val mode: TravelMode = TravelMode.DRIVE,
)

/** Road-snapped result from a routing provider. */
data class RouteResult(
    val points: List<LatLng>,
    val distanceMeters: Double,
    val durationSeconds: Double,
)

data class MotionParams(
    val avgSpeedMps: Double,
    val realism: Realism = Realism.REALISTIC,
    val accuracyMeters: Float = 4f,
    val updateHz: Int = 5,
    val seed: Long? = null,
    val timeScale: Double = 1.0,
)

/** A single synthetic GPS sample emitted to the OS. */
data class Fix(
    val lat: Double,
    val lng: Double,
    val speedMps: Float,
    val bearingDeg: Float,
    val accuracyM: Float,
    val altitudeM: Double = 12.0,
)
