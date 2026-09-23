package com.mirage.spike

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import com.mirage.spike.engine.*

@Composable
fun LiveControls(status: MockStatus, session: SessionView, onNow: () -> Unit, onNext: () -> Unit, onStop: () -> Unit, onChat: () -> Unit, onStops: () -> Unit, onAdvanced: () -> Unit) {
    val current = session.stops.getOrNull(session.index)?.stop
    val next = session.stops.getOrNull(session.index + 1)?.stop
    val title = when {
        status.paused -> "Paused here"
        session.activity == ActivityKind.ROUTING -> "Finding your route"
        session.activity == ActivityKind.TRAVELING -> "To ${current?.name ?: status.label}"
        session.activity == ActivityKind.STAYING -> "At ${current?.name ?: status.label}"
        else -> "Holding at ${current?.name ?: status.label.ifBlank { "this location" }}"
    }
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(when (session.activity) {
        ActivityKind.TRAVELING -> "${(status.speedMps / 0.44704).toInt()} mph" + if (status.remainingSec >= 0) " · about ${fmtDuration(status.remainingSec.toDouble())} left" else ""
        ActivityKind.STAYING -> "${fmtDuration(session.remainingStaySeconds.toDouble())} of simulated stay remaining"
        ActivityKind.ROUTING -> "Holding your position while the route is prepared"
        else -> "Simulation stays on until you stop it"
    }, style = MaterialTheme.typography.bodyMedium)
    Text(next?.let { "Next: ${it.name} · stay ${it.dwellMinutes} min" } ?: "No further stops", style = MaterialTheme.typography.bodySmall)
    if (status.health != Health.GREEN) Text(status.message, color = MaterialTheme.colorScheme.error)
    if (status.stepLabel.startsWith("Route failed")) Text(status.stepLabel, color = MaterialTheme.colorScheme.error)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (session.activity in listOf(ActivityKind.TRAVELING, ActivityKind.STAYING) || status.paused) {
            OutlinedButton(onClick = { Conversation.submit(if (status.paused) "continue" else "pause") }, modifier = Modifier.weight(1f)) { Text(if (status.paused) "Resume" else "Pause") }
            OutlinedButton(onClick = { Conversation.submit("skip ahead") }, modifier = Modifier.weight(1f)) { Text(if (session.activity == ActivityKind.STAYING) "Leave now" else "Jump to arrival") }
        }
    }
    if (session.activity == ActivityKind.HOLDING) TextButton(onClick = { Conversation.submit("stay for thirty minutes") }) { Text("Stay for 30 minutes") }
    if (session.activity == ActivityKind.STAYING) TextButton(onClick = { Conversation.submit("extend stay by fifteen minutes") }) { Text("＋ Stay 15 minutes longer") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onNow, modifier = Modifier.weight(1f)) { Text(if (session.activity == ActivityKind.HOLDING) "Go somewhere" else "Change destination") }
        OutlinedButton(onClick = onNext, modifier = Modifier.weight(1f)) { Text("Go there next") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onStops, enabled = session.stops.isNotEmpty()) { Text("Upcoming stops") }
        TextButton(onClick = onAdvanced) { Text("Advanced") }
    }

}

@Composable
fun UpcomingDialog(session: SessionView, onDismiss: () -> Unit, onAdd: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Upcoming stops") }, text = {
        Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val upcoming = session.stops.drop(session.index + 1)
            if (upcoming.isEmpty()) Text("No further stops. Add a destination to continue from here.")
            upcoming.forEachIndexed { i, entry ->
                Text("${i + 1}. ${entry.stop.name}", fontWeight = FontWeight.Bold)
                Text("${entry.stop.mode.name.lowercase()} · stay ${entry.stop.dwellMinutes} min")
                var modeMenu by remember(entry.id) { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { modeMenu = true }) { Text("Change travel mode") }
                    DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                        TravelMode.entries.forEach { mode -> DropdownMenuItem(text = { Text(mode.name.lowercase()) }, onClick = { LiveSession.plan?.setMode(entry.id, mode); modeMenu = false }) }
                    }
                }
                Row {
                    TextButton(onClick = { LiveSession.plan?.setStay(entry.id, entry.stop.dwellMinutes - 15) }) { Text("−15 min") }
                    TextButton(onClick = { LiveSession.plan?.setStay(entry.id, entry.stop.dwellMinutes + 15) }) { Text("＋15 min") }
                }
                Row {
                    TextButton(onClick = { LiveSession.plan?.move(entry.id, -1) }, enabled = i > 0) { Text("Up") }
                    TextButton(onClick = { LiveSession.plan?.move(entry.id, 1) }, enabled = i < upcoming.lastIndex) { Text("Down") }
                    TextButton(onClick = { LiveSession.plan?.remove(entry.id) }) { Text("Remove") }
                }
                HorizontalDivider()
            }
            Text("Changes apply to the remaining trip. The current leg continues.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }, dismissButton = { TextButton(onClick = onAdd) { Text("Add a stop") } })
}

