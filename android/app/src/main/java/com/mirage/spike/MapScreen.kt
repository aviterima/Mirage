package com.mirage.spike

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng as GLatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import com.mirage.spike.engine.LatLng
import com.mirage.spike.engine.Realism
import com.mirage.spike.engine.TravelMode

private val ACCENT = Color(0xFF4F46E5)
private val MUTED = Color(0xFF5F6368)

@Composable
fun MapScreen(
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onOpenDevSettings: () -> Unit,
    onRequestBattery: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val vm: MirageViewModel = viewModel()
    val status by MockState.status.collectAsState()
    var showSetup by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }

    val camera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(GLatLng(START_LAT, START_LNG), 13f)
    }
    val carState = rememberMarkerState()
    LaunchedEffect(status.lat, status.lng) { carState.position = GLatLng(status.lat, status.lng) }
    LaunchedEffect(vm.dest) { vm.dest?.let { camera.position = CameraPosition.fromLatLngZoom(it.toG(), 14f) } }

    val context = LocalContext.current
    val hasLocPerm = ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.ACCESS_FINE_LOCATION
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    // On first load (and once permission is granted), center on the device's real
    // location and use it as the start point — unless a simulation is already running.
    LaunchedEffect(hasLocPerm) {
        if (hasLocPerm && !status.running) {
            runCatching {
                LocationServices.getFusedLocationProviderClient(context).lastLocation
                    .addOnSuccessListener { loc ->
                        if (loc != null && !MockState.status.value.running) {
                            vm.setStartPoint(LatLng(loc.latitude, loc.longitude))
                            camera.position = CameraPosition.fromLatLngZoom(GLatLng(loc.latitude, loc.longitude), 14f)
                        }
                    }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {

        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            properties = MapProperties(isMyLocationEnabled = hasLocPerm),
            uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = hasLocPerm),
            contentPadding = PaddingValues(top = 96.dp),
            onMapClick = { vm.setDestPoint(it.toE()) },
            onMapLongClick = { vm.setStartPoint(it.toE()) },
        ) {
            vm.start?.let { s ->
                Marker(
                    state = rememberMarkerState(key = "s-${s.lat},${s.lng}", position = s.toG()),
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN),
                    title = "Start",
                )
            }
            vm.dest?.let { d ->
                Marker(
                    state = rememberMarkerState(key = "d-${d.lat},${d.lng}", position = d.toG()),
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET),
                    title = "Destination",
                )
            }
            if (vm.routePts.isNotEmpty()) {
                Polyline(points = vm.routePts.map { it.toG() }, color = ACCENT, width = 14f)
            }
            if (status.running) Marker(state = carState, title = "Mirage")
        }

        // Floating search bar + settings
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            var query by remember { mutableStateOf("") }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search a place or address") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                    if (query.isNotBlank()) vm.search(query) { }
                }),
                modifier = Modifier.weight(1f),
            )
            Surface(shape = CircleShape, shadowElevation = 3.dp, modifier = Modifier.size(52.dp)) {
                IconButton(onClick = { showSetup = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Setup", tint = ACCENT)
                }
            }
        }

        // Bottom control sheet
        Card(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 24.dp),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (status.running) {
                    LiveHud(status) { onStopService(); vm.onStopped() }
                } else {
                    Controls(
                        vm = vm,
                        showAdvanced = showAdvanced,
                        onToggleAdvanced = { showAdvanced = !showAdvanced },
                        onOpenSetup = { showSetup = true },
                        onGetRoute = { vm.buildRoute() },
                        onStart = { onRequestPermissions(); vm.startSim(onStartService) },
                        onStatic = { onRequestPermissions(); armStatic(); onStartService() },
                    )
                }
            }
        }
    }

    if (showSetup) {
        SetupDialog(
            hasKey = vm.hasKey,
            onDismiss = { showSetup = false },
            onDev = onOpenDevSettings,
            onPerms = onRequestPermissions,
            onBattery = onRequestBattery,
        )
    }
}

