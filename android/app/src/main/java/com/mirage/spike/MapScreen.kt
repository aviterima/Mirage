package com.mirage.spike

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.LatLng as GLatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import com.mirage.spike.engine.Geo
import com.mirage.spike.engine.ItineraryStop
import com.mirage.spike.engine.LatLng
import com.mirage.spike.engine.PlaceHit
import com.mirage.spike.engine.PlaybackSource
import com.mirage.spike.engine.Realism
import com.mirage.spike.engine.TravelMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private val ACCENT = Indigo
private val MUTED = Color(0xFF5F6368)
private val GREEN = Color(0xFF16A34A)
private val AMBER = Color(0xFFD97706)
private val RED = Color(0xFFDC2626)
private val VIOLET = Color(0xFF7C3AED)

/** Live state of the one-time device setup, read when the Setup dialog opens. */
data class SetupChecks(val location: Boolean, val notifications: Boolean, val battery: Boolean)

/** The simulation controls handed from the screen down to the sheet. */
private class SimActions(
    val getRoute: () -> Unit,
    val start: () -> Unit,
    val startItinerary: () -> Unit,
    val holdAt: (LatLng?) -> Unit,
)

@Composable
fun MapScreen(
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onOpenDevSettings: () -> Unit,
    onRequestBattery: () -> Unit,
    onRequestPermissions: () -> Unit,
    setupChecks: () -> SetupChecks,
) {
    val vm: MirageViewModel = viewModel()
    val status by MockState.status.collectAsState()
    var showSetup by remember { mutableStateOf(false) }
    var sheetCollapsed by remember { mutableStateOf(false) }
    // The control sheet may never take more than half the screen; the map keeps the rest.
    val maxSheet = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    val camera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(GLatLng(START_LAT, START_LNG), 13f)
    }
    val carState = rememberMarkerState()
    LaunchedEffect(status.lat, status.lng) { carState.position = GLatLng(status.lat, status.lng) }
    // Frame the whole route (or itinerary) in the visible part of the map.
    LaunchedEffect(vm.routePts) {
        if (vm.routePts.size >= 2) {
            val b = LatLngBounds.Builder()
            vm.routePts.forEach { b.include(it.toG()) }
            runCatching { camera.animate(CameraUpdateFactory.newLatLngBounds(b.build(), 90)) }
        }
    }
    // A problem must be visible even if the sheet was tucked away.
    LaunchedEffect(vm.error) { if (vm.error != null) sheetCollapsed = false }

    val hasLocPerm = ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.ACCESS_FINE_LOCATION
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    // Move the start pin and the camera to the device's REAL location: a fresh fix,
    // never the fused provider's cached last point (which is the mock after a Stop).
    val recentreOnReal: () -> Unit = {
        if (hasLocPerm) scope.launch {
            val p = realLocation(context)
            if (p != null) {
                vm.useMyLocation(p)
                runCatching { camera.animate(CameraUpdateFactory.newLatLngZoom(p.toG(), 14f)) }
            } else {
                vm.error = "Could not get a real fix right now"
            }
        }
    }
    // On first load (and once permission is granted), start from where the phone really is.
    LaunchedEffect(hasLocPerm) { if (!status.running) recentreOnReal() }
    // After a Stop, hand the app back to reality too: the old route is from a place we
    // no longer are, so the start pin and camera return to the real location.
    var wasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(status.running) {
        if (wasRunning && !status.running) recentreOnReal()
        wasRunning = status.running
    }

    val goTo: (LatLng) -> Unit = { p ->
        scope.launch { runCatching { camera.animate(CameraUpdateFactory.newLatLngZoom(p.toG(), 15f)) } }
    }
    val actions = SimActions(
        getRoute = { vm.buildRoute() },
        start = { onRequestPermissions(); vm.startSim(onStartService) },
        startItinerary = { onRequestPermissions(); vm.startItinerary(onStartService) },
        holdAt = { at -> onRequestPermissions(); armStatic(at); onStartService() },
    )
    val onStop = { onStopService(); vm.onStopped() }

    Box(Modifier.fillMaxSize()) {

        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            properties = MapProperties(isMyLocationEnabled = hasLocPerm),
            uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = hasLocPerm, compassEnabled = true),
            contentPadding = PaddingValues(
                top = topInset + 84.dp,
                bottom = when {
                    sheetCollapsed -> 100.dp
                    status.running -> 300.dp
                    else -> maxSheet
                },
            ),
            onMapClick = { vm.setDestPoint(it.toE()) },
            onMapLongClick = { vm.setStartPoint(it.toE()) },
        ) {
            val arrow = remember { runCatching { navigationArrow(ACCENT) }.getOrNull() }
            vm.start?.let { s ->
                Marker(
                    state = rememberMarkerState(key = "s-${s.lat},${s.lng}", position = s.toG()),
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN),
                    title = "Start",
                    snippet = vm.startName,
                )
            }
            vm.dest?.let { d ->
                Marker(
                    state = rememberMarkerState(key = "d-${d.lat},${d.lng}", position = d.toG()),
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET),
                    title = vm.destName,
                )
            }
            if (vm.routePts.isNotEmpty()) {
                Polyline(points = vm.routePts.map { it.toG() }, color = ACCENT, width = 14f)
            }
            if (status.running) {
                Marker(
                    state = carState,
                    icon = arrow ?: BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE),
                    anchor = Offset(0.5f, 0.5f),
                    flat = true,
                    rotation = status.bearingDeg,
                    zIndex = 10f,
                    title = "Mirage",
                )
            }
        }

        // ---- Start / End boxes + setup, with the type-ahead pick list underneath -----
        val startLabel = if (status.running && vm.useSimulatedStart) "Current simulated position" else vm.startName
        val endLabel = vm.dest?.let { vm.destName } ?: ""
        var startQuery by remember { mutableStateOf("") }
        var endQuery by remember { mutableStateOf("") }
        var startFocused by remember { mutableStateOf(false) }
        var endFocused by remember { mutableStateOf(false) }
        var snapMenu by remember { mutableStateOf(false) }
        // The boxes show the chosen place; typing replaces it, picking/snapping refills it.
        LaunchedEffect(startLabel) { if (!startFocused) startQuery = startLabel }
        LaunchedEffect(endLabel) { if (!endFocused) endQuery = endLabel }
        val onEnter: (String) -> Unit = { q ->
            keyboard?.hide()
            val top = vm.suggestions.firstOrNull()
            if (top != null) { vm.pickSuggestion(top); goTo(top.latLng) }
            else if (q.isNotBlank()) vm.search(q, goTo)
        }
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Surface(
                    shape = RoundedCornerShape(20.dp), shadowElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surface, modifier = Modifier.weight(1f),
                ) {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                        // START
                        LocationBox(
                            value = startQuery,
                            onValueChange = { startQuery = it; vm.activeField = Field.START; vm.suggest(it, camera.position.target.toE()) },
                            placeholder = "Start · search, or long-press the map",
                            dot = GREEN,
                            onFocus = { f ->
                                startFocused = f
                                if (f) { vm.activeField = Field.START; if (startQuery == startLabel) startQuery = "" }
                                else if (startQuery.isBlank()) { startQuery = startLabel; vm.clearSuggestions() }
                            },
                            onEnter = { onEnter(startQuery) },
                            trailing = {
                                Box {
                                    IconButton(onClick = { snapMenu = true }) {
                                        Icon(Icons.Filled.MyLocation, contentDescription = "Snap the start", tint = ACCENT)
                                    }
                                    DropdownMenu(expanded = snapMenu, onDismissRequest = { snapMenu = false }) {
                                        DropdownMenuItem(
                                            text = { Text(if (status.running) "My real location (last known)" else "My real location") },
                                            onClick = {
                                                snapMenu = false
                                                if (status.running) {
                                                    val r = vm.lastReal
                                                    if (r != null) { vm.useMyLocation(r); goTo(r) } else vm.error = "Real location unknown — it is captured before a simulation starts"
                                                } else recentreOnReal()
                                            },
                                        )
                                        if (status.running) DropdownMenuItem(
                                            text = { Text("Current simulated position") },
                                            onClick = { snapMenu = false; vm.useSimulatedPosition() },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Clear") },
                                            onClick = { snapMenu = false; startQuery = ""; vm.clearSuggestions() },
                                        )
                                    }
                                }
                            },
                        )
                        HorizontalDivider(color = MUTED.copy(alpha = 0.15f), modifier = Modifier.padding(horizontal = 8.dp))
                        // END
                        LocationBox(
                            value = endQuery,
                            onValueChange = { endQuery = it; vm.activeField = Field.END; vm.suggest(it, camera.position.target.toE()) },
                            placeholder = "End · search, or tap the map",
                            dot = VIOLET,
                            onFocus = { f ->
                                endFocused = f
                                if (f) { vm.activeField = Field.END; if (endQuery == endLabel) endQuery = "" }
                                else if (endQuery.isBlank()) { endQuery = endLabel; vm.clearSuggestions() }
                            },
                            onEnter = { onEnter(endQuery) },
                            trailing = {
                                if (endQuery.isNotEmpty()) IconButton(onClick = { endQuery = ""; vm.clearSuggestions() }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Clear", tint = MUTED)
                                }
                            },
                        )
                    }
                }
                Surface(shape = CircleShape, shadowElevation = 3.dp, modifier = Modifier.size(48.dp)) {
                    IconButton(onClick = { showSetup = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Setup", tint = ACCENT)
                    }
                }
            }
            if (vm.suggestions.isNotEmpty() || vm.suggestBusy) {
                Spacer(Modifier.height(6.dp))
                SuggestionList(
                    hits = vm.suggestions,
                    busy = vm.suggestBusy,
                    from = vm.start,
                    heading = if (vm.activeField == Field.START) "Start" else "End",
                    onPick = { hit -> keyboard?.hide(); vm.pickSuggestion(hit); goTo(hit.latLng) },
                )
            }
        }

        val mockBlocked = !status.running && !status.mockAppSelected && status.message.contains("Not the selected")

        // ---- Bottom sheet: capped at half the screen, scrolls inside, collapsible -----
        Card(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(10.dp).heightIn(max = maxSheet),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        ) {
            Column(
                Modifier.padding(horizontal = 18.dp, vertical = 10.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    status.running && sheetCollapsed -> MiniHud(status, onExpand = { sheetCollapsed = false }, onStop = onStop)
                    status.running -> {
                        LiveHud(status, onCollapse = { sheetCollapsed = true }, onStop = onStop)
                        HorizontalDivider(color = MUTED.copy(alpha = 0.2f))
                        Text("Plan the next leg", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("Fill the Start and End boxes above; starting replaces what is playing.", fontSize = 12.sp, color = MUTED)
                        Controls(vm = vm, status = status, mockBlocked = mockBlocked, onOpenSetup = { showSetup = true }, a = actions)
                    }
                    sheetCollapsed -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { sheetCollapsed = false }) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Expand controls", tint = ACCENT)
                        }
                        Box(Modifier.weight(1f)) { PrimaryAction(vm, false, actions) }
                    }
                    else -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Plan", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { sheetCollapsed = true }) {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Collapse to see the map", tint = ACCENT)
                            }
                        }
                        Controls(vm = vm, status = status, mockBlocked = mockBlocked, onOpenSetup = { showSetup = true }, a = actions)
                    }
                }
            }
        }
    }

    if (showSetup) {
        val checks = remember { setupChecks() }
        SetupDialog(
            status = status,
            checks = checks,
            hasKey = vm.hasKey,
            onDismiss = { showSetup = false },
            onDev = onOpenDevSettings,
            onPerms = onRequestPermissions,
            onBattery = onRequestBattery,
        )
    }
}

