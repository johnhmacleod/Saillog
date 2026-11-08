package com.example.saillog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Fired by AlarmManager at the scheduled time; starts the logging service. */
class StartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs.setScheduledAt(context, 0L)
        if (TrackingService.running) return
        ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
    }
}
