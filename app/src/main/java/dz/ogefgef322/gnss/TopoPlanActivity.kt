package dz.ogefgef322.gnss
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile

class TopoPlanActivity : AppCompatActivity() {

    private lateinit var topoView: TopoPlanView
    private val levePointStore by lazy { LevePointStore(contentResolver) }
    private val polylineStore by lazy { TopoPlanPolylineStore(contentResolver) }
    private var chantierDir: DocumentFile? = null
    private var leveId: String? = null
    private var levesUri: Uri? = null
    private var polylinesLoaded = false
    private val storedPolylines: MutableList<StoredPolyline> = mutableListOf()
    private val voiceZoomListener: (VoiceZoomBus.Event) -> Unit = { event ->
        topoView.setVoiceZoomMeters(event.meters)
        forceLastLevePointAsVoiceTargetIfNeeded()
        topoView.applyVoiceZoomAndCenter()
    }
    private val voiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val safeIntent = intent ?: return
            Log.d("VOICE_TOPO", "recv action=${safeIntent.action}")
            when (safeIntent.action) {
                MainActivity.ACTION_VOICE_ZOOM -> {
                    val enabled = safeIntent.getBooleanExtra(MainActivity.EXTRA_VOICE_ZOOM_ENABLED, false)
                    val reset = safeIntent.getBooleanExtra(MainActivity.EXTRA_VOICE_ZOOM_RESET, false)
                    val meters = safeIntent.getDoubleExtra(MainActivity.EXTRA_VOICE_ZOOM_METERS, VOICE_ZOOM_DEFAULT_METERS)
                    Log.d("VOICE_TOPO", "ZOOM enabled=$enabled reset=$reset meters=$meters")
                    topoView.setVoiceFollowEnabled(enabled)
                    topoView.setVoiceZoomMeters(meters)
                    forceLastLevePointAsVoiceTargetIfNeeded()
                    topoView.applyVoiceZoomAndCenter()
                }
                MainActivity.ACTION_VOICE_FOLLOW_POSITION -> {
                    val enabled = safeIntent.getBooleanExtra(MainActivity.EXTRA_VOICE_FOLLOW_ENABLED, false)
                    if (!enabled) return
                    val lat = safeIntent.getDoubleExtra(MainActivity.EXTRA_VOICE_FOLLOW_LAT, 0.0)
                    val lon = safeIntent.getDoubleExtra(MainActivity.EXTRA_VOICE_FOLLOW_LON, 0.0)
                    val alt = safeIntent.getDoubleExtra(MainActivity.EXTRA_VOICE_FOLLOW_ALT, 0.0)
                    if (lat == 0.0 && lon == 0.0) return
                    val inct = runCatching { wgs84ToUtmInct(lat, lon, alt) }.getOrNull() ?: return
                    topoView.updateVoiceFollowInct(inct.x, inct.y)
                    topoView.applyVoiceZoomAndCenter()
                }
                MainActivity.ACTION_VOICE_FOLLOW_LAST_POINT -> {
                    val x = safeIntent.getDoubleExtra(MainActivity.EXTRA_VOICE_LAST_X, Double.NaN)
                    val y = safeIntent.getDoubleExtra(MainActivity.EXTRA_VOICE_LAST_Y, Double.NaN)
                    if (x.isNaN() || y.isNaN()) return
                    topoView.setVoiceFollowEnabled(true)
                    topoView.updateVoiceFollowInct(x, y)
                    topoView.applyVoiceZoomAndCenter()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_topo_plan)

        topoView = findViewById(R.id.topoPlanView)

        findViewById<Button>(R.id.btnBackTopoPlan).setOnClickListener { finish() }

        val toolbarToggle = findViewById<ImageButton>(R.id.btnToggleToolbar)
        val toolbarContent = findViewById<LinearLayout>(R.id.layoutTopoToolbarContent)
        val toolbarStateText = findViewById<TextView>(R.id.txtTopoToolbarState)

        fun collapseToolbar() {
            toolbarContent.visibility = View.GONE
            toolbarToggle.setImageResource(android.R.drawable.arrow_up_float)
        }

        fun expandToolbar() {
            toolbarContent.visibility = View.VISIBLE
            toolbarToggle.setImageResource(android.R.drawable.arrow_down_float)
        }

        collapseToolbar()
        toolbarStateText.text = "Mode : POLYLINE"

        toolbarToggle.setOnClickListener {
            val isVisible = toolbarContent.visibility == View.VISIBLE
            if (isVisible) collapseToolbar() else expandToolbar()
        }

        topoView.onCanvasTouched = {
            if (toolbarContent.visibility == View.VISIBLE) collapseToolbar()
        }

        val btnClassPick = findViewById<Button>(R.id.btnTopoClass)

        val classOptions = listOf(
            "Clôture",
            "Mur",
            "Bordure/Trottoir",
            "Limite",
            "Axe/Chemin",
            "Ligne électrique"
        )
        var selectedClass = classOptions.first()
        topoView.setSelectedClassType(selectedClass)
        btnClassPick.text = "Classe : $selectedClass"

        btnClassPick.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Choisir la classe")
                .setItems(classOptions.toTypedArray()) { _, which ->
                    selectedClass = classOptions[which]
                    topoView.setSelectedClassType(selectedClass)
                    btnClassPick.text = "Classe : $selectedClass"
                    toolbarStateText.text = "Mode : POLYLINE — $selectedClass"
                    collapseToolbar()
                }
                .show()
        }

