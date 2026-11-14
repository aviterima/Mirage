package com.mirage.spike

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mirage.spike.engine.*
import com.mirage.spike.recording.*
import kotlinx.coroutines.launch

@Composable
fun RecordingsButton() {
    val state by RecordingController.state.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = Modifier.testTag("recordingsButton")) {
        Text(if (state.active != null || state.starting) "● Recording" else "Recordings")
    }
    if (open) RecordingsSheet { open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by RecordingController.state.collectAsState()
    val simulation by MockState.status.collectAsState()
    var tracks by remember { mutableStateOf(emptyList<TrackSummary>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pendingSource by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<TrackSummary?>(null) }
    var deleting by remember { mutableStateOf<TrackSummary?>(null) }
    var replaying by remember { mutableStateOf<TrackSummary?>(null) }
    var replayScale by remember { mutableStateOf(1.0) }

    LaunchedEffect(state.revision) {
        runCatching { RecordingController.list(context) }.onSuccess { tracks = it }
            .onFailure { error = it.message ?: "Could not open recordings" }
    }
    val begin: (TrackSource) -> Unit = { source ->
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, LocationRecordingService::class.java)
                .setAction(if (source == TrackSource.REAL) LocationRecordingService.REAL else LocationRecordingService.EMULATED))
        }.onFailure { error = it.message ?: "Could not start recording" }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val source = pendingSource?.let(TrackSource::valueOf)
        pendingSource = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            if (source != null) begin(source)
        } else error = "Allow precise location to record a journey. You can change this in the phone's app settings."
    }
    val requestRecording: (TrackSource) -> Unit = { source ->
        error = null
        pendingSource = source.name
        val requested = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (requested.isEmpty()) { pendingSource = null; begin(source) }
        else permissions.launch(requested.toTypedArray())
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        RecordingsContent(tracks, state, simulation.running || simulation.starting, busy, error,
            onRecord = requestRecording,
            onFinish = {
                runCatching { context.startService(Intent(context, LocationRecordingService::class.java).setAction(LocationRecordingService.STOP)) }
                    .onFailure { error = "Could not stop recording. Use Stop and save in the recording notification." }
            },
            onRename = { renaming = it }, onDelete = { deleting = it },
            onReplay = { replayScale = 1.0; replaying = it }, onDismiss = onDismiss)
    }
    renaming?.let { track ->
        var name by remember(track.id) { mutableStateOf(track.name) }
        AlertDialog(onDismissRequest = { if (!busy) renaming = null }, title = { Text("Name recording") },
            text = { OutlinedTextField(name, { name = it.take(160) }, label = { Text("Recording name") },
                singleLine = true, modifier = Modifier.testTag("recordingName")) },
            confirmButton = { TextButton(enabled = name.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    runCatching { RecordingController.rename(track.id, name) }.onSuccess { renaming = null }
                        .onFailure { error = it.message ?: "Could not rename recording" }
                    busy = false
                }
            }) { Text("Save name") } }, dismissButton = { TextButton(enabled = !busy, onClick = { renaming = null }) { Text("Cancel") } })
    }
    deleting?.let { track ->
        AlertDialog(onDismissRequest = { if (!busy) deleting = null }, title = { Text("Delete recording?") },
            text = { Text("Delete ${track.name} and its recorded locations from this device?") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    runCatching { RecordingController.delete(track.id) }.onSuccess { deleting = null }
                        .onFailure { error = it.message ?: "Could not delete recording" }
                    busy = false
                }
            }) { Text("Delete") } }, dismissButton = { TextButton(enabled = !busy, onClick = { deleting = null }) { Text("Cancel") } })
    }
    replaying?.let { track ->
        AlertDialog(onDismissRequest = { if (!busy) replaying = null }, title = { Text("Replay recording") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(track.name, fontWeight = FontWeight.Bold)
                Text("Start at the first recorded location and preserve the journey's timing. Missing sections hold the previous point until recording resumes.")
                if (simulation.running) Text("Replay replaces the current simulation.")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(1.0, 2.0, 5.0, 10.0).forEach { speed ->
                        FilterChip(selected = replayScale == speed, onClick = { replayScale = speed }, label = { Text("${speed.toInt()}×") })
                    }
                }
                if (RecordingController.recording) Text("Stop and save the active recording before replaying.")
            }
        }, confirmButton = { TextButton(enabled = !busy && !RecordingController.recording, onClick = {
            busy = true
            scope.launch {
                runCatching {
                    val recording = RecordingController.read(context, track.id)
                    check(!RecordingController.recording) { "Stop and save the active recording first" }
                    check(recording.samples.isNotEmpty()) { "No locations were recorded" }
                    check(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { "Allow precise location in Settings before replaying" }
                    PlaybackSource.clearQueue(); PlaybackSource.consumeSkip(); PlaybackSource.paused = false
                    PlaybackSource.timeScale = replayScale
                    PlaybackSource.current = recording.fixes(onGap = { gap ->
                        MockState.update { it.copy(stepLabel = if (gap) "Recording gap · holding last location" else "Replaying ${track.name}") }
                    })
                    // A bounded preview prevents day-long tracks overwhelming the map.
                    val stride = maxOf(1, recording.samples.size / 1000)
                    PlaybackSource.routePoints = recording.samples.filterIndexed { index, _ -> index % stride == 0 }
                        .map { LatLng(it.fix.lat, it.fix.lng) }
                    PlaybackSource.endPoint = recording.samples.last().fix.let { LatLng(it.lat, it.lng) }
                    PlaybackSource.label = "Replay · ${track.name}"
                    ContextCompat.startForegroundService(context, Intent(context, MockLocationService::class.java))
                }.onSuccess { replaying = null; onDismiss() }.onFailure { error = it.message ?: "Could not replay recording" }
                busy = false
            }
        }) { Text("Start replay") } }, dismissButton = { TextButton(enabled = !busy, onClick = { replaying = null }) { Text("Cancel") } })
    }
}

