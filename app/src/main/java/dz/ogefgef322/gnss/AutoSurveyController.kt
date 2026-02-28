package dz.ogefgef322.gnss

import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager

/**
 * Broadcast contract between AutoSurveyActivity (UI) and MainActivity (GNSS + file writing).
 * MainActivity listens to ACTION_AUTO_SURVEY_CMD.
 */
object AutoSurveyController {
    const val ACTION_AUTO_SURVEY_CMD = "dz.ogefgef322.gnss.AUTO_SURVEY_CMD"

    const val CMD_START = "START"
    const val CMD_PAUSE = "PAUSE"
    const val CMD_STOP = "STOP"

    const val EXTRA_CMD = "cmd"
    const val EXTRA_MODE = "mode"            // "DIST" or "TIME"
    const val EXTRA_DIST_M = "dist_m"        // Double
    const val EXTRA_TIME_S = "time_s"        // Double
    const val EXTRA_USE_ACC = "use_acc"      // Boolean
    const val EXTRA_ACC_MAX = "acc_max"      // Double
    const val EXTRA_USE_SPEED = "use_speed"  // Boolean
    const val EXTRA_SPEED_MIN = "speed_min"  // Double

    fun send(
        context: Context,
        cmd: String,
        mode: String? = null,
        distMeters: Double? = null,
        timeSeconds: Double? = null,
        useAcc: Boolean? = null,
        accMax: Double? = null,
        useSpeed: Boolean? = null,
        speedMin: Double? = null
    ) {
        val intent = Intent(ACTION_AUTO_SURVEY_CMD).apply {
            putExtra(EXTRA_CMD, cmd)
            if (mode != null) putExtra(EXTRA_MODE, mode)
            if (distMeters != null) putExtra(EXTRA_DIST_M, distMeters)
            if (timeSeconds != null) putExtra(EXTRA_TIME_S, timeSeconds)
            if (useAcc != null) putExtra(EXTRA_USE_ACC, useAcc)
            if (accMax != null) putExtra(EXTRA_ACC_MAX, accMax)
            if (useSpeed != null) putExtra(EXTRA_USE_SPEED, useSpeed)
            if (speedMin != null) putExtra(EXTRA_SPEED_MIN, speedMin)
        }
        // Must match MainActivity which registers with LocalBroadcastManager
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }
}
