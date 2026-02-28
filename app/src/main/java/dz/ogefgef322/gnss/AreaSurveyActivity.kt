package dz.ogefgef322.gnss

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import kotlin.math.abs

/**
 * SUPERFICIE (carte plein écran)
 * - Carte plein écran + boutons superposés.
 * - Paramètres FIXES (pas d'écran "levé auto").
 * - Manuel: ENREGISTRER ajoute un sommet.
 * - Auto: AUTO ON/OFF pause/reprend l'ajout automatique (sans reset).
 * - SUP en temps réel (dès 3 points) + dessin bleu (ligne/polygone).
 */
class AreaSurveyActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var btnBack: Button
    private lateinit var btnCenter: Button
    private lateinit var btnStart: Button
    private lateinit var btnAdd: Button
    private lateinit var btnAuto: Button
    private lateinit var btnClose: Button
    private lateinit var btnStop: Button
    private lateinit var txtCount: TextView
    private lateinit var txtArea: TextView

    private var autoEnabled: Boolean = false
    private var sessionStarted: Boolean = false

    // Geo points for drawing
    private val geoPts: MutableList<GeoPoint> = mutableListOf()

    private var polyline: Polyline? = null
    private var polygon: Polygon? = null
    private val markers: MutableList<Marker> = mutableListOf()

    // Small icons for points and GNSS (map markers)
    private var iconPointSmall: android.graphics.drawable.Drawable? = null
    private var iconGnssSmall: android.graphics.drawable.Drawable? = null


    // Live GNSS position marker (not saved point)
    private var gnssMarker: Marker? = null
    private var gnssMarkerAdded: Boolean = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val gnssTick = object : Runnable {
        override fun run() {
            updateGnssMarker()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    
private var lastLiveLat: Double = Double.NaN
private var lastLiveLon: Double = Double.NaN
private var lastLiveAlt: Double = Double.NaN

private val gnssPosReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return
        if (intent.action != MainActivity.ACTION_GNSS_POSITION) return
        lastLiveLat = intent.getDoubleExtra(MainActivity.EXTRA_GNSS_LAT, Double.NaN)
        lastLiveLon = intent.getDoubleExtra(MainActivity.EXTRA_GNSS_LON, Double.NaN)
        lastLiveAlt = intent.getDoubleExtra(MainActivity.EXTRA_GNSS_ALT, Double.NaN)
        updateGnssMarkerFrom(lastLiveLat, lastLiveLon)
    }
}

