package dz.ogefgef322.gnss
import android.content.ContentResolver
import android.net.Uri

/**
 * Helpers to store/validate CRS information in CSV header.
 *
 * We write a metadata line at the top of each exported CSV:
 *   # CRS: <signature>
 *
 * Signatures are normalized strings, e.g.:
 *   - EPSG:2154
 *   - EPSG:4326
 *   - INCT
 *   - WGS84_UTM_AUTO
 *
 * Legacy CSVs (old versions) may not contain this line.
 */
object CsvCrsUtils {

    private const val PREFIX = "#"
    private const val KEY = "CRS:"

    fun expectedSignatureForProject(crs: ProjectCrs): String {
        return when (crs.id) {
            // The app only supports 3 display CRS tags.
            ProjectCrs.INCT_UTM_ALGERIA.id -> "INCT_utm"
            ProjectCrs.WGS84_UTM_AUTO.id -> "WGS84_utm"
            ProjectCrs.WGS84_LATLON.id -> "WGS84_geog"
            else -> crs.epsg?.let { "EPSG:$it" } ?: crs.id
        }
    }

    fun metadataLineForProject(crs: ProjectCrs): String {
        return "$PREFIX $KEY ${expectedSignatureForProject(crs)}\n"
    }

    /**
     * Returns the CRS signature found in the CSV metadata line, or null if not present.
     * Only inspects the first ~10 non-empty lines (cheap).
     */
    fun readSignature(contentResolver: ContentResolver, uri: Uri): String? {
        return runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                var inspected = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    inspected++
                    if (trimmed.startsWith(PREFIX)) {
                        val t = trimmed.removePrefix(PREFIX).trim()
                        if (t.uppercase().startsWith(KEY)) {
                            return@use t.substring(KEY.length).trim().uppercase()
                        }
                        // other comment -> continue
                    } else {
                        // first non-comment line reached
                        break
                    }
                    if (inspected >= 10) break
                }
            }
            null
        }.getOrNull()
    }

    /**
     * Compatibility for opening a levé file.
     *
     * Storage is ALWAYS INCT in the first columns; the metadata line only drives UI display.
     * So we accept any of the 3 supported tags, and also accept legacy signatures.
     */
    fun isCompatible(@Suppress("UNUSED_PARAMETER") expected: ProjectCrs, foundSignature: String?): Boolean {
        val found = foundSignature?.trim()?.uppercase()
        if (found.isNullOrEmpty()) return true // legacy

        val allowed = setOf(
            // new display tags
            "INCT_UTM",
            "WGS84_UTM",
            "WGS84_GEOG",
            // legacy / older signatures
            "INCT",
            "WGS84_UTM_AUTO",
            "EPSG:4326"
        )

        return found in allowed || found.startsWith("EPSG:326") || found.startsWith("EPSG:327")
    }
}
