package dz.ogefgef322.gnss

import kotlin.math.*

fun wgs84ToUtmInct(lat: Double, lon: Double, alt: Double): TransformResult {
    // --- WGS84 ---
    val aW = 6378137.0
    val fW = 1.0 / 298.257223563
    val e2W = 2.0 * fW - fW * fW

    val latRad = Math.toRadians(lat)
    val lonRad = Math.toRadians(lon)

    val Nw = aW / sqrt(1.0 - e2W * sin(latRad).pow(2.0))
    val Xw = (Nw + alt) * cos(latRad) * cos(lonRad)
    val Yw = (Nw + alt) * cos(latRad) * sin(lonRad)
    val Zw = (Nw * (1.0 - e2W) + alt) * sin(latRad)

    // --- Bursa-Wolf ---
    val dX = 267.407
    val dY = 47.068
    val dZ = -446.357

    val rX = Math.toRadians(0.179423 / 3600.0)
    val rY = Math.toRadians(-5.577661 / 3600.0)
    val rZ = Math.toRadians(1.277620 / 3600.0)

    val scale = 1.0 + (-1.204866 * 1e-6)

    val Xi = dX + scale * (Xw - rZ * Yw + rY * Zw)
    val Yi = dY + scale * (rZ * Xw + Yw - rX * Zw)
    val Zi = dZ + scale * (-rY * Xw + rX * Yw + Zw)

    // --- Clarke 1880 ---
    val a = 6378249.145
    val b = 6356514.869
    val e2 = 1.0 - (b * b) / (a * a)

    val p = sqrt(Xi * Xi + Yi * Yi)
    var latI = atan2(Zi, p * (1.0 - e2))
    var latPrev: Double

    do {
        latPrev = latI
        val Ni = a / sqrt(1.0 - e2 * sin(latI).pow(2.0))
        latI = atan2(Zi + e2 * Ni * sin(latI), p)
    } while (abs(latI - latPrev) > 1e-12)

    val lonI = atan2(Yi, Xi)
    val hi = p / cos(latI) - a / sqrt(1.0 - e2 * sin(latI).pow(2.0))

    val lonDeg = Math.toDegrees(lonI)
    val zone = floor((lonDeg + 180.0) / 6.0).toInt() + 1

    // --- UTM ---
    val k0 = 0.9996
    val lon0Rad = Math.toRadians(((zone - 1) * 6 - 180 + 3).toDouble())

    val N = a / sqrt(1.0 - e2 * sin(latI).pow(2.0))
    val T = tan(latI).pow(2.0)
    val C = (e2 / (1.0 - e2)) * cos(latI).pow(2.0)
    val A = (lonI - lon0Rad) * cos(latI)

    val M = a * (
            (1.0 - e2 / 4.0 - 3.0 * e2 * e2 / 64.0 - 5.0 * e2 * e2 * e2 / 256.0) * latI
                    - (3.0 * e2 / 8.0 + 3.0 * e2 * e2 / 32.0 + 45.0 * e2 * e2 * e2 / 1024.0) * sin(2.0 * latI)
                    + (15.0 * e2 * e2 / 256.0 + 45.0 * e2 * e2 * e2 / 1024.0) * sin(4.0 * latI)
                    - (35.0 * e2 * e2 * e2 / 3072.0) * sin(6.0 * latI)
            )

    val xUTM = k0 * N * (
            A + (1.0 - T + C) * A.pow(3.0) / 6.0 +
                    (5.0 - 18.0 * T + T * T + 72.0 * C - 58.0 * (e2 / (1.0 - e2))) * A.pow(5.0) / 120.0
            ) + 500000.0

    val yTerm = k0 * N * tan(latI) * (
            A.pow(2.0) / 2.0 +
                    (5.0 - T + 9.0 * C + 4.0 * C * C) * A.pow(4.0) / 24.0 +
                    (61.0 - 58.0 * T + T * T + 600.0 * C - 330.0 * (e2 / (1.0 - e2))) * A.pow(6.0) / 720.0
            )

    val yUTM = if (lat >= 0) (k0 * M + yTerm) else (k0 * M + yTerm + 10000000.0)

    return TransformResult(xUTM, yUTM, hi, zone)
}

