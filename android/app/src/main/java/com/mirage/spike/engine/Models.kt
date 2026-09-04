package com.mirage.spike.engine

/** Core domain models shared by the routing/motion engine, service, and UI. */

data class LatLng(val lat: Double, val lng: Double)

enum class TravelMode(val apiValue: String) {
    DRIVE("driving"),
    BIKE("bicycling"),
    WALK("walking"),
    /** Real public transport from Google Directions: walk, wait, ride on the actual schedule. */
    TRANSIT("transit"),
    /** Emulated point-to-point flight (great circle); no routing API involved. */
    FLY("flight"),
}

enum class TransitVehicle(val label: String) {
    BUS("Bus"), SUBWAY("Subway"), TRAIN("Train"), TRAM("Tram"), RAIL("Rail"), FERRY("Ferry"), OTHER("Transit");

    companion object {
        /** Map Google's vehicle type strings onto our families. */
        fun from(type: String?): TransitVehicle = when (type?.uppercase()) {
            "BUS", "INTERCITY_BUS", "TROLLEYBUS", "SHARE_TAXI" -> BUS
            "SUBWAY", "METRO_RAIL" -> SUBWAY
            "HEAVY_RAIL", "COMMUTER_TRAIN", "HIGH_SPEED_TRAIN", "LONG_DISTANCE_TRAIN" -> TRAIN
            "RAIL" -> RAIL
            "TRAM", "MONORAIL", "CABLE_CAR", "FUNICULAR", "GONDOLA_LIFT" -> TRAM
            "FERRY" -> FERRY
            else -> OTHER
        }
    }
}

/** One scheduled ride: which line, between which stops, when. */
data class TransitDetails(
    val vehicle: TransitVehicle,
    val line: String,
    val headsign: String,
    val fromStop: String,
    val toStop: String,
    val departureEpoch: Long,
    val arrivalEpoch: Long,
    val departureText: String,
    val arrivalText: String,
    val numStops: Int,
)

/** A piece of a transit trip: a walk (transit == null) or a ride. */
data class RouteSegment(
    val points: List<LatLng>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val transit: TransitDetails? = null,
)

enum class Realism { CONSTANT, REALISTIC, BUSY }

data class RouteSpec(
    val origin: LatLng,
    val destination: LatLng,
    val waypoints: List<LatLng> = emptyList(),
    val mode: TravelMode = TravelMode.DRIVE,
    /** Transit only: Directions transit_mode filter (bus, subway, train, tram, rail) or null for any. */
    val transitPreference: String? = null,
)

/** Road-snapped result from a routing provider. */
data class RouteResult(
    val points: List<LatLng>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    /** Transit only: the walk/ride segments with their schedule. */
    val segments: List<RouteSegment> = emptyList(),
)

data class MotionParams(
    val avgSpeedMps: Double,
    val realism: Realism = Realism.REALISTIC,
    val mode: TravelMode = TravelMode.DRIVE,
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
    /** 0..1 progress along the current leg, or -1 when not applicable (holding). */
    val progress: Float = -1f,
    /** Estimated seconds to the end of the current leg, or -1 when unknown. */
    val remainingSec: Int = -1,
)
