package com.mirage.spike

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MirageTheme {
                Surface(Modifier.fillMaxSize()) {
                    AppRoot(
                        onStartService = ::startMockService,
                        onStopService = ::stopMockService,
                        onOpenDevSettings = ::openDeveloperSettings,
                        onRequestBattery = ::requestIgnoreBatteryOptimizations,
                        setupChecks = ::setupChecks,
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

    /** Live state of the one-time setup, so the Setup dialog can show ✓ / ✗ per step. */
    private fun setupChecks(): SetupChecks = SetupChecks(
        location = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED,
        notifications = NotificationManagerCompat.from(this).areNotificationsEnabled(),
        battery = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName),
    )
}

@Composable
private fun AppRoot(
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onOpenDevSettings: () -> Unit,
    onRequestBattery: () -> Unit,
    setupChecks: () -> SetupChecks,
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(neededPermissions())
    }

    MapScreen(
        onStartService = onStartService,
        onStopService = onStopService,
        onOpenDevSettings = onOpenDevSettings,
        onRequestBattery = onRequestBattery,
        onRequestPermissions = { permissionLauncher.launch(neededPermissions()) },
        setupChecks = setupChecks,
    )
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
