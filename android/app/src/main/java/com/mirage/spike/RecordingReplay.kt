package com.mirage.spike

import com.mirage.spike.engine.*
import com.mirage.spike.recording.RecordedTrack
import kotlinx.coroutines.flow.Flow

/** The service launch is synchronous on Main; a rejected launch restores the existing playback. */
internal fun armRecordingReplay(recording: RecordedTrack, speed: Double, startService: () -> Unit) {
    require(recording.samples.isNotEmpty()) { "No locations were recorded" }
    val oldFlow = PlaybackSource.current
    val oldPoints = PlaybackSource.routePoints
    val oldEnd = PlaybackSource.endPoint
    val oldLabel = PlaybackSource.label
    val oldSpeed = PlaybackSource.timeScale
    val oldPaused = PlaybackSource.paused
    lateinit var replay: Flow<Fix>
    replay = recording.fixes(onGap = { gap ->
        // A cancelled earlier replay must not rewrite a newer session's status.
        if (PlaybackSource.current === replay && MockState.status.value.let { it.running || it.starting }) {
            MockState.update { it.copy(stepLabel = if (gap) "Recording gap · holding last location" else "Replaying ${recording.summary.name}") }
        }
    })
    try {
        PlaybackSource.current = replay
        PlaybackSource.timeScale = speed
        PlaybackSource.paused = false
        // Avoid connecting unknown sections with a misleading line on the map.
        PlaybackSource.routePoints = listOf(recording.samples.first().fix.let { LatLng(it.lat, it.lng) })
        PlaybackSource.endPoint = recording.samples.last().fix.let { LatLng(it.lat, it.lng) }
        PlaybackSource.label = "Replay · ${recording.summary.name}"
        startService()
        PlaybackSource.clearQueue()
        PlaybackSource.consumeSkip()
    } catch (e: Exception) {
        PlaybackSource.current = oldFlow
        PlaybackSource.routePoints = oldPoints
        PlaybackSource.endPoint = oldEnd
        PlaybackSource.label = oldLabel
        PlaybackSource.timeScale = oldSpeed
        PlaybackSource.paused = oldPaused
        throw e
    }
}
