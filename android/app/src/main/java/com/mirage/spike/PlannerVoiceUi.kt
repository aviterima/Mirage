package com.mirage.spike

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers

/** The pending plan occupies the normal preview slot, so there is only one itinerary list. */
@Composable
internal fun PlannerCandidatePreview(candidate: PlanCandidate) {
    Column(Modifier.fillMaxWidth().testTag("plannerPreview").padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Review your plan", style = MaterialTheme.typography.titleSmall)
        candidate.origin?.let { Text("From ${it.label}", style = MaterialTheme.typography.bodySmall) }
        Text(candidate.departureMillis?.let { "Departure ${tripTime(it)}" } ?: "Depart when started", style = MaterialTheme.typography.bodySmall)
        candidate.preview.forEachIndexed { index, stop ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                Column(Modifier.fillMaxWidth().padding(8.dp).testTag("plannerReviewStop$index")) {
                    Text("${index + 1}. ${stop.label}", style = MaterialTheme.typography.bodyMedium)
                    Text("${stop.mode.name.lowercase().replaceFirstChar { it.uppercase() }} · ${stop.stayMinutes} min stay", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun PlannerReviewFeedback(controller: PlannerReviewController, hasUnreviewedInput: Boolean = false, onApplied: () -> Unit) {
    val view by controller.state.collectAsState()
    if (view.status.isBlank() && view.question.isBlank() && view.candidate == null && !view.busy) return
    Column(Modifier.fillMaxWidth().padding(8.dp).testTag("plannerReviewFeedback"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (view.busy) "Interpreting…" else view.status, style = MaterialTheme.typography.bodySmall)
        if (view.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (view.question.isNotBlank()) Text(view.question, Modifier.testTag("plannerReviewQuestion"), style = MaterialTheme.typography.bodyMedium)
        if (view.canConfirm && hasUnreviewedInput) Text("Review the edited entry before applying changes.", style = MaterialTheme.typography.bodySmall)
        if (view.choices.isNotEmpty()) SuggestionList(view.choices, false, null, "this part of the plan",
            choiceTagPrefix = "plannerReviewChoice", onPick = controller::choose)
        if (view.candidate != null || view.busy || view.question.isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (view.canConfirm) Button(enabled = !hasUnreviewedInput, onClick = { if (controller.confirm()) onApplied() },
                modifier = Modifier.testTag("plannerReviewApply")) { Text("Apply changes") }
            TextButton(onClick = controller::reset, modifier = Modifier.testTag("plannerReviewCancel")) { Text("Discard review") }
        }
    }
}

/** Readiness gate only: the planner owns permissions, dictation targets, and editable words. */
@Composable
fun PlannerMicrophone(
    onListen: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = "Use microphone",
    enabled: Boolean = true,
    listening: Boolean = false,
    onCancel: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val modelStatus by LocalLanguageModel.state.collectAsState(context = Dispatchers.Main.immediate)
    LaunchedEffect(context) { LocalLanguageModel.configure(context) }
    PlannerMicrophone(
        voiceReady = LocalLanguageModel.ready,
        modelStatus = modelStatus,
        onDownload = { LocalLanguageModel.download() },
        onCancelDownload = { LocalLanguageModel.cancelDownload() },
        onListen = onListen,
        modifier = modifier,
        contentDescription = contentDescription,
        enabled = enabled,
        listening = listening,
        onCancel = onCancel
    )
}

/** Explicit state also lets the setup transitions be exercised without downloading a model. */
@Composable
internal fun PlannerMicrophone(
    voiceReady: Boolean,
    modelStatus: String,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onListen: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = "Use microphone",
    enabled: Boolean = true,
    listening: Boolean = false,
    onCancel: (() -> Unit)? = null
) {
    var showSetup by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(enabled) { if (!enabled) showSetup = false }
    IconButton(
        onClick = {
            focus.clearFocus(); keyboard?.hide()
            when {
                listening -> onCancel?.invoke()
                voiceReady -> onListen()
                else -> showSetup = true
            }
        },
        modifier = modifier,
        enabled = enabled && (!listening || onCancel != null)
    ) {
        Icon(
            imageVector = if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = if (listening) "Finish listening" else contentDescription
        )
    }
    if (showSetup) VoiceSetupSheet(
        voiceReady = voiceReady,
        modelStatus = modelStatus,
        onDownload = onDownload,
        onCancelDownload = onCancelDownload,
        onDismiss = { showSetup = false },
        onUseMicrophone = {
            showSetup = false
            onListen()
        }
    )
}

@Composable
fun VoiceSetupSheet(onDismiss: () -> Unit, onUseMicrophone: () -> Unit) {
    val context = LocalContext.current
    val modelStatus by LocalLanguageModel.state.collectAsState(context = Dispatchers.Main.immediate)
    LaunchedEffect(context) { LocalLanguageModel.configure(context) }
    VoiceSetupSheet(
        voiceReady = LocalLanguageModel.ready,
        modelStatus = modelStatus,
        onDownload = { LocalLanguageModel.download() },
        onCancelDownload = { LocalLanguageModel.cancelDownload() },
        onDismiss = onDismiss,
        onUseMicrophone = onUseMicrophone
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceSetupSheet(
    voiceReady: Boolean,
    modelStatus: String,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDismiss: () -> Unit,
    onUseMicrophone: () -> Unit
) {
    val downloading = !voiceReady && modelStatus.startsWith("Downloading")
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier.fillMaxWidth().testTag("voiceSetup").imePadding().padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Voice setup", style = MaterialTheme.typography.titleLarge)
            Text(
                if (voiceReady) "Voice is ready. Speak into the selected entry, then review or edit the words."
                else "Download voice once to speak into any entry. You can edit and review the words before applying them.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(modelStatus, Modifier.testTag("voiceSetupStatus"), style = MaterialTheme.typography.bodySmall)
            when {
                voiceReady -> Button(
                    onClick = onUseMicrophone,
                    modifier = Modifier.fillMaxWidth().testTag("voiceSetupUseMicrophone")
                ) { Text("Use microphone") }
                downloading -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedButton(
                        onClick = onCancelDownload,
                        modifier = Modifier.fillMaxWidth().testTag("voiceDownloadCancel")
                    ) { Text("Cancel download") }
                }
                else -> Button(
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth().testTag("voiceDownload")
                ) {
                    Text(if (modelStatus.startsWith("Advanced voice not downloaded")) "Download voice · 610 MB" else "Retry download · 610 MB")
                }
            }
            if (!voiceReady) Text(
                "Use Wi-Fi for the 610 MB download and keep at least 750 MB free. You can keep typing while it downloads.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Voice is processed on this phone. Place search and routing use your Maps connection.",
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("voiceSetupClose")) {
                Text(if (voiceReady) "Close" else "Keep typing")
            }
        }
    }
}

/** Only the current reply and actionable review; no persistent transcript or second itinerary. */
@Composable
fun PlannerConversationFeedback(modifier: Modifier = Modifier) {
    val chat by Conversation.state.collectAsState(context = Dispatchers.Main.immediate)
    val needsReply = Conversation.needsReply()
    val reply = chat.lines.lastOrNull()?.takeUnless { it.user }?.text
    if (reply == null && chat.choices.isEmpty() && !chat.busy && !needsReply) return
    Surface(
        modifier = modifier.fillMaxWidth().testTag("plannerVoiceFeedback"),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (chat.busy) {
                Text("Understanding…", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (chat.choices.isNotEmpty()) {
                Text("Choose a place", style = MaterialTheme.typography.bodyMedium)
            } else if (reply != null) {
                Text(reply, Modifier.testTag("plannerVoiceReply"), style = MaterialTheme.typography.bodyMedium)
            }
            if (chat.choices.isNotEmpty()) SuggestionList(chat.choices, false, null, "the selected field",
                choiceTagPrefix = "plannerVoiceChoice") { place -> Conversation.choose(chat.choices.indexOf(place)) }
            if (needsReply || chat.busy) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (needsReply && chat.choices.isEmpty() && !chat.busy) Button(
                    onClick = { Conversation.submit("yes") },
                    modifier = Modifier.testTag("plannerVoiceConfirm")
                ) { Text("Confirm") }
                TextButton(
                    onClick = { Conversation.submit("cancel") },
                    modifier = Modifier.testTag("plannerVoiceCancel")
                ) { Text("Cancel") }
            }
        }
    }
}