/**
 * Inverse of [wgs84ToUtmInct].
 *
 * Input: INCT UTM (Clarke 1880 after Bursa-Wolf) easting/northing meters + UTM zone + altitude.
 * Output: WGS84 lat/lon degrees + altitude meters.
 *
 * Uses the **same Bursa-Wolf parameters** as the forward transform.
 */
fun inctUtmToWgs84(easting: Double, northing: Double, zone: Int, alt: Double): Wgs84Result {
    // --- Clarke 1880 ---
    val a = 6378249.145
    val b = 6356514.869
    val e2 = 1.0 - (b * b) / (a * a)
    val ep2 = e2 / (1.0 - e2)

    // --- Inverse UTM (north hemisphere only for Algeria) ---
    val k0 = 0.9996
    val x = easting - 500000.0
    val y = northing
    val lon0 = Math.toRadians(((zone - 1) * 6 - 180 + 3).toDouble())

    val M = y / k0
    val mu = M / (a * (1.0 - e2 / 4.0 - 3.0 * e2 * e2 / 64.0 - 5.0 * e2 * e2 * e2 / 256.0))

    val e1 = (1.0 - sqrt(1.0 - e2)) / (1.0 + sqrt(1.0 - e2))
    val j1 = 3.0 * e1 / 2.0 - 27.0 * e1.pow(3.0) / 32.0
    val j2 = 21.0 * e1.pow(2.0) / 16.0 - 55.0 * e1.pow(4.0) / 32.0
    val j3 = 151.0 * e1.pow(3.0) / 96.0
    val j4 = 1097.0 * e1.pow(4.0) / 512.0
    val fp = mu + j1 * sin(2.0 * mu) + j2 * sin(4.0 * mu) + j3 * sin(6.0 * mu) + j4 * sin(8.0 * mu)

    val sinfp = sin(fp)
    val cosfp = cos(fp)
    val tanfp = tan(fp)

    val C1 = ep2 * cosfp.pow(2.0)
    val T1 = tanfp.pow(2.0)
    val N1 = a / sqrt(1.0 - e2 * sinfp.pow(2.0))
    val R1 = a * (1.0 - e2) / (1.0 - e2 * sinfp.pow(2.0)).pow(1.5)
    val D = x / (N1 * k0)

    val latI = fp - (N1 * tanfp / R1) * (
        D.pow(2.0) / 2.0 -
            (5.0 + 3.0 * T1 + 10.0 * C1 - 4.0 * C1.pow(2.0) - 9.0 * ep2) * D.pow(4.0) / 24.0 +
            (61.0 + 90.0 * T1 + 298.0 * C1 + 45.0 * T1.pow(2.0) - 252.0 * ep2 - 3.0 * C1.pow(2.0)) * D.pow(6.0) / 720.0
    )

    val lonI = lon0 + (
        D - (1.0 + 2.0 * T1 + C1) * D.pow(3.0) / 6.0 +
            (5.0 - 2.0 * C1 + 28.0 * T1 - 3.0 * C1.pow(2.0) + 8.0 * ep2 + 24.0 * T1.pow(2.0)) * D.pow(5.0) / 120.0
    ) / cosfp

    // --- Clarke 1880 geodetic -> ECEF (Xi,Yi,Zi) ---
    val Ni = a / sqrt(1.0 - e2 * sin(latI).pow(2.0))
    val Xi = (Ni + alt) * cos(latI) * cos(lonI)
    val Yi = (Ni + alt) * cos(latI) * sin(lonI)
    val Zi = (Ni * (1.0 - e2) + alt) * sin(latI)

    // --- Inverse Bursa-Wolf (same params as forward) ---
    val dX = 267.407
    val dY = 47.068
    val dZ = -446.357

    val rX = Math.toRadians(0.179423 / 3600.0)
    val rY = Math.toRadians(-5.577661 / 3600.0)
    val rZ = Math.toRadians(1.277620 / 3600.0)

    val scale = 1.0 + (-1.204866 * 1e-6)

    val Vx = (Xi - dX) / scale
    val Vy = (Yi - dY) / scale
    val Vz = (Zi - dZ) / scale

    // A^{-1} approx (transpose) for small rotations
    val Xw = Vx + rZ * Vy - rY * Vz
    val Yw = -rZ * Vx + Vy + rX * Vz
    val Zw = rY * Vx - rX * Vy + Vz

    // --- WGS84 ECEF -> geodetic ---
    val aW = 6378137.0
    val fW = 1.0 / 298.257223563
    val e2W = 2.0 * fW - fW * fW

    val p = sqrt(Xw * Xw + Yw * Yw)
    var latW = atan2(Zw, p * (1.0 - e2W))
    var latPrev: Double
    do {
        latPrev = latW
        val Nw = aW / sqrt(1.0 - e2W * sin(latW).pow(2.0))
        latW = atan2(Zw + e2W * Nw * sin(latW), p)
    } while (abs(latW - latPrev) > 1e-12)

    val lonW = atan2(Yw, Xw)
    val Nw = aW / sqrt(1.0 - e2W * sin(latW).pow(2.0))
    val hW = p / cos(latW) - Nw

    return Wgs84Result(
        lat = Math.toDegrees(latW),
        lon = Math.toDegrees(lonW),
        alt = hW
    )
}

