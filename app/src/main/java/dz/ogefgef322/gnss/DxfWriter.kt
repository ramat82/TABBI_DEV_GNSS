package dz.ogefgef322.gnss
import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * DXF R12 (compat Covadis/AutoCAD anciens) + BLOCKS/INSERT.
 *
 * - Points : INSERT de blocs (ARBRE/POTEAU basés sur tes LISP) + TEXTE ID à droite
 * - Polylignes : POLYLINE R12 + TEXTE "nature" (ex LIMITE/CLOTURE) :
 *      * aligné sur le segment (rotation)
 *      * décalé perpendiculairement (offset 2 m)
 *      * taille auto selon longueur (sqrt(L)) bornée min/max
 *      * répété tous les N mètres (30 m) avec marge début/fin
 *
 * Calques :
 *   Points   : PTS_<TYPE>
 *   Textes   : TXT_<TYPE>
 *   Lignes   : LINE_<TYPE>
 *   Polygones: POLY_<TYPE>
 *   Trous    : HOLE_<TYPE>
 */
object DxfWriter {

    private const val DEFAULT_POINT_TYPE = "LEVE"
    private const val DEFAULT_LINE_TYPE = "LINE"

    // Texte terrain (points)
    private const val TEXT_HEIGHT = 0.25
    private const val TEXT_OFFSET_X = 0.30
    private const val TEXT_OFFSET_Y = 0.00

    // Texte polylignes (nature) : offset perpendiculaire
    private const val TEXT_OFFSET_LINE = 2.0

    // Taille auto selon longueur (robuste si longueurs variées)
    private const val TEXT_HEIGHT_MIN = 0.20
    private const val TEXT_HEIGHT_MAX = 1.20
    private const val TEXT_HEIGHT_A = 0.12 // h = A * sqrt(L)

    // Répétition sur polylignes
    private const val TEXT_REPEAT_STEP_M = 30.0
    private const val TEXT_REPEAT_MARGIN_M = 5.0

    // Blocs (charte figée)
    private const val BLK_ARBRE_ELEV = "BLK_ARBRE_ELEV"
    private const val BLK_POTEAU_ELEV = "BLK_POTEAU_ELEV"
    private const val BLK_REGARD_ELEV = "BLK_REGARD_ELEV"
    private const val BLK_BORNE_ELEV = "BLK_BORNE_ELEV"
    private const val BLK_AVALOIR_ELEV = "BLK_AVALOIR_ELEV"
    private const val BLK_POINT = "BLK_POINT"
    private const val BLK_TOPO_ALT = "PT_TOPO_ALT"

    private const val TOPO_LAYER_POINT = "TOPO_POINT"
    private const val TOPO_LAYER_ID = "TOPO_ID"
    private const val TOPO_LAYER_ALT = "TOPO_ALT"

    private const val TOPO_TEXT_HEIGHT = 0.50
    private const val TOPO_ID_OFFSET_Y = 0.70
    private const val TOPO_ALT_OFFSET_Y = -0.70

