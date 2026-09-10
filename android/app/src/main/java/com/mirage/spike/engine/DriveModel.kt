package com.mirage.spike.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Drives a Google Directions route the way a real car does: each step (road) gets a
 * posted speed limit estimated from its road class and Google's own timing, the car
 * cruises at limit + [PlaybackSource.speedOverLimitMph] (live), slows for turns and ramps,
 * and stops at traffic lights at intersections on surface streets — never on freeways.
 *
 * Google's actual speed-limit data is only sold to enterprise asset-tracking accounts, so
 * limits are inferred: freeway ≥ 55 → 65, arterial → 45, collector → 35, local → 30/25.
 */
class DriveModel(
    private val route: RouteResult,
    private val realism: Realism = Realism.REALISTIC,
    private val updateHz: Int = 5,
    private val seed: Long? = null,
) {
    /** Posted limit (mph) for a step. */
    companion object {
        private val FREEWAY = Regex("\\b(I-\\d|Interstate|Fwy|Freeway|Hwy|Highway|US-\\d|Loop \\d|Expressway|Tollway|Turnpike|Pkwy|Parkway)\\b", RegexOption.IGNORE_CASE)
        private val RAMP = Regex("\\b(ramp|merge|exit)\\b", RegexOption.IGNORE_CASE)

        fun limitMph(seg: RouteSegment): Int {
            val avgMph = if (seg.durationSeconds > 0) seg.distanceMeters / seg.durationSeconds / 0.44704 else 30.0
            val freewayName = FREEWAY.containsMatchIn(seg.instruction)
            val ramp = RAMP.containsMatchIn(seg.instruction) || seg.maneuver.contains("ramp") || seg.maneuver.contains("merge")
            return when {
                ramp -> 35
                freewayName && avgMph >= 40 -> 65
                avgMph >= 52 -> 65
                avgMph >= 38 -> 45
                avgMph >= 28 -> 35
                avgMph >= 20 -> 30
                else -> 25
            }
        }

        fun isTurn(seg: RouteSegment): Boolean =
            seg.maneuver.startsWith("turn") || seg.maneuver.contains("uturn") ||
                Regex("\\b(Turn|left|right)\\b", RegexOption.IGNORE_CASE).containsMatchIn(seg.instruction)
    }

    /** Segments with geometry (Google occasionally emits zero-length steps). */
    private val steps: List<RouteSegment> = route.segments.filter { it.points.size >= 2 }

    fun fixes(): Flow<Fix> = flow {
        val rnd = Random(seed ?: System.nanoTime())
        val dt = 1.0 / updateHz
        val dtMs = (dt * 1000).toLong()
        val accel = 2.0          // m/s²
        val brake = 2.6
        // Light probability per intersection on surface streets, by realism.
        val lightProb = when (realism) { Realism.CONSTANT -> 0.0; Realism.REALISTIC -> 0.45; Realism.BUSY -> 0.7 }
        val midBlockPerMeter = when (realism) { Realism.CONSTANT -> 0.0; Realism.REALISTIC -> 1.0 / 700.0; Realism.BUSY -> 1.0 / 450.0 }

        val totalAll = steps.sumOf { it.distanceMeters }.coerceAtLeast(1.0)
        var doneBefore = 0.0
        var speed = 0.0
        var skipAll = false

        for ((i, seg) in steps.withIndex()) {
            if (skipAll) break
            val pts = seg.points
            val cum = DoubleArray(pts.size)
            for (k in 1 until pts.size) cum[k] = cum[k - 1] + Geo.haversine(pts[k - 1], pts[k])
            val len = cum.last().coerceAtLeast(1.0)
            val limit = limitMph(seg)
            val surface = limit <= 45
            val next = steps.getOrNull(i + 1)
            // Speed we must be down to at the end of this step (a turn ahead, a ramp, or the finish).
            val endSpeedMph = when {
                next == null -> 0.0
                isTurn(next) -> 12.0
                next.maneuver.contains("ramp") || next.maneuver.contains("merge") -> 35.0
                else -> min(limit, limitMph(next)).toDouble()
            }
            // A traffic light at the intersection that starts this step (surface streets only).
            val lightAtStart = i > 0 && surface && rnd.nextDouble() < lightProb
            var nextMidLight = if (surface && midBlockPerMeter > 0) 150.0 + rnd.nextDouble() / midBlockPerMeter else Double.MAX_VALUE

            var dist = 0.0
            if (lightAtStart) {
                val p0 = pts[0]; val b0 = Geo.bearing(pts[0], pts[1])
                halt(rnd, p0, b0, 8.0 + rnd.nextDouble() * 40.0, dtMs, doneBefore / totalAll)
                speed = 0.0
            }
            while (dist < len) {
                if (PlaybackSource.consumeSkip()) { skipAll = true; break }
                val ts = PlaybackSource.timeScale
                val cruiseMph = (limit + PlaybackSource.speedOverLimitMph).coerceAtLeast(5.0)
                val noise = when (realism) { Realism.CONSTANT -> 1.0; else -> 1.0 + rnd.nextGaussian() * 0.03 }
                val cruise = cruiseMph * 0.44704 * noise
                val remaining = len - dist
                val endSpeed = endSpeedMph * 0.44704
                // Never exceed what we can brake down to by the end of the step.
                val brakeCap = sqrt(endSpeed * endSpeed + 2 * brake * remaining)
                val target = min(cruise, brakeCap)
                speed = if (speed < target) min(target, speed + accel * dt * ts) else max(target, speed - brake * dt * ts)
                dist = min(len, dist + speed * dt * ts)
                val (p, brg) = at(pts, cum, dist)
                val overall = ((doneBefore + dist) / totalAll).toFloat()
                val etaSec = ((totalAll - doneBefore - dist) / max(8.0, speed) / ts).toInt()
                emit(Fix(p.lat, p.lng, speed.toFloat(), brg.toFloat(), 4f, progress = overall, remainingSec = etaSec))
                delay(dtMs)
                if (dist >= nextMidLight && dist < len - 20) {
                    // A mid-block light: brake to a stop, wait, go.
                    halt(rnd, p, brg, 8.0 + rnd.nextDouble() * 35.0, dtMs, overall.toDouble())
                    speed = 0.0
                    nextMidLight = dist + 150.0 + rnd.nextDouble() / midBlockPerMeter
                }
            }
            doneBefore += len
        }
        val end = route.points.lastOrNull() ?: steps.lastOrNull()?.points?.lastOrNull() ?: return@flow
        val prev = route.points.getOrNull(route.points.size - 2) ?: end
        emit(Fix(end.lat, end.lng, 0f, Geo.bearing(prev, end).toFloat(), 4f, progress = 1f, remainingSec = 0))
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<Fix>.halt(
        rnd: Random, p: LatLng, brg: Double, seconds: Double, dtMs: Long, progress: Double,
    ) {
        var waited = 0.0
        while (waited < seconds) {
            if (PlaybackSource.consumeSkip()) { PlaybackSource.requestSkip(); return }
            val ts = PlaybackSource.timeScale
            emit(Fix(p.lat, p.lng, 0f, brg.toFloat(), 4f, progress = progress.toFloat(), remainingSec = -1))
            delay(dtMs)
            waited += dtMs / 1000.0 * ts
        }
        @Suppress("UNUSED_VARIABLE") val u = rnd
    }

    private fun at(pts: List<LatLng>, cum: DoubleArray, d: Double): Pair<LatLng, Double> {
        var i = 1
        while (i < cum.size && cum[i] < d) i++
        if (i >= pts.size) return pts.last() to Geo.bearing(pts[pts.size - 2], pts.last())
        val a = pts[i - 1]; val b = pts[i]
        val segLen = (cum[i] - cum[i - 1]).coerceAtLeast(1e-6)
        val t = ((d - cum[i - 1]) / segLen).coerceIn(0.0, 1.0)
        return LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t) to Geo.bearing(a, b)
    }
}
