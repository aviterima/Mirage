package com.mirage.spike

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mirage.spike.engine.*
import com.mirage.spike.store.SavedScenario

private fun actionLabel(d: ContinuationDraft): String = when (d.placement) {
    Placement.NOW -> if (PlaybackSource.paused) "Go now and resume" else "Go now"
    Placement.NEXT -> "Add after current stop"
    Placement.END -> "Add at end of trip"
}

@Composable
fun ContinuationPreviewBar(planner: ContinuationPlanner, onConfirm: () -> Unit) {
    val d = planner.state ?: return
    if(d.pinTarget!=null) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).testTag("continuationPreview"),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable { planner.edit(true) }.padding(4.dp)) {
            Text(d.title.ifBlank { "Add stop" }, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Review · " + (d.stops.firstOrNull()?.address?.takeIf { it.isNotBlank() } ?: planner.originLabel(d)),
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = planner::cancel) { Text("Cancel") }
        Button(onClick = onConfirm, enabled = d.ready && !d.previewBusy) { Text(if(d.replaceId!=null) "Replace upcoming stop" else actionLabel(d)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContinuationSheet(planner: ContinuationPlanner, saved: List<SavedScenario>, onConfirm: () -> Unit, onStop: () -> Unit, onHike: () -> Unit = {}) {
    val d = planner.state ?: return
    if (!d.editorOpen) return
    val keyboard = LocalSoftwareKeyboardController.current
    var filter by remember(d.source) { mutableStateOf("") }
    var savedMessage by remember(d.stops) { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = { if (d.stops.isEmpty()) planner.cancel() else planner.edit(false) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 590.dp).imePadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).testTag("continuationEditor"),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if(d.replaceId!=null) "Replace upcoming stop" else "Add stop", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = planner::cancel) { Text("Cancel") }
            }
            // All sources stay visible: no sideways scrolling to discover saved content.
            DestinationSource.entries.chunked(2).forEach { sources ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sources.forEach { source ->
                        FilterChip(selected = source == d.source,
                            onClick = { keyboard?.hide(); planner.source(source) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("destinationSource-${source.name}"),
                            label = { Text(source.label) })
                    }
                }
            }
            OutlinedButton(onClick={keyboard?.hide();planner.mapPick("destination")},modifier=Modifier.fillMaxWidth()) { Text("Drop a pin on map") }
            OutlinedButton(onClick=onHike, modifier=Modifier.fillMaxWidth().testTag("liveHiking")) { Text("Hiking trail · drive, park and hike") }
            Text(planner.originLabel(d), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if(d.replaceId==null) Placement.entries.forEach { choice ->
                    FilterChip(selected = choice == d.placement, onClick = { planner.placement(choice) },
                        enabled = choice == Placement.NOW || LiveSession.plan != null, label = { Text(choice.label) })
                }
            }
            if (d.stops.isEmpty()) {
                if (d.source == DestinationSource.SEARCH) {
                    OutlinedTextField(value = d.query, onValueChange = { planner.query(it) },
                        label = { Text("Place, address and city") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("continuationQuery"),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); planner.query(d.query, true) }))
                    if (d.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                    d.hits.forEach { hit ->
                        Column(Modifier.fillMaxWidth().clickable { keyboard?.hide(); planner.pick(hit) }.padding(vertical = 8.dp)) {
                            Text(hit.name, fontWeight = FontWeight.SemiBold)
                            Text(hit.address.ifBlank { "${hit.latLng.lat}, ${hit.latLng.lng}" }, style = MaterialTheme.typography.bodySmall)
                            runCatching { planner.origin(d) }.getOrNull()?.let {
                                Text("${fmtMiles(Geo.haversine(it, hit.latLng))} from planned departure", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        HorizontalDivider()
                    }
                } else {
                    OutlinedTextField(value = filter, onValueChange = { filter = it }, label = { Text("Find a saved item") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    val items = com.mirage.spike.store.savedItemSearch(saved,filter,d.source.name)
                    Text("Favorites first, then recently used",style=MaterialTheme.typography.bodySmall)
                    if (items.isEmpty()) Text("No ${d.source.label.lowercase()} match.")
                    items.forEach { item ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text((if(item.favorite) "★ " else "")+item.name, fontWeight = FontWeight.SemiBold)
                            Text(when (d.source) {
                                DestinationSource.ROUTE -> "${item.startName.ifBlank { "Saved start" }} → ${item.destName}"
                                DestinationSource.ITINERARY -> "${item.stops.size} stops · " + item.stops.joinToString(" → ") { it.name }
                                else -> item.destAddress.ifBlank { item.destName }
                            }, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { keyboard?.hide(); planner.saved(item) }, modifier = Modifier.testTag("continueSaved-${item.id}")) {
                                    Text(when(d.source) { DestinationSource.ITINERARY -> "Use itinerary"; DestinationSource.ROUTE -> "Use route"; else -> "Add place" })
                                }
                                if (d.source == DestinationSource.ROUTE) TextButton(onClick = { keyboard?.hide(); planner.saved(item, true) }) { Text("Destination only") }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            } else {
                Text(d.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                d.stops.forEachIndexed { index, stop ->
                    Text("${index + 1}. ${stop.name}", fontWeight = FontWeight.SemiBold)
                    Text(stop.address.ifBlank { "${stop.point.lat}, ${stop.point.lng}" }, style = MaterialTheme.typography.bodySmall)
                    Text("${stop.mode.name.lowercase()} · ${if (stop.dwellMinutes == 0) "no timed stay" else "stay ${stop.dwellMinutes} min"}", style = MaterialTheme.typography.bodySmall)
                    if (d.stops.size > 1) Row {
                        if (index > 0) TextButton(onClick = { planner.fromStop(index) }) { Text("Start at this stop") }
                        TextButton(onClick = { planner.onlyStop(index) }) { Text("Only this stop") }
                    }
                }
                if (d.adaptedRealStart) Text("This saved plan is adapted to start from your simulated trip.", style = MaterialTheme.typography.bodySmall)
                d.savedOrigin?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = d.connectStart, onCheckedChange = planner::connectStart)
                        Text("Travel to ${d.savedOriginName} first")
                    }
                    if (!d.connectStart) Text("Starts from your simulated trip instead of the saved origin.", style = MaterialTheme.typography.bodySmall)
                }
                if (d.source in listOf(DestinationSource.SEARCH, DestinationSource.SNAP) || (d.savedOrigin != null && d.connectStart)) {
                    Text(if (d.savedOrigin != null) "Connection travel mode" else "Travel mode", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TravelMode.entries.forEach { mode ->
                            FilterChip(selected = d.mode == mode, onClick = { planner.mode(mode) }, label = { Text(mode.name.lowercase()) })
                        }
                    }
                }
                if (d.stops.size == 1) {
                    TextButton(onClick={keyboard?.hide();planner.mapPick("destination")}) {Text("Fine-tune destination pin")}
                    TextButton(onClick={keyboard?.hide();planner.mapPick("entrance")}) {Text(if(d.stops.single().entrance==null) "Set entrance / parking pin" else "Adjust entrance / parking pin")}
                    if(d.stops.single().entrance!=null) TextButton(onClick=planner::clearEntrance){Text("Remove entrance pin")}
                    ArrivalActivityPicker(d.stops.single().arrivalActivity, planner::arrival)
                    StayChoicePicker(d.stops.single().dwellMinutes,d.stops.single().stayUntilLeave,planner::stay,planner::stayUntilLeave)
                    TextButton(onClick={savedMessage=if(planner.savePlace()) "Place settings saved" else "Could not save place settings"}) {Text("Save these place settings")}
                    if(savedMessage.isNotBlank()) Text(savedMessage)

                }
                if (d.placement == Placement.NOW && LiveSession.plan != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = d.keepRemaining, onCheckedChange = planner::keepRemaining)
                        Text("Keep my remaining stops")
                    }
                    Text(if (d.keepRemaining) "The existing unvisited stops follow this addition." else "This replaces all remaining stops.", style = MaterialTheme.typography.bodySmall)
                }
                if (d.previewBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                d.stops.singleOrNull()?.let { stop ->
                    val road=d.points.lastOrNull()
                    if(road!=null && d.savedOrigin==null && stop.mode!=TravelMode.FLY && stop.arrivalActivity.isIndoor) {
                        val entrance=stop.entrance ?: road
                        val gap=Geo.haversine(road,entrance)+Geo.haversine(entrance,stop.point)
                        Text("Arrival preview: road endpoint → " + (if(stop.entrance!=null) "entrance → " else "") + "destination pin · approximately ${gap.toInt()} m walking")
                        if(gap<2.0) Text("The route ends at this pin. Check that it is inside the building; no interior location is inferred.",style=MaterialTheme.typography.bodySmall)
                        if(Geo.haversine(road,entrance)>150 || Geo.haversine(entrance,stop.point)>150) Text("Check these pins: an arrival connection exceeds 150 m. The simulation will hold at the road endpoint.",color=MaterialTheme.colorScheme.error)
                    }
                }
                if (d.summary.isNotBlank()) Text(d.summary + if (d.stops.size > 1 || (d.savedOrigin != null && d.connectStart)) " · first leg; later legs route on departure" else "")
                if (PlaybackSource.paused && d.placement != Placement.NOW) Text("Adding will keep the simulation paused.")
                TextButton(onClick = { planner.source(d.source) }) { Text("Choose another destination") }
            }
            d.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                if (d.stops.isNotEmpty()) TextButton(onClick = planner::retryPreview) { Text("Retry preview") }
            }
            if (d.stops.isNotEmpty()) {
                OutlinedButton(onClick = { keyboard?.hide(); planner.edit(false) }, modifier = Modifier.fillMaxWidth()) { Text("Review on map") }
                Button(onClick = { keyboard?.hide(); onConfirm() }, enabled = d.ready && !d.previewBusy,
                    modifier = Modifier.fillMaxWidth().testTag("confirmContinuation")) { Text(if(d.replaceId!=null) "Replace upcoming stop" else actionLabel(d)) }
            }
            TextButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop simulation") }
            Spacer(Modifier.height(12.dp))
        }
    }
}
