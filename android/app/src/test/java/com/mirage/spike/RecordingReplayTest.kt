package com.mirage.spike

import com.mirage.spike.engine.*
import com.mirage.spike.recording.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*

class RecordingReplayTest {
    private val a = Fix(33.5, -112.0, 2f, 45f, 4f)
    private val b = Fix(33.6, -112.1, 0f, 90f, 6f)
    private val track = RecordedTrack(
        TrackSummary("fixture", "Recorded errands", TrackSource.REAL, 1000, 2000, 1000, 2, TrackState.SAVED),
        listOf(TrackSample(0, 1000, a), TrackSample(1000, 2000, b)))

    @Before fun before() = reset()
    @After fun after() = reset()
    private fun reset() {
        MockState.reset(); PlaybackSource.clearQueue(); PlaybackSource.consumeSkip()
        PlaybackSource.current = null; PlaybackSource.routePoints = emptyList(); PlaybackSource.endPoint = null
        PlaybackSource.label = ""; PlaybackSource.paused = false; PlaybackSource.timeScale = 1.0
        PlaybackSource.signal = Signal.GOOD
    }

    @Test fun rejectedServiceLaunchRestoresPreviousPlaybackAndKeepsQueueAndSkip() {
        val old = flowOf(a)
        val points = listOf(LatLng(a.lat, a.lng))
        val queued = PlaybackSource.Queued(flowOf(b), listOf(LatLng(b.lat, b.lng)), "Queued trip", LatLng(b.lat, b.lng))
        PlaybackSource.current = old; PlaybackSource.routePoints = points; PlaybackSource.endPoint = points.single()
        PlaybackSource.label = "Original trip"; PlaybackSource.timeScale = 5.0; PlaybackSource.paused = true
        PlaybackSource.signal = Signal.URBAN; PlaybackSource.enqueue(queued); PlaybackSource.requestSkip()
        var launches = 0
        try {
            armRecordingReplay(track, 2.0) { launches++; throw SecurityException("Fixture launch refused") }
            fail("A rejected launch must be reported")
        } catch (expected: SecurityException) { assertEquals("Fixture launch refused", expected.message) }
        assertEquals(1, launches); assertSame(old, PlaybackSource.current)
        assertEquals(points, PlaybackSource.routePoints); assertEquals(points.single(), PlaybackSource.endPoint)
        assertEquals("Original trip", PlaybackSource.label); assertEquals(5.0, PlaybackSource.timeScale, 0.0)
        assertTrue(PlaybackSource.paused); assertEquals(Signal.URBAN, PlaybackSource.signal)
        assertSame(queued, PlaybackSource.pollQueue()); assertNull(PlaybackSource.pollQueue())
        assertTrue(PlaybackSource.consumeSkip()); assertFalse(PlaybackSource.consumeSkip())
    }

    @Test fun acceptedReplayClearsQueueAndSkipAndEmitsExactProtectedEndpoints() = runTest {
        PlaybackSource.enqueue(PlaybackSource.Queued(flowOf(a), emptyList(), "Queued trip", null))
        PlaybackSource.requestSkip(); PlaybackSource.paused = true
        var launches = 0
        armRecordingReplay(track, 2.0) {
            launches++
            assertEquals("Replay · Recorded errands", PlaybackSource.label)
            assertEquals(1, PlaybackSource.queueSize()) // queue is discarded only after a successful launch
        }
        assertEquals(1, launches); assertEquals(0, PlaybackSource.queueSize()); assertFalse(PlaybackSource.consumeSkip())
        assertFalse(PlaybackSource.paused); assertEquals(2.0, PlaybackSource.timeScale, 0.0)
        assertEquals(listOf(LatLng(a.lat, a.lng)), PlaybackSource.routePoints)
        assertEquals(LatLng(b.lat, b.lng), PlaybackSource.endPoint)
        val emitted = requireNotNull(PlaybackSource.current).toList()
        assertTrue(emitted.size >= 2); assertTrue(emitted.all { it.preserveCoordinates })
        assertEquals(a.lat, emitted.first().lat, 0.0); assertEquals(a.lng, emitted.first().lng, 0.0)
        assertEquals(b.lat, emitted.last().lat, 0.0); assertEquals(b.lng, emitted.last().lng, 0.0)
    }

    @Test fun cancelledOldReplayCannotChangeNewSessionsStatus() = runTest {
        val withGap = track.copy(samples = listOf(track.samples.first(), track.samples.last().copy(gapBefore = true)))
        MockState.update { it.copy(running = true) }
        armRecordingReplay(withGap, 1.0) {}
        val newFlow = flowOf(b)
        requireNotNull(PlaybackSource.current).onEach {
            assertEquals("Recording gap · holding last location", MockState.status.value.stepLabel)
            PlaybackSource.current = newFlow
            MockState.update { it.copy(stepLabel = "New session") }
        }.take(1).toList()
        assertSame(newFlow, PlaybackSource.current)
        assertEquals("New session", MockState.status.value.stepLabel)
    }

    @Test fun initialReplayGapIsVisibleBeforeFirstAcceptedOutput() = runTest {
        val withGap = track.copy(samples = listOf(track.samples.first(), track.samples.last().copy(gapBefore = true)))
        armRecordingReplay(withGap, 1.0) {
            MockState.update { it.copy(starting = true, running = false) }
        }
        val emitted = requireNotNull(PlaybackSource.current).onEach {
            assertFalse(MockState.status.value.running)
            assertTrue(MockState.status.value.starting)
            assertEquals("Recording gap · holding last location", MockState.status.value.stepLabel)
            assertEquals(0f, it.speedMps, 0f)
        }.take(1).toList()
        assertEquals(a.lat, emitted.single().lat, 0.0)
        assertTrue(emitted.single().preserveCoordinates)
    }

    @Test fun emptyTrackCannotArmOrLaunchReplay() {
        val old = flowOf(a); PlaybackSource.current = old
        try {
            armRecordingReplay(track.copy(samples = emptyList()), 1.0) { fail("Empty replay must not launch") }
            fail("Empty recording must be rejected")
        } catch (expected: IllegalArgumentException) { assertEquals("No locations were recorded", expected.message) }
        assertSame(old, PlaybackSource.current)
    }
}
