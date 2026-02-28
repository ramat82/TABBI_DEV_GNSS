package dz.ogefgef322.gnss

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment

/**
 * Map screen UI handlers.
 *
 * This fragment owns the click listeners for the map toolbar (survey + implantation).
 * The underlying map logic (MapController, GNSS state, etc.) is still driven by MainActivity
 * for now, to keep this refactor incremental and low-risk.
 */
class MapFragment : Fragment(R.layout.fragment_map) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val act = activity as? MainActivity ?: return

        // Survey actions
        view.findViewById<View>(R.id.btnMapAddPoint)?.setOnClickListener {
            act.onMapAddPointClicked()
        }
        view.findViewById<View>(R.id.btnMapCenterRover)?.setOnClickListener {
            act.onMapCenterRoverClicked(isImplantation = false)
        }
        view.findViewById<View>(R.id.btnMapFollow)?.setOnClickListener {
            act.onMapFollowClicked(isImplantation = false)
        }
        view.findViewById<View>(R.id.btnMapToggleTrack)?.setOnClickListener {
            act.onMapToggleTrackClicked()
        }
        view.findViewById<View>(R.id.btnMapMore)?.setOnClickListener { anchor ->
            act.onMapMoreClicked(isImplantation = false, anchor = anchor)
        }

        // Implantation actions
        view.findViewById<View>(R.id.btnMapImplantCenterRover)?.setOnClickListener {
            act.onMapCenterRoverClicked(isImplantation = true)
        }
        view.findViewById<View>(R.id.btnMapImplantCenterTarget)?.setOnClickListener {
            act.onMapCenterTargetClicked()
        }
        view.findViewById<View>(R.id.btnMapImplantFollow)?.setOnClickListener {
            act.onMapFollowClicked(isImplantation = true)
        }
        view.findViewById<View>(R.id.btnMapImplantToggleCircle)?.setOnClickListener {
            act.onMapToggleCircleClicked()
        }
        view.findViewById<View>(R.id.btnMapImplantMore)?.setOnClickListener { anchor ->
            act.onMapMoreClicked(isImplantation = true, anchor = anchor)
        }

        // Map navigation + quit
        view.findViewById<View>(R.id.btnNavPrevMap)?.setOnClickListener { act.onMapPrevClicked() }
        view.findViewById<View>(R.id.btnNavNextMap)?.setOnClickListener { act.onMapNextClicked() }
        view.findViewById<View>(R.id.btnQuitMap)?.setOnClickListener { act.onMapQuitClicked() }
    }
}