/** One compact location box: coloured dot, editable text, optional trailing control. */
@Composable
private fun LocationBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    dot: Color,
    onFocus: (Boolean) -> Unit,
    onEnter: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(start = 8.dp, end = 4.dp).size(10.dp).background(dot, CircleShape))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, fontSize = 14.sp) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onEnter() }),
            modifier = Modifier.weight(1f).onFocusChanged { onFocus(it.isFocused) },
        )
        trailing()
    }
}

// ---- Search results ----------------------------------------------------------------

/** Uber-style pick list: every matching place with its address and distance from the start. */
@Composable
private fun SuggestionList(hits: List<PlaceHit>, busy: Boolean, from: LatLng?, heading: String, onPick: (PlaceHit) -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Set as $heading", fontSize = 11.sp, color = MUTED, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 14.dp, top = 8.dp))
            if (busy && hits.isEmpty()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Searching…", fontSize = 13.sp, color = MUTED)
                }
            }
            hits.forEachIndexed { i, hit ->
                if (i > 0) HorizontalDivider(color = MUTED.copy(alpha = 0.15f))
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(hit) }.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Place, contentDescription = null, tint = ACCENT, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(hit.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (hit.address.isNotBlank()) {
                            Text(hit.address, fontSize = 12.sp, color = MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (from != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(fmtMiles(Geo.haversine(from, hit.latLng)), fontSize = 12.sp, color = MUTED)
                    }
                }
            }
        }
    }
}

