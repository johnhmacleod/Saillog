package com.example.saillog

import android.content.ContentValues
import android.content.Context
import android.location.Location
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

const val MS_TO_KN = 1.943844

/** 15.0 -> "15", 12.5 -> "12.5" */
fun fmtKn(v: Double): String =
    if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.US, "%.1f", v)

data class TrackPoint(val time: Long, val lat: Double, val lon: Double, val speedKn: Double)

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("saillog", Context.MODE_PRIVATE)
    fun isLogging(c: Context) = sp(c).getBoolean("logging", false)
    fun setLogging(c: Context, v: Boolean) = sp(c).edit().putBoolean("logging", v).apply()
    fun scheduledAt(c: Context) = sp(c).getLong("sched", 0L)
    fun setScheduledAt(c: Context, v: Long) = sp(c).edit().putLong("sched", v).apply()
    fun lastGpxUri(c: Context): String? = sp(c).getString("gpx_uri", null)
    fun setLastGpx(c: Context, uri: String) = sp(c).edit().putString("gpx_uri", uri).apply()
    fun batteryAsked(c: Context) = sp(c).getBoolean("battery_asked", false)
    fun setBatteryAsked(c: Context) = sp(c).edit().putBoolean("battery_asked", true).apply()

    // ---- user settings ----
    fun boatName(c: Context): String = sp(c).getString("boat", "") ?: ""
    fun logIntervalSec(c: Context): Int = sp(c).getInt("interval", 1)
    fun autoStopKn(c: Context): Double = sp(c).getFloat("autostop", 15f).toDouble()
    fun saveSettings(c: Context, boat: String, intervalSec: Int, autoStopKn: Float) =
        sp(c).edit().putString("boat", boat).putInt("interval", intervalSec)
            .putFloat("autostop", autoStopKn).apply()
}

/** Shared in-process state. Only touched from the main thread. */
object TrackRepo {
    val points = ArrayList<TrackPoint>()
    var status = "Idle"
    var currentSpeedKn = 0.0
    var maxSpeedKn = 0.0
    var distanceM = 0.0
    var startTime = 0L

    private fun file(c: Context) = File(c.filesDir, "current_track.csv")

    fun reset(c: Context) {
        points.clear()
        distanceM = 0.0; maxSpeedKn = 0.0; currentSpeedKn = 0.0
        startTime = System.currentTimeMillis()
        file(c).delete()
    }

    private fun addInternal(p: TrackPoint) {
        points.lastOrNull()?.let {
            val r = FloatArray(1)
            Location.distanceBetween(it.lat, it.lon, p.lat, p.lon, r)
            distanceM += r[0]
        }
        points.add(p)
        maxSpeedKn = max(maxSpeedKn, p.speedKn)
    }

    /** Adds a point and appends it to a crash-safe CSV backup. */
    fun add(c: Context, p: TrackPoint) {
        addInternal(p)
        file(c).appendText("${p.time},${p.lat},${p.lon},${p.speedKn}\n")
    }

    /** Reloads the last session (used after the process is restarted). */
    fun loadBackup(c: Context) {
        points.clear(); distanceM = 0.0; maxSpeedKn = 0.0
        val f = file(c)
        if (f.exists()) {
            f.forEachLine { line ->
                val s = line.split(",")
                if (s.size == 4) {
                    try {
                        addInternal(TrackPoint(s[0].toLong(), s[1].toDouble(), s[2].toDouble(), s[3].toDouble()))
                    } catch (_: NumberFormatException) { }
                }
            }
        }
        startTime = points.firstOrNull()?.time ?: System.currentTimeMillis()
    }
}

object GpxExporter {
    /** Writes Documents/SailLog/<boat>_<start>.gpx and returns its display name, or null on failure. */
    fun export(ctx: Context, pts: List<TrackPoint>): String? {
        if (pts.isEmpty()) return null
        return try {
            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(pts.first().time))
            val boat = Prefs.boatName(ctx).trim()
            val safe = boat.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").trim('_').ifEmpty { "saillog" }
            val name = "${safe}_$stamp.gpx"
            val title = boat.ifEmpty { "SailLog" }
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            sb.append("<gpx version=\"1.1\" creator=\"SailLog\" ")
            sb.append("xmlns=\"http://www.topografix.com/GPX/1/1\" ")
            sb.append("xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n")
            sb.append("<metadata><time>${iso.format(Date(pts.first().time))}</time></metadata>\n")
            sb.append("<trk><name>$title $stamp</name><trkseg>\n")
            for (p in pts) {
                sb.append(
                    String.format(
                        Locale.US,
                        "<trkpt lat=\"%.6f\" lon=\"%.6f\"><time>%s</time><extensions><gpxtpx:TrackPointExtension>" +
                            "<gpxtpx:speed>%.2f</gpxtpx:speed></gpxtpx:TrackPointExtension></extensions></trkpt>\n",
                        p.lat, p.lon, iso.format(Date(p.time)), p.speedKn / MS_TO_KN
                    )
                )
            }
            sb.append("</trkseg></trk>\n</gpx>\n")

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/gpx+xml")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/SailLog")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
                ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray()) }
                ?: return null
            Prefs.setLastGpx(ctx, uri.toString())
            name
        } catch (e: Exception) {
            null
        }
    }
}