@Composable
internal fun RecordingsContent(
    tracks: List<TrackSummary>, state: RecordingUiState, simulationRunning: Boolean, busy: Boolean, error: String?,
    onRecord: (TrackSource) -> Unit, onFinish: () -> Unit, onRename: (TrackSummary) -> Unit,
    onDelete: (TrackSummary) -> Unit, onReplay: (TrackSummary) -> Unit, onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).imePadding().navigationBarsPadding().padding(16.dp)
        .testTag("recordingsLibrary"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Recordings", style = MaterialTheme.typography.titleLarge)
        Text("Saved on this device. Recording continues when you close this list.", style = MaterialTheme.typography.bodySmall)
        (error ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.starting) LinearProgressIndicator(Modifier.fillMaxWidth())
        val active = state.active
        if (active != null) {
            Text(if (active.source == TrackSource.REAL) "● Recording real journey" else "● Recording emulated day", fontWeight = FontWeight.Bold)
            Text(if (active.sampleCount == 0) "Waiting for locations…" else "${active.sampleCount} locations · ${recordingDuration(active.durationMillis)}")
            Button(onClick = onFinish, enabled = !busy, modifier = Modifier.testTag("stopRecording")) { Text("Stop and save") }
        } else if (!state.starting) {
            OutlinedButton(onClick = { onRecord(TrackSource.REAL) }, enabled = !simulationRunning && !busy,
                modifier = Modifier.fillMaxWidth().testTag("recordRealJourney")) { Text("Record real journey") }
            if (simulationRunning) Text("Stop simulation to record real locations.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { onRecord(TrackSource.EMULATED) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("recordEmulatedDay")) { Text("Record emulated day") }
            Text("Emulated recordings capture locations accepted by the phone's GPS providers across trips and pauses. Other apps' location use is not recorded.", style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        if (tracks.none { it.id != active?.id }) Text("Your saved recordings will appear here with editable suggested names.")
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tracks.filter { it.id != active?.id }, key = { it.id }) { track ->
                Column(Modifier.fillMaxWidth().testTag("recording-${track.id}")) {
                    Text(track.name, fontWeight = FontWeight.SemiBold)
                    Text("${if (track.source == TrackSource.REAL) "Real" else "Emulated"} · ${recordingDuration(track.durationMillis)} · ${track.sampleCount} locations", style = MaterialTheme.typography.bodySmall)
                    if (track.state == TrackState.INTERRUPTED) Text("Interrupted recording · captured locations recovered", style = MaterialTheme.typography.bodySmall)
                    if (track.gapCount > 0) Text("${track.gapCount} gaps in recorded locations", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { onReplay(track) }, enabled = !busy && active == null && !state.starting && track.sampleCount > 0,
                            modifier = Modifier.testTag("replayRecording-${track.id}")) { Text("Replay") }
                        TextButton(onClick = { onRename(track) }, enabled = !busy) { Text("Rename") }
                        TextButton(onClick = { onDelete(track) }, enabled = !busy) { Text("Delete") }
                    }
                    HorizontalDivider()
                }
            }
        }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
    }
}

private fun recordingDuration(millis: Long): String {
    val minutes = millis / 60_000
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}
