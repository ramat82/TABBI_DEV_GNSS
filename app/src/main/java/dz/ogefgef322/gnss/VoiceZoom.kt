package dz.ogefgef322.gnss

import kotlin.math.*

const val VOICE_ZOOM_MIN_METERS = 5.0
const val VOICE_ZOOM_MAX_METERS = 200.0
const val VOICE_ZOOM_STEP_METERS = 10.0
const val VOICE_ZOOM_DEFAULT_METERS = 20.0
const val VOICE_ZOOM_REFERENCE_LEVEL = 20.0

fun voiceZoomMetersToOsmdroidZoom(meters: Double): Double {
    val clamped = meters.coerceIn(VOICE_ZOOM_MIN_METERS, VOICE_ZOOM_MAX_METERS)
    val mapping = listOf(
        5.0 to 21.0,
        10.0 to 20.5,
        20.0 to 20.0,
        30.0 to 19.5,
        50.0 to 19.0,
        80.0 to 18.5,
        120.0 to 18.0,
        200.0 to 17.5
    )
    if (clamped <= mapping.first().first) return mapping.first().second
    if (clamped >= mapping.last().first) return mapping.last().second
    val idx = mapping.indexOfLast { it.first <= clamped }.coerceAtLeast(0)
    val (m1, z1) = mapping[idx]
    val (m2, z2) = mapping[idx + 1]
    val t = (clamped - m1) / (m2 - m1)
    return z1 + (z2 - z1) * t
}
