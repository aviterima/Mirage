package com.mirage.spike

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subway
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Tram
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.CurrentLocationRequest
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
import com.mirage.spike.engine.LiveSession
import com.mirage.spike.engine.ActivityKind
import com.mirage.spike.engine.ApiConfig
import com.mirage.spike.engine.CreditsState
import com.mirage.spike.engine.Geo
import com.mirage.spike.engine.ItineraryStop
import com.mirage.spike.engine.LatLng
import com.mirage.spike.engine.PlaceHit
import com.mirage.spike.engine.PlaybackSource
import com.mirage.spike.engine.Realism
import com.mirage.spike.engine.RouteSegment
import com.mirage.spike.engine.Signal
import com.mirage.spike.engine.TransitVehicle
import com.mirage.spike.engine.TravelMode
import com.mirage.spike.store.PrefsKeyStore
import com.mirage.spike.store.PrefsScenarioStore
import com.mirage.spike.store.SavedScenario
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
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

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun MapScreen(
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onOpenDevSettings: () -> Unit,
    onRequestBattery: () -> Unit,
    onRequestPermissions: () -> Unit,
    setupChecks: () -> SetupChecks,
    hasLocPerm: Boolean,
) {
    val vm: MirageViewModel = viewModel()
    val status by MockState.status.collectAsState(context = kotlinx.coroutines.Dispatchers.Main.immediate)
    val session by LiveSession.state.collectAsState(context = kotlinx.coroutines.Dispatchers.Main.immediate)
    val addition = vm.continuation.state
    LaunchedEffect(status.running) { if (!status.running) vm.continuation.cancel() }
    LaunchedEffect(vm.notice) { if (vm.notice != null) { delay(5000); vm.notice = null } }
    var statusClock by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { statusClock = System.currentTimeMillis(); delay(1000) } }
    // A location callback can arrive between one-second timer ticks. Diagnose it
    // against the current time so fresh output never looks like a future timestamp.
    val simulationLabel = simulationStatusText(status, session.activity, maxOf(statusClock, System.currentTimeMillis()))
    var planning by remember { mutableStateOf(false) }
    var showChat by remember { mutableStateOf(false) }
    var showItinerary by remember { mutableStateOf(false) }
    var showUpcoming by remember { mutableStateOf(false) }
    var orderProposal by remember { mutableStateOf<List<ItineraryStop>?>(null) }
    var showPlannerSettings by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showLiveDetails by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(true) }
    val live = status.running && !planning
    var showSetup by remember { mutableStateOf(false) }
    var showSaved by remember { mutableStateOf(false) }
    var plannerPinPick by remember { mutableStateOf<String?>(null) }
    var footerPx by remember {mutableStateOf(0)}
    val density=LocalDensity.current
    var sheetCollapsed by remember { mutableStateOf(false) }
    // The control sheet may never take more than half the screen; the map keeps the rest.
    val maxSheet = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // Saved plans and the user's own key live on the device.
    val keyStore = remember { PrefsKeyStore(context) }
    val recovery = remember { TripRecovery.configure(context); TripRecovery.live() }
    val recoveredDraft = remember { TripRecovery.draft() }
    var showRecovery by remember { mutableStateOf(!status.running && !vm.canSaveScenario && (recovery != null || recoveredDraft != null)) }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { vm.draftSnapshot() }.filterNotNull().debounce(400).collect { draft ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { TripRecovery.saveDraft(draft) }
        }
    }
    LaunchedEffect(Unit) {
        vm.attachStore(PrefsScenarioStore(context))
        vm.configureApi(ApiConfig(BuildConfig.MIRAGE_API_BASE, keyStore.userKey.ifBlank { BuildConfig.MAPS_API_KEY }, keyStore.installId))
    }
    // Automation: commands arrive from adb via MainActivity → CommandBus.
    LaunchedEffect(Unit) {
        vm.automationToken = keyStore.installId.take(8)
        CommandBus.commands.collect { args -> vm.runCommand(args["cmd"] ?: "", args, onStartService, onStopService) }
    }
    LaunchedEffect(vm.api, vm.lastReal, vm.start) { Conversation.configure(context, vm.api, vm.lastReal ?: vm.start); SmartVoice.bind(context, vm, onStartService) }
    LaunchedEffect(session.title) {
        if (session.title.isNotBlank()) planning = false
    }
    val saveKey: (String) -> Unit = { k ->
        keyStore.userKey = k
        vm.configureApi(ApiConfig(BuildConfig.MIRAGE_API_BASE, k.ifBlank { BuildConfig.MAPS_API_KEY }, keyStore.installId))
        if (vm.hasKey) vm.testKey()
    }

    val camera = rememberCameraPositionState {
        // Continental view until the real fix arrives — never pretend to know where you are.
        position = CameraPosition.fromLatLngZoom(GLatLng(39.5, -98.35), 3f)
    }
    LaunchedEffect(status.lat, status.lng, live, follow, addition != null) {
        if (live && follow && addition == null) runCatching { camera.move(CameraUpdateFactory.newLatLng(GLatLng(status.lat, status.lng))) }
    }
    LaunchedEffect(camera.isMoving) {
        if (camera.isMoving && camera.cameraMoveStartedReason == com.google.maps.android.compose.CameraMoveStartedReason.GESTURE) follow = false
    }
    val carState = rememberMarkerState()
    LaunchedEffect(status.lat, status.lng) { carState.position = GLatLng(status.lat, status.lng) }
    // Frame the whole route (or itinerary) in the visible part of the map.
    LaunchedEffect(vm.routePts) {
        if ((!status.running || planning) && vm.routePts.size >= 2) {
            val b = LatLngBounds.Builder()
            vm.routePts.forEach { b.include(it.toG()) }
            runCatching { camera.animate(CameraUpdateFactory.newLatLngBounds(b.build(), 90)) }
        }
    }
    // A problem must be visible even if the sheet was tucked away.
    LaunchedEffect(vm.error) { if (vm.error != null) sheetCollapsed = false }

    // Move the start pin and the camera to the device's REAL location: a fresh fix,
    // never the fused provider's cached last point (which is the mock after a Stop).
    // explicit = the user asked (⌖ menu): always adopt, and complain if it fails.
    // automatic = launch / after Stop: adopt only when it does not destroy the user's work.
    val recentreOnReal: (Boolean) -> Unit = { explicit ->
        if (hasLocPerm) scope.launch {
            // Instantly: any real last-known fix puts the map on your location right away…
            quickLastReal(context)?.let { q ->
                if (!MockState.status.value.running) {
                    val hadStart = vm.start != null
                    if (explicit) vm.pickRealStart(q) else vm.useMyLocation(q)
                    if (explicit || !hadStart) runCatching { camera.animate(CameraUpdateFactory.newLatLngZoom(q.toG(), 14f)) }
                }
            }
            // …then a fresh fix refines it (and is the only thing trusted right after a Stop),
            // without discarding a route the user may have built while we waited.
            val p = realLocation(context)
            if (p != null) {
                val hadStart = vm.start != null
                if (explicit) vm.pickRealStart(p) else vm.refineMyLocation(p)
                if (explicit || !hadStart) runCatching { camera.animate(CameraUpdateFactory.newLatLngZoom(p.toG(), 14f)) }
            } else if (explicit || vm.start == null) {
                vm.error = "Could not get a real fix — is Location turned on?"
            }
        } else if (explicit) {
            vm.error = "Location permission is needed — tap ⚙ to grant it"
        }
    }
    // On first load (and once permission is granted), start from where the phone really is.
    LaunchedEffect(hasLocPerm) {
        if (hasLocPerm && vm.error?.contains("permission") == true) vm.clearError()
        if (!status.running) recentreOnReal(false)
    }
    // After a Stop, hand the app back to reality too: the old route is from a place we
    // no longer are, so the start pin and camera return to the real location.
    var wasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(status.running) {
        if (wasRunning && !status.running) recentreOnReal(false)
        wasRunning = status.running
    }

    val goTo: (LatLng) -> Unit = { p ->
        scope.launch { runCatching { camera.animate(CameraUpdateFactory.newLatLngZoom(p.toG(), 15f)) } }
    }
    // A location-type foreground service cannot be started without location permission
    // (SecurityException on Android 14), so ask first and let the user tap again.
    val withPerms: (() -> Unit) -> Unit = { action ->
        if (hasLocPerm) action() else {
            onRequestPermissions()
            vm.error = "Grant location permission, then tap Start again"
        }
    }
    val actions = SimActions(
        getRoute = { vm.buildRoute() },
        start = { withPerms { vm.startSim(onStartService); planning = false } },
        startItinerary = { withPerms { vm.startItinerary(onStartService); planning = false } },
        holdAt = { at -> withPerms { armStatic(at, vm.destName, vm.api, vm.arrivalActivity, vm.arrivalEntrance, vm.stayUntilLeave, vm.defaultStayMinutes); vm.onSnapStarted(); onStartService(); planning = false } },
    )
    val onStop = { TripRecovery.clearLive(); Conversation.cancelPending(); onStopService(); vm.onStopped(); planning = false }
    val planNow = { planning = false; follow = false; vm.continuation.begin(Placement.NOW) }
    val planNext = { planning = false; follow = false; vm.continuation.begin(Placement.NEXT) }
    val addDestination = { planning = false; follow = false; vm.continuation.begin() }
    val confirmAddition = { if (vm.continuation.commit(onStartService)) { planning = false; follow = true } }
    LaunchedEffect(addition?.points, addition?.editorOpen) {
        val points = addition?.points.orEmpty()
        if (points.isNotEmpty() && addition?.editorOpen == false) {
            val bounds = LatLngBounds.Builder(); points.forEach { bounds.include(it.toG()) }
            runCatching { camera.animate(CameraUpdateFactory.newLatLngBounds(bounds.build(), 90)) }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val plannerHeight = (maxHeight - (if(footerPx==0)170.dp else with(density){footerPx.toDp()}) - 16.dp).coerceAtLeast(80.dp)

        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            properties = MapProperties(isMyLocationEnabled = hasLocPerm && !status.running),
            uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = hasLocPerm, compassEnabled = true),
            contentPadding = PaddingValues(
                top = topInset + if (live) 60.dp else if (vm.planMode == PlanMode.SNAP) 148.dp else 204.dp,
                bottom = when {
                    live -> 96.dp
                    sheetCollapsed -> 100.dp
                    status.running -> maxSheet
                    else -> maxSheet
                },
            ),
            onMapClick = { point ->
                when {
                    vm.continuation.state?.pinTarget!=null -> vm.continuation.pickPin(point.toE())
                    plannerPinPick=="entrance" -> {vm.setEntrance(point.toE());plannerPinPick=null;showPlannerSettings=true}
                    plannerPinPick=="destination" -> {vm.fineTuneDestination(point.toE());plannerPinPick=null;showPlannerSettings=true}
                    !live -> vm.setDestPoint(point.toE())
                }
            },
            onMapLongClick = { if (!live) vm.setStartPoint(it.toE()) },
        ) {
            val arrow = remember { runCatching { navigationArrow(ACCENT) }.getOrNull() }
            if(live) vm.lastReal?.let { real ->
                val realState=rememberMarkerState(position=real.toG())
                LaunchedEffect(real){realState.position=real.toG()}
                Marker(state=realState,title="Last known real phone location",snippet="Simulation uses the separate moving marker",icon=BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
            }
            if (!live) vm.start?.let { s ->
                Marker(
                    state = rememberMarkerState(key = "s-${s.lat},${s.lng}", position = s.toG()),
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN),
                    title = "Start",
                    snippet = vm.startName,
                )
            }
            if (!live) vm.dest?.let { d ->
                Marker(
                    state = rememberMarkerState(key = "d-${d.lat},${d.lng}", position = d.toG()),
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET),
                    title = vm.destName,
                )
            }
            if(!live) {
                val destination=if(vm.planMode==PlanMode.ITINERARY)vm.stops.lastOrNull()?.point else vm.dest
                val entrance=if(vm.planMode==PlanMode.ITINERARY)vm.stops.lastOrNull()?.entrance else vm.arrivalEntrance
                if(destination!=null && entrance!=null) {
                    Marker(state=rememberMarkerState(key="planner-entrance-$entrance",position=entrance.toG()),title="Entrance / parking")
                    Polyline(points=listOf(entrance.toG(),destination.toG()),color=VIOLET,width=5f)
                }
            }
            addition?.let { draft ->
                if (draft.points.isNotEmpty()) Polyline(points = draft.points.map { it.toG() }, color = VIOLET, width = 9f)
                draft.stops.forEachIndexed { index, stop ->
                    val entrance=stop.entrance ?: draft.points.lastOrNull().takeIf {draft.stops.size==1 && draft.savedOrigin==null}
                    if(entrance!=null && stop.mode!=TravelMode.FLY && stop.arrivalActivity!=com.mirage.spike.engine.ArrivalActivity.OUTDOOR) {
                        Polyline(points=listOf(entrance.toG(),stop.point.toG()),color=VIOLET,width=5f)
                        Marker(state=rememberMarkerState(key="entrance-$index-$entrance",position=entrance.toG()),title=if(stop.entrance!=null) "Entrance / parking" else "Road arrival",snippet="Walking connection is approximate")
                    }
                    Marker(state = rememberMarkerState(key = "addition-$index-${stop.point}", position = stop.point.toG()),
                        title = stop.name, snippet = stop.address,
                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET))
                }
            }
            if (status.running && session.points.isNotEmpty()) {
                Polyline(points = session.points.map { it.toG() }, color = ACCENT, width = 14f)
            }
            if (!live && vm.routePts.isNotEmpty()) {
                Polyline(points = vm.routePts.map { it.toG() }, color = if (status.running) VIOLET else ACCENT, width = if (status.running) 7f else 14f)
            }
            if (status.running) {
                session.stops.drop((session.index + 1).coerceAtLeast(0)).forEach { entry ->
                    Marker(state = rememberMarkerState(key = entry.id, position = entry.stop.point.toG()),
                        title = entry.stop.name, snippet = "Upcoming · ${entry.stop.dwellMinutes} min stay",
                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET))
                }
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
        val startLabel = when {
            status.running && vm.queueAfterCurrent -> "Where the current trip ends"
            status.running && vm.useSimulatedStart -> "Current simulated position"
            vm.start == null -> ""
            else -> vm.startName
        }
        val endLabel = vm.dest?.let { vm.destName } ?: ""
        var startQuery by remember { mutableStateOf("") }
        var endQuery by remember { mutableStateOf("") }
        var startFocused by remember { mutableStateOf(false) }
        var endFocused by remember { mutableStateOf(false) }
        var snapMenu by remember { mutableStateOf<Field?>(null) }
        // The boxes always show the chosen place: a pick, a map tap or a snap wins over
        // whatever was being typed.
        LaunchedEffect(startLabel) { if (!startFocused || startLabel.isNotBlank()) startQuery = startLabel }
        LaunchedEffect(endLabel) { if (!endFocused || endLabel.isNotBlank()) endQuery = endLabel }
        val syncBoxes = {
            startQuery = startLabel
            endQuery = endLabel
        }
        val done = { keyboard?.hide(); focus.clearFocus() }
        val onEnter: (String) -> Unit = { q ->
            done()
            if (q.isNotBlank()) vm.search(q) { p -> syncBoxes(); goTo(p) }
        }
        val snapReal: (Field) -> Unit = { field ->
            // "My real location" for a box: a fresh fix when idle; while spoofing the phone's own
            // GPS is masked by our mock, so use the last real fix captured before the simulation.
            snapMenu = null
            if (status.running) {
                val r = vm.lastReal
                if (r == null) vm.error = "Real location unknown — it is captured before a simulation starts"
                else if (field == Field.START) { vm.pickRealStart(r); goTo(r) }
                else { vm.setDestPoint(r, "My location"); goTo(r) }
            } else if (field == Field.START) {
                recentreOnReal(true)
            } else if (!hasLocPerm) {
                vm.error = "Location permission is needed — tap ⚙ to grant it"
            } else scope.launch {
                val p = realLocation(context)
                if (p != null) { vm.setDestPoint(p, "My location"); goTo(p) } else vm.error = "Could not get a real fix right now"
            }
        }
        val snapButton: @Composable (Field, Boolean) -> Unit = { field, allowSimulated ->
            Box {
                IconButton(onClick = { snapMenu = field }) {
                    Icon(Icons.Filled.MyLocation, contentDescription = "Choose place source", tint = ACCENT)
                }
                DropdownMenu(expanded = snapMenu == field, onDismissRequest = { snapMenu = null }) {
                    DropdownMenuItem(
                        text = { Text(if (status.running) "My real location (last known)" else "My real location") },
                        onClick = { snapReal(field) },
                    )
                    if (allowSimulated && status.running) {
                        DropdownMenuItem(
                            text = { Text("Current simulated position") },
                            onClick = { snapMenu = null; vm.useSimulatedPosition() },
                        )
                        DropdownMenuItem(
                            text = { Text("Where the current trip ends (queue after it)") },
                            onClick = { snapMenu = null; vm.useTripEnd() },
                        )
                    }
                    if (!allowSimulated && status.running && vm.planMode == PlanMode.ITINERARY) {
                        DropdownMenuItem(
                            text = { Text("Add a stop here (current position)") },
                            onClick = { snapMenu = null; vm.addStopAtSimulatedPosition() },
                        )
                        DropdownMenuItem(
                            text = { Text("Add a stop where the trip ends") },
                            onClick = { snapMenu = null; vm.addStopAtTripEnd() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Clear") },
                        onClick = {
                            snapMenu = null; vm.editLocationQuery(field, "", null)
                            if (field == Field.START) startQuery = "" else endQuery = ""
                        },
                    )
                }
            }
        }
        if (live) {
            Surface(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(8.dp).testTag("liveStatus"), shape = RoundedCornerShape(12.dp), shadowElevation = 3.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showLiveDetails = true }, modifier = Modifier.weight(1f)) {
                        Text(simulationLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, color = if (simulationLabel.startsWith("NEEDS")) RED else ACCENT)
                    }
                    TextButton(onClick = { showItinerary = true }) { Text("My itinerary") }
                }
            }
        }

        if (!live) Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().heightIn(max = plannerHeight).statusBarsPadding().padding(12.dp).verticalScroll(rememberScrollState())) {
            Surface(shape = RoundedCornerShape(12.dp)) {
                Text(simulationLabel, Modifier.padding(8.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = if (simulationLabel.startsWith("NEEDS") || status.blocked) RED else ACCENT)
            }
            if (status.running) Surface(shape = RoundedCornerShape(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (vm.queueAfterCurrent) "Next plan · after the current plan" else "New plan · replaces remaining trip", Modifier.weight(1f).padding(8.dp), fontSize = 12.sp)
                    TextButton(onClick = { planning = false; follow = true }) { Text("Back to live") }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Surface(
                    shape = RoundedCornerShape(20.dp), shadowElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surface, modifier = Modifier.weight(1f),
                ) {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                        // What are we doing?
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
                            PlanMode.entries.forEachIndexed { i, m ->
                                SegmentedButton(
                                    selected = vm.planMode == m,
                                    onClick = { vm.choosePlanMode(m) },
                                    shape = SegmentedButtonDefaults.itemShape(index = i, count = PlanMode.entries.size),
                                    label = { Text(m.label(), fontSize = 13.sp) },
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                        // START (Route and Itinerary)
                        if (vm.planMode != PlanMode.SNAP) {
                            LocationBox(
                                value = startQuery,
                                onValueChange = { startQuery = it; vm.editLocationQuery(Field.START, it, camera.position.target.toE()) },
                                placeholder = "Start · search, long-press the map, or ⌖",
                                dot = GREEN,
                                onFocus = { f ->
                                    startFocused = f
                                    if (f) { vm.activeField = Field.START; vm.clearSuggestions() }
                                    else if (startQuery.isBlank()) { startQuery = startLabel; vm.clearSuggestions() }
                                },
                                onEnter = { onEnter(startQuery) },
                                trailing = { snapButton(Field.START, true) },
                            )
                            HorizontalDivider(color = MUTED.copy(alpha = 0.15f), modifier = Modifier.padding(horizontal = 8.dp))
                        }
                        // THE CHAIN (Itinerary): every stop, editable in place, with the clock.
                        if (vm.planMode == PlanMode.ITINERARY && vm.stops.isNotEmpty()) {
                            val stopsNow = vm.stops.toList()
                            val tl = remember(stopsNow, vm.start, status.running, vm.departureMillis) { vm.timeline() }
                            Text("Estimated · ${tl.sumOf{it.legMinutes}} min travel + ${stopsNow.sumOf{it.dwellMinutes}} min stays" + (tl.lastOrNull()?.let { " · finish ${tripTime(it.leaveMillis)}" } ?: ""),style=MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick={chooseTripTime(context,vm.departureMillis ?: System.currentTimeMillis()){vm.departureMillis=it}}){Text("Departure")}
                                TextButton(onClick={runCatching{TripPlanning.suggest(vm.tripStart() ?: stopsNow.first().point,stopsNow)}.onSuccess{orderProposal=it}.onFailure{vm.error=it.message}}){Text("Suggest order")}
                            }
                            Row {
                                Text("Drag handles to reorder", style=MaterialTheme.typography.bodySmall, modifier=Modifier.weight(1f))
                                if(vm.draftUndoAvailable) TextButton(onClick=vm::undoDraftStops) { Text("Undo") }
                            }
                            ReorderableStopList(stopsNow, onMove = vm::moveStopTo) { i, stop, handle ->
                                ChainRow(
                                    index = i, stop = stop, entry = tl.getOrNull(i),
                                    current = status.running && status.legIndex == i,
                                    done = status.running && status.legIndex > i,
                                    onMode = { m -> vm.setStopMode(i, m) },
                                    onStay = { vm.dwellEditIndex = i },
                                    onMove = { d -> vm.moveStop(i, d) },
                                    onRemove = { vm.removeStop(i) },
                                    handleModifier = handle,
                                    canMoveUp = i > 0, canMoveDown = i < stopsNow.lastIndex,
                                )
                            }
                            HorizontalDivider(color = MUTED.copy(alpha = 0.15f), modifier = Modifier.padding(horizontal = 8.dp))
                        }
                        // END / PLACE / NEXT STOP
                        LocationBox(
                            value = endQuery,
                            onValueChange = { endQuery = it; vm.editLocationQuery(Field.END, it, camera.position.target.toE()) },
                            placeholder = when (vm.planMode) {
                                PlanMode.SNAP -> "Place · search, tap the map, or ⌖"
                                PlanMode.ROUTE -> "End · search, tap the map, or ⌖"
                                PlanMode.ITINERARY -> if (vm.stops.isEmpty()) "First stop · search, tap the map, or ⌖" else "Add a stop · search, tap the map, or ⌖"
                            },
                            dot = if (vm.planMode == PlanMode.SNAP) ACCENT else VIOLET,
                            onFocus = { f ->
                                endFocused = f
                                if (f) { vm.activeField = Field.END; vm.clearSuggestions() }
                                else if (endQuery.isBlank()) { endQuery = endLabel; vm.clearSuggestions() }
                            },
                            onEnter = { onEnter(endQuery) },
                            trailing = { snapButton(Field.END, false) },
                        )
                        }
                        if (vm.planMode == PlanMode.ROUTE) {
                            IconButton(
                                onClick = { done(); vm.swapEndpoints() },
                                enabled = vm.tripStart() != null && vm.dest != null,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Filled.SwapVert, contentDescription = "Swap start and destination", tint = if (vm.tripStart() != null && vm.dest != null) ACCENT else MUTED)
                            }
                        }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(shape = CircleShape, shadowElevation = 3.dp, modifier = Modifier.size(48.dp)) {
                        IconButton(onClick = { showSetup = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Setup", tint = ACCENT)
                        }
                    }
                    Surface(shape = CircleShape, shadowElevation = 3.dp, modifier = Modifier.size(48.dp)) {
                        IconButton(onClick = { showSaved = true }) {
                            Icon(Icons.Filled.Bookmark, contentDescription = "Saved plans", tint = ACCENT)
                        }
                    }
                }
            }
            if (vm.suggestions.isNotEmpty() || vm.suggestBusy) {
                Spacer(Modifier.height(6.dp))
                SuggestionList(
                    hits = vm.suggestions,
                    busy = vm.suggestBusy,
                    from = vm.start,
                    heading = when {
                        vm.activeField == Field.START -> "Start"
                        vm.planMode == PlanMode.ITINERARY -> "next stop"
                        vm.planMode == PlanMode.SNAP -> "the place"
                        else -> "End"
                    },
                    onPick = { hit -> done(); vm.pickSuggestion(hit); syncBoxes(); goTo(hit.latLng) },
                )
            }
        }

        val picking=addition?.pinTarget ?: plannerPinPick
        if(picking!=null) Card(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top=64.dp,start=16.dp,end=16.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(if(picking=="entrance") "Tap the entrance or parking point" else "Tap the final destination (inside the building if desired)")
                TextButton(onClick={if(addition?.pinTarget!=null)vm.continuation.cancelMapPick() else {plannerPinPick=null;showPlannerSettings=true}}){Text("Cancel pin selection")}
            }
        }
        val mockBlocked = !status.running && status.blocked

        // ---- Bottom sheet: capped at half the screen, scrolls inside, collapsible -----
        if (live) {
            Card(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(8.dp).testTag("liveControls"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                if (addition != null) ContinuationPreviewBar(vm.continuation, confirmAddition)
                else CompactLiveControls(status, session, onStop, onAdd = addDestination,
                    onChat = { showChat = true }, onDetails = { showLiveDetails = true }, onSaved = { showSaved = true }, notice = vm.notice)
            }
        } else Card(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().testTag("plannerFooter").onSizeChanged{footerPx=it.height}.navigationBarsPadding().padding(8.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(Modifier.padding(8.dp)) {
                vm.notice?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
                TripRecovery.failure?.let {Text(it,color=RED)}
                vm.error?.let { Text(it,color=RED);TextButton(onClick={vm.clearError()}){Text("Dismiss")}}
                PrimaryAction(vm, false, actions)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = {
                        val name=vm.draftSavedName
                        if(name==null)showSaved=true else if(vm.saveScenario(name,true))vm.notice="Changes saved"
                    },modifier=Modifier.weight(1f)) { Text(if(vm.draftSavedName==null) "Save" else "Save changes") }
                    TextButton(onClick = { showPlannerSettings = true },modifier=Modifier.weight(1f)) { Text("Settings") }
                    TextButton(onClick = { showChat = true },modifier=Modifier.weight(1f)) { Text("Talk") }
                }
                if (status.running) TextButton(onClick=onStop) { Text("Stop simulation") }
            }
        }
    }

    orderProposal?.let { proposed -> AlertDialog(onDismissRequest={orderProposal=null},title={Text("Review suggested order")},
        text={Column(Modifier.heightIn(max=320.dp).verticalScroll(rememberScrollState())) {Text("Geometric estimate only; actual road travel may differ. Final destination stays fixed.");proposed.forEachIndexed{i,stop->Text("${i+1}. ${stop.name}")}}},
        confirmButton={TextButton(onClick={vm.applyStopOrder(proposed);orderProposal=null}){Text("Use this order")}},dismissButton={TextButton(onClick={orderProposal=null}){Text("Keep current order")}}) }
    if(showRecovery) AlertDialog(onDismissRequest={showRecovery=false},title={Text("Restore your previous trip?")},
        text={Text(if(recovery!=null) "Your interrupted trip and stop settings are available. Restore for editing, or explicitly resume from the checkpoint. Resuming turns simulation on." else "An automatically preserved draft is available. Restoring does not start simulation.")},
        confirmButton={TextButton(onClick={val saved=recovery?.scenario ?: recoveredDraft;if(saved!=null)vm.loadScenario(saved);showRecovery=false}){Text("Restore for editing")}},
        dismissButton={Row {
            if(recovery!=null) TextButton(onClick={withPerms{vm.recoverLive(recovery,onStartService)};showRecovery=false}){Text("Resume trip")}
            TextButton(onClick={showRecovery=false}){Text("Not now")}
        }})
    if (showPlannerSettings) androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = { showPlannerSettings = false },
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxSheet * 1.6f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Trip settings", style = MaterialTheme.typography.titleLarge)
            val target=if(vm.planMode==PlanMode.ITINERARY)vm.stops.lastOrNull() else null
            target?.let {Text("Arrival settings for ${it.name}")}
            ArrivalActivityPicker(target?.arrivalActivity ?: vm.arrivalActivity) {
                vm.arrivalActivity=it
                if(target!=null)vm.stops[vm.stops.lastIndex]=vm.stops.last().copy(arrivalActivity=it)
            }
            StayChoicePicker(target?.dwellMinutes ?: vm.defaultStayMinutes,target?.stayUntilLeave ?: vm.stayUntilLeave,{
                vm.defaultStayMinutes=it
                if(target!=null)vm.stops[vm.stops.lastIndex]=vm.stops.last().copy(dwellMinutes=it)
            },{
                vm.stayUntilLeave=it
                if(target!=null)vm.stops[vm.stops.lastIndex]=vm.stops.last().copy(stayUntilLeave=it)
            })
            TextButton(enabled=vm.dest!=null || vm.stops.isNotEmpty(),onClick={showPlannerSettings=false;plannerPinPick="destination"}) {Text("Fine-tune destination pin")}
            TextButton(enabled=vm.dest!=null || vm.stops.isNotEmpty(),onClick={showPlannerSettings=false;plannerPinPick="entrance"}) {Text("Set entrance / parking pin")}
            if((if(target!=null)target.entrance else vm.arrivalEntrance)!=null) TextButton(onClick={vm.setEntrance(null)}) {Text("Remove entrance pin")}

            Text(vm.departureMillis?.let {"Departure ${tripTime(it)}"} ?: "Depart when started")
            TextButton(onClick={chooseTripTime(context,vm.departureMillis ?: System.currentTimeMillis()){vm.departureMillis=it}}){Text("Set departure")}
            if(vm.departureMillis!=null)TextButton(onClick={vm.departureMillis=null}){Text("Depart when started")}
            if(recovery!=null || recoveredDraft!=null) TextButton(onClick={showPlannerSettings=false;showRecovery=true}) {Text("Recover previous trip")}
            if(vm.planMode==PlanMode.ROUTE) Row(verticalAlignment=Alignment.CenterVertically) {
                androidx.compose.material3.Switch(checked=vm.replayExactRoute,onCheckedChange=vm::setExactReplay)
                Text("Replay exact road path",Modifier.weight(1f))
            }
            Text(if(vm.replayExactRoute) "Saved road geometry and timing; begin at the original start." else "Routes recalculate when used.",style=MaterialTheme.typography.bodySmall)
            Controls(vm, status, !status.running && status.blocked, { showSetup = true }, actions)
            TextButton(onClick = { showPlannerSettings = false }) { Text("Back to map") }
        }
    }
    ContinuationSheet(vm.continuation, vm.savedScenarios.toList(), confirmAddition, onStop)
    if (showLiveDetails) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLiveDetails = false },
            title = { Text("Trip details") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    locationOutputIssue(status)?.let { Text(it, color = RED) }
                    TextButton(onClick = { showLiveDetails = false; showSetup = true }) { Text("Setup and output checks") }
                    Row {
                        TextButton(onClick = { follow = true; goTo(LatLng(status.lat, status.lng)); showLiveDetails = false }) { Text("Follow location") }
                        TextButton(onClick = {
                            follow = false
                            val pts = session.points + session.stops.map { it.stop.point }
                            if (pts.size >= 2) scope.launch {
                                val bounds = LatLngBounds.Builder(); pts.forEach { bounds.include(it.toG()) }
                                runCatching { camera.animate(CameraUpdateFactory.newLatLngBounds(bounds.build(), 90)) }
                            }
                            showLiveDetails = false
                        }) { Text("Whole trip") }
                    }
                    LiveControls(status, session,
                        onNow = { showLiveDetails = false; planNow() },
                        onNext = { showLiveDetails = false; planNext() }, onStop = onStop,
                        onChat = { showLiveDetails = false; showChat = true },
                        onStops = { showLiveDetails = false; showItinerary = true },
                        onAdvanced = { showLiveDetails = false; showAdvanced = true })
                }
            },
            confirmButton = { TextButton(onClick = { showLiveDetails = false }) { Text("Back to map") } },
        )
    }
    if (showItinerary) MyItinerarySheet(vm, onDismiss = { showItinerary = false }, onAdd = { showItinerary = false; addDestination() }, onReplace = { id -> showItinerary=false;planning=false;follow=false;vm.continuation.beginReplace(id) })
    if (showChat) ChatPanel { showChat = false }
    if (showUpcoming) UpcomingDialog(session, onDismiss = { showUpcoming = false }, onAdd = { showUpcoming = false; planNext() })
    if (showAdvanced) AdvancedDialog(status) { showAdvanced = false }
    if (showSaved) {
        SavedPlansDialog(
            vm = vm,
            active = status.running,
            onAddLive = { item, destinationOnly ->
                showSaved = false; planning = false; follow = false
                vm.continuation.beginSaved(item, destinationOnly)
            },
            onDismiss = { showSaved = false },
            onLoaded = { sc ->
                showSaved = false
                planning = true
                sheetCollapsed = false
                val focusPt = sc.dest ?: sc.stops.firstOrNull()?.let { LatLng(it.lat, it.lng) }
                if (focusPt != null) goTo(focusPt)
            },
        )
    }
    vm.dwellEditIndex?.let { i ->
        val stop = vm.stops.getOrNull(i)
        if (stop == null) vm.dwellEditIndex = null
        else DwellDialog(
            stopName = stop.name, minutes = stop.dwellMinutes,
            onSet = { m -> vm.setDwell(i, m); vm.dwellEditIndex = null },
            deadline=stop.arriveByMillis,onDeadline={vm.setStopDeadline(i,it)},
            arrival=stop.arrivalActivity,onArrival={vm.stops[i]=vm.stops[i].copy(arrivalActivity=it)},
            untilLeave=stop.stayUntilLeave,onHold={vm.stops[i]=vm.stops[i].copy(stayUntilLeave=it)},
            onDismiss = { vm.dwellEditIndex = null },
        )
    }
    if (showSetup) {
        val checks = remember { setupChecks() }
        SetupDialog(
            vm = vm,
            status = status,
            checks = checks,
            hasKey = vm.hasKey,
            initialKey = keyStore.userKey,
            onSaveKey = saveKey,
            onDismiss = { showSetup = false },
            onDev = onOpenDevSettings,
            onPerms = onRequestPermissions,
            onBattery = onRequestBattery,
        )
    }
}

