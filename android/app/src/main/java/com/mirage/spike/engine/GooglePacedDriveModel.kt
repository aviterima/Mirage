package com.mirage.spike.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.ceil
import kotlin.math.min

/**
 * Replays the standard Google duration, not a live-traffic prediction.
 * Step timing already budgets traffic/turns: do not append random red-light waits.
 * The manual mph offset is deliberately ignored in this mode; fast-forward remains live.
 */
class GooglePacedDriveModel(private val route: RouteResult, private val updateHz: Int = 5) {
    private data class Step(val segment: RouteSegment, val cumulative: DoubleArray, val seconds: Double) {
        val meters: Double get() = cumulative.last()
        val average: Double get() = meters / seconds
    }
    private val steps: List<Step>
    init {
        require(updateHz in 1..100)
        val segments = route.segments.filter { it.points.size >= 2 }.ifEmpty {
            if (route.points.size >= 2) listOf(RouteSegment(route.points, route.distanceMeters, route.durationSeconds)) else emptyList()
        }
        val geometry = segments.map { seg ->
            val cumulative = DoubleArray(seg.points.size)
            for (i in 1 until cumulative.size) cumulative[i] = cumulative[i - 1] + Geo.haversine(seg.points[i - 1], seg.points[i])
            seg to cumulative
        }.filter { it.second.last() > 0.001 }
        val weights = geometry.map { (seg, cum) ->
            if (seg.durationSeconds.isFinite() && seg.durationSeconds > 0) seg.durationSeconds
            else cum.last() / (DriveModel.limitMph(seg) * 0.44704)
        }
        val sum = weights.sum()
        steps = geometry.mapIndexed { i, (seg, cum) -> Step(seg, cum, route.durationSeconds * weights[i] / sum) }
    }

    fun fixes(): Flow<Fix> = flow {
        if (steps.isEmpty()) {
            route.points.lastOrNull()?.let { emit(Fix(it.lat, it.lng, 0f, 0f, 4f, progress = 1f, remainingSec = 0)) }
            return@flow
        }
        val duration = steps.sumOf { it.seconds }
        val totalMeters = steps.sumOf { it.meters }
        // Boundary speeds are shared by adjacent steps; lane forks are not turns.
        val boundaries = DoubleArray(steps.size + 1)
        for (i in 1 until steps.size) {
            var speed = min(steps[i - 1].average, steps[i].average)
            if (DriveModel.isTurn(steps[i].segment)) speed = min(speed, 12.0 * 0.44704)
            if (DriveModel.roadKind(steps[i].segment) == DriveModel.Companion.RoadKind.RAMP) speed = min(speed, 35.0 * 0.44704)
            boundaries[i] = speed
        }
        val dt = 1.0 / updateHz
        var elapsed = 0.0
        var index = 0
        var startTime = 0.0
        var startDistance = 0.0
        while (elapsed < duration) {
            if (PlaybackSource.consumeSkip()) break
            while (index < steps.lastIndex && elapsed >= startTime + steps[index].seconds) {
                startTime += steps[index].seconds
                startDistance += steps[index].meters
                index++
            }
            val step = steps[index]
            val t = (elapsed - startTime).coerceIn(0.0, step.seconds)
            val ramp = min(6.0, step.seconds * 0.1)
            val v0 = boundaries[index]; val v1 = boundaries[index + 1]
            // Integral of the trapezoidal speed profile equals the step's geometry length.
            val cruise = (step.meters - ramp * (v0 + v1) * 0.5) / (step.seconds - ramp)
            val distance: Double
            val speed: Double
            if (t < ramp) {
                speed = v0 + (cruise - v0) * t / ramp
                distance = v0 * t + (cruise - v0) * t * t / (2 * ramp)
            } else if (t <= step.seconds - ramp) {
                speed = cruise
                distance = (v0 + cruise) * ramp * 0.5 + cruise * (t - ramp)
            } else {
                val remaining = step.seconds - t
                speed = v1 + (cruise - v1) * remaining / ramp
                distance = step.meters - v1 * remaining - (cruise - v1) * remaining * remaining / (2 * ramp)
            }
            val d = distance.coerceIn(0.0, step.meters)
            var k = 1
            while (k < step.cumulative.lastIndex && step.cumulative[k] < d) k++
            val a = step.segment.points[k - 1]; val b = step.segment.points[k]
            val span = (step.cumulative[k] - step.cumulative[k - 1]).coerceAtLeast(1e-9)
            val f = ((d - step.cumulative[k - 1]) / span).coerceIn(0.0, 1.0)
            val ts = PlaybackSource.timeScale
            emit(Fix(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f,
                speed.toFloat(), Geo.bearing(a, b).toFloat(), 4f,
                progress = ((startDistance + d) / totalMeters).toFloat(),
                remainingSec = ceil((duration - elapsed) / ts).toInt()))
            delay((dt * 1000).toLong())
            elapsed = min(duration, elapsed + dt * ts)
        }
        val end = steps.last().segment.points.last()
        emit(Fix(end.lat, end.lng, 0f, 0f, 4f, progress = 1f, remainingSec = 0))
    }
}
