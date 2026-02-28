package dz.ogefgef322.gnss

/**
 * Lightweight NMEA parser used by MainActivity.
 *
 * It focuses on the sentences currently used by the app:
 * - GGA: position + altitude + fix quality + satellites
 * - RMC: position (valid status only)
 * - GSV: satellites count
 *
 * No Android context is required; errors can be forwarded via [onError].
 */
class NmeaParser(
    private val listener: Listener,
    private val onError: ((String, Throwable) -> Unit)? = null
) {

    interface Listener {
        fun onGgaFix(update: GgaFix)
        fun onRmcFix(update: RmcFix)
        fun onSatellitesCount(count: Int)
    }

    data class GgaFix(
        val lat: Double,
        val lon: Double,
        val alt: Double,
        val fixQuality: Int,
        val satellites: Int
    )

    data class RmcFix(
        val lat: Double,
        val lon: Double
    )

    fun consumeLine(lineRaw: String) {
        val line = lineRaw.trim()
        if (line.isEmpty()) return

        when {
            line.startsWith("\$GNGGA") || line.startsWith("\$GPGGA") -> parseGga(line)
            line.startsWith("\$GNRMC") || line.startsWith("\$GPRMC") -> parseRmc(line)
            line.startsWith("\$GNGSV") || line.startsWith("\$GPGSV") -> parseGsv(line)
        }
    }

    private fun parseGga(gga: String) {
        val p = gga.split(",")
        if (p.size < 10) return

        try {
            val newLat = nmeaToDecimal(p[2], p[3])
            val newLon = nmeaToDecimal(p[4], p[5])
            val newFix = p[6].toIntOrNull() ?: 0
            val newSat = p[7].toIntOrNull() ?: 0
            val newAlt = p[9].toDoubleOrNull() ?: 0.0

            if (newLat == 0.0 || newLon == 0.0) return

            listener.onGgaFix(
                GgaFix(
                    lat = newLat,
                    lon = newLon,
                    alt = newAlt,
                    fixQuality = newFix,
                    satellites = newSat
                )
            )
        } catch (e: Exception) {
            onError?.invoke("Erreur parseGGA", e)
        }
    }

    private fun parseRmc(rmc: String) {
        val p = rmc.split(",")
        if (p.size < 7) return
        if (p[2] != "A") return

        try {
            val newLat = nmeaToDecimal(p[3], p[4])
            val newLon = nmeaToDecimal(p[5], p[6])

            if (newLat != 0.0 && newLon != 0.0) {
                listener.onRmcFix(RmcFix(lat = newLat, lon = newLon))
            }
        } catch (e: Exception) {
            onError?.invoke("Erreur parseRMC", e)
        }
    }

    private fun parseGsv(gsv: String) {
        val p = gsv.split(",")
        if (p.size >= 4) {
            val sat = p[3].toIntOrNull()
            if (sat != null) listener.onSatellitesCount(sat)
        }
    }

    private fun nmeaToDecimal(nmea: String, hemi: String): Double {
        if (nmea.isBlank() || nmea == "0") return 0.0
        return try {
            val dot = nmea.indexOf('.')
            if (dot < 0) return 0.0

            val degLen = when (hemi) {
                "N", "S" -> 2
                "E", "W" -> 3
                else -> if (dot > 4) 3 else 2
            }

            val deg = nmea.substring(0, degLen).toDouble()
            val min = nmea.substring(degLen).toDouble()

            var dec = deg + (min / 60.0)
            if (hemi == "S" || hemi == "W") dec = -dec
            dec
        } catch (_: Exception) {
            0.0
        }
    }
}
