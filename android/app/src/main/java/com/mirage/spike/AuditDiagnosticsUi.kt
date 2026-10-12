package com.mirage.spike

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mirage.spike.audit.AuditTrail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** User-controlled diagnostics. The report leaves private app storage only after a file is chosen. */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun AuditDiagnosticsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by AuditTrail.status.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val messageIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(message) { if (message != null) messageIntoView.bringIntoView() }
    val maxBodyHeight = (LocalConfiguration.current.screenHeightDp * .58f).dp
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            message = null
            try {
                withContext(Dispatchers.IO) {
                    val report = AuditTrail.exportReport()
                    val output = context.contentResolver.openOutputStream(uri, "wt")
                        ?: error("Report destination unavailable")
                    output.bufferedWriter(Charsets.UTF_8).use { it.write(report) }
                }
                message = "Report saved. Share that file in our chat, with what you expected and what happened. Nothing was sent automatically."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A document-provider exception can contain a user's file path or account details.
                message = "Couldn't save the diagnostic report. Please choose a file and try again."
            } finally {
                busy = false
            }
        }
    }
    AlertDialog(
        modifier = Modifier.testTag("auditDiagnostics"),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Interaction audit") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = maxBodyHeight).imePadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Keeps recent actions, app states and results on this device to help diagnose problems. History is limited to 1,000 events and seven days.")
                Text(
                    "No locations, place or trip names, typed searches, voice recordings or transcripts, or keys are recorded. Reports are sent only when you choose to share them.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Record interactions", Modifier.weight(1f))
                    Switch(
                        checked = status.enabled,
                        enabled = status.ready && !busy,
                        modifier = Modifier.testTag("auditEnabled").semantics { contentDescription = "Record interactions" },
                        onCheckedChange = { enabled ->
                            scope.launch {
                                busy = true
                                try {
                                    AuditTrail.setEnabled(enabled)
                                    message = if (enabled) "Recording enabled." else "Recording paused. Existing history is kept until you clear it."
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    message = "Couldn't change recording. Please try again."
                                } finally { busy = false }
                            }
                        },
                    )
                }
                Text("${status.eventCount} recorded events", Modifier.testTag("auditEventCount"), style = MaterialTheme.typography.bodySmall)
                if (!status.enabled) Text("Recording is paused. Enable it before reproducing a problem.")
                if (status.storageFailed) Text(
                    "Some history could not be saved on this device. The report may be incomplete.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("auditStorageError"),
                )
                Text("After a problem, mark it here and export the report. Tell us the marker ID so we can find the relevant actions.", style = MaterialTheme.typography.bodySmall)
                Button(
                    enabled = status.ready && status.enabled && !busy,
                    modifier = Modifier.fillMaxWidth().testTag("auditMarkProblem"),
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val marker = AuditTrail.markProblem()
                                message = "Problem marker: $marker. Export the report and include this ID when you share it."
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                message = "Couldn't mark the problem. Check recording is enabled and try again."
                            } finally { busy = false }
                        }
                    },
                ) { Text("Mark a problem") }
                OutlinedButton(
                    enabled = status.ready && !busy,
                    modifier = Modifier.fillMaxWidth().testTag("auditExport"),
                    onClick = {
                        message = null
                        try { export.launch("Mirage-diagnostic-report.json") }
                        catch (_: Exception) { message = "Couldn't open the file chooser. Please try again." }
                    },
                ) { Text("Export diagnostic report") }
                TextButton(
                    enabled = status.ready && !busy,
                    modifier = Modifier.fillMaxWidth().testTag("auditClear"),
                    onClick = { confirmClear = true },
                ) { Text("Clear history") }
                message?.let {
                    SelectionContainer {
                        Text(it, Modifier.testTag("auditMessage").bringIntoViewRequester(messageIntoView), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (busy || !status.ready) Text("Working…", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.testTag("auditDone")) { Text("Done") }
        },
    )
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear interaction history?") },
        text = { Text("This removes recorded events and problem markers from this device. Saved places and trips are kept. Reports already exported are separate files.") },
        confirmButton = {
            TextButton(modifier = Modifier.testTag("auditConfirmClear"), onClick = {
                confirmClear = false
                scope.launch {
                    busy = true
                    try {
                        AuditTrail.clear()
                        message = "Interaction history cleared."
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        message = "Couldn't clear history. Please try again."
                    } finally { busy = false }
                }
            }) { Text("Clear history") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}
