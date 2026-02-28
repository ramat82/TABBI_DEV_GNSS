package dz.ogefgef322.gnss

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Draws point IDs near their geo position.
 * Kept lightweight and independent from MainActivity.
 */
class PointLabelOverlay(context: Context) : Overlay() {

    private data class LabelPoint(val id: String, val geo: GeoPoint)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.LEFT
    }

    private val density = context.resources.displayMetrics.density
    private val scaledDensity = context.resources.displayMetrics.scaledDensity
    private val offsetX = 8f * density
    private val offsetY = -4f * density
    private val minTextSp = 10f
    private val maxTextSp = 18f

    private val points = mutableListOf<LabelPoint>()

    fun setPoints(items: List<CsvPoint>) {
        points.clear()
        points.addAll(items.map { LabelPoint(it.id, GeoPoint(it.lat, it.lon)) })
    }

    fun addPoint(id: String, geo: GeoPoint) {
        points.add(LabelPoint(id, geo))
    }

    override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        val zoom = mapView.zoomLevelDouble
        val sizeSp = (zoom.toFloat() * 1.2f - 6f).coerceIn(minTextSp, maxTextSp)
        paint.textSize = sizeSp * scaledDensity

        val projection = mapView.projection
        val screenPoint = Point()
        for (p in points) {
            projection.toPixels(p.geo, screenPoint)
            c.drawText(p.id, screenPoint.x + offsetX, screenPoint.y + offsetY, paint)
        }
    }
}
