package com.mirage.spike

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.mirage.spike.engine.DwellModel
import com.mirage.spike.engine.Fix
import com.mirage.spike.engine.LatLng
import com.mirage.spike.engine.PlaybackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Random

/**
 * Mirage's location-emitting foreground service.
 *
 * Feeds a mock fix to BOTH the LocationManager test providers (gps + network) AND the
 * Fused Location Provider (which Google Maps reads), at a fixed cadence. It plays the
 * [PlaybackSource] fix stream (route / flight / itinerary) when one is armed, then holds
 * the final point like a person at a place until Stop. A wake lock + foreground service
 * keep it alive through screen-off / Doze; a watchdog re-asserts on provider drop and
 * flags any real-fix leakage. Only Stop (button or notification) hands location back
 * to the real GPS. See SPEC §5.
 */
class MockLocationService : Service() {

    private val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    private val lm by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }
    private val flp: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val ditherRnd = Random()

    // running counters (single writer: the playback coroutine)
    private var count = 0L
    private var reasserts = 0L
    private var leakEver = false
    private var tick = 0
    private var notifTick = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // Sticky restart after the process was killed: no armed route survives a
            // process death, so never spoof a stale/default point — hand back to real GPS.
            startAsForeground()
            revertToReal("Stopped — real location restored")
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        startAsForeground()
        acquireWakeLock()
        // A new Start while already running replaces the playback (new route, jump, ...).
        loop?.cancel()
        loop = null
        if (setupProviders()) {
            loop = scope.launch { runPlayback() }
        } else {
            stopEverything()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    // --- provider setup -----------------------------------------------------

    private fun setupProviders(): Boolean {
        return try {
            for (p in providers) addOneProvider(p)
            flp.setMockMode(true)
            MockState.update {
                it.copy(
                    mockAppSelected = true, running = true, health = Health.GREEN,
                    message = "Simulating", stepLabel = "", label = PlaybackSource.label,
                    progress = -1f, remainingSec = -1,
                )
            }
            true
        } catch (e: SecurityException) {
            MockState.update {
                it.copy(
                    mockAppSelected = false, running = false, health = Health.RED,
                    message = "Not the selected mock-location app"
                )
            }
            false
        }
    }

    private fun addOneProvider(p: String) {
        try {
            @Suppress("DEPRECATION")
            lm.addTestProvider(
                p, false, true, false, false, true, true, true,
                Criteria.POWER_LOW, Criteria.ACCURACY_FINE
            )
        } catch (_: IllegalArgumentException) {
            // already added on a previous run
        }
        lm.setTestProviderEnabled(p, true)
    }

    // --- playback -----------------------------------------------------------

    private suspend fun runPlayback() {
        val src = PlaybackSource.current
        var last: Fix? = null
        if (src != null) {
            // Routed drive / flight / itinerary: play it to the end.
            try {
                src.collect { fix -> pushFix(fix); last = fix }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // stream error — fall through and hold wherever we got to
            }
        }
        // Hold the endpoint (or the chosen static point) like a person at a place until the
        // user taps Stop. Arriving is not the end of the simulation; Stop is.
        val anchorPt = last?.let { LatLng(it.lat, it.lng) } ?: PlaybackSource.routePoints.firstOrNull()
        val hold = anchorPt?.let { Fix(it.lat, it.lng, 0f, 0f, 4f) } ?: Fix(START_LAT, START_LNG, 0f, 0f, 4f)
        MockState.update {
            it.copy(stepLabel = if (last != null) "Arrived — holding position until Stop" else "Holding point until Stop")
        }
        refreshNotification()
        val dwell = DwellModel(hold, 12.0, ditherRnd)
        while (scope.isActive) {
            pushFix(dwell.next(INTERVAL_MS / 1000.0))
            delay(INTERVAL_MS)
        }
    }

    private fun pushFix(fix: Fix) {
        for (p in providers) {
            try {
                lm.setTestProviderLocation(p, toLocation(p, fix))
            } catch (_: Exception) {
                reasserts++
                runCatching { addOneProvider(p) }
            }
        }
        runCatching { flp.setMockLocation(toLocation(LocationManager.GPS_PROVIDER, fix)) }
        count++

        var leakNow = false
        if (++tick >= WATCHDOG_EVERY) {
            tick = 0
            for (p in providers) {
                if (!runCatching { lm.isProviderEnabled(p) }.getOrDefault(true)) {
                    reasserts++
                    runCatching { addOneProvider(p) }
                }
            }
            if (wakeLock?.isHeld != true) acquireWakeLock()
            leakNow = detectLeak()
            if (leakNow) leakEver = true
        }

        val health = if (leakNow) Health.AMBER else Health.GREEN
        MockState.update {
            it.copy(
                running = true, mockAppSelected = true, health = health,
                lat = fix.lat, lng = fix.lng, speedMps = fix.speedMps, bearingDeg = fix.bearingDeg,
                progress = fix.progress, remainingSec = fix.remainingSec,
                emittedCount = count, reassertCount = reasserts, leakSeen = leakEver,
                message = if (leakEver) "Re-asserting (leak seen)" else "Simulating",
                label = PlaybackSource.label,
            )
        }
        if (++notifTick >= NOTIFY_EVERY) { notifTick = 0; refreshNotification() }
    }

    private fun detectLeak(): Boolean {
        val last = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
            ?: return false
        return !isMock(last)
    }

    private fun isMock(l: Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) l.isMock
        else @Suppress("DEPRECATION") l.isFromMockProvider

    private fun toLocation(provider: String, fix: Fix): Location =
        Location(provider).apply {
            latitude = fix.lat
            longitude = fix.lng
            accuracy = fix.accuracyM
            altitude = fix.altitudeM
            speed = fix.speedMps
            bearing = fix.bearingDeg
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                bearingAccuracyDegrees = 1.0f
                speedAccuracyMetersPerSecond = 0.5f
                verticalAccuracyMeters = 3.0f
            }
        }

    // --- lifecycle / foreground --------------------------------------------

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Simulation status", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Mirage is simulating your location"
                }
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val flagsPi = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flagsPi,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
            flagsPi,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mirage — simulating location")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_mirage)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "Stop — real location", stop)
            .build()
    }

    private fun startAsForeground() {
        ensureChannel()
        val notification = buildNotification("Starting…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    /** Keep the persistent notification telling the truth: step, speed, health. */
    private fun refreshNotification() {
        val st = MockState.status.value
        if (!st.running) return
        val mph = (st.speedMps / 0.44704f).toInt()
        val step = st.stepLabel.ifBlank { st.label.ifBlank { "Simulating" } }
        val text = if (mph > 0) "$step · $mph mph" else step
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text)) }
    }

    @Suppress("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mirage:mock").also {
            it.setReferenceCounted(false)
            // No timeout: an itinerary can run all day. Released in revertToReal/onDestroy.
            it.acquire()
        }
    }

    private fun stopEverything() {
        loop?.cancel()
        loop = null
        revertToReal("Stopped — real location restored")
    }

    /** Hand location back to the real GPS: tear down every mock path. Idempotent. */
    private fun revertToReal(message: String) {
        for (p in providers) {
            runCatching { lm.setTestProviderEnabled(p, false) }
            runCatching { lm.removeTestProvider(p) }
        }
        runCatching { flp.setMockMode(false) }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        MockState.update {
            it.copy(running = false, health = Health.RED, message = message, stepLabel = "", progress = -1f, remainingSec = -1)
        }
        nudgeRealFix()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    /**
     * Turning mock mode off does not discard the fused provider's cached last location —
     * every app (Google Maps included) keeps seeing the final spoofed point until a new
     * real fix is computed. Ask for one right now so the hand-back is immediate.
     */
    @SuppressLint("MissingPermission")
    private fun nudgeRealFix() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        runCatching { flp.flushLocations() }
        runCatching {
            flp.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { loc ->
                if (loc != null && !isMock(loc)) {
                    MockState.update { it.copy(lat = loc.latitude, lng = loc.longitude, message = "Stopped — real location restored") }
                }
            }
        }
    }

    override fun onDestroy() {
        // Belt and braces: whatever path brought us here, leave no mock behind.
        for (p in providers) {
            runCatching { lm.setTestProviderEnabled(p, false) }
            runCatching { lm.removeTestProvider(p) }
        }
        runCatching { flp.setMockMode(false) }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.mirage.spike.STOP"
        private const val CHANNEL_ID = "mirage_mock"
        private const val NOTIF_ID = 42
        private const val INTERVAL_MS = 200L
        private const val WATCHDOG_EVERY = 10
        /** Refresh the notification every ~5 s at 5 Hz. */
        private const val NOTIFY_EVERY = 25
    }
}
