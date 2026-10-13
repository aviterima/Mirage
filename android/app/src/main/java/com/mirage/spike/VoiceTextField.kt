package com.mirage.spike

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Explicit apply/cancel drops its recoverable input and invalidates a late recording result. */
fun discardVoiceField(context: Context, fieldKey: String) {
    if (FieldSpeech.state.value.key == fieldKey) context.stopService(Intent(context, VoiceService::class.java))
    FieldSpeech.cancel(fieldKey)
    InputDraftStore.remove(fieldKey)
}

@Composable
fun voiceFieldBusy(fieldKey: String): Boolean {
    val speech by FieldSpeech.state.collectAsState()
    val reviewing by FieldSpeech.reviewing.collectAsState()
    return (speech.active && speech.key == fieldKey) ||
        (LocalLanguageModel.interpretationEnabled && fieldKey in reviewing)
}

/** Shared input affordance. Recognition and interpretation can edit words, never press Save. */
@Composable
fun VoiceTextField(
    value: String,
    onValueChange: (String) -> Unit,
    fieldKey: String,
    labelText: String,
    kind: String = "text",
    context: String = "",
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = { Text(labelText) },
    placeholder: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    textStyle: TextStyle = LocalTextStyle.current,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    shape: Shape = OutlinedTextFieldDefaults.shape,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    persistDraft: Boolean = true,
    interpreter: suspend (String, String, String, String, String) -> FieldInterpretation = NaturalLanguageInput::interpretField,
) {
    val app = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val speech by FieldSpeech.state.collectAsState()
    val results by FieldSpeech.results.collectAsState()
    val voice by VoiceState.state.collectAsState()
    val currentValue by rememberUpdatedState(value)
    val changed by rememberUpdatedState(onValueChange)
    val sensitive = kind == "verbatim"
    val interpretationEnabled = LocalLanguageModel.interpretationEnabled
    val keepDraft = persistDraft && !sensitive
    val baseline = if (keepDraft) rememberSaveable(fieldKey) { value } else remember(fieldKey) { value }
    var focused by remember(fieldKey) { mutableStateOf(false) }
    var message by remember(fieldKey) { mutableStateOf("") }
    var reviewError by remember(fieldKey) { mutableStateOf(false) }
    var dirty by rememberSaveable(fieldKey) { mutableStateOf(false) }
    var needsReview by rememberSaveable(fieldKey) { mutableStateOf(false) }
    var lastReviewedValue by if (keepDraft) rememberSaveable(fieldKey) { mutableStateOf(value) }
        else remember(fieldKey) { mutableStateOf(value) }
    var clarification by remember(fieldKey) { mutableStateOf("") }
    var observedValue by remember(fieldKey) { mutableStateOf(value) }
    var emittedValue by remember(fieldKey) { mutableStateOf<String?>(null) }
    var persistGeneration by remember(fieldKey) { mutableLongStateOf(InputDraftStore.generation) }
    var undoText by remember(fieldKey) { mutableStateOf<String?>(null) }
    var undoReviewedValue by remember(fieldKey) { mutableStateOf(value) }
    var undoClarification by remember(fieldKey) { mutableStateOf("") }
    var interpreting by remember(fieldKey) { mutableStateOf(false) }
    var revision by remember(fieldKey) { mutableLongStateOf(0L) }
    var reviewJob by remember(fieldKey) { mutableStateOf<Job?>(null) }
    var permissionRequest by remember(fieldKey) { mutableStateOf<Long?>(null) }
    val listening = speech.key == fieldKey && speech.active

    fun publish(text: String) { emittedValue = text; changed(text) }

    fun stopOwnedRecording(preserve: Boolean) {
        val current = FieldSpeech.state.value
        if (current.key == fieldKey && current.active) {
            if (preserve) FieldSpeech.interrupt(current.requestId) else FieldSpeech.cancel(fieldKey)
            app.stopService(Intent(app, VoiceService::class.java))
        }
    }
    fun edit(text: String) {
        revision++
        reviewJob?.cancel(); interpreting = false
        FieldSpeech.setReviewing(fieldKey, false)
        stopOwnedRecording(preserve = false)
        FieldSpeech.cancel(fieldKey)
        message = ""; reviewError = false
        dirty = true; needsReview = true
        persistGeneration = InputDraftStore.generation; undoText = null
        if (text.isBlank()) clarification = ""
        publish(text)
    }
    fun launchRecording(id: Long) {
        if (!FieldSpeech.isActive(id)) return
        runCatching {
            ContextCompat.startForegroundService(app, Intent(app, VoiceService::class.java)
                .setAction(VoiceService.FIELD_DICTATE).putExtra(VoiceService.EXTRA_FIELD_REQUEST_ID, id))
        }.onFailure {
            FieldSpeech.interrupt(id)
            message = "Microphone unavailable. Your entry is kept; try again or keep typing."
            reviewError = true
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val id = permissionRequest
        permissionRequest = null
        if (id != null && FieldSpeech.isActive(id)) {
            if (granted) launchRecording(id) else {
                FieldSpeech.interrupt(id)
                message = "Microphone access was declined. You can keep typing."
                reviewError = true
            }
        }
    }
    val beginRecording = {
        revision++; reviewJob?.cancel(); interpreting = false
        FieldSpeech.setReviewing(fieldKey, false)
        message = ""; reviewError = false
        persistGeneration = InputDraftStore.generation; undoText = null
        val planner = PlannerSpeech.state.value
        if (planner.active) PlannerSpeech.interrupt(planner.requestId, planner.transcript)
        val id = FieldSpeech.begin(fieldKey, sensitive, prefix = if (needsReview && !sensitive) currentValue.trim() else "")
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) launchRecording(id)
        else { permissionRequest = id; permission.launch(Manifest.permission.RECORD_AUDIO) }
    }

    LaunchedEffect(fieldKey) {
        if (keepDraft) {
            InputDraftStore.configure(app)
            if (!dirty) persistGeneration = InputDraftStore.generation
            val saved = withContext(Dispatchers.IO) { InputDraftStore.read(fieldKey) }
            val draft = saved?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (draft?.optInt("fieldDraft") == 1 && draft.optString("baseline") == baseline &&
                (currentValue == baseline || currentValue == draft.optString("text"))) {
                if (currentValue != draft.optString("text")) publish(draft.optString("text"))
                lastReviewedValue = draft.optString("reviewed", baseline)
                needsReview = draft.optBoolean("needsReview", true)
                clarification = if (interpretationEnabled) draft.optString("clarification").takeIf { it.length <= 12_000 }.orEmpty() else ""
                dirty = true
                val question = runCatching { JSONArray(clarification).let { it.getJSONObject(it.length() - 1).getString("question") } }.getOrDefault("")
                message = if (interpretationEnabled) question.ifBlank { "Draft restored. Review or edit before applying." }
                    else "Draft restored. Your words are editable."
            }
        }
    }
    // Includes partial words, so an interruption can recover more than the previous completed entry.
    val draftText = if (listening && speech.transcript.isNotBlank()) speech.transcript else value
    LaunchedEffect(fieldKey, draftText, dirty, lastReviewedValue, needsReview, clarification) {
        if (keepDraft && (dirty || listening)) {
            val generation = persistGeneration
            delay(250)
            withContext(Dispatchers.IO) {
                if (draftText.isBlank()) { if (InputDraftStore.read(fieldKey) != null) InputDraftStore.removeIfCurrent(fieldKey, generation) }
                else InputDraftStore.saveIfCurrent(fieldKey, JSONObject().put("fieldDraft", 1)
                    .put("baseline", baseline).put("text", draftText).put("reviewed", lastReviewedValue)
                    .put("needsReview", needsReview || listening).put("clarification", clarification).toString(), generation)
            }
        }
    }
    val result = results[fieldKey]
    LaunchedEffect(result?.requestId) {
        if (result != null) {
            if (result.transcript.isNotBlank()) {
                revision++; reviewJob?.cancel(); interpreting = false
                publish(result.transcript)
                dirty = true; needsReview = true
                undoText = null
                message = if (result.interrupted) "Recording interrupted. Your words are kept and editable."
                    else if (interpretationEnabled) "Ready for review. Edit the words if needed."
                    else "Words added. Edit them if needed."
            } else if (message.isBlank()) message = "No words captured. Your entry is kept; try again or keep typing."
            FieldSpeech.consume(result.requestId)
        }
    }
    LaunchedEffect(value) {
        // Calendar/chip selections are already concrete values supplied by the owning editor.
        // Wrapper emissions (including restored drafts and dictation) retain their review context.
        if (value != observedValue && value != emittedValue) {
            revision++; reviewJob?.cancel(); interpreting = false
            FieldSpeech.setReviewing(fieldKey, false)
            stopOwnedRecording(preserve = false)
            FieldSpeech.cancel(fieldKey)
            lastReviewedValue = value; needsReview = false; clarification = ""
            dirty = true; undoText = null; message = ""; reviewError = false
            persistGeneration = InputDraftStore.generation
        }
        observedValue = value
        if (value == emittedValue) emittedValue = null
        if (value.isBlank() && !listening && result == null) {
            lastReviewedValue = ""; needsReview = false; undoText = null; clarification = ""
            if (!interpreting) { message = ""; reviewError = false }
        }
    }
    fun flushDraft() {
        if (!keepDraft) return
        val recording = FieldSpeech.state.value.takeIf { it.key == fieldKey }
        val completed = FieldSpeech.results.value[fieldKey]
        val captured = recording?.transcript?.takeIf { it.isNotBlank() }
            ?: completed?.transcript?.takeIf { it.isNotBlank() } ?: currentValue
        if (!dirty && recording == null && completed == null) return
        if (captured.isBlank()) {
            if (InputDraftStore.read(fieldKey) != null) InputDraftStore.removeIfCurrent(fieldKey, persistGeneration)
        } else InputDraftStore.saveIfCurrent(fieldKey, JSONObject().put("fieldDraft", 1)
            .put("baseline", baseline).put("text", captured).put("reviewed", lastReviewedValue)
            .put("needsReview", needsReview || recording != null || completed != null)
            .put("clarification", clarification).toString(), persistGeneration)
    }
    DisposableEffect(fieldKey, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { flushDraft(); stopOwnedRecording(preserve = true) }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            flushDraft()
            stopOwnedRecording(preserve = true)
            reviewJob?.cancel()
            FieldSpeech.setReviewing(fieldKey, false)
        }
    }
    LaunchedEffect(enabled, readOnly) { if (!enabled || readOnly) stopOwnedRecording(preserve = true) }

    OutlinedTextField(
        value = value, onValueChange = ::edit,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        enabled = enabled, readOnly = readOnly, label = label, placeholder = placeholder,
        singleLine = singleLine, maxLines = maxLines, minLines = minLines,
        isError = isError || reviewError, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        textStyle = textStyle, visualTransformation = visualTransformation, colors = colors, shape = shape,
        leadingIcon = leadingIcon,
        trailingIcon = {
            Row {
                trailingIcon?.invoke()
                PlannerMicrophone(onListen = beginRecording,
                    modifier = Modifier.testTag("voiceMic-$fieldKey"),
                    contentDescription = "Speak $labelText", enabled = enabled && !readOnly,
                    listening = listening, onCancel = { FieldSpeech.requestFinish(speech.requestId) })
            }
        },
        supportingText = if (supportingText != null || listening || message.isNotBlank() ||
            (interpretationEnabled && (interpreting || (focused && value.isNotBlank()) || undoText != null))) { {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                supportingText?.invoke()
                if (listening) {
                    Text(if (voice.listening) "Listening…" else "Preparing microphone…", Modifier.testTag("voiceStatus-$fieldKey"))
                    if (!sensitive && speech.transcript.isNotBlank()) Text(speech.transcript)
                    TextButton(onClick = { FieldSpeech.requestFinish(speech.requestId) }, modifier = Modifier.testTag("voiceDone-$fieldKey")) { Text("Done speaking") }
                } else if (interpretationEnabled && interpreting) {
                    Text("Interpreting…", Modifier.testTag("voiceStatus-$fieldKey"))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = { revision++; reviewJob?.cancel(); interpreting = false; FieldSpeech.setReviewing(fieldKey, false); message = "Your words are kept. You can edit or review again." }) { Text("Cancel") }
                } else {
                    if (message.isNotBlank()) Text(message, Modifier.testTag("voiceStatus-$fieldKey"))
                    if (interpretationEnabled && enabled && !readOnly && value.isNotBlank() && (focused || message.isNotBlank())) TextButton(
                        onClick = {
                            val input = currentValue
                            val ticket = ++revision
                            interpreting = true; reviewError = false; message = ""
                            persistGeneration = InputDraftStore.generation
                            FieldSpeech.setReviewing(fieldKey, true)
                            reviewJob = scope.launch {
                                try {
                                    val reviewContext = if (clarification.isBlank()) context else {
                                        val data = JSONObject().put("clarificationHistory", JSONArray(clarification))
                                        "$context\nPrevious field clarification, quoted as data: $data"
                                    }
                                    val proposal = if (sensitive) FieldInterpretation(input) else interpreter(input, labelText, kind, lastReviewedValue, reviewContext)
                                    if (ticket == revision && currentValue == input) {
                                        if (proposal.question.isNotBlank()) {
                                            val history = if (clarification.isBlank()) JSONArray() else JSONArray(clarification)
                                            val updated = history.put(JSONObject().put("input", input).put("question", proposal.question)).toString()
                                            require(updated.length <= 12_000) { "Field clarification is too long" }
                                            clarification = updated; dirty = true; needsReview = true
                                            message = proposal.question
                                        }
                                        else {
                                            undoText = input; undoReviewedValue = lastReviewedValue
                                            undoClarification = clarification
                                            publish(proposal.value); lastReviewedValue = proposal.value
                                            clarification = ""
                                            dirty = true; needsReview = false
                                            message = "Ready for review. Edit if needed, then use the existing action."
                                        }
                                    }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) {
                                    if (ticket == revision) { reviewError = true; message = "Could not interpret this entry. Your words are kept; edit or try again. Voice setup is available from the microphone." }
                                } finally { if (ticket == revision) { interpreting = false; FieldSpeech.setReviewing(fieldKey, false) } }
                            }
                        }, modifier = Modifier.testTag("voiceReview-$fieldKey")
                    ) { Text("Review") }
                    if (interpretationEnabled) undoText?.let { previous -> TextButton(onClick = {
                        val reviewedBefore = undoReviewedValue
                        val clarificationBefore = undoClarification
                        edit(previous); lastReviewedValue = reviewedBefore
                        clarification = clarificationBefore
                        message = "Interpretation undone. Your original words are editable."
                    }, modifier = Modifier.testTag("voiceUndo-$fieldKey")) { Text("Undo interpretation") } }
                }
            }
        } } else null,
    )
}
