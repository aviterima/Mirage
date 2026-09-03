package com.mirage.spike.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Random
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Geographic helpers (metres / bearings on the sphere). */
object Geo {
    private const val R = 6_371_000.0

    fun haversine(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val la1 = Math.toRadians(a.lat)
        val la2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(la1) * cos(la2) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * R * atan2(sqrt(h), sqrt(1 - h))
    }

    fun bearing(a: LatLng, b: LatLng): Double {
        val la1 = Math.toRadians(a.lat)
        val la2 = Math.toRadians(b.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val y = sin(dLng) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Offset a point by metres north/east (small-distance approximation). */
    fun offset(p: LatLng, north: Double, east: Double): LatLng {
        val dLat = north / 111_320.0
        val dLng = east / (111_320.0 * cos(Math.toRadians(p.lat)))
        return LatLng(p.lat + dLat, p.lng + dLng)
    }

    /** Point at fraction [f] (0..1) along the great-circle arc from [a] to [b]. */
    fun gcInterp(a: LatLng, b: LatLng, f: Double): LatLng {
        val p1 = Math.toRadians(a.lat); val l1 = Math.toRadians(a.lng)
        val p2 = Math.toRadians(b.lat); val l2 = Math.toRadians(b.lng)
        val h = sin((p2 - p1) / 2).pow(2) + cos(p1) * cos(p2) * sin((l2 - l1) / 2).pow(2)
        val d = 2 * asin(min(1.0, sqrt(h)))
        if (d < 1e-9) return a
        val aa = sin((1 - f) * d) / sin(d)
        val bb = sin(f * d) / sin(d)
        val x = aa * cos(p1) * cos(l1) + bb * cos(p2) * cos(l2)
        val y = aa * cos(p1) * sin(l1) + bb * cos(p2) * sin(l2)
        val z = aa * sin(p1) + bb * sin(p2)
        return LatLng(Math.toDegrees(atan2(z, sqrt(x * x + y * y))), Math.toDegrees(atan2(y, x)))
    }
}

private fun clamp(v: Double, lo: Double, hi: Double) = if (v < lo) lo else if (v > hi) hi else v

/**
 * Turns a road-snapped [RouteResult] into a realistic, fixed-cadence stream of [Fix].
 * Speed varies around the target average (mean preserved), accelerates/brakes smoothly,
 * and — for REALISTIC/BUSY — pauses at occasional stops. Bearing follows the path.
 */
class MotionModel(
    private val route: RouteResult,
    private val params: MotionParams,
) {
    private val pts: List<LatLng> = route.points
    private val cum: DoubleArray = DoubleArray(pts.size)
    val totalMeters: Double

    /** Mode-specific motion character: walkers don't brake like cars; cars stop at lights. */
    private class Profile(
        val variance: Double, val busyVariance: Double,
        val stopProb: Double, val busyStopProb: Double,
        val accelMax: Double, val maxFactor: Double,
        val dwellMinMs: Int, val dwellSpanMs: Int,
    )
    private val profile: Profile = when (params.mode) {
        TravelMode.WALK -> Profile(0.06, 0.10, 0.0004, 0.0010, 0.5, 1.25, 1000, 3000)
        TravelMode.BIKE -> Profile(0.10, 0.18, 0.0010, 0.0030, 1.0, 1.4, 1500, 4000)
        else -> Profile(0.12, 0.22, 0.0016, 0.0045, 2.0, 1.8, 2000, 6000)
    }

    init {
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + Geo.haversine(pts[i - 1], pts[i])
        totalMeters = if (pts.isEmpty()) 0.0 else cum[cum.size - 1]
    }

    fun fixes(): Flow<Fix> = flow {
        if (pts.size < 2) return@flow
        val rnd = Random(params.seed ?: System.nanoTime())
        val dt = 1.0 / params.updateHz
        val dtMillis = (dt * 1000).toLong()
        val stopProbPerTick = when (params.realism) {
            Realism.CONSTANT -> 0.0
            Realism.REALISTIC -> profile.stopProb
            Realism.BUSY -> profile.busyStopProb
        }

        var dist = 0.0
        var speed = params.avgSpeedMps
        while (dist < totalMeters) {
            val target = when (params.realism) {
                Realism.CONSTANT -> params.avgSpeedMps
                Realism.REALISTIC -> params.avgSpeedMps * clamp(1.0 + rnd.nextGaussian() * profile.variance, 0.55, 1.4)
                Realism.BUSY -> params.avgSpeedMps * clamp(1.0 + rnd.nextGaussian() * profile.busyVariance, 0.2, 1.4)
            }
            // smooth accel/brake toward target, bounded per mode
            speed += clamp(target - speed, -profile.accelMax, profile.accelMax) * dt * 3.0
            speed = clamp(speed, 0.0, params.avgSpeedMps * profile.maxFactor)

            dist += speed * dt * params.timeScale
            val at = clamp(dist, 0.0, totalMeters)
            val (p, brg) = interpolate(at)
            val prog = (at / totalMeters).toFloat()
            val eta = ((totalMeters - at) / (params.avgSpeedMps * params.timeScale)).toInt()
            emit(Fix(p.lat, p.lng, speed.toFloat(), brg.toFloat(), params.accuracyMeters, progress = prog, remainingSec = eta))

            // occasional realistic stop (traffic light / congestion)
            if (rnd.nextDouble() < stopProbPerTick) {
                val dwellMs = ((profile.dwellMinMs + rnd.nextInt(profile.dwellSpanMs)) / params.timeScale).toInt()
                var elapsed = 0
                while (elapsed < dwellMs) {
                    emit(Fix(p.lat, p.lng, 0f, brg.toFloat(), params.accuracyMeters, progress = prog, remainingSec = eta))
                    delay(dtMillis)
                    elapsed += dtMillis.toInt()
                }
                speed = 0.0
            }
            delay(dtMillis)
        }
        // final point
        val end = pts[pts.size - 1]
        val brgEnd = Geo.bearing(pts[pts.size - 2], end)
        emit(Fix(end.lat, end.lng, 0f, brgEnd.toFloat(), params.accuracyMeters, progress = 1f, remainingSec = 0))
    }

    /** Point + bearing at [d] metres along the route. */
    private fun interpolate(d: Double): Pair<LatLng, Double> {
        var i = 1
        while (i < cum.size && cum[i] < d) i++
        if (i >= pts.size) {
            val a = pts[pts.size - 2]; val b = pts[pts.size - 1]
            return b to Geo.bearing(a, b)
        }
        val a = pts[i - 1]; val b = pts[i]
        val segLen = (cum[i] - cum[i - 1]).coerceAtLeast(1e-6)
        val t = ((d - cum[i - 1]) / segLen).coerceIn(0.0, 1.0)
        val p = LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
        return p to Geo.bearing(a, b)
    }
}

/**
 * Process-wide handoff of the currently-armed playback stream from the UI to the
 * foreground service. The UI builds the route + motion model, sets [current], then
 * starts the service, which collects it. Null = static hold at the start point.
 */
object PlaybackSource {
    @Volatile var current: Flow<Fix>? = null
    @Volatile var routePoints: List<LatLng> = emptyList()
    @Volatile var label: String = ""
}
