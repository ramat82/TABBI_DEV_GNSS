package dz.ogefgef322.gnss

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Encapsulates classic Bluetooth discovery (startDiscovery + ACTION_FOUND stream).
 *
 * MainActivity stays in charge of the UI & list model; this class only manages
 * receiver registration, discovery lifecycle, and a scan timeout.
 */
class BluetoothDeviceScanner(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val hasBtScanPermission: () -> Boolean,
    private val onScanningChanged: (Boolean) -> Unit,
    private val onDeviceFound: (device: BluetoothDevice, rssi: Int?) -> Unit,
    private val onScanFinished: () -> Unit,
    private val onError: (msg: String, tr: Throwable?) -> Unit = { _, _ -> }
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 12_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var registered = false
    private var scanning = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    setScanning(true)
                }

                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }

                    val rssiShort = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    val rssi = if (rssiShort != Short.MIN_VALUE) rssiShort.toInt() else null
                    if (device != null) {
                        onDeviceFound(device, rssi)
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    cancelTimeout()
                    setScanning(false)
                    onScanFinished()
                }
            }
        }
    }

    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_FOUND)
        }
        runCatching {
            context.registerReceiver(receiver, filter)
            registered = true
        }.onFailure { tr ->
            onError("Bluetooth scan: registerReceiver failed", tr)
        }
    }

    fun unregister() {
        if (!registered) return
        cancelTimeout()
        runCatching {
            context.unregisterReceiver(receiver)
        }.onFailure { tr ->
            // ignore unregister failures, but keep a trace
            onError("Bluetooth scan: unregisterReceiver failed", tr)
        }
        registered = false
        setScanning(false)
    }

    fun startScan(timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        if (!adapter.isEnabled) {
            onError("Bluetooth scan: adapter disabled", null)
            setScanning(false)
            return
        }
        if (!hasBtScanPermission()) {
            onError("Bluetooth scan: missing scan permission", null)
            setScanning(false)
            return
        }

        try {
            // Ensure receiver is active
            register()

            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }

            val ok = adapter.startDiscovery()
            if (!ok) {
                onError("Bluetooth scan: startDiscovery() returned false", null)
                setScanning(false)
                return
            }

            setScanning(true)
            scheduleTimeout(timeoutMs)
        } catch (se: SecurityException) {
            onError("Bluetooth scan: SecurityException", se)
            setScanning(false)
        } catch (tr: Throwable) {
            onError("Bluetooth scan: unexpected error", tr)
            setScanning(false)
        }
    }

    fun stopScan() {
        cancelTimeout()
        try {
            if (adapter.isDiscovering && hasBtScanPermission()) {
                adapter.cancelDiscovery()
            }
        } catch (_: SecurityException) {
        } catch (tr: Throwable) {
            onError("Bluetooth scan: stopScan failed", tr)
        }
        setScanning(false)
    }

    private fun scheduleTimeout(timeoutMs: Long) {
        cancelTimeout()
        timeoutRunnable = Runnable {
            try {
                if (adapter.isDiscovering && hasBtScanPermission()) {
                    adapter.cancelDiscovery()
                }
            } catch (_: SecurityException) {
            } catch (tr: Throwable) {
                onError("Bluetooth scan: timeout cancelDiscovery failed", tr)
            }
            setScanning(false)
        }.also { handler.postDelayed(it, timeoutMs) }
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun setScanning(value: Boolean) {
        if (scanning == value) return
        scanning = value
        onScanningChanged(value)
    }
}
