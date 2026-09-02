package com.mirage.spike

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirage.spike.engine.GoogleDirectionsRouteEngine
import com.mirage.spike.engine.Geo
import com.mirage.spike.engine.LatLng
import com.mirage.spike.engine.MotionModel
import com.mirage.spike.engine.MotionParams
import com.mirage.spike.engine.PlaybackSource
import com.mirage.spike.engine.Realism
import com.mirage.spike.engine.RouteResult
import com.mirage.spike.engine.RouteSpec
import com.mirage.spike.engine.TravelMode
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SpikeScreen(
                        onStartStatic = { armStaticThenStart() },
                        onStartDemo = { armDemoThenStart() },
                        onStartGoogle = ::armGoogleRoute,
                        onStop = { stopMockService() },
                        onOpenDevSettings = { openDeveloperSettings() },
                        onRequestBattery = { requestIgnoreBatteryOptimizations() }
                    )
                }
            }
        }
    }

    private fun startMockService() {
        val intent = Intent(this, MockLocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun stopMockService() {
        startService(Intent(this, MockLocationService::class.java).setAction(MockLocationService.ACTION_STOP))
    }

    private fun armStaticThenStart() {
        PlaybackSource.current = null
        PlaybackSource.routePoints = emptyList()
        PlaybackSource.label = "Static"
        startMockService()
    }

    private fun armDemoThenStart() {
        val route = demoRoute()
        val params = MotionParams(avgSpeedMps = 13.4, realism = Realism.REALISTIC)
        PlaybackSource.current = MotionModel(route, params).fixes()
        PlaybackSource.routePoints = route.points
        PlaybackSource.label = "Demo drive"
        startMockService()
    }

    /** Builds a short synthetic road-like path near the start point (no network needed). */
    private fun demoRoute(): RouteResult {
        val p0 = LatLng(START_LAT, START_LNG)
        val p1 = Geo.offset(p0, -300.0, 0.0)
        val p2 = Geo.offset(p1, 0.0, 400.0)
        val p3 = Geo.offset(p2, -300.0, 0.0)
        val p4 = Geo.offset(p3, 0.0, 450.0)
        val pts = listOf(p0, p1, p2, p3, p4)
        var dist = 0.0
        for (i in 1 until pts.size) dist += Geo.haversine(pts[i - 1], pts[i])
        return RouteResult(pts, dist, dist / 13.4)
    }

    private fun armGoogleRoute(scopeLaunch: (suspend () -> Unit) -> Unit, onError: (String) -> Unit) {
        scopeLaunch {
            try {
                val engine = GoogleDirectionsRouteEngine(BuildConfig.MAPS_API_KEY)
                val spec = RouteSpec(
                    origin = LatLng(37.7955, -122.3937),      // Ferry Building
                    destination = LatLng(37.7786, -122.3893), // Oracle Park
                    mode = TravelMode.DRIVE
                )
                val route = engine.route(spec)
                val params = MotionParams(avgSpeedMps = 13.4, realism = Realism.REALISTIC)
                PlaybackSource.current = MotionModel(route, params).fixes()
                PlaybackSource.routePoints = route.points
                PlaybackSource.label = "Google route"
                startMockService()
            } catch (e: Exception) {
                onError(e.message ?: "Routing failed")
            }
        }
    }

    private fun openDeveloperSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
    }

    @Suppress("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }
    }
}

@Composable
private fun SpikeScreen(
    onStartStatic: () -> Unit,
    onStartDemo: () -> Unit,
    onStartGoogle: (scopeLaunch: (suspend () -> Unit) -> Unit, onError: (String) -> Unit) -> Unit,
    onStop: () -> Unit,
    onOpenDevSettings: () -> Unit,
    onRequestBattery: () -> Unit,
) {
    val status by MockState.status.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Mirage", fontSize = 24.sp, style = MaterialTheme.typography.headlineSmall)
        Text(
            "Spoofs device location and holds it against Google Maps through screen-off / Doze. " +
                "Select Mirage as the mock-location app in Developer options first.",
            fontSize = 13.sp
        )

        val healthColor = when (status.health) {
            Health.GREEN -> Color(0xFF16A34A)
            Health.AMBER -> Color(0xFFD97706)
            Health.RED -> Color(0xFFDC2626)
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("● ${status.health}  —  ${status.message}", color = healthColor, fontSize = 18.sp)
                Text("Mock app selected: ${if (status.mockAppSelected) "yes" else "no"}", fontSize = 14.sp)
                Text(
                    "Lat/Lng: ${"%.6f".format(status.lat)}, ${"%.6f".format(status.lng)}",
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp
                )
                Text("Speed: ${"%.1f".format(status.speedMps)} m/s", fontSize = 14.sp)
                Text("Fixes emitted: ${status.emittedCount}", fontSize = 14.sp)
                Text("Re-asserts: ${status.reassertCount}", fontSize = 14.sp)
                Text("Real-fix leak seen: ${if (status.leakSeen) "YES ⚠" else "no"}", fontSize = 14.sp)
            }
        }

        error?.let {
            Text("⚠ $it", color = Color(0xFFDC2626), fontSize = 13.sp)
        }

        Spacer(Modifier.height(4.dp))

        Button(
            onClick = { permissionLauncher.launch(neededPermissions()); onStartDemo() },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Simulate demo drive") }

        OutlinedButton(onClick = { permissionLauncher.launch(neededPermissions()); onStartStatic() }, modifier = Modifier.fillMaxWidth()) {
            Text("Spoof static point")
        }
        OutlinedButton(
            onClick = {
                error = null
                permissionLauncher.launch(neededPermissions())
                onStartGoogle({ block -> scope.launch { block() } }, { error = it })
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Simulate Google route (needs API key)") }

        OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop") }

        Spacer(Modifier.height(8.dp))
        Text("Setup", fontSize = 16.sp, style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { permissionLauncher.launch(neededPermissions()) }, modifier = Modifier.fillMaxWidth()) {
            Text("Grant location & notification permissions")
        }
        OutlinedButton(onClick = onOpenDevSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Open Developer options (select mock app)")
        }
        OutlinedButton(onClick = onRequestBattery, modifier = Modifier.fillMaxWidth()) {
            Text("Ignore battery optimizations")
        }
    }
}

private fun neededPermissions(): Array<String> {
    val perms = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        perms.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    return perms.toTypedArray()
}