    fun writeDxf(
        contentResolver: ContentResolver,
        chantierDir: DocumentFile,
        fileName: String,
        points: List<PointXY>,
        polylines: List<StoredPolyline>
    ): DocumentFile? {

        chantierDir.findFile(fileName)?.delete()

        val file = chantierDir.createFile("application/octet-stream", fileName) ?: return null
        val sb = StringBuilder(160_000)

        // 1) Calques réellement utilisés
        val layers = linkedSetOf<String>()

        if (points.isNotEmpty()) {
            layers += sanitizeLayer(TOPO_LAYER_POINT)
            layers += sanitizeLayer(TOPO_LAYER_ID)
            layers += sanitizeLayer(TOPO_LAYER_ALT)
        }

        polylines.forEach { pl ->
            val t = normType(pl.type.ifBlank { DEFAULT_LINE_TYPE })
            val closed = isClosed(pl)
            layers += if (closed) layerPoly(t) else layerLine(t)
            layers += layerTxt(t)
            pl.holes.orEmpty().forEach { _ ->
                layers += layerHole(t)
            }
        }

        appendHeaderR12(sb)
        appendTablesR12(sb, layers.toList())

        // 2) BLOCS (symboles)
        appendBlocksR12(sb)

        // 3) ENTITIES
        sb.append("0\nSECTION\n2\nENTITIES\n")

        // ---- Points : INSERT + ID ----
        points.forEach { p ->
            appendInsert(
                sb = sb,
                blockName = BLK_TOPO_ALT,
                x = p.x,
                y = p.y,
                z = p.z,
                layer = TOPO_LAYER_POINT,
                scale = 1.0,
                rotationDeg = 0.0,
                hasAttributes = true
            )

            appendAttrib(
                sb = sb,
                x = p.x,
                y = p.y + TOPO_ID_OFFSET_Y,
                z = p.z,
                tag = "ID",
                value = p.id,
                layer = TOPO_LAYER_ID,
                rotationDeg = 0.0,
                center = true,
                height = TOPO_TEXT_HEIGHT
            )

            appendAttrib(
                sb = sb,
                x = p.x,
                y = p.y + TOPO_ALT_OFFSET_Y,
                z = p.z,
                tag = "ALT",
                value = formatAltitude(p.z),
                layer = TOPO_LAYER_ALT,
                rotationDeg = 0.0,
                center = true,
                height = TOPO_TEXT_HEIGHT
            )

            appendSeqEnd(sb, TOPO_LAYER_POINT)
        }

        // ---- Polylignes : tracé + texte nature aligné/offset/taille auto/répété ----
        polylines.forEach { pl ->
            val t = normType(pl.type.ifBlank { DEFAULT_LINE_TYPE })
            val closed = isClosed(pl)
            val layer = if (closed) layerPoly(t) else layerLine(t)

            appendPolylineR12(sb, pl.points, layer, closed)

            // Texte "nature" répété le long de la polyligne
            val len = polylineLength(pl.points)
            if (len > 0.0) {
                val h = textHeightForLength(len)
                val stations = stationsForRepeatedText(len)
                for (s in stations) {
                    val st = stationPointOnPolyline(pl.points, s) ?: continue
                    val (tx, ty) = offsetPointPerpendicular(
                        x = st.x,
                        y = st.y,
                        angleDeg = st.angleDeg,
                        offset = TEXT_OFFSET_LINE
                    )

                    appendText(
                        sb = sb,
                        x = tx,
                        y = ty,
                        text = t,
                        layer = layerTxt(t),
                        rotationDeg = st.angleDeg,
                        center = true,
                        height = h
                    )
                }
            }

            // Trous
            pl.holes.orEmpty().forEach { hole ->
                appendPolylineR12(sb, hole, layerHole(t), closed = true)
            }
        }

        // 4) Fin
        sb.append("0\nENDSEC\n0\nEOF\n")

        return runCatching {
            contentResolver.openOutputStream(file.uri, "wt")?.bufferedWriter()?.use { w ->
                w.write(sb.toString())
            }
            file
        }.getOrNull()
    }

    // ---------------- DXF (R12) ----------------

    private fun appendHeaderR12(sb: StringBuilder) {
        sb.append("0\nSECTION\n2\nHEADER\n")
        sb.append("9\n\$ACADVER\n1\nAC1009\n") // R12
        sb.append("9\n\$PDMODE\n70\n34\n")
        sb.append("9\n\$PDSIZE\n40\n0.35\n")
        sb.append("0\nENDSEC\n")
    }

    private fun appendTablesR12(sb: StringBuilder, layers: List<String>) {
        sb.append("0\nSECTION\n2\nTABLES\n")

        // LTYPE minimal (CONTINUOUS)
        sb.append("0\nTABLE\n2\nLTYPE\n70\n1\n")
        sb.append("0\nLTYPE\n2\nCONTINUOUS\n70\n0\n3\nSolid line\n72\n65\n73\n0\n40\n0.0\n")
        sb.append("0\nENDTAB\n")

        // LAYER table
        sb.append("0\nTABLE\n2\nLAYER\n")
        sb.append("70\n").append(layers.size).append("\n")
        layers.forEach { layer ->
            sb.append("0\nLAYER\n")
            sb.append("2\n").append(layer).append("\n")
            sb.append("70\n0\n")
            sb.append("62\n7\n")
            sb.append("6\nCONTINUOUS\n")
        }
        sb.append("0\nENDTAB\n")

        sb.append("0\nENDSEC\n")
    }

