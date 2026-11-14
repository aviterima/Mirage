package com.mirage.spike

import android.content.Context
import android.os.SystemClock
import com.mirage.spike.engine.Fix
import com.mirage.spike.recording.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class RecordingUiState(
    val active: TrackSummary? = null,
    val starting: Boolean = false,
    val error: String? = null,
    val revision: Long = 0,
)

/** Disk writes stay off the location-output thread and retain their arrival order. */
object RecordingController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(RecordingUiState())
    val state = mutable.asStateFlow()
    @Volatile private var source: TrackSource? = null
    @Volatile private var token = 0L
    @Volatile private var store: FileTrackStore? = null
    private var onFailure: (() -> Unit)? = null
    private val commands = Channel<suspend () -> Unit>(512)

    init {
        scope.launch {
            for (command in commands) {
                try { command() }
                catch (e: Exception) {
                    source = null
                    mutable.value = mutable.value.copy(active = null, starting = false,
                        error = "Recording stopped: ${e.message ?: "storage unavailable"}. Earlier samples remain in Recordings.",
                        revision = mutable.value.revision + 1)
                    withContext(Dispatchers.Main) { onFailure?.invoke() }
                }
            }
        }
    }

    @Synchronized fun initialize(context: Context) {
        if (store == null) store = FileTrackStore(File(context.applicationContext.noBackupFilesDir, "recordings"))
    }

    val realRecording: Boolean get() = source == TrackSource.REAL
    val recording: Boolean get() = source != null

    /** Called on Main, including the source reservation that excludes simulation. */
    fun start(context: Context, requested: TrackSource, onStarted: () -> Unit, onFailed: () -> Unit) {
        initialize(context)
        check(source == null) { "A recording is already active" }
        check(requested != TrackSource.REAL || !MockState.status.value.let { it.running || it.starting }) {
            "Stop simulation before recording a real journey"
        }
        val sessionToken = ++token
        source = requested
        onFailure = onFailed
        mutable.value = mutable.value.copy(starting = true, error = null)
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        enqueue {
            if (token == sessionToken) {
                val summary = requireNotNull(store).start(requested, wall, elapsed)
                mutable.value = mutable.value.copy(active = summary, starting = false, revision = mutable.value.revision + 1)
                withContext(Dispatchers.Main) { if (source != null) onStarted() }
            }
        }
    }

    fun sample(kind: TrackSource, fix: Fix, wallMillis: Long, elapsedMillis: Long) {
        if (source != kind) return
        val sessionToken = token
        enqueue {
            if (token == sessionToken && store?.active != null) {
                if (requireNotNull(store).append(fix, wallMillis, elapsedMillis)) {
                    mutable.value = mutable.value.copy(active = store?.active)
                }
            }
        }
    }

    fun gap() {
        if (source != TrackSource.EMULATED) return
        val sessionToken = token
        enqueue { if (token == sessionToken) store?.gap() }
    }

    fun finish(onFinished: () -> Unit = {}) {
        source = null // no more samples; already queued samples are flushed before finish
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        enqueue {
            store?.finish(wall, elapsed)
            mutable.value = mutable.value.copy(active = null, starting = false, revision = mutable.value.revision + 1)
            withContext(Dispatchers.Main) { onFinished() }
        }
    }

    fun reportError(message: String) { mutable.value = mutable.value.copy(error = message) }

    suspend fun list(context: Context): List<TrackSummary> = withContext(Dispatchers.IO) {
        initialize(context); requireNotNull(store).list()
    }
    suspend fun read(context: Context, id: String): RecordedTrack = withContext(Dispatchers.IO) {
        initialize(context); requireNotNull(store).read(id)
    }
    suspend fun rename(id: String, name: String) = withContext(Dispatchers.IO) {
        requireNotNull(store).rename(id, name)
        mutable.value = mutable.value.copy(revision = mutable.value.revision + 1)
    }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        requireNotNull(store).delete(id)
        mutable.value = mutable.value.copy(revision = mutable.value.revision + 1)
    }

    private fun enqueue(action: suspend () -> Unit) {
        if (commands.trySend(action).isFailure) {
            source = null
            mutable.value = mutable.value.copy(starting = false, error = "Recording storage is too slow. Stop and save the captured samples.")
            scope.launch(Dispatchers.Main) { onFailure?.invoke() }
        }
    }
}
