package com.mirage.spike.audit

import android.content.Context
import android.os.Build
import com.mirage.spike.BuildConfig
import com.mirage.spike.Field
import com.mirage.spike.Phase
import com.mirage.spike.PlanMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Deliberately no free text, coordinates, place IDs, queries, transcripts or exception messages. */
enum class AuditAction { APP_SESSION, PLANNER_MODE, START_SOURCE, INPUT_EDIT, SEARCH, SAVED_PICKER, SAVED_SELECT, PLACE_SELECT, ROUTE_BUILD, PLAN_SAVE, PLAN_LOAD, STOP_EDIT, SIMULATION, CONTINUATION, VOICE, PERMISSION, RECOVERY, PROBLEM_MARKER }
enum class AuditOutcome { REQUESTED, SUCCEEDED, FAILED, BLOCKED, CANCELLED, IGNORED }
enum class AuditReason { NONE, NO_START, NO_DESTINATION, NO_STOPS, NO_API, NOT_READY, INVALID_INPUT, DUPLICATE_NAME, MISSING_ITEM, NO_RESULTS, NETWORK_TIMEOUT, NETWORK_IO, PERMISSION, STORAGE, STALE, OTHER }
enum class AuditSource { TOUCH, TYPEAHEAD, SAVED_PLACE, MAP, REAL_LOCATION, SIMULATED_LOCATION, TRIP_END, VOICE, AUTOMATION, SERVICE, RECOVERY }
enum class AuditControl { OPEN, CLOSE, CONFIRM, EDIT, ADD, REMOVE, MOVE, UNDO, SWAP, START, STOP, PAUSE, RESUME, SKIP, RESTORE, IMPORT, EXPORT, SAVE_COPY, REPLACE, RENAME, FAVORITE, ALIAS }

data class AuditState(
    val planMode: PlanMode? = null, val phase: Phase? = null, val field: Field? = null,
    val stopCount: Int = 0, val hasStart: Boolean = false, val hasDestination: Boolean = false,
    val running: Boolean = false, val paused: Boolean = false, val startFromReal: Boolean = false,
    val useSimulatedStart: Boolean = false, val queueAfterCurrent: Boolean = false,
)
data class AuditDetails(
    val reason: AuditReason = AuditReason.NONE, val source: AuditSource? = null,
    val localMatches: Int? = null, val remoteMatches: Int? = null, val elapsedMs: Long? = null,
    val index: Int? = null, val count: Int? = null, val control: AuditControl? = null,
)
data class AuditStatus(
    val enabled: Boolean = true, val eventCount: Int = 0, val storageFailed: Boolean = false,
    val ready: Boolean = false, val droppedCount: Long = 0,
)

/** Only exception types are classified. Causes/messages are never recorded. */
fun auditReason(error: Throwable): AuditReason = when (error) {
    is SocketTimeoutException -> AuditReason.NETWORK_TIMEOUT
    is SecurityException -> AuditReason.PERMISSION
    is IOException -> AuditReason.NETWORK_IO
    is IllegalArgumentException -> AuditReason.INVALID_INPUT
    else -> AuditReason.OTHER
}

object AuditTrail {
    private val mutableStatus = MutableStateFlow(AuditStatus())
    val status: StateFlow<AuditStatus> = mutableStatus.asStateFlow()
    @Volatile private var engine: AuditTrailEngine? = null
    private val operations = AtomicLong()

    /** Obtaining/creating noBackupFilesDir and all file access happen on the IO actor. */
    @Synchronized fun initialize(context: Context) {
        if (engine != null) return
        val application = context.applicationContext
        engine = AuditTrailEngine(
            FileAuditStorage { File(application.noBackupFilesDir, "interaction-audit.json") },
            CoroutineScope(SupervisorJob() + Dispatchers.IO), BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT, statusSink = mutableStatus,
        )
    }

    fun newOperation(): Long = operations.incrementAndGet()
    fun record(action: AuditAction, outcome: AuditOutcome, state: AuditState? = null,
        details: AuditDetails = AuditDetails(), operation: Long? = null) {
        engine?.record(action, outcome, state, details, operation)
    }
    suspend fun setEnabled(enabled: Boolean) { requireNotNull(engine) { "Audit is not ready" }.setEnabled(enabled) }
    suspend fun clear() { requireNotNull(engine) { "Audit is not ready" }.clear() }
    suspend fun exportReport(): String = requireNotNull(engine) { "Audit is not ready" }.exportReport()
    suspend fun markProblem(): String = requireNotNull(engine) { "Audit is not ready" }.markProblem()

