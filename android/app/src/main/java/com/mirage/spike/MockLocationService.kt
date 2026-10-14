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
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.mirage.spike.engine.LiveSession
import com.mirage.spike.engine.DwellModel
import com.mirage.spike.engine.Fix
import com.mirage.spike.engine.LatLng
import com.mirage.spike.engine.PlaybackSource
import com.mirage.spike.audit.*
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
import kotlinx.coroutines.withContext
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

    // Provider operations and GMS callbacks are confined to the main thread. The
    // route flow remains on Default; only its short emission step crosses to Main.
    private var fusedMonitor: FusedLocationMonitor? = null
    private var monitorJob: Job? = null
    private var fusedCallback: LocationCallback? = null
    private var observerRegistered = false
    private val observerRequest = PendingLocationRequest()
    private var nextObserverAttemptNanos = 0L
    private val fusedWriteRequest = PendingLocationRequest()
    private var latestFix: Fix? = null
    private var sessionStartedNanos = 0L
    private val rawProvidersNeedingRecovery = mutableSetOf<String>()

    // running counters (single writer: the playback coroutine)
    private var count = 0L
    private var reasserts = 0L
    private var leakEver = false
    private var notifTick = 0

    override fun onCreate() {
        super.onCreate()
        AuditTrail.initialize(this)
    }

    private fun audit(control: AuditControl, outcome: AuditOutcome, reason: AuditReason = AuditReason.NONE,
        operation: Long? = null, action: AuditAction = AuditAction.SIMULATION) {
        AuditTrail.record(action, outcome,
            details = AuditDetails(reason = reason, source = AuditSource.SERVICE, control = control), operation = operation)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val operation = AuditTrail.newOperation()
        if (intent == null) {
            // Restarted by the system after a process death: nothing armed survives that,
            // so never spoof a stale point. Just make sure no mock is left behind and stop.
            // (No foreground promotion here — from the background that can itself throw.)
            generation++
            revertToReal("Simulation stopped — waiting for real location", blocked = false)
            audit(AuditControl.STOP, AuditOutcome.SUCCEEDED, AuditReason.NOT_READY, operation)
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_STOP) {
            audit(AuditControl.STOP, AuditOutcome.REQUESTED, operation = operation)
            TripRecovery.configure(this); TripRecovery.clearLive()
            stopEverything()
            audit(AuditControl.STOP, AuditOutcome.SUCCEEDED, operation = operation)
            return START_NOT_STICKY
        }
        // Live controls (notification actions, automation): no restart of the playback.
        when (intent.action) {
            ACTION_PAUSE -> { PlaybackSource.paused = true; refreshNotification(); audit(AuditControl.PAUSE, AuditOutcome.SUCCEEDED, operation = operation); return START_NOT_STICKY }
            ACTION_RESUME -> { PlaybackSource.paused = false; refreshNotification(); audit(AuditControl.RESUME, AuditOutcome.SUCCEEDED, operation = operation); return START_NOT_STICKY }
            ACTION_SKIP -> { PlaybackSource.requestSkip(); audit(AuditControl.SKIP, AuditOutcome.REQUESTED, operation = operation); return START_NOT_STICKY }
        }
        audit(AuditControl.START, AuditOutcome.REQUESTED, operation = operation)
        if (!hasLocationPermission()) {
            // A location-type foreground service cannot even be promoted without it (API 34).
            MockState.update {
                it.copy(running = false, starting = false, health = Health.RED,
                    message = "Location permission is required — grant it and tap Start again")
            }
            audit(AuditControl.START, AuditOutcome.BLOCKED, AuditReason.PERMISSION, operation, AuditAction.PERMISSION)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (!startAsForeground(operation)) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        acquireWakeLock()
        // A new Start (also while already running) replaces the playback.
        val gen = ++generation
        PlaybackSource.paused = false
        PlaybackSource.consumeSkip()
        loop?.cancel()
        loop = null
        stopMonitoring()
        latestFix = null
        rawProvidersNeedingRecovery.clear()
        fusedWriteRequest.invalidate()
        sessionStartedNanos = SystemClock.elapsedRealtimeNanos()
        fusedMonitor = FusedLocationMonitor(sessionStartedNanos)
        count = 0L
        reasserts = 0L
        leakEver = false
        notifTick = 0
        if (setupProviders(operation)) {
            startMonitoring(gen, operation)
            if (gen == generation) loop = scope.launch { runPlayback(gen, operation) }
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

    private fun setupProviders(operation: Long): Boolean {
        return try {
            for (p in providers) addOneProvider(p)
            MockState.update {
                it.copy(
                    mockAppSelected = true, blocked = false, starting = true, running = false,
                    lastFixMillis = 0L, lastFusedFixMillis = 0L,
                    health = Health.AMBER, message = "Starting…", stepLabel = "", label = PlaybackSource.label,
                    leakSeen = false, reassertCount = 0L,
                    progress = -1f, remainingSec = -1, legIndex = -1,
                )
            }
            true
        } catch (e: SecurityException) {
            audit(AuditControl.START, AuditOutcome.BLOCKED, AuditReason.PERMISSION, operation, AuditAction.PERMISSION)
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
                android.location.provider.ProviderProperties.POWER_USAGE_LOW, android.location.provider.ProviderProperties.ACCURACY_FINE
            )
        } catch (_: IllegalArgumentException) {
            // already added on a previous run
        }
        lm.setTestProviderEnabled(p, true)
    }

    /** Watch output independently of the route emitter, including AA connection changes. */
    private fun startMonitoring(gen: Int, operation: Long) {
        monitorJob = scope.launch(Dispatchers.Main.immediate) {
            while (isActive && gen == generation) {
                ensureFusedObserver(gen)
                if (!PlaybackSource.signal.dropped) {
                    if (latestFix != null && detectLeak()) {
                        leakEver = true
                        fusedMonitor?.writeFailed()
                        rawProvidersNeedingRecovery.addAll(providers)
                    }
                    for (provider in providers) {
                        if (provider in rawProvidersNeedingRecovery || !runCatching { lm.isProviderEnabled(provider) }.getOrDefault(true)) {
                            reasserts++
                            runCatching { addOneProvider(provider) }.onSuccess { rawProvidersNeedingRecovery.remove(provider) }
                        }
                    }
                    recoverFusedMode(gen, operation)
                    if (gen != generation) break
                    val issue = if (MODE_SWITCH.overdue() && fusedMonitor?.recoveryInFlight == true)
                        "Google location mode is not responding — output unconfirmed"
                    else fusedMonitor?.issue(SystemClock.elapsedRealtimeNanos())
                    issue?.let { message ->
                        MockState.update { if (gen != generation) it else it.copy(health = Health.AMBER, message = message) }
                    }
                }
                if (wakeLock?.isHeld != true) acquireWakeLock()
                delay(1_000L)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun ensureFusedObserver(gen: Int) {
        if (observerRegistered || gen != generation) return
        val now = SystemClock.elapsedRealtimeNanos()
        if (now < nextObserverAttemptNanos) return
        nextObserverAttemptNanos = now + FusedLocationMonitor.FRESH_NANOS
        val monitor = fusedMonitor ?: return
        val requestToken = observerRequest.begin(now) ?: return
        fusedCallback?.let { runCatching { flp.removeLocationUpdates(it) } }
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (gen != generation || monitor !== fusedMonitor || !observerRequest.isCurrent(requestToken)) return
                val receivedAt = SystemClock.elapsedRealtimeNanos()
                for (location in result.locations) {
                    monitor.observe(isMock(location), location.elapsedRealtimeNanos, receivedAt)
                }
                leakEver = leakEver || monitor.leakSeen
                monitor.issue(receivedAt)?.let { issue ->
                    MockState.update { if (gen != generation) it else it.copy(health = Health.AMBER, message = issue, leakSeen = leakEver) }
                }
            }
        }.also { fusedCallback = it }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(INTERVAL_MS)
            .setMaxUpdateDelayMillis(0L)
            .setMaxUpdateAgeMillis(0L)
            .build()
        try {
            flp.requestLocationUpdates(request, callback, Looper.getMainLooper()).addOnCompleteListener { task ->
                if (gen != generation || monitor !== fusedMonitor || !observerRequest.complete(requestToken)) {
                    // Registration can complete after Stop; remove it again in that case.
                    runCatching { flp.removeLocationUpdates(callback) }
                    return@addOnCompleteListener
                }
                observerRegistered = task.isSuccessful
            }
        } catch (_: Exception) {
            observerRequest.complete(requestToken)
            observerRegistered = false
        }
    }

    private fun requestFusedMode(enabled: Boolean, completed: (Boolean, Exception?) -> Unit = { _, _ -> }) {
        var failure: Exception? = null
        // Shared across service instances so a Stop and rapid restart remain ordered.
        MODE_SWITCH.request(enabled, apply = { mode, done ->
            try {
                flp.setMockMode(mode).addOnCompleteListener { task ->
                    failure = task.exception
                    done(task.isSuccessful)
                }
                MODE_TIMEOUTS.postDelayed({ MODE_SWITCH.checkTimeout() }, 5_000L)
            } catch (e: Exception) {
                failure = e
                done(false)
            }
        }, completed = { success -> completed(success, failure) })
    }

    private fun recoverFusedMode(gen: Int, operation: Long) {
        if (gen != generation) return
        val monitor = fusedMonitor ?: return
        if (!monitor.beginRecovery(SystemClock.elapsedRealtimeNanos())) return
        fusedWriteRequest.invalidate()
        reasserts++
        requestFusedMode(true) { success, failure ->
            if (gen != generation || monitor !== fusedMonitor) return@requestFusedMode
            monitor.recoveryCompleted(success, SystemClock.elapsedRealtimeNanos())
            if (success) {
                // Re-register a silent observer as well as restoring mock output.
                stopFusedObserver()
                ensureFusedObserver(gen)
                // Enabling mock mode clears the fused cache; fill it immediately.
                sendLatestFusedFix(gen, operation)
            } else if (failure != null && isDenied(failure)) {
                blockMockOutput(operation)
            } else {
                MockState.update { it.copy(health = Health.AMBER, message = "Google location feed unavailable — retrying") }
            }
        }
    }

    private fun sendLatestFusedFix(gen: Int, operation: Long) {
        val monitor = fusedMonitor ?: return
        if (gen != generation || !monitor.modeReady || PlaybackSource.signal.dropped) return
        val fix = latestFix ?: return
        val requestToken = fusedWriteRequest.begin(SystemClock.elapsedRealtimeNanos()) ?: return
        val location = toLocation(LocationManager.GPS_PROVIDER, fix)
        val sentAt = location.time
        try {
            flp.setMockLocation(location).addOnCompleteListener { task ->
                if (gen != generation || monitor !== fusedMonitor || !fusedWriteRequest.complete(requestToken)) return@addOnCompleteListener
                if (task.isSuccessful) {
                    monitor.accepted(location.elapsedRealtimeNanos, SystemClock.elapsedRealtimeNanos())
                    MockState.update { it.copy(lastFusedFixMillis = maxOf(it.lastFusedFixMillis, sentAt)) }
                } else {
                    monitor.writeFailed()
                    val failure = task.exception
                    if (failure != null && isDenied(failure)) blockMockOutput(operation)
                }
            }
        } catch (e: Exception) {
            fusedWriteRequest.complete(requestToken)
            monitor.writeFailed()
            if (isDenied(e)) blockMockOutput(operation)
        }
    }

    private fun blockMockOutput(operation: Long) {
        audit(AuditControl.START, AuditOutcome.BLOCKED, AuditReason.PERMISSION, operation, AuditAction.PERMISSION)
        revertToReal("Not the selected mock-location app", blocked = true)
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        stopFusedObserver()
        fusedWriteRequest.invalidate()
        fusedMonitor = null
    }

    private fun stopFusedObserver() {
        fusedCallback?.let { callback -> runCatching { flp.removeLocationUpdates(callback) } }
        fusedCallback = null
        observerRegistered = false
        observerRequest.invalidate()
        nextObserverAttemptNanos = 0L
    }

    // --- playback -----------------------------------------------------------

    private suspend fun runPlayback(gen: Int, operation: Long) {
        var src = PlaybackSource.current
        var last: Fix? = null
        var startRecorded = false
        suspend fun emitFix(fix: Fix) = withContext(Dispatchers.Main.immediate) {
            pushFix(fix, gen, operation)
            val status = MockState.status.value
            // Success needs both output channels and a fresh mock readback. Launching
            // playback or receiving only a write acknowledgement is not verification.
            if (!startRecorded && gen == generation && status.running && status.lastFixMillis > 0L &&
                status.lastFusedFixMillis > 0L && status.health == Health.GREEN) {
                startRecorded = true
                audit(AuditControl.START, AuditOutcome.SUCCEEDED, operation = operation)
            }
        }
        LiveSession.clear()
        while (currentCoroutineContext().isActive && gen == generation) {
            try {
                src?.collect { fix ->
                    if (PlaybackSource.paused && gen == generation) {
                        val anchor = (last ?: fix).copy(speedMps = 0f)
                        while (PlaybackSource.paused && gen == generation && currentCoroutineContext().isActive) {
                            emitFix(anchor)
                            delay(INTERVAL_MS)
                        }
                    }
                    if (gen == generation) { emitFix(fix); last = fix }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return
                audit(AuditControl.START, AuditOutcome.FAILED, auditReason(e), operation)
                MockState.update { it.copy(stepLabel = "Route failed: ${e.message}. Holding here; choose a new destination.") }
                Conversation.notice("Route failed: ${e.message}. Holding the last position.")
                // Never silently continue the queue after an execution failure.
                PlaybackSource.clearQueue()
            }
            if (gen != generation || !currentCoroutineContext().isActive) return
            val point = last?.let { LatLng(it.lat, it.lng) } ?: PlaybackSource.routePoints.firstOrNull()
            if (point == null) {
                audit(AuditControl.START, AuditOutcome.BLOCKED, AuditReason.NOT_READY, operation)
                withContext(Dispatchers.Main.immediate) {
                    if (gen == generation) revertToReal("Nothing to simulate — real location restored", false)
                }
                return
            }
            val anchor = Fix(point.lat, point.lng, 0f, 0f, 4f, altitudeM = last?.altitudeM ?: 12.0)
            LiveSession.holding(PlaybackSource.label, listOf(point))
            val dwell = DwellModel(anchor)
            var next = PlaybackSource.pollQueue()
            while (next == null && gen == generation && currentCoroutineContext().isActive) {
                emitFix(if (PlaybackSource.paused) anchor else dwell.next(0.2 * PlaybackSource.timeScale))
                delay(INTERVAL_MS)
                next = PlaybackSource.pollQueue()
            }
            if (gen != generation) return
            val queued = next ?: return
            PlaybackSource.routePoints = queued.points
            PlaybackSource.label = queued.label
            PlaybackSource.endPoint = queued.endPoint
            MockState.update { it.copy(label = queued.label, legIndex = -1, stepLabel = "Continuing: ${queued.label}") }
            src = queued.flow
        }
    }

    private fun pushFix(fixIn: Fix, gen: Int, operation: Long) {
        if (gen != generation) return
        // Simulated GPS quality: a dropout delivers nothing at all; otherwise jitter the
        // position and widen the reported accuracy to match the preset.
        val sig = PlaybackSource.signal
        if (sig.dropped) {
            MockState.update {
                if (gen != generation) it else it.copy(
                    running = true, starting = false, health = Health.AMBER, signalDropped = true, signalName = sig.name,
                    paused = PlaybackSource.paused, message = "No GPS signal (simulated dropout)",
                    queued = PlaybackSource.queueSize(),
                )
            }
            return
        }
        val fix = if (sig.jitterM > 0.0) {
            val p = com.mirage.spike.engine.Geo.offset(
                LatLng(fixIn.lat, fixIn.lng), ditherRnd.nextGaussian() * sig.jitterM, ditherRnd.nextGaussian() * sig.jitterM,
            )
            fixIn.copy(lat = p.lat, lng = p.lng, accuracyM = maxOf(fixIn.accuracyM, sig.accuracyM * (0.8f + ditherRnd.nextFloat() * 0.4f)))
        } else fixIn
        var acceptedProviderCount = 0
        val sentAt = System.currentTimeMillis()
        for (p in providers) {
            try {
                lm.setTestProviderLocation(p, toLocation(p, fix))
                acceptedProviderCount++
            } catch (_: Exception) {
                if (gen != generation) return
                rawProvidersNeedingRecovery.add(p)
            }
        }
        latestFix = fix
        sendLatestFusedFix(gen, operation)
        if (gen != generation) return
        count++
        val providerAccepted = acceptedProviderCount == providers.size
        val locationOff = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !runCatching { lm.isLocationEnabled }.getOrDefault(true)
        val fusedIssue = if (MODE_SWITCH.overdue() && fusedMonitor?.recoveryInFlight == true)
            "Google location mode is not responding — output unconfirmed"
            else if (fusedMonitor == null) "Waiting for location verification"
            else fusedMonitor?.issue(SystemClock.elapsedRealtimeNanos())
        leakEver = leakEver || fusedMonitor?.leakSeen == true
        val health = when {
            locationOff -> Health.RED
            !providerAccepted || fusedIssue != null -> Health.AMBER
            else -> Health.GREEN
        }
        val message = when {
            locationOff -> "Location is turned OFF on the phone"
            !providerAccepted -> "Location output rejected — checking providers"
            fusedIssue != null -> fusedIssue
            PlaybackSource.paused -> "Paused — holding here"
            else -> "Simulating"
        }
        MockState.update {
            if (gen != generation) it else it.copy(
                running = true, starting = false, mockAppSelected = true, blocked = false, health = health,
                lat = fix.lat, lng = fix.lng, speedMps = fix.speedMps, bearingDeg = fix.bearingDeg,
                progress = fix.progress, remainingSec = fix.remainingSec,
                emittedCount = count, lastFixMillis = if (providerAccepted) sentAt else it.lastFixMillis,
                reassertCount = reasserts, leakSeen = leakEver,
                message = message, label = PlaybackSource.label,
                paused = PlaybackSource.paused, signalDropped = false, signalName = sig.name,
                queued = PlaybackSource.queueSize(),
            )
        }
        if (++notifTick >= NOTIFY_EVERY) { notifTick = 0; if (gen == generation) refreshNotification() }
    }

    @SuppressLint("MissingPermission")
    private fun detectLeak(): Boolean {
        val now = SystemClock.elapsedRealtimeNanos()
        return providers.any { provider ->
            val last = runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
            last != null && last.elapsedRealtimeNanos >= sessionStartedNanos &&
                FusedLocationMonitor.fresh(last.elapsedRealtimeNanos, now) && !isMock(last)
        }
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
        val pauseAction = if (PlaybackSource.paused) ACTION_RESUME else ACTION_PAUSE
        val pause = PendingIntent.getService(
            this, 2,
            Intent(this, MockLocationService::class.java).setAction(pauseAction),
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
            .addAction(0, if (PlaybackSource.paused) "Resume" else "Pause", pause)
            .addAction(0, "Stop — real location", stop)
            .build()
    }

    /** Promote to foreground; false (with a visible reason) if the platform refuses. */
    private fun startAsForeground(operation: Long): Boolean {
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
            audit(AuditControl.START, AuditOutcome.FAILED, auditReason(e), operation)
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
        val outputIssue = locationOutputIssue(st)
        val text = when {
            outputIssue != null -> outputIssue
            PlaybackSource.paused -> "Paused · $step"
            mph > 0 -> "$step · $mph mph"
            else -> step
        }
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
        PlaybackSource.paused = false
        PlaybackSource.clearQueue()
        PlaybackSource.consumeSkip()
        Conversation.cancelPending()
        LiveSession.clear()
        loop?.cancel()
        loop = null
        revertToReal("Stopped — waiting for real location", blocked = false)
    }

    /** Hand location back to the real GPS: tear down every mock path. Idempotent. */
    private fun revertToReal(message: String, blocked: Boolean) {
        generation++
        val stoppedGeneration = generation
        loop?.cancel()
        loop = null
        stopMonitoring()
        latestFix = null
        fusedWriteRequest.invalidate()
        LiveSession.clear()
        for (p in providers) {
            runCatching { lm.setTestProviderEnabled(p, false) }
            runCatching { lm.removeTestProvider(p) }
        }
        requestFusedMode(false) { success, _ ->
            if (generation == stoppedGeneration && fusedMonitor == null) {
                if (success) nudgeRealFix()
                else MockState.update { if (it.blocked) it else it.copy(message = "Simulation stopped — Google location reset not confirmed") }
            }
        }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        MockState.update {
            it.copy(running = false, starting = false, blocked = blocked, health = Health.RED,
                message = message, stepLabel = "", progress = -1f, remainingSec = -1)
        }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelfResult(lastStartId)
    }

    /**
     * Exiting mock mode clears the fused cache. After it completes, request a fresh
     * real fix; consumers may still keep their own final simulated point meanwhile.
     */
    @SuppressLint("MissingPermission")
    private fun nudgeRealFix() {
        if (!hasLocationPermission()) return
        runCatching { flp.flushLocations() }
        runCatching {
            flp.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { loc ->
                if (loc != null && !isMock(loc)) {
                    // Only the coordinates, and only if no new simulation has started meanwhile.
                    MockState.update {
                        if (it.running || it.starting) it else it.copy(lat = loc.latitude, lng = loc.longitude,
                            message = if (it.blocked) it.message else "Stopped — real location restored")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        // Belt and braces: whatever path brought us here, leave no mock behind.
        generation++
        stopMonitoring()
        latestFix = null
        for (p in providers) {
            runCatching { lm.setTestProviderEnabled(p, false) }
            runCatching { lm.removeTestProvider(p) }
        }
        requestFusedMode(false) { success, _ ->
            val status = MockState.status.value
            if (success && !status.running && !status.starting) nudgeRealFix()
            else if (!success && !status.running && !status.starting && !status.blocked)
                MockState.update { it.copy(message = "Simulation stopped — Google location reset not confirmed") }
        }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        LiveSession.clear()
        scope.cancel()
        MockState.update { if (it.running || it.starting) it.copy(running = false, starting = false, health = Health.RED, message = "Stopped — waiting for real location") else it }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.mirage.spike.STOP"
        const val ACTION_PAUSE = "com.mirage.spike.PAUSE"
        const val ACTION_RESUME = "com.mirage.spike.RESUME"
        const val ACTION_SKIP = "com.mirage.spike.SKIP"
        private const val CHANNEL_ID = "mirage_mock"
        private const val NOTIF_ID = 42
        private const val INTERVAL_MS = 200L
        private val MODE_SWITCH = SerializedMockModeSwitch { SystemClock.elapsedRealtimeNanos() }
        private val MODE_TIMEOUTS = Handler(Looper.getMainLooper())
        /** Refresh the notification every ~5 s at 5 Hz. */
        private const val NOTIFY_EVERY = 25
    }
}
