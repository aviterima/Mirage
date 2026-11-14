package com.mirage.spike.recording

import com.mirage.spike.engine.Fix
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Use one instance with a directory under Context.noBackupFilesDir; call on an IO worker. */
class FileTrackStore(private val directory: File) {
    private data class Session(
        var summary: TrackSummary,
        val startedMonotonic: Long,
        var lastObserved: TrackSample? = null,
        var lastWritten: TrackSample? = null,
        var pendingGap: Boolean = false,
    )
    private var session: Session? = null
    val active: TrackSummary? @Synchronized get() = session?.summary

    @Synchronized fun start(source: TrackSource, wallTimeMillis: Long, monotonicMillis: Long, name: String? = null): TrackSummary {
        check(session == null) { "A recording is already active" }
        require(monotonicMillis >= 0) { "Invalid recording clock" }
        ensureDirectory()
        check((directory.listFiles { f -> f.extension == "json" }?.size ?: 0) < 200) { "Recording library is full. Delete a recording first." }
        val summary = TrackSummary(UUID.randomUUID().toString(), cleanName(name ?: RecordingPolicy.suggestedName(source, wallTimeMillis)),
            source, wallTimeMillis, null, 0, 0, TrackState.ACTIVE)
        writeMetadata(summary)
        session = Session(summary, monotonicMillis)
        return summary
    }

    /** False means invalid, out-of-order, or intentionally decimated; storage errors throw. */
    @Synchronized fun append(fix: Fix, wallTimeMillis: Long, monotonicMillis: Long): Boolean {
        val current = session ?: return false
        if (!RecordingPolicy.valid(fix) || monotonicMillis < current.startedMonotonic) return false
        val offset = monotonicMillis - current.startedMonotonic
        check(offset <= RecordingPolicy.MAX_DURATION_MILLIS) { "Recording reached the 48-hour limit. Stop and save it." }
        val previous = current.lastObserved
        if (previous != null && offset <= previous.offsetMillis) return false
        if (previous != null && offset - previous.offsetMillis > RecordingPolicy.CONTINUITY_MILLIS) gap("Location updates interrupted")
        val sample = TrackSample(offset, wallTimeMillis, fix, current.pendingGap)
        current.lastObserved = sample
        current.summary = current.summary.copy(durationMillis = offset)
        val written = current.lastWritten
        val interval = if (written != null && RecordingPolicy.stationary(written.fix, fix))
            RecordingPolicy.STATIONARY_INTERVAL_MILLIS else RecordingPolicy.MOVING_INTERVAL_MILLIS
        if (written != null && !current.pendingGap && offset - written.offsetMillis < interval) return false
        writeSample(current, sample)
        return true
    }

    /** Marks missing output explicitly. Repeat calls while already in a gap are harmless. */
    @Synchronized fun gap(reason: String = "Location unavailable") {
        val current = session ?: return
        if (current.pendingGap) return
        flushLatest(current)
        val offset = current.lastObserved?.offsetMillis ?: 0L
        appendRecord(current.summary.id, JSONObject().put("type", "gap").put("offset", offset)
            .put("reason", reason.take(160)))
        current.pendingGap = true
        current.summary = current.summary.copy(gapCount = current.summary.gapCount + 1)
    }

    @Synchronized fun finish(wallTimeMillis: Long, monotonicMillis: Long): TrackSummary? {
        val current = session ?: return null
        flushLatest(current)
        val duration = maxOf(current.summary.durationMillis,
            (monotonicMillis - current.startedMonotonic).coerceIn(0, RecordingPolicy.MAX_DURATION_MILLIS))
        val result = current.summary.copy(endedAtMillis = wallTimeMillis, durationMillis = duration, state = TrackState.SAVED)
        writeMetadata(result)
        session = null
        return result
    }

    @Synchronized fun list(): List<TrackSummary> {
        if (!directory.exists()) return emptyList()
        return (directory.listFiles { f -> f.extension == "json" } ?: emptyArray()).mapNotNull { file ->
            runCatching {
                val metadata = parseMetadata(JSONObject(readBounded(file, 8192)))
                if (metadata.id == session?.summary?.id) session!!.summary
                else if (metadata.state == TrackState.ACTIVE) recover(metadata).summary else metadata
            }.getOrNull()
        }.sortedByDescending { it.startedAtMillis }
    }

    @Synchronized fun read(id: String): RecordedTrack {
        val metadata = if (id == session?.summary?.id) session!!.summary else readMetadata(id)
        return recover(metadata)
    }

    @Synchronized fun rename(id: String, name: String): TrackSummary {
        val old = if (id == session?.summary?.id) session!!.summary else read(id).summary
        val updated = old.copy(name = cleanName(name))
        writeMetadata(updated)
        if (id == session?.summary?.id) session!!.summary = updated
        return updated
    }

    @Synchronized fun delete(id: String) {
        check(id != session?.summary?.id) { "Stop and save the recording before deleting it" }
        val metadata = metadataFile(id)
        check(metadata.exists()) { "Recording not found" }
        val samples = samplesFile(id)
        if (samples.exists()) check(samples.delete()) { "Could not delete recorded locations" }
        check(metadata.delete()) { "Could not delete recording" }
    }

    private fun flushLatest(current: Session) {
        val latest = current.lastObserved ?: return
        if (current.lastWritten?.offsetMillis != latest.offsetMillis) writeSample(current, latest)
    }