    internal fun initializeForTests(directory: File, scope: CoroutineScope): AuditTrailEngine {
        engine?.close()
        mutableStatus.value = AuditStatus()
        return AuditTrailEngine(FileAuditStorage { File(directory, "interaction-audit.json") },
            scope, "test", 0, 0, statusSink = mutableStatus).also { engine = it }
    }
    internal fun resetForTests() { engine?.close(); engine = null; mutableStatus.value = AuditStatus() }
}

internal interface AuditStorage {
    fun read(maxBytes: Int): ByteArray?
    fun write(bytes: ByteArray)
}

/** Same-directory atomic replacement: an interrupted write cannot leave a partial report. */
internal class FileAuditStorage(private val file: () -> File) : AuditStorage {
    override fun read(maxBytes: Int): ByteArray? {
        val target = file()
        if (!target.exists()) return null
        target.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
                if (read < 0) return output.toByteArray()
                output.write(buffer, 0, read)
                if (output.size() > maxBytes) throw IOException("Audit storage exceeds its limit")
            }
        }
    }

    override fun write(bytes: ByteArray) {
        val target = file()
        val parent = requireNotNull(target.parentFile)
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Audit storage unavailable")
        val temporary = File(parent, target.name + ".tmp")
        try {
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } finally {
            // Also remove old interrupted temporary contents when clearing the report.
            if (temporary.exists()) temporary.delete()
        }
    }
}

internal data class AuditEvent(
    val timeMs: Long, val session: String, val sequence: Long, val action: AuditAction,
    val outcome: AuditOutcome, val state: AuditState?, val details: AuditDetails,
    val operation: Long? = null, val marker: String? = null,
    val versionCode: Int? = null, val apiLevel: Int? = null,
)

