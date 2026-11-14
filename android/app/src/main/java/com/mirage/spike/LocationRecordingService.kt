package com.mirage.spike

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.mirage.spike.engine.Fix
import com.mirage.spike.recording.*

/** Explicit user-started recording, independent of the simulation lifecycle. */
class LocationRecordingService : Service() {
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var callback: LocationCallback? = null
    private var stopping = false
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        if (intent.action == STOP) { finish(); return START_NOT_STICKY }
        if (RecordingController.recording || stopping) return START_NOT_STICKY
        val source = if (intent.action == REAL) TrackSource.REAL else if (intent.action == EMULATED) TrackSource.EMULATED else {
            stopSelf(); return START_NOT_STICKY
        }
        try {
            check(ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                "Precise location permission is required. Open Recordings and try again."
            }
            check(source != TrackSource.REAL || !MockState.status.value.let { it.running || it.starting }) {
                "Stop simulation before recording a real journey."
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Journey recording", NotificationManager.IMPORTANCE_LOW))
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(if (source == TrackSource.REAL) "Recording real journey" else "Recording emulated day")
                .setContentText(if (source == TrackSource.REAL) "Fresh GPS locations · tap Stop and save when finished" else "Captures emulated output across trips · stop when finished")
                .setContentIntent(PendingIntent.getActivity(this, 81, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .setOngoing(true)
                .addAction(0, "Stop and save", PendingIntent.getService(this, 82, Intent(this, LocationRecordingService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIFICATION, notification)
            RecordingController.start(this, source,
                onStarted = { if (source == TrackSource.REAL && !stopping) observeReal() },
                onFailed = { finish() })
        } catch (e: Exception) {
            RecordingController.reportError(e.message ?: "Could not start recording")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun observeReal() {
        val observer = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) {
                    val now = SystemClock.elapsedRealtime()
                    val mock = if (Build.VERSION.SDK_INT >= 31) location.isMock else location.isFromMockProvider
                    if (!RecordingPolicy.acceptReal(mock, MockState.status.value.let { it.running || it.starting }, location.elapsedRealtimeNanos / 1_000_000, now)) continue
                    RecordingController.sample(TrackSource.REAL,
                        Fix(location.latitude, location.longitude, if (location.hasSpeed()) location.speed else 0f,
                            if (location.hasBearing()) location.bearing else 0f,
                            if (location.hasAccuracy()) location.accuracy else 0f,
                            altitudeM = if (location.hasAltitude()) location.altitude else 0.0),
                        location.time, location.elapsedRealtimeNanos / 1_000_000)
                }
            }
        }
        callback = observer
        try {
            fused.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(1000L).setMaxUpdateDelayMillis(0L).build(), observer, Looper.getMainLooper())
                .addOnFailureListener {
                    RecordingController.reportError("Location recording could not continue. Check location permission and try again.")
                    finish()
                }
        } catch (e: Exception) {
            RecordingController.reportError("Location recording could not continue. Check location permission and try again.")
            finish()
        }
    }

    private fun finish() {
        if (stopping) return
        stopping = true
        callback?.let { runCatching { fused.removeLocationUpdates(it) } }
        callback = null
        RecordingController.finish { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    override fun onDestroy() {
        callback?.let { runCatching { fused.removeLocationUpdates(it) } }
        callback = null
        if (!stopping && RecordingController.recording) RecordingController.finish()
        super.onDestroy()
    }

    companion object {
        const val REAL = "com.mirage.spike.RECORD_REAL"
        const val EMULATED = "com.mirage.spike.RECORD_EMULATED"
        const val STOP = "com.mirage.spike.STOP_RECORDING"
        private const val CHANNEL = "mirage_recordings"
        private const val NOTIFICATION = 81
    }
}