fun wgs84ToUtm(lat: Double, lon: Double): UtmCoordinate {
    val a = 6378137.0
    val f = 1.0 / 298.257223563
    val e2 = 2.0 * f - f * f
    val ePrime2 = e2 / (1.0 - e2)

    val latRad = Math.toRadians(lat)
    val lonRad = Math.toRadians(lon)
    val zone = floor((lon + 180.0) / 6.0).toInt() + 1
    val lon0Rad = Math.toRadians(((zone - 1) * 6 - 180 + 3).toDouble())

    val N = a / sqrt(1.0 - e2 * sin(latRad).pow(2.0))
    val T = tan(latRad).pow(2.0)
    val C = ePrime2 * cos(latRad).pow(2.0)
    val A = cos(latRad) * (lonRad - lon0Rad)

    val M = a * (
            (1.0 - e2 / 4.0 - 3.0 * e2 * e2 / 64.0 - 5.0 * e2 * e2 * e2 / 256.0) * latRad
                    - (3.0 * e2 / 8.0 + 3.0 * e2 * e2 / 32.0 + 45.0 * e2 * e2 * e2 / 1024.0) * sin(2.0 * latRad)
                    + (15.0 * e2 * e2 / 256.0 + 45.0 * e2 * e2 * e2 / 1024.0) * sin(4.0 * latRad)
                    - (35.0 * e2 * e2 * e2 / 3072.0) * sin(6.0 * latRad)
            )

    val k0 = 0.9996
    val easting = k0 * N * (
            A + (1.0 - T + C) * A.pow(3.0) / 6.0 +
                    (5.0 - 18.0 * T + T * T + 72.0 * C - 58.0 * ePrime2) * A.pow(5.0) / 120.0
            ) + 500000.0

    var northing = k0 * (
            M + N * tan(latRad) * (
                    A.pow(2.0) / 2.0 +
                            (5.0 - T + 9.0 * C + 4.0 * C * C) * A.pow(4.0) / 24.0 +
                            (61.0 - 58.0 * T + T * T + 600.0 * C - 330.0 * ePrime2) * A.pow(6.0) / 720.0
                    )
            )
    if (lat < 0) {
        northing += 10000000.0
    }

    return UtmCoordinate(easting, northing)
}