/** A bounded, nonblocking producer queue and one IO consumer serialize data and control changes. */
internal class AuditTrailEngine(
    private val storage: AuditStorage, scope: CoroutineScope,
    private val appVersion: String = "test", private val versionCode: Int = 0,
    private val apiLevel: Int = 0, private val now: () -> Long = System::currentTimeMillis,
    private val maxEvents: Int = 1000, private val maxBytes: Int = 512 * 1024,
    private val retentionMs: Long = 7L * 24 * 60 * 60 * 1000,
    queueCapacity: Int = 128, private val statusSink: MutableStateFlow<AuditStatus> = MutableStateFlow(AuditStatus()),
) {
    private sealed interface Command {
        data class Record(val action: AuditAction, val outcome: AuditOutcome, val state: AuditState?,
            val details: AuditDetails, val operation: Long?, val timeMs: Long) : Command
        data class Control(val apply: () -> Unit) : Command
    }
    private val commands = Channel<Command>(queueCapacity)
    private val pendingDropped = AtomicLong()
    private val session = UUID.randomUUID().toString()
    private val events = ArrayDeque<AuditEvent>()
    private var sequence = 0L
    private var dropped = 0L
    private var enabled = true
    private var storageFailed = false
    val status: StateFlow<AuditStatus> = statusSink.asStateFlow()
    private val worker: Job = scope.launch {
        try {
            storage.read(maxBytes)?.let { bytes -> load(bytes); persist() }
        } catch (_: Exception) {
            // A damaged/unreadable preference must never silently resume a user's paused audit.
            enabled = false
            storageFailed = true
        }
        trim()
        publish()
        for (command in commands) {
            dropped = safeAdd(dropped, pendingDropped.getAndSet(0))
            when (command) {
                is Command.Record -> if (enabled) {
                    append(command.action, command.outcome, command.state, command.details,
                        command.operation, command.timeMs)
                    persist()
                }
                is Command.Control -> command.apply()
            }
            publish()
        }
    }

    fun record(action: AuditAction, outcome: AuditOutcome, state: AuditState? = null,
        details: AuditDetails = AuditDetails(), operation: Long? = null) {
        // Once pause is acknowledged, do not enqueue events or count queue overflow. During
        // initial loading the actor must first recover the preference in its original order.
        // Resuming takes effect when setEnabled(true) completes; input before that stays paused.
        val current = statusSink.value
        if (current.ready && !current.enabled) return
        if (commands.trySend(Command.Record(action, outcome, state, details, operation, now())).isFailure)
            pendingDropped.updateAndGet { safeAdd(it, 1) }
    }

    suspend fun setEnabled(value: Boolean) = control {
        enabled = value
        persist()
        if (storageFailed) throw IOException("Audit preference could not be saved")
    }
    suspend fun clear() = control {
        events.clear(); dropped = 0; pendingDropped.set(0)
        persist()
        if (storageFailed) throw IOException("Audit history could not be cleared from storage")
    }
    suspend fun markProblem(): String = control {
        check(enabled) { "Audit recording is paused" }
        val marker = UUID.randomUUID().toString()
        append(AuditAction.PROBLEM_MARKER, AuditOutcome.SUCCEEDED, null,
            AuditDetails(source = AuditSource.TOUCH), null, now(), marker)
        persist()
        marker
    }
    suspend fun exportReport(): String = control {
        persist()
        // Rebuild from typed records; original file bytes or unknown fields are never exported.
        val reportId = UUID.randomUUID().toString()
        fun buildReport(): String = JSONObject().put("schema", 1).put("reportId", reportId)
                .put("createdAtMs", now()).put("appVersion", appVersion).put("versionCode", versionCode)
                .put("apiLevel", apiLevel).put("enabled", enabled).put("storageFailed", storageFailed)
                .put("retention", JSONObject().put("maxEvents", maxEvents).put("maxBytes", maxBytes)
                    .put("maxAgeMs", retentionMs).put("droppedEvents", dropped))
                .put("events", eventArray()).toString()
        var report = buildReport()
        var pruned = false
        while (report.toByteArray(Charsets.UTF_8).size > maxBytes && events.isNotEmpty()) {
            events.removeFirst(); dropped = safeAdd(dropped, 1)
            pruned = true
            report = buildReport()
        }
        if (pruned) { persist(); report = buildReport() }
        report
    }
    internal fun close() { commands.close(); worker.cancel() }

    private suspend fun <T> control(block: () -> T): T {
        val result = CompletableDeferred<Result<T>>()
        commands.send(Command.Control {
            val outcome = runCatching(block)
            publish()
            result.complete(outcome)
        })
        return result.await().getOrThrow()
    }
    private fun append(action: AuditAction, outcome: AuditOutcome, state: AuditState?,
        details: AuditDetails, operation: Long?, timeMs: Long, marker: String? = null) {
        events.addLast(AuditEvent(timeMs.coerceAtLeast(0), session, ++sequence, action, outcome,
            state?.copy(stopCount = state.stopCount.coerceIn(0, COUNT_LIMIT)), details.copy(
                localMatches = details.localMatches?.coerceIn(0, COUNT_LIMIT),
                remoteMatches = details.remoteMatches?.coerceIn(0, COUNT_LIMIT),
                elapsedMs = details.elapsedMs?.coerceIn(0, retentionMs),
                index = details.index?.coerceIn(0, COUNT_LIMIT), count = details.count?.coerceIn(0, COUNT_LIMIT)),
            operation?.takeIf { it > 0 }, marker, versionCode.coerceAtLeast(0), apiLevel.coerceIn(0, 1000)))
        trim()
    }
    private fun trim() {
        val oldest = (now() - retentionMs).coerceAtLeast(0)
        val kept = events.filter { it.timeMs >= oldest && it.timeMs <= now() + 60_000L }
        dropped = safeAdd(dropped, (events.size - kept.size).toLong())
        events.clear(); events.addAll(kept)
        while (events.size > maxEvents) { events.removeFirst(); dropped = safeAdd(dropped, 1) }
    }
    private fun persist() {
        trim()
        var bytes = storedJson().toString().toByteArray(Charsets.UTF_8)
        while (bytes.size > maxBytes && events.isNotEmpty()) {
            events.removeFirst(); dropped = safeAdd(dropped, 1)
            bytes = storedJson().toString().toByteArray(Charsets.UTF_8)
        }
        try {
            check(bytes.size <= maxBytes)
            storage.write(bytes)
            storageFailed = false
        } catch (_: Exception) { storageFailed = true }
    }
    private fun storedJson(): JSONObject = JSONObject().put("schema", 1).put("enabled", enabled)
        .put("droppedEvents", dropped).put("events", eventArray())
    private fun eventArray(): JSONArray = JSONArray().also { array -> events.forEach { array.put(it.toJson()) } }
    private fun publish() { statusSink.value = AuditStatus(enabled, events.size, storageFailed, true, dropped) }

    private fun load(bytes: ByteArray) {
        require(bytes.size <= maxBytes)
        val text = bytes.toString(Charsets.UTF_8)
        require(shallowJson(text))
        val root = JSONObject(text)
        require(root.long("schema", 1, 1) == 1L)
        val loadedEnabled = root.boolean("enabled")
        val loadedDropped = root.long("droppedEvents", 0, Long.MAX_VALUE)
        val array = root.getJSONArray("events")
        enabled = loadedEnabled
        dropped = loadedDropped
        val first = (array.length() - maxEvents).coerceAtLeast(0)
        dropped = safeAdd(dropped, first.toLong())
        for (index in first until array.length()) {
            try { events.addLast(parseEvent(array.getJSONObject(index))) }
            catch (_: Exception) { dropped = safeAdd(dropped, 1) }
        }
    }

    private fun parseEvent(json: JSONObject): AuditEvent {
        val eventSession = json.uuid("session")
        val state = json.optJSONObject("state")?.let { value ->
            AuditState(value.enumOrNull<PlanMode>("planMode"), value.enumOrNull<Phase>("phase"),
                value.enumOrNull<Field>("field"), value.int("stopCount"), value.boolean("hasStart"),
                value.boolean("hasDestination"), value.boolean("running"), value.boolean("paused"),
                value.boolean("startFromReal"), value.boolean("useSimulatedStart"), value.boolean("queueAfterCurrent"))
        }
        require(!json.has("state") || json.isNull("state") || state != null)
        val value = json.getJSONObject("details")
        val details = AuditDetails(value.enumRequired("reason"), value.enumOrNull("source"),
            value.intOrNull("localMatches"), value.intOrNull("remoteMatches"),
            value.longOrNull("elapsedMs", 0, retentionMs), value.intOrNull("index"),
            value.intOrNull("count"), value.enumOrNull("control"))
        return AuditEvent(json.long("timeMs", 0, Long.MAX_VALUE), eventSession,
            json.long("sequence", 1, Long.MAX_VALUE), json.enumRequired("action"),
            json.enumRequired("outcome"), state, details, json.longOrNull("operation", 1, Long.MAX_VALUE),
            if (json.has("marker")) json.uuid("marker") else null,
            json.longOrNull("versionCode", 0, Int.MAX_VALUE.toLong())?.toInt(),
            json.longOrNull("apiLevel", 0, 1000)?.toInt())
    }

    private fun AuditEvent.toJson(): JSONObject = JSONObject().put("timeMs", timeMs)
        .put("session", session).put("sequence", sequence).put("action", action.name)
        .put("outcome", outcome.name).apply {
            operation?.let { put("operation", it) }; marker?.let { put("marker", it) }
            this@toJson.versionCode?.let { put("versionCode", it) }
            this@toJson.apiLevel?.let { put("apiLevel", it) }
            state?.let { value -> put("state", JSONObject().apply {
                value.planMode?.let { put("planMode", it.name) }; value.phase?.let { put("phase", it.name) }
                value.field?.let { put("field", it.name) }; put("stopCount", value.stopCount)
                put("hasStart", value.hasStart); put("hasDestination", value.hasDestination)
                put("running", value.running); put("paused", value.paused)
                put("startFromReal", value.startFromReal); put("useSimulatedStart", value.useSimulatedStart)
                put("queueAfterCurrent", value.queueAfterCurrent)
            }) }
            put("details", JSONObject().apply {
                put("reason", details.reason.name); details.source?.let { put("source", it.name) }
                details.control?.let { put("control", it.name) }
                details.localMatches?.let { put("localMatches", it) }; details.remoteMatches?.let { put("remoteMatches", it) }
                details.elapsedMs?.let { put("elapsedMs", it) }; details.index?.let { put("index", it) }
                details.count?.let { put("count", it) }
            })
        }

    companion object { private const val COUNT_LIMIT = 100_000 }
}

