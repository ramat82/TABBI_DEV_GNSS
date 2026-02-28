package dz.ogefgef322.gnss

import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class AutoSurveyActivity : AppCompatActivity() {

    private lateinit var radioMode: RadioGroup
    private lateinit var rbDist: RadioButton
    private lateinit var rbTime: RadioButton

    private lateinit var inputDist: EditText
    private lateinit var inputTime: EditText

    private lateinit var chkAcc: CheckBox
    private lateinit var inputAcc: EditText

    private lateinit var chkSpeed: CheckBox
    private lateinit var inputSpeed: EditText

    private lateinit var btnStart: Button
    private lateinit var btnPause: Button
    private lateinit var btnStop: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auto_survey)

        radioMode = findViewById(R.id.radioAutoMode)
        rbDist = findViewById(R.id.rbAutoDist)
        rbTime = findViewById(R.id.rbAutoTime)

        inputDist = findViewById(R.id.inputAutoDist)
        inputTime = findViewById(R.id.inputAutoTime)

        chkAcc = findViewById(R.id.chkAutoAcc)
        inputAcc = findViewById(R.id.inputAutoAcc)

        chkSpeed = findViewById(R.id.chkAutoSpeed)
        inputSpeed = findViewById(R.id.inputAutoSpeed)

        btnStart = findViewById(R.id.btnAutoStart)
        btnPause = findViewById(R.id.btnAutoPause)
        btnStop = findViewById(R.id.btnAutoStop)

        // Defaults
        rbDist.isChecked = true
        inputDist.setText("5")
        inputTime.setText("2")
        inputAcc.setText("0.30")
        inputSpeed.setText("0.20")

        updateModeUi()
        updateFilterUi()

        radioMode.setOnCheckedChangeListener { _, _ -> updateModeUi() }
        chkAcc.setOnCheckedChangeListener { _, _ -> updateFilterUi() }
        chkSpeed.setOnCheckedChangeListener { _, _ -> updateFilterUi() }

        btnStart.setOnClickListener {
            val (mode, distM, timeS) = readMode()
            val useAcc = chkAcc.isChecked
            val accMax = readDouble(inputAcc.text?.toString(), 0.30)
            val useSpeed = chkSpeed.isChecked
            val speedMin = readDouble(inputSpeed.text?.toString(), 0.20)

            AutoSurveyController.send(
                context = this,
                cmd = AutoSurveyController.CMD_START,
                mode = mode,
                distMeters = distM,
                timeSeconds = timeS,
                useAcc = useAcc,
                accMax = accMax,
                useSpeed = useSpeed,
                speedMin = speedMin
            )
            Toast.makeText(this, "Levé auto: START", Toast.LENGTH_SHORT).show()
        }

        btnPause.setOnClickListener {
            AutoSurveyController.send(this, AutoSurveyController.CMD_PAUSE)
        }

        btnStop.setOnClickListener {
            AutoSurveyController.send(this, AutoSurveyController.CMD_STOP)
        }

        findViewById<ImageButton>(R.id.btnAutoBack).setOnClickListener { finish() }
    }

    private fun updateModeUi() {
        val dist = rbDist.isChecked
        inputDist.isEnabled = dist
        inputTime.isEnabled = !dist
    }

    private fun updateFilterUi() {
        inputAcc.isEnabled = chkAcc.isChecked
        inputSpeed.isEnabled = chkSpeed.isChecked
    }

    private fun readMode(): Triple<String, Double, Double> {
        val mode = if (rbTime.isChecked) "TIME" else "DIST"
        val distM = readDouble(inputDist.text?.toString(), 5.0)
        val timeS = readDouble(inputTime.text?.toString(), 2.0)
        return Triple(mode, distM, timeS)
    }

    private fun readDouble(s: String?, def: Double): Double {
        val v = s?.trim()?.replace(',', '.')
        return v?.toDoubleOrNull() ?: def
    }
}