private val pointSavedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            if (intent.action != MainActivity.ACTION_POINT_SAVED) return

            val type = intent.getStringExtra(MainActivity.EXTRA_POINT_SAVED_TYPE) ?: return
            if (type != "AREA") return

            val lat = intent.getDoubleExtra(MainActivity.EXTRA_POINT_SAVED_LAT, Double.NaN)
            val lon = intent.getDoubleExtra(MainActivity.EXTRA_POINT_SAVED_LON, Double.NaN)
            if (!lat.isFinite() || !lon.isFinite()) return

            val gp = GeoPoint(lat, lon)
            geoPts.add(gp)
            redraw()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_area_survey)

        map = findViewById(R.id.area_map)
        btnBack = findViewById(R.id.area_btn_back)
        btnCenter = findViewById(R.id.area_btn_center)
        btnStart = findViewById(R.id.area_btn_start)
        btnAdd = findViewById(R.id.area_btn_add)
        btnAuto = findViewById(R.id.area_btn_auto)
        btnClose = findViewById(R.id.area_btn_close)
        btnStop = findViewById(R.id.area_btn_stop)
        txtCount = findViewById(R.id.area_count)
        txtArea = findViewById(R.id.area_result)

        btnBack.setOnClickListener { finish() }

        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(16.0)

        // Reduce marker icon size a bit for readability
        iconPointSmall = loadScaledDrawable(org.osmdroid.library.R.drawable.marker_default, targetWidthDp = 24)
        iconGnssSmall = loadScaledDrawable(android.R.drawable.ic_menu_mylocation, targetWidthDp = 22)

        polyline = Polyline(map).apply {
            outlinePaint.color = Color.BLUE
            outlinePaint.strokeWidth = 6f
        }
        polygon = Polygon(map).apply {
            fillPaint.color = Color.argb(60, 0, 0, 255)
            outlinePaint.color = Color.BLUE
            outlinePaint.strokeWidth = 5f
            isVisible = false
        }
        map.overlays.add(polyline)
        map.overlays.add(polygon)

        // GNSS live marker (same style as other map screens)
        gnssMarker = Marker(map).apply {
            title = "GNSS"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = iconGnssSmall ?: androidx.core.content.ContextCompat.getDrawable(this@AreaSurveyActivity, android.R.drawable.ic_menu_mylocation)
            // IMPORTANT: In osmdroid, disabling an Overlay can also disable drawing.
            // Keep it enabled so it is visible, but swallow clicks.
            isEnabled = true
            setOnMarkerClickListener { _, _ -> true }
        }
        
        btnCenter.setOnClickListener {
            // 1) Prefer GNSS (from broadcast / snapshot)
            val latGnss = if (lastLiveLat.isFinite()) lastLiveLat else MainActivity.lastGnssLat
            val lonGnss = if (lastLiveLon.isFinite()) lastLiveLon else MainActivity.lastGnssLon
            val targetGnss = if (latGnss.isFinite() && lonGnss.isFinite() && (latGnss != 0.0 || lonGnss != 0.0)) {
                GeoPoint(latGnss, lonGnss)
            } else null

            // Fallback to last saved point
            val target = targetGnss ?: geoPts.lastOrNull()

            if (target != null) {
                map.controller.animateTo(target)
                // optional: zoom in if too far out
                if (map.zoomLevelDouble < 18.0) map.controller.setZoom(18.0)
            }
        }

        btnStart.setOnClickListener {
            geoPts.clear()
            clearMarkers()
            autoEnabled = false
            btnAuto.text = "AUTO ON"
            sendCmdStart(startIndex = 1)
            sessionStarted = true
            redraw()
        }

        btnAdd.setOnClickListener {
            ensureStarted()
            sendCmdAdd()
        }

        btnAuto.setOnClickListener {
            ensureStarted()
            autoEnabled = !autoEnabled
            btnAuto.text = if (autoEnabled) "AUTO OFF" else "AUTO ON"
            // toggle pause (MainActivity)
            sendCmdPauseToggle()
        }

        btnClose.setOnClickListener {
            sendCmdClose()
            redraw()
        }

        btnStop.setOnClickListener {
            sendCmdStop()
            autoEnabled = false
            btnAuto.text = "AUTO ON"
        }

        // initial center if possible
        val lat0 = MainActivity.lastGnssLat
        val lon0 = MainActivity.lastGnssLon
        if (lat0 != 0.0 || lon0 != 0.0) {
            map.controller.setCenter(GeoPoint(lat0, lon0))
        }

        redraw()
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(pointSavedReceiver, IntentFilter(MainActivity.ACTION_POINT_SAVED))
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(gnssPosReceiver, IntentFilter(MainActivity.ACTION_GNSS_POSITION))
        map.onResume()
        uiHandler.post(gnssTick)
    }

    override fun onStop() {
        uiHandler.removeCallbacksAndMessages(null)
        runCatching { LocalBroadcastManager.getInstance(this).unregisterReceiver(pointSavedReceiver) }
        runCatching { LocalBroadcastManager.getInstance(this).unregisterReceiver(gnssPosReceiver) }
        map.onPause()
        super.onStop()
    }

    
private fun updateGnssMarker() {
    // Prefer live broadcast values; fallback to MainActivity snapshot
    val lat = if (lastLiveLat.isFinite()) lastLiveLat else MainActivity.lastGnssLat
    val lon = if (lastLiveLon.isFinite()) lastLiveLon else MainActivity.lastGnssLon
    updateGnssMarkerFrom(lat, lon)
}