private fun safeAdd(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
private fun JSONObject.boolean(key: String): Boolean = get(key).let { require(it is Boolean); it }
private fun JSONObject.long(key: String, min: Long, max: Long): Long = get(key).let {
    require(it is Int || it is Long)
    (it as Number).toLong().also { value -> require(value in min..max) }
}
private fun JSONObject.longOrNull(key: String, min: Long, max: Long): Long? =
    if (!has(key)) null else long(key, min, max)
private fun JSONObject.int(key: String): Int = long(key, 0, 100_000).toInt()
private fun JSONObject.intOrNull(key: String): Int? = if (!has(key)) null else int(key)
private inline fun <reified T : Enum<T>> JSONObject.enumRequired(key: String): T {
    val value = get(key); require(value is String)
    return enumValues<T>().firstOrNull { it.name == value } ?: throw IllegalArgumentException("Invalid audit enum")
}
private inline fun <reified T : Enum<T>> JSONObject.enumOrNull(key: String): T? =
    if (!has(key)) null else enumRequired(key)
private fun JSONObject.uuid(key: String): String {
    val value = get(key); require(value is String && value.length == 36)
    require(UUID.fromString(value).toString() == value)
    return value
}
/** Reject deeply nested corrupt input before org.json recursively allocates/parses it. */
private fun shallowJson(text: String): Boolean {
    var depth = 0; var quoted = false; var escaped = false
    for (character in text) {
        if (escaped) { escaped = false; continue }
        if (quoted && character == '\\') { escaped = true; continue }
        if (character == '"') { quoted = !quoted; continue }
        if (!quoted) when (character) {
            '{', '[' -> { depth++; if (depth > 12) return false }
            '}', ']' -> { depth--; if (depth < 0) return false }
        }
    }
    return depth == 0 && !quoted
}
