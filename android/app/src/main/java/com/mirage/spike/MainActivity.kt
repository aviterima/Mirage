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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SpikeScreen(
                        onStart = { startMockService() },
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
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenDevSettings: () -> Unit,
    onRequestBattery: () -> Unit,
) {
    val status by MockState.status.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result handled implicitly; user can retry Start */ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Mirage — reliability spike", fontSize = 22.sp, style = MaterialTheme.typography.headlineSmall)
        Text(
            "Proves the mock location holds against Google Maps through screen-off and Doze. " +
                "Select this app as the mock-location app in Developer options first.",
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
                Text("Lat/Lng: ${"%.6f".format(status.lat)}, ${"%.6f".format(status.lng)}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text("Speed: ${"%.1f".format(status.speedMps)} m/s", fontSize = 14.sp)
                Text("Fixes emitted: ${status.emittedCount}", fontSize = 14.sp)
                Text("Re-asserts: ${status.reassertCount}", fontSize = 14.sp)
                Text("Real-fix leak seen: ${if (status.leakSeen) "YES ⚠" else "no"}", fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(4.dp))

        Button(
            onClick = {
                permissionLauncher.launch(neededPermissions())
                onStart()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Start spoofing") }

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
