package com.mirage.spike.engine

/** The pin is selected by the user/geocoder, never an invented room coordinate. */
enum class ArrivalActivity(val label: String) {
    BUILDING("Inside building"), TABLE("Seated at table"), OFFICE("At office"),
    CONFERENCE("In conference room"), OUTDOOR("Stay at route endpoint");
    companion object {
        fun parse(value: String) = entries.firstOrNull { it.name == value } ?: BUILDING
    }
}

/** Short, approximate walking connection from road endpoint to the selected destination pin.
 * No floor plan, entrance, room, or obstacle geometry is inferred. Long gaps require a better pin.
 */
class ArrivalModel(private val start: Fix, private val target: LatLng) {
    private val origin = LatLng(start.lat, start.lng)
    private val distance = Geo.haversine(origin, target)
    val canWalk = target.lat.isFinite() && target.lng.isFinite() && target.lat in -90.0..90.0 &&
        target.lng in -180.0..180.0 && distance.isFinite() && distance <= 150.0
    private var travelled = 0.0
    val complete get() = !canWalk || travelled >= distance
    fun next(dtSeconds: Double): Fix {
        require(dtSeconds.isFinite() && dtSeconds >= 0)
        if(!canWalk) return start.copy(speedMps=0f)
        travelled = (travelled + 1.2 * dtSeconds).coerceAtMost(distance)
        val fraction = if(distance == 0.0) 1.0 else travelled / distance
        // Local interpolation is bounded to 150 m; normalize longitude at the dateline.
        val deltaLng = (target.lng-origin.lng+540.0)%360.0-180.0
        val lng = (origin.lng+deltaLng*fraction+540.0)%360.0-180.0
        return start.copy(lat=if(complete) target.lat else origin.lat+(target.lat-origin.lat)*fraction,
            lng=if(complete) target.lng else lng, speedMps=if(complete) 0f else 1.2f,
            bearingDeg=Geo.bearing(origin,target).toFloat(), accuracyM=8f,
            progress=fraction.toFloat(), remainingSec=kotlin.math.ceil((distance-travelled)/1.2).toInt())
    }
}
