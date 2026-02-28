package dz.ogefgef322.gnss

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ListView
import androidx.fragment.app.Fragment

class NavigationFragment : Fragment(R.layout.fragment_navigation) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val host = activity as? MainActivity ?: return

        view.findViewById<View>(R.id.btnScanGnss)?.setOnClickListener {
            host.onNavScanGnssClicked()
        }
        view.findViewById<View>(R.id.btnConnectGnss)?.setOnClickListener {
            host.onNavConnectGnssClicked()
        }
        view.findViewById<View>(R.id.btnDisconnectGnss)?.setOnClickListener {
            host.onNavDisconnectGnssClicked()
        }
        view.findViewById<View>(R.id.btnSimToggle)?.setOnClickListener {
            host.onNavToggleSimulationClicked()
        }

        view.findViewById<ListView>(R.id.listGnssDevices)?.setOnItemClickListener { _, _, position, _ ->
            host.onNavGnssDeviceSelected(position)
        }

        view.findViewById<ImageButton>(R.id.btnNavNextNavigation)?.setOnClickListener {
            host.onNavNextClicked()
        }

        view.findViewById<Button>(R.id.btnQuitNavigation)?.setOnClickListener {
            host.onNavQuitClicked()
        }
    }
}
