package dz.ogefgef322.gnss

import android.content.ContentResolver
import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.events.MapListener
import kotlin.math.sqrt

/**
 * Owns all OSMDroid map state: overlays, markers, trace, follow mode.
 * MainActivity stays focused on UI + app logic.
 */
class MapController(
    private val context: Context,
    private val packageName: String,
    private val mapView: MapView,
    private val contentResolver: ContentResolver,
    private val levePointStore: LevePointStore,
    private val isImplantationModeProvider: () -> Boolean,
    private val hasActiveLeve: () -> Boolean,
    private val onSurveyLongPress: (GeoPoint) -> Unit,
    private val onImplantLongPress: (GeoPoint) -> Unit,
    private val onFollowChangedByUserPan: (Boolean) -> Unit,
) {

    companion object {
        private const val TAG = "MapController"
    }

    private var myMarker: Marker? = null
    private val pointMarkers = mutableListOf<Marker>()
    private val leveMarkers = mutableListOf<Marker>()
    private val repereMarkers = mutableListOf<Marker>()
    private var targetMarker: Marker? = null

    private var trackLine: Polyline? = null
    private var labelOverlay: PointLabelOverlay? = null
    private var targetLine: Polyline? = null
    private var targetCircle: Polygon? = null

    private var compassOverlay: CompassOverlay? = null

    private var lastAutoCenterElapsed = 0L
    private val autoCenterIntervalMs = 700L

    var isFollowEnabled: Boolean = false
        private set

    var isTraceVisible: Boolean = true
        private set

    private var markersVisible: Boolean = true
    private var compassEnabled: Boolean = true

    fun init() {
        Configuration.getInstance().userAgentValue = packageName

        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(18.0)
        mapView.controller.setCenter(GeoPoint(36.0, 3.0))

        // Trace
        trackLine = Polyline().apply {
            outlinePaint.strokeWidth = 4f
            isVisible = isTraceVisible
        }
        mapView.overlays.add(trackLine)

        // Labels
        labelOverlay = PointLabelOverlay(context).also { overlay ->
            mapView.overlays.add(overlay)
        }

        // Compass
        if (compassOverlay == null) {
            compassOverlay = CompassOverlay(
                context,
                InternalCompassOrientationProvider(context),
                mapView
            ).apply { enableCompass() }
        }
        compassOverlay?.let { overlay ->
            if (!mapView.overlays.contains(overlay)) mapView.overlays.add(overlay)
        }

        // Long press events
        val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false

            override fun longPressHelper(p: GeoPoint?): Boolean {
                val pressPoint = p ?: return false
                if (!hasActiveLeve()) return false
                return if (isImplantationModeProvider()) {
                    onImplantLongPress(pressPoint)
                    true
                } else {
                    onSurveyLongPress(pressPoint)
                    true
                }
            }
        })
        mapView.overlays.add(eventsOverlay)

        // User pan disables follow
        mapView.addMapListener(object : MapListener {
            override fun onZoom(event: ZoomEvent?): Boolean {
                mapView.invalidate()
                return true
            }

            override fun onScroll(event: ScrollEvent?): Boolean {
                if (isFollowEnabled) {
                    isFollowEnabled = false
                    onFollowChangedByUserPan(false)
                }
                return false
            }
        })

        // Rover marker
        myMarker = Marker(mapView).apply {
            title = "VOUS"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(context, android.R.drawable.ic_menu_mylocation)
        }
        mapView.overlays.add(myMarker)

        // Rotation gestures (safe to add even if not used)
        runCatching {
            val rotation = RotationGestureOverlay(mapView)
            rotation.isEnabled = true
            mapView.overlays.add(rotation)
        }
    }

    fun setFollowEnabled(enabled: Boolean) {
        isFollowEnabled = enabled
    }

    fun toggleFollowEnabled(): Boolean {
        isFollowEnabled = !isFollowEnabled
        return isFollowEnabled
    }

    fun toggleTraceVisibility(): Boolean {
        isTraceVisible = !isTraceVisible
        trackLine?.isVisible = isTraceVisible
        mapView.invalidate()
        return isTraceVisible
    }

    fun toggleMarkersVisibility(): Boolean {
        markersVisible = !markersVisible
        applyMarkersVisibility()
        mapView.invalidate()
        return markersVisible
    }

    fun toggleCompassOverlay(): Boolean {
        val overlay = compassOverlay ?: return compassEnabled
        compassEnabled = !compassEnabled
        if (compassEnabled) overlay.enableCompass() else overlay.disableCompass()
        mapView.invalidate()
        return compassEnabled
    }

    fun centerOnMe(lat: Double, lon: Double, forceZoom: Boolean): Boolean {
        if (lat == 0.0 && lon == 0.0) return false
        centerOn(lat, lon, zoom = if (forceZoom) 19.0 else null)
        return true
    }

    fun centerOn(lat: Double, lon: Double, zoom: Double? = null) {
        val gp = GeoPoint(lat, lon)
        mapView.controller.setCenter(gp)
        if (zoom != null) mapView.controller.setZoom(zoom)
    }

    fun updatePosition(lat: Double, lon: Double, fixQuality: Int, isMapVisible: Boolean) {
        if (lat == 0.0 && lon == 0.0) return

        val gp = GeoPoint(lat, lon)

        myMarker?.position = gp
        myMarker?.snippet = when (fixQuality) {
            4 -> "RTK Fixe"
            5 -> "RTK Float"
            1 -> "Single"
            else -> "No Fix"
        }

        // Track: add point only if moved enough
        if (isTraceVisible) {
            val last = trackLine?.actualPoints?.lastOrNull()
            val dist = if (last != null) gp.distanceToAsDouble(last) else Double.MAX_VALUE
            if (dist >= 0.20) {
                trackLine?.addPoint(gp)
                val pts = trackLine?.actualPoints
                if (pts != null && pts.size > 2000) {
                    pts.subList(0, pts.size - 2000).clear()
                }
            }
        }

        if (isMapVisible) {
            if (isFollowEnabled) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastAutoCenterElapsed >= autoCenterIntervalMs) {
                    lastAutoCenterElapsed = now
                    runCatching { mapView.controller.animateTo(gp) }
                        .onFailure { mapView.controller.setCenter(gp) }
                }
            }
            mapView.invalidate()
        }
    }

    fun refreshMarkers(levesUri: android.net.Uri?, reperesUri: android.net.Uri?, isMapVisible: Boolean) {
        // Clear previously loaded markers (not the in-session point markers)
        leveMarkers.forEach { mapView.overlays.remove(it) }
        repereMarkers.forEach { mapView.overlays.remove(it) }
        leveMarkers.clear()
        repereMarkers.clear()

        val labelPoints = mutableListOf<CsvPoint>()

        if (levesUri != null) {
            loadLeveCsvPoints(levesUri).forEach { point ->
                buildMarker(point, isRepere = false)?.let { marker ->
                    leveMarkers.add(marker)
                    mapView.overlays.add(marker)
                    applyMarkerVisibility(marker)
                }
                labelPoints.add(point)
            }
        }

        if (reperesUri != null) {
            loadRepereCsvPoints(reperesUri).forEach { point ->
                buildMarker(point, isRepere = true)?.let { marker ->
                    repereMarkers.add(marker)
                    mapView.overlays.add(marker)
                    applyMarkerVisibility(marker)
                }
                labelPoints.add(point)
            }
        }

        labelOverlay?.setPoints(labelPoints)
        labelOverlay?.let { overlay ->
            // Keep overlay on top
            mapView.overlays.remove(overlay)
            mapView.overlays.add(overlay)
        }

        if (isMapVisible) mapView.invalidate()
    }

    fun addMarkerForSavedPoint(
        inct: InctResult,
        lat: Double,
        lon: Double,
        fixQuality: Int,
        isMapVisible: Boolean
    ) {
        if (lat == 0.0 && lon == 0.0) return

        val gp = GeoPoint(lat, lon)
        val m = Marker(mapView).apply {
            position = gp
            title = "Point ${inct.id}"
            snippet = String.format(
                java.util.Locale.US,
                "X=%.2f  Y=%.2f  Z=%.2f  Zone=%d",
                inct.x, inct.y, inct.z, inct.zone
            )
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

            icon = when (fixQuality) {
                4 -> ContextCompat.getDrawable(context, android.R.drawable.presence_online)
                5 -> ContextCompat.getDrawable(context, android.R.drawable.presence_away)
                1 -> ContextCompat.getDrawable(context, android.R.drawable.presence_invisible)
                else -> ContextCompat.getDrawable(context, android.R.drawable.presence_busy)
            }
        }

        pointMarkers.add(m)
        mapView.overlays.add(m)
        applyMarkerVisibility(m)

        labelOverlay?.addPoint(inct.id, gp)
        labelOverlay?.let { overlay ->
            mapView.overlays.remove(overlay)
            mapView.overlays.add(overlay)
        }

        if (isMapVisible) mapView.invalidate()
    }

    fun updateTargetMarker(id: String?, lat: Double?, lon: Double?, isMapVisible: Boolean) {
        targetMarker?.let { mapView.overlays.remove(it) }
        targetMarker = null

        if (id.isNullOrBlank() || lat == null || lon == null) return
        if (lat == 0.0 && lon == 0.0) return

        val marker = Marker(mapView).apply {
            position = GeoPoint(lat, lon)
            title = "Cible $id"
            snippet = id
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(context, android.R.drawable.ic_menu_compass)
        }

        targetMarker = marker
        mapView.overlays.add(marker)
        applyMarkerVisibility(marker)

        if (isMapVisible) mapView.invalidate()
    }

    fun updateTargetLineOnMap(
        currentLat: Double?,
        currentLon: Double?,
        targetLat: Double?,
        targetLon: Double?,
        isMapVisible: Boolean
    ) {
        if (currentLat == null || currentLon == null || targetLat == null || targetLon == null) {
            targetLine?.setPoints(arrayListOf())
            if (isMapVisible) mapView.invalidate()
            return
        }

        ensureTargetLineOverlay()
        val pNow = GeoPoint(currentLat, currentLon)
        val pTarget = GeoPoint(targetLat, targetLon)
        targetLine?.setPoints(arrayListOf(pNow, pTarget))
        targetLine?.outlinePaint?.color = Color.RED

        if (isMapVisible) mapView.invalidate()
    }

    fun updateTargetCircle(targetLat: Double, targetLon: Double, radiusMeters: Double) {
        ensureTargetCircle()
        val center = GeoPoint(targetLat, targetLon)
        targetCircle?.points = Polygon.pointsAsCircle(center, radiusMeters)
        mapView.invalidate()
    }

    fun clearTargetCircle() {
        targetCircle?.points = arrayListOf()
        mapView.invalidate()
    }

    /**
     * Returns the nearest TargetPoint to a press location, computed in screen pixels.
     *
     * This avoids comparing lat/lon distances which can feel "off" visually on a map.
     * The threshold is expressed in dp to behave consistently across screen densities.
     */
    fun pickNearestTargetFromPress(
        pressPoint: GeoPoint,
        targets: List<TargetPoint>,
        thresholdDp: Double = 60.0
    ): TargetPoint? {
        if (targets.isEmpty()) return null

        val projection = mapView.projection
        val pressPixel = Point()
        projection.toPixels(pressPoint, pressPixel)

        var nearest: TargetPoint? = null
        var nearestDistance = Double.MAX_VALUE
        val targetPixel = Point()

        for (t in targets) {
            projection.toPixels(GeoPoint(t.lat, t.lon), targetPixel)
            val dx = (pressPixel.x - targetPixel.x).toDouble()
            val dy = (pressPixel.y - targetPixel.y).toDouble()
            val dist = sqrt(dx * dx + dy * dy)
            if (dist < nearestDistance) {
                nearestDistance = dist
                nearest = t
            }
        }

        val thresholdPx = thresholdDp * context.resources.displayMetrics.density
        val chosen = nearest ?: return null
        return if (nearestDistance <= thresholdPx) chosen else null
    }

    // --- CSV helpers exposed for dialogs ---

    fun loadLeveCsvPoints(uri: android.net.Uri): List<CsvPoint> {
        return levePointStore.getLevePointsForCurrentChantier(uri).map { p ->
            CsvPoint(p.id, p.lat, p.lon)
        }
    }

    fun loadRepereCsvPoints(uri: android.net.Uri): List<CsvPoint> {
        return parseCsvPoints(uri, idIndex = 0, latIndex = 4, lonIndex = 5)
    }

    // --- Internals ---

    private fun ensureTargetLineOverlay() {
        if (targetLine != null) return
        targetLine = Polyline().apply {
            outlinePaint.strokeWidth = 6f
            outlinePaint.isAntiAlias = true
            outlinePaint.color = Color.RED
        }
        mapView.overlays.add(targetLine)
    }

    private fun ensureTargetCircle() {
        if (targetCircle != null) return
        targetCircle = Polygon().apply {
            fillPaint.color = Color.argb(40, 46, 125, 50)
            outlinePaint.color = ContextCompat.getColor(context, R.color.guid_ok)
            outlinePaint.strokeWidth = 3f
        }
        mapView.overlays.add(targetCircle)
    }

    private fun parseCsvPoints(uri: android.net.Uri, idIndex: Int, latIndex: Int, lonIndex: Int): List<CsvPoint> {
        val points = mutableListOf<CsvPoint>()
        runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                reader.lineSequence().drop(1).forEach { line ->
                    if (line.isBlank()) return@forEach
                    val parts = line.split(",")
                    if (parts.size <= maxOf(idIndex, latIndex, lonIndex)) return@forEach
                    val id = parts[idIndex].trim()
                    val lat = parts[latIndex].trim().toDoubleOrNull()
                    val lon = parts[lonIndex].trim().toDoubleOrNull()
                    if (id.isNotBlank() && lat != null && lon != null) {
                        points.add(CsvPoint(id, lat, lon))
                    }
                }
            }
        }.onFailure { e ->
            Log.e(TAG, "parseCsvPoints failed", e)
        }
        return points
    }

    private fun buildMarker(point: CsvPoint, isRepere: Boolean): Marker? {
        if (point.lat == 0.0 && point.lon == 0.0) return null
        return Marker(mapView).apply {
            position = GeoPoint(point.lat, point.lon)
            title = point.id
            snippet = point.id
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = if (isRepere) {
                ContextCompat.getDrawable(context, android.R.drawable.ic_menu_myplaces)
            } else {
                ContextCompat.getDrawable(context, android.R.drawable.presence_online)
            }
        }
    }

    private fun applyMarkerVisibility(marker: Marker) {
        marker.isEnabled = markersVisible
        runCatching { marker.alpha = if (markersVisible) 1.0f else 0.0f }
    }

    private fun applyMarkersVisibility() {
        for (o in mapView.overlays) {
            if (o is Marker) {
                o.isEnabled = markersVisible
                runCatching { o.alpha = if (markersVisible) 1.0f else 0.0f }
            }
        }
    }
}