/** One compact location box: coloured dot, editable text, optional trailing control. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    val bringIntoView=remember {BringIntoViewRequester()}
    var focused by remember {mutableStateOf(false)}
    val imeBottom=WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(focused,imeBottom) {
        if(focused && imeBottom>0) { kotlinx.coroutines.delay(150);bringIntoView.bringIntoView() }
    }
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
            modifier = Modifier.weight(1f).bringIntoViewRequester(bringIntoView).onFocusChanged { focused=it.isFocused;onFocus(it.isFocused) },
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
    val transit = vm.mode == TravelMode.TRANSIT
    val running = status.running
    val snap = vm.planMode == PlanMode.SNAP
    val itin = vm.planMode == PlanMode.ITINERARY

    // Where we stand
    val summary = vm.routeSummary
    if (summary != null) {
        Text(summary, fontSize = 13.sp, color = ACCENT, fontWeight = FontWeight.SemiBold)
    } else {
        Text(
            when (vm.planMode) {
                PlanMode.SNAP -> if (vm.dest == null) "Pick a place above (or tap the map). Place mode puts you there instantly and holds until Stop."
                    else "Ready — you will be at “${vm.destName}” instantly and stay there until Stop."
                PlanMode.ROUTE -> when {
                    vm.dest == null -> "Fill Start and End above, or tap the map for the End and long-press for the Start."
                    running && vm.useSimulatedStart -> "Next leg starts from the current simulated position; ⌖ on the Start box changes that."
                    else -> "Start and End set — get the route."
                }
                PlanMode.ITINERARY -> if (vm.stops.isEmpty()) "Each place you pick above (or tap on the map) is added as the next stop."
                    else "Pick the next stop above, or start the day."
            },
            fontSize = 12.sp, color = MUTED,
        )
    }

    if (!snap) {
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

        vm.notice?.let { Text(it, fontSize = 12.sp, color = ACCENT, fontWeight = FontWeight.SemiBold) }
        if (fly) {
            Text("Emulated flight: taxi, climb to 35,000 ft, cruise at about 550 mph, descent, landing.", fontSize = 12.sp, color = MUTED)
        } else if (vm.mode == TravelMode.DRIVE) {
            val n = vm.speedOverLimit.toInt()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text(if (itin) "New stops · driving" else "Driving · cruise at the posted limit", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("${if (n >= 0) "+" else ""}$n mph", color = ACCENT, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Slider(value = vm.speedOverLimit, onValueChange = { vm.speedOverLimit = it }, valueRange = -10f..15f)
            Text(
                "Estimated road limits. This setting applies to the new plan. Use Advanced in the Live view to change the running trip.",
                fontSize = 11.sp, color = MUTED,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Realism.entries.forEach { r ->
                    FilterChip(selected = vm.realism == r, onClick = { vm.realism = r }, label = { Text(when (r) { Realism.CONSTANT -> "No lights"; Realism.REALISTIC -> "Normal lights"; Realism.BUSY -> "Heavy traffic" }) })
                }
            }
        } else if (transit) {
            Text(
                "Real public transport from the timetable: walk to the stop, wait for the scheduled departure, ride with station stops, walk to the end.",
                fontSize = 12.sp, color = MUTED,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to "Any", "rail" to "Rail", "subway" to "Subway", "train" to "Train", "tram" to "Tram", "bus" to "Bus").forEach { (key, label) ->
                    FilterChip(selected = vm.transitPref == key, onClick = { vm.chooseTransitPref(key) }, label = { Text(label) })
                }
            }
            if (vm.transitSegments.isNotEmpty()) TransitLegs(vm.transitSegments)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text(
                    if (itin) "New stops · ${vm.mode.label()} speed" else "Average speed · ${vm.mode.label()}",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
                Text("${vm.avgMph.toInt()} mph", color = ACCENT, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Slider(value = vm.avgMph, onValueChange = { vm.avgMph = it }, valueRange = speedRange(vm.mode))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Realism.entries.forEach { r ->
                    FilterChip(selected = vm.realism == r, onClick = { vm.realism = r }, label = { Text(r.label()) })
                }
            }
        }
    }

    if (itin) {
        if (vm.stops.isNotEmpty()) {
            val tl = vm.timeline()
            Text(
                "${vm.stops.size} ${if (vm.stops.size == 1) "stop" else "stops"} · ${fmtStay(vm.dwellTotalMinutes)} on site" +
                    (tl.lastOrNull()?.let { " · ends about ${fmtClock(it.leaveMillis)}" } ?: ""),
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { vm.addReturnToStart() }, enabled = vm.start != null && vm.stops.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text("＋ Return to start at the end") }
        }
        Text(
            "Stops are edited in the card above: tap a stop's time to set how long you stay, its icon to change how you get there, ⋮ to reorder or remove.",
            fontSize = 12.sp, color = MUTED,
        )
    }

    PrimaryAction(vm, running, a)

    if (!snap) {
        // Fast-forward (testing)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Fast-forward", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            listOf(1f, 2f, 5f, 10f).forEach { sc ->
                FilterChip(selected = vm.timeScale == sc, onClick = { vm.timeScale = sc }, label = { Text("${sc.toInt()}×") })
            }
        }
        if (vm.timeScale > 1f) {
            Text(
                "Positions advance ${vm.timeScale.toInt()}× faster than real time (for testing); reported speed stays realistic.",
                fontSize = 12.sp, color = AMBER,
            )
        }
    }

    // During a run these controls live exclusively in Advanced, not the draft.
    if (!running) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("GPS signal", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(4.dp))
        Signal.PRESETS.forEach { p ->
            FilterChip(selected = vm.signal.name == p.name, onClick = { vm.setSignalPreset(p) }, label = { Text(p.name) })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.dropSignal(15) }, enabled = running) { Text("Drop GPS 15 s") }
        OutlinedButton(onClick = { vm.dropSignal(60) }, enabled = running) { Text("Drop 60 s") }
    }

    }

    // Notices
    if (!vm.hasKey) {
        Text("Search and routing need a Google Maps key — tap ⚙ to paste yours and test it. Place mode works without one.", fontSize = 12.sp, color = AMBER)
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
private fun PrimaryAction(vm: MirageViewModel, running: Boolean, a: SimActions) {
    val fly = vm.mode == TravelMode.FLY
    if (MockState.status.collectAsState(context = kotlinx.coroutines.Dispatchers.Main.immediate).value.starting) { BusyRow("Starting…"); return }
    when (vm.planMode) {
        PlanMode.SNAP -> BigButton(
            if (vm.dest != null) "Start at “${vm.destName}”" else "Choose a place",
            enabled = vm.dest != null, onClick = { a.holdAt(vm.dest) },
        )
        PlanMode.ITINERARY ->
            if (vm.itineraryBusy) BusyRow("Routing legs…")
            else BigButton(
                when { running && vm.queueAfterCurrent -> "Queue itinerary after arrival"; running -> "Start itinerary now (replaces)"; else -> "Start itinerary" },
                enabled = vm.stops.isNotEmpty(), onClick = a.startItinerary,
            )
        PlanMode.ROUTE -> when {
            vm.phase == Phase.ROUTING -> BusyRow("Routing…")
            vm.canStart -> BigButton(
                when {
                    running && vm.queueAfterCurrent -> "Queue after arrival"
                    running -> if (fly) "Start flight now (replaces)" else "Start now (replaces)"
                    fly -> "Start flight"
                    else -> "Start simulation"
                },
                onClick = a.start,
            )
            vm.dest != null -> BigButton(if (fly) "Plot flight" else "Get route", icon = null, onClick = a.getRoute)
            else -> BigButton("Set Start and End above", enabled = false, icon = null, onClick = {})
        }
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

// ---- Setup dialog ------------------------------------------------------------------

@Composable
private fun SetupDialog(
    vm: MirageViewModel,
    status: MockStatus,
    checks: SetupChecks,
    hasKey: Boolean,
    initialKey: String,
    onSaveKey: (String) -> Unit,
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
                Text("Google Maps access", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                val credits by CreditsState.credits.collectAsState(context = kotlinx.coroutines.Dispatchers.Main.immediate)
                if (vm.api.mode == ApiConfig.Mode.HOSTED) {
                    Text(
                        "Mirage hosted · " + (if (credits >= 0) "$credits credits left" else "credits shown after the first search"),
                        fontSize = 12.sp, color = GREEN,
                    )
                } else {
                    var keyText by remember { mutableStateOf(initialKey) }
                    Text(
                        if (hasKey) "Key configured ✓ — tap Test to verify every API" else "No key yet — paste one below, then Save & test",
                        fontSize = 12.sp, color = if (hasKey) GREEN else AMBER,
                    )
                    OutlinedTextField(
                        value = keyText, onValueChange = { keyText = it.trim() }, singleLine = true,
                        label = { Text("Your Google Maps API key") }, placeholder = { Text("AIza…") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onSaveKey(keyText) }, enabled = keyText.isNotBlank() && !vm.keyTesting) { Text("Save & test") }
                        TextButton(onClick = { vm.testKey() }, enabled = hasKey && !vm.keyTesting) { Text("Test") }
                    }
                    if (vm.keyTesting) BusyRow("Testing every API…")
                    vm.keyTest?.forEach { c -> SetupRow(ok = c.ok, title = c.api, detail = c.detail, action = "", onAction = {}) }
                    Text(
                        "Get a key at console.cloud.google.com → APIs & Services → Credentials. Enable Directions API, Geocoding API and Places API (New). Leave Application restrictions at None.",
                        fontSize = 11.sp, color = MUTED,
                    )
                }
                HorizontalDivider()
                Text("Automation (adb)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("Token: ${vm.automationToken}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Text(
                    "adb shell am start -n com.mirage.app/com.mirage.spike.MainActivity --es cmd pause --es token ${vm.automationToken}\nCommands: pause, resume, skip, stop, timescale, speed_over, signal, drop, place, route, plan — see README.",
                    fontSize = 11.sp, color = MUTED,
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
        if (!ok && action.isNotBlank()) TextButton(onClick = onAction) { Text(action, fontSize = 12.sp) }
    }
}

// ---- Itinerary chain rows (in the top card) ---------------------------------------------

private fun fmtClock(millis: Long): String =
    java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(millis)).lowercase()

@Composable
internal fun ChainRow(
    index: Int, stop: ItineraryStop, entry: MirageViewModel.TimelineEntry?,
    current: Boolean, done: Boolean,
    onMode: (TravelMode) -> Unit, onStay: () -> Unit, onMove: (Int) -> Unit, onRemove: () -> Unit,
    handleModifier: Modifier, canMoveUp: Boolean, canMoveDown: Boolean,
) {
    var modeMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    val bg = if (current) ACCENT.copy(alpha = 0.10f) else Color.Transparent
    val textColor = if (done) MUTED else MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(10.dp)).padding(horizontal = 4.dp, vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).background(if (done) MUTED else ACCENT, CircleShape), contentAlignment = Alignment.Center) {
                Text("${index + 1}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Box {
                IconButton(onClick = { modeMenu = true }, modifier = Modifier.size(34.dp)) {
                    Icon(stop.mode.icon(), contentDescription = "How to get there: ${stop.mode.label()}", tint = if (done) MUTED else ACCENT)
                }
                DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                    TravelMode.entries.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m.label()) },
                            leadingIcon = { Icon(m.icon(), contentDescription = null) },
                            onClick = { modeMenu = false; onMode(m) },
                        )
                    }
                }
            }
            Text(
                stop.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = textColor,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Surface(
                shape = RoundedCornerShape(12.dp), color = ACCENT.copy(alpha = 0.12f),
                modifier = Modifier.clickable { onStay() },
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Schedule, contentDescription = "Stay time", tint = ACCENT, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(fmtStay(stop.dwellMinutes), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ACCENT)
                }
            }
            Box {
                IconButton(onClick = { moreMenu = true }, modifier = handleModifier.size(48.dp)) {
                    Icon(Icons.Filled.DragHandle, contentDescription = "Reorder stop ${index + 1}: ${stop.name}", tint = MUTED)
                }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    DropdownMenuItem(text = { Text("Move up") }, enabled = canMoveUp, onClick = { moreMenu = false; onMove(-1) })
                    DropdownMenuItem(text = { Text("Move down") }, enabled = canMoveDown, onClick = { moreMenu = false; onMove(1) })
                    DropdownMenuItem(text = { Text("Remove", color = RED) }, onClick = { moreMenu = false; onRemove() })
                }
            }
        }
        if(stop.arriveByMillis!=null) Text("Target ${tripTime(stop.arriveByMillis)}" + if(entry!=null && entry.arriveMillis>stop.arriveByMillis) " · estimated late arrival" else "",color=if(entry!=null && entry.arriveMillis>stop.arriveByMillis) RED else MUTED,fontSize=11.sp)
        if (entry != null) {
            Text(
                "arrive ${fmtClock(entry.arriveMillis)}" +
                    (if (stop.dwellMinutes > 0) " · leave ${fmtClock(entry.leaveMillis)}" else "") +
                    " · ${fmtMiles(entry.legMiles * 1609.344)} by ${stop.mode.label().lowercase()}",
                fontSize = 11.sp, color = MUTED, modifier = Modifier.padding(start = 30.dp, bottom = 2.dp),
            )
        }
    }
}

// ---- Stay time editor -----------------------------------------------------------------

private fun fmtStay(minutes: Int): String {
    if (minutes < 60) return "$minutes min"
    val h = minutes / 60; val m = minutes % 60
    return if (m == 0) "$h h" else "$h h $m min"
}

@Composable
internal fun DwellDialog(stopName: String, minutes: Int, onSet: (Int) -> Unit, onDismiss: () -> Unit, deadline: Long? = null, onDeadline: ((Long?)->Unit)? = null, arrival: com.mirage.spike.engine.ArrivalActivity? = null, onArrival: ((com.mirage.spike.engine.ArrivalActivity)->Unit)? = null, untilLeave: Boolean = false, onHold: ((Boolean)->Unit)? = null, onPreferences: ((Int, Boolean, com.mirage.spike.engine.ArrivalActivity?)->Unit)? = null) {
    val context=LocalContext.current
    var selectedHold by remember(untilLeave) { mutableStateOf(untilLeave) }
    var selectedArrival by remember(arrival) { mutableStateOf(arrival) }
    var text by remember { mutableStateOf(minutes.toString()) }
    val value = text.trim().toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stay at $stopName") },
        text = {
            Column(Modifier.heightIn(max=450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if(onHold!=null || onPreferences!=null) StayChoicePicker(value ?: minutes,selectedHold,{text=it.toString()},{selectedHold=it})
                if(selectedArrival!=null && (onArrival!=null || onPreferences!=null)) ArrivalActivityPicker(selectedArrival!!) { selectedArrival=it }
                Text("How long to stay before continuing the itinerary (0–1,440 minutes).", fontSize = 12.sp, color = MUTED)
                if(onDeadline!=null) {
                    TextButton(onClick={chooseTripTime(context,deadline ?: System.currentTimeMillis()){onDeadline(it)}}){Text(deadline?.let{"Arrive by ${tripTime(it)}"} ?: "Set arrival target")}
                    if(deadline!=null)TextButton(onClick={onDeadline(null)}){Text("Clear arrival target")}
                }
                OutlinedTextField(
                    value = text, onValueChange = { text = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("Minutes") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 45, 60, 90, 120, 180, 240).forEach { m ->
                        FilterChip(selected = value == m, onClick = { text = m.toString() }, label = { Text(fmtStay(m)) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if(onPreferences!=null) value?.let {onPreferences(it,selectedHold,selectedArrival)} else {selectedArrival?.let { onArrival?.invoke(it) }; value?.let(onSet);onHold?.invoke(selectedHold)} }, enabled = value != null && value in 0..1440) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- Saved plans ------------------------------------------------------------------------

private fun scenarioSummary(sc: SavedScenario, kind: PlanMode): String = when (kind) {
    PlanMode.SNAP -> "Place · ${sc.destName}"
    PlanMode.ROUTE -> "${if (sc.startIsReal) "My location" else sc.startName} → ${sc.destName} · ${sc.travelMode.label()}"
    PlanMode.ITINERARY -> {
        val total = sc.stops.sumOf { it.dwellMinutes }
        "${sc.stops.size} ${if (sc.stops.size == 1) "stop" else "stops"} · ${fmtStay(total)} on site · " +
            sc.stops.joinToString(" → ") { it.name }
    }
}

// ---- Helpers ------------------------------------------------------------------------

private fun TravelMode.label(): String = when (this) {
    TravelMode.DRIVE -> "Drive"
    TravelMode.BIKE -> "Bike"
    TravelMode.WALK -> "Walk"
    TravelMode.TRANSIT -> "Transit"
    TravelMode.FLY -> "Fly"
}

private fun TravelMode.icon(): ImageVector = when (this) {
    TravelMode.DRIVE -> Icons.Filled.DirectionsCar
    TravelMode.BIKE -> Icons.Filled.DirectionsBike
    TravelMode.WALK -> Icons.Filled.DirectionsWalk
    TravelMode.TRANSIT -> Icons.Filled.DirectionsTransit
    TravelMode.FLY -> Icons.Filled.Flight
}

private fun TransitVehicle.icon(): ImageVector = when (this) {
    TransitVehicle.BUS -> Icons.Filled.DirectionsBus
    TransitVehicle.SUBWAY -> Icons.Filled.Subway
    TransitVehicle.TRAIN, TransitVehicle.RAIL -> Icons.Filled.Train
    TransitVehicle.TRAM -> Icons.Filled.Tram
    TransitVehicle.FERRY -> Icons.Filled.DirectionsBoat
    TransitVehicle.OTHER -> Icons.Filled.DirectionsTransit
}

/** The scheduled legs of a transit trip: walk · line from → to · times. */
@Composable
private fun TransitLegs(segments: List<RouteSegment>) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            segments.forEach { seg ->
                val td = seg.transit
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(td?.vehicle?.icon() ?: Icons.Filled.DirectionsWalk, contentDescription = null, tint = ACCENT, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    if (td == null) {
                        Text("Walk ${fmtMiles(seg.distanceMeters)} · ${fmtDuration(seg.durationSeconds)}", fontSize = 12.sp, color = MUTED)
                    } else {
                        Column {
                            Text("${td.line} ${td.vehicle.label.lowercase()} → ${td.headsign.ifBlank { td.toStop }}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("${td.fromStop} ${td.departureText} → ${td.toStop} ${td.arrivalText} · ${td.numStops} ${if (td.numStops == 1) "stop" else "stops"}", fontSize = 12.sp, color = MUTED)
                        }
                    }
                }
            }
        }
    }
}

