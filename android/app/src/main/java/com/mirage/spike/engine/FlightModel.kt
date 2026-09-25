package com.mirage.spike.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.ceil
import kotlin.math.min

data class FlightParams(
    val cruiseSpeedMps: Double = 245.0,
    val cruiseAltM: Double = 10_668.0,
    val updateHz: Int = 2,
    val timeScale: Double = 1.0,
)

/**
 * Generic jet simulation, not a prediction for an actual airline flight.
 * Timed ground/climb/descent phases follow a great circle, not airport taxiways or
 * assigned airways. No wind, aircraft-specific performance or airport elevation data.
 */
class FlightModel(
    private val origin: LatLng,
    private val dest: LatLng,
    private val params: FlightParams = FlightParams(),
) {
    init {
        require(params.cruiseSpeedMps.isFinite() && params.cruiseSpeedMps > 7.0)
        require(params.cruiseAltM.isFinite() && params.cruiseAltM >= 0.0)
        require(params.updateHz in 1..100)
        require(params.timeScale.isFinite() && params.timeScale > 0.0)
    }

    val pathPoints: List<LatLng> = (0..64).map { Geo.gcInterp(origin, dest, it / 64.0) }
    val totalMeters: Double = Geo.haversine(origin, dest)

    private data class Phase(val seconds: Double, val v0: Double, val v1: Double, val h0: Double, val h1: Double) {
        val meters: Double get() = (v0 + v1) * 0.5 * seconds
        fun distanceAt(t: Double): Double = v0 * t + (v1 - v0) * t * t / (2.0 * seconds)
    }

    private fun phases(): List<Phase> {
        if (totalMeters < 1.0) return emptyList()
        val taxiSpeed = 7.0
        // Two minutes at either end, capped for very short synthetic trips.
        val taxiSeconds = min(120.0, totalMeters / (40.0 * taxiSpeed))
        val taxiOut = Phase(taxiSeconds, 0.0, taxiSpeed, 0.0, 0.0)
        val taxiIn = Phase(taxiSeconds, taxiSpeed, 0.0, 0.0, 0.0)
        val available = totalMeters - taxiOut.meters - taxiIn.meters
        fun airborne(factor: Double): List<Phase> {
            val takeoff = taxiSpeed + (min(80.0, params.cruiseSpeedMps) - taxiSpeed) * factor
            val cruise = taxiSpeed + (params.cruiseSpeedMps - taxiSpeed) * factor
            val altitude = params.cruiseAltM * factor * factor
            return listOf(
                Phase(35.0 * factor, taxiSpeed, takeoff, 0.0, 0.0),
                Phase(900.0 * factor, takeoff, cruise, 0.0, altitude),
                Phase(900.0 * factor, cruise, takeoff, altitude, 0.0),
                Phase(35.0 * factor, takeoff, taxiSpeed, 0.0, 0.0),
            )
        }
        // On short trips reduce both peak speed/altitude and phase duration.
        var factor = 1.0
        if (airborne(1.0).sumOf { it.meters } > available) {
            var lo = 0.0; var hi = 1.0
            repeat(60) {
                val mid = (lo + hi) / 2.0
                if (airborne(mid).sumOf { it.meters } > available) hi = mid else lo = mid
            }
            factor = (lo + hi) / 2.0
        }
        val air = airborne(factor)
        val cruiseMeters = (available - air.sumOf { it.meters }).coerceAtLeast(0.0)
        return buildList {
            add(taxiOut)
            add(air[0]); add(air[1])
            if (cruiseMeters > 1e-6) add(Phase(cruiseMeters / air[1].v1, air[1].v1, air[1].v1, air[1].h1, air[1].h1))
            add(air[2]); add(air[3])
            add(taxiIn)
        }
    }

    private val schedule = phases()
    val durationSeconds: Double = schedule.sumOf { it.seconds }

    private fun sample(elapsed: Double): Triple<Double, Double, Double> {
        var remaining = elapsed
        var distance = 0.0
        for (phase in schedule) {
            if (remaining < phase.seconds) {
                val fraction = remaining / phase.seconds
                return Triple(
                    distance + phase.distanceAt(remaining),
                    phase.v0 + (phase.v1 - phase.v0) * fraction,
                    phase.h0 + (phase.h1 - phase.h0) * fraction,
                )
            }
            remaining -= phase.seconds
            distance += phase.meters
        }
        return Triple(totalMeters, 0.0, 0.0)
    }

    fun fixes(): Flow<Fix> = flow {
        val dt = 1.0 / params.updateHz
        val dtMs = (dt * 1000).toLong()
        var elapsed = 0.0
        while (elapsed < durationSeconds) {
            if (PlaybackSource.consumeSkip()) break
            val (distance, speed, altitude) = sample(elapsed)
            val fraction = (distance / totalMeters).coerceIn(0.0, 1.0)
            val p = Geo.gcInterp(origin, dest, fraction)
            val ahead = Geo.gcInterp(origin, dest, (fraction + 0.002).coerceAtMost(1.0))
            val ts = params.timeScale * PlaybackSource.timeScale
            val eta = ceil((durationSeconds - elapsed) / ts).toInt()
            emit(Fix(p.lat, p.lng, speed.toFloat(), Geo.bearing(p, ahead).toFloat(), 5f, altitude,
                progress = fraction.toFloat(), remainingSec = eta))
            delay(dtMs)
            elapsed = min(durationSeconds, elapsed + dt * ts)
        }
        val bearing = if (totalMeters < 1.0) 0.0 else Geo.bearing(pathPoints[pathPoints.size - 2], dest)
        emit(Fix(dest.lat, dest.lng, 0f, bearing.toFloat(), 5f, 0.0, progress = 1f, remainingSec = 0))
    }
}
