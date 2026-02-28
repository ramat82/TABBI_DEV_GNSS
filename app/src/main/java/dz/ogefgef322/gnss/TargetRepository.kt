package dz.ogefgef322.gnss

import android.content.ContentResolver
import android.net.Uri
import java.util.Locale

/**
 * Loads Implantation targets from the current LEVES / REPÈRES CSV URIs.
 *
 * This keeps parsing & file IO out of MainActivity.
 *
 * Includes a simple in-memory cache to avoid re-reading CSV files on every click.
 * Cache is automatically invalidated when levesUri / reperesUri change (by value).
 */
class TargetRepository(
    private val contentResolver: ContentResolver
) {
    private val lock = Any()

    private var cachedLevesUriStr: String? = null
    private var cachedReperesUriStr: String? = null
    private var cachedTargets: List<TargetPoint>? = null

    /**
     * Returns cached targets when the source URIs are unchanged.
     * Set [forceReload] to true to bypass the cache.
     */
    fun loadTargets(levesUri: Uri?, reperesUri: Uri?, forceReload: Boolean = false): List<TargetPoint> {
        val levesKey = levesUri?.toString()
        val reperesKey = reperesUri?.toString()

        synchronized(lock) {
            val hit = !forceReload &&
                cachedTargets != null &&
                cachedLevesUriStr == levesKey &&
                cachedReperesUriStr == reperesKey

            if (hit) return cachedTargets!!
        }

        val out = mutableListOf<TargetPoint>()
        if (levesUri != null) out.addAll(parseLeveTargets(levesUri))
        if (reperesUri != null) out.addAll(parseRepereTargets(reperesUri))

        val frozen = out.toList()
        synchronized(lock) {
            cachedLevesUriStr = levesKey
            cachedReperesUriStr = reperesKey
            cachedTargets = frozen
        }
        return frozen
    }

    /** Manually clears the cache (optional). */
    fun invalidate() {
        synchronized(lock) {
            cachedLevesUriStr = null
            cachedReperesUriStr = null
            cachedTargets = null
        }
    }

    /**
     * LEVES CSV: uses header-based columns (supports comments/metadata lines starting with '#').
     * Expected columns (case-insensitive):
     * - id, xinct, yinct, zinct, lat, lon
     * Optional: alt, xwgs84_utm, ywgs84_utm
     */
    private fun parseLeveTargets(uri: Uri): List<TargetPoint> {
        val targets = mutableListOf<TargetPoint>()
        runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                // Skip optional metadata/comment lines until header
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
                val idxX = idxOfAny("xinct", "x_inct", "x")
                val idxY = idxOfAny("yinct", "y_inct", "y")
                val idxZ = idxOfAny("zinct", "z_inct", "z")
                val idxLat = idxOfAny("lat", "latitude")
                val idxLon = idxOfAny("lon", "longitude")
                val idxAlt = idxOfAny("alt", "h", "altitude")
                val idxUtmX = idxOfAny("xwgs84_utm", "utm_e")
                val idxUtmY = idxOfAny("ywgs84_utm", "utm_n")

                if (idxId < 0 || idxX < 0 || idxY < 0 || idxZ < 0 || idxLat < 0 || idxLon < 0) return@use

                reader.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val parts = line.split(",")
                    fun part(i: Int): String? = if (i in parts.indices) parts[i].trim() else null

                    val id = part(idxId) ?: return@forEachLine
                    val xInct = part(idxX)?.toDoubleOrNull() ?: return@forEachLine
                    val yInct = part(idxY)?.toDoubleOrNull() ?: return@forEachLine
                    val zInct = part(idxZ)?.toDoubleOrNull()
                    val lat = part(idxLat)?.toDoubleOrNull() ?: return@forEachLine
                    val lon = part(idxLon)?.toDoubleOrNull() ?: return@forEachLine
                    val alt = if (idxAlt >= 0) part(idxAlt)?.toDoubleOrNull() else null
                    val utmX = if (idxUtmX >= 0) part(idxUtmX)?.toDoubleOrNull() else null
                    val utmY = if (idxUtmY >= 0) part(idxUtmY)?.toDoubleOrNull() else null

                    if (id.isNotBlank()) {
                        targets.add(
                            TargetPoint(
                                id = id,
                                xInct = xInct,
                                yInct = yInct,
                                zInct = zInct,
                                xWgs84Utm = utmX,
                                yWgs84Utm = utmY,
                                lat = lat,
                                lon = lon,
                                alt = alt,
                                sourceLabel = "LEVE"
                            )
                        )
                    }
                }
            }
        }
        return targets
    }

    /**
     * REPÈRES CSV (legacy): we reuse the existing column positions (id at 0, lat at 4, lon at 5).
     * If your file format evolves later, we can upgrade this to header-based parsing too.
     */
    private fun parseRepereTargets(uri: Uri): List<TargetPoint> {
        val targets = mutableListOf<TargetPoint>()
        runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                reader.lineSequence().drop(1).forEach { line ->
                    if (line.isBlank()) return@forEach
                    val parts = line.split(",")
                    if (parts.size <= 5) return@forEach

                    val id = parts[0].trim()
                    val lat = parts[4].trim().toDoubleOrNull()
                    val lon = parts[5].trim().toDoubleOrNull()
                    if (id.isBlank() || lat == null || lon == null) return@forEach

                    val inct = wgs84ToUtmInct(lat, lon, 0.0)
                    targets.add(
                        TargetPoint(
                            id = id,
                            xInct = inct.x,
                            yInct = inct.y,
                            zInct = null,
                            xWgs84Utm = null,
                            yWgs84Utm = null,
                            lat = lat,
                            lon = lon,
                            alt = null,
                            sourceLabel = "REPERE"
                        )
                    )
                }
            }
        }
        return targets
    }
}
