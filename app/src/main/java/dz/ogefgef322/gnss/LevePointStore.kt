package dz.ogefgef322.gnss

import android.content.ContentResolver
import android.net.Uri
import java.util.Locale

data class PointXY(
    val id: String,
    /** INCT coordinates (storage columns: xinct/yinct/zinct). */
    val x: Double,
    val y: Double,
    val z: Double,
    val lat: Double,
    val lon: Double,
    /** WGS84 altitude (from CSV 'alt' column) when available; otherwise equals [z]. */
    val alt: Double = 0.0,
    /** Optional WGS84 UTM (easting/northing) read from xwgs84_utm/ywgs84_utm when available. */
    val wgs84UtmX: Double? = null,
    val wgs84UtmY: Double? = null,
    val typeLabel: String? = null // type du point (optionnel)
)

/** Used for base station selection (any point from the levé). */
data class StationCandidate(
    val id: String,
    val x: Double,
    val y: Double,
    val z: Double,
    /** Raw timestamp string from CSV column (e.g. "2026-02-19 12:18:41" or "2026-02-19"). */
    val timestamp: String,
    /** File/levé name for UI context (optional). */
    val leveName: String? = null,
    /** True when the candidate comes from the currently opened levé. */
    val isCurrentLeve: Boolean = false
)

class LevePointStore(private val contentResolver: ContentResolver) {

    /** Returns points for display/implantation (always uses x/y/z). */
    fun getLevePointsForCurrentChantier(uri: Uri): List<PointXY> {
        val points = mutableListOf<PointXY>()

        runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                // Skip optional metadata/comment lines
                var headerLine: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    val t = line.trim()
                    if (t.isEmpty()) continue
                    if (t.startsWith("#")) continue
                    headerLine = line
                    break
                }

                if (headerLine == null) return@use
                val headers = headerLine.split(",").map { it.trim().lowercase(Locale.getDefault()) }

                fun idxOfAny(vararg names: String): Int {
                    for (n in names) {
                        val i = headers.indexOf(n.lowercase(Locale.getDefault()))
                        if (i >= 0) return i
                    }
                    return -1
                }

                val idxId = idxOfAny("id")
                // Storage columns are ALWAYS INCT.
                val idxX = idxOfAny("xinct", "x_inct", "x", "x_proj", "e", "easting")
                val idxY = idxOfAny("yinct", "y_inct", "y", "y_proj", "n", "northing")
                val idxZ = idxOfAny("zinct", "z_inct", "z", "z_proj", "altitude", "h")
                val idxLat = idxOfAny("lat", "latitude")
                val idxLon = idxOfAny("lon", "longitude")
                val idxAlt = idxOfAny("alt", "altitude", "h")
                val idxUtmX = idxOfAny("xwgs84_utm", "utm_e", "e_utm")
                val idxUtmY = idxOfAny("ywgs84_utm", "utm_n", "n_utm")
                val idxType = idxOfAny("type", "type_label", "typ")

                if (idxId < 0 || idxX < 0 || idxY < 0 || idxZ < 0 || idxLat < 0 || idxLon < 0) return@use

                reader.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val parts = line.split(",")

                    fun part(i: Int): String? = if (i in parts.indices) parts[i].trim() else null

                    val id = part(idxId) ?: return@forEachLine
                    val x = part(idxX)?.toDoubleOrNull()
                    val y = part(idxY)?.toDoubleOrNull()
                    val z = part(idxZ)?.toDoubleOrNull()
                    val lat = part(idxLat)?.toDoubleOrNull()
                    val lon = part(idxLon)?.toDoubleOrNull()
                    val alt = if (idxAlt >= 0) part(idxAlt)?.toDoubleOrNull() else null
                    val utmX = if (idxUtmX >= 0) part(idxUtmX)?.toDoubleOrNull() else null
                    val utmY = if (idxUtmY >= 0) part(idxUtmY)?.toDoubleOrNull() else null

                    val typeLabel = if (idxType >= 0) {
                        part(idxType)?.takeIf { it.isNotEmpty() }?.uppercase(Locale.ROOT)
                    } else null

                    if (id.isNotBlank() && x != null && y != null && z != null && lat != null && lon != null) {
                        points.add(
                            PointXY(
                                id = id,
                                x = x,
                                y = y,
                                z = z,
                                lat = lat,
                                lon = lon,
                                alt = alt ?: z,
                                wgs84UtmX = utmX,
                                wgs84UtmY = utmY,
                                typeLabel = typeLabel
                            )
                        )
                    }
                }
            }
        }

        return points
    }

    /**
     * Reads station candidates from a levé CSV.
     *
     * Rules:
     * - Any point id is eligible (no more "STA" restriction).
     * - Timestamp is read from the "timestamp" column when available; otherwise empty string.
     */
    fun getStationCandidates(uri: Uri, leveName: String? = null, isCurrentLeve: Boolean = false): List<StationCandidate> {
        val out = mutableListOf<StationCandidate>()
        runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                // Skip metadata/comment lines
                var headerLine: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    val t = line.trim()
                    if (t.isEmpty()) continue
                    if (t.startsWith("#")) continue
                    headerLine = line
                    break
                }
                if (headerLine == null) return@use

                val headers = headerLine.split(",").map { it.trim().lowercase(Locale.getDefault()) }
                fun idxOfAny(vararg names: String): Int {
                    for (n in names) {
                        val i = headers.indexOf(n.lowercase(Locale.getDefault()))
                        if (i >= 0) return i
                    }
                    return -1
                }

                val idxId = idxOfAny("id")
                // Stationing always relies on INCT storage columns.
                val idxX = idxOfAny("xinct", "x_inct", "x")
                val idxY = idxOfAny("yinct", "y_inct", "y")
                val idxZ = idxOfAny("zinct", "z_inct", "z")
                val idxTs = idxOfAny("timestamp", "time", "date")
                if (idxId < 0 || idxX < 0 || idxY < 0 || idxZ < 0) return@use

                reader.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val parts = line.split(",")
                    fun part(i: Int): String? = if (i in parts.indices) parts[i].trim() else null
                    val id = part(idxId) ?: return@forEachLine
                    if (id.isBlank()) return@forEachLine

                    val x = part(idxX)?.toDoubleOrNull() ?: return@forEachLine
                    val y = part(idxY)?.toDoubleOrNull() ?: return@forEachLine
                    val z = part(idxZ)?.toDoubleOrNull() ?: return@forEachLine
                    val ts = if (idxTs >= 0) (part(idxTs) ?: "") else ""

                    out.add(
                        StationCandidate(
                            id = id,
                            x = x,
                            y = y,
                            z = z,
                            timestamp = ts,
                            leveName = leveName,
                            isCurrentLeve = isCurrentLeve
                        )
                    )
                }
            }
        }
        return out
    }
}