// ---- Planning controls -------------------------------------------------------------

@Composable
private fun Controls(
    vm: MirageViewModel,
    status: MockStatus,
    mockBlocked: Boolean,
    onOpenSetup: () -> Unit,
    a: SimActions,
) {
    val fly = vm.mode == TravelMode.FLY
    val running = status.running

    // Trip type
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = !vm.itineraryMode, onClick = { vm.itineraryMode = false },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2), label = { Text("Single trip") },
        )
        SegmentedButton(
            selected = vm.itineraryMode, onClick = { vm.itineraryMode = true },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2), label = { Text("Itinerary") },
        )
    }

    // Where we stand
    vm.routeSummary?.let {
        Text(it, fontSize = 13.sp, color = ACCENT, fontWeight = FontWeight.SemiBold)
    } ?: Text(
        when {
            vm.dest == null -> "Type in the End box or tap the map. Long-press the map to move the Start."
            running && vm.useSimulatedStart -> "Next leg starts from the current simulated position; use ⌖ on the Start box to change that."
            else -> "Start and End set — get the route."
        },
        fontSize = 12.sp, color = MUTED,
    )

    // Travel mode
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TravelMode.entries.forEach { m ->
            FilterChip(
                selected = vm.mode == m,
                onClick = { vm.chooseMode(m) },
                label = { Text(m.label()) },
                leadingIcon = { Icon(m.icon(), contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }

    if (fly) {
        Text("Emulated flight: taxi, climb to 35,000 ft, cruise at about 550 mph, descent, landing.", fontSize = 12.sp, color = MUTED)
    } else {
        // Average speed — the hero control
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(
                if (vm.itineraryMode) "Next leg speed · ${vm.mode.label()}" else "Average speed · ${vm.mode.label()}",
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            )
            Text("${vm.avgMph.toInt()} mph", color = ACCENT, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Slider(value = vm.avgMph, onValueChange = { vm.avgMph = it }, valueRange = speedRange(vm.mode))

        // Realism
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Realism.entries.forEach { r ->
                FilterChip(selected = vm.realism == r, onClick = { vm.realism = r }, label = { Text(r.label()) })
            }
        }
    }

    if (vm.itineraryMode) {
        // ---- Itinerary: ordered stops, each with a dwell time ----
        Text(
            "Add stops in order. Each leg travels with the mode and speed set above; tap a stop's icon to change its mode.",
            fontSize = 12.sp, color = MUTED,
        )
        vm.stops.forEachIndexed { i, stop ->
            StopRow(
                index = i, stop = stop,
                onMode = { vm.cycleStopMode(i) },
                onDwell = { vm.adjustDwell(i, it) },
                onRemove = { vm.removeStop(i) },
            )
        }
        if (vm.stops.isNotEmpty()) {
            Text(
                "${vm.stops.size} ${if (vm.stops.size == 1) "stop" else "stops"} · ${fmtDuration(vm.dwellTotalMinutes * 60.0)} on site in total",
                fontSize = 12.sp, color = MUTED,
            )
        }
        OutlinedButton(onClick = { vm.addStop() }, enabled = vm.dest != null, modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.dest != null) "Add “${vm.destName}” as a stop" else "Search or tap a place to add a stop")
        }
        PrimaryAction(vm, running, a)
    } else {
        PrimaryAction(vm, running, a)
        // Find a place and simply BE there — no route, instant, holds until Stop.
        if (vm.dest != null) {
            OutlinedButton(onClick = { a.holdAt(vm.dest) }, modifier = Modifier.fillMaxWidth()) {
                Text("Jump to “${vm.destName}” — no route")
            }
        }
        if (vm.routePts.isNotEmpty() && !running) {
            TextButton(onClick = { a.holdAt(vm.start) }, modifier = Modifier.fillMaxWidth()) { Text("Hold the start point instead") }
        }
    }

    // Fast-forward (testing)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Fast-forward", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        listOf(1f, 2f, 5f, 10f).forEach { s ->
            FilterChip(selected = vm.timeScale == s, onClick = { vm.timeScale = s }, label = { Text("${s.toInt()}×") })
        }
    }
    if (vm.timeScale > 1f) {
        Text(
            "Positions advance ${vm.timeScale.toInt()}× faster than real time (for testing); reported speed stays realistic.",
            fontSize = 12.sp, color = AMBER,
        )
    }

    // Notices
    if (!vm.hasKey) {
        Text("Map, search and routing need a Google Maps key — tap ⚙ for details. Holding a point works without it.", fontSize = 12.sp, color = AMBER)
    }
    if (mockBlocked) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = RED.copy(alpha = 0.08f))) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Mirage isn't selected as the mock-location app yet, so Android won't let it move your location. " +
                        "Developer options → Select mock location app → Mirage, then tap Start again.",
                    fontSize = 12.sp, color = RED,
                )
                Button(onClick = onOpenSetup, modifier = Modifier.fillMaxWidth()) { Text("Fix: select Mirage as mock app") }
            }
        }
    }
    vm.error?.let {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = RED, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(it, fontSize = 12.sp, color = RED, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.clearError() }) { Text("Dismiss", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun StopRow(index: Int, stop: ItineraryStop, onMode: () -> Unit, onDwell: (Int) -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).background(ACCENT, CircleShape), contentAlignment = Alignment.Center) {
            Text("${index + 1}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = onMode, modifier = Modifier.size(36.dp)) {
            Icon(stop.mode.icon(), contentDescription = "Travel mode: ${stop.mode.label()} (tap to change)", tint = ACCENT)
        }
        Column(Modifier.weight(1f)) {
            Text(stop.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${stop.mode.label()} · ${stop.avgMph.toInt()} mph", fontSize = 11.sp, color = MUTED)
        }
        IconButton(onClick = { onDwell(-15) }, modifier = Modifier.size(32.dp)) { Text("−", fontSize = 18.sp) }
        Text("${stop.dwellMinutes} min", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        IconButton(onClick = { onDwell(15) }, modifier = Modifier.size(32.dp)) { Text("+", fontSize = 18.sp) }
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = "Remove stop", tint = RED)
        }
    }
}

