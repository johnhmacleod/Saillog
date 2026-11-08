package com.example.saillog

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** Offline track plot: no map tiles, just the track, start/current markers, north arrow and scale bar. */
class TrackView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    private var pts: List<TrackPoint> = emptyList()
    private val d = resources.displayMetrics.density
    private val path = Path()

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4FC3F7.toInt(); style = Paint.Style.STROKE
        strokeWidth = 3 * d; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13 * d }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2 * d }

    fun setPoints(p: List<TrackPoint>) {
        pts = p
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF0B2540.toInt())
        if (pts.isEmpty()) {
            text.textAlign = Paint.Align.CENTER
            canvas.drawText("No track yet", width / 2f, height / 2f, text)
            return
        }

        var minLat = 90.0; var maxLat = -90.0; var minLon = 180.0; var maxLon = -180.0
        for (p in pts) {
            minLat = min(minLat, p.lat); maxLat = max(maxLat, p.lat)
            minLon = min(minLon, p.lon); maxLon = max(maxLon, p.lon)
        }
        val kx = cos(Math.toRadians((minLat + maxLat) / 2)) // shrink longitude so the plot isn't stretched
        val spanX = (maxLon - minLon) * kx
        val spanY = maxLat - minLat

        val pad = 28 * d
        val aw = max(width - 2 * pad, 1f)
        val ah = max(height - 2 * pad - 16 * d, 1f)
        val scale = min(aw / max(spanX, 0.001), ah / max(spanY, 0.001)) // pixels per degree of latitude
        val ox = pad + (aw - spanX * scale) / 2
        val oy = pad + (ah - spanY * scale) / 2

        fun px(p: TrackPoint) = (ox + (p.lon - minLon) * kx * scale).toFloat()
        fun py(p: TrackPoint) = (oy + (maxLat - p.lat) * scale).toFloat()

        path.reset()
        path.moveTo(px(pts[0]), py(pts[0]))
        for (i in 1 until pts.size) path.lineTo(px(pts[i]), py(pts[i]))
        canvas.drawPath(path, line)

        dot.color = 0xFF66BB6A.toInt()
        canvas.drawCircle(px(pts.first()), py(pts.first()), 5 * d, dot)
        dot.color = 0xFFFFA726.toInt()
        canvas.drawCircle(px(pts.last()), py(pts.last()), 6 * d, dot)

        // north arrow
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText("N ↑", width - 8 * d, 20 * d, text)

        // scale bar (nautical miles / metres)
        val metersPerPx = 111_320.0 / scale
        val targetM = metersPerPx * 100 // ~100 px long
        val nice: Double
        val label: String
        if (targetM >= 1852) {
            val nm = listOf(1, 2, 5, 10, 20, 50, 100).lastOrNull { it * 1852.0 <= targetM } ?: 1
            nice = nm * 1852.0; label = "$nm nm"
        } else {
            val m = listOf(10, 20, 50, 100, 200, 500, 1000).lastOrNull { it <= targetM } ?: 10
            nice = m.toDouble(); label = "$m m"
        }
        val len = (nice / metersPerPx).toFloat()
        val y = height - 14 * d
        canvas.drawLine(8 * d, y, 8 * d + len, y, bar)
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(label, 8 * d + len + 6 * d, y + 4 * d, text)
    }
}