private fun updateGnssMarkerFrom(lat: Double, lon: Double) {
    val m = gnssMarker ?: return
    if (lat.isFinite() && lon.isFinite() && (lat != 0.0 || lon != 0.0)) {
        m.position = GeoPoint(lat, lon)
        if (!gnssMarkerAdded) {
            map.overlays.add(m)
            gnssMarkerAdded = true
        } else {
            // Keep GNSS marker on top of other overlays (points/polygon)
            // so it remains visible even if it overlaps a saved point marker.
            map.overlays.remove(m)
            map.overlays.add(m)
        }
    } else {
        if (gnssMarkerAdded) {
            map.overlays.remove(m)
            gnssMarkerAdded = false
        }
    }
    map.invalidate()
}

    private fun ensureStarted() {
        if (!sessionStarted) {
            sendCmdStart(startIndex = 1)
            sessionStarted = true
        }
    }

    private fun sendCmdStart(startIndex: Int) {
        AreaSurveyController.send(
            context = this,
            cmd = AreaSurveyController.CMD_START,
            mode = "DIST",
            distMeters = 5.0,
            timeMs = 2000L,
            useAcc = false,
            accMax = 0.30,
            useSpeed = false,
            speedMin = 0.20,
            startIndex = startIndex,
        )
    }

    private fun sendCmdAdd() {
        AreaSurveyController.send(
            context = this,
            cmd = AreaSurveyController.CMD_ADD,
            mode = "DIST",
            distMeters = 5.0,
            timeMs = 2000L,
            useAcc = false,
            accMax = 0.30,
            useSpeed = false,
            speedMin = 0.20,
            startIndex = 1,
        )
    }

    private fun sendCmdPauseToggle() {
        AreaSurveyController.send(
            context = this,
            cmd = AreaSurveyController.CMD_PAUSE,
            mode = "DIST",
            distMeters = 5.0,
            timeMs = 2000L,
            useAcc = false,
            accMax = 0.30,
            useSpeed = false,
            speedMin = 0.20,
            startIndex = 1,
        )
    }

    private fun sendCmdClose() {
        AreaSurveyController.send(
            context = this,
            cmd = AreaSurveyController.CMD_CLOSE,
            mode = "DIST",
            distMeters = 5.0,
            timeMs = 2000L,
            useAcc = false,
            accMax = 0.30,
            useSpeed = false,
            speedMin = 0.20,
            startIndex = 1,
        )
    }

    private fun sendCmdStop() {
        AreaSurveyController.send(
            context = this,
            cmd = AreaSurveyController.CMD_STOP,
            mode = "DIST",
            distMeters = 5.0,
            timeMs = 2000L,
            useAcc = false,
            accMax = 0.30,
            useSpeed = false,
            speedMin = 0.20,
            startIndex = 1,
        )
    }

    private fun redraw() {
        txtCount.text = "Points: ${geoPts.size}"

        // Convert geo points to meters (UTM) on-the-fly to avoid any list desync.
        // IMPORTANT: ProjectCrs.WGS84_UTM_AUTO has no EPSG (null) -> CoordinateTransformer throws.
        // Use the project UTM converter instead.
        val enPts: List<Pair<Double, Double>> = geoPts.map { gp ->
            val u = wgs84ToUtm(gp.latitude, gp.longitude)
            Pair(u.easting, u.northing)
        }

        val areaM2 = if (enPts.size >= 3) polygonAreaAbsM2(enPts) else 0.0
        val ha = areaM2 / 10000.0
        txtArea.text = if (enPts.size < 3) {
            "SUP: --"
        } else {
            "SUP: ${"%.2f".format(areaM2)} m²  (${"%.4f".format(ha)} ha)"
        }

        syncMarkers()
        polyline?.setPoints(geoPts)

        if (geoPts.size >= 3) {
            polygon?.points = geoPts
            polygon?.isVisible = true
        } else {
            polygon?.isVisible = false
        }

        map.invalidate()

        // Ensure GNSS marker is kept in sync (even if no points saved yet)
        updateGnssMarker()
    }

    private fun clearMarkers() {
        markers.forEach { map.overlays.remove(it) }
        markers.clear()
    }

    private fun syncMarkers() {
        if (markers.size == geoPts.size) return
        clearMarkers()
        geoPts.forEachIndexed { idx, gp ->
            val m = Marker(map).apply {
                position = gp
                title = "${idx + 1}"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                iconPointSmall?.let { icon = it }
            }
            markers.add(m)
            map.overlays.add(m)
        }
    }


    private fun loadScaledDrawable(drawableId: Int, targetWidthDp: Int): android.graphics.drawable.Drawable? {
        val d = androidx.core.content.ContextCompat.getDrawable(this, drawableId) ?: return null
        val density = resources.displayMetrics.density
        val targetW = (targetWidthDp * density).toInt().coerceAtLeast(1)

        val iw = d.intrinsicWidth.coerceAtLeast(1)
        val ih = d.intrinsicHeight.coerceAtLeast(1)

        val scale = targetW.toFloat() / iw.toFloat()
        val w = targetW
        val h = (ih * scale).toInt().coerceAtLeast(1)

        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(canvas)
        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }

    private fun polygonAreaAbsM2(pts: List<Pair<Double, Double>>): Double {
        var sum = 0.0
        for (i in pts.indices) {
            val (x1, y1) = pts[i]
            val (x2, y2) = pts[(i + 1) % pts.size]
            sum += (x1 * y2) - (x2 * y1)
        }
        return abs(sum) * 0.5
    }
}