    private fun writeSample(current: Session, sample: TrackSample) {
        check(current.summary.sampleCount < RecordingPolicy.MAX_SAMPLES) { "Recording is full. Stop and save it." }
        val fix = sample.fix
        appendRecord(current.summary.id, JSONObject().put("type", "sample").put("offset", sample.offsetMillis)
            .put("wall", sample.wallTimeMillis).put("lat", fix.lat).put("lng", fix.lng).put("speed", fix.speedMps.toDouble())
            .put("bearing", fix.bearingDeg.toDouble()).put("accuracy", fix.accuracyM.toDouble()).put("altitude", fix.altitudeM)
            .put("gapBefore", sample.gapBefore))
        current.lastWritten = sample
        current.pendingGap = false
        current.summary = current.summary.copy(sampleCount = current.summary.sampleCount + 1)
    }

    private fun recover(metadata: TrackSummary): RecordedTrack {
        val samples = mutableListOf<TrackSample>()
        val gaps = mutableListOf<TrackGap>()
        val file = samplesFile(metadata.id)
        var interrupted = false
        if (file.exists()) {
            check(file.length() <= RecordingPolicy.MAX_TRACK_BYTES) { "Recording exceeds storage limit" }
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.length > 2048) { interrupted = true; break }
                    val parsed = runCatching {
                        val objectValue = JSONObject(line)
                        val offset = objectValue.getLong("offset")
                        require(offset in 0..RecordingPolicy.MAX_DURATION_MILLIS)
                        when (objectValue.getString("type")) {
                            "sample" -> {
                                val fix = Fix(objectValue.getDouble("lat"), objectValue.getDouble("lng"),
                                    objectValue.getDouble("speed").toFloat(), objectValue.getDouble("bearing").toFloat(),
                                    objectValue.getDouble("accuracy").toFloat(), objectValue.getDouble("altitude"))
                                require(RecordingPolicy.valid(fix) && (samples.isEmpty() || offset > samples.last().offsetMillis))
                                require(samples.size < RecordingPolicy.MAX_SAMPLES)
                                samples += TrackSample(offset, objectValue.getLong("wall"), fix, objectValue.optBoolean("gapBefore"))
                            }
                            "gap" -> gaps += TrackGap(offset, objectValue.optString("reason", "Location unavailable").take(160))
                            else -> error("Unknown recording entry")
                        }
                    }
                    // A partial last write or damaged suffix cannot erase the valid prefix.
                    if (parsed.isFailure) { interrupted = true; break }
                }
            }
        }
        val state = when {
            metadata.id == session?.summary?.id -> TrackState.ACTIVE
            metadata.state == TrackState.ACTIVE || interrupted -> TrackState.INTERRUPTED
            else -> metadata.state
        }
        val duration = if (state == TrackState.INTERRUPTED) samples.lastOrNull()?.offsetMillis ?: 0
            else maxOf(metadata.durationMillis, samples.lastOrNull()?.offsetMillis ?: 0)
        return RecordedTrack(metadata.copy(state = state, durationMillis = duration, sampleCount = samples.size, gapCount = gaps.size), samples, gaps)
    }

    private fun appendRecord(id: String, json: JSONObject) {
        val file = samplesFile(id)
        val bytes = (json.toString() + "\n").toByteArray(Charsets.UTF_8)
        check(file.length() + bytes.size <= RecordingPolicy.MAX_TRACK_BYTES) { "Recording is full. Stop and save it." }
        FileOutputStream(file, true).use { it.write(bytes); it.fd.sync() }
    }

    private fun writeMetadata(summary: TrackSummary) {
        ensureDirectory()
        val destination = metadataFile(summary.id)
        val temporary = File(directory, "${summary.id}.pending")
        val objectValue = JSONObject().put("version", 1).put("id", summary.id).put("name", summary.name)
            .put("source", summary.source.name).put("started", summary.startedAtMillis)
            .put("ended", summary.endedAtMillis ?: JSONObject.NULL).put("duration", summary.durationMillis)
            .put("samples", summary.sampleCount).put("state", summary.state.name).put("gaps", summary.gapCount)
        FileOutputStream(temporary).use { it.write(objectValue.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
        try { Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun readMetadata(id: String): TrackSummary = parseMetadata(JSONObject(readBounded(metadataFile(id), 8192)))
    private fun parseMetadata(value: JSONObject): TrackSummary {
        require(value.getInt("version") == 1) { "Unsupported recording" }
        val id = value.getString("id")
        checkId(id)
        return TrackSummary(id, cleanName(value.getString("name")), TrackSource.valueOf(value.getString("source")),
            value.getLong("started"), if (value.isNull("ended")) null else value.getLong("ended"),
            value.getLong("duration").also { require(it in 0..RecordingPolicy.MAX_DURATION_MILLIS) },
            value.getInt("samples").also { require(it in 0..RecordingPolicy.MAX_SAMPLES) },
            TrackState.valueOf(value.getString("state")), value.optInt("gaps"))
    }
    private fun readBounded(file: File, maxBytes: Int): String {
        require(file.length() in 1..maxBytes.toLong()) { "Invalid recording metadata" }
        return file.readText(Charsets.UTF_8)
    }
    private fun ensureDirectory() { check(directory.isDirectory || directory.mkdirs()) { "Could not create private recording storage" } }
    private fun checkId(id: String) { require(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) { "Invalid recording identifier" } }
    private fun metadataFile(id: String): File { checkId(id); return File(directory, "$id.json") }
    private fun samplesFile(id: String): File { checkId(id); return File(directory, "$id.jsonl") }
    private fun cleanName(name: String): String = name.trim().also { require(it.isNotEmpty() && it.length <= 160) { "Use a recording name of 1–160 characters" } }
}