    /**
     * BLOCKS R12 :
     * - BLK_POTEAU_ELEV : basé sur ton LISP POTEAULAMPE
     * - BLK_ARBRE_ELEV  : basé sur ton LISP ARBRE
     * + autres blocs simples (regard/borne/avaloir) + fallback
     */
    private fun appendBlocksR12(sb: StringBuilder) {
        sb.append("0\nSECTION\n2\nBLOCKS\n")

        // Fallback simple
        appendBlockStart(sb, BLK_POINT)
        appendLine(sb, "0", -0.08, 0.00, 0.08, 0.00)
        appendLine(sb, "0", 0.00, 0.00, 0.00, 0.16)
        appendBlockEnd(sb)

        // ---------------- POTEAU (LISP POTEAULAMPE) ----------------
        appendBlockStart(sb, BLK_POTEAU_ELEV)

        val hPoteau = 8.0
        val hPorte = 1.5
        val baseHalf = 0.15
        val topHalf = 0.10
        val lampR = 0.20      // diam 0.4
        val glowR = 0.05

        appendPolylineClosed(sb, "0", listOf(
            Pair(-baseHalf, 0.0),
            Pair(baseHalf, 0.0),
            Pair(topHalf, hPoteau),
            Pair(-topHalf, hPoteau),
            Pair(-baseHalf, 0.0)
        ))

        appendLine(sb, "0", 0.0, hPoteau, hPorte, hPoteau)
        appendCircle(sb, "0", hPorte, hPoteau, lampR)
        appendCircle(sb, "0", hPorte, hPoteau, glowR)

        appendBlockEnd(sb)

        // ---------------- ARBRE (LISP ARBRE) ----------------
        appendBlockStart(sb, BLK_ARBRE_ELEV)

        val hArbre = 4.0
        val hTronc = 1.5
        val largTronc = 0.4
        val baseHalfT = largTronc / 2.0          // 0.2
        val topHalfT = largTronc * 0.6           // 0.24
        val hFrond = hArbre - hTronc             // 2.5

        // tronc (rectangle)
        appendPolylineClosed(sb, "0", listOf(
            Pair(-baseHalfT, 0.0),
            Pair(baseHalfT, 0.0),
            Pair(topHalfT, hTronc),
            Pair(-topHalfT, hTronc),
            Pair(-baseHalfT, 0.0)
        ))

        // texture tronc (2 lignes)
        val xLeft = -largTronc * 0.15            // -0.06
        val xRight = largTronc * 0.15            // +0.06
        appendLine(sb, "0", xLeft, hTronc * 0.3, xLeft, hTronc * 0.8)
        appendLine(sb, "0", xRight, hTronc * 0.4, xRight, hTronc * 0.9)

        // frondaison (3 cercles)
        appendCircle(sb, "0", 0.0, hTronc + (hFrond * 0.3), largTronc * 1.8) // r=0.72
        appendCircle(sb, "0", 0.0, hTronc + (hFrond * 0.6), largTronc * 1.4) // r=0.56
        appendCircle(sb, "0", 0.0, hTronc + (hFrond * 0.9), largTronc * 1.0) // r=0.40

        // point au sommet
        appendCircle(sb, "0", 0.0, hArbre, 0.1)

        appendBlockEnd(sb)

        // REGARD
        appendBlockStart(sb, BLK_REGARD_ELEV)
        appendClosedRectPolyline(sb, "0", left = -0.15, bottom = 0.00, right = 0.15, top = 0.30)
        appendLine(sb, "0", -0.15, 0.00, 0.15, 0.30)
        appendLine(sb, "0", -0.15, 0.30, 0.15, 0.00)
        appendBlockEnd(sb)

        // BORNE
        appendBlockStart(sb, BLK_BORNE_ELEV)
        appendClosedRectPolyline(sb, "0", left = -0.10, bottom = 0.00, right = 0.10, top = 0.40)
        appendLine(sb, "0", -0.14, 0.00, 0.14, 0.00)
        appendBlockEnd(sb)

        // AVALOIR
        appendBlockStart(sb, BLK_AVALOIR_ELEV)
        val g = listOf(
            Pair(-0.30, 0.00),
            Pair(0.22, 0.10),
            Pair(0.30, 0.36),
            Pair(-0.22, 0.26),
            Pair(-0.30, 0.00)
        )
        appendPolylineClosed(sb, "0", g)
        appendLine(sb, "0", -0.22, 0.08, 0.20, 0.16)
        appendLine(sb, "0", -0.23, 0.16, 0.19, 0.24)
        appendLine(sb, "0", -0.24, 0.24, 0.18, 0.32)
        appendBlockEnd(sb)

        // ---------------- PT TOPO ALT ----------------
        appendBlockStart(sb, BLK_TOPO_ALT)
        appendPoint(sb, TOPO_LAYER_POINT, 0.0, 0.0, 0.0)
        appendAttDef(
            sb = sb,
            x = 0.0,
            y = TOPO_ID_OFFSET_Y,
            z = 0.0,
            tag = "ID",
            defaultValue = "",
            layer = TOPO_LAYER_ID,
            rotationDeg = 0.0,
            center = true,
            height = TOPO_TEXT_HEIGHT
        )
        appendAttDef(
            sb = sb,
            x = 0.0,
            y = TOPO_ALT_OFFSET_Y,
            z = 0.0,
            tag = "ALT",
            defaultValue = "",
            layer = TOPO_LAYER_ALT,
            rotationDeg = 0.0,
            center = true,
            height = TOPO_TEXT_HEIGHT
        )
        appendBlockEnd(sb)

        sb.append("0\nENDSEC\n")
    }

