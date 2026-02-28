package dz.ogefgef322.gnss
import android.Manifest
import android.app.AlertDialog
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResult
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.switchmaterial.SwitchMaterial
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.*
import kotlin.random.Random

// Top-level models/utils moved to dedicated files (Models.kt, GeoConversions.kt, VoiceZoom.kt, InctFileManager.kt)

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        // Emitted when a point is saved (used by Superficie/Area screen to update map + area in real time)
        const val ACTION_POINT_SAVED = "dz.ogefgef322.gnss.ACTION_POINT_SAVED"
        const val EXTRA_POINT_SAVED_TYPE = "type"   // e.g. AUTO / AREA / MANUAL / INT ...
        const val EXTRA_POINT_SAVED_ID = "id"
        const val EXTRA_POINT_SAVED_LAT = "lat"
        const val EXTRA_POINT_SAVED_LON = "lon"
        const val EXTRA_POINT_SAVED_ALT = "alt"

        // Emitted on every GNSS update so map screens can show live position
        const val ACTION_GNSS_POSITION = "dz.ogefgef322.gnss.ACTION_GNSS_POSITION"
        const val EXTRA_GNSS_LAT = "lat"
        const val EXTRA_GNSS_LON = "lon"
        const val EXTRA_GNSS_ALT = "alt"

        // Latest GNSS position (WGS84) for map-centric screens (Superficie, etc.)
        // Updated on each GNSS UI refresh (includes simulation).
        @Volatile var lastGnssLat: Double = 0.0
        @Volatile var lastGnssLon: Double = 0.0
        @Volatile var lastGnssAlt: Double = 0.0
    

        const val TAG = "OGEF_DZ"
        const val PERM_REQ = 200
        const val PERM_REQ_AUDIO = 201
        private const val PREFS_NAME = "surveyogef_prefs"

        // For legacy levé files that don't embed a CRS signature: remember the chosen CRS per file.
        private const val KEY_LEVE_CRS_OVERRIDE_PREFIX = "leve_crs_override_"
        private const val KEY_SOUND_ENABLED = "sound_enabled"
        private const val KEY_VOICE_ENABLED = "voice_enabled"
        private const val KEY_VOICE_MODE_ENABLED = "voice_mode_enabled"
        private const val KEY_VOICE_STATE = "voice_state"
        private const val KEY_VOICE_LANG_MODE = "voice_lang_mode"
        private const val KEY_ANTENNA_HEIGHT = "antenna_height_m"
        private const val KEY_DESIRED_PRECISION = "desired_precision_m"
        private const val KEY_PRECISION_LOCK = "precision_lock"
        const val ACTION_VOICE_ZOOM = "com.ogef.rabeh.ACTION_VOICE_ZOOM"
        const val ACTION_VOICE_FOLLOW_POSITION = "com.ogef.rabeh.ACTION_VOICE_FOLLOW_POSITION"
        const val ACTION_VOICE_FOLLOW_LAST_POINT = "com.ogef.rabeh.ACTION_VOICE_FOLLOW_LAST_POINT"
        const val EXTRA_VOICE_ZOOM_ENABLED = "extra_voice_zoom_enabled"
        const val EXTRA_VOICE_ZOOM_RESET = "extra_voice_zoom_reset"
        const val EXTRA_VOICE_ZOOM_METERS = "extra_voice_zoom_meters"
        const val EXTRA_VOICE_FOLLOW_ENABLED = "extra_voice_follow_enabled"
        const val EXTRA_VOICE_FOLLOW_METERS = "extra_voice_follow_meters"
        const val EXTRA_VOICE_FOLLOW_LAT = "extra_voice_follow_lat"
        const val EXTRA_VOICE_FOLLOW_LON = "extra_voice_follow_lon"
        const val EXTRA_VOICE_FOLLOW_ALT = "extra_voice_follow_alt"
        const val EXTRA_VOICE_LAST_X = "EXTRA_VOICE_LAST_X"
        const val EXTRA_VOICE_LAST_Y = "EXTRA_VOICE_LAST_Y"
    }

    private enum class MapMode {
        SURVEY,
        IMPLANTATION
    }

    private enum class Screen {
        NAVIGATION,
        GESTION,
        PROGRAMMES_TOPO,
        LEVE,
        MAP_LEVE,
        IMPLANTATION,
        MAP_IMPLANTATION
    }

    private enum class VoiceState {
        IDLE,
        WAITING_POINT_TYPE
    }

    private enum class VoiceCommand {
        SAVE,
        NEXT,
        BACK,
        PLAN_TOPO,
        IMPLANTATION,
        CLOSE,
        CANCEL,
        HELP,
        ZOOM_RESET,
        ZOOM_PLUS,
        ZOOM_MINUS
    }

    private enum class VoiceCommandResult {
        SUCCESS,
        FAIL,
        UNKNOWN
    }

    private enum class VoiceLanguage {
        FR,
        AR
    }

    private enum class VoiceLangMode(val prefValue: String) {
        AUTO("AUTO"),
        FR("FR"),
        AR("AR");

        companion object {
            fun fromPref(value: String?): VoiceLangMode {
                return entries.firstOrNull { it.prefValue == value } ?: AUTO
            }
        }
    }

    private enum class GuidanceKey {
        LEFT,
        RIGHT,
        FORWARD,
        BACK,
        OK,
        TARGET_SELECTED
    }

        /* ---- Bluetooth ---- */
    private lateinit var btAdapter: BluetoothAdapter
    private lateinit var btClient: BluetoothGnssClient
    private lateinit var btScanner: BluetoothDeviceScanner
    private var selectedDevice: BluetoothDevice? = null
    private val paired = mutableListOf<BluetoothDevice>()
    private val discovered = mutableListOf<BluetoothDevice>()
    private val allDevices = mutableListOf<BluetoothDevice>()
    private val deviceNames = mutableListOf<String>()
    private val discoveredRssi = mutableMapOf<String, Int>()
    private val scanTimeoutMs = 12_000L
    /* ---- GNSS ---- */
    private val gnssStore = GnssStateStore()

    private var lat: Double
        get() = gnssStore.state.lat
        set(value) { gnssStore.setLat(value) }

    private var lon: Double
        get() = gnssStore.state.lon
        set(value) { gnssStore.setLon(value) }

    private var alt: Double
        get() = gnssStore.state.alt
        set(value) { gnssStore.setAlt(value) }

    private var fixQuality: Int
        get() = gnssStore.state.fixQuality
        set(value) { gnssStore.setFixQuality(value) }

    private var satellites: Int
        get() = gnssStore.state.satellites
        set(value) { gnssStore.setSatellites(value) }

    private var currentInct: InctResult?
        get() = gnssStore.state.inct
        set(value) { gnssStore.setInct(value) }

    // NMEA parser (extracted from MainActivity)
    private lateinit var nmeaParser: NmeaParser

    /* =========================
       AUTO SURVEY (runs inside MainActivity; configured via AutoSurveyActivity)
       ========================= */

    private var autoSurveyRunning: Boolean = false
    private var autoSurveyPaused: Boolean = false
    private var autoSurveyMode: String = "DIST" // DIST | TIME
    private var autoSurveyDistMeters: Double = 5.0
    private var autoSurveyTimeMs: Long = 2000L
    private var autoSurveyUseAcc: Boolean = false
    private var autoSurveyAccMax: Double = 0.50
    private var autoSurveyUseSpeed: Boolean = false
    private var autoSurveySpeedMin: Double = 0.20
    private var autoSurveyCounter: Int = 1
    private var lastAutoSavedInct: InctResult? = null
    private var lastAutoSavedAtMs: Long = 0L
    private var lastFixInctForSpeed: InctResult? = null
    private var lastFixAtMs: Long = 0L
    private var lastSpeedMps: Double = 0.0

    private val autoSurveyHandler = Handler(Looper.getMainLooper())
    private val autoSurveyTick = object : Runnable {
        override fun run() {
            runCatching { tickAutoSurvey() }
            runCatching { tickAreaSurvey() }
            autoSurveyHandler.postDelayed(this, 500L)
        }
    }

    private val autoSurveyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            if (intent.action != AutoSurveyController.ACTION_AUTO_SURVEY_CMD) return

            val cmd = intent.getStringExtra(AutoSurveyController.EXTRA_CMD) ?: return
            autoSurveyMode = intent.getStringExtra(AutoSurveyController.EXTRA_MODE) ?: autoSurveyMode
            autoSurveyDistMeters = intent.getDoubleExtra(AutoSurveyController.EXTRA_DIST_M, autoSurveyDistMeters)
            val timeS = intent.getDoubleExtra(AutoSurveyController.EXTRA_TIME_S, (autoSurveyTimeMs / 1000.0))
            autoSurveyTimeMs = (timeS * 1000.0).toLong().coerceAtLeast(500L)
            autoSurveyUseAcc = intent.getBooleanExtra(AutoSurveyController.EXTRA_USE_ACC, autoSurveyUseAcc)
            autoSurveyAccMax = intent.getDoubleExtra(AutoSurveyController.EXTRA_ACC_MAX, autoSurveyAccMax)
            autoSurveyUseSpeed = intent.getBooleanExtra(AutoSurveyController.EXTRA_USE_SPEED, autoSurveyUseSpeed)
            autoSurveySpeedMin = intent.getDoubleExtra(AutoSurveyController.EXTRA_SPEED_MIN, autoSurveySpeedMin)

            when (cmd) {
                AutoSurveyController.CMD_START -> startAutoSurvey()
                AutoSurveyController.CMD_PAUSE -> pauseAutoSurvey()
                AutoSurveyController.CMD_STOP -> stopAutoSurvey()
            }
        }
    }

    /* =========================
       AREA / SUPERFICIE survey
       ========================= */

    private var areaSurveyRunning = false
    private var areaSurveyPaused = false
    private var areaSurveyMode = "DIST" // DIST / TIME
    private var areaSurveyDistMeters = 5.0
    private var areaSurveyTimeMs = 2000L
    private var areaSurveyUseAcc = false
    private var areaSurveyAccMax = 0.30
    private var areaSurveyUseSpeed = false
    private var areaSurveySpeedMin = 0.20
    private var areaSurveyCounter = 1
    private var lastAreaSavedInct: InctResult? = null
    private var lastAreaSavedAtMs: Long = 0L

    private val areaSurveyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val cmd = intent.getStringExtra(AreaSurveyController.EXTRA_CMD) ?: return
            // update params always
            areaSurveyMode = intent.getStringExtra(AreaSurveyController.EXTRA_MODE) ?: areaSurveyMode
            areaSurveyDistMeters = intent.getDoubleExtra(AreaSurveyController.EXTRA_DIST_M, areaSurveyDistMeters)
            areaSurveyTimeMs = intent.getLongExtra(AreaSurveyController.EXTRA_TIME_MS, areaSurveyTimeMs)
            areaSurveyUseAcc = intent.getBooleanExtra(AreaSurveyController.EXTRA_USE_ACC, areaSurveyUseAcc)
            areaSurveyAccMax = intent.getDoubleExtra(AreaSurveyController.EXTRA_ACC_MAX, areaSurveyAccMax)
            areaSurveyUseSpeed = intent.getBooleanExtra(AreaSurveyController.EXTRA_USE_SPEED, areaSurveyUseSpeed)
            areaSurveySpeedMin = intent.getDoubleExtra(AreaSurveyController.EXTRA_SPEED_MIN, areaSurveySpeedMin)
            val startIdx = intent.getIntExtra(AreaSurveyController.EXTRA_START_INDEX, 1)

            when (cmd) {
                AreaSurveyController.CMD_START -> startAreaSurvey(startIdx)
                AreaSurveyController.CMD_PAUSE -> pauseAreaSurvey()
                AreaSurveyController.CMD_STOP -> stopAreaSurvey()
                AreaSurveyController.CMD_ADD -> addAreaPointOnce()
                AreaSurveyController.CMD_CLOSE -> closeAreaSurvey()
            }
        }
    }
    private var simRunning = false
    private var simThread: Thread? = null
    private var isGnssConnected = false

    // Wizard: BASE -> Mise en station -> ROVER
    private var onStationSetupComplete: (() -> Unit)? = null

    /* ---- Enregistrement ---- */
    private lateinit var fileManager: InctFileManager
    private val levePointStore by lazy { LevePointStore(contentResolver) }
    private var recording = false
    private var rootUri: Uri? = null
    private var chantierName: String? = null
    private var projectName: String? = null
    private var levesUri: Uri? = null
    private var reperesUri: Uri? = null

    // --- CRS verrouillé par levé (pas par projet) ---
    /** Storage CRS for x/y/z columns in the levé CSV (locked by CSV metadata). */
    /** CRS used for UI display (driven by CSV metadata line '# CRS: ...'). */
    private var activeLeveCrs: ProjectCrs? = null

    // --- Réglages du levé (persistés dans *_leve_settings.json) ---
    private var activeLeveSettingsUri: Uri? = null
    private var activeLeveSettings: LeveSettings? = null

    // --- Mise en station (transformation appliquée live) ---
    private var stationReady: Boolean = false
    private var stationDx: Double = 0.0
    private var stationDy: Double = 0.0
    private var stationDz: Double = 0.0

    // ✅ Dossier chantier SAF (DocumentFile) — utilisé pour créer les fichiers levé dans le bon dossier
    private var chantierDirDoc: DocumentFile? = null

    // ✅ ID topo (prefix + compteur)
    private var pointPrefix: String = "P"
    private var pointIndex: Int = 1
    private var pointId: String = "P1"

    // Cache of IDs already used in the currently opened levé (to avoid duplicates).
    private val usedPointIds: MutableSet<String> = mutableSetOf()

    /* ---- Audio ---- */
    private lateinit var tts: TextToSpeech
    private var toneGenerator: ToneGenerator? = null
    private var soundEnabled = true
    private var voiceEnabled = true
    private var antennaHeightMeters = 0.0
    private var desiredPrecisionMeters = 0.050
    private var precisionLockEnabled = false

    // ✅ guidage vocal (implantation)
    private var guidanceVoiceEnabled = true
    private var guidanceLocale = Locale.FRENCH
    private var guidanceStrings = GuidanceStrings.french()

    // ✅ alternance : 1 fois ΔY, 1 fois ΔX (pas au même moment)
    // 0 = prochain message LR, 1 = prochain message FB
    private var guidanceNextAxis = 0
    private var guidancePlayer: MediaPlayer? = null
    private var lastGuidanceKey: GuidanceKey? = null
    private var lastGuidanceSpokenAtElapsed = 0L
    private var nextGuidanceSpeakAtElapsed = 0L
    private var okGuidanceArmed = true
    private val guidanceUpdateIntervalMs = 3000L
    private var lastGuidanceUpdateElapsed = 0L
    private var lastGuidanceTargetKey: String? = null

    /* ---- Commandes vocales ---- */
    private var speechRecognizer: SpeechRecognizer? = null
    private var isVoiceListening = false
    private var voiceModeEnabled = false
    private var voiceState = VoiceState.IDLE
    private var voiceLangMode = VoiceLangMode.AUTO
    private var voiceLangInitialized = false
    private var lastVoiceLanguage: VoiceLanguage? = null
    private var lastSpokenMessage: String? = null
    private var lastSpokenAtElapsed = 0L
    private var lastTtsAtElapsed = 0L
    private var lastNotUnderstoodAtElapsed = 0L
    private var voiceErrorSpeakStreak = 0
    private var lastVoiceZoomUpdateElapsed = 0L
    private var lastVoiceZoomSpeakElapsed = 0L
    private var lastVoiceZoomHintElapsed = 0L
    private val ttsGuardMs = 800L
    private val voiceLangOptions = listOf("AUTO", "FR", "AR")
    private val voiceRestartHandler = Handler(Looper.getMainLooper())
    private val voiceRestartRunnable = Runnable {
        if (voiceModeEnabled) startVoiceCommandListening(autoStart = true)
    }
    private var voiceRestartDelayFailMs = 3000L
    private var voiceRestartDelaySuccessMs = 600L
    private var defaultVoiceButtonTint: ColorStateList? = null
    private var defaultVoiceButtonTextColor: Int? = null
    private var voiceZoomFollowEnabled = false
    private var voiceZoomHeightMeters = VOICE_ZOOM_DEFAULT_METERS
    private var voiceZoomCenterTargetPending = false

    /* ---- UI Screens ---- */
    private lateinit var screenNav: ScrollView
    private lateinit var screenGestion: ScrollView
    private lateinit var screenProgrammesTopo: ScrollView
    private lateinit var screenSurvey: ScrollView
    private lateinit var screenMap: FrameLayout
    private lateinit var screenImplantation: ScrollView

    /* ---- UI Programmes TOPO ---- */
    private lateinit var btnBackProgrammesTopo: com.google.android.material.button.MaterialButton
    private lateinit var tileLeve: View
    private lateinit var tileEditPoints: View
    private lateinit var tileImplantPt: View
    private lateinit var tileIntersect: View
    private lateinit var tileRefLigne: View
    private lateinit var tileImplantLigne: View
    private lateinit var tilePkDeport: View
    private lateinit var tileAuto: View
    private lateinit var tileSuperficie: View
    private lateinit var tileTransfo2d: View
    private lateinit var tileImport: View
    private lateinit var tileExport: View

    /* ---- UI Nav ---- */
    private lateinit var bannerNav: TextView
    private lateinit var txtGnssStatusNav: TextView
    private lateinit var txtXinctNav: TextView
    private lateinit var txtYinctNav: TextView
    private lateinit var txtZinctNav: TextView
    private lateinit var txtSatNav: TextView
    private lateinit var txtPrecNav: TextView
    private lateinit var txtSimulationNav: TextView
    private lateinit var txtActiveLeveNav: TextView
    private lateinit var btnNavNextNavigation: ImageButton
    private lateinit var btnNavPrevGestion: ImageButton
    private lateinit var btnNavNextGestion: ImageButton
    private lateinit var btnNavPrevLeve: ImageButton
    private lateinit var btnNavNextLeve: ImageButton
    private lateinit var btnNavPrevMap: ImageButton
    private lateinit var btnNavNextMap: ImageButton
    private lateinit var btnNavPrevImplantation: ImageButton
    private lateinit var btnNavNextImplantation: ImageButton

    /* ---- UI Survey ---- */
    private lateinit var bannerLeve: TextView
    private lateinit var txtLeveLocked: TextView
    private lateinit var txtFixSurvey: TextView
    private lateinit var txtRecStatus: TextView
    private lateinit var txtFileName: TextView
    private lateinit var txtCounter: TextView
    private lateinit var txtPrecisionStatusSurvey: TextView
    private lateinit var txtSurveyLeveName: TextView
    private lateinit var txtSurveyCrsDisplay: TextView
    private var defaultOpenLeveBackground: Drawable? = null
    private var defaultFileNameColor: Int? = null
    private lateinit var btnCreateLeve: Button
    private lateinit var btnOpenLeve: Button
    private lateinit var btnImportLeveGestion: Button

    // =========================
    // IMPORT LEVÉ (CSV/TXT)
    // =========================

    private enum class ImportFileType { XY, LLH }
    private enum class ImportDestCrs { INCT_UTM, WGS84_UTM, WGS84_LLH }

    private data class ImportConfig(
        val surveyName: String,
        val fileType: ImportFileType,
        val destCrs: ImportDestCrs,
        val utmZone: Int?,
        val utmHemisphereNorth: Boolean?
    )

    private var pendingImportConfig: ImportConfig? = null

    private val importFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val cfg = pendingImportConfig
        if (uri == null || cfg == null) return@registerForActivityResult
        showImportPreviewDialog(cfg, uri)
    }

    // =========================
    // INTERSECT (A-B) x (C-D)
    // =========================

    private val intersectLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res: ActivityResult ->
        if (res.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = res.data ?: return@registerForActivityResult
        handleIntersectResult(data)
    }
    private lateinit var btnStationSetupSurvey: Button
    private lateinit var btnSavePoint: Button
    private lateinit var btnTopoPlanSurvey: Button
    private lateinit var btnVoiceSurvey: Button
    private lateinit var btnScanGnss: Button
    private lateinit var btnConnectGnss: Button
    private lateinit var btnDisconnectGnss: Button
    private lateinit var btnSimToggle: Button
    private lateinit var btnAntennaHeightSurvey: Button
    private lateinit var btnPrecisionSurvey: Button
    private lateinit var listGnssDevices: ListView
    private lateinit var gnssAdapter: ArrayAdapter<String>
    private lateinit var edtPointId: EditText
    private lateinit var btnApplyId: Button
    private lateinit var spinnerPointTypeSurvey: Spinner
    private lateinit var spinnerVoiceLangMode: Spinner
    private var selectedPointTypeLabel: String = "Aucun"
    private lateinit var btnQuitNavigation: Button
    private lateinit var btnQuitGestion: Button
    private lateinit var btnExportDxfGestion: Button
    private lateinit var btnQuitLeve: Button
    private lateinit var btnQuitImplantation: Button
    private lateinit var btnQuitMap: Button
    private lateinit var switchVoiceSurvey: SwitchMaterial
    private lateinit var switchSoundSurvey: SwitchMaterial

    /* ---- UI Map ---- */
    private lateinit var mapView: MapView
    private lateinit var bannerMap: TextView
    private lateinit var txtMapTitle: TextView
    private lateinit var txtMapFix: TextView
    private lateinit var txtMapPrecision: TextView
    private lateinit var txtMapSats: TextView
    private lateinit var txtMapInct: TextView
    private lateinit var txtMapRec: TextView
    private lateinit var txtMapTarget: TextView
    private lateinit var txtMapDistance: TextView
    private lateinit var txtMapDeltaFB: TextView
    private lateinit var txtMapDeltaLR: TextView
    private lateinit var txtMapOk: TextView
    private lateinit var layoutMapDelta: View
    private lateinit var layoutMapDelta2: View
    private lateinit var txtMapLocked: TextView
    private lateinit var layoutMapActionsSurvey: LinearLayout
    private lateinit var layoutMapActionsImplantation: LinearLayout
    private lateinit var btnMapAddPoint: View
    private lateinit var btnMapCenterRover: View
    private lateinit var btnMapToggleTrack: View
    private lateinit var btnMapFollow: View
    private lateinit var btnMapMore: View
    private lateinit var btnMapImplantCenterRover: View
    private lateinit var btnMapImplantCenterTarget: View
    private lateinit var btnMapImplantToggleCircle: View
    private lateinit var btnMapImplantFollow: View
    private lateinit var btnMapImplantMore: View

    /* ---- UI Implantation ---- */
    private lateinit var txtImplantationLocked: TextView
    private lateinit var txtImplantationCrsDisplay: TextView
    private lateinit var txtImplantationMode: TextView
    private lateinit var layoutRefLine: View
    private lateinit var txtRefLineSummary: TextView
    private lateinit var btnChooseRefA: Button
    private lateinit var btnChooseRefB: Button
    private lateinit var edtRefLineFixedOffset: EditText
    private lateinit var rgRefLineSide: RadioGroup
    private lateinit var btnSelectImplantationTarget: Button
    private lateinit var btnChooseTargetOnMap: Button
    private lateinit var txtImplantationTargetSummary: TextView
    private lateinit var txtImplantationCurrentInct: TextView
    private lateinit var txtImplantationTargetInct: TextView
    private lateinit var txtImplantationDeltas: TextView
    private lateinit var txtImplantationDistances: TextView
    private lateinit var imgForward: ImageView
    private lateinit var txtForwardCm: TextView
    private lateinit var imgLeft: ImageView
    private lateinit var txtLeftCm: TextView
    private lateinit var imgUpDown: ImageView
    private lateinit var txtDzCm: TextView
    private lateinit var imgCompassArrow: ImageView
    private lateinit var layoutCompassArrow: FrameLayout
    private lateinit var txtCompassDistance: TextView

    /* ---- Map overlays ---- */
private lateinit var mapController: MapController
private var mapMode: MapMode = MapMode.SURVEY
private var currentScreen: Screen = Screen.NAVIGATION
private var showTargetCircle = true

    // Implantation targets (LEVE / REPERE / etc.) are loaded via TargetRepository.
    private lateinit var targetRepository: TargetRepository

    /**
     * ✅ GUIDAGE VOCAL :
     * - UNE SEULE consigne à la fois
     * - Alternance : (G/D) puis (ΔX) puis (G/D) ...
     * - Pas de Monte/Descends EN AUDIO (mais ΔZ reste affiché dans l'UI)
     */
    private data class GuidanceStrings(
        val forward: String,
        val back: String,
        val left: String,
        val right: String,
        val reached: String,
        val targetSelected: String
    ) {
        companion object {
            fun arabic() = GuidanceStrings(
                forward = "تقدم",
                back = "تراجع",
                left = "يسار",
                right = "يمين",
                reached = "تمام",
                targetSelected = "تم اختيار الهدف"
            )

            fun french() = GuidanceStrings(
                forward = "Avant",
                back = "Arrière",
                left = "À gauche",
                right = "À droite",
                reached = "OK",
                targetSelected = "Cible sélectionnée"
            )
        }
    }

    private var selectedTarget: TargetPoint? = null


private enum class ImplantMode { POINT, REF_LINE }
private var implantMode: ImplantMode = ImplantMode.POINT

// Reference line mode (A-B)
private var refLineAId: String? = null
private var refLineBId: String? = null
private var refLineFixedOffsetMeters: Double = 0.0
private var refLineFixedOffsetLeft: Boolean = true

    private var lastImplantDhMeters: Double? = null
    private var lastImplantDxMeters: Double? = null
    private var lastImplantDyMeters: Double? = null
    private var lastImplantOk: Boolean = false

    private var prevInctX: Double? = null
    private var prevInctY: Double? = null
    private var lastHeadingRad: Double = 0.0
    private var headingValid = false
    private var selectedDeviceAddress: String? = null

    private val gnssUiHandler = Handler(Looper.getMainLooper())

    private var uiReady: Boolean = false
    private var gnssUiReason: String = "init"
    private val gnssUiRunnable = Runnable {
        updateNavUI()
        updateSurveyUI()
        if (::btnConnectGnss.isInitialized && ::btnDisconnectGnss.isInitialized) {
            btnConnectGnss.isEnabled = !isGnssConnected && !simRunning
            btnDisconnectGnss.isEnabled = isGnssConnected
        }
    }

    
private fun setScanUi(scanning: Boolean) {
    if (!::btnScanGnss.isInitialized) return
    runOnUiThread {
        btnScanGnss.isEnabled = !scanning
        btnScanGnss.alpha = if (scanning) 0.75f else 1f
        btnScanGnss.text = if (scanning) "Scan en cours…" else "Scanner GNSS"
    }
}