private fun PlanMode.label(): String = when (this) {
    PlanMode.SNAP -> "Place"
    PlanMode.ROUTE -> "Route"
    PlanMode.ITINERARY -> "Itinerary"
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
 * A FRESH real fix, never a cached mock. Right after a Stop the fused provider's cache is
 * the final spoofed point (and `getCurrentLocation` happily returns cached fixes up to a
 * minute old), so: demand zero cache age, then fall back to the platform network and GPS
 * providers directly, then to any non-mock last-known fix. Null only if nothing real exists.
 */
@SuppressLint("MissingPermission")
private suspend fun realLocation(context: Context): LatLng? {
    val flp = LocationServices.getFusedLocationProviderClient(context)
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // 1. A recent real last-known fix is good enough to start with (instant).
    val last = await<Location?> { done -> flp.lastLocation.addOnSuccessListener { done(it) }.addOnFailureListener { done(null) } }
    if (last != null && !isMockLocation(last) && ageSec(last) < 60) return LatLng(last.latitude, last.longitude)

    // 2. Fused provider, fresh computation only.
    repeat(2) {
        val req = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(0)
            .setDurationMillis(12_000)
            .build()
        val loc = await<Location?> { done ->
            flp.getCurrentLocation(req, null).addOnSuccessListener { done(it) }.addOnFailureListener { done(null) }
        }
        if (loc != null && !isMockLocation(loc)) return LatLng(loc.latitude, loc.longitude)
        delay(1000)
    }

    // 3. Platform providers directly (network is fast indoors; GPS if that fails).
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
            if (!runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)) continue
            val loc = await<Location?> { done ->
                runCatching {
                    lm.getCurrentLocation(provider, CancellationSignal(), ContextCompat.getMainExecutor(context)) { done(it) }
                }.onFailure { done(null) }
            }
            if (loc != null && !isMockLocation(loc)) return LatLng(loc.latitude, loc.longitude)
        }
    }

    // 4. Any real last-known fix, however old, beats nothing.
    if (last != null && !isMockLocation(last)) return LatLng(last.latitude, last.longitude)
    for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
        val l = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
        if (!isMockLocation(l)) return LatLng(l.latitude, l.longitude)
    }
    return null
}