    private fun appendBlockStart(sb: StringBuilder, name: String) {
        sb.append("0\nBLOCK\n")
        sb.append("2\n").append(name).append("\n")
        sb.append("70\n0\n")
        sb.append("10\n0.0\n20\n0.0\n30\n0.0\n")
        sb.append("3\n").append(name).append("\n")
        sb.append("1\n").append(name).append("\n")
    }

    private fun appendBlockEnd(sb: StringBuilder) {
        sb.append("0\nENDBLK\n")
    }

    private fun appendInsert(
        sb: StringBuilder,
        blockName: String,
        x: Double,
        y: Double,
        z: Double,
        layer: String,
        scale: Double,
        rotationDeg: Double,
        hasAttributes: Boolean = false
    ) {
        sb.append("0\nINSERT\n")
        sb.append("8\n").append(layer).append("\n")
        sb.append("2\n").append(blockName).append("\n")
        sb.append("10\n").append(fmt(x)).append("\n")
        sb.append("20\n").append(fmt(y)).append("\n")
        sb.append("30\n").append(fmt(z)).append("\n")
        sb.append("41\n").append(fmt(scale)).append("\n")
        sb.append("42\n").append(fmt(scale)).append("\n")
        sb.append("43\n").append(fmt(scale)).append("\n")
        sb.append("50\n").append(fmt(rotationDeg)).append("\n")
        if (hasAttributes) {
            sb.append("66\n1\n")
        }
    }

    private fun appendSeqEnd(sb: StringBuilder, layer: String) {
        sb.append("0\nSEQEND\n")
        sb.append("8\n").append(layer).append("\n")
    }

    private fun appendCircle(sb: StringBuilder, layer: String, cx: Double, cy: Double, r: Double) {
        sb.append("0\nCIRCLE\n")
        sb.append("8\n").append(layer).append("\n")
        sb.append("10\n").append(fmt(cx)).append("\n")
        sb.append("20\n").append(fmt(cy)).append("\n")
        sb.append("30\n0.0\n")
        sb.append("40\n").append(fmt(r)).append("\n")
    }

    private fun appendLine(sb: StringBuilder, layer: String, x1: Double, y1: Double, x2: Double, y2: Double) {
        sb.append("0\nLINE\n")
        sb.append("8\n").append(layer).append("\n")
        sb.append("10\n").append(fmt(x1)).append("\n")
        sb.append("20\n").append(fmt(y1)).append("\n")
        sb.append("30\n0.0\n")
        sb.append("11\n").append(fmt(x2)).append("\n")
        sb.append("21\n").append(fmt(y2)).append("\n")
        sb.append("31\n0.0\n")
    }