        val btnUndo = findViewById<Button>(R.id.btnUndo)
        val btnFinish = findViewById<Button>(R.id.btnFinish)
        val btnExit = findViewById<Button>(R.id.btnExit)

        val btnZoomIn = findViewById<Button>(R.id.btnZoomIn)
        val btnZoomOut = findViewById<Button>(R.id.btnZoomOut)

        // Mode unique : POLYLINE
        topoView.setPlanMode(TopoPlanMode.DRAW_POLYLINE)
        toolbarStateText.text = "Mode : POLYLINE — $selectedClass"

        fun refreshToolbarButtons() {
            updateToolbarState(TopoPlanMode.DRAW_POLYLINE, btnUndo, btnFinish, btnExit)
        }

        btnUndo.setOnClickListener {
            topoView.undoPolylineVertex()
            refreshToolbarButtons()
        }
        btnFinish.setOnClickListener {
            topoView.finishPolyline()
            refreshToolbarButtons()
        }
        btnExit.setOnClickListener {
            topoView.exitPolyline()
            refreshToolbarButtons()
            toolbarStateText.text = "Mode : POLYLINE — $selectedClass"
            collapseToolbar()
        }

        btnZoomIn.setOnClickListener { topoView.zoomIn() }
        btnZoomOut.setOnClickListener { topoView.zoomOut() }

        topoView.onMultiPointSelection = { candidates, onSelected ->
            topoView.ensureMultiPointDialog(candidates, onSelected)
        }
        topoView.onPolylineSelected = { feature ->
            showPolylineActions(feature)
        }
        topoView.onPolylineFinished = { feature ->
            storedPolylines.add(
                StoredPolyline(
                    id = feature.id,
                    leveId = leveId,
                    type = feature.classType,
                    points = feature.points.map { pt -> StoredPolylinePoint(pt.x, pt.y) },
                    closed = feature.isClosed
                )
            )
            savePolylines()
        }
        topoView.onPolylineChanged = { refreshToolbarButtons() }

