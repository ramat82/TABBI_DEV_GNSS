package dz.ogefgef322.gnss

import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager

/** Command channel for Area/Surface survey screen -> MainActivity. */
object AreaSurveyController {

    const val ACTION_AREA_SURVEY_CMD = "dz.ogefgef322.gnss.ACTION_AREA_SURVEY_CMD"

    const val EXTRA_CMD = "cmd"
    const val EXTRA_MODE = "mode"            // DIST / TIME
    const val EXTRA_DIST_M = "dist_m"        // Double
    const val EXTRA_TIME_MS = "time_ms"      // Long
    const val EXTRA_USE_ACC = "use_acc"      // Boolean
    const val EXTRA_ACC_MAX = "acc_max"      // Double
    const val EXTRA_USE_SPEED = "use_speed"  // Boolean
    const val EXTRA_SPEED_MIN = "speed_min"  // Double
    const val EXTRA_START_INDEX = "start_index" // Int

    const val CMD_START = "START"
    const val CMD_PAUSE = "PAUSE"
    const val CMD_STOP = "STOP"
    const val CMD_ADD = "ADD"
    const val CMD_CLOSE = "CLOSE"

    fun send(
        context: Context,
        cmd: String,
        mode: String,
        distMeters: Double,
        timeMs: Long,
        useAcc: Boolean,
        accMax: Double,
        useSpeed: Boolean,
        speedMin: Double,
        startIndex: Int = 1,
    ) {
        val i = Intent(ACTION_AREA_SURVEY_CMD).apply {
            putExtra(EXTRA_CMD, cmd)
            putExtra(EXTRA_MODE, mode)
            putExtra(EXTRA_DIST_M, distMeters)
            putExtra(EXTRA_TIME_MS, timeMs)
            putExtra(EXTRA_USE_ACC, useAcc)
            putExtra(EXTRA_ACC_MAX, accMax)
            putExtra(EXTRA_USE_SPEED, useSpeed)
            putExtra(EXTRA_SPEED_MIN, speedMin)
            putExtra(EXTRA_START_INDEX, startIndex)
        }
        // Must use LocalBroadcastManager because MainActivity registers with it.
        LocalBroadcastManager.getInstance(context).sendBroadcast(i)
    }
}
