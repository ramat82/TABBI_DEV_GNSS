package dz.ogefgef322.gnss

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment

/**
 * Survey (Levé) screen UI handlers.
 *
 * Owns click listeners for the "Levé" screen. Business logic stays in MainActivity for now.
 */
class SurveyFragment : Fragment(R.layout.fragment_survey) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val act = activity as? MainActivity ?: return

        view.findViewById<View>(R.id.btnStationSetupSurvey)?.setOnClickListener {
            act.onSurveyStationSetupClicked()
        }
        view.findViewById<View>(R.id.btnSavePointSurvey)?.setOnClickListener {
            act.onSurveySavePointClicked()
        }
        view.findViewById<View>(R.id.btnTopoPlanSurvey)?.setOnClickListener {
            act.onSurveyTopoPlanClicked()
        }
        view.findViewById<View>(R.id.btnVoiceSurvey)?.setOnClickListener {
            act.onSurveyVoiceClicked()
        }
        view.findViewById<View>(R.id.btnApplyIdSurvey)?.setOnClickListener {
            act.onSurveyApplyIdClicked()
        }
        view.findViewById<View>(R.id.btnAntennaHeightSurvey)?.setOnClickListener {
            act.onSurveyAntennaHeightClicked()
        }
        view.findViewById<View>(R.id.btnPrecisionSurvey)?.setOnClickListener {
            act.onSurveyPrecisionClicked()
        }

        view.findViewById<View>(R.id.btnNavPrevLeve)?.setOnClickListener {
            act.onSurveyPrevClicked()
        }
        view.findViewById<View>(R.id.btnNavNextLeve)?.setOnClickListener {
            act.onSurveyNextClicked()
        }
        view.findViewById<View>(R.id.btnQuitLeve)?.setOnClickListener {
            act.onSurveyQuitClicked()
        }
    }
}