@Composable
private fun PrimaryAction(vm: MirageViewModel, running: Boolean, a: SimActions) {
    val fly = vm.mode == TravelMode.FLY
    if (vm.itineraryMode) {
        if (vm.itineraryBusy) BusyRow("Routing legs…")
        else BigButton(if (running) "Start new itinerary" else "Start itinerary", enabled = vm.stops.isNotEmpty(), onClick = a.startItinerary)
        return
    }
    when {
        vm.phase == Phase.ROUTING -> BusyRow("Routing…")
        vm.routePts.isNotEmpty() -> BigButton(
            when {
                running -> if (fly) "Start new flight" else "Start new route"
                fly -> "Start flight"
                else -> "Start simulation"
            },
            onClick = a.start,
        )
        vm.dest != null -> BigButton(if (fly) "Plot flight" else "Get route", icon = null, onClick = a.getRoute)
        running -> BigButton("Search or tap a destination", enabled = false, icon = null, onClick = {})
        else -> BigButton("Hold the start point", onClick = { a.holdAt(vm.start) })
    }
}

@Composable
private fun BigButton(text: String, enabled: Boolean = true, icon: ImageVector? = Icons.Filled.PlayArrow, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        if (icon != null) { Icon(icon, contentDescription = null); Spacer(Modifier.width(6.dp)) }
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BusyRow(text: String) {
    Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text(text)
    }
}

