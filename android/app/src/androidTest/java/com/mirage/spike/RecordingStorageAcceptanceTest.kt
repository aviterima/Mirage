package com.mirage.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mirage.spike.engine.Fix
import com.mirage.spike.recording.FileTrackStore
import com.mirage.spike.recording.TrackSource
import com.mirage.spike.recording.TrackState
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Android's actual JSONObject, fsync and atomic metadata replacement; no GPS or simulation. */
@RunWith(AndroidJUnit4::class)
class RecordingStorageAcceptanceTest {
    private lateinit var privateRoot: File
    private lateinit var fixtureDirectory: File

    @Before fun createDisposableStorage() {
        privateRoot = InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir.canonicalFile
        fixtureDirectory = File(privateRoot, "recording-acceptance-${UUID.randomUUID()}").canonicalFile
        assertEquals(privateRoot, fixtureDirectory.parentFile)
        assertFalse(fixtureDirectory.exists())
        assertTrue(fixtureDirectory.mkdir())
    }

    @After fun removeOnlyDisposableStorage() {
        if (!::fixtureDirectory.isInitialized || !fixtureDirectory.exists()) return
        // Never recurse through application storage or follow a substituted directory.
        assertEquals(privateRoot, fixtureDirectory.canonicalFile.parentFile)
        assertTrue(fixtureDirectory.name.startsWith("recording-acceptance-"))
        for (file in requireNotNull(fixtureDirectory.listFiles())) {
            assertEquals(fixtureDirectory, file.canonicalFile.parentFile)
            assertTrue("Unexpected directory inside the fixture", file.isFile)
            assertTrue("Could not remove the disposable fixture file", file.delete())
        }
        assertTrue(fixtureDirectory.delete())
    }

    private fun fix(latitude: Double) = Fix(latitude, -112.0, 2f, 10f, 4f, altitudeM = 17.0)

    @Test fun saveRenameReopenAndReplayThroughAndroidStorage() = runBlocking {
        val store = FileTrackStore(fixtureDirectory)
        val initial = store.start(TrackSource.REAL, 1_000_000, 1000, "Synthetic real fixture")
        assertTrue(store.append(fix(33.0), 1_000_000, 1000))
        assertTrue(store.append(fix(33.001), 1_001_000, 2000))
        store.gap("Synthetic reception gap")
        assertTrue(store.append(fix(34.0), 1_003_000, 4000))
        val saved = requireNotNull(store.finish(1_004_000, 5000))
        assertEquals(TrackState.SAVED, saved.state)
        assertEquals(3, saved.sampleCount)
        assertEquals(4000L, saved.durationMillis)
        assertEquals("Synthetic morning route", store.rename(initial.id, "Synthetic morning route").name)
        assertNull(store.active)

        val reopened = FileTrackStore(fixtureDirectory)
        val recording = reopened.read(initial.id)
        assertEquals("Synthetic morning route", reopened.list().single().name)
        assertEquals(TrackState.SAVED, recording.summary.state)
        assertEquals(TrackSource.REAL, recording.summary.source)
        assertEquals(listOf(0L, 1000L, 3000L), recording.samples.map { it.offsetMillis })
        assertEquals(listOf(1_000_000L, 1_001_000L, 1_003_000L), recording.samples.map { it.wallTimeMillis })
        assertEquals("Synthetic reception gap", recording.gaps.single().reason)
        assertTrue(recording.samples.last().gapBefore)
        assertEquals(17.0, recording.samples.first().fix.altitudeM, 0.0)
        assertFalse(File(fixtureDirectory, "${initial.id}.pending").exists())

        val replayed = recording.fixes(timeScale = { 1.0 }, paused = { false }).take(3).toList()
        assertEquals(3, replayed.size)
        assertEquals(33.0, replayed.first().lat, 0.0)
        assertEquals(33.0004, replayed.last().lat, 0.000001)
        assertTrue(replayed.all { it.preserveCoordinates })
        assertNull(reopened.active)
    }

    @Test fun partialAndroidAppendRecoversPrefixAndKeepsSavedRecording() {
        val store = FileTrackStore(fixtureDirectory)
        val saved = store.start(TrackSource.REAL, 1_000_000, 1000, "Saved fixture")
        store.append(fix(32.0), 1_000_000, 1000)
        store.finish(1_001_000, 2000)
        val interrupted = store.start(TrackSource.EMULATED, 2_000_000, 3000, "Interrupted fixture")
        store.append(fix(33.0), 2_000_000, 3000)
        store.append(fix(33.001), 2_001_000, 4000)
        store.gap("Synthetic session change")
        store.append(fix(34.0), 2_002_000, 5000)
        val journal = File(fixtureDirectory, "${interrupted.id}.jsonl")
        FileOutputStream(journal, true).use {
            it.write("{\"type\":\"sample\",\"offset\":".toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        // A new store represents a process restart: no active-session memory is retained.
        val reopened = FileTrackStore(fixtureDirectory)
        val recovered = reopened.read(interrupted.id)
        assertNull(reopened.active)
        assertEquals(TrackState.INTERRUPTED, recovered.summary.state)
        assertEquals(3, recovered.summary.sampleCount)
        assertEquals(2000L, recovered.summary.durationMillis)
        assertEquals(1, recovered.gaps.size)
        assertEquals(34.0, recovered.samples.last().fix.lat, 0.0)
        reopened.rename(interrupted.id, "Recovered fixture")
        assertEquals("Recovered fixture", reopened.read(interrupted.id).summary.name)
        assertEquals(TrackState.INTERRUPTED, reopened.read(interrupted.id).summary.state)
        reopened.delete(interrupted.id)
        assertEquals(listOf(saved.id), reopened.list().map { it.id })
        assertEquals(32.0, reopened.read(saved.id).samples.single().fix.lat, 0.0)
    }
}
