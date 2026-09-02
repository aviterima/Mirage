package com.mirage.spike

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng as GLatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import com.mirage.spike.engine.LatLng
import com.mirage.spike.engine.Realism
import com.mirage.spike.engine.TravelMode

private val ACCENT = Color(0xFF4F46E5)

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

    val camera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(GLatLng(START_LAT, START_LNG), 13f)
    }

    val carState = rememberMarkerState()
    androidx.compose.runtime.LaunchedEffect(status.lat, status.lng) {
        carState.position = GLatLng(status.lat, status.lng)
    }
    androidx.compose.runtime.LaunchedEffect(vm.dest) {
        vm.dest?.let { camera.position = CameraPosition.fromLatLngZoom(it.toG(), 14f) }
    }

    Box(Modifier.fillMaxSize()) {

        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = false),
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
            if (status.running) {
                Marker(state = carState, title = "Mirage")
            }
        }

        // Top: search + notices
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            var query by remember { mutableStateOf("") }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search a place, address, or landmark") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                    if (query.isNotBlank()) vm.search(query) { }
                }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (!vm.hasKey) {
                Notice("No Google Maps API key set — the map, search, and routing are disabled. Add MAPS_API_KEY to local.properties.", Color(0xFFD97706))
            }
            vm.error?.let { Notice("⚠ $it", Color(0xFFDC2626)) }
            if (status.leakSeen) {
                Notice("Real-fix leak detected during this session.", Color(0xFFD97706))
            }
        }

        // Bottom: controls or live HUD
        Card(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (status.running) {
                    LiveHud(status) { onStopService(); vm.onStopped() }
                } else {
                    Controls(
                        vm = vm,
                        onGetRoute = { vm.buildRoute() },
                        onStart = { onRequestPermissions(); vm.startSim(onStartService) },
                        onStatic = { onRequestPermissions(); armStatic(); onStartService() },
                    )
                    SetupRow(onOpenDevSettings, onRequestPermissions, onRequestBattery)
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String, color: Color) {
    Card(Modifier.fillMaxWidth()) {
        Text(text, color = color, fontSize = 12.5.sp, modifier = Modifier.padding(10.dp))
    }
}

@Composable
private fun Controls(
    vm: MirageViewModel,
    onGetRoute: () -> Unit,
    onStart: () -> Unit,
    onStatic: () -> Unit,
) {
    Text("Simulate route", fontSize = 18.sp, style = MaterialTheme.typography.titleMedium)
    Text(
        "Long-press the map to set the start (A), tap to set the destination (B) — or search by name.",
        fontSize = 12.sp, color = Color(0xFF5F6368)
    )

    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text("Average speed", fontSize = 14.sp)
        Text("${vm.avgMph.toInt()} mph", color = ACCENT, fontSize = 16.sp)
    }
    Slider(value = vm.avgMph, onValueChange = { vm.avgMph = it }, valueRange = 5f..80f)

    // Realism
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Realism.entries.forEach { r ->
            FilterChip(selected = vm.realism == r, onClick = { vm.realism = r }, label = { Text(r.name.lowercase().replaceFirstChar { it.uppercase() }) })
        }
    }
    // Mode
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TravelMode.entries.forEach { m ->
            FilterChip(selected = vm.mode == m, onClick = { vm.mode = m }, label = { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) })
        }
    }

    when {
        vm.phase == Phase.ROUTING -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text("Routing…")
        }
        vm.routePts.isEmpty() -> Button(onClick = onGetRoute, enabled = vm.hasKey && vm.dest != null, modifier = Modifier.fillMaxWidth()) {
            Text("Get route")
        }
        else -> Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start simulation") }
    }
    OutlinedButton(onClick = onStatic, modifier = Modifier.fillMaxWidth()) { Text("Spoof current start point (no route)") }
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
        Text("${mph.toInt()} mph", fontSize = 26.sp, style = MaterialTheme.typography.headlineSmall)
        Text("● ${status.message}", color = healthColor, fontSize = 14.sp)
    }
    Text(
        "${"%.5f".format(status.lat)}, ${"%.5f".format(status.lng)}",
        fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color(0xFF5F6368)
    )
    Text("Fixes ${status.emittedCount} · re-asserts ${status.reassertCount} · leak ${if (status.leakSeen) "YES" else "no"}", fontSize = 12.sp)
    Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
}

@Composable
private fun SetupRow(onDev: () -> Unit, onPerms: () -> Unit, onBattery: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onDev, modifier = Modifier.weight(1f)) { Text("Mock app", fontSize = 12.sp) }
        TextButton(onClick = onPerms, modifier = Modifier.weight(1f)) { Text("Permissions", fontSize = 12.sp) }
        TextButton(onClick = onBattery, modifier = Modifier.weight(1f)) { Text("Battery", fontSize = 12.sp) }
    }
}

private fun armStatic() {
    com.mirage.spike.engine.PlaybackSource.current = null
    com.mirage.spike.engine.PlaybackSource.routePoints = emptyList()
    com.mirage.spike.engine.PlaybackSource.label = "Static"
}

private fun LatLng.toG() = GLatLng(lat, lng)
private fun GLatLng.toE() = LatLng(latitude, longitude)
