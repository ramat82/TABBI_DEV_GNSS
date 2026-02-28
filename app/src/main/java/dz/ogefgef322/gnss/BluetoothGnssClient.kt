package dz.ogefgef322.gnss

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.InputStream
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * Handles Bluetooth connection to an external GNSS receiver that outputs NMEA over SPP.
 *
 * Responsibilities:
 * - connect/disconnect to a BluetoothDevice
 * - read NMEA stream and emit complete lines
 * - auto-reconnect when the stream ends unexpectedly (unless user explicitly disconnected)
 *
 * UI (device list / discovery) stays in MainActivity for now.
 */
class BluetoothGnssClient(
    private val adapter: BluetoothAdapter,
    private val hasBtConnectPermission: () -> Boolean,
    private val hasBtScanPermission: () -> Boolean,
    private val onConnectionState: (connected: Boolean, reason: String) -> Unit,
    private val onNmeaLine: (String) -> Unit,
    private val onError: (msg: String, tr: Throwable?) -> Unit = { _, _ -> }
) {

    companion object {
        const val TAG = "OGEF_DZ"
        const val DEFAULT_SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"

        const val REASON_CONNECT = "connect"
        const val REASON_USER_DISCONNECT = "disconnect"
        const val REASON_BT_LOST = "bt_lost"
        const val REASON_STREAM_END = "nmea_stream_end"
        const val REASON_CONNECT_FAILED = "connect_failed"
        const val REASON_READ_ERROR = "read_error"
    }

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var isConnected: Boolean = false
    @Volatile private var userRequestedDisconnect: Boolean = false
    @Volatile private var lastConnectedAddress: String? = null

    private var reconnectAttempts = 0
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null

    fun connect(device: BluetoothDevice, sppUuid: String = DEFAULT_SPP_UUID) {
        userRequestedDisconnect = false
        lastConnectedAddress = device.address

        thread(name = "bt-gnss-connect") {
            try {
                cancelDiscoverySafely()

                if (!hasBtConnectPermission()) return@thread

                val s = device.createRfcommSocketToServiceRecord(UUID.fromString(sppUuid))
                socket = s
                s.connect()

                isConnected = true
                reconnectAttempts = 0
                onConnectionState(true, REASON_CONNECT)

                readLoop(s.inputStream)

            } catch (se: SecurityException) {
                onError("Bluetooth permission refusée", se)
                isConnected = false
                onConnectionState(false, REASON_CONNECT_FAILED)
            } catch (e: Exception) {
                onError("Erreur connexion BT", e)
                isConnected = false
                onConnectionState(false, REASON_CONNECT_FAILED)
            }
        }
    }

    fun disconnect(userInitiated: Boolean = true) {
        if (userInitiated) {
            userRequestedDisconnect = true
        }
        cancelReconnect()

        closeSocketSafely()

        isConnected = false
        if (userInitiated) {
            onConnectionState(false, REASON_USER_DISCONNECT)
        }
    }

    private fun cancelDiscoverySafely() {
        try {
            if (hasBtScanPermission() && adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun closeSocketSafely() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { reconnectHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    private fun scheduleReconnect(reason: String) {
        if (userRequestedDisconnect) return
        val addr = lastConnectedAddress ?: return
        if (!hasBtConnectPermission()) return

        reconnectAttempts += 1
        val delay = when {
            reconnectAttempts <= 1 -> 1000L
            reconnectAttempts == 2 -> 2000L
            reconnectAttempts == 3 -> 5000L
            reconnectAttempts == 4 -> 10_000L
            else -> 15_000L
        }

        Log.d(TAG, "Reconnexion BT programmée (tentative $reconnectAttempts) dans ${delay}ms, raison=$reason")

        cancelReconnect()
        reconnectRunnable = Runnable {
            if (userRequestedDisconnect) return@Runnable
            try {
                val dev = adapter.getRemoteDevice(addr)
                connect(dev)
            } catch (e: Exception) {
                onError("Reconnexion BT impossible", e)
            }
        }.also { reconnectHandler.postDelayed(it, delay) }
    }

    private fun readLoop(input: InputStream) {
        val buf = ByteArray(1024)
        val sb = StringBuilder()

        try {
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break

                sb.append(String(buf, 0, n))

                // split by \n or \r
                while (true) {
                    val iN = sb.indexOf("\n")
                    val iR = sb.indexOf("\r")
                    val cut = when {
                        iN == -1 && iR == -1 -> -1
                        iN == -1 -> iR
                        iR == -1 -> iN
                        else -> min(iN, iR)
                    }
                    if (cut == -1) break

                    val line = sb.substring(0, cut).trim()
                    sb.delete(0, cut + 1)
                    if (line.isNotEmpty()) onNmeaLine(line)
                }
            }
        } catch (e: Exception) {
            onError("Lecture NMEA arrêtée", e)
            if (!userRequestedDisconnect) {
                onConnectionState(false, REASON_READ_ERROR)
            }
        } finally {
            closeSocketSafely()

            val unexpected = !userRequestedDisconnect
            isConnected = false

            if (unexpected) {
                onConnectionState(false, REASON_BT_LOST)
                scheduleReconnect(REASON_STREAM_END)
            }
        }
    }
}
