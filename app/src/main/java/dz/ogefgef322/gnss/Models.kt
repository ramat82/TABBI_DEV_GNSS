package dz.ogefgef322.gnss

/* =========================
   DONNÉES
   ========================= */

data class InctResult(
    val id: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val zone: Int,
    val timestamp: Long = System.currentTimeMillis()
)

data class TransformResult(val x: Double, val y: Double, val z: Double, val zone: Int)
data class UtmCoordinate(val easting: Double, val northing: Double)
data class Wgs84Result(val lat: Double, val lon: Double, val alt: Double)

/**
 * Target point used by Implantation (source: LEVE / REPERE / INTERSECT, etc.).
 *
 * - xInct/yInct/zInct: coordinates used for storage/guidance (INCT frame)
 * - lat/lon/alt: WGS84 used for map display
 * - xWgs84Utm/yWgs84Utm: optional cached UTM WGS84 columns when present in CSV
 */
data class TargetPoint(
    val id: String,
    // Always available (storage)
    val xInct: Double,
    val yInct: Double,
    val zInct: Double?,
    // Optional columns (may be empty in legacy files)
    val xWgs84Utm: Double?,
    val yWgs84Utm: Double?,
    val lat: Double,
    val lon: Double,
    val alt: Double?,
    val sourceLabel: String
)
