package dz.ogefgef322.gnss

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Intersect program: choose 4 points (A,B) and (C,D) then:
 * - IMPLANT: compute intersection and return it to MainActivity as a temporary target
 * - SAVE: compute intersection and ask MainActivity to save it as INT_###
 */
class IntersectActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LEVE_URI = "extra_leve_uri"
        const val EXTRA_ACTION = "extra_action" // returned to caller
        const val ACTION_IMPLANT = "IMPLANT"
        const val ACTION_SAVE = "SAVE"

        const val EXTRA_A = "extra_a"
        const val EXTRA_B = "extra_b"
        const val EXTRA_C = "extra_c"
        const val EXTRA_D = "extra_d"

// Optional measured points (when user clicks "Mesurer A/B/C/D")
const val EXTRA_MEAS_A_LAT = "extra_meas_a_lat"
const val EXTRA_MEAS_A_LON = "extra_meas_a_lon"
const val EXTRA_MEAS_A_ALT = "extra_meas_a_alt"
const val EXTRA_MEAS_B_LAT = "extra_meas_b_lat"
const val EXTRA_MEAS_B_LON = "extra_meas_b_lon"
const val EXTRA_MEAS_B_ALT = "extra_meas_b_alt"
const val EXTRA_MEAS_C_LAT = "extra_meas_c_lat"
const val EXTRA_MEAS_C_LON = "extra_meas_c_lon"
const val EXTRA_MEAS_C_ALT = "extra_meas_c_alt"
const val EXTRA_MEAS_D_LAT = "extra_meas_d_lat"
const val EXTRA_MEAS_D_LON = "extra_meas_d_lon"
const val EXTRA_MEAS_D_ALT = "extra_meas_d_alt"
    }

    private var leveUri: Uri? = null
    private lateinit var pointStore: LevePointStore

    private var aId: String? = null
    private var bId: String? = null
    private var cId: String? = null
    private var dId: String? = null


private var measALat: Double? = null
private var measALon: Double? = null
private var measAAlt: Double? = null
private var measBLat: Double? = null
private var measBLon: Double? = null
private var measBAlt: Double? = null
private var measCLat: Double? = null
private var measCLon: Double? = null
private var measCAlt: Double? = null
private var measDLat: Double? = null
private var measDLon: Double? = null
private var measDAlt: Double? = null


    private lateinit var txtA: TextView
    private lateinit var txtB: TextView
    private lateinit var txtC: TextView
    private lateinit var txtD: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_intersect)

        pointStore = LevePointStore(contentResolver)
        leveUri = intent.getStringExtra(EXTRA_LEVE_URI)?.let { runCatching { Uri.parse(it) }.getOrNull() }

        txtA = findViewById(R.id.txtIntersectA)
        txtB = findViewById(R.id.txtIntersectB)
        txtC = findViewById(R.id.txtIntersectC)
        txtD = findViewById(R.id.txtIntersectD)

        val btnPickA = findViewById<Button>(R.id.btnPickA)
        val btnPickB = findViewById<Button>(R.id.btnPickB)
        val btnPickC = findViewById<Button>(R.id.btnPickC)
        val btnPickD = findViewById<Button>(R.id.btnPickD)
        val btnMeasA = findViewById<Button>(R.id.btnMeasA)
        val btnMeasB = findViewById<Button>(R.id.btnMeasB)
        val btnMeasC = findViewById<Button>(R.id.btnMeasC)
        val btnMeasD = findViewById<Button>(R.id.btnMeasD)
        val btnImplant = findViewById<Button>(R.id.btnIntersectImplant)
        val btnSave = findViewById<Button>(R.id.btnIntersectSave)
        val btnBack = findViewById<Button>(R.id.btnIntersectBack)

        fun refreshLabels() {
            txtA.text = aId ?: "(non choisi)"
            txtB.text = bId ?: "(non choisi)"
            txtC.text = cId ?: "(non choisi)"
            txtD.text = dId ?: "(non choisi)"
        }
        refreshLabels()

        fun pickPoint(onPicked: (String) -> Unit) {
            val uri = leveUri
            if (uri == null) {
                Toast.makeText(this, "Aucun levé ouvert", Toast.LENGTH_SHORT).show()
                return
            }
            val pts = pointStore.getLevePointsForCurrentChantier(uri)
                .sortedWith { p1, p2 -> naturalCompare(p1.id, p2.id) }
            if (pts.isEmpty()) {
                Toast.makeText(this, "Aucun point", Toast.LENGTH_SHORT).show()
                return
            }
            val labels = pts.map { it.id }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Choisir un point")
                .setItems(labels) { _, which ->
                    onPicked(labels[which])
                    refreshLabels()
                }
                .setNegativeButton("Annuler", null)
                .show()
        }

        btnPickA.setOnClickListener { pickPoint { aId = it } }
        btnPickB.setOnClickListener { pickPoint { bId = it } }
        btnPickC.setOnClickListener { pickPoint { cId = it } }
        btnPickD.setOnClickListener { pickPoint { dId = it } }