        refreshToolbarButtons()
        initChantierDir()
        loadPointsFromIntent()
    }

    override fun onStart() {
        super.onStart()
        loadPointsFromIntent()
        VoiceZoomBus.register(voiceZoomListener)
        val filter = IntentFilter().apply {
            addAction(MainActivity.ACTION_VOICE_ZOOM)
            addAction(MainActivity.ACTION_VOICE_FOLLOW_POSITION)
            addAction(MainActivity.ACTION_VOICE_FOLLOW_LAST_POINT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(voiceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(voiceReceiver, filter)
        }
    }

    override fun onStop() {
        VoiceZoomBus.unregister(voiceZoomListener)
        unregisterReceiver(voiceReceiver)
        super.onStop()
    }

    private fun loadPointsFromIntent() {
        val intentLeveUri = intent.getStringExtra(EXTRA_LEVES_URI)?.let { Uri.parse(it) }
        levesUri = intentLeveUri
        if (intentLeveUri == null) {
            topoView.setTopoPoints(emptyList())
            Toast.makeText(this, "Aucun levé ouvert", Toast.LENGTH_SHORT).show()
            return
        }

        leveId = getLeveId(intentLeveUri)

        // CRS signature is stored in CSV. TopoPlan follows the levé CRS (no project-level check).
        val foundSig = CsvCrsUtils.readSignature(contentResolver, intentLeveUri)
        if (foundSig.isNullOrBlank()) {
            AlertDialog.Builder(this)
                .setTitle("CRS affiché non indiqué")
                .setMessage("Ce fichier est ancien (pas de CRS dans l’en-tête).\n\nContinuer quand même ?")
                .setNegativeButton("Annuler") { _, _ -> finish() }
                .setPositiveButton("Continuer", null)
                .show()
        }

        val points = levePointStore.getLevePointsForCurrentChantier(intentLeveUri)
            .map { p ->
                TopoPoint(
                    id = p.id,
                    x = p.x,
                    y = p.y,
                    type = TopoPointType.fromLabel(p.typeLabel) // ✅ icônes
                )
            }

        topoView.setTopoPoints(points)
        loadPolylinesIfNeeded()
    }

    private fun forceLastLevePointAsVoiceTargetIfNeeded() {
        if (topoView.hasVoiceFollowTarget()) return
        val leveUri = levesUri ?: run {
            Log.w("VOICE_TOPO", "Missing levesUri for voice zoom fallback")
            return
        }
        val pts = levePointStore.getLevePointsForCurrentChantier(leveUri)
        val last = pts.lastOrNull()
        if (last == null) {
            Log.w("VOICE_TOPO", "No leve points available for voice zoom fallback")
            return
        }
        topoView.setVoiceFollowEnabled(true)
        topoView.updateVoiceFollowInct(last.x, last.y)
    }
//
    private fun initChantierDir() {
        val chantierUri = intent.getStringExtra(EXTRA_CHANTIER_DIR_URI)?.let { Uri.parse(it) }
        chantierDir = resolveChantierDirFromUriOrPrefs(this, chantierUri)
        if (chantierDir == null) {
            Toast.makeText(this, "Chantier non défini", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getLeveId(leveUri: Uri): String? {
        return DocumentFile.fromSingleUri(this, leveUri)?.name ?: leveUri.lastPathSegment
    }

    private fun loadPolylinesIfNeeded() {
        if (polylinesLoaded) return
        val dir = chantierDir ?: return
        storedPolylines.clear()
        storedPolylines.addAll(polylineStore.load(dir))
        val filtered = filterPolylinesForLeve(leveId)
        topoView.setPolylines(filtered.map { toPolylineFeature(it) })
        polylinesLoaded = true
    }

    private fun filterPolylinesForLeve(leveId: String?): List<StoredPolyline> {
        return if (leveId == null) {
            storedPolylines.toList()
        } else {
            storedPolylines.filter { it.leveId == null || it.leveId == leveId }
        }
    }

    private fun toPolylineFeature(polyline: StoredPolyline): PolylineFeature {
        val points = polyline.points.mapIndexed { index, pt ->
            PointRef("pt-${polyline.id}-${index + 1}", pt.x, pt.y)
        }
        val isClosed = polyline.closed || (
            points.size >= 3 &&
                points.first().x == points.last().x &&
                points.first().y == points.last().y
            )
        val isSurfaceFill = isClosed && !polyline.type.equals(LINEAR_NO_SURFACE, ignoreCase = true)
        return PolylineFeature(
            id = polyline.id,
            classType = polyline.type,
            points = points,
            isClosed = isClosed,
            isSurfaceFill = isSurfaceFill
        )
    }

    private fun savePolylines() {
        val dir = chantierDir ?: return
        polylineStore.save(dir, storedPolylines)
    }

    private fun showPolylineActions(feature: PolylineFeature) {
        AlertDialog.Builder(this)
            .setTitle("Polyligne sélectionnée")
            .setMessage("Que voulez-vous faire ?")
            .setPositiveButton("Supprimer") { _, _ ->
                confirmPolylineDeletion(feature)
            }
            .setNegativeButton("Annuler") { _, _ ->
                topoView.clearSelectedPolyline()
            }
            .setOnCancelListener {
                topoView.clearSelectedPolyline()
            }
            .show()
    }

    private fun confirmPolylineDeletion(feature: PolylineFeature) {
        AlertDialog.Builder(this)
            .setTitle("Supprimer la polyligne ?")
            .setMessage("Cette action est définitive.")
            .setPositiveButton("Supprimer") { _, _ ->
                deletePolyline(feature.id)
            }
            .setNegativeButton("Annuler") { _, _ ->
                topoView.clearSelectedPolyline()
            }
            .show()
    }

    private fun deletePolyline(polylineId: String) {
        val idx = storedPolylines.indexOfFirst { it.id == polylineId }
        if (idx >= 0) {
            storedPolylines.removeAt(idx)
            savePolylines()
            val filtered = filterPolylinesForLeve(leveId)
            topoView.setPolylines(filtered.map { toPolylineFeature(it) })
        }
        topoView.clearSelectedPolyline()
    }

    private fun updateToolbarState(
        mode: TopoPlanMode,
        btnUndo: Button,
        btnFinish: Button,
        btnExit: Button
    ) {
        val isDrawing = mode == TopoPlanMode.DRAW_POLYLINE
        btnUndo.isEnabled = isDrawing && topoView.canUndo()
        btnFinish.isEnabled = isDrawing && topoView.canFinish()
        btnExit.isEnabled = isDrawing
    }

    companion object {
        const val EXTRA_LEVES_URI = "extra_leves_uri"
        const val EXTRA_CHANTIER_DIR_URI = "extra_chantier_dir_uri"
        private const val LINEAR_NO_SURFACE = "Ligne électrique"
    }
}
