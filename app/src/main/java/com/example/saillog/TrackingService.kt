package com.example.saillog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class TrackingService : Service(), LocationListener {

    companion object {
        const val ACTION_STOP = "com.example.saillog.STOP"

        /** Consecutive fixes above the auto-stop speed before logging stops (filters GPS spikes). */
        const val AUTO_STOP_FIXES = 3
        const val MAX_ACCURACY_M = 50f

        private const val CH_TRACK = "tracking"
        private const val CH_EVENTS = "events"
        private const val NOTIF_ID = 1
        private const val EVENT_ID = 2

        @Volatile
        var running = false
    }

    private lateinit var lm: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStored = 0L
    private var fastCount = 0
    private var lastNotif = 0L
    private var intervalMs = 1000L
    private var autoStopKn = 15.0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_TRACK, "Recording", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_EVENTS, "Log events", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (running) stopLogging("Stopped manually") else {
                Prefs.setLogging(this, false)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        // Always satisfy the foreground-service contract first.
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification("Waiting for GPS fix…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        if (running) return START_STICKY

        // null intent = system restarted us after the process was killed
        val resume = intent == null
        if (resume && !Prefs.isLogging(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startLogging(resume)
        return START_STICKY
    }

    private fun startLogging(resume: Boolean) {
        running = true
        Prefs.setLogging(this, true)
        if (resume) TrackRepo.loadBackup(this) else TrackRepo.reset(this)
        TrackRepo.status = "Waiting for GPS fix…"
        fastCount = 0
        intervalMs = Prefs.logIntervalSec(this) * 1000L
        autoStopKn = Prefs.autoStopKn(this)
        lastStored = TrackRepo.points.lastOrNull()?.time ?: 0L

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SailLog:track").apply {
            acquire(72L * 60 * 60 * 1000)
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        } catch (e: SecurityException) {
            stopLogging("Location permission missing")
        }
    }

    override fun onLocationChanged(loc: Location) {
        if (!running) return
        if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_M) return

        val kn = if (loc.hasSpeed()) loc.speed * MS_TO_KN else 0.0
        TrackRepo.currentSpeedKn = kn
        TrackRepo.status = "Logging"

        if (kn > autoStopKn) {
            fastCount++
            if (fastCount >= AUTO_STOP_FIXES) {
                stopLogging("Auto-stopped: speed over ${fmtKn(autoStopKn)} kn")
            }
            return // don't store suspicious fixes
        }
        fastCount = 0

        if (loc.time - lastStored >= intervalMs - 250) {
            lastStored = loc.time
            TrackRepo.add(this, TrackPoint(loc.time, loc.latitude, loc.longitude, kn))
        }

        val now = System.currentTimeMillis()
        if (now - lastNotif > 5000) {
            lastNotif = now
            val text = String.format(
                "%.1f kn · %.2f nm · %d points", kn, TrackRepo.distanceM / 1852.0, TrackRepo.points.size
            )
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun stopLogging(reason: String) {
        running = false
        try { lm.removeUpdates(this) } catch (_: SecurityException) { }
        Prefs.setLogging(this, false)

        val name = GpxExporter.export(this, TrackRepo.points)
        val result = when {
            name != null -> "$reason — saved Documents/SailLog/$name"
            TrackRepo.points.isEmpty() -> "$reason — no data recorded"
            else -> "$reason — GPX export FAILED (backup kept in app storage)"
        }
        TrackRepo.status = result
        TrackRepo.currentSpeedKn = 0.0

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val eventBuilder = NotificationCompat.Builder(this, CH_EVENTS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("SailLog stopped")
            .setContentText(result)
            .setStyle(NotificationCompat.BigTextStyle().bigText(result))
            .setContentIntent(open)
            .setAutoCancel(true)
        if (name != null) {
            Prefs.lastGpxUri(this)?.let { u ->
                val share = PendingIntent.getActivity(
                    this, 2, Sharing.intent(listOf(Uri.parse(u))),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                eventBuilder.addAction(0, "Share GPX", share)
            }
        }
        getSystemService(NotificationManager::class.java).notify(EVENT_ID, eventBuilder.build())

        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        if (running) { // killed by the system, not stopped by us – keep Prefs.logging for the sticky restart
            running = false
            try { lm.removeUpdates(this) } catch (_: SecurityException) { }
        }
        releaseWakeLock()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_TRACK)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("SailLog recording")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Stop & save", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    // Required explicitly: these are abstract on API 29.
    override fun onProviderEnabled(provider: String) {
        if (running) TrackRepo.status = "Waiting for GPS fix…"
    }

    override fun onProviderDisabled(provider: String) {
        if (running) TrackRepo.status = "GPS is switched off!"
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }
}
