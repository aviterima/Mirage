package com.mirage.spike.recording

import com.mirage.spike.engine.Fix
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecordingTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store() = FileTrackStore(temporary.newFolder())
    private fun fix(latitude: Double = 33.0, speed: Float = 3f) = Fix(latitude, -112.0, speed, 20f, 4f)

    @Test fun `real capture rejects mocked stale future and active simulation observations`() {
        assertTrue(RecordingPolicy.acceptReal(false, false, 1000, 2000))
        assertFalse(RecordingPolicy.acceptReal(true, false, 1000, 2000))
        assertFalse(RecordingPolicy.acceptReal(false, true, 1000, 2000))
        assertFalse(RecordingPolicy.acceptReal(false, false, 1000, 12000))
        assertFalse(RecordingPolicy.acceptReal(false, false, 3000, 2000))
        assertFalse(RecordingPolicy.acceptReal(false, false, -1, 2000))
    }

    @Test fun `capture survives wall clock changes while rejecting backward monotonic fixes`() {
        val store = store()
        val track = store.start(TrackSource.REAL, 10_000, 1000)
        assertTrue(store.append(fix(), 10_000, 1000))
        assertTrue(store.append(fix(33.0001), 5_000, 2000))
        assertFalse(store.append(fix(33.0002), 4_000, 1999))
        assertFalse(store.append(fix(33.0002), 4_000, 2000))
        store.finish(6_000, 3000)
        val saved = store.read(track.id)
        assertEquals(listOf(0L, 1000L), saved.samples.map { it.offsetMillis })
        assertEquals(listOf(10_000L, 5_000L), saved.samples.map { it.wallTimeMillis })
        assertEquals(2000, saved.summary.durationMillis)
    }

    @Test fun `moving capture is bounded and saving flushes the last pending location`() {
        val store = store()
        val track = store.start(TrackSource.EMULATED, 1000, 1000)
        for (i in 0..12) store.append(fix(33.0 + i / 10000.0), 1000 + i * 100L, 1000 + i * 100L)
        assertEquals(2, store.active!!.sampleCount)
        store.finish(2200, 2200)
        assertEquals(listOf(0L, 1000L, 1200L), store.read(track.id).samples.map { it.offsetMillis })
        assertNull(store.active)
    }

    @Test fun `stationary capture keeps duration with fewer samples and no false gap`() {
        val store = store()
        val track = store.start(TrackSource.REAL, 1000, 1000)
        for (second in 0..60) store.append(fix(speed = 0f), 1000 + second * 1000L, 1000 + second * 1000L)
        store.finish(61_000, 61_000)
        val saved = store.read(track.id)
        assertEquals(listOf(0L, 30_000L, 60_000L), saved.samples.map { it.offsetMillis })
        assertTrue(saved.gaps.isEmpty())
        assertEquals(60_000, saved.summary.durationMillis)
    }

    @Test fun `departure after compressed stay retains timing without a false replay gap`() = runTest {
        val store = store()
        val track = store.start(TrackSource.REAL, 1000, 1000)
        for (second in 0..19) store.append(fix(speed = 0f), 1000 + second * 1000L, 1000 + second * 1000L)
        assertEquals(1, store.active!!.sampleCount)
        assertTrue(store.append(fix(33.0001, speed = 2f), 21_000, 21_000))
        store.finish(21_000, 21_000)
        val saved = store.read(track.id)
        assertEquals(listOf(0L, 19_000L, 20_000L), saved.samples.map { it.offsetMillis })
        assertTrue(saved.gaps.isEmpty())
        val gaps = mutableListOf<Boolean>()
        val replayed = saved.fixes(timeScale = { 1.0 }, paused = { false }, onGap = gaps::add).toList()
        assertEquals(listOf(false), gaps)
        assertTrue(replayed.take(96).all { it.lat == 33.0 })
        assertEquals(33.00002, replayed[96].lat, 0.000001)
        assertEquals(33.0001, replayed.last().lat, 0.0)
    }

    @Test fun `session changes and missing observations persist gaps without losing latest fix`() {
        val store = store()
        val track = store.start(TrackSource.EMULATED, 1000, 1000)
        store.append(fix(), 1000, 1000)
        store.append(fix(33.0001), 1500, 1500)
        store.gap("Simulation stopped")
        store.gap("Still stopped")
        store.append(fix(34.0), 2000, 2000)
        store.append(fix(35.0), 22_000, 22_000)
        store.finish(22_000, 22_000)
        val saved = store.read(track.id)
        assertEquals(2, saved.gaps.size)
        assertEquals(500, saved.gaps.first().offsetMillis)
        assertEquals("Simulation stopped", saved.gaps.first().reason)
        assertTrue(saved.samples[2].gapBefore)
        assertTrue(saved.samples.last().gapBefore)
        assertEquals(listOf(0L, 500L, 1000L, 21_000L), saved.samples.map { it.offsetMillis })
    }

    @Test fun `interrupted JSONL suffix recovers valid locations and editable name`() {
        val directory = temporary.newFolder()
        val original = FileTrackStore(directory)
        val track = original.start(TrackSource.REAL, 1000, 1000)
        original.append(fix(), 1000, 1000)
        original.append(fix(33.01), 2000, 2000)
        original.rename(track.id, "Morning errands")
        File(directory, "${track.id}.jsonl").appendText("{partial")
        val reopened = FileTrackStore(directory)
        val recovered = reopened.list().single()
        assertEquals(TrackState.INTERRUPTED, recovered.state)
        assertEquals("Morning errands", recovered.name)
        assertEquals(2, recovered.sampleCount)
        assertEquals(1000, recovered.durationMillis)
        assertEquals(33.01, reopened.read(track.id).samples.last().fix.lat, 0.0)
        assertNull(reopened.active)
    }

    @Test fun `saved recordings reopen rename and delete without modifying others`() {
        val directory = temporary.newFolder()
        val firstStore = FileTrackStore(directory)
        val first = firstStore.start(TrackSource.REAL, 1000, 1000)
        firstStore.append(fix(), 1000, 1000)
        firstStore.finish(2000, 2000)
        val second = firstStore.start(TrackSource.EMULATED, 3000, 3000)
        firstStore.append(fix(34.0), 3000, 3000)
        firstStore.finish(4000, 4000)
        val reopened = FileTrackStore(directory)
        assertEquals(TrackState.SAVED, reopened.read(first.id).summary.state)
        reopened.rename(first.id, "  Commute  ")
        assertEquals("Commute", reopened.read(first.id).summary.name)
        reopened.delete(second.id)
        assertEquals(listOf(first.id), reopened.list().map { it.id })
        assertEquals(33.0, reopened.read(first.id).samples.single().fix.lat, 0.0)
    }

    @Test fun `invalid fixes names and identifiers cannot alter the recording`() {
        val store = store()
        val active = store.start(TrackSource.REAL, 1000, 1000, "Trip")
        assertFalse(store.append(fix(Double.NaN), 1000, 1000))
        assertFalse(store.append(fix(91.0), 1000, 1000))
        assertFalse(store.append(fix().copy(accuracyM = -1f), 1000, 1000))
        assertFalse(store.append(fix().copy(speedMps = Float.NaN), 1000, 1000))
        assertThrows(IllegalArgumentException::class.java) { store.rename(active.id, " ") }
        assertThrows(IllegalArgumentException::class.java) { store.read("../outside") }
        assertThrows(IllegalStateException::class.java) { store.delete(active.id) }
        assertThrows(IllegalStateException::class.java) { store.start(TrackSource.EMULATED, 1000, 1000) }
        assertEquals("Trip", store.active!!.name)
        assertEquals(0, store.active!!.sampleCount)
    }

    @Test fun `automatic names distinguish source and manual edits survive finish`() {
        val store = store()
        val track = store.start(TrackSource.EMULATED, 1_795_000_000_000, 1000)
        assertTrue(track.name.startsWith("Emulated day"))
        assertTrue(RecordingPolicy.suggestedName(TrackSource.REAL, 1_795_000_000_000).startsWith("Real journey"))
        store.rename(track.id, "Tuesday rounds")
        store.append(fix(), 1_795_000_000_000, 1000)
        assertEquals("Tuesday rounds", store.finish(1_795_000_001_000, 2000)!!.name)
    }

    @Test fun `duration limit reports failure with the existing recording still recoverable`() {
        val store = store()
        val track = store.start(TrackSource.REAL, 1000, 1000)
        store.append(fix(), 1000, 1000)
        assertThrows(IllegalStateException::class.java) {
            store.append(fix(), 2000, 1001 + RecordingPolicy.MAX_DURATION_MILLIS)
        }
        assertEquals(1, store.read(track.id).samples.size)
        assertEquals(track.id, store.active!!.id)
    }

    @Test fun `interrupt releases the active session and preserves durable locations`() {
        val store = store()
        val first = store.start(TrackSource.REAL, 1000, 1000)
        store.append(fix(), 1000, 1000)
        store.append(fix(33.01), 2000, 2000)
        store.append(fix(33.02), 2100, 2100)
        assertEquals(TrackState.INTERRUPTED, store.interrupt()!!.state)
        assertNull(store.active)
        assertEquals(TrackState.INTERRUPTED, store.read(first.id).summary.state)
        assertEquals(2, store.read(first.id).samples.size)
        val next = store.start(TrackSource.EMULATED, 3000, 3000)
        assertNotEquals(first.id, next.id)
        assertEquals(2, store.list().size)
    }

    private fun recording(samples: List<TrackSample>, gaps: List<TrackGap> = emptyList(), duration: Long = samples.last().offsetMillis) =
        RecordedTrack(TrackSummary("test", "Replay", TrackSource.REAL, 0, duration, duration, samples.size, TrackState.SAVED), samples, gaps)

    @Test fun `replay interpolates timed continuous samples and preserves captured coordinates`() = runTest {
        val track = recording(listOf(TrackSample(0, 0, fix(33.0)), TrackSample(1000, 1000, fix(33.01))))
        val output = track.fixes(timeScale = { 1.0 }, paused = { false }).toList()
        assertEquals(1000, testScheduler.currentTime)
        assertEquals(6, output.size)
        assertEquals(33.0, output.first().lat, 0.0)
        assertEquals(33.004, output[2].lat, 0.000001)
        assertEquals(33.01, output.last().lat, 0.0)
        assertTrue(output.all { it.preserveCoordinates })
        assertEquals(1f, output.last().progress, 0f)
    }

    @Test fun `replay holds across explicit and long unknown gaps`() = runTest {
        val changes = mutableListOf<Boolean>()
        val explicit = recording(listOf(TrackSample(0, 0, fix(33.0)), TrackSample(2000, 2000, fix(34.0), true)),
            listOf(TrackGap(0, "Stopped")))
        val points = explicit.fixes(timeScale = { 1.0 }, paused = { false }, onGap = changes::add).toList()
        assertTrue(points.dropLast(1).all { it.lat == 33.0 && it.speedMps == 0f })
        assertEquals(34.0, points.last().lat, 0.0)
        assertEquals(listOf(true, false), changes)
        val unknown = recording(listOf(TrackSample(0, 0, fix(33.0)), TrackSample(30_000, 30_000, fix(34.0))))
        assertTrue(unknown.fixes(timeScale = { 100.0 }, paused = { false }).toList().dropLast(1).all { it.lat == 33.0 })
    }

    @Test fun `replay speed changes live and pause does not consume recorded time`() = runTest {
        val track = recording(listOf(TrackSample(0, 0, fix(33.0)), TrackSample(4000, 4000, fix(33.04))))
        var paused = false
        var scale = 1.0
        val observed = mutableListOf<Pair<Long, Fix>>()
        launch { delay(400); paused = true; delay(1000); paused = false; scale = 2.0 }
        track.fixes(timeScale = { scale }, paused = { paused }).collect { observed += testScheduler.currentTime to it }
        val pausePoints = observed.filter { it.first in 400..1200 }.map { it.second }
        assertTrue(pausePoints.isNotEmpty())
        assertEquals(1, pausePoints.map { it.lat }.distinct().size)
        assertTrue(pausePoints.all { it.speedMps == 0f })
        assertEquals(33.04, observed.last().second.lat, 0.0)
        assertTrue(testScheduler.currentTime in 2800..3800)
    }

    @Test fun `replay cancellation stops at caller boundary and clears gap status`() = runTest {
        val track = recording(listOf(TrackSample(0, 0, fix()), TrackSample(30_000, 30_000, fix(34.0), true)))
        val gaps = mutableListOf<Boolean>()
        val output = track.fixes(timeScale = { 1.0 }, paused = { false }, onGap = gaps::add).take(3).toList()
        assertEquals(3, output.size)
        assertEquals(400, testScheduler.currentTime)
        assertEquals(listOf(true, false), gaps)
    }
}