// ---- Live HUD (running) -------------------------------------------------------------

private fun healthColor(h: Health) = when (h) {
    Health.GREEN -> GREEN
    Health.AMBER -> AMBER
    Health.RED -> RED
}

@Composable
private fun LiveHud(status: MockStatus, onCollapse: () -> Unit, onStop: () -> Unit) {
    val mph = (status.speedMps / 0.44704f).toInt()
    val hc = healthColor(status.health)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$mph", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(" mph", fontSize = 15.sp, color = MUTED)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(20.dp), color = hc.copy(alpha = 0.12f)) {
                Text(
                    "● ${status.message}", color = hc, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            IconButton(onClick = onCollapse) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Collapse to see the map", tint = ACCENT)
            }
        }
    }
    if (status.stepLabel.isNotEmpty()) {
        Text(status.stepLabel, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ACCENT)
    }
    if (status.progress >= 0f) {
        LinearProgressIndicator(progress = { status.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${(status.progress * 100).toInt()}% of this leg", fontSize = 12.sp, color = MUTED)
            if (status.remainingSec >= 0) Text("about ${fmtDuration(status.remainingSec.toDouble())} left", fontSize = 12.sp, color = MUTED)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            "${"%.5f".format(status.lat)}, ${"%.5f".format(status.lng)}",
            fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MUTED,
        )
        if (status.label.isNotEmpty()) Text(status.label, fontSize = 12.sp, color = MUTED)
    }
    Text(
        "Fixes ${status.emittedCount} · re-asserts ${status.reassertCount} · leak ${if (status.leakSeen) "YES ⚠" else "no"}",
        fontSize = 11.sp, color = MUTED,
    )
    Button(
        onClick = onStop,
        colors = ButtonDefaults.buttonColors(containerColor = RED),
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        Icon(Icons.Filled.Stop, contentDescription = null); Spacer(Modifier.width(6.dp))
        Text("Stop — return to real location", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** One-line HUD when the sheet is collapsed while running: still shows health, and Stop stays reachable. */
@Composable
private fun MiniHud(status: MockStatus, onExpand: () -> Unit, onStop: () -> Unit) {
    val mph = (status.speedMps / 0.44704f).toInt()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onExpand) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Expand status", tint = ACCENT)
        }
        Box(Modifier.size(10.dp).background(healthColor(status.health), CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("$mph mph", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(status.stepLabel.ifEmpty { status.message }, fontSize = 12.sp, color = MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = RED), contentPadding = PaddingValues(horizontal = 14.dp)) {
            Text("Stop")
        }
    }
}

// ---- Setup dialog ------------------------------------------------------------------

@Composable
private fun SetupDialog(
    status: MockStatus,
    checks: SetupChecks,
    hasKey: Boolean,
    onDismiss: () -> Unit,
    onDev: () -> Unit,
    onPerms: () -> Unit,
    onBattery: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Setup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("One-time device setup. Each step shows its current state.", fontSize = 12.sp, color = MUTED)
                SetupRow(
                    ok = status.mockAppSelected, title = "Mock-location app",
                    detail = if (status.mockAppSelected) "Mirage is selected" else "Developer options → Select mock location app → Mirage (verified on first Start)",
                    action = "Open Developer options", onAction = onDev,
                )
                SetupRow(
                    ok = checks.location, title = "Location permission",
                    detail = if (checks.location) "Precise location granted" else "Precise location is required",
                    action = "Grant", onAction = onPerms,
                )
                SetupRow(
                    ok = checks.notifications, title = "Notifications",
                    detail = if (checks.notifications) "Enabled" else "Needed for the persistent status and its Stop button",
                    action = "Grant", onAction = onPerms,
                )
                SetupRow(
                    ok = checks.battery, title = "Battery optimization",
                    detail = if (checks.battery) "Ignored — the simulation can run all day" else "Allow so the simulation survives Doze",
                    action = "Allow", onAction = onBattery,
                )
                HorizontalDivider()
                Text(
                    if (hasKey) "Google Maps key: set ✓"
                    else "Google Maps key: not set — map, search and routing stay off until MAPS_API_KEY is provided (see README).",
                    fontSize = 12.sp,
                    color = if (hasKey) GREEN else AMBER,
                )
                Text("Mirage ${BuildConfig.VERSION_NAME}", fontSize = 11.sp, color = MUTED)
            }
        },
    )
}

