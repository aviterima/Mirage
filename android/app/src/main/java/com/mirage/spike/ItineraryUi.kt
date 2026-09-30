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
            if (LiveSession.plan?.canUndo() == true) TextButton(onClick = {
                message = if (LiveSession.plan?.undoEdit() == true) "Edit undone" else "The trip advanced; completed movement cannot be undone"
            }) { Text("Undo last edit") }
            var showVisited by remember { mutableStateOf(false) }
            var stayId by remember { mutableStateOf<String?>(null) }
            if (session.index > 0) TextButton(onClick={showVisited=!showVisited}) { Text(if(showVisited) "Hide visited stops" else "Show ${session.index} visited stops") }
            if(showVisited) session.stops.take(session.index.coerceAtLeast(0)).forEach { Text("✓ ${it.stop.name}") }
            session.stops.getOrNull(session.index)?.let { current ->
                Text("Current: ${current.stop.name}", fontWeight=FontWeight.Bold)
                Text(if(session.activity==ActivityKind.STAYING) "${(session.remainingStaySeconds+59)/60} min remaining" else session.activity.name.lowercase())
            }
            val future=session.stops.drop((session.index+1).coerceAtLeast(0))
            ReorderableStopList(future.map{it.stop}, onMove={from,to ->
                val entry=future.getOrNull(from)
                if(entry != null && LiveSession.plan?.move(entry.id,to-from)!=true) message="The trip advanced; review the remaining stops"
            }) { i,stop,handle ->
                val entry=future[i]
                ChainRow(index=session.index+1+i,stop=stop,entry=null,current=false,done=false,
                    onMode={LiveSession.plan?.setMode(entry.id,it)},onStay={stayId=entry.id},
                    onMove={LiveSession.plan?.move(entry.id,it)},onRemove={LiveSession.plan?.remove(entry.id)},
                    handleModifier=handle,canMoveUp=i>0,canMoveDown=i<future.lastIndex)
            }
            stayId?.let { id ->
                session.stops.firstOrNull{it.id==id}?.let { entry -> DwellDialog(entry.stop.name,entry.stop.dwellMinutes,
                    onSet={LiveSession.plan?.setStay(id,it);stayId=null},onDismiss={stayId=null},deadline=entry.stop.arriveByMillis,onDeadline={LiveSession.plan?.setDeadline(id,it)}) }
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