    private fun appendPoint(sb: StringBuilder, layer: String, x: Double, y: Double, z: Double) {
        sb.append("0\nPOINT\n")
        sb.append("8\n").append(layer).append("\n")
        sb.append("10\n").append(fmt(x)).append("\n")
        sb.append("20\n").append(fmt(y)).append("\n")
        sb.append("30\n").append(fmt(z)).append("\n")
    }

    private fun appendText(
        sb: StringBuilder,
        x: Double,
        y: Double,
        text: String,
        layer: String,
        rotationDeg: Double = 0.0,
        center: Boolean = false,
        height: Double = TEXT_HEIGHT
    ) {
        sb.append("0\nTEXT\n")
        sb.append("8\n").append(layer).append("\n")

        sb.append("10\n").append(fmt(x)).append("\n")
        sb.append("20\n").append(fmt(y)).append("\n")
        sb.append("30\n0.0\n")

        sb.append("40\n").append(fmt(height)).append("\n")
        sb.append("50\n").append(fmt(rotationDeg)).append("\n")

        if (center) {
            sb.append("72\n1\n") // center
            sb.append("73\n2\n") // middle
            sb.append("11\n").append(fmt(x)).append("\n")
            sb.append("21\n").append(fmt(y)).append("\n")
            sb.append("31\n0.0\n")
        }

        sb.append("1\n").append(text).append("\n")
    }

    private fun appendAttDef(
        sb: StringBuilder,
        x: Double,
        y: Double,
        z: Double,
        tag: String,
        defaultValue: String,
        layer: String,
        rotationDeg: Double = 0.0,
        center: Boolean = false,
        height: Double = TEXT_HEIGHT
    ) {
        sb.append("0\nATTDEF\n")
        sb.append("8\n").append(layer).append("\n")
        sb.append("10\n").append(fmt(x)).append("\n")
        sb.append("20\n").append(fmt(y)).append("\n")
        sb.append("30\n").append(fmt(z)).append("\n")
        sb.append("40\n").append(fmt(height)).append("\n")
        sb.append("50\n").append(fmt(rotationDeg)).append("\n")
        if (center) {
            sb.append("72\n1\n")
            sb.append("74\n2\n")
            sb.append("11\n").append(fmt(x)).append("\n")
            sb.append("21\n").append(fmt(y)).append("\n")
            sb.append("31\n").append(fmt(z)).append("\n")
        }
        sb.append("1\n").append(defaultValue).append("\n")
        sb.append("2\n").append(tag).append("\n")
        sb.append("3\n").append(tag).append("\n")
        sb.append("70\n0\n")
    }

    private fun appendAttrib(
        sb: StringBuilder,
        x: Double,
        y: Double,
        z: Double,
        tag: String,
        value: String,
        layer: String,
        rotationDeg: Double = 0.0,
        center: Boolean = false,
        height: Double = TEXT_HEIGHT
    ) {
        sb.append("0\nATTRIB\n")
        sb.append("8\n").append(layer).append("\n")
        sb.append("10\n").append(fmt(x)).append("\n")
        sb.append("20\n").append(fmt(y)).append("\n")
        sb.append("30\n").append(fmt(z)).append("\n")
        sb.append("40\n").append(fmt(height)).append("\n")
        sb.append("50\n").append(fmt(rotationDeg)).append("\n")
        if (center) {
            sb.append("72\n1\n")
            sb.append("74\n2\n")
            sb.append("11\n").append(fmt(x)).append("\n")
            sb.append("21\n").append(fmt(y)).append("\n")
            sb.append("31\n").append(fmt(z)).append("\n")
        }
        sb.append("1\n").append(value).append("\n")
        sb.append("2\n").append(tag).append("\n")
        sb.append("70\n0\n")
    }