@Composable
private fun SetupRow(ok: Boolean, title: String, detail: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Filled.Check else Icons.Filled.Warning,
            contentDescription = if (ok) "Done" else "Needs attention",
            tint = if (ok) GREEN else AMBER,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, fontSize = 12.sp, color = MUTED)
        }
        if (!ok) TextButton(onClick = onAction) { Text(action, fontSize = 12.sp) }
    }
}

// ---- Helpers ------------------------------------------------------------------------

private fun TravelMode.label(): String = when (this) {
    TravelMode.DRIVE -> "Drive"
    TravelMode.BIKE -> "Bike"
    TravelMode.WALK -> "Walk"
    TravelMode.FLY -> "Fly"
}

private fun TravelMode.icon(): ImageVector = when (this) {
    TravelMode.DRIVE -> Icons.Filled.DirectionsCar
    TravelMode.BIKE -> Icons.Filled.DirectionsBike
    TravelMode.WALK -> Icons.Filled.DirectionsWalk
    TravelMode.FLY -> Icons.Filled.Flight
}

private fun Realism.label(): String = when (this) {
    Realism.CONSTANT -> "Steady"
    Realism.REALISTIC -> "Realistic"
    Realism.BUSY -> "Heavy traffic"
}

/** A heading arrow in a white halo, drawn once, for the moving position marker. */
private fun navigationArrow(color: Color, sizePx: Int = 96): BitmapDescriptor {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val s = sizePx.toFloat()
    val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE }
    c.drawCircle(s / 2, s / 2, s * 0.42f, halo)
    val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toArgb() }
    c.drawCircle(s / 2, s / 2, s * 0.34f, body)
    val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE }
    val p = Path().apply {
        moveTo(s / 2, s * 0.20f)
        lineTo(s * 0.70f, s * 0.68f)
        lineTo(s / 2, s * 0.56f)
        lineTo(s * 0.30f, s * 0.68f)
        close()
    }
    c.drawPath(p, arrow)
    return BitmapDescriptorFactory.fromBitmap(bmp)
}

/**
 * A FRESH real fix. `lastLocation` is useless right after a Stop: the fused provider keeps
 * handing out the final spoofed point until a new fix is computed, so ask for one and
 * refuse anything still flagged as mock (a few retries while the providers settle).
 */
@SuppressLint("MissingPermission")
private suspend fun realLocation(context: Context, attempts: Int = 4): LatLng? {
    val flp = LocationServices.getFusedLocationProviderClient(context)
    repeat(attempts) { i ->
        val loc: Location? = suspendCancellableCoroutine { cont ->
            runCatching {
                flp.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
        if (loc != null && !isMockLocation(loc)) return LatLng(loc.latitude, loc.longitude)
        if (i < attempts - 1) delay(1500)
    }
    return null
}

private fun isMockLocation(l: Location): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) l.isMock
    else @Suppress("DEPRECATION") l.isFromMockProvider

private fun armStatic(at: LatLng?) {
    PlaybackSource.current = null
    PlaybackSource.routePoints = listOfNotNull(at)
    PlaybackSource.label = "Static"
}

private fun LatLng.toG() = GLatLng(lat, lng)
private fun GLatLng.toE() = LatLng(latitude, longitude)