fun measurePoint(tag: String) {
    val latM = MainActivity.lastGnssLat
    val lonM = MainActivity.lastGnssLon
    val altM = MainActivity.lastGnssAlt

    if (!latM.isFinite() || !lonM.isFinite() || (latM == 0.0 && lonM == 0.0)) {
        Toast.makeText(this, "GNSS non disponible", Toast.LENGTH_SHORT).show()
        return
    }

    when (tag) {
        "A" -> {
            aId = "__MEAS_A__"
            measALat = latM; measALon = lonM; measAAlt = altM
        }
        "B" -> {
            bId = "__MEAS_B__"
            measBLat = latM; measBLon = lonM; measBAlt = altM
        }
        "C" -> {
            cId = "__MEAS_C__"
            measCLat = latM; measCLon = lonM; measCAlt = altM
        }
        "D" -> {
            dId = "__MEAS_D__"
            measDLat = latM; measDLon = lonM; measDAlt = altM
        }
    }
    refreshLabels()
}

btnMeasA.setOnClickListener { measurePoint("A") }
btnMeasB.setOnClickListener { measurePoint("B") }
btnMeasC.setOnClickListener { measurePoint("C") }
btnMeasD.setOnClickListener { measurePoint("D") }

        fun canRun(): Boolean = !aId.isNullOrBlank() && !bId.isNullOrBlank() && !cId.isNullOrBlank() && !dId.isNullOrBlank()

        btnImplant.setOnClickListener {
            if (!canRun()) {
                Toast.makeText(this, "Choisis A, B, C, D", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val data = intentForResult(ACTION_IMPLANT)
            setResult(Activity.RESULT_OK, data)
            finish()
        }

        btnSave.setOnClickListener {
            if (!canRun()) {
                Toast.makeText(this, "Choisis A, B, C, D", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val data = intentForResult(ACTION_SAVE)
            setResult(Activity.RESULT_OK, data)
            finish()
        }

        btnBack.setOnClickListener { finish() }
    }

    private fun intentForResult(action: String) = android.content.Intent().apply {
        putExtra(EXTRA_ACTION, action)
        putExtra(EXTRA_LEVE_URI, leveUri?.toString().orEmpty())
        putExtra(EXTRA_A, aId)
        putExtra(EXTRA_B, bId)
        putExtra(EXTRA_C, cId)
        putExtra(EXTRA_D, dId)

// measured points (optional)
putExtra(EXTRA_MEAS_A_LAT, measALat ?: Double.NaN)
putExtra(EXTRA_MEAS_A_LON, measALon ?: Double.NaN)
putExtra(EXTRA_MEAS_A_ALT, measAAlt ?: Double.NaN)
putExtra(EXTRA_MEAS_B_LAT, measBLat ?: Double.NaN)
putExtra(EXTRA_MEAS_B_LON, measBLon ?: Double.NaN)
putExtra(EXTRA_MEAS_B_ALT, measBAlt ?: Double.NaN)
putExtra(EXTRA_MEAS_C_LAT, measCLat ?: Double.NaN)
putExtra(EXTRA_MEAS_C_LON, measCLon ?: Double.NaN)
putExtra(EXTRA_MEAS_C_ALT, measCAlt ?: Double.NaN)
putExtra(EXTRA_MEAS_D_LAT, measDLat ?: Double.NaN)
putExtra(EXTRA_MEAS_D_LON, measDLon ?: Double.NaN)
putExtra(EXTRA_MEAS_D_ALT, measDAlt ?: Double.NaN)
    }

    /** Natural compare so P2 < P10. */
    private fun naturalCompare(a: String, b: String): Int {
        val ra = Regex("(\\d+)|(\\D+)")
        val ma = ra.findAll(a)
        val mb = ra.findAll(b)
        val ita = ma.iterator()
        val itb = mb.iterator()
        while (ita.hasNext() && itb.hasNext()) {
            val pa = ita.next().value
            val pb = itb.next().value
            val na = pa.toIntOrNull()
            val nb = pb.toIntOrNull()
            val c = if (na != null && nb != null) na.compareTo(nb) else pa.compareTo(pb, ignoreCase = true)
            if (c != 0) return c
        }
        return a.length.compareTo(b.length)
    }
}
