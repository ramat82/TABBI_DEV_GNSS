package dz.ogefgef322.gnss

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment

/**
 * Gestion screen UI handlers.
 *
 * Owns click listeners for the "Gestion" screen (create/open/import/export, nav, quit).
 * The underlying logic still lives in MainActivity for now (incremental refactor).
 */
class GestionFragment : Fragment(R.layout.fragment_gestion) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val act = activity as? MainActivity ?: return

        view.findViewById<View>(R.id.btnCreateLeve)?.setOnClickListener {
            act.onGestionCreateLeveClicked()
        }
        view.findViewById<View>(R.id.btnOpenLeveSurvey)?.setOnClickListener {
            act.onGestionOpenLeveClicked()
        }
        view.findViewById<View>(R.id.btnImportLeveGestion)?.setOnClickListener {
            act.onGestionImportLeveClicked()
        }
        view.findViewById<View>(R.id.btnExportDxfGestion)?.setOnClickListener {
            act.onGestionExportDxfClicked()
        }

        view.findViewById<View>(R.id.btnNavPrevGestion)?.setOnClickListener {
            act.onGestionPrevClicked()
        }
        view.findViewById<View>(R.id.btnNavNextGestion)?.setOnClickListener {
            act.onGestionNextClicked()
        }
        view.findViewById<View>(R.id.btnQuitGestion)?.setOnClickListener {
            act.onGestionQuitClicked()
        }
    }
}