private fun lastFixClock(millis: Long): String =
    if (millis <= 0L) "—" else java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(millis))

private fun ageSec(l: Location): Long =
    (android.os.SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000_000L

/** The fastest real (non-mock) position available, any age — for putting the map somewhere sensible at once. */
@SuppressLint("MissingPermission")
private suspend fun quickLastReal(context: Context): LatLng? {
    val flp = LocationServices.getFusedLocationProviderClient(context)
    val last = await<Location?> { done -> flp.lastLocation.addOnSuccessListener { done(it) }.addOnFailureListener { done(null) } }
    if (last != null && !isMockLocation(last)) return LatLng(last.latitude, last.longitude)
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
        val l = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
        if (!isMockLocation(l)) return LatLng(l.latitude, l.longitude)
    }
    return null
}

/** Bridge a one-shot callback API into a suspend call; the callback fires at most once. */
private suspend fun <T> await(start: ((T) -> Unit) -> Unit): T = suspendCancellableCoroutine { cont ->
    var delivered = false
    start { v -> if (!delivered && cont.isActive) { delivered = true; cont.resume(v) } }
}

private fun isMockLocation(l: Location): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) l.isMock
    else @Suppress("DEPRECATION") l.isFromMockProvider

private fun armStatic(at: LatLng?, name: String, cfg: ApiConfig, arrival: com.mirage.spike.engine.ArrivalActivity, entrance: LatLng?, untilLeave: Boolean, minutes: Int) {
    PlaybackSource.clearQueue()
    PlaybackSource.consumeSkip()
    PlaybackSource.endPoint = at
    PlaybackSource.paused = false
    LiveSession.clear()
    PlaybackSource.current = at?.let { com.mirage.spike.engine.holdPlan(it, name, cfg, arrival, entrance, untilLeave, minutes).fixes() }
    PlaybackSource.routePoints = listOfNotNull(at)
    PlaybackSource.label = name
}

private fun LatLng.toG() = GLatLng(lat, lng)
private fun GLatLng.toE() = LatLng(latitude, longitude)

