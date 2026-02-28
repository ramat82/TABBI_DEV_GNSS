package dz.ogefgef322.gnss
data class TopoPoint(
    val id: String,
    val x: Double,
    val y: Double,
    val type: TopoPointType? = null
)

enum class TopoPointType(val label: String) {
    ARBRE("Arbre"),
    POTEAU("Poteau"),
    REGARD("Regard"),
    BORNE("Borne"),
    AVALOIR("Avaloir");

    companion object {
        fun fromLabel(value: String?): TopoPointType? {
            if (value.isNullOrBlank()) return null
            return when (value.trim().uppercase(java.util.Locale.ROOT)) {
                "ARBRE" -> ARBRE
                "POTEAU" -> POTEAU
                "AVALOIR" -> AVALOIR
                "REGARD" -> REGARD
                "BORNE" -> BORNE
                else -> null
            }
        }
    }
}

data class PointRef(
    val id: String,
    val x: Double,
    val y: Double
)

data class PolylineFeature(
    val id: String,
    val classType: String,
    val points: List<PointRef>,
    val isClosed: Boolean,
    val isSurfaceFill: Boolean
)

enum class TopoPlanMode {
    VIEW,
    DRAW_POLYLINE
}