    /**
     * POLYLINE R12: POLYLINE + VERTEX... + SEQEND
     */
    private fun appendPolylineR12(
        sb: StringBuilder,
        pts: List<StoredPolylinePoint>,
        layer: String,
        closed: Boolean
    ) {
        if (pts.isEmpty()) return

        sb.append("0\nPOLYLINE\n")
        sb.append("8\n").append(layer).append("\n")
        sb.append("66\n1\n")
        sb.append("70\n").append(if (closed) 1 else 0).append("\n")
        sb.append("30\n0.0\n")

        pts.forEach { pt ->
            sb.append("0\nVERTEX\n")
            sb.append("8\n").append(layer).append("\n")
            sb.append("10\n").append(fmt(pt.x)).append("\n")
            sb.append("20\n").append(fmt(pt.y)).append("\n")
            sb.append("30\n0.0\n")
        }

        sb.append("0\nSEQEND\n")
        sb.append("8\n").append(layer).append("\n")
    }

    // --- helpers POLYLINE pour BLOCKS (coords Double) ---

    private fun appendPolylineOpen(sb: StringBuilder, layer: String, pts: List<Pair<Double, Double>>) {
        if (pts.isEmpty()) return
        sb.append("0\nPOLYLINE\n")
        sb.append("8\n").append(layer).append("\n")
        sb.append("66\n1\n")
        sb.append("70\n0\n")
        sb.append("30\n0.0\n")
        pts.forEach { (x, y) ->
            sb.append("0\nVERTEX\n")
            sb.append("8\n").append(layer).append("\n")
            sb.append("10\n").append(fmt(x)).append("\n")
            sb.append("20\n").append(fmt(y)).append("\n")
            sb.append("30\n0.0\n")
        }
        sb.append("0\nSEQEND\n")
        sb.append("8\n").append(layer).append("\n")
    }

    private fun appendPolylineClosed(sb: StringBuilder, layer: String, pts: List<Pair<Double, Double>>) {
        if (pts.isEmpty()) return
        sb.append("0\nPOLYLINE\n")
        sb.append("8\n").append(layer).append("\n")
        sb.append("66\n1\n")
        sb.append("70\n1\n")
        sb.append("30\n0.0\n")
        pts.forEach { (x, y) ->
            sb.append("0\nVERTEX\n")
            sb.append("8\n").append(layer).append("\n")
            sb.append("10\n").append(fmt(x)).append("\n")
            sb.append("20\n").append(fmt(y)).append("\n")
            sb.append("30\n0.0\n")
        }
        sb.append("0\nSEQEND\n")
        sb.append("8\n").append(layer).append("\n")
    }

    private fun appendClosedRectPolyline(
        sb: StringBuilder,
        layer: String,
        left: Double,
        bottom: Double,
        right: Double,
        top: Double
    ) {
        val rect = listOf(
            Pair(left, bottom),
            Pair(right, bottom),
            Pair(right, top),
            Pair(left, top),
            Pair(left, bottom)
        )
        appendPolylineClosed(sb, layer, rect)
    }

    // ---------------- Texte polyligne : stations / offset / taille ----------------

