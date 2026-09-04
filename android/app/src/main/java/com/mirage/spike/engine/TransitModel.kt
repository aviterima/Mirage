package com.mirage.spike.engine

import com.mirage.spike.MockState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Random
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Plays a real public-transport trip from Google Directions the way it actually happens:
 * walk to the stop, WAIT on the platform until the scheduled departure, ride the line with
 * vehicle-appropriate acceleration and a halt at every intermediate station, arrive on the
 * scheduled time, walk to the next stop or the destination.
 *
 * The simulated clock starts at [now] and advances with the live fast-forward factor, so
 * a 20-minute wait is 20 real minutes at 1× and 2 minutes at 10×.
 */
class TransitModel(
    private val route: RouteResult,
    private val updateHz: Int = 5,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    /** Acceleration (m/s²), top speed (m/s), halt at a station (s). */
    private class Vehicle(val accel: Double, val vmax: Double, val haltSec: Double)

    private fun vehicle(v: TransitVehicle) = when (v) {
        TransitVehicle.BUS -> Vehicle(1.0, 18.0, 15.0)
        TransitVehicle.SUBWAY -> Vehicle(1.1, 28.0, 25.0)
        TransitVehicle.TRAIN -> Vehicle(0.7, 45.0, 45.0)
        TransitVehicle.RAIL -> Vehicle(0.7, 40.0, 40.0)
        TransitVehicle.TRAM -> Vehicle(1.0, 16.0, 20.0)
        TransitVehicle.FERRY -> Vehicle(0.25, 9.0, 120.0)
        TransitVehicle.OTHER -> Vehicle(0.8, 20.0, 20.0)
    }

    fun fixes(): Flow<Fix> = flow {
        val dt = 1.0 / updateHz
        val dtMs = (dt * 1000).toLong()
        val rnd = Random()
        var simNow = now().toDouble()
        var last: Fix? = null
        val segs = route.segments

        for ((idx, seg) in segs.withIndex()) {
            val ts0 = PlaybackSource.timeScale
            val td = seg.transit
            if (td == null) {
                // ---- Walk ----
                if (seg.points.size < 2) continue
                val next = segs.getOrNull(idx + 1)?.transit?.fromStop
                MockState.update { it.copy(stepLabel = if (next != null) "Walking to $next" else "Walking to the destination") }
                val walkSpeed = if (seg.durationSeconds > 0) (seg.distanceMeters / seg.durationSeconds).coerceIn(0.8, 1.8) else 1.3
                val model = MotionModel(
                    RouteResult(seg.points, seg.distanceMeters, seg.durationSeconds),
                    MotionParams(avgSpeedMps = walkSpeed, realism = Realism.REALISTIC, mode = TravelMode.WALK, updateHz = updateHz),
                )
                model.fixes().collect { f -> last = f; emit(f); simNow += dt * PlaybackSource.timeScale }
                continue
            }

            // ---- Wait on the platform until the scheduled departure ----
            val platform = seg.points.firstOrNull() ?: last?.let { LatLng(it.lat, it.lng) } ?: continue
            val anchor = Fix(platform.lat, platform.lng, 0f, last?.bearingDeg ?: 0f, 5f)
            val dwell = DwellModel(anchor, 4.0, rnd)
            val waitTotal = (td.departureEpoch - simNow).coerceAtLeast(0.0)
            MockState.update {
                it.copy(stepLabel = "Waiting at ${td.fromStop} · ${td.line} ${td.vehicle.label.lowercase()} departs ${td.departureText}")
            }
            while (simNow < td.departureEpoch) {
                val ts = PlaybackSource.timeScale
                val remaining = td.departureEpoch - simNow
                val prog = if (waitTotal > 0) (1.0 - remaining / waitTotal).toFloat() else 1f
                val f = dwell.next(dt * ts).copy(progress = prog, remainingSec = (remaining / ts).toInt())
                last = f; emit(f)
                delay(dtMs)
                simNow += dt * ts
            }

            // ---- Ride ----
            val ride = seg.points
            if (ride.size < 2) { simNow = maxOf(simNow, td.arrivalEpoch.toDouble()); continue }
            val v = vehicle(td.vehicle)
            val cum = DoubleArray(ride.size)
            for (i in 1 until ride.size) cum[i] = cum[i - 1] + Geo.haversine(ride[i - 1], ride[i])
            val total = cum.last().coerceAtLeast(1.0)
            val stops = td.numStops.coerceAtLeast(1)                 // rides between halts
            val scheduled = (td.arrivalEpoch - td.departureEpoch).toDouble().coerceAtLeast(30.0)
            val moving = (scheduled - v.haltSec * (stops - 1)).coerceAtLeast(scheduled * 0.5)
            val hopLen = total / stops
            val hopBudget = moving / stops
            // Cruise speed that makes one hop take its budget with symmetric accel/decel; capped.
            val disc = v.accel * v.accel * hopBudget * hopBudget - 4 * v.accel * hopLen
            val cruise = (if (disc >= 0) (v.accel * hopBudget - sqrt(disc)) / 2 else sqrt(v.accel * hopLen))
                .coerceIn(2.0, v.vmax)
            MockState.update {
                it.copy(stepLabel = "Riding ${td.line} ${td.vehicle.label.lowercase()} to ${td.toStop} · ${td.numStops} ${if (td.numStops == 1) "stop" else "stops"} · arrives ${td.arrivalText}")
            }
            var dist = 0.0
            var hop = 0
            val rideStartSim = simNow
            val rideTotalSim = maxOf(scheduled, 1.0)
            while (dist < total - 0.5) {
                val ts = PlaybackSource.timeScale
                val hopStart = hop * hopLen
                val hopEnd = ((hop + 1) * hopLen).coerceAtMost(total)
                val s = dist - hopStart
                val remainHop = hopEnd - dist
                // Speed as a function of position: accelerate, cruise, brake into the halt.
                val speed = min(min(sqrt(2 * v.accel * (s + 0.5)), cruise), sqrt(2 * v.accel * (remainHop + 0.2)))
                dist = (dist + speed * dt * ts).coerceAtMost(total)
                val (p, brg) = pointAt(ride, cum, dist)
                val prog = ((simNow - rideStartSim) / rideTotalSim).coerceIn(0.0, 0.999).toFloat()
                val f = Fix(p.lat, p.lng, speed.toFloat(), brg.toFloat(), 8f, progress = prog,
                    remainingSec = ((td.arrivalEpoch - simNow) / ts).coerceAtLeast(0.0).toInt())
                last = f; emit(f)
                delay(dtMs)
                simNow += dt * ts
                if (dist >= hopEnd - 0.5 && hop < stops - 1) {
                    // Halt at an intermediate station.
                    hop++
                    var halted = 0.0
                    val haltFor = v.haltSec * (0.8 + rnd.nextDouble() * 0.4)
                    while (halted < haltFor) {
                        val ts2 = PlaybackSource.timeScale
                        val hf = Fix(p.lat, p.lng, 0f, brg.toFloat(), 8f, progress = prog,
                            remainingSec = ((td.arrivalEpoch - simNow) / ts2).coerceAtLeast(0.0).toInt())
                        last = hf; emit(hf)
                        delay(dtMs)
                        halted += dt * ts2; simNow += dt * ts2
                    }
                }
            }
            // The timetable wins: we are at the arrival stop at the scheduled time.
            simNow = maxOf(simNow, td.arrivalEpoch.toDouble())
            val end = ride.last()
            val f = Fix(end.lat, end.lng, 0f, last?.bearingDeg ?: 0f, 6f, progress = 1f, remainingSec = 0)
            last = f; emit(f)
            @Suppress("UNUSED_VARIABLE") val unused = ts0
        }
        // Final point of the trip.
        val endPt = route.points.lastOrNull() ?: segs.lastOrNull()?.points?.lastOrNull()
        if (endPt != null) emit(Fix(endPt.lat, endPt.lng, 0f, last?.bearingDeg ?: 0f, 5f, progress = 1f, remainingSec = 0))
    }

    private fun pointAt(pts: List<LatLng>, cum: DoubleArray, d: Double): Pair<LatLng, Double> {
        var i = 1
        while (i < cum.size && cum[i] < d) i++
        if (i >= pts.size) return pts.last() to Geo.bearing(pts[pts.size - 2], pts.last())
        val a = pts[i - 1]; val b = pts[i]
        val segLen = (cum[i] - cum[i - 1]).coerceAtLeast(1e-6)
        val t = ((d - cum[i - 1]) / segLen).coerceIn(0.0, 1.0)
        return LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t) to Geo.bearing(a, b)
    }
}
