package com.example.saillog

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvSchedule: TextView
    private lateinit var trackView: TrackView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnSchedule: Button

    private val handler = Handler(Looper.getMainLooper())
    private var afterPermissions: (() -> Unit)? = null

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    private val basePermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val next = afterPermissions
            afterPermissions = null
            if (hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) next?.invoke()
            else toast("Location permission is required")
        }

    private val bgPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            val next = afterPermissions
            afterPermissions = null
            if (!hasPerm(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                toast("\"Allow all the time\" not granted – a scheduled start may not get GPS")
            }
            next?.invoke()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvStats = findViewById(R.id.tvStats)
        tvSchedule = findViewById(R.id.tvSchedule)
        trackView = findViewById(R.id.trackView)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnSchedule = findViewById(R.id.btnSchedule)

        if (TrackRepo.points.isEmpty() && !TrackingService.running) TrackRepo.loadBackup(this)

        btnStart.setOnClickListener { ensureBasePermissions { startLogging() } }
        btnStop.setOnClickListener {
            startService(Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_STOP))
        }
        btnSchedule.setOnClickListener {
            if (Prefs.scheduledAt(this) > System.currentTimeMillis()) cancelSchedule() else pickDateTime()
        }
        findViewById<Button>(R.id.btnShare).setOnClickListener { shareLast() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestBatteryExemption() }
    }

    override fun onStart() {
        super.onStart()
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        super.onStop()
    }

    // ---------- UI ----------

    private fun refresh() {
        val logging = TrackingService.running
        val pts = TrackRepo.points
        tvStatus.text = if (logging) "● " + TrackRepo.status.uppercase() else TrackRepo.status

        val elapsed = when {
            logging -> System.currentTimeMillis() - TrackRepo.startTime
            pts.size >= 2 -> pts.last().time - pts.first().time
            else -> 0L
        }
        val s = elapsed / 1000
        tvStats.text = String.format(
            "%.1f kn now · %.1f kn max\n%.2f nm · %d:%02d:%02d · %d points",
            TrackRepo.currentSpeedKn, TrackRepo.maxSpeedKn, TrackRepo.distanceM / 1852.0,
            s / 3600, (s / 60) % 60, s % 60, pts.size
        )
        trackView.setPoints(pts)

        btnStart.isEnabled = !logging
        btnStop.isEnabled = logging

        val sched = Prefs.scheduledAt(this)
        val scheduled = sched > System.currentTimeMillis()
        btnSchedule.text = if (scheduled) "Cancel schedule" else "Schedule start…"
        tvSchedule.text = if (scheduled) {
            "Scheduled start: " + java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT
            ).format(Date(sched))
        } else ""
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // ---------- logging ----------

    private fun startLogging() {
        if (TrackingService.running) return
        ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java))
        if (!Prefs.batteryAsked(this)) {
            Prefs.setBatteryAsked(this)
            requestBatteryExemption()
        }
    }

    private fun ensureBasePermissions(next: () -> Unit) {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { !hasPerm(it) }
        if (missing.isEmpty()) next() else {
            afterPermissions = next
            basePermLauncher.launch(missing.toTypedArray())
        }
    }

    private fun ensureBackgroundLocation(next: () -> Unit) {
        if (hasPerm(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) { next(); return }
        AlertDialog.Builder(this)
            .setTitle("Background location")
            .setMessage("To start automatically while the screen is off, choose \"Allow all the time\" for location on the next screen.")
            .setPositiveButton("Continue") { _, _ ->
                afterPermissions = next
                bgPermLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            .setNegativeButton("Skip") { _, _ -> next() }
            .show()
    }

    // ---------- scheduling ----------

    private fun pickDateTime() {
        ensureBasePermissions {
            val now = Calendar.getInstance()
            DatePickerDialog(this, { _, y, m, d ->
                TimePickerDialog(this, { _, h, min ->
                    val c = Calendar.getInstance().apply {
                        set(y, m, d, h, min, 0); set(Calendar.MILLISECOND, 0)
                    }
                    if (c.timeInMillis <= System.currentTimeMillis()) toast("Pick a time in the future")
                    else ensureBackgroundLocation { scheduleAt(c.timeInMillis) }
                }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE),
                    android.text.format.DateFormat.is24HourFormat(this)).show()
            }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
        }
    }

    private fun alarmIntent() = PendingIntent.getBroadcast(
        this, 1, Intent(this, StartReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun scheduleAt(ms: Long) {
        val am = getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            toast("Allow \"Alarms & reminders\" for SailLog, then schedule again")
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
            )
            return
        }
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, alarmIntent())
        Prefs.setScheduledAt(this, ms)
        if (!Prefs.batteryAsked(this)) {
            Prefs.setBatteryAsked(this)
            requestBatteryExemption()
        }
        refresh()
    }

    private fun cancelSchedule() {
        getSystemService(AlarmManager::class.java).cancel(alarmIntent())
        Prefs.setScheduledAt(this, 0L)
        refresh()
    }

    // ---------- battery / share ----------

    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Battery optimisation is already disabled for SailLog")
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun shareLast() {
        val uri = Prefs.lastGpxUri(this)?.let { Uri.parse(it) }
        if (uri == null) { toast("No GPX saved yet"); return }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share GPX"))
    }
}
