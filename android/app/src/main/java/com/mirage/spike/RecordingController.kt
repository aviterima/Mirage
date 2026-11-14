package com.mirage.spike

import android.content.Context
import android.os.SystemClock
import com.mirage.spike.engine.Fix
import com.mirage.spike.recording.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class RecordingUiState(
    val active: TrackSummary? = null,
    val starting: Boolean = false,
    val finishing: Boolean = false,
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
                    store?.interrupt()
                    token++
                    source = null
                    mutable.update { it.copy(active = null, starting = false, finishing = true,
                        error = "Recording stopped: ${e.message ?: "storage unavailable"}. Earlier samples remain in Recordings.",
                        revision = it.revision + 1) }
                    withContext(Dispatchers.Main) { onFailure?.invoke() }
                }
            }
        }
    }

    @Synchronized fun initialize(context: Context) {
        if (store == null) store = FileTrackStore(File(context.applicationContext.noBackupFilesDir, "recordings"))
    }

    val realRecording: Boolean get() = source == TrackSource.REAL
    val recording: Boolean get() = source != null || mutable.value.finishing

    /** Called on Main, including the source reservation that excludes simulation. */
    fun start(context: Context, requested: TrackSource, onStarted: () -> Unit, onFailed: () -> Unit) {
        initialize(context)
        check(!recording) { "A recording is already active or still saving" }
        check(requested != TrackSource.REAL || !MockState.status.value.let { it.running || it.starting }) {
            "Stop simulation before recording a real journey"
        }
        val sessionToken = ++token
        source = requested
        onFailure = onFailed
        mutable.update { it.copy(starting = true, error = null) }
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        enqueue {
            if (token == sessionToken) {
                val summary = requireNotNull(store).start(requested, wall, elapsed)
                mutable.update { it.copy(active = summary, starting = false, revision = it.revision + 1) }
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
                    mutable.update { it.copy(active = store?.active) }
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
        mutable.update { it.copy(finishing = true) }
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        // Lifecycle controls must never be dropped when a slow disk fills the sample queue.
        scope.launch { commands.send {
            try { store?.finish(wall, elapsed) }
            catch (e: Exception) {
                store?.interrupt()
                mutable.update { it.copy(error = "Recording interrupted while saving. Captured locations remain recoverable.") }
            } finally {
                withContext(Dispatchers.Main) {
                    onFinished()
                    mutable.update { it.copy(active = null, starting = false, finishing = false, revision = it.revision + 1) }
                }
            }
        } }
    }

    fun reportError(message: String) { mutable.update { it.copy(error = message) } }

    suspend fun list(context: Context): List<TrackSummary> = withContext(Dispatchers.IO) {
        initialize(context); requireNotNull(store).list()
    }
    suspend fun read(context: Context, id: String): RecordedTrack = withContext(Dispatchers.IO) {
        initialize(context); requireNotNull(store).read(id)
    }
    suspend fun rename(id: String, name: String) = withContext(Dispatchers.IO) {
        requireNotNull(store).rename(id, name)
        mutable.update { it.copy(revision = it.revision + 1) }
    }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        requireNotNull(store).delete(id)
        mutable.update { it.copy(revision = it.revision + 1) }
    }

    private fun enqueue(action: suspend () -> Unit) {
        if (commands.trySend(action).isFailure) {
            source = null
            mutable.update { it.copy(starting = false, finishing = true, error = "Recording storage is too slow. Saving captured samples.") }
            scope.launch(Dispatchers.Main) { onFailure?.invoke() }
        }
    }
}
