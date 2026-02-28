package dz.ogefgef322.gnss
import org.locationtech.proj4j.BasicCoordinateTransform
import org.locationtech.proj4j.CRSFactory
import org.locationtech.proj4j.ProjCoordinate

/**
 * Small helper around Proj4J to convert WGS84 (EPSG:4326) lat/lon to a projected CRS (EPSG).
 *
 * Notes:
 * - Proj4J expects lon/lat order for geographic coordinates.
 * - Some CRSs (e.g. EPSG:22300) use kilometres; we convert to meters via [ProjectCrs.unitToMeterScale].
 */
object CoordinateTransformer {

    private val factory: CRSFactory by lazy { CRSFactory() }
    private val wgs84 by lazy { factory.createFromName("EPSG:4326") }

    // Cache transforms by EPSG code
    private val transformCache = HashMap<Int, BasicCoordinateTransform>()

    private fun getTransformWgsTo(dstEpsg: Int): BasicCoordinateTransform {
        return transformCache.getOrPut(dstEpsg) {
            val dst = factory.createFromName("EPSG:$dstEpsg")
            BasicCoordinateTransform(wgs84, dst)
        }
    }

    private val inverseTransformCache = HashMap<Int, BasicCoordinateTransform>()
    private fun getTransformToWgs(srcEpsg: Int): BasicCoordinateTransform {
        return inverseTransformCache.getOrPut(srcEpsg) {
            val src = factory.createFromName("EPSG:$srcEpsg")
            BasicCoordinateTransform(src, wgs84)
        }
    }

    /** Returns Pair(easting, northing) in meters (after applying unit scale if needed). */
    fun wgs84ToEastingNorthingMeters(crs: ProjectCrs, lat: Double, lon: Double): Pair<Double, Double> {
        val epsg = crs.epsg ?: error("CRS ${crs.id} has no EPSG")
        val t = getTransformWgsTo(epsg)
        val src = ProjCoordinate(lon, lat)
        val dst = ProjCoordinate()
        t.transform(src, dst)
        val scale = crs.unitToMeterScale
        return Pair(dst.x * scale, dst.y * scale)
    }

    /** Returns Pair(lat, lon) in degrees, from projected coordinates in meters (will divide by unit scale if needed). */
    fun eastingNorthingMetersToWgs84(crs: ProjectCrs, eastingMeters: Double, northingMeters: Double): Pair<Double, Double> {
        val epsg = crs.epsg ?: error("CRS ${crs.id} has no EPSG")
        val t = getTransformToWgs(epsg)
        val scale = crs.unitToMeterScale
        val src = ProjCoordinate(eastingMeters / scale, northingMeters / scale)
        val dst = ProjCoordinate()
        t.transform(src, dst)
        // dst = (lon, lat)
        return Pair(dst.y, dst.x)
    }
}
