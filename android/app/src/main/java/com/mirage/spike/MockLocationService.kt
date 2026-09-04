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
import kotlinx.coroutines.currentCoroutineContext
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
 *
 * Concurrency model: every Start bumps [generation]; the playback loop captures its
 * generation and every side effect is skipped once a newer Start or a Stop has moved on.
 * That is what keeps a straggling tick from re-adding providers or flipping the status
 * back to "running" after a Stop.
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

    /** Bumped on every Start and Stop; a loop only acts while it matches. */
    @Volatile private var generation = 0
    private var lastStartId = -1

    // Fused-provider mock channel state (set asynchronously by GMS).
    @Volatile private var flpMockReady = false
    @Volatile private var flpFailures = 0

    // running counters (single writer: the playback coroutine)
    private var count = 0L
    private var reasserts = 0L
    private var leakEver = false
    private var tick = 0
    private var notifTick = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent == null) {
            // Restarted by the system after a process death: nothing armed survives that,
            // so never spoof a stale point. Just make sure no mock is left behind and stop.
            // (No foreground promotion here — from the background that can itself throw.)
            generation++
            revertToReal("Stopped — real location restored", blocked = false)
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        if (!hasLocationPermission()) {
            // A location-type foreground service cannot even be promoted without it (API 34).
            MockState.update {
                it.copy(running = false, starting = false, health = Health.RED,
                    message = "Location permission is required — grant it and tap Start again")
            }
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (!startAsForeground()) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        acquireWakeLock()
        // A new Start (also while already running) replaces the playback.
        val gen = ++generation
        loop?.cancel()
        loop = null
        if (setupProviders()) {
            loop = scope.launch { runPlayback(gen) }
        } else {
            revertToReal("Not the selected mock-location app", blocked = true)
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // --- provider setup -----------------------------------------------------

    private fun setupProviders(): Boolean {
        return try {
            for (p in providers) addOneProvider(p)
            // Asynchronous on the GMS side: do not block on it, but do not feed the fused
            // provider until it has confirmed, and surface a refusal as "blocked".
            flpMockReady = false
            flpFailures = 0
            val gen = generation
            flp.setMockMode(true)
                .addOnSuccessListener { if (gen == generation) flpMockReady = true }
                .addOnFailureListener { e ->
                    if (gen != generation) return@addOnFailureListener
                    if (isDenied(e)) {
                        generation++
                        loop?.cancel(); loop = null
                        revertToReal("Not the selected mock-location app", blocked = true)
                    } else {
                        MockState.update { it.copy(health = Health.AMBER, message = "Google feed: ${e.message ?: "mock mode failed"}") }
                    }
                }
            MockState.update {
                it.copy(
                    mockAppSelected = true, blocked = false, starting = true, running = false,
                    health = Health.GREEN, message = "Starting…", stepLabel = "", label = PlaybackSource.label,
                    progress = -1f, remainingSec = -1,
                )
            }
            true
        } catch (e: SecurityException) {
            MockState.update {
                it.copy(mockAppSelected = false, blocked = true, running = false, starting = false,
                    health = Health.RED, message = "Not the selected mock-location app")
            }
            false
        }
    }

    private fun isDenied(e: Throwable): Boolean =
        e is SecurityException || e.cause is SecurityException ||
            (e.message?.contains("SecurityException", ignoreCase = true) == true) ||
            (e.message?.contains("mock", ignoreCase = true) == true && e.message?.contains("permission", ignoreCase = true) == true)

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

    private suspend fun runPlayback(gen: Int) {
        val src = PlaybackSource.current
        var last: Fix? = null
        if (src != null) {
            // Routed drive / flight / itinerary: play it to the end.
            try {
                src.collect { fix -> if (gen == generation) { pushFix(fix, gen); last = fix } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // stream error — fall through and hold wherever we got to
            }
        }
        if (gen != generation || !currentCoroutineContext().isActive) return
        // Hold the endpoint (or the chosen static point) like a person at a place until the
        // user taps Stop. Arriving is not the end of the simulation; Stop is.
        val anchorPt = last?.let { LatLng(it.lat, it.lng) } ?: PlaybackSource.routePoints.firstOrNull()
        if (anchorPt == null) {
            // Nothing to play and nothing to hold: never invent a point — hand back to real GPS.
            if (gen == generation) revertToReal("Nothing to simulate — real location restored", blocked = false)
            return
        }
        val hold = Fix(anchorPt.lat, anchorPt.lng, 0f, 0f, 4f, altitudeM = last?.altitudeM ?: 12.0)
        MockState.update {
            it.copy(stepLabel = if (last != null) "Arrived — holding position until Stop" else "Holding point until Stop")
        }
        refreshNotification()
        val dwell = DwellModel(hold, 12.0, ditherRnd)
        while (currentCoroutineContext().isActive && gen == generation) {
            pushFix(dwell.next(INTERVAL_MS / 1000.0 * PlaybackSource.timeScale), gen)
            delay(INTERVAL_MS)
        }
    }

    private fun pushFix(fix: Fix, gen: Int) {
        if (gen != generation) return
        for (p in providers) {
            try {
                lm.setTestProviderLocation(p, toLocation(p, fix))
            } catch (_: Exception) {
                if (gen != generation) return
                reasserts++
                runCatching { addOneProvider(p) }
            }
        }
        if (flpMockReady) {
            runCatching {
                flp.setMockLocation(toLocation(LocationManager.GPS_PROVIDER, fix))
                    .addOnSuccessListener { flpFailures = 0 }
                    .addOnFailureListener { e ->
                        if (gen != generation) return@addOnFailureListener
                        flpFailures++
                        if (flpFailures == 5) {
                            // Google Maps reads this channel: say so instead of showing GREEN.
                            MockState.update { it.copy(health = Health.AMBER, message = "Google feed failing: ${e.message ?: "rejected"}") }
                            flpMockReady = false
                            flp.setMockMode(true).addOnSuccessListener { if (gen == generation) { flpMockReady = true; flpFailures = 0 } }
                        }
                    }
            }
        }
        count++

        var leakNow = false
        var locationOff = false
        if (++tick >= WATCHDOG_EVERY) {
            tick = 0
            locationOff = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !runCatching { lm.isLocationEnabled }.getOrDefault(true)
            if (!locationOff) {
                for (p in providers) {
                    if (!runCatching { lm.isProviderEnabled(p) }.getOrDefault(true)) {
                        if (gen != generation) return
                        reasserts++
                        runCatching { addOneProvider(p) }
                    }
                }
            }
            if (wakeLock?.isHeld != true) acquireWakeLock()
            leakNow = detectLeak()
            if (leakNow) leakEver = true
        }

        val health = when {
            locationOff -> Health.RED
            leakNow || flpFailures >= 5 -> Health.AMBER
            else -> Health.GREEN
        }
        val message = when {
            locationOff -> "Location is turned OFF on the phone"
            flpFailures >= 5 -> "Google feed failing — re-asserting"
            leakEver -> "Re-asserting (leak seen)"
            else -> "Simulating"
        }
        MockState.update {
            if (gen != generation) it else it.copy(
                running = true, starting = false, mockAppSelected = true, blocked = false, health = health,
                lat = fix.lat, lng = fix.lng, speedMps = fix.speedMps, bearingDeg = fix.bearingDeg,
                progress = fix.progress, remainingSec = fix.remainingSec,
                emittedCount = count, lastFixMillis = System.currentTimeMillis(),
                reassertCount = reasserts, leakSeen = leakEver,
                message = message, label = PlaybackSource.label,
            )
        }
        if (++notifTick >= NOTIFY_EVERY) { notifTick = 0; if (gen == generation) refreshNotification() }
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock = true
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

    /** Promote to foreground; false (with a visible reason) if the platform refuses. */
    private fun startAsForeground(): Boolean {
        return try {
            ensureChannel()
            val notification = buildNotification("Starting…")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            true
        } catch (e: Exception) {
            MockState.update {
                it.copy(running = false, starting = false, health = Health.RED,
                    message = "Cannot start: ${e.message ?: e::class.simpleName}")
            }
            false
        }
    }

    /** Keep the persistent notification telling the truth: step, speed, health. */
    private fun refreshNotification() {
        val st = MockState.status.value
        if (!st.running && !st.starting) return
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
        generation++            // every in-flight tick becomes a no-op from here on
        loop?.cancel()
        loop = null
        revertToReal("Stopped — real location restored", blocked = false)
    }

    /** Hand location back to the real GPS: tear down every mock path. Idempotent. */
    private fun revertToReal(message: String, blocked: Boolean) {
        flpMockReady = false
        for (p in providers) {
            runCatching { lm.setTestProviderEnabled(p, false) }
            runCatching { lm.removeTestProvider(p) }
        }
        runCatching { flp.setMockMode(false) }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        MockState.update {
            it.copy(running = false, starting = false, blocked = blocked, health = Health.RED,
                message = message, stepLabel = "", progress = -1f, remainingSec = -1)
        }
        nudgeRealFix()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelfResult(lastStartId)
    }

    /**
     * Turning mock mode off does not discard the fused provider's cached last location —
     * every app (Google Maps included) keeps seeing the final spoofed point until a new
     * real fix is computed. Ask for one right now so the hand-back is immediate.
     */
    @SuppressLint("MissingPermission")
    private fun nudgeRealFix() {
        if (!hasLocationPermission()) return
        runCatching { flp.flushLocations() }
        runCatching {
            flp.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { loc ->
                if (loc != null && !isMock(loc)) {
                    // Only the coordinates, and only if no new simulation has started meanwhile.
                    MockState.update { if (it.running || it.starting) it else it.copy(lat = loc.latitude, lng = loc.longitude) }
                }
            }
        }
    }

    override fun onDestroy() {
        // Belt and braces: whatever path brought us here, leave no mock behind.
        generation++
        for (p in providers) {
            runCatching { lm.setTestProviderEnabled(p, false) }
            runCatching { lm.removeTestProvider(p) }
        }
        runCatching { flp.setMockMode(false) }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        scope.cancel()
        MockState.update { if (it.running || it.starting) it.copy(running = false, starting = false, health = Health.RED, message = "Stopped — real location restored") else it }
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