    private fun polylineLength(pts: List<StoredPolylinePoint>): Double {
        if (pts.size < 2) return 0.0
        var sum = 0.0
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val dx = b.x - a.x
            val dy = b.y - a.y
            sum += kotlin.math.sqrt(dx * dx + dy * dy)
        }
        return sum
    }

    private fun clamp(v: Double, lo: Double, hi: Double): Double =
        kotlin.math.max(lo, kotlin.math.min(hi, v))

    private fun textHeightForLength(len: Double): Double {
        val h = TEXT_HEIGHT_A * kotlin.math.sqrt(kotlin.math.max(0.0, len))
        return clamp(h, TEXT_HEIGHT_MIN, TEXT_HEIGHT_MAX)
    }

    private data class StationPoint(val x: Double, val y: Double, val angleDeg: Double)

    private fun stationPointOnPolyline(
        pts: List<StoredPolylinePoint>,
        s: Double
    ): StationPoint? {
        if (pts.size < 2) return null
        if (s < 0.0) return null

        var acc = 0.0
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val segLen = kotlin.math.sqrt(dx * dx + dy * dy)
            if (segLen <= 1e-9) continue

            if (acc + segLen >= s) {
                val t = (s - acc) / segLen
                val x = a.x + t * dx
                val y = a.y + t * dy
                val angDeg = kotlin.math.atan2(dy, dx) * 180.0 / Math.PI
                return StationPoint(x, y, angDeg)
            }
            acc += segLen
        }

        val lastA = pts[pts.size - 2]
        val lastB = pts.last()
        val angDeg = kotlin.math.atan2(lastB.y - lastA.y, lastB.x - lastA.x) * 180.0 / Math.PI
        return StationPoint(lastB.x, lastB.y, angDeg)
    }

    private fun stationsForRepeatedText(totalLen: Double): List<Double> {
        if (totalLen <= 0.0) return emptyList()

        val start = TEXT_REPEAT_MARGIN_M
        val end = totalLen - TEXT_REPEAT_MARGIN_M
        if (end <= start) return listOf(totalLen / 2.0)

        val step = TEXT_REPEAT_STEP_M
        val res = ArrayList<Double>()

        if (end - start < step) {
            res += (start + end) / 2.0
            return res
        }

        var s = start
        while (s <= end) {
            res += s
            s += step
        }
        return res
    }

    private fun offsetPointPerpendicular(
        x: Double,
        y: Double,
        angleDeg: Double,
        offset: Double
    ): Pair<Double, Double> {
        val ang = Math.toRadians(angleDeg + 90.0)
        val ox = x + offset * kotlin.math.cos(ang)
        val oy = y + offset * kotlin.math.sin(ang)
        return Pair(ox, oy)
    }

    // ---------------- Mapping type -> bloc ----------------

    private fun blockForType(typeUpper: String): String {
        return when (typeUpper) {
            "ARBRE", "TREE", "ARB" -> BLK_ARBRE_ELEV
            "POTEAU", "POLE", "POTEAULAMPE", "LAMPE" -> BLK_POTEAU_ELEV
            "REGARD", "MANHOLE" -> BLK_REGARD_ELEV
            "BORNE", "BND", "BENCH", "BM" -> BLK_BORNE_ELEV
            "AVALOIR", "DRAIN", "EP" -> BLK_AVALOIR_ELEV
            else -> BLK_POINT
        }
    }

    // ---------------- Helpers généraux ----------------

    private fun layerPts(t: String) = sanitizeLayer("PTS_$t")
    private fun layerTxt(t: String) = sanitizeLayer("TXT_$t")
    private fun layerLine(t: String) = sanitizeLayer("LINE_$t")
    private fun layerPoly(t: String) = sanitizeLayer("POLY_$t")
    private fun layerHole(t: String) = sanitizeLayer("HOLE_$t")

    private fun normType(raw: String): String {
        val s = raw.trim().uppercase(Locale.ROOT)
        return if (s.isBlank()) DEFAULT_POINT_TYPE else s
    }

    private fun sanitizeLayer(name: String): String {
        val cleaned = buildString(name.length) {
            name.forEach { c ->
                val ok = (c in 'A'..'Z') || (c in '0'..'9') || c == '_' || c == '-'
                append(if (ok) c else '_')
            }
        }
        val trimmed = cleaned.trim('_')
        val limited = if (trimmed.length > 31) trimmed.substring(0, 31) else trimmed
        return if (limited.isBlank()) "LAYER" else limited
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.3f", v)
    private fun formatAltitude(v: Double): String = String.format(Locale.US, "%.2f", v)

    private fun isClosed(polyline: StoredPolyline): Boolean {
        if (polyline.closed) return true
        val pts = polyline.points
        if (pts.size < 3) return false
        val a = pts.first()
        val b = pts.last()
        return (a.x == b.x && a.y == b.y)
    }

    private fun midpoint(pts: List<StoredPolylinePoint>): Pair<Double, Double>? {
        if (pts.isEmpty()) return null
        var minX = pts[0].x
        var maxX = pts[0].x
        var minY = pts[0].y
        var maxY = pts[0].y
        for (p in pts) {
            minX = min(minX, p.x); maxX = max(maxX, p.x)
            minY = min(minY, p.y); maxY = max(maxY, p.y)
        }
        return Pair((minX + maxX) / 2.0, (minY + maxY) / 2.0)
    }
}
