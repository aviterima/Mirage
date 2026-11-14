package com.mirage.spike.recording

import com.mirage.spike.engine.Fix
import com.mirage.spike.engine.Geo
import com.mirage.spike.engine.LatLng
import com.mirage.spike.engine.PlaybackSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

enum class TrackSource { REAL, EMULATED }
enum class TrackState { ACTIVE, SAVED, INTERRUPTED }

data class TrackSample(val offsetMillis: Long, val wallTimeMillis: Long, val fix: Fix, val gapBefore: Boolean = false)
data class TrackGap(val offsetMillis: Long, val reason: String)
data class TrackSummary(
    val id: String,
    val name: String,
    val source: TrackSource,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val durationMillis: Long,
    val sampleCount: Int,
    val state: TrackState,
    val gapCount: Int = 0,
)

object RecordingPolicy {
    const val MOVING_INTERVAL_MILLIS = 1_000L
    const val STATIONARY_INTERVAL_MILLIS = 30_000L
    const val CONTINUITY_MILLIS = 15_000L
    const val MAX_DURATION_MILLIS = 48L * 60 * 60 * 1_000
    const val MAX_SAMPLES = 180_000
    const val MAX_TRACK_BYTES = 64L * 1024 * 1024

    fun acceptReal(isMock: Boolean, simulationActive: Boolean, fixElapsedMillis: Long, nowElapsedMillis: Long): Boolean =
        !isMock && !simulationActive && fixElapsedMillis >= 0 && nowElapsedMillis >= fixElapsedMillis &&
            nowElapsedMillis - fixElapsedMillis <= 10_000L

    fun suggestedName(source: TrackSource, startedAtMillis: Long): String =
        "${if (source == TrackSource.REAL) "Real journey" else "Emulated day"} · " +
            SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()).format(Date(startedAtMillis))

    fun valid(fix: Fix): Boolean = fix.lat.isFinite() && fix.lat in -90.0..90.0 &&
        fix.lng.isFinite() && fix.lng in -180.0..180.0 && fix.accuracyM.isFinite() && fix.accuracyM >= 0 &&
        fix.speedMps.isFinite() && fix.speedMps >= 0 && fix.bearingDeg.isFinite() && fix.altitudeM.isFinite()

    internal fun stationary(a: Fix, b: Fix): Boolean =
        a.speedMps < 0.5f && b.speedMps < 0.5f && Geo.haversine(LatLng(a.lat, a.lng), LatLng(b.lat, b.lng)) < 3.0
}

data class RecordedTrack(val summary: TrackSummary, val samples: List<TrackSample>, val gaps: List<TrackGap> = emptyList()) {
    init {
        require(samples.all { it.offsetMillis >= 0 && RecordingPolicy.valid(it.fix) }) { "Invalid recording sample" }
        require(samples.zipWithNext().all { (a, b) -> b.offsetMillis > a.offsetMillis }) { "Recording times must increase" }
        require(gaps.all { it.offsetMillis >= 0 }) { "Invalid recording gap" }
    }

    /** A gap holds the last observed point; it never draws invented movement between observations. */
    fun fixes(
        timeScale: () -> Double = { PlaybackSource.timeScale },
        paused: () -> Boolean = { PlaybackSource.paused },
        onGap: (Boolean) -> Unit = {},
    ): Flow<Fix> = flow {
        require(samples.isNotEmpty()) { "This recording has no locations to replay" }
        var elapsed = samples.first().offsetMillis.toDouble()
        val end = maxOf(samples.last().offsetMillis, summary.durationMillis).toDouble()
        var index = 0
        var previousGap: Boolean? = null
        try {
            while (true) {
                while (index < samples.lastIndex && samples[index + 1].offsetMillis <= elapsed) index++
                val a = samples[index]
                val b = samples.getOrNull(index + 1)
                val explicitGap = gaps.any { it.offsetMillis >= a.offsetMillis && it.offsetMillis <= elapsed &&
                    (b == null || it.offsetMillis < b.offsetMillis) }
                val discontinuity = b != null && (b.gapBefore ||
                    (b.offsetMillis - a.offsetMillis > RecordingPolicy.CONTINUITY_MILLIS && !RecordingPolicy.stationary(a.fix, b.fix)))
                val gap = explicitGap || discontinuity || (b == null && elapsed > a.offsetMillis + RecordingPolicy.CONTINUITY_MILLIS)
                if (previousGap != gap) { onGap(gap); previousGap = gap }
                val fix = when {
                    b == null || gap -> a.fix.copy(speedMps = 0f)
                    b.offsetMillis - a.offsetMillis > RecordingPolicy.CONTINUITY_MILLIS -> a.fix.copy(speedMps = 0f)
                    else -> interpolate(a, b, elapsed)
                }
                val frozen = paused()
                val scale = timeScale().takeIf { it.isFinite() && it > 0 }?.coerceIn(0.1, 1000.0) ?: 1.0
                emit(fix.copy(speedMps = if (frozen) 0f else fix.speedMps,
                    progress = if (end <= 0) 1f else (elapsed / end).toFloat().coerceIn(0f, 1f),
                    remainingSec = ((end - elapsed) / (1000 * scale)).toInt().coerceAtLeast(0), preserveCoordinates = true))
                if (elapsed >= end) break
                delay(200)
                // The service may suspend this collector while paused. Advance only after
                // an actual unpaused tick, never by wall time spent inside emit().
                if (!frozen && !paused()) elapsed = minOf(end, elapsed + 200 * scale)
            }
        } finally { if (previousGap == true) onGap(false) }
    }

    private fun interpolate(a: TrackSample, b: TrackSample, elapsed: Double): Fix {
        val fraction = ((elapsed - a.offsetMillis) / (b.offsetMillis - a.offsetMillis)).coerceIn(0.0, 1.0)
        // Shortest-longitude interpolation also handles the date line without jumping continents.
        var longitudeDelta = b.fix.lng - a.fix.lng
        if (longitudeDelta > 180) longitudeDelta -= 360
        if (longitudeDelta < -180) longitudeDelta += 360
        var longitude = a.fix.lng + longitudeDelta * fraction
        if (longitude > 180) longitude -= 360
        if (longitude < -180) longitude += 360
        var bearingDelta = (b.fix.bearingDeg - a.fix.bearingDeg).toDouble()
        if (bearingDelta > 180) bearingDelta -= 360
        if (bearingDelta < -180) bearingDelta += 360
        return a.fix.copy(lat = a.fix.lat + (b.fix.lat - a.fix.lat) * fraction, lng = longitude,
            speedMps = (a.fix.speedMps + (b.fix.speedMps - a.fix.speedMps) * fraction).toFloat(),
            bearingDeg = ((a.fix.bearingDeg + bearingDelta * fraction + 360) % 360).toFloat(),
            accuracyM = maxOf(a.fix.accuracyM, b.fix.accuracyM),
            altitudeM = a.fix.altitudeM + (b.fix.altitudeM - a.fix.altitudeM) * fraction)
    }
}