private fun pulseView(v: View) {
    v.animate().cancel()
    v.scaleX = 0.96f
    v.scaleY = 0.96f
    v.animate()
        .scaleX(1f)
        .scaleY(1f)
        .setDuration(160)
        .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
        .start()
}

    /* =========================
       LIFECYCLE
       ========================= */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main_host)

        ensureScreenFragmentsAttached(savedInstanceState)

        setupNmeaParser()


        setupGnssStateStore()
        // Auto Survey command channel (from AutoSurveyActivity)
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(autoSurveyReceiver, IntentFilter(AutoSurveyController.ACTION_AUTO_SURVEY_CMD))
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(areaSurveyReceiver, IntentFilter(AreaSurveyController.ACTION_AREA_SURVEY_CMD))
        autoSurveyHandler.post(autoSurveyTick)

        val chantier = intent.getStringExtra(ChantierHomeActivity.EXTRA_CHANTIER_NAME)
        val chantierDirUri = intent.getStringExtra(ChantierHomeActivity.EXTRA_CHANTIER_DIR_URI)
            ?.let { runCatching { it.toUri() }.getOrNull() }
        val levesUriStr = intent.getStringExtra(ChantierHomeActivity.EXTRA_LEVES_URI)
        val reperesUriStr = intent.getStringExtra(ChantierHomeActivity.EXTRA_REPERES_URI)
        val levesUri = levesUriStr?.let { runCatching { it.toUri() }.getOrNull() }
        val reperesUri = reperesUriStr?.let { runCatching { it.toUri() }.getOrNull() }
        if (reperesUri == null || chantier.isNullOrBlank()) {
            Toast.makeText(this, "Chantier non défini", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val docsUriStr = prefs.getString(ChantierHomeActivity.KEY_DOCS_TREE_URI, null)
        val docsUri = docsUriStr?.let { runCatching { it.toUri() }.getOrNull() }
        if (docsUri == null || !hasPersistedPermission(docsUri)) {
            Toast.makeText(this, "Erreur chantier", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val selectedProject = prefs.getString(ChantierHomeActivity.KEY_PROJECT_NAME, null)?.trim().orEmpty()
        if (selectedProject.isBlank()) {
            Toast.makeText(this, "Erreur chantier", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val docsRoot = DocumentFile.fromTreeUri(this, docsUri)
        val topoDir = docsRoot?.findFile("TOPOGRAPHIE")
        val projectDir = topoDir?.findFile(selectedProject)
        val chantierDirFromIntent = chantierDirUri?.let { uri ->
            DocumentFile.fromTreeUri(this, uri) ?: DocumentFile.fromSingleUri(this, uri)
        }
        val chantierDir = chantierDirFromIntent ?: projectDir?.findFile(chantier)

        if (chantierDir == null) {
            Toast.makeText(this, "Erreur chantier", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // ✅ garder le DocumentFile du dossier chantier : c'est lui qui servira à createFile() pour le levé
        chantierDirDoc = chantierDir

        // ✅ garder aussi l'URI SAF du dossier chantier
        rootUri = chantierDir.uri

        chantierName = chantier
        projectName = selectedProject
        this.levesUri = levesUri
        this.reperesUri = reperesUri

        fileManager = InctFileManager(this)
        targetRepository = TargetRepository(contentResolver)

        deferUiInit(savedInstanceState)

    }

    private fun hasPersistedPermission(uri: Uri): Boolean {
        val targetId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        return contentResolver.persistedUriPermissions.any { perm ->
            if (!perm.isReadPermission || !perm.isWritePermission) return@any false
            val permId = runCatching { DocumentsContract.getTreeDocumentId(perm.uri) }.getOrNull()
            when {
                perm.uri == uri -> true
                targetId != null && permId != null && targetId.startsWith(permId) -> true
                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) mapView.onResume()
        if (voiceModeEnabled) {
            startVoiceCommandListening(autoStart = true)
        }
        if (::btScanner.isInitialized) btScanner.register()
    }

    private fun hasBtScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun hasBtConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    override fun onPause() {
        super.onPause()
        if (::mapView.isInitialized) mapView.onPause()
        stopProximityBeep()
        releaseGuidancePlayer()
        resetGuidanceSchedule()
        stopVoiceListening()
        voiceRestartHandler.removeCallbacks(voiceRestartRunnable)

        if (::btScanner.isInitialized) {
            btScanner.stopScan()
            btScanner.unregister()
        }
    }

    override fun onBackPressed() {
        if (currentScreen == Screen.NAVIGATION) {
            super.onBackPressed()   // laisse Android fermer l’activité
            return
        }
        navigatePrev()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_VOICE_MODE_ENABLED, voiceModeEnabled)
        outState.putString(KEY_VOICE_STATE, voiceState.name)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(autoSurveyReceiver)
        }
        runCatching {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(areaSurveyReceiver)
        }
        autoSurveyHandler.removeCallbacksAndMessages(null)
        stopNmeaSimulation()
        stopProximityBeep()
        releaseGuidancePlayer()
        resetGuidanceSchedule()
        try {
            if (recording) fileManager.close()
        } catch (_: Exception) {
        }
        try {
            disconnectBt()
        } catch (_: Exception) {
        }
        try {
            tts.shutdown()
        } catch (_: Exception) {
        }
        releaseToneGenerator()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    /* =========================
       INIT UI
       ========================= */


    private fun ensureScreenFragmentsAttached(savedInstanceState: Bundle?) {
        // NOTE:
        // - Après refactor "1 écran = 1 fragment", il arrive que savedInstanceState != null
        //   mais que les fragments écran ne soient pas restaurés (ex: ancien état / crash / container id différent).
        // - Dans ce cas, les findViewById(screen_*) renvoient null -> lateinit non initialisé.
        // => On vérifie la présence réelle des fragments, sinon on recrée le set complet.

        val fm = supportFragmentManager
        val tags = listOf(
            "screen_navigation",
            "screen_programmes_topo",
            "screen_gestion",
            "screen_survey",
            "screen_implantation",
            "screen_map",
        )

        fun hasAllFragments(): Boolean = tags.all { fm.findFragmentByTag(it) != null }

        // Tentative: fragments restaurés automatiquement
        if (savedInstanceState != null && hasAllFragments()) {
            fm.executePendingTransactions()
            // Si leurs views sont déjà là, parfait.
            val viewsReady = tags.all { fm.findFragmentByTag(it)?.view != null }
            if (viewsReady) return
        }

        // Sinon on repart proprement (supprime ce qui existe et recrée)
        fm.beginTransaction().apply {
            setReorderingAllowed(true)
            tags.forEach { tag ->
                fm.findFragmentByTag(tag)?.let { remove(it) }
            }
            add(R.id.fragment_container, NavigationFragment(), "screen_navigation")
            add(R.id.fragment_container, ProgrammesTopoFragment(), "screen_programmes_topo")
            add(R.id.fragment_container, GestionFragment(), "screen_gestion")
            add(R.id.fragment_container, SurveyFragment(), "screen_survey")
            add(R.id.fragment_container, ImplantationFragment(), "screen_implantation")
            add(R.id.fragment_container, MapFragment(), "screen_map")
        }.commitNow()

        fm.executePendingTransactions()
    }
    
    private fun deferUiInit(savedInstanceState: Bundle?) {
        // Les views des fragments peuvent ne pas être disponibles immédiatement dans onCreate().
        // On reporte l'initialisation UI au prochain "frame" (après inflation/attachement des fragments).
        window.decorView.post {
            // Sécurité : si pour une raison quelconque les fragments n'ont pas de view, on force un attach.
            if (supportFragmentManager.findFragmentByTag("screen_navigation")?.view == null) {
                ensureScreenFragmentsAttached(null)
            }

            initUI()

            // State restoration (depends on views)
            if (savedInstanceState != null) {
                voiceModeEnabled = savedInstanceState.getBoolean(KEY_VOICE_MODE_ENABLED, false)
                voiceState = runCatching {
                    VoiceState.valueOf(savedInstanceState.getString(KEY_VOICE_STATE, VoiceState.IDLE.name))
                }.getOrDefault(VoiceState.IDLE)
                updateVoiceModeUi()
            }

            initBluetooth()
            initMap()
            refreshMarkers()

            tts = TextToSpeech(this, this)
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)

            uiReady = true
            requestPermissions()

            // ✅ affiche l’ID initial
            refreshPointIdFieldIfNotEditing()
            showNavigation()

            // Force one UI refresh after everything is ready
            refreshGnssUi("ui_ready")
        }
    }

private fun initUI() {
        // Les écrans vivent dans des fragments : on récupère les roots de façon "safe".
        screenNav = findViewById<ScrollView?>(R.id.screen_navigation)
            ?: (supportFragmentManager.findFragmentByTag("screen_navigation")?.view as? ScrollView)
            ?: error("screen_navigation introuvable")
        screenGestion = findViewById<ScrollView?>(R.id.screen_gestion)
            ?: (supportFragmentManager.findFragmentByTag("screen_gestion")?.view as? ScrollView)
            ?: error("screen_gestion introuvable")
        screenProgrammesTopo = findViewById<ScrollView?>(R.id.screen_programmes_topo)
            ?: (supportFragmentManager.findFragmentByTag("screen_programmes_topo")?.view as? ScrollView)
            ?: error("screen_programmes_topo introuvable")
        screenSurvey = findViewById<ScrollView?>(R.id.screen_survey)
            ?: (supportFragmentManager.findFragmentByTag("screen_survey")?.view as? ScrollView)
            ?: error("screen_survey introuvable")
        screenMap = findViewById<FrameLayout?>(R.id.screen_map)
            ?: (supportFragmentManager.findFragmentByTag("screen_map")?.view as? FrameLayout)
            ?: error("screen_map introuvable")
        screenImplantation = findViewById<ScrollView?>(R.id.screen_implantation)
            ?: (supportFragmentManager.findFragmentByTag("screen_implantation")?.view as? ScrollView)
            ?: error("screen_implantation introuvable")

        // Programmes TOPO
        btnBackProgrammesTopo = findViewById(R.id.btnBackProgrammesTopo)
        tileLeve = findViewById(R.id.tileLeve)
        tileEditPoints = findViewById(R.id.tileEditPoints)
        tileImplantPt = findViewById(R.id.tileImplantPt)
        tileIntersect = findViewById(R.id.tileIntersect)
        tileRefLigne = findViewById(R.id.tileRefLigne)
        tileImplantLigne = findViewById(R.id.tileImplantLigne)
        tilePkDeport = findViewById(R.id.tilePkDeport)
        tileAuto = findViewById(R.id.tileAuto)
        tileSuperficie = findViewById(R.id.tileSuperficie)
        tileTransfo2d = findViewById(R.id.tileTransfo2d)
        tileImport = findViewById(R.id.tileImport)
        tileExport = findViewById(R.id.tileExport)

        // Nav
        bannerNav = screenNav.findViewById(R.id.bannerNav)
        txtGnssStatusNav = screenNav.findViewById(R.id.txtGnssStatusNav)
        txtXinctNav = screenNav.findViewById(R.id.txtXinctNav)
        txtYinctNav = screenNav.findViewById(R.id.txtYinctNav)
        txtZinctNav = screenNav.findViewById(R.id.txtZinctNav)

        txtSatNav = screenNav.findViewById(R.id.txtSatellitesNav)
        txtPrecNav = screenNav.findViewById(R.id.txtPrecisionNav)
        txtSimulationNav = screenNav.findViewById(R.id.txtSimulationNav)
        txtActiveLeveNav = screenNav.findViewById(R.id.txtActiveLeveNav)
        btnNavNextNavigation = screenNav.findViewById(R.id.btnNavNextNavigation)
        btnNavPrevGestion = findViewById(R.id.btnNavPrevGestion)
        btnNavNextGestion = findViewById(R.id.btnNavNextGestion)
        btnNavPrevLeve = findViewById(R.id.btnNavPrevLeve)
        btnNavNextLeve = findViewById(R.id.btnNavNextLeve)
        btnNavPrevMap = findViewById(R.id.btnNavPrevMap)
        btnNavNextMap = findViewById(R.id.btnNavNextMap)
        btnNavPrevImplantation = findViewById(R.id.btnNavPrevImplantation)
        btnNavNextImplantation = findViewById(R.id.btnNavNextImplantation)

        // Survey
        bannerLeve = findViewById(R.id.bannerLeve)
        txtLeveLocked = findViewById(R.id.txtLeveLocked)
        txtFixSurvey = findViewById(R.id.txtFixSurvey)

        txtRecStatus = findViewById(R.id.txtRecordStatusSurvey)
        txtFileName = findViewById(R.id.txtFileNameSurvey)
        txtCounter = findViewById(R.id.txtCounterSurvey)
        txtPrecisionStatusSurvey = findViewById(R.id.txtPrecisionStatusSurvey)

        // ⚠️ Avec l'UI découpée en Fragments, on récupère ces vues depuis le root du screen (sinon findViewById peut renvoyer null).
        btnCreateLeve = screenGestion.findViewById(R.id.btnCreateLeve)
        btnOpenLeve = screenGestion.findViewById(R.id.btnOpenLeveSurvey)
        btnImportLeveGestion = screenGestion.findViewById(R.id.btnImportLeveGestion)
        btnStationSetupSurvey = findViewById(R.id.btnStationSetupSurvey)
        btnSavePoint = findViewById(R.id.btnSavePointSurvey)
        btnTopoPlanSurvey = findViewById(R.id.btnTopoPlanSurvey)
        btnVoiceSurvey = findViewById(R.id.btnVoiceSurvey)
        btnScanGnss = screenNav.findViewById(R.id.btnScanGnss)
        btnConnectGnss = screenNav.findViewById(R.id.btnConnectGnss)
        btnDisconnectGnss = screenNav.findViewById(R.id.btnDisconnectGnss)
        btnSimToggle = screenNav.findViewById(R.id.btnSimToggle)
        btnAntennaHeightSurvey = findViewById(R.id.btnAntennaHeightSurvey)
        btnPrecisionSurvey = findViewById(R.id.btnPrecisionSurvey)
        listGnssDevices = screenNav.findViewById(R.id.listGnssDevices)
        edtPointId = findViewById(R.id.edtPointIdSurvey)
        btnApplyId = findViewById(R.id.btnApplyIdSurvey)
        spinnerPointTypeSurvey = findViewById(R.id.spinnerPointTypeSurvey)
        spinnerVoiceLangMode = findViewById(R.id.spinnerVoiceLangMode)
        switchVoiceSurvey = findViewById(R.id.switchVoiceSurvey)
        switchSoundSurvey = findViewById(R.id.switchSoundSurvey)
        btnQuitNavigation = screenNav.findViewById(R.id.btnQuitNavigation)
        btnQuitGestion = findViewById(R.id.btnQuitGestion)
        btnExportDxfGestion = findViewById(R.id.btnExportDxfGestion)
        btnQuitLeve = findViewById(R.id.btnQuitLeve)
        btnQuitImplantation = findViewById(R.id.btnQuitImplantation)
        btnQuitMap = findViewById(R.id.btnQuitMap)
        defaultOpenLeveBackground = btnOpenLeve.background?.constantState?.newDrawable()
        defaultFileNameColor = txtFileName.currentTextColor
        defaultVoiceButtonTint = btnVoiceSurvey.backgroundTintList
        defaultVoiceButtonTextColor = btnVoiceSurvey.currentTextColor

        txtSurveyLeveName = screenGestion.findViewById(R.id.txtSurveyLeveName)
        txtSurveyCrsDisplay = screenGestion.findViewById(R.id.txtSurveyCrsDisplay)

        val typeLabels = listOf("Aucun", "Arbre", "Poteau", "Regard", "Borne", "Avaloir")
        val typeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, typeLabels)
        spinnerPointTypeSurvey.adapter = typeAdapter
        spinnerPointTypeSurvey.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedPointTypeLabel = typeLabels.getOrNull(position) ?: "Aucun"
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedPointTypeLabel = "Aucun"
            }
        }

        val voiceLangAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voiceLangOptions)
        spinnerVoiceLangMode.adapter = voiceLangAdapter
        spinnerVoiceLangMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedValue = voiceLangOptions.getOrNull(position) ?: VoiceLangMode.AUTO.prefValue
                val newMode = VoiceLangMode.fromPref(selectedValue)
                voiceLangMode = newMode
                if (!voiceLangInitialized) return
                lastVoiceLanguage = null
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_VOICE_LANG_MODE, newMode.prefValue)
                    .apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        updateVoiceModeUi()

        // Map UI
        mapView = findViewById(R.id.mapView)
        bannerMap = findViewById(R.id.bannerMap)
        txtMapTitle = findViewById(R.id.txtMapTitle)
        txtMapFix = findViewById(R.id.txtMapFix)
        txtMapPrecision = findViewById(R.id.txtMapPrecision)
        txtMapSats = findViewById(R.id.txtMapSats)
        txtMapInct = findViewById(R.id.txtMapInct)
        txtMapRec = findViewById(R.id.txtMapRec)
        txtMapTarget = findViewById(R.id.txtMapTarget)
        txtMapDistance = findViewById(R.id.txtMapDistance)
        txtMapDeltaFB = findViewById(R.id.txtMapDeltaFB)
        txtMapDeltaLR = findViewById(R.id.txtMapDeltaLR)
        txtMapOk = findViewById(R.id.txtMapOk)
        layoutMapDelta = findViewById(R.id.layoutMapDelta)
        layoutMapDelta2 = findViewById(R.id.layoutMapDelta2)
        txtMapLocked = findViewById(R.id.txtMapLocked)
        layoutMapActionsSurvey = findViewById(R.id.layoutMapActionsSurvey)
        layoutMapActionsImplantation = findViewById(R.id.layoutMapActionsImplantation)
        btnMapAddPoint = findViewById(R.id.btnMapAddPoint)
        btnMapCenterRover = findViewById(R.id.btnMapCenterRover)
        btnMapToggleTrack = findViewById(R.id.btnMapToggleTrack)
        btnMapFollow = findViewById(R.id.btnMapFollow)
        btnMapMore = findViewById(R.id.btnMapMore)
        btnMapImplantCenterRover = findViewById(R.id.btnMapImplantCenterRover)
        btnMapImplantCenterTarget = findViewById(R.id.btnMapImplantCenterTarget)
        btnMapImplantToggleCircle = findViewById(R.id.btnMapImplantToggleCircle)
        btnMapImplantFollow = findViewById(R.id.btnMapImplantFollow)
        btnMapImplantMore = findViewById(R.id.btnMapImplantMore)

mapController = MapController(
    context = this,
    packageName = packageName,
    mapView = mapView,
    contentResolver = contentResolver,
    levePointStore = levePointStore,
    isImplantationModeProvider = { mapMode == MapMode.IMPLANTATION },
    hasActiveLeve = { hasActiveLeve() },
    onSurveyLongPress = { gp -> promptMapPointId(isLeve = false, touchPoint = gp) },
    onImplantLongPress = { gp -> selectTargetFromMapPress(gp) },
    onFollowChangedByUserPan = { enabled ->
        try { setToolbarToggleState(btnMapFollow, enabled) } catch (_: Exception) {}
        try { setToolbarToggleState(btnMapImplantFollow, enabled) } catch (_: Exception) {}
    }
)

        // Implantation UI
        txtImplantationLocked = findViewById(R.id.txtImplantationLocked)
        txtImplantationCrsDisplay = findViewById(R.id.txtImplantationCrsDisplay)
        txtImplantationMode = findViewById(R.id.txtImplantationMode)
        layoutRefLine = findViewById(R.id.layoutRefLine)
        txtRefLineSummary = findViewById(R.id.txtRefLineSummary)
        btnChooseRefA = findViewById(R.id.btnChooseRefA)
        btnChooseRefB = findViewById(R.id.btnChooseRefB)
        edtRefLineFixedOffset = findViewById(R.id.edtRefLineFixedOffset)
        rgRefLineSide = findViewById(R.id.rgRefLineSide)
        btnSelectImplantationTarget = findViewById(R.id.btnSelectImplantationTarget)
        btnChooseTargetOnMap = findViewById(R.id.btnChooseTargetOnMap)
        txtImplantationTargetSummary = findViewById(R.id.txtImplantationTargetSummary)
        txtImplantationCurrentInct = findViewById(R.id.txtImplantationCurrentInct)
        txtImplantationTargetInct = findViewById(R.id.txtImplantationTargetInct)
        txtImplantationDeltas = findViewById(R.id.txtImplantationDeltas)
        txtImplantationDistances = findViewById(R.id.txtImplantationDistances)
        imgForward = findViewById(R.id.imgForward)
        txtForwardCm = findViewById(R.id.txtForwardCm)
        imgLeft = findViewById(R.id.imgLeft)
        txtLeftCm = findViewById(R.id.txtLeftCm)
        imgUpDown = findViewById(R.id.imgUpDown)
        txtDzCm = findViewById(R.id.txtDzCm)
        layoutCompassArrow = findViewById(R.id.layoutCompassArrow)
        imgCompassArrow = findViewById(R.id.imgCompassArrow)
        txtCompassDistance = findViewById(R.id.txtCompassDistance)

        gnssAdapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_list_item_1,
            deviceNames
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val textView = view as TextView
                val device = allDevices.getOrNull(position)
                val isSelected = device?.address == selectedDeviceAddress
                textView.setTypeface(
                    textView.typeface,
                    if (isSelected) Typeface.BOLD else Typeface.NORMAL
                )
                val textColor = if (isSelected) {
                    ContextCompat.getColor(context, R.color.guid_approach)
                } else {
                    ContextCompat.getColor(context, android.R.color.black)
                }
                textView.setTextColor(textColor)
                return view
            }
        }
        listGnssDevices.adapter = gnssAdapter
        // Navigation list selection + next/quit are handled by NavigationFragment

        // Map screen navigation buttons are handled by MapFragment
        btnNavPrevImplantation.setOnClickListener {
            Toast.makeText(this, "Écran précédent", Toast.LENGTH_SHORT).show()
            navigatePrev()
        }
        btnNavNextImplantation.setOnClickListener {
            Toast.makeText(this, "Écran suivant", Toast.LENGTH_SHORT).show()
            navigateNext()
        }

        // Programmes TOPO
        btnBackProgrammesTopo.setOnClickListener { showGestion() }
        setupProgrammesTopoTiles()

        // Navigation actions are handled by NavigationFragment
        // Map toolbar actions are handled by MapFragment


        // Initial toolbar states
        try { setToolbarToggleState(btnMapFollow, mapController.isFollowEnabled) } catch (_: Exception) {}
        try { setToolbarToggleState(btnMapImplantFollow, mapController.isFollowEnabled) } catch (_: Exception) {}
        try { setToolbarToggleState(btnMapToggleTrack, mapController.isTraceVisible) } catch (_: Exception) {}
        // Note: map toolbar click listeners are now inside MapFragment
        btnSelectImplantationTarget.setOnClickListener { promptImplantationTarget() }
        btnChooseTargetOnMap.setOnClickListener { enterMapSelectionMode() }
        btnChooseRefA.setOnClickListener { pickReferenceLinePoint(isA = true) }
        btnChooseRefB.setOnClickListener { pickReferenceLinePoint(isA = false) }

        // Reference line fixed offset (parallel line)
        edtRefLineFixedOffset.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refLineFixedOffsetMeters = s?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
                if (implantMode == ImplantMode.REF_LINE) updateImplantationUI()
            }
        })
        rgRefLineSide.setOnCheckedChangeListener { _, checkedId ->
            refLineFixedOffsetLeft = (checkedId == R.id.rbRefLineLeft)
            if (implantMode == ImplantMode.REF_LINE) updateImplantationUI()
        }
        btnQuitImplantation.setOnClickListener { quitApp() }
        // Quit button on map screen is handled by MapFragment

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        soundEnabled = prefs.getBoolean(KEY_SOUND_ENABLED, true)
        voiceEnabled = prefs.getBoolean(KEY_VOICE_ENABLED, true)
        voiceLangMode = VoiceLangMode.fromPref(
            prefs.getString(KEY_VOICE_LANG_MODE, VoiceLangMode.AUTO.prefValue)
        )
        antennaHeightMeters = getDoublePref(prefs, KEY_ANTENNA_HEIGHT, 0.0)
        desiredPrecisionMeters = getDoublePref(prefs, KEY_DESIRED_PRECISION, 0.050)
        precisionLockEnabled = prefs.getBoolean(KEY_PRECISION_LOCK, false)
        switchSoundSurvey.isChecked = soundEnabled
        switchVoiceSurvey.isChecked = voiceEnabled
        spinnerVoiceLangMode.setSelection(
            voiceLangOptions.indexOf(voiceLangMode.prefValue).coerceAtLeast(0)
        )
        voiceLangInitialized = true
        switchSoundSurvey.setOnCheckedChangeListener { _, isChecked ->
            soundEnabled = isChecked
            prefs.edit().putBoolean(KEY_SOUND_ENABLED, isChecked).apply()
        }
        switchVoiceSurvey.setOnCheckedChangeListener { _, isChecked ->
            voiceEnabled = isChecked
            prefs.edit().putBoolean(KEY_VOICE_ENABLED, isChecked).apply()
        }

        // ✅ Enter / Done du clavier
        edtPointId.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyPointIdFromUI()
                true
            } else false
        }

        updateNavUI()
        updateSurveyUI()
        updateImplantationUI()
        updateSimulationUi()
    }

    private fun showNavigation() {
        currentScreen = Screen.NAVIGATION
        screenNav.visibility = View.VISIBLE
        screenGestion.visibility = View.GONE
        screenProgrammesTopo.visibility = View.GONE
        screenSurvey.visibility = View.GONE
        screenMap.visibility = View.GONE
        screenImplantation.visibility = View.GONE
        releaseGuidancePlayer()
        resetGuidanceSchedule()
        updateNavUI()
    }

    private fun showGestion() {
        currentScreen = Screen.GESTION
        screenNav.visibility = View.GONE
        screenGestion.visibility = View.VISIBLE
        screenProgrammesTopo.visibility = View.GONE
        screenSurvey.visibility = View.GONE
        screenMap.visibility = View.GONE
        screenImplantation.visibility = View.GONE
        updateSurveyContextInfo()
    }

    private fun showProgrammesTopo() {
        currentScreen = Screen.PROGRAMMES_TOPO
        screenNav.visibility = View.GONE
        screenGestion.visibility = View.GONE
        screenProgrammesTopo.visibility = View.VISIBLE
        screenSurvey.visibility = View.GONE
        screenMap.visibility = View.GONE
        screenImplantation.visibility = View.GONE
    }

    private fun showLeve() {
        currentScreen = Screen.LEVE
        screenNav.visibility = View.GONE
        screenGestion.visibility = View.GONE
        screenProgrammesTopo.visibility = View.GONE
        screenSurvey.visibility = View.VISIBLE
        screenMap.visibility = View.GONE
        screenImplantation.visibility = View.GONE
        updateSurveyUI()
    }

    private fun startStationWizard() {
        if (!hasActiveLeve()) {
            Toast.makeText(this, "Ouvre ou crée un levé d'abord", Toast.LENGTH_SHORT).show()
            return
        }
        showStationWizardStepBase()
    }

    private fun showStationWizardStepBase() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        layout.addView(TextView(this).apply {
            text = "1) Déconnecte le ROVER si nécessaire\n" +
                    "2) Connecte la BASE (Navigation → Connecter GNSS)\n" +
                    "3) Attends la réception NMEA\n\n" +
                    "Puis clique : Je suis connecté à la BASE"
            textSize = 15f
        })
        layout.addView(TextView(this).apply {
            val status = if (isGnssConnected) "Connecté" else "Non connecté"
            text = "Statut GNSS : $status"
            setPadding(0, 18, 0, 0)
        })

        AlertDialog.Builder(this)
            .setTitle("Assistant mise en station — BASE")
            .setView(layout)
            .setCancelable(true)
            .setNeutralButton("Aller à Navigation") { _, _ ->
                showNavigation()
            }
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Je suis connecté à la BASE") { _, _ ->
                // We only require a valid position to proceed.
                if (lat == 0.0 || lon == 0.0) {
                    Toast.makeText(this, "Aucune position valide reçue. Connecte la BASE puis réessaye.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                showStationWizardStepMode()
            }
            .show()
    }

    private fun showStationWizardStepMode() {
        // When station is validated, we continue to the rover step.
        onStationSetupComplete = {
            onStationSetupComplete = null
            showStationWizardStepRover()
        }
        // Force=true to allow changing station even if already ready.
        showStationSetupDialog(force = true)
    }

    private fun showStationWizardStepRover() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        layout.addView(TextView(this).apply {
            text = "4) Déconnecte la BASE\n" +
                    "5) Connecte le ROVER (Navigation → Connecter GNSS)\n\n" +
                    "Puis clique : Je suis connecté au ROVER"
            textSize = 15f
        })

        AlertDialog.Builder(this)
            .setTitle("Assistant mise en station — ROVER")
            .setView(layout)
            .setCancelable(true)
            .setNeutralButton("Aller à Navigation") { _, _ ->
                showNavigation()
            }
            .setNegativeButton("Retour") { _, _ ->
                // Back to mode step (keeps current measured position)
                showStationWizardStepMode()
            }
            .setPositiveButton("Je suis connecté au ROVER") { _, _ ->
                showLeve()
                Toast.makeText(this, "Levé prêt — station active", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showMapLeve() {
        currentScreen = Screen.MAP_LEVE
        mapMode = MapMode.SURVEY
        showMapScreen()
    }

    private fun showMapImplantation() {
        currentScreen = Screen.MAP_IMPLANTATION
        mapMode = MapMode.IMPLANTATION
        showMapScreen()
    }

    private fun showMapScreen() {
        screenNav.visibility = View.GONE
        screenGestion.visibility = View.GONE
        screenProgrammesTopo.visibility = View.GONE
        screenSurvey.visibility = View.GONE
        screenMap.visibility = View.VISIBLE
        screenImplantation.visibility = View.GONE
        applyMapTitle()
        updateMapActionsForMode()
        updateMapLockState()
        releaseGuidancePlayer()
        resetGuidanceSchedule()
        centerOnMe(false)
        refreshMarkers()
        mapView.invalidate()
    }

    private fun showImplantation() {
        currentScreen = Screen.IMPLANTATION
        screenNav.visibility = View.GONE
        screenGestion.visibility = View.GONE
        screenProgrammesTopo.visibility = View.GONE
        screenSurvey.visibility = View.GONE
        screenMap.visibility = View.GONE
        screenImplantation.visibility = View.VISIBLE
        updateImplantationUI()
    }

    private fun hasActiveLeve(): Boolean = recording && fileManager.isOpen()

    private fun navigateNext() {
        when (currentScreen) {
            Screen.NAVIGATION -> showGestion()
            Screen.GESTION -> showProgrammesTopo()
            Screen.PROGRAMMES_TOPO -> Unit
            Screen.LEVE -> showMapLeve()
            Screen.MAP_LEVE -> showImplantation()
            Screen.IMPLANTATION -> showMapImplantation()
            Screen.MAP_IMPLANTATION -> Unit
        }
    }

    private fun navigatePrev() {
        when (currentScreen) {
            Screen.NAVIGATION -> Unit
            Screen.GESTION -> showNavigation()
            Screen.PROGRAMMES_TOPO -> showGestion()
            Screen.LEVE -> showProgrammesTopo()
            Screen.MAP_LEVE -> showLeve()
            Screen.IMPLANTATION -> showMapLeve()
            Screen.MAP_IMPLANTATION -> showImplantation()
        }
    }

    private fun setupProgrammesTopoTiles() {
        fun setTile(tile: View, enabled: Boolean, onClick: (() -> Unit)? = null) {
            tile.isEnabled = enabled
            tile.alpha = if (enabled) 1.0f else 0.35f
            tile.setOnClickListener {
                if (enabled && onClick != null) {
                    onClick()
                } else {
                    Toast.makeText(this, "Bientôt disponible", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Actifs (déjà existants)
        setTile(tileLeve, true) { showLeve() }
        setTile(tileImplantPt, true) { implantMode = ImplantMode.POINT; showImplantation() }
        setTile(tileImport, true) { startImportLeveWizard() }
        setTile(tileExport, true) { exportDxf() }

        // ✅ Actif : EDIT POINTS
        setTile(tileEditPoints, true) { showEditPointsDialog() }

        // Désactivés (à implémenter ensuite)
        setTile(tileIntersect, true) { launchIntersect() }
        setTile(tileRefLigne, true) { implantMode = ImplantMode.REF_LINE; showImplantation() }
        setTile(tileImplantLigne, false)
        setTile(tilePkDeport, false)
        setTile(tileAuto, true) {
            startActivity(Intent(this, AutoSurveyActivity::class.java))
        }
        setTile(tileSuperficie, true) {
            startActivity(Intent(this, AreaSurveyActivity::class.java))
        }
        setTile(tileTransfo2d, false)
    }

    private fun launchIntersect() {
        val uri = levesUri
        if (uri == null) {
            Toast.makeText(this, "Aucun levé ouvert", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(this, IntersectActivity::class.java).apply {
            putExtra(IntersectActivity.EXTRA_LEVE_URI, uri.toString())
        }
        intersectLauncher.launch(i)
    }

    private fun handleIntersectResult(data: Intent) {
        val uriStr = data.getStringExtra(IntersectActivity.EXTRA_LEVE_URI)
        val uri = uriStr?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: levesUri
        if (uri == null) {
            Toast.makeText(this, "Aucun levé ouvert", Toast.LENGTH_SHORT).show()
            return
        }

        val aId = data.getStringExtra(IntersectActivity.EXTRA_A)?.trim().orEmpty()
        val bId = data.getStringExtra(IntersectActivity.EXTRA_B)?.trim().orEmpty()
        val cId = data.getStringExtra(IntersectActivity.EXTRA_C)?.trim().orEmpty()
        val dId = data.getStringExtra(IntersectActivity.EXTRA_D)?.trim().orEmpty()
        if (aId.isBlank() || bId.isBlank() || cId.isBlank() || dId.isBlank()) {
            Toast.makeText(this, "Intersect: A, B, C, D manquants", Toast.LENGTH_SHORT).show()
            return
        }

        val pts = levePointStore.getLevePointsForCurrentChantier(uri)

        fun measuredPoint(tag: String, id: String): PointXY? {
            if (id != "__MEAS_${tag}__") return null
            fun d(extra: String): Double = data.getDoubleExtra(extra, Double.NaN)

            val latM = when (tag) {
                "A" -> d(IntersectActivity.EXTRA_MEAS_A_LAT)
                "B" -> d(IntersectActivity.EXTRA_MEAS_B_LAT)
                "C" -> d(IntersectActivity.EXTRA_MEAS_C_LAT)
                else -> d(IntersectActivity.EXTRA_MEAS_D_LAT)
            }
            val lonM = when (tag) {
                "A" -> d(IntersectActivity.EXTRA_MEAS_A_LON)
                "B" -> d(IntersectActivity.EXTRA_MEAS_B_LON)
                "C" -> d(IntersectActivity.EXTRA_MEAS_C_LON)
                else -> d(IntersectActivity.EXTRA_MEAS_D_LON)
            }
            val altM = when (tag) {
                "A" -> d(IntersectActivity.EXTRA_MEAS_A_ALT)
                "B" -> d(IntersectActivity.EXTRA_MEAS_B_ALT)
                "C" -> d(IntersectActivity.EXTRA_MEAS_C_ALT)
                else -> d(IntersectActivity.EXTRA_MEAS_D_ALT)
            }

            if (!latM.isFinite() || !lonM.isFinite()) return null
            val altSafe = if (altM.isFinite()) altM else 0.0
            val tr = wgs84ToUtmInct(latM, lonM, altSafe)
            return PointXY(
                id = id,
                x = tr.x,
                y = tr.y,
                z = tr.z,
                lat = latM,
                lon = lonM,
                alt = altSafe
            )
        }

        fun find(id: String): PointXY? = pts.firstOrNull { it.id.equals(id, ignoreCase = false) }

        val A = measuredPoint("A", aId) ?: find(aId)
        val B = measuredPoint("B", bId) ?: find(bId)
        val C = measuredPoint("C", cId) ?: find(cId)
        val D = measuredPoint("D", dId) ?: find(dId)

        if (A == null || B == null || C == null || D == null) {
            Toast.makeText(this, "Intersect: point introuvable", Toast.LENGTH_SHORT).show()
            return
        }

        val inter = computeIntersectionWgs84(A, B, C, D)
        if (inter == null) {
            Toast.makeText(this, "Lignes parallèles (pas d'intersection)", Toast.LENGTH_SHORT).show()
            return
        }
        val (latI, lonI, altI) = inter

        // Compute INCT coords (storage) without adding station twice.
        val tr = wgs84ToUtmInct(latI, lonI, altI)
        val utm = wgs84ToUtm(latI, lonI)

        val action = data.getStringExtra(IntersectActivity.EXTRA_ACTION)
        if (action == IntersectActivity.ACTION_SAVE) {
            val id = nextFreeIntId()
            val ok = saveComputedPoint(
                customId = id,
                inctX = tr.x,
                inctY = tr.y,
                inctZ = tr.z,
                zone = tr.zone,
                lat = latI,
                lon = lonI,
                alt = altI,
                typeLabel = "INT"
            )
            if (ok) {
                Toast.makeText(this, "✅ Intersect enregistré : $id", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Default: implant as temporary target
        selectedTarget = TargetPoint(
            id = "INTERSECT",
            xInct = tr.x,
            yInct = tr.y,
            zInct = tr.z,
            xWgs84Utm = utm.easting,
            yWgs84Utm = utm.northing,
            lat = latI,
            lon = lonI,
            alt = altI,
            sourceLabel = "INTERSECT"
        )
        updateTargetMarker(selectedTarget)
        updateImplantationUI()
        showImplantation()
        resetGuidanceSchedule()
        speakTargetSelected()
    }

    /** Returns (lat, lon, alt) of intersection in WGS84. Uses local tangent plane to avoid degrees math. */
    private fun computeIntersectionWgs84(A: PointXY, B: PointXY, C: PointXY, D: PointXY): Triple<Double, Double, Double>? {
        // Require valid lat/lon
        val lat0 = (A.lat + B.lat + C.lat + D.lat) / 4.0
        val lon0 = (A.lon + B.lon + C.lon + D.lon) / 4.0
        val lat0Rad = Math.toRadians(lat0)
        val R = 6378137.0
        fun toXY(p: PointXY): Pair<Double, Double> {
            val x = Math.toRadians(p.lon - lon0) * cos(lat0Rad) * R
            val y = Math.toRadians(p.lat - lat0) * R
            return x to y
        }

        val (x1, y1) = toXY(A)
        val (x2, y2) = toXY(B)
        val (x3, y3) = toXY(C)
        val (x4, y4) = toXY(D)

        val denom = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
        if (abs(denom) < 1e-12) return null
        val px = ((x1 * y2 - y1 * x2) * (x3 - x4) - (x1 - x2) * (x3 * y4 - y3 * x4)) / denom
        val py = ((x1 * y2 - y1 * x2) * (y3 - y4) - (y1 - y2) * (x3 * y4 - y3 * x4)) / denom

        // back to lat/lon
        val lat = lat0 + Math.toDegrees(py / R)
        val lon = lon0 + Math.toDegrees(px / (R * cos(lat0Rad)))
        val alt = (A.alt + B.alt + C.alt + D.alt) / 4.0
        return Triple(lat, lon, alt)
    }

    private fun nextFreeIntId(): String {
        reloadUsedPointIdsFromCurrentLeve()
        val regex = Regex("^INT_(\\d+)$", RegexOption.IGNORE_CASE)
        var maxIdx = 0
        usedPointIds.forEach { id ->
            val m = regex.find(id.trim()) ?: return@forEach
            val n = m.groupValues.getOrNull(1)?.toIntOrNull() ?: return@forEach
            if (n > maxIdx) maxIdx = n
        }
        var idx = (maxIdx + 1).coerceAtLeast(1)
        var id = String.format(Locale.US, "INT_%03d", idx)
        while (usedPointIds.contains(id)) {
            idx++
            id = String.format(Locale.US, "INT_%03d", idx)
        }
        return id
    }

    /** Save a computed point (not coming from live GNSS) while preserving the same CSV structure. */
    private fun saveComputedPoint(
        customId: String,
        inctX: Double,
        inctY: Double,
        inctZ: Double,
        zone: Int,
        lat: Double,
        lon: Double,
        alt: Double,
        typeLabel: String
    ): Boolean {
        if (!recording || !fileManager.isOpen()) {
            Toast.makeText(this, "Démarre un levé d'abord", Toast.LENGTH_SHORT).show()
            return false
        }
        val toSave = InctResult(
            id = customId,
            x = inctX,
            y = inctY,
            z = inctZ,
            zone = zone,
            timestamp = System.currentTimeMillis()
        )
        val ok = fileManager.write(toSave, lat, lon, alt, typeLabel)
        if (ok) {
            usedPointIds.add(customId)
            addMarkerForSavedPoint(toSave)
            updateSurveyUI()
        } else {
            Toast.makeText(this, "❌ Erreur écriture levé", Toast.LENGTH_LONG).show()
        }
        return ok
    }

    /**
     * EDIT POINTS: list points of the current levé and allow rename/delete.
     * Display is driven by '# CRS:' (INCT_utm / WGS84_utm / WGS84_geog) with NO transformation.
     */
    private fun showEditPointsDialog() {
        val uri = levesUri
        if (uri == null) {
            Toast.makeText(this, "Aucun levé ouvert", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_points, null)
        val txtCrs = dialogView.findViewById<TextView>(R.id.txtEditPointsCrs)
        val inputSearch = dialogView.findViewById<EditText>(R.id.inputEditPointsSearch)
        val recycler = dialogView.findViewById<RecyclerView>(R.id.recyclerEditPoints)

        val crs = getDisplayCrs()
        txtCrs.text = "CRS affiché : ${crsLabel(crs)}"

        fun loadPoints(): MutableList<PointXY> {
            val pts = levePointStore.getLevePointsForCurrentChantier(uri)
            return pts.sortedWith { a, b -> naturalCompare(a.id, b.id) }.toMutableList()
        }

        val allPoints = loadPoints()
        val shownPoints = allPoints.toMutableList()

        recycler.layoutManager = LinearLayoutManager(this)

        lateinit var applyFilter: (String) -> Unit

        class EditPointsAdapter(private val data: MutableList<PointXY>) :
            RecyclerView.Adapter<EditPointsAdapter.VH>() {

            inner class VH(v: View) : RecyclerView.ViewHolder(v) {
                val txtId: TextView = v.findViewById(R.id.rowPointId)
                val txtCoords: TextView = v.findViewById(R.id.rowPointCoords)
                val btnMore: ImageButton = v.findViewById(R.id.rowPointMore)
            }

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
                val v = layoutInflater.inflate(R.layout.row_edit_point, parent, false)
                return VH(v)
            }

            override fun getItemCount(): Int = data.size

            override fun onBindViewHolder(holder: VH, position: Int) {
                val p = data[position]
                holder.txtId.text = p.id
                holder.txtCoords.text = formatPointForDisplayCrs(p, crs)

                fun showActions(anchor: View) {
                    val popup = PopupMenu(this@MainActivity, anchor)
                    popup.menu.add("Renommer")
                    popup.menu.add("Supprimer")
                    popup.setOnMenuItemClickListener { item ->
                        when (item.title.toString()) {
                            "Renommer" -> {
                                showRenamePointDialog(uri, p.id) {
                                    val re = loadPoints()
                                    allPoints.clear(); allPoints.addAll(re)
                                    applyFilter(inputSearch.text?.toString().orEmpty())
                                }
                                true
                            }

                            "Supprimer" -> {
                                showDeletePointConfirm(uri, p.id) {
                                    val re = loadPoints()
                                    allPoints.clear(); allPoints.addAll(re)
                                    applyFilter(inputSearch.text?.toString().orEmpty())
                                }
                                true
                            }

                            else -> false
                        }
                    }
                    popup.show()
                }

                holder.btnMore.setOnClickListener { showActions(it) }
                holder.itemView.setOnLongClickListener {
                    showActions(holder.btnMore)
                    true
                }
            }
        }

        val adapter = EditPointsAdapter(shownPoints)
        recycler.adapter = adapter

        fun refreshShown(newList: List<PointXY>) {
            shownPoints.clear()
            shownPoints.addAll(newList)
            adapter.notifyDataSetChanged()
        }

        applyFilter = { query ->
            val q = query.trim().lowercase(Locale.getDefault())
            if (q.isEmpty()) {
                refreshShown(allPoints)
            } else {
                refreshShown(allPoints.filter { it.id.lowercase(Locale.getDefault()).contains(q) })
            }
        }

        inputSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })

        AlertDialog.Builder(this)
            .setTitle("EDIT POINTS")
            .setView(dialogView)
            .setPositiveButton("Fermer", null)
            .show()
    }

    private fun crsLabel(crs: ProjectCrs): String = when (crs.id) {
        ProjectCrs.INCT_UTM_ALGERIA.id -> "INCT_utm"
        ProjectCrs.WGS84_UTM_AUTO.id -> "WGS84_utm"
        ProjectCrs.WGS84_LATLON.id -> "WGS84_geog"
        else -> crs.label
    }

    private fun formatPointForDisplayCrs(p: PointXY, crs: ProjectCrs): String {
        return when (crs.id) {
            ProjectCrs.INCT_UTM_ALGERIA.id -> {
                "X=${fmt(p.x)}  Y=${fmt(p.y)}  Z=${fmt(p.z)}"
            }

            ProjectCrs.WGS84_UTM_AUTO.id -> {
                val ex = p.wgs84UtmX
                val ny = p.wgs84UtmY
                if (ex != null && ny != null) {
                    "E=${fmt(ex)}  N=${fmt(ny)}  Z=${fmt(p.z)}"
                } else {
                    "UTM indisponible"
                }
            }

            ProjectCrs.WGS84_LATLON.id -> {
                "Lat=${fmt6(p.lat)}  Lon=${fmt6(p.lon)}  Alt=${fmt(p.alt)}"
            }

            else -> "X=${fmt(p.x)}  Y=${fmt(p.y)}  Z=${fmt(p.z)}"
        }
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.3f", v)
    private fun fmt6(v: Double): String = String.format(Locale.US, "%.6f", v)

    private fun showRenamePointDialog(uri: Uri, oldId: String, onDone: () -> Unit) {
        val input = EditText(this).apply {
            setText(oldId)
            setSelection(oldId.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Renommer")
            .setMessage("Nouveau nom pour $oldId")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val newId = input.text?.toString()?.trim().orEmpty()
                if (newId.isBlank()) {
                    Toast.makeText(this, "Nom vide", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newId == oldId) {
                    onDone()
                    return@setPositiveButton
                }
                val ok = renamePointInLeveCsv(uri, oldId, newId)
                if (ok) {
                    usedPointIds.remove(oldId)
                    usedPointIds.add(newId)
                    Toast.makeText(this, "Renommé", Toast.LENGTH_SHORT).show()
                    onDone()
                } else {
                    Toast.makeText(this, "Échec renommage", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showDeletePointConfirm(uri: Uri, id: String, onDone: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Supprimer")
            .setMessage("Supprimer le point $id ?")
            .setPositiveButton("Supprimer") { _, _ ->
                val ok = deletePointFromLeveCsv(uri, id)
                if (ok) {
                    usedPointIds.remove(id)
                    Toast.makeText(this, "Supprimé", Toast.LENGTH_SHORT).show()
                    onDone()
                } else {
                    Toast.makeText(this, "Échec suppression", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun renamePointInLeveCsv(uri: Uri, oldId: String, newId: String): Boolean {
        return runCatching {
            val existing = levePointStore.getLevePointsForCurrentChantier(uri).map { it.id }.toSet()
            if (newId in existing) return false

            val lines = contentResolver.openInputStream(uri)?.bufferedReader()?.readLines().orEmpty()
            if (lines.isEmpty()) return false

            var headerIndex = -1
            for (i in lines.indices) {
                val t = lines[i].trim()
                if (t.isEmpty()) continue
                if (t.startsWith("#")) continue
                headerIndex = i
                break
            }
            if (headerIndex < 0) return false

            val headers = lines[headerIndex].split(",").map { it.trim().lowercase(Locale.getDefault()) }
            val idxId = headers.indexOf("id")
            if (idxId < 0) return false

            val out = ArrayList<String>(lines.size)
            out.addAll(lines.subList(0, headerIndex + 1))
            for (i in headerIndex + 1 until lines.size) {
                val line = lines[i]
                if (line.isBlank()) {
                    out.add(line)
                    continue
                }
                val parts = line.split(",").toMutableList()
                if (idxId in parts.indices && parts[idxId].trim() == oldId) {
                    parts[idxId] = newId
                    out.add(parts.joinToString(","))
                } else {
                    out.add(line)
                }
            }
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { w ->
                out.forEachIndexed { i, l ->
                    w.write(l)
                    if (i != out.lastIndex) w.newLine()
                }
            }
            true
        }.getOrElse { false }
    }

    private fun deletePointFromLeveCsv(uri: Uri, id: String): Boolean {
        return runCatching {
            val lines = contentResolver.openInputStream(uri)?.bufferedReader()?.readLines().orEmpty()
            if (lines.isEmpty()) return false

            var headerIndex = -1
            for (i in lines.indices) {
                val t = lines[i].trim()
                if (t.isEmpty()) continue
                if (t.startsWith("#")) continue
                headerIndex = i
                break
            }
            if (headerIndex < 0) return false

            val headers = lines[headerIndex].split(",").map { it.trim().lowercase(Locale.getDefault()) }
            val idxId = headers.indexOf("id")
            if (idxId < 0) return false

            val out = ArrayList<String>(lines.size)
            out.addAll(lines.subList(0, headerIndex + 1))
            for (i in headerIndex + 1 until lines.size) {
                val line = lines[i]
                if (line.isBlank()) {
                    out.add(line)
                    continue
                }
                val parts = line.split(",")
                val curId = if (idxId in parts.indices) parts[idxId].trim() else ""
                if (curId == id) {
                    // skip
                } else {
                    out.add(line)
                }
            }
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { w ->
                out.forEachIndexed { i, l ->
                    w.write(l)
                    if (i != out.lastIndex) w.newLine()
                }
            }
            true
        }.getOrElse { false }
    }

    private fun updateMapActionsForMode() {
        if (!uiReady) return
        layoutMapActionsSurvey.visibility = if (mapMode == MapMode.SURVEY) View.VISIBLE else View.GONE
        layoutMapActionsImplantation.visibility =
            if (mapMode == MapMode.IMPLANTATION) View.VISIBLE else View.GONE
        btnNavNextMap.visibility = if (currentScreen == Screen.MAP_IMPLANTATION) View.INVISIBLE else View.VISIBLE
    }

    private fun updateMapLockState() {
        val locked = !(hasActiveLeve() && stationReady)
        txtMapLocked.visibility = if (locked) View.VISIBLE else View.GONE
        val enabled = !locked
        btnMapAddPoint.isEnabled = enabled
        btnMapCenterRover.isEnabled = enabled
        btnMapToggleTrack.isEnabled = enabled
        btnMapImplantCenterRover.isEnabled = enabled
        btnMapImplantCenterTarget.isEnabled = enabled
        btnMapImplantToggleCircle.isEnabled = enabled
    }

    private fun updateGnssBanners() {
        if (!::bannerNav.isInitialized || !::bannerLeve.isInitialized || !::bannerMap.isInitialized) {
            return
        }
        val isReady = simRunning || isGnssConnected
        val (label, color) = if (isReady) {
            "GNSS READY" to Color.parseColor("#2E7D32")
        } else {
            "GNSS NOT READY" to Color.parseColor("#C62828")
        }
        bannerNav.text = label
        bannerLeve.text = label
        bannerMap.text = label
        bannerNav.setBackgroundColor(color)
        bannerLeve.setBackgroundColor(color)
        bannerMap.setBackgroundColor(color)
    }

private fun toggleCompassOverlay() {
    mapController.toggleCompassOverlay()
}


private fun centerOnTarget() {
    val target = selectedTarget ?: return
    mapController.centerOn(lat = target.lat, lon = target.lon, zoom = 19.0)
}


    private fun toggleTargetCircle() {
        showTargetCircle = !showTargetCircle
        updateImplantationUI()
        mapView.invalidate()
    }

private fun showMapPointsList() {
    val leveUri = levesUri
    val repereUri = reperesUri
    val levePoints = leveUri?.let { mapController.loadLeveCsvPoints(it) }.orEmpty()
    val reperePoints = repereUri?.let { mapController.loadRepereCsvPoints(it) }.orEmpty()

    if (levePoints.isEmpty() && reperePoints.isEmpty()) {
        Toast.makeText(this, "Aucun point", Toast.LENGTH_SHORT).show()
        return
    }

    val labels = mutableListOf<String>()
    levePoints.forEach { point ->
        labels.add("LEVE ${point.id} | ${point.lat}, ${point.lon}")
    }
    reperePoints.forEach { point ->
        labels.add("REPERE ${point.id} | ${point.lat}, ${point.lon}")
    }

    AlertDialog.Builder(this)
        .setTitle("Points")
        .setItems(labels.toTypedArray(), null)
        .setPositiveButton("OK", null)
        .show()
}


    private fun openTopoPlan() {
        val leveUri = levesUri
        if (!hasActiveLeve() || leveUri == null) {
            Toast.makeText(this, "Aucun levé ouvert", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, TopoPlanActivity::class.java).apply {
                putExtra(TopoPlanActivity.EXTRA_LEVES_URI, leveUri.toString())
                rootUri?.let { putExtra(TopoPlanActivity.EXTRA_CHANTIER_DIR_URI, it.toString()) }
            }
        )
    }

    private fun exportDxf() {
        val chantierDir = resolveChantierDirFromUriOrPrefs(this, rootUri)
            ?: chantierDirDoc
        if (chantierDir == null || chantierDir.name == "TOPOGRAPHIE") {
            Toast.makeText(this, "Chantier non défini / permissions", Toast.LENGTH_SHORT).show()
            return
        }
        val leveUri = levesUri
        if (leveUri == null) {
            Toast.makeText(this, "Ouvrir un levé d’abord", Toast.LENGTH_SHORT).show()
            return
        }
        val chantierLabel = chantierName?.ifBlank { null } ?: "chantier"
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "export_${chantierLabel}_${timestamp}.dxf"
        val points = levePointStore.getLevePointsForCurrentChantier(leveUri)
        val polylines = TopoPlanPolylineStore(contentResolver).load(chantierDir)
        val leveId = getLeveId(leveUri)
        val filteredPolylines = if (leveId == null) {
            polylines
        } else {
            polylines.filter { it.leveId == null || it.leveId == leveId }
        }
        thread {
            val file = DxfWriter.writeDxf(contentResolver, chantierDir, fileName, points, filteredPolylines)
            runOnUiThread {
                if (file != null) {
                    Toast.makeText(this, "DXF exporté: ${file.name}", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Échec export DXF", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getLeveId(leveUri: Uri): String? {
        return DocumentFile.fromSingleUri(this, leveUri)?.name ?: leveUri.lastPathSegment
    }

    private fun enterMapSelectionMode() {
        if (!hasActiveLeve()) {
            Toast.makeText(this, getString(R.string.implantation_no_target), Toast.LENGTH_SHORT).show()
            return
        }
        mapMode = MapMode.IMPLANTATION
        showMapImplantation()
        Toast.makeText(this, getString(R.string.choose_on_map_hint), Toast.LENGTH_LONG).show()
    }

    private fun toggleSimulation() {
        if (!simRunning) {
            disconnectBt()
            startNmeaSimulation()
        } else {
            stopNmeaSimulation()
        }
        updateSimulationUi()
        refreshGnssUi("toggle_simulation")
    }

    private fun updateSimulationUi() {
        if (!::btnSimToggle.isInitialized) return
        btnSimToggle.text = if (simRunning) "Simulation OFF" else "Simulation ON"
    }

    private fun speak(message: String) {
        if (!voiceEnabled) return
        val nowElapsed = SystemClock.elapsedRealtime()
        if (message == lastSpokenMessage && nowElapsed - lastSpokenAtElapsed < 800L) return
        lastSpokenMessage = message
        lastSpokenAtElapsed = nowElapsed
        try {
            tts.speak(
                message,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "voice_${nowElapsed}"
            )
            lastTtsAtElapsed = SystemClock.elapsedRealtime()
        } catch (_: Exception) {
        }
    }

    private fun playNotif(pointId: String) {
        if (soundEnabled) {
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            } catch (_: Exception) {
            }
        }
        if (voiceEnabled) {
            try {
                tts.speak(
                    "Point $pointId enregistré",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "pt_${System.currentTimeMillis()}"
                )
                lastTtsAtElapsed = SystemClock.elapsedRealtime()
            } catch (_: Exception) {
            }
        }
    }

    /* =========================
       PERMISSIONS
       ========================= */

    private fun requestPermissions() {
        val perms = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) perms.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) perms.add(Manifest.permission.BLUETOOTH_SCAN)

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERM_REQ)
        } else {
            refreshPairedDevices()
        }
    }

    private fun hasBtConnect(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun hasBtScan(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERM_REQ -> {
                val ok = grantResults.isNotEmpty() && grantResults.all {
                    it == PackageManager.PERMISSION_GRANTED
                }
                if (!ok) {
                    Toast.makeText(this, "Permissions refusées (Bluetooth/GPS)", Toast.LENGTH_LONG)
                        .show()
                    return
                }
                refreshPairedDevices()
            }

            PERM_REQ_AUDIO -> {
                val ok = grantResults.isNotEmpty() && grantResults.all {
                    it == PackageManager.PERMISSION_GRANTED
                }
                if (!ok) {
                    Toast.makeText(
                        this,
                        "Permission micro refusée",
                        Toast.LENGTH_LONG
                    ).show()
                    speak("Permission micro refusée")
                    disableVoiceMode("permission")
                    return
                }
                startVoiceCommandListening()
            }
        }
    }

    /* =========================
       BLUETOOTH
       ========================= */

    private fun initBluetooth() {
        btAdapter = BluetoothAdapter.getDefaultAdapter()
        if (btAdapter == null) {
            Toast.makeText(this, "Bluetooth non supporté", Toast.LENGTH_LONG).show()
            return
        }

        btClient = BluetoothGnssClient(
            adapter = btAdapter,
            hasBtConnectPermission = { hasBtConnectPermission() },
            hasBtScanPermission = { hasBtScanPermission() },
            onConnectionState = { connected, reason ->
                isGnssConnected = connected
                gnssStore.setConnection(connected, selectedDevice?.let { deviceLabel(it) } ?: selectedDeviceAddress)
                gnssStore.setSource(
                    if (connected) GnssState.Source.BLUETOOTH else if (simRunning) GnssState.Source.SIMULATION else GnssState.Source.NONE
                )
                if (connected) {
                    stopNmeaSimulation()
                    refreshGnssUi("connect")
                } else {
                    // Avoid double UI refresh for user-triggered disconnect; disconnectBt() already does it.
                    if (reason != BluetoothGnssClient.REASON_USER_DISCONNECT) {
                        refreshGnssUi(reason)
                    }
                }
            },
            onNmeaLine = { line ->
                runOnUiThread { nmeaParser.consumeLine(line) }
            },
            onError = { msg, tr -> Log.e(TAG, msg, tr) }
        )

        btScanner = BluetoothDeviceScanner(
            context = this,
            adapter = btAdapter,
            hasBtScanPermission = { hasBtScanPermission() },
            onScanningChanged = { scanning -> setScanUi(scanning) },
            onDeviceFound = { device, rssi ->
                if (rssi != null) {
                    discoveredRssi[device.address] = rssi
                }
                if (discovered.none { d -> d.address == device.address }) {
                    discovered.add(device)
                    rebuildDeviceList()
                }
            },
            onScanFinished = {
                refreshPairedDevices()
                refreshGnssUi("scan_finished")
            },
            onError = { msg, tr -> Log.e(TAG, msg, tr) }
        )

        refreshPairedDevices()
    }

    private fun safeName(d: BluetoothDevice): String {
        return try {
            if (hasBtConnect()) d.name ?: "Inconnu" else "Permission requise"
        } catch (_: SecurityException) {
            "Inconnu"
        }
    }

    private fun deviceLabel(d: BluetoothDevice): String {
        val name = safeName(d).ifBlank { "Inconnu" }
        return "$name - ${d.address}"
    }

    private fun refreshPairedDevices() {
        if (!::btAdapter.isInitialized) return

        try {
            paired.clear()
            if (hasBtConnectPermission()) {
                paired.addAll(btAdapter.bondedDevices)
            }
            rebuildDeviceList()
        } catch (_: SecurityException) {
            rebuildDeviceList()
        } catch (e: Exception) {
            Log.e(TAG, "Erreur paired devices", e)
        }
    }

    
private fun rebuildDeviceList() {
    allDevices.clear()
    deviceNames.clear()

    // 1) Always show paired devices first
    for (d in paired) {
        allDevices.add(d)
        deviceNames.add("${deviceLabel(d)} (Appairé)")
    }

    val existing = allDevices.map { it.address }.toHashSet()

    // 2) Then add newly discovered devices
    for (d in discovered) {
        if (d.address !in existing) {
            allDevices.add(d)
            val rssi = discoveredRssi[d.address]
            val rssiLabel = if (rssi != null && rssi != 0 && rssi != -32768) " • RSSI $rssi dBm" else ""
            deviceNames.add("${deviceLabel(d)} (Découvert$rssiLabel)")
        }
    }

    // Keep selection coherent
    if (selectedDeviceAddress != null && allDevices.none { it.address == selectedDeviceAddress }) {
        selectedDevice = null
        selectedDeviceAddress = null
    }

    if (::gnssAdapter.isInitialized) {
        runOnUiThread { gnssAdapter.notifyDataSetChanged() }
    }

    Log.d(TAG, "Appareils disponibles: ${allDevices.size}")
}

    private fun startScan() {
    if (!::btAdapter.isInitialized || !::btScanner.isInitialized) return
    if (!btAdapter.isEnabled) {
        Toast.makeText(this, "Active le Bluetooth", Toast.LENGTH_SHORT).show()
        return
    }
    if (!hasBtScan()) {
        Toast.makeText(this, "Permission scan Bluetooth manquante", Toast.LENGTH_LONG).show()
        requestPermissions()
        return
    }

    // Palpable press feedback
    try {
        btnScanGnss.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        pulseView(btnScanGnss)
    } catch (_: Exception) {
    }

    // Refresh paired devices immediately so the list is useful even before discovery results
    refreshPairedDevices()

    discovered.clear()
    discoveredRssi.clear()
    rebuildDeviceList()

    btScanner.startScan(timeoutMs = scanTimeoutMs)
}

    private fun connectSelected() {
        val dev = selectedDevice
        if (dev == null) {
            Toast.makeText(this, "Sélectionne un GNSS dans la liste", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasBtConnect()) {
            Toast.makeText(this, "Permission Bluetooth connect manquante", Toast.LENGTH_LONG).show()
            requestPermissions()
            return
        }

        Log.d(TAG, "Connexion à ${safeName(dev)}...")
        btClient.connect(dev)
    }

        private fun disconnectBt() {
        btClient.disconnect(userInitiated = true)

        isGnssConnected = false
        selectedDevice = null
        selectedDeviceAddress = null

        gnssUiHandler.removeCallbacks(gnssUiRunnable)
        gnssStore.resetFix()
        gnssStore.setConnection(false, null)
        gnssStore.setSource(if (simRunning) GnssState.Source.SIMULATION else GnssState.Source.NONE)
        refreshGnssUi("disconnect")
    }
    private fun quitApp() {
        finishAffinity()
    }

    /* =========================
       NMEA
       ========================= */

    private fun setupNmeaParser() {
        nmeaParser = NmeaParser(
            listener = object : NmeaParser.Listener {

                override fun onGgaFix(update: NmeaParser.GgaFix) {
                    val inct = if (update.fixQuality > 0) {
                        wgs84ToProjectCoords(update.lat, update.lon, update.alt)
                    } else null
                    gnssStore.updateFromGga(update, inct)

                    updateMapPosition()
                    refreshGnssUi("gnss_update")
                }

                override fun onRmcFix(update: NmeaParser.RmcFix) {
                    val inct = if (fixQuality > 0) {
                        wgs84ToProjectCoords(update.lat, update.lon, alt)
                    } else null
                    gnssStore.updateFromRmc(update, inct)

                    updateMapPosition()
                    refreshGnssUi("gnss_update")
                }

                override fun onSatellitesCount(count: Int) {
                    gnssStore.setSatellites(count)
                    refreshGnssUi("gnss_update")
                }
            },
            onError = { msg, tr -> Log.e(TAG, msg, tr) }
        )
    }

    private fun setupGnssStateStore() {
        // Centralize "latest GNSS position" publication for map-centric screens and other activities.
        // Any component can observe GNSS updates through this store later (Fragments, controllers...).
        gnssStore.addListener { state ->
            publishGnssPosition(state)
        }
    }

    private fun publishGnssPosition(state: GnssState) {
        // Publish latest position for map screens (Superficie, etc.)
        lastGnssLat = state.lat
        lastGnssLon = state.lon
        lastGnssAlt = state.alt

        // Broadcast latest GNSS position for other activities (e.g., Superficie)
        runCatching {
            val i = Intent(ACTION_GNSS_POSITION)
            i.putExtra(EXTRA_GNSS_LAT, lastGnssLat)
            i.putExtra(EXTRA_GNSS_LON, lastGnssLon)
            i.putExtra(EXTRA_GNSS_ALT, lastGnssAlt)
            LocalBroadcastManager.getInstance(this).sendBroadcast(i)
        }
    }

/**
     * Converts current WGS84 GNSS position to the **project CRS** selected in ChantierHomeActivity.
     *
     * - INCT: keeps the existing transformation unchanged.
     * - WGS84 Lat/Lon: stores X=Lon, Y=Lat (degrees).
     * - WGS84 UTM auto: uses EPSG:326xx (north) based on lon.
     * - Other CRSs: uses Proj4J EPSG definitions.
     */
    private fun wgs84ToProjectCoords(
        lat: Double,
        lon: Double,
        alt: Double,
        applyStationTransform: Boolean = true
    ): InctResult {
        // Storage CRS is ALWAYS INCT (xinct/yinct/zinct).
        val tr = wgs84ToUtmInct(lat, lon, alt)
        val base = InctResult(id = pointId, x = tr.x, y = tr.y, z = tr.z, zone = tr.zone)
        return if (applyStationTransform && stationReady) {
            base.copy(x = base.x + stationDx, y = base.y + stationDy, z = base.z + stationDz)
        } else base
    }

    /**
     * Converts a point expressed in the **active levé CRS** (already station-adjusted) back to WGS84 (lat/lon/alt).
     * This is required so CSV columns stay coherent (lat/lon + WGS84 UTM) after station offsets.
     */
    private fun projectCoordsToWgs84(work: InctResult): Wgs84Result {
        // Storage CRS is ALWAYS INCT.
        return inctUtmToWgs84(easting = work.x, northing = work.y, zone = work.zone, alt = work.z)
    }

    /* =========================
       SIMULATION
       ========================= */

    private fun startNmeaSimulation() {
        if (simRunning) return
        gnssStore.setSource(GnssState.Source.SIMULATION)
        simRunning = true
        updateSimulationUi()
        simThread = Thread {
            val random = Random(System.currentTimeMillis())
            var simLat = 36.055525
            var simLon = 4.750931
            val startLat = simLat
            val startLon = simLon
            val simAlt = 910.0
            val speedKmh = 6.0
            val speedMps = speedKmh * 1000.0 / 3600.0
            val updateMs = 200L
            val stepMeters = speedMps * (updateMs / 1000.0)
            var bearing = random.nextDouble(0.0, 360.0)
            var nextTurnAt = System.currentTimeMillis() + random.nextLong(3000L, 10001L)

            while (simRunning) {
                val now = System.currentTimeMillis()
                val distFromStart = distanceMeters(simLat, simLon, startLat, startLon)
                bearing = if (distFromStart > 950.0) {
                    bearingDeg(simLat, simLon, startLat, startLon)
                } else if (now >= nextTurnAt) {
                    nextTurnAt = now + random.nextLong(3000L, 10001L)
                    random.nextDouble(0.0, 360.0)
                } else {
                    bearing
                }

                val moved = moveByMeters(simLat, simLon, stepMeters, bearing)
                simLat = moved.first
                simLon = moved.second

                val noiseMeters = random.nextDouble(0.0, 0.15)
                if (noiseMeters > 0.0) {
                    val noiseBearing = random.nextDouble(0.0, 360.0)
                    val noisy = moveByMeters(simLat, simLon, noiseMeters, noiseBearing)
                    simLat = noisy.first
                    simLon = noisy.second
                }

                val gga = buildGga(simLat, simLon, 4, 18, simAlt)
                val rmc = buildRmc(simLat, simLon)
                runOnUiThread {
                    nmeaParser.consumeLine(gga)
                    nmeaParser.consumeLine(rmc)
                }

                try {
                    Thread.sleep(updateMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        simThread?.start()
    }

    private fun stopNmeaSimulation() {
        simRunning = false
        gnssStore.setSource(if (isGnssConnected) GnssState.Source.BLUETOOTH else GnssState.Source.NONE)
        updateSimulationUi()
        simThread?.interrupt()
        simThread = null
    }

    private fun refreshGnssUi(reason: String) {
        if (!uiReady) return
        // GNSS position publication (lastGnss* + broadcast) is handled by GnssStateStore listener.
        val isSimulationOn = simRunning
        val isReady = isSimulationOn || isGnssConnected
        Log.d(
            TAG,
            "refreshGnssUi reason=$reason ready=$isReady connected=$isGnssConnected sim=$isSimulationOn"
        )
        gnssUiReason = reason
        gnssUiHandler.removeCallbacks(gnssUiRunnable)
        gnssUiHandler.post(gnssUiRunnable)
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    private fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        val brng = Math.toDegrees(atan2(y, x))
        return (brng + 360.0) % 360.0
    }

    private fun moveByMeters(
        lat: Double,
        lon: Double,
        distMeters: Double,
        bearingDeg: Double
    ): Pair<Double, Double> {
        val r = 6371000.0
        val bearingRad = Math.toRadians(bearingDeg)
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)
        val delta = distMeters / r

        val newLat = asin(
            sin(latRad) * cos(delta) +
                    cos(latRad) * sin(delta) * cos(bearingRad)
        )
        val newLon = lonRad + atan2(
            sin(bearingRad) * sin(delta) * cos(latRad),
            cos(delta) - sin(latRad) * sin(newLat)
        )
        return Pair(Math.toDegrees(newLat), Math.toDegrees(newLon))
    }

    private fun buildGga(lat: Double, lon: Double, fix: Int, sats: Int, alt: Double): String {
        val df = SimpleDateFormat("HHmmss.SSS", Locale.US)
        val time = df.format(Date())
        val (latVal, latHemi) = toNmeaLat(lat)
        val (lonVal, lonHemi) = toNmeaLon(lon)
        val sentence = String.format(
            Locale.US,
            "GPGGA,%s,%s,%s,%s,%s,%d,%02d,0.9,%.2f,M,0.0,M,,",
            time,
            latVal,
            latHemi,
            lonVal,
            lonHemi,
            fix,
            sats,
            alt
        )
        return withChecksum(sentence)
    }

    private fun buildRmc(lat: Double, lon: Double): String {
        val timeDf = SimpleDateFormat("HHmmss.SSS", Locale.US)
        val dateDf = SimpleDateFormat("ddMMyy", Locale.US)
        val time = timeDf.format(Date())
        val date = dateDf.format(Date())
        val (latVal, latHemi) = toNmeaLat(lat)
        val (lonVal, lonHemi) = toNmeaLon(lon)
        val sentence = String.format(
            Locale.US,
            "GPRMC,%s,A,%s,%s,%s,%s,0.0,0.0,%s,,",
            time,
            latVal,
            latHemi,
            lonVal,
            lonHemi,
            date
        )
        return withChecksum(sentence)
    }

    private fun toNmeaLat(dd: Double): Pair<String, String> {
        val hemi = if (dd >= 0) "N" else "S"
        val absVal = abs(dd)
        val deg = absVal.toInt()
        val min = (absVal - deg) * 60.0
        val value = String.format(Locale.US, "%02d%07.4f", deg, min)
        return value to hemi
    }

    private fun toNmeaLon(dd: Double): Pair<String, String> {
        val hemi = if (dd >= 0) "E" else "W"
        val absVal = abs(dd)
        val deg = absVal.toInt()
        val min = (absVal - deg) * 60.0
        val value = String.format(Locale.US, "%03d%07.4f", deg, min)
        return value to hemi
    }

    private fun withChecksum(sentenceWithoutDollarAndWithoutStar: String): String {
        var checksum = 0
        for (ch in sentenceWithoutDollarAndWithoutStar) checksum = checksum xor ch.code
        val hex = String.format(Locale.US, "%02X", checksum)
        return "\$${sentenceWithoutDollarAndWithoutStar}*$hex"
    }

    /* =========================
       UI UPDATE
       ========================= */

    private fun updateNavUI() {
        if (!uiReady) return
        runOnUiThread {
            val inct = currentInct
            if (inct != null) {
                txtXinctNav.text = String.format(Locale.US, "X : %.2f m", inct.x)
                txtYinctNav.text = String.format(Locale.US, "Y : %.2f m", inct.y)
                txtZinctNav.text = String.format(Locale.US, "Z : %.2f m", inct.z)
            } else {
                txtXinctNav.text = "X : ---"
                txtYinctNav.text = "Y : ---"
                txtZinctNav.text = "Z : ---"
            }

            txtSatNav.text = "Satellites : $satellites"

            val fixText = when (fixQuality) {
                1 -> "Single"
                4 -> "RTK Fixe"
                5 -> "RTK Float"
                else -> "No Fix"
            }
            val fixColorRes = when (fixQuality) {
                4 -> android.R.color.holo_green_dark   // RTK Fix
                5 -> android.R.color.holo_orange_dark  // RTK Float
                1 -> android.R.color.holo_red_dark     // Single
                else -> android.R.color.darker_gray
            }
            val fixColor = ContextCompat.getColor(this, fixColorRes)
            txtGnssStatusNav.text = "Statut GNSS : $fixText"
            txtGnssStatusNav.setTextColor(fixColor)

            val prec = when (fixQuality) {
                4 -> "~0.02 m"
                5 -> "~0.10 m"
                1 -> "~5 m"
                else -> "---"
            }
            txtPrecNav.text = "Précision : $prec"

            txtSimulationNav.text = "Simulation : ${if (simRunning) "ON" else "OFF"}"
            txtActiveLeveNav.text =
                "Levé actif : ${if (hasActiveLeve()) fileManager.getFileName() else "NONE"}"

            updateGnssBanners()
            updateImplantationUI()
        }
    }

    
    private fun updateMapStatusPanel(isImplantation: Boolean) {
        if (!uiReady) return
        if (!::txtMapFix.isInitialized) return

        val fixText = when (fixQuality) {
            1 -> "Single"
            4 -> "RTK Fix"
            5 -> "RTK Float"
            else -> "No Fix"
        }
        val fixColorRes = when (fixQuality) {
            4 -> android.R.color.holo_green_dark
            5 -> android.R.color.holo_orange_dark
            1 -> android.R.color.holo_red_dark
            else -> android.R.color.darker_gray
        }
        val fixColor = ContextCompat.getColor(this, fixColorRes)

        txtMapFix.text = "Fix : $fixText"
        txtMapFix.setTextColor(fixColor)

        val prec = currentPrecisionMeters()
        txtMapPrecision.text = "Précision : " + (prec?.let { formatMeters(it) } ?: "---")
        txtMapSats.text = "Sat : $satellites"

        val inct = currentInct
        txtMapInct.text = if (inct != null) {
            "INCT : X=${formatMeters(inct.x)}  Y=${formatMeters(inct.y)}  Z=${formatMeters(inct.z)}  (Z${inct.zone})"
        } else {
            "INCT : ---"
        }

        // Recording indicator
        val rec = hasActiveLeve()
        txtMapRec.visibility = if (rec) View.VISIBLE else View.GONE

        // Implantation info
        txtMapTarget.visibility = if (isImplantation) View.VISIBLE else View.GONE
        layoutMapDelta.visibility = if (isImplantation) View.VISIBLE else View.GONE
        layoutMapDelta2.visibility = if (isImplantation) View.VISIBLE else View.GONE

        if (isImplantation) {
            val target = selectedTarget
            txtMapTarget.text = if (target != null) "Cible : ${target.id}" else "Cible : ---"
            val d = lastImplantDhMeters
            txtMapDistance.text = "Distance : " + (d?.let { formatMeters(it) } ?: "---")
            txtMapDeltaFB.text = "ΔX : " + (lastImplantDxMeters?.let { formatMeters(it) } ?: "---")
            txtMapDeltaLR.text = "ΔY : " + (lastImplantDyMeters?.let { formatMeters(it) } ?: "---")
            txtMapOk.visibility = if (lastImplantOk) View.VISIBLE else View.GONE
        } else {
            txtMapOk.visibility = View.GONE
        }
    }

private fun updateSurveyUI() {
        if (!uiReady) return
        runOnUiThread {
            val fixText = when (fixQuality) {
                1 -> "Single"
                4 -> "RTK Fixe"
                5 -> "RTK Float"
                else -> "No Fix"
            }
            txtFixSurvey.text = "Fix : $fixText"
            val fixColorRes = when (fixQuality) {
                4 -> android.R.color.holo_green_dark
                5 -> android.R.color.holo_orange_dark
                1 -> android.R.color.holo_red_dark
                else -> android.R.color.darker_gray
            }
            val fixColor = ContextCompat.getColor(this, fixColorRes)
            txtFixSurvey.setTextColor(fixColor)

            val hasActiveLeve = hasActiveLeve()
            val inct = currentInct
            val canRecord = hasActiveLeve && stationReady && inct != null && fixQuality in listOf(1, 4, 5)
            val precisionOk = isPrecisionLockSatisfied()

            txtLeveLocked.visibility = if (hasActiveLeve && stationReady) View.GONE else View.VISIBLE
            txtLeveLocked.text = when {
                !hasActiveLeve -> getString(R.string.leve_locked_message)
                !stationReady -> "Mise en station obligatoire"
                                else -> getString(R.string.leve_locked_message)
            }

            if (hasActiveLeve) {
                txtFileName.text = "Levé : ${fileManager.getFileName()}"
                txtCounter.text = "Points : ${fileManager.getCount()}"
                updateLeveSelectionUi(true)
            } else {
                txtFileName.text = "Levé : ---"
                txtCounter.text = "Points : 0"
                updateLeveSelectionUi(false)
            }

            txtRecStatus.text = when {
                !hasActiveLeve -> "Aucun levé actif"
                fixQuality == 5 -> "⚠️ GNSS FLOAT : enregistrement autorisé"
                fixQuality == 4 -> "GNSS RTK FIX : prêt"
                fixQuality == 1 -> "GNSS SINGLE : prêt"
                else -> "GNSS NON PRÊT"
            }

            updateSurveyContextInfo()
            updatePrecisionStatusUi()
            refreshPointIdFieldIfNotEditing()
            btnSavePoint.isEnabled = canRecord && precisionOk
            updateGnssBanners()
            if (::txtMapLocked.isInitialized) {
                updateMapLockState()
            }
            updateMapStatusPanel(isImplantation = (layoutMapActionsImplantation.visibility == View.VISIBLE))
        }
    }

    private fun updateImplantationUI() {
        if (!uiReady) return
        runOnUiThread {
            val locked = !(hasActiveLeve() && stationReady)
            txtImplantationLocked.visibility = if (locked) View.VISIBLE else View.GONE

            val crsLabel = if (hasActiveLeve()) getDisplayCrsTag() else "---"
            txtImplantationCrsDisplay.text = "CRS affiché : $crsLabel"

            fun currentDisplay(): Triple<Double, Double, Double> {
                return when (crsLabel) {
                    "WGS84_geog" -> Triple(lon, lat, alt)
                    "WGS84_utm" -> {
                        val utm = if (lat == 0.0 && lon == 0.0) null else wgs84ToUtm(lat, lon)
                        Triple(utm?.easting ?: 0.0, utm?.northing ?: 0.0, alt)
                    }
                    else -> {
                        val inct = currentInct
                        Triple(inct?.x ?: 0.0, inct?.y ?: 0.0, inct?.z ?: 0.0)
                    }
                }
            }

            fun targetDisplay(t: TargetPoint): Triple<Double, Double, Double> {
                return when (crsLabel) {
                    "WGS84_geog" -> Triple(t.lon, t.lat, (t.alt ?: t.zInct ?: 0.0))
                    "WGS84_utm" -> {
                        val utm = if (t.xWgs84Utm != null && t.yWgs84Utm != null) {
                            UtmCoordinate(t.xWgs84Utm, t.yWgs84Utm)
                        } else {
                            wgs84ToUtm(t.lat, t.lon)
                        }
                        Triple(utm.easting, utm.northing, (t.alt ?: t.zInct ?: 0.0))
                    }
                    else -> Triple(t.xInct, t.yInct, (t.zInct ?: 0.0))
                }
            }

            val target = selectedTarget
            val inct = currentInct


// Mode banner + reference-line controls
txtImplantationMode.text = if (implantMode == ImplantMode.POINT) "MODE : IMPLANT PT" else "MODE : REF LIGNE"
layoutRefLine.visibility = if (implantMode == ImplantMode.REF_LINE) View.VISIBLE else View.GONE
btnSelectImplantationTarget.text = if (implantMode == ImplantMode.REF_LINE) "CHOISIR CIBLE (OPTION)" else "CHOISIR CIBLE"
if (implantMode == ImplantMode.REF_LINE) {
    val aLbl = refLineAId ?: "A ?"
    val bLbl = refLineBId ?: "B ?"
    txtRefLineSummary.text = "Ligne : $aLbl - $bLbl"
}



if (implantMode == ImplantMode.REF_LINE) {
    updateImplantationRefLine(crsLabel)
    return@runOnUiThread
}

            if (target == null) {
                txtImplantationTargetSummary.setText(R.string.implantation_target_placeholder)
                txtImplantationTargetInct.setText(R.string.implantation_target_inct_placeholder)
                txtCompassDistance.text = "NI"
                updateTargetLineOnMap(null, null, null, null, null)
                clearTargetCircle()
            } else {
                val zText = target.zInct?.let { String.format(Locale.US, "%.2f m", it) } ?: "NI"
                txtImplantationTargetSummary.text = String.format(
                    Locale.US,
                    "Cible : %s %s | Lat: %.8f | Lon: %.8f | Z: %s",
                    target.sourceLabel,
                    target.id,
                    target.lat,
                    target.lon,
                    zText
                )
                val (tx, ty, tz) = targetDisplay(target)
                txtImplantationTargetInct.text = when (crsLabel) {
                    "WGS84_geog" -> String.format(Locale.US, "Cible (%s) : Lon=%.8f°  Lat=%.8f°  Alt=%.2f m", crsLabel, tx, ty, tz)
                    "WGS84_utm" -> String.format(Locale.US, "Cible (%s) : X=%.2f m  Y=%.2f m  Z=%.2f m", crsLabel, tx, ty, tz)
                    else -> String.format(Locale.US, "Cible (%s) : X=%.2f m  Y=%.2f m  Z=%.2f m", crsLabel, tx, ty, tz)
                }
            }

            if (inct == null) {
                txtImplantationCurrentInct.setText(R.string.implantation_current_placeholder)
                txtImplantationDeltas.setText(R.string.implantation_delta_placeholder)
                txtImplantationDistances.setText(R.string.implantation_distance_placeholder)
                txtCompassDistance.text = "NI"
                stopProximityBeep()
                updateTargetLineOnMap(null, null, null, null, null)
                clearTargetCircle()
                resetGuidanceSchedule()
                return@runOnUiThread
            }

            txtImplantationCurrentInct.text = String.format(
                Locale.US,
                when (crsLabel) {
                    "WGS84_geog" -> "Actuel (%s) : Lon=%.8f°  Lat=%.8f°  Alt=%.2f m"
                    else -> "Actuel (%s) : X=%.2f m  Y=%.2f m  Z=%.2f m"
                },
                crsLabel,
                currentDisplay().first,
                currentDisplay().second,
                currentDisplay().third
            )

            if (target == null) {
                txtImplantationDeltas.setText(R.string.implantation_delta_placeholder)
                txtImplantationDistances.setText(R.string.implantation_distance_placeholder)
                txtCompassDistance.text = "NI"
                stopProximityBeep()
                updateTargetLineOnMap(null, null, null, null, null)
                clearTargetCircle()
                resetGuidanceSchedule()
                return@runOnUiThread
            }

            val currentLat = if (lat == 0.0 && lon == 0.0) null else lat
            val currentLon = if (lat == 0.0 && lon == 0.0) null else lon

            val nowElapsed = SystemClock.elapsedRealtime()
            val targetKey = "${target.sourceLabel}:${target.id}"
            val shouldUpdateGuidance = lastGuidanceUpdateElapsed == 0L ||
                    targetKey != lastGuidanceTargetKey ||
                    nowElapsed - lastGuidanceUpdateElapsed >= guidanceUpdateIntervalMs

            if (shouldUpdateGuidance) {
                lastGuidanceUpdateElapsed = nowElapsed
                lastGuidanceTargetKey = targetKey

                val (cx, cy, cz) = currentDisplay()
                val (tx, ty, tz) = targetDisplay(target)

                val dx: Double
                val dy: Double
                val dz: Double
                val dh: Double
                val headingRad: Double

                if (crsLabel == "WGS84_geog") {
                    dx = tx - cx // ΔLon (deg)
                    dy = ty - cy // ΔLat (deg)
                    dz = cz - tz
                    dh = distanceMeters(cy, cx, ty, tx) // meters
                    headingRad = Math.toRadians(bearingDeg(cy, cx, ty, tx))
                } else {
                    dx = tx - cx
                    dy = ty - cy
                    dz = cz - tz
                    dh = sqrt(dx * dx + dy * dy)
                    headingRad = computeGuidanceHeading(cx, cy, dx, dy)
                }

                val dxGuidance = if (crsLabel == "WGS84_geog") {
                    // Convert degrees to approx meters for guidance UI/voice.
                    val metersPerDegLat = 111320.0
                    val metersPerDegLon = cos(Math.toRadians(cy)) * 111320.0
                    dx * metersPerDegLon
                } else dx

                val dyGuidance = if (crsLabel == "WGS84_geog") {
                    val metersPerDegLat = 111320.0
                    dy * metersPerDegLat
                } else dy

                lastImplantDhMeters = dh
                lastImplantDxMeters = dxGuidance
                lastImplantDyMeters = dyGuidance
                Log.d(
                    TAG,
                    String.format(
                        Locale.US,
                        "Guidance inputs: current=(%.2f, %.2f) target=(%.2f, %.2f) dx=%.6f dy=%.6f dh=%.2f " +
                                "okThreshold=1.0m arrowEps=0.01m",
                        cx,
                        cy,
                        tx,
                        ty,
                        dx,
                        dy,
                        dh
                    )
                )
                Log.d(
                    TAG,
                    "Implantation ΔZ: currentZ=${String.format(Locale.US, "%.2f", inct.z)} " +
                            "targetZ=${target.zInct?.let { String.format(Locale.US, "%.2f", it) } ?: "NI"} " +
                            "dz=${String.format(Locale.US, "%.2f", dz)}"
                )

                val dzText = String.format(Locale.US, "%.2f m", dz)
                txtImplantationDeltas.text = if (crsLabel == "WGS84_geog") {
                    String.format(Locale.US, "ΔLon : %.8f°  ΔLat : %.8f°  ΔZ: %s", dx, dy, dzText)
                } else {
                    String.format(Locale.US, "ΔX : %.2f m  ΔY : %.2f m  ΔZ: %s", dx, dy, dzText)
                }

                val d3Text = String.format(Locale.US, "%.2f m", sqrt((dh * dh) + (dz * dz)))
                txtImplantationDistances.text = String.format(
                    Locale.US,
                    "Dh : %.2f m  D3: %s",
                    dh,
                    d3Text
                )
                val dhText = String.format(Locale.US, "%.2f m", dh)
                txtCompassDistance.text = dhText
                Log.d(TAG, "Guidance compass distance label: $dhText")

                // ✅ UI flèches : on garde l'affichage, mais Z est toujours NI (pas de monte/descends en audio)
                updateGuidanceUI(
                    xNow = cx,
                    yNow = cy,
                    dx = dxGuidance,
                    dy = dyGuidance,
                    dz = dz,
                    headingRad = headingRad
                )

                if (currentLat != null && currentLon != null) {
                    val bearingToTarget = bearingDeg(currentLat, currentLon, target.lat, target.lon)
                    val headingDeg = Math.toDegrees(headingRad)
                    val rotation = ((bearingToTarget - headingDeg + 360.0) % 360.0).toFloat()
                    layoutCompassArrow.rotation = rotation
                }

                // ✅ Vecteurs guidage (pour la voix)
                val (forward, left) = computeGuidanceVectors(inct.x, inct.y, dx, dy)

                // ✅ Voix : UNE consigne à la fois, avec alternance LR / FB
                speakGuidanceIfNeeded(
                    dh = dh,
                    forward = forward,
                    left = left
                )

                lastImplantOk = (dh <= 1.0)
                if (dh <= 1.0) startProximityBeep() else stopProximityBeep()
            }

            updateTargetLineOnMap(currentLat, currentLon, target.lat, target.lon, null)
            if (showTargetCircle) {
                updateTargetCircle(target.lat, target.lon, 0.02)
            } else {
                clearTargetCircle()
            }

            updateMapStatusPanel(isImplantation = true)
        }
    }

    /* =========================
       GUIDAGE UI (flèches)
       ========================= */

    private fun updateGuidanceUI(
        xNow: Double,
        yNow: Double,
        dx: Double,
        dy: Double,
        dz: Double?,
        headingRad: Double? = null
    ) {
        val heading = headingRad ?: computeGuidanceHeading(xNow, yNow, dx, dy)
        val forward = dx * cos(heading) + dy * sin(heading)
        val left = -dx * sin(heading) + dy * cos(heading)

        applyArrowState(imgForward, txtForwardCm, forward, 0f, 180f)
        applyArrowState(imgLeft, txtLeftCm, left, 270f, 90f)

        applyUpDownState(imgUpDown, txtDzCm, dz)
    }

    private fun updateHeading(xNow: Double, yNow: Double) {
        if (prevInctX == null || prevInctY == null) {
            prevInctX = xNow
            prevInctY = yNow
            return
        }

        val mx = xNow - (prevInctX ?: xNow)
        val my = yNow - (prevInctY ?: yNow)
        val move = sqrt(mx * mx + my * my)
        if (move >= 0.20) {
            lastHeadingRad = atan2(my, mx)
            headingValid = true
        }
        prevInctX = xNow
        prevInctY = yNow
    }

    private fun computeGuidanceHeading(xNow: Double, yNow: Double, dx: Double, dy: Double): Double {
        updateHeading(xNow, yNow)
        return if (headingValid) lastHeadingRad else atan2(dy, dx)
    }

    private fun computeGuidanceVectors(
        xNow: Double,
        yNow: Double,
        dx: Double,
        dy: Double
    ): Pair<Double, Double> {
        val heading = computeGuidanceHeading(xNow, yNow, dx, dy)
        val forward = dx * cos(heading) + dy * sin(heading)
        val left = -dx * sin(heading) + dy * cos(heading)
        return forward to left
    }

    /* =========================
       GUIDAGE VOCAL : ALTERNANCE
       ========================= */

    private fun resetGuidanceSchedule() {
        nextGuidanceSpeakAtElapsed = 0L
        guidanceNextAxis = 0
        okGuidanceArmed = true
        lastGuidanceKey = null
        lastGuidanceSpokenAtElapsed = 0L
        lastGuidanceUpdateElapsed = 0L
        lastGuidanceTargetKey = null
    }

    private fun shouldPlayGuidanceSound(): Boolean {
        return guidanceVoiceEnabled &&
                ::screenImplantation.isInitialized &&
                screenImplantation.visibility == View.VISIBLE &&
                selectedTarget != null &&
                currentInct != null
    }

    private fun releaseGuidancePlayer() {
        try {
            guidancePlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            guidancePlayer?.release()
        } catch (_: Exception) {
        }
        guidancePlayer = null
    }

    // ✅ Voix/Son : lecture des consignes guidage via MediaPlayer.
    
// ✅ Voix/Son : consignes guidage via TTS (avancer/reculer/à gauche/à droite).
private fun playGuidanceSound(key: GuidanceKey) {
    if (!shouldPlayGuidanceSound()) return

    // Stop any previous MediaPlayer usage (legacy) to avoid overlap.
    releaseGuidancePlayer()

    val phrase = when (key) {
        GuidanceKey.LEFT -> "à gauche"
        GuidanceKey.RIGHT -> "à droite"
        GuidanceKey.FORWARD -> "avancer"
        GuidanceKey.BACK -> "reculer"
        GuidanceKey.OK -> "ok"
        GuidanceKey.TARGET_SELECTED -> "ok"
    }

    // Short, high-priority speech
    speak(phrase)
}
    /**
     * ✅ Règles guidage :
     * - Toutes les 5s, jouer UNE seule consigne.
     * - Alternance stricte LR puis FB puis LR...
     * - Zone OK (Dh <= 1.0 m) : jouer ok.wav une seule fois puis silence.
     */
    
/**
 * Choix de consigne plus "fluide" :
 * - priorité à l'axe le plus "erreur" (|left| vs |forward|)
 * - deadband : si l'écart est faible, on évite de changer de consigne
 * - alternance conservée mais uniquement quand les deux axes ont une erreur significative
 */
private fun chooseGuidanceKeyAlternating(
    forward: Double,
    left: Double
): GuidanceKey {
    val absF = kotlin.math.abs(forward)
    val absL = kotlin.math.abs(left)

    val deadband = 0.25 // m
    val bothSignificant = absF >= deadband && absL >= deadband

    // If only one axis is significant, speak that axis (no forced alternation)
    val axis = when {
        absL < deadband && absF < deadband -> "OK"
        absL < deadband -> "FB"
        absF < deadband -> "LR"
        else -> if (bothSignificant) (if (guidanceNextAxis == 0) "LR" else "FB") else (if (absL > absF) "LR" else "FB")
    }

    val key = when (axis) {
        "LR" -> if (left >= 0.0) GuidanceKey.LEFT else GuidanceKey.RIGHT
        "FB" -> if (forward >= 0.0) GuidanceKey.FORWARD else GuidanceKey.BACK
        else -> GuidanceKey.OK
    }

    if (bothSignificant) {
        guidanceNextAxis = 1 - guidanceNextAxis
    }

    Log.d(
        TAG,
        "Guidance choice: axis=$axis key=$key forward=%.2f left=%.2f".format(
            Locale.US,
            forward,
            left
        )
    )
    return key
}

private fun adaptiveGuidanceIntervalMs(dh: Double): Long {
    return when {
        dh >= 30.0 -> 2000L
        dh >= 10.0 -> 3000L
        dh >= 3.0 -> 4000L
        else -> 5000L
    }
}

private fun speakGuidanceIfNeeded(
        dh: Double,
        forward: Double,
        left: Double
    ) {
        if (!shouldPlayGuidanceSound()) return
        val nowElapsed = SystemClock.elapsedRealtime()
        if (dh <= 1.0) {
            if (okGuidanceArmed) {
                val key = GuidanceKey.OK
                if (key != lastGuidanceKey || (nowElapsed - lastGuidanceSpokenAtElapsed) >= 2000L) {
                    playGuidanceSound(key)
                    lastGuidanceKey = key
                    lastGuidanceSpokenAtElapsed = nowElapsed
                    okGuidanceArmed = false
                }
            }
            return
        }

        okGuidanceArmed = true
        if (nextGuidanceSpeakAtElapsed == 0L) nextGuidanceSpeakAtElapsed = nowElapsed
        if (nowElapsed < nextGuidanceSpeakAtElapsed) return

        val key = chooseGuidanceKeyAlternating(forward, left)
        if (key == lastGuidanceKey && (nowElapsed - lastGuidanceSpokenAtElapsed) < 2000L) {
            nextGuidanceSpeakAtElapsed = nowElapsed + adaptiveGuidanceIntervalMs(dh)
            return
        }
        playGuidanceSound(key)
        lastGuidanceKey = key
        lastGuidanceSpokenAtElapsed = nowElapsed
        nextGuidanceSpeakAtElapsed = nowElapsed + adaptiveGuidanceIntervalMs(dh)
    }

    private fun speakTargetSelected() {
        if (!shouldPlayGuidanceSound()) return
        playGuidanceSound(GuidanceKey.OK)

        // reset alternance au moment du choix (on commence par LR)
        guidanceNextAxis = 0

        val inct = currentInct
        val target = selectedTarget
        if (inct != null && target != null) {
            okGuidanceArmed = true
            nextGuidanceSpeakAtElapsed = 0L
        } else {
            resetGuidanceSchedule()
        }
    }

    private fun fmtAdaptive(valueMeters: Double): String {
        val absMeters = abs(valueMeters)
        return String.format(Locale.US, "%.2f m", absMeters)
    }

    private fun colorForAbsCm(absCm: Double): Int {
        return when {
            absCm <= 2.0 -> ContextCompat.getColor(this, R.color.guid_ok)
            absCm <= 20.0 -> ContextCompat.getColor(this, R.color.guid_approach)
            else -> ContextCompat.getColor(this, R.color.guid_far)
        }
    }

    private fun applyArrowState(
        img: ImageView,
        txt: TextView,
        signedMeters: Double,
        rotationPos: Float,
        rotationNeg: Float,
        epsMeters: Double = 0.01
    ) {
        val absCm = abs(signedMeters) * 100.0
        val color = colorForAbsCm(absCm)
        val rotation = if (signedMeters < -epsMeters) rotationNeg else rotationPos

        img.rotation = rotation
        img.setColorFilter(color)
        txt.text = fmtAdaptive(signedMeters)
        txt.setTextColor(color)
    }

    private fun applyNiState(img: ImageView, txt: TextView) {
        val color = ContextCompat.getColor(this, R.color.guid_ni)
        img.rotation = 0f
        img.setColorFilter(color)
        txt.text = "NI"
        txt.setTextColor(color)
    }

    private fun applyUpDownState(img: ImageView, txt: TextView, dz: Double?) {
        if (dz == null) {
            applyNiState(img, txt)
            return
        }

        val absMeters = abs(dz)
        val color = when {
            absMeters <= 0.02 -> ContextCompat.getColor(this, R.color.guid_ok)
            absMeters <= 0.20 -> ContextCompat.getColor(this, R.color.guid_approach)
            else -> ContextCompat.getColor(this, R.color.guid_far)
        }
        val rotation = when {
            dz > 0.01 -> 0f
            dz < -0.01 -> 180f
            else -> 0f
        }

        img.rotation = rotation
        img.setColorFilter(color)
        txt.text = String.format(Locale.US, "%.2f m", absMeters)
        txt.setTextColor(color)
    }

    private fun startProximityBeep() {
        if (!soundEnabled) return
        if (toneGenerator == null) {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        }
        toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
    }

    private fun stopProximityBeep() {
        toneGenerator?.stopTone()
    }

    private fun releaseToneGenerator() {
        toneGenerator?.release()
        toneGenerator = null
    }

    /* =========================
       ID TOPO (prefix + compteur)
       ========================= */

    private fun applyPointIdFromUI() {
        val s = edtPointId.text.toString().trim()
        if (s.isEmpty()) {
            refreshPointIdFieldIfNotEditing(force = true)
            return
        }

        // accepte : "clot" ou "clot1" ou "P0007" etc.
        val m = Regex("^(.+?)(\\d+)?$").find(s)
        if (m == null) {
            Toast.makeText(this, "Format ID invalide", Toast.LENGTH_SHORT).show()
            refreshPointIdFieldIfNotEditing(force = true)
            return
        }

        val prefix = m.groupValues[1].trim()
        val numStr = m.groupValues.getOrNull(2)?.trim().orEmpty()

        pointPrefix = prefix
        pointIndex = if (numStr.isNotEmpty()) numStr.toIntOrNull() ?: 1 else 1

        // Ensure we don't land on an existing ID.
        pointId = formatPointId(pointPrefix, pointIndex)
        if (usedPointIds.contains(pointId)) {
            pointIndex = nextFreeIndexForPrefix(pointPrefix, startAt = pointIndex)
            pointId = formatPointId(pointPrefix, pointIndex)
        }

        hideKeyboard()
        Toast.makeText(this, "ID départ : $pointId", Toast.LENGTH_SHORT).show()
        refreshPointIdFieldIfNotEditing(force = true)
        updateSurveyUI()
    }

    private fun nextPointId() {
        pointIndex = nextFreeIndexForPrefix(pointPrefix, startAt = pointIndex + 1)
        pointId = formatPointId(pointPrefix, pointIndex)
    }

    private fun formatPointId(prefix: String, index: Int): String {
        return if (prefix.equals("STA", ignoreCase = true)) {
            "STA" + String.format(Locale.US, "%02d", index)
        } else {
            "$prefix$index"
        }
    }

    private fun nextFreeIndexForPrefix(prefix: String, startAt: Int): Int {
        var idx = if (startAt <= 0) 1 else startAt
        while (usedPointIds.contains(formatPointId(prefix, idx))) {
            idx += 1
        }
        return idx
    }

    private fun refreshPointIdFieldIfNotEditing(force: Boolean = false) {
        if (!::edtPointId.isInitialized) return
        if (force || !edtPointId.hasFocus()) {
            edtPointId.setText(pointId)
            edtPointId.setSelection(pointId.length)
        }
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(edtPointId.windowToken, 0)
        } catch (_: Exception) {
        }
    }

    private fun reloadUsedPointIdsFromCurrentLeve() {
        usedPointIds.clear()
        val uri = levesUri ?: return
        runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                // Skip metadata/comment lines
                var headerLine: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    val t = line.trim()
                    if (t.isEmpty()) continue
                    if (t.startsWith("#")) continue
                    headerLine = line
                    break
                }
                if (headerLine == null) return@use
                val headers = headerLine!!.split(",").map { it.trim().lowercase(Locale.getDefault()) }
                val idxId = headers.indexOf("id")
                if (idxId < 0) return@use
                reader.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val parts = line.split(",")
                    if (idxId !in parts.indices) return@forEachLine
                    val id = parts[idxId].trim()
                    if (id.isNotBlank()) usedPointIds.add(id)
                }
            }
        }
    }

    private fun initAutoPointId(prefix: String = "P") {
        pointPrefix = prefix
        // Start after max existing index for this prefix.
        val p = if (pointPrefix.equals("STA", ignoreCase = true)) "STA" else pointPrefix
        val regex = Regex("^" + Regex.escape(p) + "(\\d+)$", RegexOption.IGNORE_CASE)
        var maxIdx = 0
        usedPointIds.forEach { id ->
            val m = regex.find(id.trim()) ?: return@forEach
            val n = m.groupValues.getOrNull(1)?.toIntOrNull() ?: return@forEach
            if (n > maxIdx) maxIdx = n
        }
        pointIndex = nextFreeIndexForPrefix(pointPrefix, startAt = (maxIdx + 1).coerceAtLeast(1))
        pointId = formatPointId(pointPrefix, pointIndex)
        refreshPointIdFieldIfNotEditing(force = true)
    }

    /* =========================
       LEVÉS : FICHIER / POINTS
       ========================= */

    private fun toggleFile() {
        if (!recording) {
            createLeveFile()
            return
        }

        recording = false
        val countBeforeClose = fileManager.getCount()
        fileManager.close()
        activeLeveCrs = null
        activeLeveSettingsUri = null
        activeLeveSettings = null
        usedPointIds.clear()
        initAutoPointId(prefix = "P")
        stationReady = false
        stationDx = 0.0
        stationDy = 0.0
        stationDz = 0.0
        updateSurveyUI()

        Toast.makeText(this, "✅ Enregistrement terminé ($countBeforeClose points)", Toast.LENGTH_LONG)
            .show()
    }

    private fun ensureLoadAndApplyLeveSettings(levesDir: DocumentFile, csvFile: DocumentFile, initialCrs: ProjectCrs) {
        val settingsFile = LeveSettingsStore.ensureExists(this, levesDir, csvFile)
        activeLeveSettingsUri = settingsFile?.uri
        val loaded = settingsFile?.uri?.let { LeveSettingsStore.load(contentResolver, it) }
        val effective = loaded ?: LeveSettings(version = 2)
        activeLeveSettings = effective

        // Display CRS (UI) comes from the CSV metadata.
        activeLeveCrs = initialCrs

        // Apply stationing
        stationReady = effective.stationing.enabled
        stationDx = effective.stationing.dx
        stationDy = effective.stationing.dy
        stationDz = effective.stationing.dz
    }

    /**
     * Storage CRS for the levé CSV.
     * The first coordinate columns are ALWAYS INCT: xinct/yinct/zinct.
     */
    private fun getStorageCrs(): ProjectCrs = ProjectCrs.INCT_UTM_ALGERIA

    /** CRS currently selected for UI display. */
    private fun getDisplayCrs(): ProjectCrs = activeLeveCrs ?: ProjectCrs.INCT_UTM_ALGERIA

    private fun getWorkCrs(): ProjectCrs {
        // Calculations remain in storage CRS.
        return getStorageCrs()
    }

    /**
     * UI display CRS tag (only 3 values expected by the app):
     * - INCT_utm
     * - WGS84_utm
     * - WGS84_geog
     */
    private fun getDisplayCrsTag(): String {
        val crs = getDisplayCrs()
        return when {
            crs.id == ProjectCrs.INCT_UTM_ALGERIA.id -> "INCT_utm"
            crs.id == ProjectCrs.WGS84_LATLON.id -> "WGS84_geog"
            else -> "WGS84_utm" // wgs84_utm_auto or explicit zones
        }
    }

    private fun persistCurrentLeveSettings() {
        val uri = activeLeveSettingsUri ?: return
        val current = activeLeveSettings ?: return
        LeveSettingsStore.save(contentResolver, uri, current)
    }

    private fun createLeveFile() {
        val et = EditText(this).apply {
            hint = "Nom du levé"
            inputType = InputType.TYPE_CLASS_TEXT
        }

        AlertDialog.Builder(this)
            .setTitle("Nouveau levé")
            .setView(et)
            .setPositiveButton("Créer") { _, _ ->
                val raw = et.text?.toString()?.trim().orEmpty()
                val name = sanitizeSurveyName(raw)
                if (name.isBlank()) {
                    Toast.makeText(this, "Nom invalide", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                showLeveCrsChooser(
                    title = "CRS affiché",
                    onChosen = { crs ->
                        createLeveFileWithNameAndCrs(name, crs)
                    }
                )
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun createLeveFileWithNameAndCrs(
        surveyName: String,
        crs: ProjectCrs
    ) {

        val levesDir = resolveChantierDir(this)
        if (levesDir == null) {
            Toast.makeText(this, "Chantier non défini", Toast.LENGTH_SHORT).show()
            return
        }

        val df = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val requestedName = "${surveyName}_${df.format(Date())}.csv"
        val file = levesDir.createFile("text/csv", requestedName)
        if (file == null) {
            Toast.makeText(this, "❌ Erreur création levé", Toast.LENGTH_LONG).show()
            return
        }

        // Ensure per-levé settings file exists and drive work CRS from it.
        ensureLoadAndApplyLeveSettings(levesDir, file, crs)
        fileManager.open(file.uri, file.name ?: requestedName, crs)
        if (!fileManager.isOpen()) {
            Toast.makeText(this, "❌ Erreur ouverture levé", Toast.LENGTH_LONG).show()
            return
        }
        levesUri = file.uri
        recording = true
        // station vars already loaded from settings (defaults are disabled)
        updateSurveyUI()
        Toast.makeText(this, "📁 Levé créé: ${fileManager.getFileName()}", Toast.LENGTH_LONG).show()
        onLeveOpenedOrCreated(showStation = false)
    }

    /**
     * Common post-open logic:
     * - Always redirect to Levé screen.
     * - Rebuild used IDs cache and propose the next auto ID.
     * - Keep measurement blocked until stationReady=true (handled elsewhere).
     */
    private fun onLeveOpenedOrCreated(showStation: Boolean) {
        showLeve()
        reloadUsedPointIdsFromCurrentLeve()
        initAutoPointId(prefix = "P")
        updateSurveyUI()
        if (showStation) {
            showStationSetupDialog(force = false)
        }
    }

    // =========================
    // IMPORT LEVÉ (CSV/TXT)
    // =========================

    private fun startImportLeveWizard() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 0)
        }

        val etName = EditText(this).apply {
            hint = "Nom du levé (sans accents)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        container.addView(etName)

        val typeLabel = TextView(this).apply {
            text = "Format du fichier"
            setPadding(0, 18, 0, 6)
        }
        container.addView(typeLabel)

        val rgType = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        val rbXY = RadioButton(this).apply { text = "XY : id,xinct,yinct,zinct (Z optionnel)"; id = View.generateViewId() }
        val rbLLH = RadioButton(this).apply { text = "LLH : id,lat,lon,h (h optionnel)"; id = View.generateViewId() }
        rgType.addView(rbXY)
        rgType.addView(rbLLH)
        rbXY.isChecked = true
        container.addView(rgType)

        val crsLabel = TextView(this).apply {
            text = "CRS (affichage levé)"
            setPadding(0, 18, 0, 6)
        }
        container.addView(crsLabel)

        val spCrs = Spinner(this)
        val crsOptions = listOf(
            "INCT UTM (30N/31N/32N)",
            "WGS84 UTM (zone 1..60 + N/S)",
            "WGS84 Lat/Lon/h (ellipsoïde)"
        )
        spCrs.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, crsOptions)
        container.addView(spCrs)

        // INCT zone spinner
        val spInctZone = Spinner(this)
        val inctZones = listOf("30N", "31N", "32N")
        spInctZone.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, inctZones)
        container.addView(spInctZone)

        // WGS84 UTM: zone (1..60) + hemisphere N/S
        val utmRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 0)
        }
        val npZone = NumberPicker(this).apply {
            minValue = 1
            maxValue = 60
            value = 31
            wrapSelectorWheel = false
        }
        val rgHemi = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val rbN = RadioButton(this).apply { text = "N"; id = View.generateViewId() }
        val rbS = RadioButton(this).apply { text = "S"; id = View.generateViewId() }
        rgHemi.addView(rbN)
        rgHemi.addView(rbS)
        rbN.isChecked = true
        utmRow.addView(npZone)
        utmRow.addView(Space(this).apply { layoutParams = LinearLayout.LayoutParams(24, 1) })
        utmRow.addView(rgHemi)
        container.addView(utmRow)

        fun refreshZoneControls() {
            val idx = spCrs.selectedItemPosition
            when (idx) {
                0 -> { // INCT
                    spInctZone.visibility = View.VISIBLE
                    utmRow.visibility = View.GONE
                }
                1 -> { // WGS84 UTM
                    spInctZone.visibility = View.GONE
                    utmRow.visibility = View.VISIBLE
                }
                else -> { // WGS84 LLH
                    spInctZone.visibility = View.GONE
                    utmRow.visibility = View.GONE
                }
            }
        }
        refreshZoneControls()
        spCrs.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                refreshZoneControls()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        AlertDialog.Builder(this)
            .setTitle("Importer un levé")
            .setView(container)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Suivant") { _, _ ->
                val rawName = etName.text?.toString()?.trim().orEmpty()
                val name = sanitizeSurveyName(rawName)
                val allowed = Regex("^[a-zA-Z0-9_-]+$")
                if (name.isBlank() || !allowed.matches(rawName)) {
                    Toast.makeText(this, "Nom invalide (sans accents, sans espaces)", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val fileType = if (rbLLH.isChecked) ImportFileType.LLH else ImportFileType.XY
                val dest = when (spCrs.selectedItemPosition) {
                    0 -> ImportDestCrs.INCT_UTM
                    1 -> ImportDestCrs.WGS84_UTM
                    else -> ImportDestCrs.WGS84_LLH
                }

                // Rule: XY -> cannot import to Lat/Lon
                if (fileType == ImportFileType.XY && dest == ImportDestCrs.WGS84_LLH) {
                    Toast.makeText(this, "Import XY vers Lat/Lon non supporté. Choisissez un CRS UTM.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                val (zone, hemiNorth) = when (dest) {
                    ImportDestCrs.INCT_UTM -> {
                        val z = (inctZones.getOrNull(spInctZone.selectedItemPosition)?.take(2)?.toIntOrNull())
                        z to true
                    }
                    ImportDestCrs.WGS84_UTM -> npZone.value to rbN.isChecked
                    ImportDestCrs.WGS84_LLH -> null to null
                }

                pendingImportConfig = ImportConfig(
                    surveyName = name,
                    fileType = fileType,
                    destCrs = dest,
                    utmZone = zone,
                    utmHemisphereNorth = hemiNorth
                )

                importFilePicker.launch(arrayOf("text/*", "text/csv", "application/csv", "application/vnd.ms-excel"))
            }
            .show()
    }

    private data class ImportPreview(
        val separator: Char,
        val hasHeader: Boolean,
        val sampleLines: List<String>,
        val sampleInvalid: Int
    )

    private fun showImportPreviewDialog(cfg: ImportConfig, uri: Uri) {
        val preview = buildImportPreview(cfg, uri)
        if (preview == null) {
            Toast.makeText(this, "Impossible de lire le fichier", Toast.LENGTH_LONG).show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 0)
        }

        val txtInfo = TextView(this).apply {
            text = "Aperçu (unités: ${if (cfg.fileType == ImportFileType.XY) "m" else "°/m"})\n" +
                "Séparateur détecté: '${preview.separator}'  |  En-tête: ${if (preview.hasHeader) "oui" else "non"}"
        }
        container.addView(txtInfo)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 0)
        }
        val spSep = Spinner(this)
        val sepLabels = listOf("Auto", ";", ",")
        spSep.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sepLabels)
        row.addView(TextView(this).apply { text = "Séparateur:"; setPadding(0, 8, 16, 0) })
        row.addView(spSep)
        container.addView(row)

        val cbHeader = CheckBox(this).apply {
            text = "La première ligne est un en-tête"
            isChecked = preview.hasHeader
        }
        container.addView(cbHeader)

        val cbInvert = CheckBox(this).apply {
            text = "Inverser lat/lon"
            isChecked = false
            visibility = if (cfg.fileType == ImportFileType.LLH) View.VISIBLE else View.GONE
        }
        container.addView(cbInvert)

        val tvTable = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            text = preview.sampleLines.joinToString("\n")
            setPadding(0, 12, 0, 0)
        }
        val scroll = ScrollView(this)
        scroll.addView(tvTable)
        container.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 700))

        val dlg = AlertDialog.Builder(this)
            .setTitle("Aperçu import")
            .setView(container)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Importer", null)
            .create()

        dlg.setOnShowListener {
            // Preselect separator based on detection
            spSep.setSelection(
                when (preview.separator) {
                    ';' -> 1
                    ',' -> 2
                    else -> 0
                }
            )
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val chosenSep = when (spSep.selectedItemPosition) {
                    1 -> ';'
                    2 -> ','
                    else -> preview.separator
                }
                val hasHeader = cbHeader.isChecked
                val invert = cbInvert.isChecked

                dlg.dismiss()
                runImportLeve(cfg, uri, chosenSep, hasHeader, invert)
            }
        }
        dlg.show()
    }

    private fun buildImportPreview(cfg: ImportConfig, uri: Uri): ImportPreview? {
        return runCatching {
            val lines = contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                val out = mutableListOf<String>()
                while (out.size < 30) {
                    val l = reader.readLine() ?: break
                    val t = l.trim()
                    if (t.isNotEmpty()) out.add(t)
                }
                out
            } ?: return null

            if (lines.isEmpty()) return null

            val first = lines.first()
            val comma = first.count { it == ',' }
            val semi = first.count { it == ';' }
            val sep = if (semi >= comma) ';' else ','

            fun split(line: String, s: Char): List<String> = line.split(s).map { it.trim() }

            val firstParts = split(first, sep)
            val headerGuess = firstParts.any { it.any { ch -> ch.isLetter() } } &&
                firstParts.drop(1).any { it.toDoubleOrNull() == null }

            var invalid = 0
            val sample = mutableListOf<String>()
            val startIdx = if (headerGuess) 1 else 0
            for (i in startIdx until min(lines.size, startIdx + 10)) {
                val p = split(lines[i], sep)
                val formatted = formatPreviewLine(cfg, p)
                if (formatted == null) {
                    invalid++
                    sample.add("(ligne ${i + 1})  ❌")
                } else {
                    sample.add(formatted)
                }
            }

            ImportPreview(separator = sep, hasHeader = headerGuess, sampleLines = sample, sampleInvalid = invalid)
        }.getOrNull()
    }

    private fun formatPreviewLine(cfg: ImportConfig, parts: List<String>): String? {
        return try {
            if (cfg.fileType == ImportFileType.XY) {
                if (parts.size < 3) return null
                val id = parts[0]
                val x = parts[1].toDoubleOrNull() ?: return null
                val y = parts[2].toDoubleOrNull() ?: return null
                val z = parts.getOrNull(3)?.toDoubleOrNull() ?: 0.0
                String.format(Locale.US, "%-12s  X=%.3f m  Y=%.3f m  Z=%.3f m", id, x, y, z)
            } else {
                if (parts.size < 3) return null
                val id = parts[0]
                val lat = parts[1].toDoubleOrNull() ?: return null
                val lon = parts[2].toDoubleOrNull() ?: return null
                val h = parts.getOrNull(3)?.toDoubleOrNull() ?: 0.0
                String.format(Locale.US, "%-12s  lat=%.8f°  lon=%.8f°  h=%.3f m", id, lat, lon, h)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun runImportLeve(cfg: ImportConfig, uri: Uri, sep: Char, hasHeader: Boolean, invertLatLon: Boolean) {
        // Create an empty levé file first (main thread), then write points in background.
        val crsForLeve = when (cfg.destCrs) {
            ImportDestCrs.INCT_UTM -> ProjectCrs.INCT_UTM_ALGERIA
            ImportDestCrs.WGS84_LLH -> ProjectCrs.WGS84_LATLON
            ImportDestCrs.WGS84_UTM -> {
                val zone = cfg.utmZone ?: 31
                val north = cfg.utmHemisphereNorth ?: true
                val epsg = (if (north) 32600 else 32700) + zone
                ProjectCrs(
                    id = "wgs84_utm_${zone}${if (north) "N" else "S"}",
                    label = "WGS84 / UTM zone $zone${if (north) "N" else "S"} (EPSG:$epsg)",
                    epsg = epsg,
                    unitToMeterScale = 1.0,
                    supportedNow = true
                )
            }
        }

        val levesDir = resolveChantierDir(this)
        if (levesDir == null) {
            Toast.makeText(this, "Chantier non défini", Toast.LENGTH_SHORT).show()
            return
        }

        val df = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val requestedName = "${cfg.surveyName}_${df.format(Date())}.csv"
        val file = levesDir.createFile("text/csv", requestedName)
        if (file == null) {
            Toast.makeText(this, "❌ Erreur création levé", Toast.LENGTH_LONG).show()
            return
        }

        // Open file manager for writing (do not navigate yet)
        ensureLoadAndApplyLeveSettings(levesDir, file, crsForLeve)
        fileManager.open(file.uri, file.name ?: requestedName, crsForLeve)
        if (!fileManager.isOpen()) {
            Toast.makeText(this, "❌ Erreur ouverture levé", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this, "Import en cours...", Toast.LENGTH_SHORT).show()

        thread {
            var imported = 0
            var rejected = 0
            val seen = mutableSetOf<String>()
            val renamed = AtomicInteger(0)

            fun normalizeNumberToken(token: String): String {
                // Support French decimal comma when separator is ';'
                return if (sep == ';') token.replace(',', '.') else token
            }

            fun uniqueId(base: String): String {
                var id = base
                if (!seen.add(id)) {
                    var k = 1
                    while (!seen.add("${base}_$k")) k++
                    id = "${base}_$k"
                    renamed.incrementAndGet()
                }
                return id
            }

            runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader()?.useLines { seq ->
                    val iter = seq.iterator()
                    var lineIndex = 0
                    var headerSkipped = false
                    lineLoop@ while (iter.hasNext()) {
                        val rawLine = iter.next()
                        lineIndex++
                        val line = rawLine.trim()
                        if (line.isEmpty()) continue

                        if (!headerSkipped && hasHeader) {
                            headerSkipped = true
                            continue
                        }

                        val parts = line.split(sep).map { it.trim() }
                        try {
                            if (cfg.fileType == ImportFileType.XY) {
                                if (parts.size < 3) { rejected++; continue }
                                val id0 = uniqueId(parts[0])
                                val xParsed = normalizeNumberToken(parts[1]).toDoubleOrNull()
                                if (xParsed == null) { rejected++; continue@lineLoop }
                                val yParsed = normalizeNumberToken(parts[2]).toDoubleOrNull()
                                if (yParsed == null) { rejected++; continue@lineLoop }
                                val x = xParsed
                                val y = yParsed
                                val z = normalizeNumberToken(parts.getOrNull(3).orEmpty()).toDoubleOrNull() ?: 0.0

                                when (cfg.destCrs) {
                                    ImportDestCrs.INCT_UTM -> {
                                        val zone = cfg.utmZone ?: 31
                                        // Convert INCT UTM -> WGS84 lat/lon using same Bursa-Wolf parameters.
                                        val w = inctUtmToWgs84(easting = x, northing = y, zone = zone, alt = z)
                                        val res = InctResult(id = id0, x = x, y = y, z = z, zone = zone)
                                        if (fileManager.write(res, w.lat, w.lon, w.alt, "Import")) imported++ else rejected++
                                    }
                                    ImportDestCrs.WGS84_UTM -> {
                                        val zone = cfg.utmZone
                                        if (zone == null) { rejected++; continue@lineLoop }
                                        val north = cfg.utmHemisphereNorth ?: true
                                        val epsg = (if (north) 32600 else 32700) + zone
                                        val crs = ProjectCrs(
                                            id = "wgs84_utm_${zone}${if (north) "N" else "S"}",
                                            label = "WGS84 / UTM zone $zone${if (north) "N" else "S"} (EPSG:$epsg)",
                                            epsg = epsg,
                                            unitToMeterScale = 1.0,
                                            supportedNow = true
                                        )
                                        val (lat, lon) = CoordinateTransformer.eastingNorthingMetersToWgs84(crs, x, y)
                                        val res = InctResult(id = id0, x = x, y = y, z = z, zone = zone)
                                        if (fileManager.write(res, lat, lon, z, "Import")) imported++ else rejected++
                                    }
                                    ImportDestCrs.WGS84_LLH -> {
                                        // XY -> Lat/Lon is not supported by design.
                                        rejected++
                                    }
                                }
                            } else {
                                if (parts.size < 3) { rejected++; continue }
                                val id0 = uniqueId(parts[0])
                                val latParsed = normalizeNumberToken(parts[1]).toDoubleOrNull()
                                if (latParsed == null) { rejected++; continue@lineLoop }
                                val lonParsed = normalizeNumberToken(parts[2]).toDoubleOrNull()
                                if (lonParsed == null) { rejected++; continue@lineLoop }
                                var lat = latParsed
                                var lon = lonParsed
                                val h = normalizeNumberToken(parts.getOrNull(3).orEmpty()).toDoubleOrNull() ?: 0.0
                                if (invertLatLon) {
                                    val tmp = lat
                                    lat = lon
                                    lon = tmp
                                }
                                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) { rejected++; continue }

                                when (cfg.destCrs) {
                                    ImportDestCrs.WGS84_LLH -> {
                                        val res = InctResult(id = id0, x = lon, y = lat, z = h, zone = 4326)
                                        if (fileManager.write(res, lat, lon, h, "Import")) imported++ else rejected++
                                    }
                                    ImportDestCrs.INCT_UTM -> {
                                        val tr = wgs84ToUtmInct(lat, lon, h)
                                        val zone = cfg.utmZone ?: tr.zone
                                        val res = InctResult(id = id0, x = tr.x, y = tr.y, z = tr.z, zone = zone)
                                        if (fileManager.write(res, lat, lon, h, "Import")) imported++ else rejected++
                                    }
                                    ImportDestCrs.WGS84_UTM -> {
                                        val zone = cfg.utmZone
                                        if (zone == null) { rejected++; continue@lineLoop }
                                        val north = cfg.utmHemisphereNorth ?: true
                                        val epsg = (if (north) 32600 else 32700) + zone
                                        val crs = ProjectCrs(
                                            id = "wgs84_utm_${zone}${if (north) "N" else "S"}",
                                            label = "WGS84 / UTM zone $zone${if (north) "N" else "S"} (EPSG:$epsg)",
                                            epsg = epsg,
                                            unitToMeterScale = 1.0,
                                            supportedNow = true
                                        )
                                        val (e, n) = CoordinateTransformer.wgs84ToEastingNorthingMeters(crs, lat, lon)
                                        val res = InctResult(id = id0, x = e, y = n, z = h, zone = zone)
                                        if (fileManager.write(res, lat, lon, h, "Import")) imported++ else rejected++
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            rejected++
                        }
                    }
                }
            }.onFailure {
                Log.e(TAG, "Import failed", it)
            }

            runOnUiThread {
                // Mark levé active and open it
                levesUri = file.uri
                recording = true
                updateSurveyUI()
                onLeveOpenedOrCreated(showStation = false)
                Toast.makeText(
                    this,
                    "✅ Import terminé: $imported pts | ❌ rejetés: $rejected | ✏️ renommés: ${renamed.get()}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showExistingLeves() {
        val levesDir = getLevesDir()
        if (levesDir == null) {
            Toast.makeText(this, "Aucun levé disponible", Toast.LENGTH_SHORT).show()
            return
        }

        val files = levesDir.listFiles()
            .filter { it.isFile && (it.name?.lowercase(Locale.getDefault())?.endsWith(".csv") == true) }
            .sortedBy { it.name?.lowercase(Locale.getDefault()) }

        if (files.isEmpty()) {
            Toast.makeText(this, "Aucun levé disponible", Toast.LENGTH_SHORT).show()
            return
        }

        val names = files.map { it.name ?: "leve.csv" }.toTypedArray()
        var selectedIndex = -1
        val dialog = AlertDialog.Builder(this)
            .setTitle("Choisir un levé")
            .setSingleChoiceItems(names, -1) { _, which ->
                selectedIndex = which
            }
            .setNegativeButton("ANNULER", null)
            .setPositiveButton("OK", null)
            .create()

        dialog.setOnShowListener {
            val okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            okButton.setOnClickListener {
                if (selectedIndex == -1) {
                    Toast.makeText(this, "Veuillez choisir un levé.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val file = files[selectedIndex]
                val name = file.name ?: "leve.csv"

                val sig = CsvCrsUtils.readSignature(contentResolver, file.uri)
                if (sig.isNullOrBlank()) {
                    // Legacy file: CRS is missing in CSV metadata.
                    // If user already chose a CRS earlier for this file, reuse it and DO NOT prompt again.
                    val stored = getStoredLeveCrsOverride(file.uri)
                    if (stored != null) {
                        val streamOk = fileManager.openExistingForce(file.uri, name)
                        if (!streamOk) {
                            Toast.makeText(this, "❌ Erreur ouverture levé", Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                        ensureLoadAndApplyLeveSettings(levesDir, file, stored)
                        levesUri = file.uri
                        recording = true
                        onLeveOpenedOrCreated(showStation = false)
                        Toast.makeText(this, "📂 Levé ouvert: $name", Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                    } else {
                        showLeveCrsChooser(
                            title = "CRS affiché (fichier ancien)",
                            onChosen = { chosen ->
                                val streamOk = fileManager.openExistingForce(file.uri, name)
                                if (!streamOk) {
                                    Toast.makeText(this, "❌ Erreur ouverture levé", Toast.LENGTH_LONG).show()
                                    return@showLeveCrsChooser
                                }
                                // Remember the CRS for this legacy file so the user cannot change it later.
                                storeLeveCrsOverride(file.uri, chosen)

                                ensureLoadAndApplyLeveSettings(levesDir, file, chosen)
                                levesUri = file.uri
                                recording = true
                                onLeveOpenedOrCreated(showStation = false)
                                Toast.makeText(this, "📂 Levé ouvert: $name", Toast.LENGTH_LONG).show()
                                dialog.dismiss()
                            }
                        )
                    }
                } else {
                    val chosen = ProjectCrs.fromSignature(sig)
                    val streamOk = fileManager.openExistingForce(file.uri, name)
                    if (!streamOk) {
                        Toast.makeText(this, "❌ Erreur ouverture levé", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    ensureLoadAndApplyLeveSettings(levesDir, file, chosen)
                    levesUri = file.uri
                    recording = true
                    onLeveOpenedOrCreated(showStation = false)
                    Toast.makeText(this, "📂 Levé ouvert: $name", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun updateLeveSelectionUi(hasSelection: Boolean) {
        if (hasSelection) {
            val green = ContextCompat.getColor(this, android.R.color.holo_green_dark)
            btnOpenLeve.setBackgroundColor(green)
            txtFileName.setTextColor(green)
        } else {
            val restored = defaultOpenLeveBackground?.constantState?.newDrawable()
            if (restored != null) {
                btnOpenLeve.background = restored
            }
            defaultFileNameColor?.let { txtFileName.setTextColor(it) }
        }
    }

    private fun updateSurveyContextInfo() {
        val leveLabel = if (recording && fileManager.isOpen()) fileManager.getFileName() else "---"
        txtSurveyLeveName.text = getString(R.string.survey_leve_format, leveLabel)

        val crsLabel = if (recording && fileManager.isOpen()) getDisplayCrsTag() else "---"
        txtSurveyCrsDisplay.text = "CRS affiché : $crsLabel"
    }

    private fun getDoublePref(prefs: SharedPreferences, key: String, defaultValue: Double): Double {
        val stored = prefs.getString(key, null)?.trim().orEmpty()
        return stored.toDoubleOrNull() ?: defaultValue
    }

    private fun formatMeters(value: Double): String = String.format(Locale.US, "%.3f", value)

    private fun currentPrecisionMeters(): Double? {
        return when (fixQuality) {
            4 -> 0.020
            5 -> 0.100
            1 -> 5.000
            else -> null
        }
    }

    private fun isPrecisionLockSatisfied(): Boolean {
        if (!precisionLockEnabled) return true
        val current = currentPrecisionMeters() ?: return false
        return current <= desiredPrecisionMeters
    }

    private fun updatePrecisionStatusUi() {
        val desiredLabel = formatMeters(desiredPrecisionMeters)
        val current = currentPrecisionMeters()
        val statusText = if (!precisionLockEnabled) {
            val currentLabel = current?.let { formatMeters(it) } ?: "inconnue"
            "Verrouillage OFF • actuelle: $currentLabel m • seuil: $desiredLabel m"
        } else if (current == null) {
            "❌ Précision inconnue (seuil: $desiredLabel m)"
        } else if (current <= desiredPrecisionMeters) {
            val currentLabel = formatMeters(current)
            "✅ Précision OK (actuelle: $currentLabel m ≤ $desiredLabel m)"
        } else {
            val currentLabel = formatMeters(current)
            "❌ Précision insuffisante (actuelle: $currentLabel m > $desiredLabel m)"
        }
        txtPrecisionStatusSurvey.text = statusText
    }

    private fun showAntennaHeightDialog() {
        val input = EditText(this).apply {
            hint = "x.xxx m"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(formatMeters(antennaHeightMeters))
            setSelection(text.length)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Hauteur antenne (m)")
            .setView(input)
            .setPositiveButton("Valider", null)
            .setNegativeButton("Annuler", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = input.text?.toString()?.trim().orEmpty().replace(',', '.')
                val regex = Regex("^\\d+\\.\\d{3}$")
                if (!regex.matches(raw)) {
                    input.error = "Format attendu : x.xxx"
                    return@setOnClickListener
                }
                val value = raw.toDoubleOrNull()
                if (value == null) {
                    input.error = "Valeur invalide"
                    return@setOnClickListener
                }
                antennaHeightMeters = value
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_ANTENNA_HEIGHT, formatMeters(value))
                    .apply()
                updateSurveyUI()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showPrecisionDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 0)
        }
        val input = EditText(this).apply {
            hint = "0.xxx m"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(formatMeters(desiredPrecisionMeters))
            setSelection(text.length)
        }
        val switchLock = SwitchMaterial(this).apply {
            text = "Verrouiller l'enregistrement"
            isChecked = precisionLockEnabled
        }
        container.addView(input)
        container.addView(switchLock)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Précision requise (m)")
            .setView(container)
            .setPositiveButton("Valider", null)
            .setNegativeButton("Annuler", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = input.text?.toString()?.trim().orEmpty().replace(',', '.')
                val regex = Regex("^0\\.\\d{3}$")
                if (!regex.matches(raw)) {
                    input.error = "Format attendu : 0.xxx"
                    return@setOnClickListener
                }
                val value = raw.toDoubleOrNull()
                if (value == null) {
                    input.error = "Valeur invalide"
                    return@setOnClickListener
                }
                desiredPrecisionMeters = value
                precisionLockEnabled = switchLock.isChecked
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_DESIRED_PRECISION, formatMeters(value))
                    .putBoolean(KEY_PRECISION_LOCK, precisionLockEnabled)
                    .apply()
                updateSurveyUI()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun adjustedInctForAntennaHeight(inct: InctResult): InctResult {
        return inct.copy(z = inct.z - antennaHeightMeters)
    }

    private fun showPrecisionLockToast() {
        val desiredLabel = formatMeters(desiredPrecisionMeters)
        val current = currentPrecisionMeters()
        val message = if (current == null) {
            "❌ Précision inconnue (seuil: $desiredLabel m)"
        } else {
            val currentLabel = formatMeters(current)
            "❌ Précision insuffisante (actuelle: $currentLabel m > $desiredLabel m)"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun getLevesDir(): DocumentFile? {
        val dir = resolveChantierDir(this)
        if (dir == null) {
            Toast.makeText(this, "Chantier non défini", Toast.LENGTH_SHORT).show()
            return null
        }
        return dir
    }

    private fun sanitizeSurveyName(raw: String): String {
        return raw.replace(Regex("[^a-zA-Z0-9_-]"), "")
            .trim()
            .replace(Regex("""\s+"""), "_")
    }

    private fun showLeveCrsChooser(title: String, onChosen: (ProjectCrs) -> Unit) {
        val options = ProjectCrs.allForUi()
        val labels = options.map { it.label }.toTypedArray()
        var selected = 0
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, selected) { _, which ->
                selected = which
            }
            .setNegativeButton("Annuler", null)
            .setPositiveButton("OK") { _, _ ->
                onChosen(options[selected])
            }
            .show()
    }

    private fun leveCrsOverrideKey(uri: Uri): String {
        // Keep it stable and filesystem-safe.
        return KEY_LEVE_CRS_OVERRIDE_PREFIX + uri.toString().hashCode().toString()
    }

    private fun getStoredLeveCrsOverride(uri: Uri): ProjectCrs? {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val sig = prefs.getString(leveCrsOverrideKey(uri), null)?.trim().orEmpty()
        if (sig.isBlank()) return null
        return ProjectCrs.fromSignature(sig)
    }

    private fun storeLeveCrsOverride(uri: Uri, crs: ProjectCrs) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(leveCrsOverrideKey(uri), CsvCrsUtils.expectedSignatureForProject(crs))
            .apply()
    }

    private fun showStationSetupDialog(force: Boolean) {
        if (!force && stationReady) return
        if (!recording || !fileManager.isOpen()) return

        // GNSS pas prêt : on autorise l'utilisateur à continuer (ou revenir) sans bloquer l'app.
        // La mesure restera bloquée tant que stationReady=false.
        if (lat == 0.0 || lon == 0.0) {
            AlertDialog.Builder(this)
                .setTitle("GNSS non prêt")
                .setMessage("Aucune position valide reçue (lat/lon). Connecte la base / attends le flux NMEA, puis relance la mise en station.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val baseMeasured = wgs84ToProjectCoords(lat, lon, alt, applyStationTransform = false)
        val crsLabel = getDisplayCrsTag()

        // After the FIRST station setup of the levé (any mode), we lock the "manual typing" and "auto" modes.
        // From then on, only "from an existing point" stays available.
        val manualLocked = (activeLeveSettings?.stationing?.createdAtEpochMs ?: 0L) > 0L

        // Business rule:
        // - Manual coordinate entry is allowed ONLY once for the very first station of the levé.
        // - After that, only "from an existing point" is allowed.
        val items = if (manualLocked) {
            arrayOf("Manuel : choisir un point station")
        } else {
            arrayOf(
                "Automatique (aucune transformation)",
                "Manuel : choisir un point station",
                "Manuel : saisir coordonnées station"
            )
        }

        // IMPORTANT: sur certains appareils, setMessage + setItems peut faire disparaître la liste.
        // On utilise un view custom pour afficher la base mesurée, puis une liste simple pour le mode.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(TextView(this@MainActivity).apply {
                text = String.format(
                    Locale.US,
                    "Base mesurée:\nX=%.3f  Y=%.3f\nZ=%.3f",
                    baseMeasured.x,
                    baseMeasured.y,
                    baseMeasured.z
                )
                textSize = 16f
            })
            addView(TextView(this@MainActivity).apply {
                text = "\nChoisir le mode :"
                textSize = 14f
            })
        }

        AlertDialog.Builder(this)
            .setTitle("Mise en station — $crsLabel")
            .setView(header)
            .setCancelable(true)
            .setItems(items) { _, which ->
                if (manualLocked) {
                    // Only one option in the list.
                    chooseStationFromLeves(baseMeasured)
                    return@setItems
                }

                when (which) {
                    0 -> {
                        stationDx = 0.0
                        stationDy = 0.0
                        stationDz = 0.0
                        stationReady = true
                        activeLeveSettings = (activeLeveSettings ?: LeveSettings()).copy(
                            stationing = (activeLeveSettings?.stationing ?: LeveSettings.StationingSettings()).copy(
                                enabled = false,
                                dx = 0.0,
                                dy = 0.0,
                                dz = 0.0,
                                createdAtEpochMs = System.currentTimeMillis(),
                                source = "AUTO"
                            )
                        )
                        persistCurrentLeveSettings()
                        updateSurveyUI()
                        Toast.makeText(this, "Mise en station : automatique", Toast.LENGTH_SHORT).show()
                        onStationSetupComplete?.invoke()
                    }
                    1 -> chooseStationFromLeves(baseMeasured)
                    2 -> manualStationEntry(baseMeasured)
                }
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun chooseStationFromLeves(baseMeasured: InctResult) {
        // Business request: choose the station point from the CURRENT levé points list,
        // regardless of the point name/prefix. (No more STA-only filtering.)
        val currentUri = levesUri
        if (currentUri == null || !fileManager.isOpen()) {
            Toast.makeText(this, "Aucun levé ouvert", Toast.LENGTH_SHORT).show()
            return
        }

        val currentName = fileManager.getFileName().ifBlank { "levé" }
        val candidates = levePointStore.getStationCandidates(currentUri, leveName = currentName, isCurrentLeve = true)
        if (candidates.isEmpty()) {
            Toast.makeText(this, "Aucun point trouvé dans le levé", Toast.LENGTH_SHORT).show()
            return
        }

        // Sort: alphanum (natural) ascending by id.
        val sorted = candidates.sortedWith { a, b -> naturalCompare(a.id, b.id) }

        val labels = sorted.map { s ->
            val ts = s.timestamp.ifBlank { "(sans date)" }
            "${s.id} — $ts"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Choisir point station")
            .setItems(labels) { _, which ->
                val st = sorted[which]
                applyStationTransformFromTrue(baseMeasured, st.x, st.y, st.z, source = "FROM_POINT")
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /**
     * Natural alphanumeric sorting key:
     * - "P2" < "P10"
     * - case-insensitive
     */
    private fun naturalSortKey(input: String): List<Comparable<*>> {
        val s = input.trim().lowercase(Locale.getDefault())
        if (s.isEmpty()) return listOf("")
        val out = mutableListOf<Comparable<*>>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isDigit()) {
                var j = i
                while (j < s.length && s[j].isDigit()) j++
                // Large numbers: compare by length then lexicographically to avoid Int overflow.
                val numStr = s.substring(i, j).trimStart('0')
                val normalized = if (numStr.isEmpty()) "0" else numStr
                out.add(normalized.length)
                out.add(normalized)
                i = j
            } else {
                var j = i
                while (j < s.length && !s[j].isDigit()) j++
                out.add(s.substring(i, j))
                i = j
            }
        }
        return out
    }

    private fun naturalCompare(a: String, b: String): Int {
        val ka = naturalSortKey(a)
        val kb = naturalSortKey(b)
        val n = minOf(ka.size, kb.size)
        for (i in 0 until n) {
            val va = ka[i]
            val vb = kb[i]
            @Suppress("UNCHECKED_CAST")
            val cmp = (va as Comparable<Any>).compareTo(vb as Any)
            if (cmp != 0) return cmp
        }
        return ka.size.compareTo(kb.size)
    }

    private fun manualStationEntry(baseMeasured: InctResult) {
        val crsOptions = ProjectCrs.allForUi()
        val labels = crsOptions.map { it.label }.toTypedArray()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        val sp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(crsOptions.indexOf(activeLeveCrs ?: ProjectCrs.INCT_UTM_ALGERIA).coerceAtLeast(0))
        }
        val etX = EditText(this).apply { hint = "X"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED }
        val etY = EditText(this).apply { hint = "Y"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED }
        val etZ = EditText(this).apply { hint = "Z"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED }

        layout.addView(TextView(this).apply { text = "CRS de saisie" })
        layout.addView(sp)
        layout.addView(etX)
        layout.addView(etY)
        layout.addView(etZ)

        AlertDialog.Builder(this)
            .setTitle("Saisir station")
            .setView(layout)
            .setPositiveButton("Valider") { _, _ ->
                val x = etX.text.toString().trim().toDoubleOrNull()
                val y = etY.text.toString().trim().toDoubleOrNull()
                val z = etZ.text.toString().trim().toDoubleOrNull()
                if (x == null || y == null || z == null) {
                    Toast.makeText(this, "Coordonnées invalides", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val inputCrs = crsOptions[sp.selectedItemPosition]
                val active = activeLeveCrs ?: ProjectCrs.INCT_UTM_ALGERIA

                // Minimal conversions supported (enough for current supported CRSs):
                // - same CRS -> direct
                // - WGS84 lat/lon (X=Lon,Y=Lat) -> any projected (INCT/UTM auto) via wgs84ToProjectCoords
                val (tx, ty, tz) = if (inputCrs.id == active.id) {
                    Triple(x, y, z)
                } else if (inputCrs.id == ProjectCrs.WGS84_LATLON.id) {
                    // input: X=Lon, Y=Lat
                    val latIn = y
                    val lonIn = x
                    val tmp = wgs84ToProjectCoords(latIn, lonIn, z, applyStationTransform = false)
                    Triple(tmp.x, tmp.y, tmp.z)
                } else {
                    Toast.makeText(this, "Conversion CRS non supportée : saisir dans le CRS du levé", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                // Manual coordinate entry is allowed only once (first station of the levé).
                applyStationTransformFromTrue(
                    baseMeasured,
                    tx,
                    ty,
                    tz,
                    markManualFirstDone = true,
                    source = "MANUAL_ENTRY"
                )
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun applyStationTransformFromTrue(
        baseMeasured: InctResult,
        trueX: Double,
        trueY: Double,
        trueZ: Double,
        markManualFirstDone: Boolean = false,
        source: String = "ONE_POINT"
    ) {
        val dx = trueX - baseMeasured.x
        val dy = trueY - baseMeasured.y
        val dz = trueZ - baseMeasured.z

        val maxShift = activeLeveSettings?.stationing?.maxShiftMeters ?: 30.0
        val dist3d = sqrt(dx * dx + dy * dy + dz * dz)

        if (dist3d > maxShift) {
            AlertDialog.Builder(this)
                .setTitle("Décalage trop grand")
                .setMessage(
                    String.format(
                        Locale.US,
                        "ΔX=%.3f  ΔY=%.3f  ΔZ=%.3f\n|Δ|=%.3f m\n\nLimite autorisée: %.1f m.\nVérifie CRS/zone/station.",
                        dx,
                        dy,
                        dz,
                        dist3d,
                        maxShift
                    )
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        stationDx = dx
        stationDy = dy
        stationDz = dz
        stationReady = true

        // Persist in leve_settings.json
        activeLeveSettings = (activeLeveSettings ?: LeveSettings()).copy(
            stationing = (activeLeveSettings?.stationing ?: LeveSettings.StationingSettings()).copy(
                enabled = true,
                manualFirstDone = (activeLeveSettings?.stationing?.manualFirstDone == true) || markManualFirstDone,
                dx = dx,
                dy = dy,
                dz = dz,
                createdAtEpochMs = System.currentTimeMillis(),
                source = source
            )
        )
        persistCurrentLeveSettings()

        updateSurveyUI()
        Toast.makeText(
            this,
            String.format(Locale.US, "Station OK: ΔX=%.3f  ΔY=%.3f  ΔZ=%.3f", dx, dy, dz),
            Toast.LENGTH_LONG
        ).show()
        onStationSetupComplete?.invoke()
    }

    private fun savePoint() {
        // Manual save uses current pointId and selected type.
        val typeLabel = if (selectedPointTypeLabel == "Aucun") "" else selectedPointTypeLabel
        val ok = savePointInternal(
            customId = pointId,
            typeLabel = typeLabel,
            advanceManualId = true,
            showToasts = true
        )
        if (ok) {
            nextPointId()
            refreshPointIdFieldIfNotEditing(force = true)
        }
    }

    /** Shared writer for manual + auto survey (same pipeline + stationing). */
    private fun savePointInternal(
        customId: String,
        typeLabel: String,
        advanceManualId: Boolean,
        showToasts: Boolean
    ): Boolean {
        if (!recording || !fileManager.isOpen()) {
            if (showToasts) Toast.makeText(this, "Démarre un levé d'abord", Toast.LENGTH_SHORT).show()
            return false
        }
        if (!stationReady) {
            if (showToasts) {
                Toast.makeText(this, "Mise en station obligatoire", Toast.LENGTH_SHORT).show()
                showStationSetupDialog(force = true)
            }
            return false
        }

        val inct = currentInct
        if (inct == null || fixQuality !in listOf(1, 4, 5)) {
            if (showToasts) Toast.makeText(this, "Position invalide (pas de Fix)", Toast.LENGTH_SHORT).show()
            return false
        }
        if (!isPrecisionLockSatisfied()) {
            if (showToasts) showPrecisionLockToast()
            return false
        }

        val toSave = adjustedInctForAntennaHeight(
            inct.copy(id = customId, timestamp = System.currentTimeMillis())
        )

        // For map & interoperability, keep the original WGS84 GNSS position in the CSV.
        // (Project/station corrections are already stored in x/y/z columns.)
        // This also ensures that immediately after saving, the saved-point marker matches
        // the live GNSS marker on map-centric screens (e.g. Superficie).
        val wgsLat = lat
        val wgsLon = lon
        val wgsAlt = alt

        val ok = fileManager.write(toSave, wgsLat, wgsLon, wgsAlt, typeLabel)
        if (ok) {
            playNotif(toSave.id)
            addMarkerForSavedPoint(toSave)
            usedPointIds.add(toSave.id)
            // Notify other screens (e.g., Superficie) that a point was saved.
            runCatching {
                val b = Intent(ACTION_POINT_SAVED).apply {
                    putExtra(EXTRA_POINT_SAVED_TYPE, typeLabel)
                    putExtra(EXTRA_POINT_SAVED_ID, toSave.id)
                    putExtra(EXTRA_POINT_SAVED_LAT, wgsLat)
                    putExtra(EXTRA_POINT_SAVED_LON, wgsLon)
                    putExtra(EXTRA_POINT_SAVED_ALT, wgsAlt)
                }
                LocalBroadcastManager.getInstance(this).sendBroadcast(b)
            }
            updateSurveyUI()
            if (showToasts) Toast.makeText(this, "✅ Point ${toSave.id} enregistré", Toast.LENGTH_SHORT).show()
        } else {
            if (showToasts) Toast.makeText(this, "❌ Erreur écriture levé", Toast.LENGTH_LONG).show()
        }
        return ok
    }

    /* =========================
       AUTO SURVEY implementation
       ========================= */

    private fun startAutoSurvey() {
        if (!recording || !fileManager.isOpen()) {
            Toast.makeText(this, "Ouvre un levé d'abord", Toast.LENGTH_SHORT).show()
            return
        }
        if (!stationReady) {
            Toast.makeText(this, "Mise en station obligatoire", Toast.LENGTH_SHORT).show()
            showStationSetupDialog(force = true)
            return
        }
        autoSurveyRunning = true
        autoSurveyPaused = false
        // Avoid ID duplicates across multiple auto-survey sessions in the same levé.
        // Example: if AUTO_001..AUTO_025 already exist, next should start at AUTO_026.
        initAutoSurveyCounter()
        // Reset triggers when starting
        lastAutoSavedInct = null
        lastAutoSavedAtMs = 0L
        Toast.makeText(this, "Levé auto démarré", Toast.LENGTH_SHORT).show()
    }

    private fun initAutoSurveyCounter() {
        // Ensure we have the latest IDs from the CSV.
        reloadUsedPointIdsFromCurrentLeve()
        val regex = Regex("^AUTO_(\\d+)$", RegexOption.IGNORE_CASE)
        var maxIdx = 0
        usedPointIds.forEach { id ->
            val m = regex.find(id.trim()) ?: return@forEach
            val n = m.groupValues.getOrNull(1)?.toIntOrNull() ?: return@forEach
            if (n > maxIdx) maxIdx = n
        }
        autoSurveyCounter = (maxIdx + 1).coerceAtLeast(1)
        // Safety: if for any reason it still exists, advance until free.
        while (usedPointIds.contains(String.format(Locale.US, "AUTO_%03d", autoSurveyCounter))) {
            autoSurveyCounter++
        }
    }

    private fun pauseAutoSurvey() {
        if (!autoSurveyRunning) return
        autoSurveyPaused = !autoSurveyPaused
        Toast.makeText(this, if (autoSurveyPaused) "Levé auto en pause" else "Levé auto repris", Toast.LENGTH_SHORT).show()
    }

    private fun stopAutoSurvey() {
        if (!autoSurveyRunning) return
        autoSurveyRunning = false
        autoSurveyPaused = false
        lastAutoSavedInct = null
        lastAutoSavedAtMs = 0L
        Toast.makeText(this, "Levé auto arrêté", Toast.LENGTH_SHORT).show()
    }

    private fun tickAutoSurvey() {
        if (!autoSurveyRunning || autoSurveyPaused) return

        // Update speed estimate
        val now = System.currentTimeMillis()
        val inct = currentInct
        if (inct != null) {
            val prev = lastFixInctForSpeed
            val prevT = lastFixAtMs
            if (prev != null && prevT > 0L && now > prevT) {
                val dt = (now - prevT) / 1000.0
                if (dt > 0.0) {
                    val d = hypot(inct.x - prev.x, inct.y - prev.y)
                    lastSpeedMps = d / dt
                }
            }
            lastFixInctForSpeed = inct
            lastFixAtMs = now
        }

        // Quality
        if (inct == null || fixQuality !in listOf(1, 4, 5)) return

        // Optional filters
        if (autoSurveyUseAcc) {
            val p = currentPrecisionMeters() ?: return
            if (p > autoSurveyAccMax) return
        }
        if (autoSurveyUseSpeed) {
            if (lastSpeedMps < autoSurveySpeedMin) return
        }

        val shouldSave = when (autoSurveyMode) {
            "TIME" -> (now - lastAutoSavedAtMs) >= autoSurveyTimeMs
            else -> {
                val last = lastAutoSavedInct
                if (last == null) true else hypot(inct.x - last.x, inct.y - last.y) >= autoSurveyDistMeters
            }
        }
        if (!shouldSave) return

        // Guarantee no duplicates even if file was edited externally.
        var id = String.format(Locale.US, "AUTO_%03d", autoSurveyCounter)
        while (usedPointIds.contains(id)) {
            autoSurveyCounter++
            id = String.format(Locale.US, "AUTO_%03d", autoSurveyCounter)
        }
        val ok = savePointInternal(customId = id, typeLabel = "AUTO", advanceManualId = false, showToasts = false)
        if (ok) {
            autoSurveyCounter++
            lastAutoSavedInct = inct
            lastAutoSavedAtMs = now
            Toast.makeText(this, "AUTO: $id", Toast.LENGTH_SHORT).show()
        }
    }

    /* =========================
       AREA / SUPERFICIE implementation
       ========================= */

    private fun formatAreaId(idx: Int): String {
        return if (idx in 0..999) String.format(Locale.US, "%03d_AREA", idx) else "${idx}_AREA"
    }

    private fun initAreaSurveyCounter(startIdx: Int) {
        reloadUsedPointIdsFromCurrentLeve()
        var idx = startIdx.coerceAtLeast(1)
        // If the user starts in the middle, ensure uniqueness.
        while (usedPointIds.contains(formatAreaId(idx))) idx++
        areaSurveyCounter = idx
    }

    private fun startAreaSurvey(startIdx: Int) {
        if (!recording || !fileManager.isOpen()) {
            Toast.makeText(this, "Ouvre un levé d'abord", Toast.LENGTH_SHORT).show()
            return
        }
        if (!stationReady) {
            Toast.makeText(this, "Mise en station obligatoire", Toast.LENGTH_SHORT).show()
            showStationSetupDialog(force = true)
            return
        }
        areaSurveyRunning = true
        // Start paused: the AreaSurveyActivity UI exposes an explicit AUTO ON/OFF.
        // If we start unpaused, points are saved immediately and the AUTO button feels inverted.
        areaSurveyPaused = true
        initAreaSurveyCounter(startIdx)
        lastAreaSavedInct = null
        lastAreaSavedAtMs = 0L
        Toast.makeText(this, "Superficie prête (AUTO OFF)", Toast.LENGTH_SHORT).show()
    }

    private fun pauseAreaSurvey() {
        if (!areaSurveyRunning) return
        areaSurveyPaused = !areaSurveyPaused
        Toast.makeText(this, if (areaSurveyPaused) "Superficie en pause" else "Superficie reprise", Toast.LENGTH_SHORT).show()
    }

    private fun stopAreaSurvey() {
        if (!areaSurveyRunning) return
        areaSurveyRunning = false
        areaSurveyPaused = false
        lastAreaSavedInct = null
        lastAreaSavedAtMs = 0L
        Toast.makeText(this, "Superficie arrêtée", Toast.LENGTH_SHORT).show()
    }

    private fun closeAreaSurvey() {
        // Just stop the auto triggers; the Activity will compute area with collected points.
        areaSurveyRunning = false
        areaSurveyPaused = false
        Toast.makeText(this, "Contour fermé", Toast.LENGTH_SHORT).show()
    }

    private fun addAreaPointOnce() {
        // Manual point must work even when AUTO is paused.
        if (!areaSurveyRunning) return
        val id = formatAreaId(areaSurveyCounter)
        val ok = savePointInternal(customId = id, typeLabel = "AREA", advanceManualId = false, showToasts = false)
        if (ok) {
            areaSurveyCounter++
            lastAreaSavedInct = currentInct
            lastAreaSavedAtMs = System.currentTimeMillis()
            Toast.makeText(this, "AREA: $id", Toast.LENGTH_SHORT).show()
        }
    }

    private fun tickAreaSurvey() {
        if (!areaSurveyRunning || areaSurveyPaused) return
        val now = System.currentTimeMillis()
        val inct = currentInct
        if (inct == null || fixQuality !in listOf(1, 4, 5)) return

        // Optional filters
        if (areaSurveyUseAcc) {
            val p = currentPrecisionMeters() ?: return
            if (p > areaSurveyAccMax) return
        }
        if (areaSurveyUseSpeed) {
            if (lastSpeedMps < areaSurveySpeedMin) return
        }

        val shouldSave = when (areaSurveyMode) {
            "TIME" -> (now - lastAreaSavedAtMs) >= areaSurveyTimeMs
            else -> {
                val last = lastAreaSavedInct
                if (last == null) true else hypot(inct.x - last.x, inct.y - last.y) >= areaSurveyDistMeters
            }
        }
        if (!shouldSave) return

        var id = formatAreaId(areaSurveyCounter)
        while (usedPointIds.contains(id)) {
            areaSurveyCounter++
            id = formatAreaId(areaSurveyCounter)
        }
        val ok = savePointInternal(customId = id, typeLabel = "AREA", advanceManualId = false, showToasts = false)
        if (ok) {
            areaSurveyCounter++
            lastAreaSavedInct = inct
            lastAreaSavedAtMs = now
        }
    }

    /* =========================
       COMMANDES VOCALES
       ========================= */

    private fun startVoiceCommandListening(autoStart: Boolean = false) {
        if (!hasAudioPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERM_REQ_AUDIO
            )
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Reconnaissance vocale indisponible", Toast.LENGTH_SHORT).show()
            disableVoiceMode("unavailable")
            return
        }

        if (isVoiceListening) {
            if (!autoStart) {
                Toast.makeText(this, "🎤 Déjà en écoute...", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val recognizer = ensureSpeechRecognizer() ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            resolveVoiceLanguageTag()?.let { languageTag ->
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            }
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        isVoiceListening = true
        Toast.makeText(this, "🎤 Écoute…", Toast.LENGTH_SHORT).show()
        recognizer.startListening(intent)
    }

    private fun stopVoiceListening() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.stopListening()
        } catch (_: Exception) {
        } finally {
            isVoiceListening = false
        }
    }

    private fun scheduleVoiceRestart(delayMs: Long) {
        if (!voiceModeEnabled) return
        val nowElapsed = SystemClock.elapsedRealtime()
        val sinceTts = nowElapsed - lastTtsAtElapsed
        val guardRemaining = (ttsGuardMs - sinceTts).coerceAtLeast(0L)
        val finalDelay = max(delayMs, guardRemaining)
        voiceRestartHandler.removeCallbacks(voiceRestartRunnable)
        voiceRestartHandler.postDelayed(voiceRestartRunnable, finalDelay)
    }

    private fun updateVoiceModeUi() {
        if (!::btnVoiceSurvey.isInitialized) return
        if (voiceModeEnabled) {
            btnVoiceSurvey.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2E7D32"))
            btnVoiceSurvey.setTextColor(Color.WHITE)
        } else {
            btnVoiceSurvey.backgroundTintList = defaultVoiceButtonTint
            defaultVoiceButtonTextColor?.let { btnVoiceSurvey.setTextColor(it) }
        }
    }

    private fun closeVoiceModeWithFeedback() {
        Toast.makeText(this, "Commandes vocales désactivées", Toast.LENGTH_SHORT).show()
        disableVoiceMode("close")
    }

    private fun disableVoiceMode(reason: String? = null) {
        voiceModeEnabled = false
        voiceState = VoiceState.IDLE
        isVoiceListening = false
        voiceRestartHandler.removeCallbacksAndMessages(null)
        voiceErrorSpeakStreak = 0
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.stopListening()
        } catch (_: Exception) {
        }
        try {
            if (::tts.isInitialized) {
                tts.stop()
            }
        } catch (_: Exception) {
        }
        updateVoiceModeUi()
    }

    private fun resolveVoiceLanguageTag(): String? {
        return when (voiceLangMode) {
            VoiceLangMode.FR -> "fr-FR"
            VoiceLangMode.AR -> "ar-DZ"
            VoiceLangMode.AUTO -> when (lastVoiceLanguage) {
                VoiceLanguage.FR -> "fr-FR"
                VoiceLanguage.AR -> "ar-DZ"
                null -> null
            }
        }
    }

    private fun ensureSpeechRecognizer(): SpeechRecognizer? {
        if (speechRecognizer != null) return speechRecognizer
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                isVoiceListening = false
            }

            override fun onError(error: Int) {
                isVoiceListening = false
                if (!voiceModeEnabled) return
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    Toast.makeText(
                        this@MainActivity,
                        "Permission micro refusée",
                        Toast.LENGTH_SHORT
                    ).show()
                    speak("Permission micro refusée")
                    disableVoiceMode("permission")
                } else {
                    showNotUnderstoodFeedback()
                    scheduleVoiceRestart(max(voiceRestartDelayFailMs, 3000L))
                }
            }

            override fun onResults(results: Bundle?) {
                isVoiceListening = false
                if (!voiceModeEnabled) return
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spoken = matches?.firstOrNull()
                if (spoken.isNullOrBlank()) {
                    showNotUnderstoodFeedback()
                    scheduleVoiceRestart(max(voiceRestartDelayFailMs, 3000L))
                    return
                }
                when (handleVoiceCommand(spoken)) {
                    VoiceCommandResult.UNKNOWN -> {
                        Toast.makeText(this@MainActivity, "Commande inconnue", Toast.LENGTH_SHORT).show()
                        maybeSpeakVoiceError("Commande inconnue")
                        scheduleVoiceRestart(max(voiceRestartDelayFailMs, 3000L))
                    }
                    VoiceCommandResult.FAIL -> {
                        scheduleVoiceRestart(max(voiceRestartDelayFailMs, 3000L))
                    }
                    VoiceCommandResult.SUCCESS -> {
                        voiceErrorSpeakStreak = 0
                        scheduleVoiceRestart(voiceRestartDelaySuccessMs)
                    }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        speechRecognizer = recognizer
        return recognizer
    }

    private fun handleVoiceCommand(spoken: String): VoiceCommandResult {
        val parsed = parseVoiceCommand(spoken)

        if (parsed?.command == VoiceCommand.CLOSE) {
            closeVoiceModeWithFeedback()
            return VoiceCommandResult.SUCCESS
        }

        if (voiceState == VoiceState.WAITING_POINT_TYPE) {
            if (parsed?.command == VoiceCommand.CANCEL) {
                voiceState = VoiceState.IDLE
                Toast.makeText(this, "Saisie annulée", Toast.LENGTH_SHORT).show()
                return VoiceCommandResult.SUCCESS
            }
            if (parsed?.command in setOf(VoiceCommand.ZOOM_RESET, VoiceCommand.ZOOM_PLUS, VoiceCommand.ZOOM_MINUS)) {
                return VoiceCommandResult.SUCCESS
            }
            val match = findSpinnerIndexByVoice(spoken)
            if (match != null) {
                spinnerPointTypeSurvey.setSelection(match.index)
                val typeLabel =
                    spinnerPointTypeSurvey.adapter?.getItem(match.index)?.toString()?.trim().orEmpty()
                savePoint()
                Toast.makeText(this, "Point enregistré : $typeLabel", Toast.LENGTH_SHORT).show()
                speak("Point enregistré : $typeLabel")
                if (voiceLangMode == VoiceLangMode.AUTO) {
                    lastVoiceLanguage = match.language
                }
                voiceState = VoiceState.IDLE
            } else {
                Toast.makeText(this, "Type invalide, répétez", Toast.LENGTH_SHORT).show()
                speak("Type invalide, répétez")
                return VoiceCommandResult.FAIL
            }
            return VoiceCommandResult.SUCCESS
        }

        if (parsed == null) return VoiceCommandResult.UNKNOWN
        if (voiceLangMode == VoiceLangMode.AUTO) {
            lastVoiceLanguage = parsed.language
        }

        when (parsed.command) {
            VoiceCommand.SAVE -> {
                val refusal = validateVoiceSavePoint()
                if (refusal != null) {
                    showVoiceRefusal(refusal)
                } else {
                    voiceState = VoiceState.WAITING_POINT_TYPE
                    Toast.makeText(this, "Dites le type de point", Toast.LENGTH_SHORT).show()
                    speak("Dites le type de point")
                }
            }

            VoiceCommand.PLAN_TOPO -> {
                if (!hasActiveLeve() || levesUri == null) {
                    showVoiceRefusal("Aucun levé ouvert")
                } else {
                    openTopoPlan()
                }
            }

            VoiceCommand.IMPLANTATION -> showImplantation()
            VoiceCommand.BACK -> navigatePrev()
            VoiceCommand.NEXT -> navigateNext()
            VoiceCommand.HELP -> showVoiceHelpDialog()
            VoiceCommand.ZOOM_RESET -> {
                Log.e("VOICE_TOPO", "VOICE COMMAND = ZOOM_RESET detected")
                return handleVoiceZoomReset()
            }
            VoiceCommand.ZOOM_PLUS -> {
                Log.e("VOICE_TOPO", "VOICE COMMAND = ZOOM_PLUS detected")
                return handleVoiceZoomDelta(VOICE_ZOOM_STEP_METERS)
            }
            VoiceCommand.ZOOM_MINUS -> {
                Log.e("VOICE_TOPO", "VOICE COMMAND = ZOOM_MINUS detected")
                return handleVoiceZoomDelta(-VOICE_ZOOM_STEP_METERS)
            }
            VoiceCommand.CLOSE, VoiceCommand.CANCEL -> Unit
        }
        return VoiceCommandResult.SUCCESS
    }

    private fun validateVoiceSavePoint(): String? {
        if (!recording || !fileManager.isOpen()) {
            return "Démarre un levé d'abord"
        }
        val inct = currentInct
        if (inct == null || fixQuality !in listOf(1, 4, 5)) {
            return "Position invalide (pas de Fix)"
        }
        if (!isPrecisionLockSatisfied()) {
            val desiredLabel = formatMeters(desiredPrecisionMeters)
            val current = currentPrecisionMeters()
            return if (current == null) {
                "Précision inconnue (seuil: $desiredLabel m)"
            } else {
                val currentLabel = formatMeters(current)
                "Précision insuffisante (actuelle: $currentLabel m > $desiredLabel m)"
            }
        }
        return null
    }

    private fun showVoiceRefusal(message: String) {
        Toast.makeText(this, "⛔ Refus : $message", Toast.LENGTH_SHORT).show()
    }

    private fun showVoiceHelpDialog() {
        val message = """
            Commandes disponibles :
            • "enregistrer", "enregistre"
            • "plan topo"
            • "retour"
            • "suivant"
            • "implantation"
            • "zoom", "plus", "moins"
            • "fermer"
            • "annuler" (quand le type est demandé)
            • "aide"
        """.trimIndent()
        AlertDialog.Builder(this)
            .setTitle("Aide commandes vocales")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun handleVoiceZoomReset(): VoiceCommandResult {
        voiceZoomFollowEnabled = true
        voiceZoomHeightMeters = VOICE_ZOOM_DEFAULT_METERS
        voiceZoomCenterTargetPending = mapMode == MapMode.IMPLANTATION && selectedTarget != null
        sendVoiceZoomBroadcast(reset = true)
        broadcastVoiceFollowLastPointIfAny()
        maybeSpeakVoiceZoomMeters(voiceZoomHeightMeters)
        maybeUpdateVoiceZoomFollow(force = true, reset = true)
        return VoiceCommandResult.SUCCESS
    }

    private fun handleVoiceZoomDelta(deltaMeters: Double): VoiceCommandResult {
        if (!voiceZoomFollowEnabled) {
            maybeSpeakVoiceZoomHint()
        }
        val next = (voiceZoomHeightMeters + deltaMeters).coerceIn(VOICE_ZOOM_MIN_METERS, VOICE_ZOOM_MAX_METERS)
        voiceZoomHeightMeters = next
        sendVoiceZoomBroadcast(reset = false)
        broadcastVoiceFollowLastPointIfAny()
        maybeSpeakVoiceZoomMeters(voiceZoomHeightMeters)
        maybeUpdateVoiceZoomFollow(force = true, reset = false)
        return VoiceCommandResult.SUCCESS
    }

    private fun maybeSpeakVoiceZoomMeters(meters: Double) {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - lastVoiceZoomSpeakElapsed < 1200L) return
        lastVoiceZoomSpeakElapsed = nowElapsed
        val label = meters.roundToInt().toString()
        speak("Zoom $label mètres")
    }

    private fun maybeSpeakVoiceZoomHint() {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - lastVoiceZoomHintElapsed < 4000L) return
        lastVoiceZoomHintElapsed = nowElapsed
        speak("Dites zoom")
    }

    private fun getLastLevePointInct(): PointXY? {
        val leveUri = levesUri ?: return null
        val points = runCatching { levePointStore.getLevePointsForCurrentChantier(leveUri) }.getOrNull()
            ?: return null
        return points.lastOrNull()
    }

    private fun broadcastVoiceFollowLastPointIfAny() {
        val lastPoint = getLastLevePointInct() ?: return
        sendBroadcast(
            Intent(ACTION_VOICE_FOLLOW_LAST_POINT).setPackage(packageName).apply {
                putExtra(EXTRA_VOICE_LAST_X, lastPoint.x)
                putExtra(EXTRA_VOICE_LAST_Y, lastPoint.y)
            }
        )
    }

    private data class ParsedCommand(val command: VoiceCommand, val language: VoiceLanguage)

    private data class VoiceTypeMatch(val index: Int, val language: VoiceLanguage)

    private fun parseVoiceCommand(text: String): ParsedCommand? {
        val normalizedFr = normalizeFrenchVoiceText(text)
        val normalizedAr = normalizeArabicVoiceText(text)

        fun matchFrench(): VoiceCommand? = matchCommand(normalizedFr, frenchCommandMap, ::normalizeFrenchVoiceText)

        fun matchArabic(): VoiceCommand? = matchCommand(normalizedAr, arabicCommandMap, ::normalizeArabicVoiceText)

        return when (voiceLangMode) {
            VoiceLangMode.FR -> matchFrench()?.let { ParsedCommand(it, VoiceLanguage.FR) }
                ?: matchArabic()?.let { ParsedCommand(it, VoiceLanguage.AR) }

            VoiceLangMode.AR -> matchArabic()?.let { ParsedCommand(it, VoiceLanguage.AR) }
                ?: matchFrench()?.let { ParsedCommand(it, VoiceLanguage.FR) }

            VoiceLangMode.AUTO -> matchFrench()?.let { ParsedCommand(it, VoiceLanguage.FR) }
                ?: matchArabic()?.let { ParsedCommand(it, VoiceLanguage.AR) }
        }
    }

    private val frenchCommandMap: Map<VoiceCommand, List<String>> = mapOf(
        VoiceCommand.CLOSE to listOf(
            "fermer",
            "ferme",
            "stop",
            "arreter",
            "arrêter",
            "coupe",
            "couper",
            "quitter",
            "quit",
            "close"
        ),
        VoiceCommand.SAVE to listOf(
            "enregistrer",
            "enregistre",
            "enregistr",
            "enreg",
            "enregistrement",
            "sauvegarder",
            "sauvegarde",
            "sauve",
            "sauver",
            "valider",
            "valide",
            "confirmer"
        ),
        VoiceCommand.NEXT to listOf(
            "suivant",
            "suivante",
            "apres",
            "après",
            "continue",
            "continuer",
            "avance",
            "avancer",
            "prochain",
            "prochaine"
        ),
        VoiceCommand.BACK to listOf(
            "retour",
            "revenir",
            "reviens",
            "arriere",
            "arrière",
            "recule",
            "reculer",
            "precedent",
            "précédent",
            "precedente",
            "précédente",
            "avant"
        ),
        VoiceCommand.PLAN_TOPO to listOf("plan topo"),
        VoiceCommand.IMPLANTATION to listOf("implantation"),
        VoiceCommand.ZOOM_PLUS to listOf(
            "plus",
            "rapproche",
            "descend",
            "descends",
            "encore",
            "augmente",
            "zoom plus"
        ),
        VoiceCommand.ZOOM_MINUS to listOf(
            "moins",
            "eloigne",
            "éloigne",
            "monte",
            "montes",
            "diminue",
            "reduis",
            "réduis",
            "reduit",
            "réduit",
            "zoom moins"
        ),
        VoiceCommand.ZOOM_RESET to listOf(
            "zoom",
            "zoome",
            "zoomer",
            "zoom sur",
            "agrandir",
            "rapproche",
            "rapprocher",
            "focus",
            "centrer",
            "centre",
            "reset zoom",
            "reinitialise zoom",
            "réinitialise zoom",
            "zoom normal",
            "zoom par defaut",
            "zoom par défaut",
            "vingt",
            "vingt metres",
            "vingt mètres",
            "20",
            "20 metres",
            "20 mètres"
        ),
        VoiceCommand.CANCEL to listOf("annuler", "annule", "cancel", "abandon", "abandonner", "laisse tomber"),
        VoiceCommand.HELP to listOf("aide", "help", "commandes", "commande", "quoi dire")
    )

    private val arabicCommandMap: Map<VoiceCommand, List<String>> = mapOf(
        VoiceCommand.SAVE to listOf("سجل", "تسجيل", "سجّل"),
        VoiceCommand.NEXT to listOf("التالي", "التاليه", "بعد"),
        VoiceCommand.BACK to listOf("رجوع", "عودة", "عوده", "العودة", "ارجع"),
        VoiceCommand.PLAN_TOPO to listOf("مخطط", "بلان طوبو"),
        VoiceCommand.IMPLANTATION to listOf("غرس", "ارساء", "إرساء"),
        VoiceCommand.ZOOM_RESET to listOf("زووم", "تكبير", "قرب"),
        VoiceCommand.ZOOM_PLUS to listOf("زيد", "طلع", "اطلع"),
        VoiceCommand.ZOOM_MINUS to listOf("نقص", "انزل", "هبط"),
        VoiceCommand.CLOSE to listOf("اغلق", "إغلاق", "اغلاق", "قفل", "سكر"),
        VoiceCommand.CANCEL to listOf("إلغاء"),
        VoiceCommand.HELP to listOf("مساعدة", "ساعدني")
    )

    private fun matchCommand(
        normalizedText: String,
        commandMap: Map<VoiceCommand, List<String>>,
        normalizer: (String) -> String
    ): VoiceCommand? {
        return commandMap.entries.firstOrNull { (_, phrases) ->
            phrases.any { phrase -> normalizedText.contains(normalizer(phrase)) }
        }?.key
    }

    private val frenchPointTypeMap: Map<String, List<String>> = mapOf(
        "Aucun" to listOf("aucun", "rien", "vide", "sans type", "pas de type"),
        "Arbre" to listOf("arbre", "arbres", "arb"),
        "Poteau" to listOf("poteau", "poteaux", "pot", "poto", "pilier", "pylone", "pylône"),
        "Regard" to listOf("regard", "regar", "bouche", "bouche egout", "bouche d egout"),
        "Borne" to listOf("borne", "bornes", "repere", "repère", "point fixe", "limite"),
        "Avaloir" to listOf("avaloir", "avaloirs", "ava", "egout", "égout", "drain", "drainage")
    )

    private val arabicPointTypeMap: Map<String, String> = listOf(
        "لا شيء" to "Aucun",
        "لاشيء" to "Aucun",
        "شجرة" to "Arbre",
        "شجره" to "Arbre",
        "الشجرة" to "Arbre",
        "شجر" to "Arbre",
        "عمود" to "Poteau",
        "منهل" to "Regard",
        "رجارد" to "Regard",
        "معلم" to "Borne",
        "حد" to "Borne",
        "حدّ" to "Borne",
        "بورنة" to "Borne",
        "بالوعة" to "Avaloir",
        "بالوعه" to "Avaloir",
        "بالوعات" to "Avaloir"
    ).associate { (ar, fr) -> normalizeArabicVoiceText(ar) to fr }

    private fun normalizeFrenchVoiceText(input: String): String {
        val normalized = Normalizer.normalize(input.lowercase(Locale.FRANCE), Normalizer.Form.NFD)
        return normalized
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun normalizeArabicVoiceText(input: String): String {
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFKC)
        return normalized
            .replace(Regex("[\\u0640\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]"), "")
            .replace(Regex("[أإآ]"), "ا")
            .replace("ة", "ه")
            .replace("ى", "ي")
            .replace("ؤ", "و")
            .replace("ئ", "ي")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun findSpinnerIndexByVoice(text: String): VoiceTypeMatch? {
        if (!::spinnerPointTypeSurvey.isInitialized) return null
        val adapter = spinnerPointTypeSurvey.adapter ?: return null
        val normalizedFr = normalizeFrenchVoiceText(text)
        val mappedLabelFr = matchFrenchPointType(normalizedFr)
        if (mappedLabelFr != null) {
            for (index in 0 until adapter.count) {
                val label = adapter.getItem(index)?.toString()?.trim().orEmpty()
                if (normalizeFrenchVoiceText(label) == normalizeFrenchVoiceText(mappedLabelFr)) {
                    return VoiceTypeMatch(index, VoiceLanguage.FR)
                }
            }
        }
        for (index in 0 until adapter.count) {
            val label = adapter.getItem(index)?.toString()?.trim().orEmpty()
            if (normalizeFrenchVoiceText(label) == normalizedFr) {
                return VoiceTypeMatch(index, VoiceLanguage.FR)
            }
        }
        val normalizedAr = normalizeArabicVoiceText(text)
        val mappedLabelAr = arabicPointTypeMap[normalizedAr] ?: matchArabicPointTypePrefix(normalizedAr) ?: return null
        for (index in 0 until adapter.count) {
            val label = adapter.getItem(index)?.toString()?.trim().orEmpty()
            if (normalizeFrenchVoiceText(label) == normalizeFrenchVoiceText(mappedLabelAr)) {
                return VoiceTypeMatch(index, VoiceLanguage.AR)
            }
        }
        return null
    }

    private fun matchFrenchPointType(normalizedFr: String): String? {
        val match = frenchPointTypeMap.entries.firstOrNull { (_, phrases) ->
            phrases.any { phrase -> normalizedFr.contains(normalizeFrenchVoiceText(phrase)) }
        }?.key
        return match ?: matchFrenchPointTypePrefix(normalizedFr)
    }

    private fun matchFrenchPointTypePrefix(normalizedFr: String): String? {
        val prefixMatches = listOf(
            "arb" to "Arbre",
            "pot" to "Poteau",
            "ava" to "Avaloir"
        ).filter { (prefix, _) -> normalizedFr.startsWith(prefix) }

        if (prefixMatches.isEmpty()) return null
        val labels = prefixMatches.map { it.second }.distinct()
        return if (labels.size == 1) labels.first() else null
    }

    private fun matchArabicPointTypePrefix(normalizedAr: String): String? {
        val prefixMatches = listOf(
            "شج" to "Arbre",
            "بالو" to "Avaloir",
            "بال" to "Avaloir",
            "عم" to "Poteau"
        ).filter { (prefix, _) -> normalizedAr.startsWith(prefix) }

        if (prefixMatches.isEmpty()) return null
        val labels = prefixMatches.map { it.second }.distinct()
        return if (labels.size == 1) labels.first() else null
    }

    private fun showNotUnderstoodFeedback() {
        if (!voiceModeEnabled) return
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - lastNotUnderstoodAtElapsed < 3000L) return
        lastNotUnderstoodAtElapsed = nowElapsed
        Toast.makeText(this@MainActivity, "Je n'ai pas compris", Toast.LENGTH_SHORT).show()
        maybeSpeakVoiceError("Je n'ai pas compris")
    }

    private fun maybeSpeakVoiceError(message: String) {
        if (!voiceModeEnabled) return
        if (voiceErrorSpeakStreak >= 2) return
        voiceErrorSpeakStreak += 1
        speak(message)
    }

    /* =========================
       QUITTER
       ========================= */

    private fun backToChantierHome() {
        val intent = Intent(this, ChantierHomeActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun confirmQuit() {
        AlertDialog.Builder(this)
            .setTitle("Quitter")
            .setMessage("Voulez-vous fermer l'application ?")
            .setPositiveButton("Oui") { _, _ ->
                try {
                    if (recording) fileManager.close()
                } catch (_: Exception) {
                }
                try {
                    disconnectBt()
                } catch (_: Exception) {
                }
                finishAffinity()
            }
            .setNegativeButton("Non", null)
            .show()
    }

    /* =========================
       TTS INIT
       ========================= */

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return

        val ar = Locale("ar")
        val arOk = tts.isLanguageAvailable(ar) >= TextToSpeech.LANG_AVAILABLE

        guidanceLocale = if (arOk) ar else Locale.FRENCH
        guidanceStrings = if (arOk) GuidanceStrings.arabic() else GuidanceStrings.french()

        // Langue par défaut de l'app (hors guidage)
        tts.language = Locale.FRENCH
    }


    /* =========================
       NAVIGATION
       ========================= */

    // ---------------------------------------------------------------------
    // NavigationFragment handlers
    // ---------------------------------------------------------------------
    fun onNavScanGnssClicked() { startScan() }

    fun onNavConnectGnssClicked() { connectSelected() }

    fun onNavDisconnectGnssClicked() { disconnectBt() }

    fun onNavToggleSimulationClicked() { toggleSimulation() }

    fun onNavGnssDeviceSelected(position: Int) {
        val device = allDevices.getOrNull(position) ?: return
        selectedDevice = device
        selectedDeviceAddress = device.address
        if (::gnssAdapter.isInitialized) {
            runOnUiThread { gnssAdapter.notifyDataSetChanged() }
        }
    }

    fun onNavNextClicked() { navigateNext() }

    fun onNavQuitClicked() { quitApp() }

    /* =========================
       MAP
       ========================= */

    // ---------------------------------------------------------------------
    // MapFragment handlers
    // ---------------------------------------------------------------------
    /** Called by MapFragment (toolbar: add point). */
    fun onMapAddPointClicked() {
        promptMapPointId(isLeve = true)
    }

    /** Called by MapFragment (toolbar: center rover). */
    fun onMapCenterRoverClicked(isImplantation: Boolean) {
        mapController.setFollowEnabled(true)
        centerOnMe(true)
        val btn = runCatching { if (isImplantation) btnMapImplantFollow else btnMapFollow }.getOrNull()
        if (btn != null) runCatching { setToolbarToggleState(btn, mapController.isFollowEnabled) }
    }

    /** Called by MapFragment (toolbar: follow toggle). */
    fun onMapFollowClicked(isImplantation: Boolean) {
        val enabled = mapController.toggleFollowEnabled()
        if (enabled) centerOnMe(false)
        val btn = runCatching { if (isImplantation) btnMapImplantFollow else btnMapFollow }.getOrNull()
        if (btn != null) runCatching { setToolbarToggleState(btn, enabled) }
    }

    /** Called by MapFragment (toolbar: trace toggle). */
    fun onMapToggleTrackClicked() {
        toggleTraceVisibility()
        Toast.makeText(this, "Trace", Toast.LENGTH_SHORT).show()
    }

    /** Called by MapFragment (toolbar: more menu). */
    fun onMapMoreClicked(isImplantation: Boolean, anchor: View) {
        showMapMoreMenu(isImplantation = isImplantation, anchor = anchor)
    }

    /** Called by MapFragment (implant toolbar: center target). */
    fun onMapCenterTargetClicked() {
        centerOnTarget()
    }

    /** Called by MapFragment (implant toolbar: tolerance circle). */
    fun onMapToggleCircleClicked() {
        toggleTargetCircle()
        Toast.makeText(this, "Tolérance", Toast.LENGTH_SHORT).show()
    }

    /** Called by MapFragment (bottom navigation). */
    fun onMapPrevClicked() {
        navigatePrev()
    }

    /** Called by MapFragment (bottom navigation). */
    fun onMapNextClicked() {
        navigateNext()
    }

    /** Called by MapFragment (quit button). */
    fun onMapQuitClicked() {
        quitApp()
    }


    // ----------------------------------------
    // GestionFragment handlers
    // ----------------------------------------
    fun onGestionCreateLeveClicked() { createLeveFile() }

    fun onGestionOpenLeveClicked() { showExistingLeves() }

    fun onGestionImportLeveClicked() { startImportLeveWizard() }

    fun onGestionExportDxfClicked() { exportDxf() }

    fun onGestionPrevClicked() { navigatePrev() }

    fun onGestionNextClicked() { navigateNext() }

    fun onGestionQuitClicked() { quitApp() }

    // ----------------------------------------
    // SurveyFragment handlers
    // ----------------------------------------
    fun onSurveyStationSetupClicked() { startStationWizard() }

    fun onSurveySavePointClicked() { savePoint() }

    fun onSurveyTopoPlanClicked() { openTopoPlan() }

    fun onSurveyApplyIdClicked() { applyPointIdFromUI() }

    fun onSurveyVoiceClicked() {
        if (voiceModeEnabled) {
            disableVoiceMode("button")
        } else {
            voiceModeEnabled = true
            voiceState = VoiceState.IDLE
            voiceErrorSpeakStreak = 0
            updateVoiceModeUi()
            Toast.makeText(this, "Commandes vocales activées", Toast.LENGTH_SHORT).show()
            speak("Commandes vocales activées")
            startVoiceCommandListening()
        }
    }

    fun onSurveyAntennaHeightClicked() { showAntennaHeightDialog() }

    fun onSurveyPrecisionClicked() { showPrecisionDialog() }

    fun onSurveyPrevClicked() {
        Toast.makeText(this, "Écran précédent", Toast.LENGTH_SHORT).show()
        navigatePrev()
    }

    fun onSurveyNextClicked() {
        Toast.makeText(this, "Écran suivant", Toast.LENGTH_SHORT).show()
        navigateNext()
    }

    fun onSurveyQuitClicked() { confirmQuit() }


private fun initMap() {
    mapController.init()
}


private fun centerOnMe(force: Boolean) {
    val ok = mapController.centerOnMe(lat = lat, lon = lon, forceZoom = force)
    if (!ok && force) {
        Toast.makeText(this, "Pas de position", Toast.LENGTH_SHORT).show()
    }
}


private fun updateMapPosition() {
    if (!::mapView.isInitialized) return
    mapController.updatePosition(
        lat = lat,
        lon = lon,
        fixQuality = fixQuality,
        isMapVisible = (screenMap.visibility == View.VISIBLE)
    )
    maybeUpdateVoiceZoomFollow()
}


    private fun maybeUpdateVoiceZoomFollow(force: Boolean = false, reset: Boolean = false) {
        if (!voiceZoomFollowEnabled && !force) return
        val nowElapsed = SystemClock.elapsedRealtime()
        if (!force && nowElapsed - lastVoiceZoomUpdateElapsed < 900L) return
        lastVoiceZoomUpdateElapsed = nowElapsed

        voiceZoomHeightMeters = voiceZoomHeightMeters.coerceIn(VOICE_ZOOM_MIN_METERS, VOICE_ZOOM_MAX_METERS)
        val zoomLevel = voiceZoomMetersToOsmdroidZoom(voiceZoomHeightMeters)

        val hasPosition = lat != 0.0 || lon != 0.0
        val isMapScreen = currentScreen == Screen.MAP_LEVE || currentScreen == Screen.MAP_IMPLANTATION

        if (isMapScreen) {
            if (voiceZoomFollowEnabled) {
                if (mapMode == MapMode.IMPLANTATION && voiceZoomCenterTargetPending && selectedTarget != null) {
                    centerOnTarget()
                    voiceZoomCenterTargetPending = false
                    mapView.controller.setZoom(zoomLevel)
                    mapView.invalidate()
                } else if (hasPosition) {
                    centerOnMe(force = false)
                    mapView.controller.setZoom(zoomLevel)
                    mapView.invalidate()
                }
            } else {
                mapView.controller.setZoom(zoomLevel)
                mapView.invalidate()
            }
            if (force) {
                val tag = if (mapMode == MapMode.IMPLANTATION) "IMPL RECV ZOOM" else "MAP RECV ZOOM"
                Log.d("VOICE_TOPO", "$tag meters=$voiceZoomHeightMeters reset=$reset")
            }
        }

        if (voiceZoomFollowEnabled && hasPosition) {
            sendVoiceFollowBroadcast()
        }
    }

    private fun sendVoiceZoomBroadcast(reset: Boolean) {
        VoiceZoomBus.post(
            meters = voiceZoomHeightMeters,
            reset = reset
        )
        Log.d("VOICE_TOPO", "zoom meters=$voiceZoomHeightMeters reset=$reset")
        sendBroadcast(
            Intent(ACTION_VOICE_ZOOM).setPackage(packageName).apply {
                putExtra(EXTRA_VOICE_ZOOM_ENABLED, voiceZoomFollowEnabled)
                putExtra(EXTRA_VOICE_ZOOM_RESET, reset)
                putExtra(EXTRA_VOICE_ZOOM_METERS, voiceZoomHeightMeters)
            }
        )
    }

    private fun sendVoiceFollowBroadcast() {
        if (!voiceZoomFollowEnabled) return
        sendBroadcast(
            Intent(ACTION_VOICE_FOLLOW_POSITION).apply {
                putExtra(EXTRA_VOICE_FOLLOW_ENABLED, voiceZoomFollowEnabled)
                putExtra(EXTRA_VOICE_FOLLOW_METERS, voiceZoomHeightMeters)
                putExtra(EXTRA_VOICE_FOLLOW_LAT, lat)
                putExtra(EXTRA_VOICE_FOLLOW_LON, lon)
                putExtra(EXTRA_VOICE_FOLLOW_ALT, alt)
            }
        )
    }

    
    private fun setToolbarToggleState(view: View, enabled: Boolean) {
        val bg = if (enabled) {
            com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, 0)
        } else {
            ContextCompat.getColor(this, android.R.color.darker_gray)
        }
        val fg = if (enabled) {
            ContextCompat.getColor(this, android.R.color.white)
        } else {
            ContextCompat.getColor(this, android.R.color.black)
        }

        // MaterialButton supports backgroundTint and iconTint; but to keep this safe with View, use background and alpha.
        view.backgroundTintList = android.content.res.ColorStateList.valueOf(bg)
        view.alpha = if (enabled) 1.0f else 0.85f
        if (view is com.google.android.material.button.MaterialButton) {
            view.setTextColor(fg)
            view.iconTint = android.content.res.ColorStateList.valueOf(fg)
        }
    }


    private fun showMapMoreMenu(isImplantation: Boolean, anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        if (isImplantation) {
            popup.menu.add(0, 1, 0, "Choisir cible")
            popup.menu.add(0, 2, 1, "Choisir sur carte")
            popup.menu.add(0, 3, 2, "Boussole ON/OFF")
            popup.menu.add(0, 4, 3, "Trace ON/OFF")
        } else {
            popup.menu.add(0, 10, 0, "Liste points")
            popup.menu.add(0, 11, 1, "Boussole ON/OFF")
            popup.menu.add(0, 12, 2, "Marqueurs ON/OFF")
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { promptImplantationTarget(); true }
                2 -> { enterMapSelectionMode(); true }
                3, 11 -> { toggleCompassOverlay(); true }
                4 -> { toggleTraceVisibility(); true }
                10 -> { showMapPointsList(); true }
                12 -> { toggleMarkersVisibility(); true }
                else -> false
            }
        }
        popup.show()
    }

private fun toggleMarkersVisibility() {
    val visible = mapController.toggleMarkersVisibility()
    Toast.makeText(
        this,
        if (visible) "Marqueurs affichés" else "Marqueurs masqués",
        Toast.LENGTH_SHORT
    ).show()
}


private fun toggleTraceVisibility() {
    val visible = mapController.toggleTraceVisibility()
    try {
        setToolbarToggleState(btnMapToggleTrack, visible)
    } catch (_: Exception) {}
}


    /* =========================
       IMPLANTATION : cible
       ========================= */

    private fun promptImplantationTarget() {
        val targets = targetRepository.loadTargets(levesUri, reperesUri)

        if (targets.isEmpty()) {
            Toast.makeText(this, getString(R.string.implantation_no_target), Toast.LENGTH_SHORT).show()
            return
        }

        val labels = targets.map { buildTargetLabel(it) }.toTypedArray()
        var selectedIndex = -1

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.implantation_select_title))
            .setSingleChoiceItems(labels, -1) { _, which -> selectedIndex = which }
            .setPositiveButton("OK") { _, _ ->
                if (selectedIndex !in targets.indices) return@setPositiveButton
                selectedTarget = targets[selectedIndex]
                updateImplantationUI()
                updateTargetMarker(selectedTarget)
                resetGuidanceSchedule()
                speakTargetSelected()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun buildTargetLabel(target: TargetPoint): String {
        val zText = target.zInct?.let { String.format(Locale.US, "%.2f", it) } ?: "NI"
        return String.format(
            Locale.US,
            "%s | %s | lat=%.8f | lon=%.8f | Z=%s",
            target.sourceLabel,
            target.id,
            target.lat,
            target.lon,
            zText
        )
    }

    // Target parsing moved to TargetRepository (TargetRepository.kt)
private fun updateTargetMarker(target: TargetPoint?) {
    mapController.updateTargetMarker(
        id = target?.id,
        lat = target?.lat,
        lon = target?.lon,
        isMapVisible = (screenMap.visibility == View.VISIBLE)
    )
}


    private fun selectTargetFromMapPress(pressPoint: GeoPoint) {
        val targets = targetRepository.loadTargets(levesUri, reperesUri)
        if (targets.isEmpty()) {
            Toast.makeText(this, getString(R.string.implantation_no_target), Toast.LENGTH_SHORT).show()
            return
        }

        val chosen = mapController.pickNearestTargetFromPress(pressPoint, targets) ?: return

        selectedTarget = chosen
        updateTargetMarker(chosen)
        updateImplantationUI()
        showImplantation()
        resetGuidanceSchedule()
        speakTargetSelected()
    }

    // loadImplantationTargets() removed (use targetRepository.loadTargets(levesUri, reperesUri))

    /* =========================
       AJOUT POINTS CARTE (levé / repère)
       ========================= */

    private fun promptMapPointId(isLeve: Boolean, touchPoint: GeoPoint? = null) {
        val inct = currentInct
        if (isLeve) {
            if (!stationReady) {
                Toast.makeText(this, "Mise en station obligatoire", Toast.LENGTH_SHORT).show()
                showStationSetupDialog(force = true)
                return
            }
            if (inct == null || fixQuality !in listOf(1, 4, 5)) {
                Toast.makeText(this, "Position invalide (pas de Fix)", Toast.LENGTH_SHORT).show()
                return
            }
            if (!isPrecisionLockSatisfied()) {
                showPrecisionLockToast()
                return
            }
            if (lat == 0.0 || lon == 0.0) {
                Toast.makeText(this, "Pas de point valide", Toast.LENGTH_SHORT).show()
                return
            }
            if (!fileManager.isOpen()) {
                Toast.makeText(this, "Aucun levé ouvert", Toast.LENGTH_SHORT).show()
                return
            }
        } else {
            if (touchPoint == null) {
                Toast.makeText(this, "Position invalide", Toast.LENGTH_SHORT).show()
                return
            }
            if (reperesUri == null) {
                Toast.makeText(this, "Fichier repères indisponible", Toast.LENGTH_SHORT).show()
                return
            }
        }

        val et = EditText(this).apply {
            hint = "ID"
            inputType = InputType.TYPE_CLASS_TEXT
        }

        AlertDialog.Builder(this)
            .setTitle(if (isLeve) "LEVES PTS" else "REPERE PERS")
            .setView(et)
            .setPositiveButton("Ajouter") { _, _ ->
                val id = et.text?.toString()?.trim().orEmpty()
                if (id.isBlank()) {
                    Toast.makeText(this, "ID invalide", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (isLeve) {
                    if (inct != null) appendLevePoint(id, inct)
                } else {
                    val point = touchPoint ?: return@setPositiveButton
                    appendReperePoint(id, point)
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun appendLevePoint(id: String, inct: InctResult) {
        if (!isPrecisionLockSatisfied()) {
            showPrecisionLockToast()
            return
        }
        val toSave = adjustedInctForAntennaHeight(
            inct.copy(id = id, timestamp = System.currentTimeMillis())
        )
        val typeLabel = if (selectedPointTypeLabel == "Aucun") "" else selectedPointTypeLabel
        val w = projectCoordsToWgs84(toSave)
        val ok = fileManager.write(toSave, w.lat, w.lon, w.alt, typeLabel)
        if (ok) {
            addMarkerForSavedPoint(toSave)
            refreshMarkers()
            updateSurveyUI()
            Toast.makeText(this, "✅ Point $id enregistré", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "❌ Erreur écriture levé", Toast.LENGTH_LONG).show()
        }
    }

    private fun appendReperePoint(id: String, point: GeoPoint) {
        val uri = reperesUri
        if (uri == null) {
            Toast.makeText(this, "Fichier repères indisponible", Toast.LENGTH_SHORT).show()
            return
        }

        val line = String.format(
            Locale.US,
            "%s,%.2f,%.2f,%d,%.8f,%.8f\n",
            id, 0.0, 0.0, 0, point.latitude, point.longitude
        )
        try {
            val stream = contentResolver.openOutputStream(uri, "wa")
            if (stream == null) {
                Toast.makeText(this, "❌ Erreur écriture repère", Toast.LENGTH_LONG).show()
                Log.e(TAG, "CSV write failed: output stream null")
                return
            }
            OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                writer.write(line)
                writer.flush()
            }
            Log.d(TAG, "CSV appended: reperes.csv")
            refreshMarkers()
            Toast.makeText(this, "✅ Repère $id enregistré", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "CSV write failed", e)
            Toast.makeText(this, "❌ Erreur écriture repère", Toast.LENGTH_LONG).show()
        }
    }

private fun addMarkerForSavedPoint(inct: InctResult) {
    mapController.addMarkerForSavedPoint(
        inct = inct,
        lat = lat,
        lon = lon,
        fixQuality = fixQuality,
        isMapVisible = (screenMap.visibility == View.VISIBLE)
    )
}


private fun refreshMarkers() {
    mapController.refreshMarkers(
        levesUri = levesUri,
        reperesUri = reperesUri,
        isMapVisible = (screenMap.visibility == View.VISIBLE)
    )
}

    /* =========================
       LIGNE + CERCLE CIBLE
       ========================= */




private fun updateImplantationRefLine(crsLabel: String) {
    // Locked state already handled by caller.
    val uri = levesUri
    if (uri == null) {
        txtImplantationTargetSummary.text = "Aucun levé ouvert"
        txtImplantationTargetInct.text = ""
        txtImplantationDeltas.text = "NI"
        txtImplantationDistances.text = "NI"
        txtCompassDistance.text = "NI"
        stopProximityBeep()
        resetGuidanceSchedule()
        return
    }

    val pts = levePointStore.getLevePointsForCurrentChantier(uri)
    fun find(id: String?): PointXY? = id?.let { key -> pts.firstOrNull { it.id == key } }

    val A = find(refLineAId)
    val B = find(refLineBId)
    if (A == null || B == null) {
        txtImplantationTargetSummary.text = "Choisir les 2 points de la ligne (A et B)"
        txtImplantationTargetInct.text = ""
        txtImplantationDeltas.text = "PK / Déport : NI"
        txtImplantationDistances.text = "NI"
        txtCompassDistance.text = "NI"
        stopProximityBeep()
        resetGuidanceSchedule()
        return
    }

    // Current position needed (we use lat/lon when available, otherwise INCT for INCT_utm).
    val hasGeo = !(lat == 0.0 && lon == 0.0)

    fun pointMeters(p: PointXY): Pair<Double, Double> {
        return when (crsLabel) {
            "WGS84_geog" -> {
                val u = wgs84ToUtm(p.lat, p.lon)
                Pair(u.easting, u.northing)
            }
            "WGS84_utm" -> {
                val e = p.wgs84UtmX
                val n = p.wgs84UtmY
                if (e != null && n != null) Pair(e, n)
                else {
                    val u = wgs84ToUtm(p.lat, p.lon)
                    Pair(u.easting, u.northing)
                }
            }
            else -> Pair(p.x, p.y) // INCT meters
        }
    }

    fun targetMeters(t: TargetPoint): Pair<Double, Double> {
        return when (crsLabel) {
            "WGS84_geog" -> {
                val u = wgs84ToUtm(t.lat, t.lon)
                Pair(u.easting, u.northing)
            }
            "WGS84_utm" -> {
                val e = t.xWgs84Utm
                val n = t.yWgs84Utm
                if (e != null && n != null) Pair(e, n)
                else {
                    val u = wgs84ToUtm(t.lat, t.lon)
                    Pair(u.easting, u.northing)
                }
            }
            else -> Pair(t.xInct, t.yInct)
        }
    }

    fun currentMeters(): Pair<Double, Double>? {
        return when (crsLabel) {
            "WGS84_geog", "WGS84_utm" -> {
                if (!hasGeo) null
                else {
                    val u = wgs84ToUtm(lat, lon)
                    Pair(u.easting, u.northing)
                }
            }
            else -> {
                val inct = currentInct ?: return null
                Pair(inct.x, inct.y)
            }
        }
    }

    val P = currentMeters()
    if (P == null) {
        txtImplantationCurrentInct.text = "Actuel ($crsLabel) : NI"
        txtImplantationTargetSummary.text = "Ligne : ${A.id} - ${B.id}"
        txtImplantationTargetInct.text = ""
        txtImplantationDeltas.text = "PK / Déport : NI"
        txtImplantationDistances.text = "NI"
        txtCompassDistance.text = "NI"
        stopProximityBeep()
        resetGuidanceSchedule()
        return
    }

    val (ax, ay) = pointMeters(A)
    val (bx, by) = pointMeters(B)
    val (px, py) = P

    val abx = bx - ax
    val aby = by - ay
    val abLen2 = abx * abx + aby * aby
    val abLen = kotlin.math.sqrt(abLen2)
    if (abLen < 1e-6) {
        txtImplantationTargetSummary.text = "Ligne invalide (A=B)"
        txtImplantationTargetInct.text = ""
        txtImplantationDeltas.text = "NI"
        txtImplantationDistances.text = "NI"
        txtCompassDistance.text = "NI"
        stopProximityBeep()
        resetGuidanceSchedule()
        return
    }

    // Optional: build a parallel line at fixed offset (left/right relative to A->B).
    // Left normal = (-dy, dx) / |AB|.
    val offAbs = kotlin.math.abs(refLineFixedOffsetMeters)
    val offSigned = if (refLineFixedOffsetLeft) offAbs else -offAbs
    val nx = -aby / abLen
    val ny = abx / abLen
    val axS = ax + offSigned * nx
    val ayS = ay + offSigned * ny
    val bxS = bx + offSigned * nx
    val byS = by + offSigned * ny

    val axUse = if (offAbs > 0.0) axS else ax
    val ayUse = if (offAbs > 0.0) ayS else ay
    val bxUse = if (offAbs > 0.0) bxS else bx
    val byUse = if (offAbs > 0.0) byS else by

    val abxUse = bxUse - axUse
    val abyUse = byUse - ayUse
    val abLenUse = kotlin.math.sqrt(abxUse * abxUse + abyUse * abyUse)

    if (abLenUse < 1e-6) {
        txtImplantationTargetSummary.text = "Ligne invalide"
        txtImplantationTargetInct.text = ""
        txtImplantationDeltas.text = "NI"
        txtImplantationDistances.text = "NI"
        txtCompassDistance.text = "NI"
        stopProximityBeep()
        resetGuidanceSchedule()
        return
    }

    // Project P onto AB: PK in meters from A, offset signed (left positive).
    val apx = px - axUse
    val apy = py - ayUse
    val pk = (apx * abxUse + apy * abyUse) / abLenUse
    val offset = (apx * abyUse - apy * abxUse) / abLenUse  // cross(AP,AB)/|AB|

    val offInfo = if (offAbs > 0.0) {
        val side = if (refLineFixedOffsetLeft) "G" else "D"
        String.format(Locale.US, " | // %.2f m %s", offAbs, side)
    } else ""
    txtImplantationTargetSummary.text = "Ligne : ${A.id} - ${B.id}$offInfo"

    val target = selectedTarget
    if (target == null) {
        txtImplantationTargetInct.text = "Cible : (optionnel)"
        txtImplantationDeltas.text = String.format(Locale.US, "PK=%.2f m  Déport=%.2f m", pk, offset)
        txtImplantationDistances.text = "Choisir une cible pour ΔPK / ΔDéport"
        txtCompassDistance.text = String.format(Locale.US, "%.2f m", kotlin.math.abs(offset))
        return
    }

    val (tx, ty) = targetMeters(target)
    val atx = tx - axUse
    val aty = ty - ayUse
    val pkT = (atx * abxUse + aty * abyUse) / abLenUse
    val offT = (atx * abyUse - aty * abxUse) / abLenUse

    val dPk = pkT - pk
    val dOff = offT - offset
    val dz = (currentInct?.z ?: 0.0) - (target.zInct ?: 0.0)

    txtImplantationTargetInct.text = String.format(Locale.US, "Cible : %s %s | PK=%.2f m  Déport=%.2f m",
        target.sourceLabel, target.id, pkT, offT
    )

    txtImplantationDeltas.text = String.format(Locale.US, "ΔPK=%.2f m  ΔDéport=%.2f m  ΔZ=%.2f m", dPk, dOff, dz)
    txtImplantationDistances.text = String.format(Locale.US, "Actuel : PK=%.2f m  Déport=%.2f m", pk, offset)
    txtCompassDistance.text = String.format(Locale.US, "%.2f m", kotlin.math.abs(dOff))
}

private fun pickReferenceLinePoint(isA: Boolean) {
    val uri = levesUri
    if (uri == null) {
        Toast.makeText(this, "Aucun levé ouvert", Toast.LENGTH_SHORT).show()
        return
    }
    val pts = levePointStore.getLevePointsForCurrentChantier(uri)
    if (pts.isEmpty()) {
        Toast.makeText(this, "Aucun point", Toast.LENGTH_SHORT).show()
        return
    }
    val ids = pts.sortedWith { p1, p2 -> naturalCompare(p1.id, p2.id) }.map { it.id }
    AlertDialog.Builder(this)
        .setTitle(if (isA) "Choisir A" else "Choisir B")
        .setItems(ids.toTypedArray()) { _, which ->
            if (isA) refLineAId = ids[which] else refLineBId = ids[which]
            updateImplantationUI()
        }
        .setNegativeButton("Annuler", null)
        .show()
}


private fun updateTargetLineOnMap(
    currentLat: Double?,
    currentLon: Double?,
    targetLat: Double?,
    targetLon: Double?,
    dhMeters: Double?
) {
    mapController.updateTargetLineOnMap(
        currentLat = currentLat,
        currentLon = currentLon,
        targetLat = targetLat,
        targetLon = targetLon,
        isMapVisible = (screenMap.visibility == View.VISIBLE)
    )
}




private fun updateTargetCircle(targetLat: Double, targetLon: Double, radiusMeters: Double) {
    mapController.updateTargetCircle(targetLat = targetLat, targetLon = targetLon, radiusMeters = radiusMeters)
}


private fun clearTargetCircle() {
    mapController.clearTargetCircle()
}


    private fun applyMapTitle() {
        val title = when (mapMode) {
            MapMode.SURVEY -> getString(R.string.map_title_survey)
            MapMode.IMPLANTATION -> getString(R.string.map_title_implantation)
        }
        supportActionBar?.title = title
        if (::txtMapTitle.isInitialized) txtMapTitle.text = title
    }
}