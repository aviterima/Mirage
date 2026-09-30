package com.mirage.spike

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.mirage.spike.engine.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyItinerarySheet(vm: MirageViewModel, onDismiss: () -> Unit, onAdd: () -> Unit) {
    val session by LiveSession.state.collectAsState()
    var saveNew by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).navigationBarsPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("My itinerary", style = MaterialTheme.typography.titleLarge)
            Text(session.savedName.ifBlank { "New trip" }, fontWeight = FontWeight.Bold)
            Text(if (session.dirty) "Unsaved changes" else "All changes saved", modifier = Modifier.testTag("itinerarySaveStatus"))
            Text("Your simulation continues while you edit. Saving includes the full trip from its starting point.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onAdd) { Text("Add stop") }
                if (session.savedId != null) Button(onClick = {
                    message = if (vm.saveActiveScenario(session.savedName, true)) "Changes saved" else vm.error ?: "Could not save"
                }, enabled = session.dirty, modifier = Modifier.testTag("saveTripChanges")) { Text("Save changes") }
            }
            TextButton(onClick = { name = ""; vm.error = null; saveNew = true }, modifier = Modifier.testTag("saveTripAsNew")) {
                Text(if (session.savedId == null) "Save itinerary" else "Save as new itinerary")
            }
            if (message.isNotBlank()) Text(message)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                session.stops.forEachIndexed { i, entry ->
                    val editable = i > session.index
                    Text("${i + 1}. ${entry.stop.name}", fontWeight = FontWeight.Bold)
                    Text(when { i < session.index -> "Visited"; i == session.index -> "Current stop"; else -> "Upcoming" })
                    if (entry.stop.address.isNotBlank()) Text(entry.stop.address, style = MaterialTheme.typography.bodySmall)
                    Text("${entry.stop.mode.name.lowercase()} · stay ${entry.stop.dwellMinutes} minutes")
                    if (editable) {
                        Row {
                            TextButton(onClick = { LiveSession.plan?.move(entry.id, -1) }, enabled = i > session.index + 1) { Text("Move up") }
                            TextButton(onClick = { LiveSession.plan?.move(entry.id, 1) }, enabled = i < session.stops.lastIndex) { Text("Move down") }
                            TextButton(onClick = { LiveSession.plan?.remove(entry.id) }) { Text("Remove") }
                        }
                        Row {
                            TextButton(onClick = { LiveSession.plan?.setStay(entry.id, entry.stop.dwellMinutes - 15) }) { Text("−15 min") }
                            TextButton(onClick = { LiveSession.plan?.setStay(entry.id, entry.stop.dwellMinutes + 15) }) { Text("+15 min") }
                            var modes by remember(entry.id) { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { modes = true }) { Text("Travel mode") }
                                DropdownMenu(expanded = modes, onDismissRequest = { modes = false }) {
                                    TravelMode.entries.forEach { mode -> DropdownMenuItem(text = { Text(mode.name.lowercase()) }, onClick = { LiveSession.plan?.setMode(entry.id, mode); modes = false }) }
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Back to map") }
        }
    }
    if (saveNew) AlertDialog(onDismissRequest = { saveNew = false }, title = { Text("Save itinerary") }, text = {
        Column {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Itinerary name") }, singleLine = true, modifier = Modifier.testTag("itineraryName"))
            if (vm.error != null) Text(vm.error!!, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { Button(onClick = {
        if (vm.saveActiveScenario(name)) { saveNew = false; message = "Saved $name" }
    }, enabled = name.isNotBlank(), modifier = Modifier.testTag("confirmSaveItinerary")) { Text("Save") } },
        dismissButton = { TextButton(onClick = { saveNew = false }) { Text("Cancel") } })
}
