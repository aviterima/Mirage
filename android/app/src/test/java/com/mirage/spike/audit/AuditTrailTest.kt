package com.mirage.spike.audit

import com.mirage.spike.Field
import com.mirage.spike.Phase
import com.mirage.spike.PlanMode
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuditTrailTest {
    private class MemoryStorage : AuditStorage {
        var bytes: ByteArray? = null
        var fail = false
        var largestWrite = 0
        override fun read(maxBytes: Int): ByteArray? {
            if (fail) throw IOException("PRIVATE storage exception")
            return bytes?.copyOf()
        }
        override fun write(bytes: ByteArray) {
            if (fail) throw IOException("PRIVATE storage exception")
            largestWrite = maxOf(largestWrite, bytes.size)
            this.bytes = bytes.copyOf()
        }
    }
    private fun String.events(): JSONArray = JSONObject(this).getJSONArray("events")
    private fun ByteArray.json(): JSONObject = JSONObject(toString(Charsets.UTF_8))
    private fun JSONObject.bytes(): ByteArray = toString().toByteArray(Charsets.UTF_8)

    @Test fun exportHasTypedWorkflowContextAndNoExceptionText() = runTest {
        val storage = MemoryStorage()
        val engine = AuditTrailEngine(storage, backgroundScope, "0.16.0", 41, 34)
        engine.record(AuditAction.SEARCH, AuditOutcome.FAILED,
            AuditState(PlanMode.ITINERARY, Phase.READY, Field.END, 3, true, true),
            AuditDetails(reason = auditReason(SocketTimeoutException("PRIVATE address and key")),
                source = AuditSource.TYPEAHEAD, localMatches = 1, remoteMatches = 0,
                elapsedMs = 2400, control = AuditControl.EDIT), operation = 12)
        val report = engine.exportReport()
        val root = JSONObject(report)
        val event = root.getJSONArray("events").getJSONObject(0)
        assertEquals("0.16.0", root.getString("appVersion"))
        assertEquals(34, root.getInt("apiLevel"))
        assertEquals("ITINERARY", event.getJSONObject("state").getString("planMode"))
        assertEquals(3, event.getJSONObject("state").getInt("stopCount"))
        assertEquals("NETWORK_TIMEOUT", event.getJSONObject("details").getString("reason"))
        assertEquals("EDIT", event.getJSONObject("details").getString("control"))
        assertEquals(12L, event.getLong("operation"))
        assertFalse(report.contains("PRIVATE"))
        assertEquals(AuditReason.NETWORK_IO, auditReason(IOException("PRIVATE URL")))
        assertEquals(AuditReason.OTHER, auditReason(RuntimeException("PRIVATE transcript")))
    }

    @Test fun restartPreservesEventsMarkersAndPauseWithoutADeviceIdentifier() = runTest {
        val storage = MemoryStorage()
        val first = AuditTrailEngine(storage, backgroundScope, "0.16.0", 41, 34)
        first.record(AuditAction.PLAN_SAVE, AuditOutcome.SUCCEEDED)
        val marker = first.markProblem()
        first.setEnabled(false)
        val previous = first.exportReport().events()
        first.close()
        val restarted = AuditTrailEngine(storage, backgroundScope, "0.17.0", 42, 35)
        val report = JSONObject(restarted.exportReport())
        assertFalse(report.getBoolean("enabled"))
        assertEquals(42, report.getInt("versionCode"))
        assertEquals(41, report.getJSONArray("events").getJSONObject(0).getInt("versionCode"))
        assertEquals(34, report.getJSONArray("events").getJSONObject(0).getInt("apiLevel"))
        assertEquals(2, report.getJSONArray("events").length())
        assertEquals(marker, report.getJSONArray("events").getJSONObject(1).getString("marker"))
        restarted.setEnabled(true)
        restarted.record(AuditAction.APP_SESSION, AuditOutcome.SUCCEEDED)
        val events = restarted.exportReport().events()
        assertNotEquals(previous.getJSONObject(0).getString("session"), events.getJSONObject(2).getString("session"))
        assertEquals(1L, events.getJSONObject(2).getLong("sequence"))
        assertEquals(42, events.getJSONObject(2).getInt("versionCode"))
        assertEquals(35, events.getJSONObject(2).getInt("apiLevel"))
    }

    @Test fun loadedUnknownFieldsNeverEscapeAndInvalidKnownFieldsAreDiscarded() = runTest {
        val storage = MemoryStorage()
        val first = AuditTrailEngine(storage, backgroundScope)
        first.record(AuditAction.SAVED_SELECT, AuditOutcome.SUCCEEDED, AuditState(hasDestination = true))
        first.exportReport()
        first.close()
        val saved = storage.bytes!!.json()
        val event = saved.getJSONArray("events").getJSONObject(0)
        saved.put("unknownSecret", "PRIVATE api key")
        event.put("unknownSecret", "PRIVATE home address")
        event.getJSONObject("state").put("destination", "PRIVATE coordinates")
        event.getJSONObject("details").put("query", "PRIVATE spoken transcript")
        saved.getJSONArray("events").put(JSONObject(event.toString()).put("action", "PRIVATE secret action"))
        saved.getJSONArray("events").put(JSONObject(event.toString()).apply {
            getJSONObject("state").put("stopCount", "PRIVATE stop")
        })
        storage.bytes = saved.bytes()
        val restarted = AuditTrailEngine(storage, backgroundScope)
        val report = restarted.exportReport()
        assertEquals(1, report.events().length())
        assertFalse(report.contains("PRIVATE"))
        assertFalse(storage.bytes!!.toString(Charsets.UTF_8).contains("PRIVATE"))
        assertEquals(2L, JSONObject(report).getJSONObject("retention").getLong("droppedEvents"))
    }

    @Test fun corruptDeepOrOversizedStorageCannotBecomeAnExport() = runTest {
        for (bad in listOf("{PRIVATE broken", "[".repeat(40) + "0" + "]".repeat(40), "PRIVATE".repeat(80_000))) {
            val storage = MemoryStorage().apply { bytes = bad.toByteArray() }
            val engine = AuditTrailEngine(storage, backgroundScope)
            runCurrent()
            assertTrue(engine.status.value.ready)
            assertTrue(engine.status.value.storageFailed)
            assertFalse(engine.status.value.enabled)
            val report = engine.exportReport()
            assertEquals(0, report.events().length())
            assertFalse(report.contains("PRIVATE"))
            engine.close()
        }
    }

    @Test fun eventAndByteLimitsKeepRecentChronologyAndDeclareDroppedEvents() = runTest {
        val storage = MemoryStorage()
        val engine = AuditTrailEngine(storage, backgroundScope, maxEvents = 5, maxBytes = 1600)
        repeat(12) { index -> engine.record(AuditAction.STOP_EDIT, AuditOutcome.SUCCEEDED,
            AuditState(PlanMode.ITINERARY, Phase.READY, stopCount = index),
            AuditDetails(control = AuditControl.ADD, count = index)) }
        val report = engine.exportReport()
        val events = report.events()
        assertTrue(events.length() in 1..5)
        assertEquals(11, events.getJSONObject(events.length() - 1).getJSONObject("details").getInt("count"))
        assertTrue(report.toByteArray().size <= 1600)
        assertTrue(storage.largestWrite <= 1600)
        assertTrue(JSONObject(report).getJSONObject("retention").getLong("droppedEvents") >= 7)
        for (index in 1 until events.length())
            assertTrue(events.getJSONObject(index).getLong("sequence") > events.getJSONObject(index - 1).getLong("sequence"))
    }

    @Test fun sevenDayRetentionExpiresPersistedAndExportedHistory() = runTest {
        var time = 20L * 24 * 60 * 60 * 1000
        val storage = MemoryStorage()
        val engine = AuditTrailEngine(storage, backgroundScope, now = { time })
        engine.record(AuditAction.SAVED_PICKER, AuditOutcome.REQUESTED)
        assertEquals(1, engine.exportReport().events().length())
        time += 8L * 24 * 60 * 60 * 1000
        assertEquals(0, engine.exportReport().events().length())
        assertEquals(0, storage.bytes!!.json().getJSONArray("events").length())
        assertEquals(1L, engine.status.value.droppedCount)
    }

    @Test fun loadingExcessiveHistoryEnforcesTheProductionThousandEventLimit() = runTest {
        val storage = MemoryStorage()
        val first = AuditTrailEngine(storage, backgroundScope)
        first.record(AuditAction.INPUT_EDIT, AuditOutcome.SUCCEEDED)
        first.exportReport()
        first.close()
        val root = storage.bytes!!.json()
        val original = root.getJSONArray("events").getJSONObject(0)
        root.put("events", JSONArray().apply {
            repeat(1100) { index -> put(JSONObject(original.toString()).put("sequence", index + 1)) }
        })
        storage.bytes = root.bytes()
        val restarted = AuditTrailEngine(storage, backgroundScope)
        val report = JSONObject(restarted.exportReport())
        assertEquals(1000, report.getJSONArray("events").length())
        assertEquals(101L, report.getJSONArray("events").getJSONObject(0).getLong("sequence"))
        assertEquals(100L, report.getJSONObject("retention").getLong("droppedEvents"))
        assertTrue(storage.bytes!!.size <= 512 * 1024)
    }

    @Test fun pendingQueueIsBoundedAndOverflowIsVisible() = runTest {
        val engine = AuditTrailEngine(MemoryStorage(), backgroundScope, queueCapacity = 3)
        repeat(100) { engine.record(AuditAction.INPUT_EDIT, AuditOutcome.SUCCEEDED) }
        val report = JSONObject(engine.exportReport())
        assertEquals(3, report.getJSONArray("events").length())
        assertEquals(97L, report.getJSONObject("retention").getLong("droppedEvents"))
    }

    @Test fun pauseClearAndExportAreSerializedAgainstQueuedInteractions() = runTest {
        val storage = MemoryStorage()
        val engine = AuditTrailEngine(storage, backgroundScope)
        repeat(5) { engine.record(AuditAction.INPUT_EDIT, AuditOutcome.SUCCEEDED) }
        val pause = async { engine.setEnabled(false) }
        runCurrent()
        pause.await()
        repeat(5) { engine.record(AuditAction.SAVED_SELECT, AuditOutcome.SUCCEEDED) }
        assertEquals(5, engine.exportReport().events().length())
        try { engine.markProblem(); fail("Paused recording must not claim to create a marker") }
        catch (_: IllegalStateException) { /* A fixed, non-sensitive failure. */ }
        engine.clear()
        assertEquals(0, engine.exportReport().events().length())
        engine.close()
        val restarted = AuditTrailEngine(storage, backgroundScope)
        val report = JSONObject(restarted.exportReport())
        assertEquals(0, report.getJSONArray("events").length())
        assertFalse(report.getBoolean("enabled"))
        restarted.setEnabled(true)
        restarted.record(AuditAction.SAVED_SELECT, AuditOutcome.SUCCEEDED)
        assertEquals(1, restarted.exportReport().events().length())
    }

    @Test fun acknowledgedPauseDoesNotEnqueueOrCountOverflowAndResumeWaitsForAcknowledgement() = runTest {
        val engine = AuditTrailEngine(MemoryStorage(), backgroundScope, queueCapacity = 3)
        engine.record(AuditAction.APP_SESSION, AuditOutcome.SUCCEEDED)
        engine.setEnabled(false)
        val before = JSONObject(engine.exportReport())
        repeat(100) { engine.record(AuditAction.INPUT_EDIT, AuditOutcome.SUCCEEDED) }
        val after = JSONObject(engine.exportReport())
        assertEquals(before.getJSONArray("events").toString(), after.getJSONArray("events").toString())
        assertEquals(before.getJSONObject("retention").getLong("droppedEvents"),
            after.getJSONObject("retention").getLong("droppedEvents"))
        val resume = async { engine.setEnabled(true) }
        // Merely scheduling resume must not admit interactions before its acknowledgement.
        repeat(100) { engine.record(AuditAction.INPUT_EDIT, AuditOutcome.SUCCEEDED) }
        resume.await()
        engine.record(AuditAction.SAVED_PICKER, AuditOutcome.REQUESTED)
        val resumed = JSONObject(engine.exportReport())
        assertEquals(2, resumed.getJSONArray("events").length())
        assertEquals(0L, resumed.getJSONObject("retention").getLong("droppedEvents"))
    }

    @Test fun unavailableStorageNeverBreaksInteractionsAndMemoryCanStillBeExported() = runTest {
        val storage = MemoryStorage()
        val engine = AuditTrailEngine(storage, backgroundScope)
        runCurrent()
        storage.fail = true
        engine.record(AuditAction.PLAN_SAVE, AuditOutcome.SUCCEEDED)
        val marker = engine.markProblem()
        val report = JSONObject(engine.exportReport())
        assertTrue(engine.status.value.storageFailed)
        assertTrue(report.getBoolean("storageFailed"))
        assertEquals(marker, report.getJSONArray("events").getJSONObject(1).getString("marker"))
        assertFalse(report.toString().contains("PRIVATE"))
        try { engine.setEnabled(false); fail("Unpersisted pause must be reported") }
        catch (_: IOException) { }
        assertFalse(engine.status.value.enabled)
        try { engine.clear(); fail("Unpersisted deletion must be reported") }
        catch (_: IOException) { }
        assertEquals(0, engine.exportReport().events().length())
        storage.fail = false
        engine.setEnabled(true)
        assertFalse(engine.status.value.storageFailed)
    }

    @Test fun unreadableStoredPreferenceFailsClosedUntilTheUserEnablesRecording() = runTest {
        val storage = MemoryStorage().apply { fail = true }
        val engine = AuditTrailEngine(storage, backgroundScope)
        runCurrent()
        assertFalse(engine.status.value.enabled)
        engine.record(AuditAction.APP_SESSION, AuditOutcome.SUCCEEDED)
        assertEquals(0, engine.exportReport().events().length())
        storage.fail = false
        engine.setEnabled(true)
        engine.record(AuditAction.SAVED_PICKER, AuditOutcome.REQUESTED)
        assertEquals(1, engine.exportReport().events().length())
    }

    @Test fun atomicFileStoreBoundsReadsAndLeavesNoTemporaryReport() {
        val directory = Files.createTempDirectory("mirage-audit-test").toFile()
        try {
            val file = File(directory, "audit.json")
            val storage = FileAuditStorage { file }
            assertNull(storage.read(10))
            storage.write("first".toByteArray())
            storage.write("second".toByteArray())
            assertEquals("second", storage.read(6)!!.toString(Charsets.UTF_8))
            assertFalse(File(directory, "audit.json.tmp").exists())
            try { storage.read(5); fail("Oversized local file must be rejected") }
            catch (_: IOException) { }
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }
}
