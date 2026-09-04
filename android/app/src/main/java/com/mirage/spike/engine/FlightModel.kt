package com.mirage.spike.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class FlightParams(
    val cruiseSpeedMps: Double = 245.0,   // ≈ 550 mph ground speed
    val cruiseAltM: Double = 10_668.0,    // 35,000 ft
    val updateHz: Int = 2,
    val timeScale: Double = 1.0,
)

/**
 * Emulates a point-to-point flight: a great-circle path with a realistic profile —
 * taxi → takeoff → climb → cruise → descent → landing — reporting altitude, speed,
 * and bearing for each phase. Computed locally (no API needed).
 */
class FlightModel(
    private val origin: LatLng,
    private val dest: LatLng,
    private val params: FlightParams = FlightParams(),
) {
    /** Great-circle path for drawing on the map. */
    val pathPoints: List<LatLng> = (0..64).map { Geo.gcInterp(origin, dest, it / 64.0) }
    val totalMeters: Double = Geo.haversine(origin, dest)

    fun fixes(): Flow<Fix> = flow {
        if (totalMeters < 1.0) {
            emit(Fix(dest.lat, dest.lng, 0f, 0f, 5f, 0.0)); return@flow
        }
        val dt = 1.0 / params.updateHz
        val dtMs = (dt * 1000).toLong()
        var dist = 0.0
        while (dist < totalMeters) {
            val f = (dist / totalMeters).coerceIn(0.0, 1.0)
            val (speed, alt) = profile(f)
            val p = Geo.gcInterp(origin, dest, f)
            val ahead = Geo.gcInterp(origin, dest, (f + 0.002).coerceAtMost(1.0))
            val brg = Geo.bearing(p, ahead)
            val ts = params.timeScale * PlaybackSource.timeScale
            val eta = ((totalMeters - dist) / (params.cruiseSpeedMps * ts) * 1.12).toInt()
            emit(Fix(p.lat, p.lng, speed.toFloat(), brg.toFloat(), 5f, alt, progress = f.toFloat(), remainingSec = eta))
            dist += speed * dt * ts
            delay(dtMs)
        }
        val finalBrg = Geo.bearing(pathPoints[pathPoints.size - 2], dest)
        emit(Fix(dest.lat, dest.lng, 0f, finalBrg.toFloat(), 5f, 0.0, progress = 1f, remainingSec = 0))
    }

    /** Speed (m/s) and altitude (m) as a function of progress fraction. */
    private fun profile(f: Double): Pair<Double, Double> {
        val c = params.cruiseSpeedMps
        val a = params.cruiseAltM
        return when {
            f < 0.02 -> lerp(10.0, 80.0, f / 0.02) to 0.0                                  // taxi + takeoff roll
            f < 0.15 -> { val t = (f - 0.02) / 0.13; lerp(80.0, c, t) to lerp(0.0, a, t) } // climb
            f < 0.85 -> c to a                                                              // cruise
            f < 0.98 -> { val t = (f - 0.85) / 0.13; lerp(c, 80.0, t) to lerp(a, 0.0, t) } // descent
            else -> lerp(80.0, 10.0, (f - 0.98) / 0.02) to 0.0                              // landing + taxi
        }
    }

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t.coerceIn(0.0, 1.0)
}