@Composable
private fun Controls(
    vm: MirageViewModel,
    showAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
    onOpenSetup: () -> Unit,
    onGetRoute: () -> Unit,
    onStart: () -> Unit,
    onStatic: () -> Unit,
) {
    // Endpoint hint
    val hint = when {
        vm.dest == null -> "Long-press the map to set start · tap to set destination · or search"
        else -> "Route ready to build"
    }
    Text(hint, fontSize = 12.sp, color = MUTED)

    // Average speed — the hero control
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Text("Average speed", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text("${vm.avgMph.toInt()} mph", color = ACCENT, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
    Slider(value = vm.avgMph, onValueChange = { vm.avgMph = it }, valueRange = 5f..80f)

    // Realism
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Realism.entries.forEach { r ->
            FilterChip(
                selected = vm.realism == r,
                onClick = { vm.realism = r },
                label = { Text(r.name.lowercase().replaceFirstChar { it.uppercase() }) },
            )
        }
    }

    // Advanced (mode)
    TextButton(onClick = onToggleAdvanced) {
        Text(if (showAdvanced) "Hide options" else "More options", color = ACCENT, fontSize = 13.sp)
    }
    if (showAdvanced) {
        Text("Transport mode", fontSize = 13.sp, color = MUTED)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TravelMode.entries.forEach { m ->
                FilterChip(
                    selected = vm.mode == m,
                    onClick = { vm.mode = m },
                    label = { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }
    }

    // Primary action — smart by state
    when {
        vm.phase == Phase.ROUTING -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text("Routing…")
        }
        vm.routePts.isNotEmpty() ->
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start simulation") }
        vm.hasKey && vm.dest != null ->
            Button(onClick = onGetRoute, modifier = Modifier.fillMaxWidth()) { Text("Get route") }
        else ->
            Button(onClick = onStatic, modifier = Modifier.fillMaxWidth()) { Text("Spoof this point") }
    }

    // Secondary / notices
    if (vm.routePts.isNotEmpty()) {
        OutlinedButton(onClick = onStatic, modifier = Modifier.fillMaxWidth()) { Text("Spoof start point instead") }
    }
    if (!vm.hasKey) {
        Text(
            "Map & routing need a Google Maps key — tap ⚙ to set it up. Spoofing works without it.",
            fontSize = 12.sp, color = Color(0xFFD97706),
        )
    }
    vm.error?.let { Text("⚠ $it", fontSize = 12.sp, color = Color(0xFFDC2626)) }
}

@Composable
private fun LiveHud(status: MockStatus, onStop: () -> Unit) {
    val mph = status.speedMps / 0.44704f
    val healthColor = when (status.health) {
        Health.GREEN -> Color(0xFF16A34A)
        Health.AMBER -> Color(0xFFD97706)
        Health.RED -> Color(0xFFDC2626)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${mph.toInt()}", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(" mph", fontSize = 15.sp, color = MUTED)
        }
        Surface(shape = RoundedCornerShape(20.dp), color = healthColor.copy(alpha = 0.12f)) {
            Text("● ${status.message}", color = healthColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
    Text("${"%.5f".format(status.lat)}, ${"%.5f".format(status.lng)}",
        fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MUTED)
    Text("Fixes ${status.emittedCount} · re-asserts ${status.reassertCount} · leak ${if (status.leakSeen) "YES ⚠" else "no"}",
        fontSize = 12.sp, color = MUTED)
    Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
}

@Composable
private fun SetupDialog(
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
                Text("1. Select Mirage as the mock-location app.", fontSize = 13.sp)
                OutlinedButton(onClick = onDev, modifier = Modifier.fillMaxWidth()) { Text("Open Developer options") }
                Text("2. Allow location & notifications.", fontSize = 13.sp)
                OutlinedButton(onClick = onPerms, modifier = Modifier.fillMaxWidth()) { Text("Grant permissions") }
                Text("3. Keep it running in Doze.", fontSize = 13.sp)
                OutlinedButton(onClick = onBattery, modifier = Modifier.fillMaxWidth()) { Text("Ignore battery optimization") }
                Spacer(Modifier.height(2.dp))
                Text(
                    if (hasKey) "Google Maps key: set ✓"
                    else "Google Maps key: not set — the map & routing stay off until MAPS_API_KEY is provided (see README).",
                    fontSize = 12.sp,
                    color = if (hasKey) Color(0xFF16A34A) else Color(0xFFD97706),
                )
            }
        },
    )
}

private fun armStatic() {
    com.mirage.spike.engine.PlaybackSource.current = null
    com.mirage.spike.engine.PlaybackSource.routePoints = emptyList()
    com.mirage.spike.engine.PlaybackSource.label = "Static"
}

private fun LatLng.toG() = GLatLng(lat, lng)
private fun GLatLng.toE() = LatLng(latitude, longitude)
