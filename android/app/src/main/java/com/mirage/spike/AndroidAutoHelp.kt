package com.mirage.spike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/** Read-only Wi-Fi status plus a user-initiated system settings action; never toggles Wi-Fi. */
@Composable
fun AndroidAutoHelp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    fun readWifi(): Boolean? = runCatching {
        (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled
    }.getOrNull()
    var wifiEnabled by remember { mutableStateOf(readWifi()) }
    var settingsUnavailable by remember { mutableStateOf(false) }
    DisposableEffect(appContext) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { wifiEnabled = readWifi() }
        }
        val registered = runCatching {
            ContextCompat.registerReceiver(appContext, receiver, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION), ContextCompat.RECEIVER_EXPORTED)
        }.isSuccess
        wifiEnabled = readWifi()
        onDispose { if (registered) runCatching { appContext.unregisterReceiver(receiver) } }
    }
    Column(modifier) {
        AndroidAutoHelpCard(wifiEnabled, onOpenWifiSettings = {
            val panelAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            val panelOpened = runCatching { context.startActivity(Intent(panelAction)) }.isSuccess
            settingsUnavailable = !panelOpened && runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }.isFailure
        })
        if (settingsUnavailable) Text("Open Wi-Fi from your phone's Settings app.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun AndroidAutoHelpCard(
    wifiEnabled: Boolean?,
    onOpenWifiSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Android Auto & Wi-Fi", style = MaterialTheme.typography.titleMedium)
        Text(when (wifiEnabled) {
            true -> "Wi-Fi is on"
            false -> "Wi-Fi is off"
            null -> "Wi-Fi status unavailable"
        }, style = MaterialTheme.typography.labelLarge)
        Text("If the map returns to your real location in the car, Mirage now checks the phone's Google location feed and retries when it loses confirmation.", style = MaterialTheme.typography.bodyMedium)
        Text("Wireless Android Auto uses Wi-Fi. Turning Wi-Fi off disconnects wireless Android Auto. Android does not let Mirage switch Wi-Fi off or keep it off.", style = MaterialTheme.typography.bodyMedium)
        Text("For repeated reconnects, use the car's connection settings to disconnect wireless Android Auto or disable automatic connection, if available. A USB connection may help isolate the cause, but still needs testing.", style = MaterialTheme.typography.bodyMedium)
        Text("A car can use a separate location source. Verified phone output does not confirm what the car's map displays. Check both while parked.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = onOpenWifiSettings, modifier = Modifier.fillMaxWidth().testTag("carWifiSettings")) {
            Text("Open Wi-Fi settings")
        }
    }
}