@Composable
fun AdvancedDialog(status: MockStatus, onDismiss: () -> Unit) {
    var scale by remember { mutableStateOf(PlaybackSource.timeScale) }
    var over by remember { mutableStateOf(PlaybackSource.speedOverLimitMph.toFloat()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Live advanced controls") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Fast-forward · ${scale.toInt()}×")
            Row { listOf(1.0, 2.0, 5.0, 10.0).forEach { n -> TextButton(onClick = { scale = n; PlaybackSource.timeScale = n }) { Text("${n.toInt()}×") } } }
            Text("Driving · estimated road limit ${if (over >= 0) "+" else ""}${over.toInt()} mph")
            Slider(value = over, onValueChange = { over = it; PlaybackSource.speedOverLimitMph = it.toDouble() }, valueRange = -10f..15f)
            Text("Applies immediately to driving legs. Road limits are estimates.", style = MaterialTheme.typography.bodySmall)
            Text("GPS signal · ${status.signalName}")
            Row { Signal.PRESETS.forEach { preset -> TextButton(onClick = { PlaybackSource.signal = preset }) { Text(preset.name) } } }
            TextButton(onClick = { PlaybackSource.signal = PlaybackSource.signal.withDropout(15) }) { Text("Drop GPS for 15 seconds") }
            Text("${status.lat}, ${status.lng}\n${status.emittedCount} fixes · ${status.reassertCount} reassertions\nLeak detected: ${status.leakSeen}", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPanel(onDismiss: () -> Unit) {
    val chat by Conversation.state.collectAsState()
    val voice by VoiceState.state.collectAsState()
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var requestedAction by remember { mutableStateOf(VoiceService.LISTEN) }
    val launchVoice: (String) -> Unit = { action ->
        runCatching { ContextCompat.startForegroundService(context, Intent(context, VoiceService::class.java).setAction(action)) }
            .onFailure { Conversation.notice("Could not start microphone: ${it.message}") }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchVoice(requestedAction) else Conversation.notice("Microphone access was declined. You can still type instructions.")
    }
    val requestVoice: (String) -> Unit = { action ->
        requestedAction = action
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) launchVoice(action)
        else permissions.launch(Manifest.permission.RECORD_AUDIO)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Talk to Mirage", style = MaterialTheme.typography.titleLarge)
            Text(voice.status, color = if (voice.listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                TextButton(onClick = { requestVoice(VoiceService.LISTEN) }) { Text("🎙 Speak now") }
                TextButton(onClick = {
                    if (voice.enabled) context.stopService(Intent(context, VoiceService::class.java)) else requestVoice(VoiceService.ENABLE)
                }) { Text(if (voice.enabled) "Microphone off" else "Enable Hello Mirage") }
            }
            if (voice.listening) Text(voice.transcript.ifBlank { "Speak after the two-note chime…" })
            val scroll = rememberScrollState()
            LaunchedEffect(chat.lines.size, chat.choices.size) { scroll.animateScrollTo(scroll.maxValue) }
            Column(Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 300.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (chat.lines.isEmpty()) Text("Try ‘drive to Sky Harbor Terminal Four’, ‘after this, walk to a café’, or ‘what happens next’. Say ‘help’ for more commands.")
                chat.lines.forEach { line ->
                    Surface(color = if (line.user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                        Text((if (line.user) "You: " else "Mirage: ") + line.text, Modifier.padding(10.dp))
                    }
                }
                chat.choices.forEachIndexed { i, place ->
                    OutlinedButton(onClick = { Conversation.choose(i) }, modifier = Modifier.fillMaxWidth()) { Text("${i + 1}. ${place.name}\n${place.address}") }
                }
            }
            if (chat.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text("Tell Mirage what to do…") }, modifier = Modifier.weight(1f), maxLines = 3)
                Button(onClick = { Conversation.submit(text); text = "" }, enabled = text.isNotBlank()) { Text("Send") }
            }
            Text("Voice is processed on this phone. Place search and routing use your Maps connection. Hands-free stays enabled until you turn the microphone off.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
