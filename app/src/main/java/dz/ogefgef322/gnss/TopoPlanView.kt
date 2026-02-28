package dz.ogefgef322.gnss
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.Log
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private data class BoundsXY(val minX: Double, val maxX: Double, val minY: Double, val maxY: Double)

private data class WorldToScreen(
    val scale: Double,
    val offsetX: Double,
    val offsetY: Double,
    val minX: Double,
    val maxY: Double
) {
    fun xToPx(x: Double): Float = (offsetX + (x - minX) * scale).toFloat()
    fun yToPx(y: Double): Float = (offsetY + (maxY - y) * scale).toFloat()
}

class TopoPlanView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var points: List<TopoPoint> = emptyList()
    private val features: MutableList<PolylineFeature> = mutableListOf()

    private var mode: TopoPlanMode = TopoPlanMode.VIEW
    private var selectedPoint: TopoPoint? = null
    private var currentClassType: String? = null
    private val currentPolylinePoints: MutableList<PointRef> = mutableListOf()
    private var selectedPolylineId: String? = null

    private var lastBounds: BoundsXY? = null
    private var fitToViewPending = true

    // callbacks
    var onCanvasTouched: (() -> Unit)? = null
    var onMultiPointSelection: ((List<TopoPoint>, (TopoPoint) -> Unit) -> Unit)? = null
    var onPolylineChanged: (() -> Unit)? = null
    var onPolylineSelected: ((PolylineFeature) -> Unit)? = null

    // navigation
    private var userZoom = 1f
    private var panX = 0f
    private var panY = 0f
    private val minZoom = 0.05f
    private val maxZoom = 100000f // “infini” pratique
    private var voiceFollowEnabled = false
    private var voiceZoomMeters = VOICE_ZOOM_DEFAULT_METERS
    private var voiceFollowX: Double? = null
    private var voiceFollowY: Double? = null

    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    // dessin / styles
    private val frameInset = 18f
    private val axisLabelPadding = 8f
    private val minAxisLabelSpacingPx = 70f

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(225, 225, 225)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(60, 60, 60)
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }
    private val axisHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(45, 45, 45)
        strokeWidth = 7f
        style = Paint.Style.STROKE
    }
    private val polyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(40, 120, 200)
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(55, 40, 120, 200)
        style = Paint.Style.FILL
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 160, 0)
        strokeWidth = 10f
        style = Paint.Style.STROKE
    }
    private val selectedPolylinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 80, 0)
        strokeWidth = 14f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val pointStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 120, 0)
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }
    private val pointFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val labelFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 42f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        style = Paint.Style.FILL
    }
    private val labelStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val axisValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(35, 35, 35)
        textSize = 30f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val axisValuePaintY = Paint(axisValuePaint).apply {
        textAlign = Paint.Align.LEFT
    }
    private val voiceTargetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private val selectionTolerancePx = dp(42f)
    private val polylineSelectionTolerancePx = dp(48f)
    private val longPressDelayMs = 1000L
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val symbolCache: MutableMap<TopoPointType, Bitmap> = mutableMapOf()

    // pan en mode polyline : si on démarre près d’un point => on bloque pan (priorité tap)
    private var downNearPoint = false
    private var downX = 0f
    private var downY = 0f
    private var longPressTriggered = false
    private val longPressRunnable = Runnable {
        val selected = selectPolylineAt(downX, downY)
        if (selected != null) {
            longPressTriggered = true
            onPolylineSelected?.invoke(selected)
        }
    }

    init {
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val old = userZoom
                val nz = (old * detector.scaleFactor).coerceIn(minZoom, maxZoom)
                val factor = nz / old

                val fx = detector.focusX
                val fy = detector.focusY
                panX = fx - (fx - panX) * factor
                panY = fy - (fy - panY) * factor

                userZoom = nz
                invalidate()
                return true
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                downNearPoint = isTouchNearAnyPoint(e.x, e.y)
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                // VIEW : pan toujours
                if (mode == TopoPlanMode.VIEW) {
                    panX -= dx
                    panY -= dy
                    invalidate()
                    return true
                }

                // DRAW_POLYLINE : pan si on a commencé DANS LE VIDE
                if (!downNearPoint) {
                    panX -= dx
                    panY -= dy
                    invalidate()
                    return true
                }

                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val old = userZoom
                val nz = (old * 2f).coerceIn(minZoom, maxZoom)
                val factor = nz / old

                val fx = e.x
                val fy = e.y
                panX = fx - (fx - panX) * factor
                panY = fy - (fy - panY) * factor

                userZoom = nz
                invalidate()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                return handleTap(e)
            }
        })
    }

    // Zone utile : respecte paddingBottom (pour que la toolbar ne cache pas l’axe X)
    private fun contentLeft(): Double = (paddingLeft.toFloat() + frameInset).toDouble()
    private fun contentTop(): Double = (paddingTop.toFloat() + frameInset).toDouble()
    private fun contentRight(): Double = ((width - paddingRight).toFloat() - frameInset).toDouble()
    private fun contentBottom(): Double = ((height - paddingBottom).toFloat() - frameInset).toDouble()

    fun setTopoPoints(pts: List<TopoPoint>) {
        points = pts
        fitToViewPending = true
        invalidate()
    }

    fun setPlanMode(newMode: TopoPlanMode) {
        mode = newMode
        invalidate()
    }

    fun setSelectedClassType(classType: String) {
        currentClassType = classType
    }

    fun setPolylines(polylines: List<PolylineFeature>) {
        features.clear()
        features.addAll(polylines)
        if (selectedPolylineId != null && features.none { it.id == selectedPolylineId }) {
            selectedPolylineId = null
        }
        invalidate()
    }

    fun clearSelectedPolyline() {
        selectedPolylineId = null
        invalidate()
    }

    fun undoPolylineVertex() {
        if (currentPolylinePoints.isNotEmpty()) {
            currentPolylinePoints.removeAt(currentPolylinePoints.lastIndex)
            onPolylineChanged?.invoke()
            invalidate()
        }
    }

    fun finishPolyline() {
        if (mode != TopoPlanMode.DRAW_POLYLINE) return
        if (currentPolylinePoints.size < 2) return
        val cls = currentClassType ?: return

        val first = currentPolylinePoints.first()
        val last = currentPolylinePoints.last()
        val isClosed = first.id == last.id && currentPolylinePoints.size >= 3
        val isSurfaceFill = isClosed && !cls.equals(LINEAR_NO_SURFACE, ignoreCase = true)

        val feature = PolylineFeature(
            id = "poly-${features.size + 1}",
            classType = cls,
            points = currentPolylinePoints.toList(),
            isClosed = isClosed,
            isSurfaceFill = isSurfaceFill
        )
        features.add(feature)
        currentPolylinePoints.clear()
        onPolylineFinished?.invoke(feature)
        onPolylineChanged?.invoke()
        invalidate()
    }

    fun exitPolyline() {
        currentPolylinePoints.clear()
        onPolylineChanged?.invoke()
        invalidate()
    }

    fun canUndo(): Boolean = mode == TopoPlanMode.DRAW_POLYLINE && currentPolylinePoints.isNotEmpty()
    fun canFinish(): Boolean = mode == TopoPlanMode.DRAW_POLYLINE && currentPolylinePoints.size >= 2

    fun zoomIn() = zoomBy(1.25f)
    fun zoomOut() = zoomBy(0.8f)

    private fun zoomBy(factor: Float) {
        val old = userZoom
        val nz = (old * factor).coerceIn(minZoom, maxZoom)
        val f = nz / old
        val fx = width / 2f
        val fy = height / 2f
        panX = fx - (fx - panX) * f
        panY = fy - (fy - panY) * f
        userZoom = nz
        invalidate()
    }

    fun setVoiceFollowEnabled(enabled: Boolean) {
        voiceFollowEnabled = enabled
    }

    fun setVoiceZoomMeters(meters: Double) {
        voiceZoomMeters = meters.coerceIn(VOICE_ZOOM_MIN_METERS, VOICE_ZOOM_MAX_METERS)
    }

    fun updateVoiceFollowInct(x: Double, y: Double) {
        voiceFollowX = x
        voiceFollowY = y
        invalidate()
    }

    fun hasVoiceFollowTarget(): Boolean {
        return voiceFollowX != null && voiceFollowY != null
    }

    fun applyVoiceZoomAndCenter() {
        Log.d(
            "VOICE_TOPO",
            "apply voice=($voiceFollowX,$voiceFollowY) zoomMeters=$voiceZoomMeters pan=($panX,$panY) zoom=$userZoom"
        )
        val b = computeBoundsAll() ?: return
        fitToViewPending = false
        val zoomLevel = voiceZoomMetersToOsmdroidZoom(voiceZoomMeters)
        val zoomDelta = zoomLevel - VOICE_ZOOM_REFERENCE_LEVEL
        val desiredZoom = (2.0.pow(-zoomDelta)).toFloat().coerceIn(minZoom, maxZoom)
        userZoom = desiredZoom

        if (voiceFollowEnabled) {
            val targetX = voiceFollowX
            val targetY = voiceFollowY
            if (targetX != null && targetY != null) {
                val (newPanX, newPanY) = computePanForWorldPoint(b, targetX, targetY)
                panX = newPanX
                panY = newPanY
            }
        }
        Log.d("VOICE_TOPO", "after apply pan=($panX,$panY) zoom=$userZoom")
        invalidate()
    }

    private fun computeBoundsAll(): BoundsXY? {
        val allX = mutableListOf<Double>()
        val allY = mutableListOf<Double>()

        points.forEach { allX += it.x; allY += it.y }
        features.forEach { f -> f.points.forEach { r -> allX += r.x; allY += r.y } }
        currentPolylinePoints.forEach { r -> allX += r.x; allY += r.y }

        if (allX.isEmpty() || allY.isEmpty()) return null

        val minX = allX.minOrNull() ?: return null
        val maxX = allX.maxOrNull() ?: return null
        val minY = allY.minOrNull() ?: return null
        val maxY = allY.maxOrNull() ?: return null

        val padX = max(1.0, (maxX - minX) * 0.08)
        val padY = max(1.0, (maxY - minY) * 0.08)
        return BoundsXY(minX - padX, maxX + padX, minY - padY, maxY + padY)
    }

    private fun buildTransform(b: BoundsXY): WorldToScreen {
        val worldW = (b.maxX - b.minX).takeIf { it > 0 } ?: 1.0
        val worldH = (b.maxY - b.minY).takeIf { it > 0 } ?: 1.0

        val left = contentLeft()
        val top = contentTop()
        val right = contentRight()
        val bottom = contentBottom()

        val availW = (right - left).coerceAtLeast(1.0)
        val availH = (bottom - top).coerceAtLeast(1.0)

        val baseScale = min(availW / worldW, availH / worldH)
        val scale = baseScale * userZoom.toDouble()

        val usedW = worldW * scale
        val usedH = worldH * scale

        val ox = left + (availW - usedW) / 2.0 + panX.toDouble()
        val oy = top + (availH - usedH) / 2.0 + panY.toDouble()

        return WorldToScreen(
            scale = scale,
            offsetX = ox,
            offsetY = oy,
            minX = b.minX,
            maxY = b.maxY
        )
    }

    private fun computePanForWorldPoint(b: BoundsXY, x: Double, y: Double): Pair<Float, Float> {
        val worldW = (b.maxX - b.minX).takeIf { it > 0 } ?: 1.0
        val worldH = (b.maxY - b.minY).takeIf { it > 0 } ?: 1.0

        val left = contentLeft()
        val top = contentTop()
        val right = contentRight()
        val bottom = contentBottom()

        val availW = (right - left).coerceAtLeast(1.0)
        val availH = (bottom - top).coerceAtLeast(1.0)

        val baseScale = min(availW / worldW, availH / worldH)
        val scale = baseScale * userZoom.toDouble()

        val usedW = worldW * scale
        val usedH = worldH * scale

        val centerX = left + availW / 2.0
        val centerY = top + availH / 2.0

        val panX = centerX - left - (availW - usedW) / 2.0 - (x - b.minX) * scale
        val panY = centerY - top - (availH - usedH) / 2.0 - (b.maxY - y) * scale
        return panX.toFloat() to panY.toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)

        val b = computeBoundsAll() ?: run {
            drawEmpty(canvas)
            return
        }
        lastBounds = b

        if (fitToViewPending && !hasVoiceFollowTarget()) {
            userZoom = 1f
            panX = 0f
            panY = 0f
            fitToViewPending = false
        }

        val t = buildTransform(b)
        drawGrid(canvas, b, t)
        drawFrame(canvas)
        drawAxisLabels(canvas, b, t)
        drawFeatures(canvas, t)
        drawActivePolyline(canvas, t)
        drawPoints(canvas, t)
        val voiceX = voiceFollowX
        val voiceY = voiceFollowY
        if (voiceX != null && voiceY != null) {
            val px = t.xToPx(voiceX)
            val py = t.yToPx(voiceY)
            canvas.drawCircle(px, py, dp(8f), voiceTargetPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressTriggered = false
                downX = event.x
                downY = event.y
                removeCallbacks(longPressRunnable)
                if (event.pointerCount == 1) {
                    postDelayed(longPressRunnable, longPressDelayMs)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (dx * dx + dy * dy > touchSlop * touchSlop) {
                    removeCallbacks(longPressRunnable)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                if (event.actionMasked == MotionEvent.ACTION_UP && longPressTriggered) {
                    longPressTriggered = false
                    return true
                }
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onCanvasTouched?.invoke()
        val sd = scaleDetector.onTouchEvent(event)
        val gd = gestureDetector.onTouchEvent(event)
        return sd || gd || super.onTouchEvent(event)
    }

    private fun drawEmpty(canvas: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 40f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("Aucun point.", 40f, 80f, p)
    }

    private fun drawFrame(canvas: Canvas) {
        val l = contentLeft().toFloat()
        val t = contentTop().toFloat()
        val r = contentRight().toFloat()
        val b = contentBottom().toFloat()

        canvas.drawRect(l, t, r, b, axisPaint)
        canvas.drawLine(l, b, r, b, axisHighlightPaint)
        canvas.drawLine(l, t, l, b, axisHighlightPaint)

        val lp = Paint(labelFillPaint).apply { textSize = 30f }

        // X en bas (dans la zone utile => plus caché)
        canvas.drawText("X (m)", (l + r) * 0.5f - 30f, b - 10f, lp)

        // Y à gauche
        canvas.save()
        canvas.rotate(-90f, l - 10f, (t + b) * 0.5f)
        canvas.drawText("Y (m)", l - 10f, (t + b) * 0.5f, lp)
        canvas.restore()
    }

    private fun drawGrid(canvas: Canvas, b: BoundsXY, t: WorldToScreen) {
        val step = inferGridStep(b)

        val l = contentLeft().toFloat()
        val top = contentTop().toFloat()
        val r = contentRight().toFloat()
        val bot = contentBottom().toFloat()

        var x = kotlin.math.floor(b.minX / step) * step
        val xEnd = kotlin.math.ceil(b.maxX / step) * step
        while (x <= xEnd + 1e-9) {
            val px = t.xToPx(x)
            canvas.drawLine(px, top, px, bot, gridPaint)
            x += step
        }

        var y = kotlin.math.floor(b.minY / step) * step
        val yEnd = kotlin.math.ceil(b.maxY / step) * step
        while (y <= yEnd + 1e-9) {
            val py = t.yToPx(y)
            canvas.drawLine(l, py, r, py, gridPaint)
            y += step
        }
    }

    private fun drawAxisLabels(canvas: Canvas, b: BoundsXY, t: WorldToScreen) {
        val step = inferGridStep(b)
        val labelEvery = if (step * t.scale < minAxisLabelSpacingPx) 2 else 1

        val l = contentLeft().toFloat()
        val top = contentTop().toFloat()
        val r = contentRight().toFloat()
        val bot = contentBottom().toFloat()

        var xi = 0
        var x = kotlin.math.floor(b.minX / step) * step
        val xEnd = kotlin.math.ceil(b.maxX / step) * step
        val bottomY = bot - axisLabelPadding
        while (x <= xEnd + 1e-9) {
            if (xi % labelEvery == 0) {
                val px = t.xToPx(x)
                val label = formatAxisValue(x)
                val w = axisValuePaint.measureText(label)
                if (px - w / 2f >= l && px + w / 2f <= r) {
                    canvas.save()
                    canvas.translate(px, bottomY)
                    canvas.rotate(-90f)
                    canvas.drawText(label, 0f, 0f, axisValuePaint)
                    canvas.restore()
                }
            }
            xi++
            x += step
        }

        var yi = 0
        var y = kotlin.math.floor(b.minY / step) * step
        val yEnd = kotlin.math.ceil(b.maxY / step) * step
        val fm = axisValuePaintY.fontMetrics
        val leftX = l + axisLabelPadding
        while (y <= yEnd + 1e-9) {
            if (yi % labelEvery == 0) {
                val py = t.yToPx(y)
                val label = formatAxisValue(y)
                val textTop = py + fm.ascent
                val textBottom = py + fm.descent
                if (textTop >= top && textBottom <= bot) {
                    canvas.drawText(label, leftX, py, axisValuePaintY)
                }
            }
            yi++
            y += step
        }
    }

    private fun inferGridStep(b: BoundsXY): Double {
        val span = max(b.maxX - b.minX, b.maxY - b.minY)
        return when {
            span <= 10 -> 1.0
            span <= 20 -> 2.0
            span <= 50 -> 5.0
            span <= 100 -> 10.0
            span <= 200 -> 20.0
            else -> 50.0
        }
    }

    private fun formatAxisValue(v: Double): String = kotlin.math.round(v).toLong().toString()

    private fun drawFeatures(canvas: Canvas, t: WorldToScreen) {
        features.forEach { f ->
            if (f.points.size < 2) return@forEach
            val path = Path()
            f.points.forEachIndexed { i, ref ->
                val px = t.xToPx(ref.x)
                val py = t.yToPx(ref.y)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            if (f.isClosed) {
                path.close()
                if (f.isSurfaceFill) canvas.drawPath(path, fillPaint)
            }
            val isSelected = f.id == selectedPolylineId
            val paint = if (isSelected) selectedPolylinePaint else paintForClass(f.classType)
            canvas.drawPath(path, paint)
        }
    }

    private fun drawActivePolyline(canvas: Canvas, t: WorldToScreen) {
        if (currentPolylinePoints.size < 2) return
        val path = Path()
        currentPolylinePoints.forEachIndexed { i, ref ->
            val px = t.xToPx(ref.x)
            val py = t.yToPx(ref.y)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, paintForClass(currentClassType ?: "Limite"))
    }

    private fun drawPoints(canvas: Canvas, t: WorldToScreen) {
        val r = 18f
        val showLabels = true
        val labelMargin = 12f

        points.forEachIndexed { idx, p ->
            val px = t.xToPx(p.x)
            val py = t.yToPx(p.y)

            val type = p.type ?: TopoPointType.BORNE
            val bmp = getSymbolBitmap(type, 120)
            if (bmp != null) {
                canvas.drawBitmap(bmp, px - bmp.width / 2f, py - bmp.height / 2f, null)
            } else {
                canvas.drawCircle(px, py, r, pointFill)
                canvas.drawCircle(px, py, r, pointStroke)
            }

            if (p == selectedPoint) canvas.drawCircle(px, py, r + 10f, highlightPaint)

            if (showLabels) {
                val label = p.id
                val iconOffset = bmp?.width?.div(2f) ?: r
                val textX = px + iconOffset + labelMargin
                val alt = if (idx % 2 == 0) -20f else 28f
                val textY = py + alt
                val w = labelFillPaint.measureText(label)
                val fm = labelFillPaint.fontMetrics
                val h = fm.descent - fm.ascent
                val pad = 6f
                canvas.drawRoundRect(
                    textX - pad,
                    textY + fm.ascent - pad,
                    textX + w + pad,
                    textY + fm.ascent + h + pad,
                    8f, 8f, labelBgPaint
                )
                canvas.drawText(label, textX, textY, labelStrokePaint)
                canvas.drawText(label, textX, textY, labelFillPaint)
            }
        }
    }

    private fun getSymbolBitmap(type: TopoPointType, targetPx: Int): Bitmap? {
        symbolCache[type]?.let { return it }

        val resId = when (type) {
            TopoPointType.ARBRE -> R.drawable.arbre
            TopoPointType.AVALOIR -> R.drawable.avaloir
            TopoPointType.BORNE -> R.drawable.borne
            TopoPointType.POTEAU -> R.drawable.poteau
            TopoPointType.REGARD -> R.drawable.regard
        }

        val raw = BitmapFactory.decodeResource(resources, resId) ?: return null
        val bmp = if (raw.width != targetPx || raw.height != targetPx) {
            Bitmap.createScaledBitmap(raw, targetPx, targetPx, true)
        } else {
            raw
        }

        symbolCache[type] = bmp
        return bmp
    }

    private fun isTouchNearAnyPoint(x: Float, y: Float): Boolean {
        val b = lastBounds ?: return false
        if (width <= 0 || height <= 0) return false
        val t = buildTransform(b)
        return points.any { p ->
            val px = t.xToPx(p.x)
            val py = t.yToPx(p.y)
            hypot(x - px, y - py) <= selectionTolerancePx
        }
    }

    private fun handleTap(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return false
        if (longPressTriggered) return true
        val b = lastBounds ?: return false
        val t = buildTransform(b)

        val hits = points.mapNotNull { p ->
            val px = t.xToPx(p.x)
            val py = t.yToPx(p.y)
            val d = hypot(e.x - px, e.y - py)
            if (d <= selectionTolerancePx) Pair(p, d) else null
        }.sortedBy { it.second }

        if (hits.isEmpty()) return false

        val candidates = hits.take(8).map { it.first }
        if (candidates.size == 1) {
            handlePointSelection(candidates.first())
        } else {
            onMultiPointSelection?.invoke(candidates) { chosen -> handlePointSelection(chosen) }
        }
        return true
    }

    private fun handlePointSelection(point: TopoPoint) {
        selectedPoint = point
        if (mode == TopoPlanMode.DRAW_POLYLINE) {
            currentPolylinePoints.add(PointRef(point.id, point.x, point.y))
            onPolylineChanged?.invoke()
        }
        invalidate()
    }

    fun ensureMultiPointDialog(candidates: List<TopoPoint>, onSelected: (TopoPoint) -> Unit) {
        val items = candidates.map { it.id }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle("Choisir un point")
            .setItems(items) { _, which -> onSelected(candidates[which]) }
            .show()
    }

    private fun paintForClass(classType: String): Paint {
        val p = Paint(polyPaint)
        when (classType.lowercase()) {
            "mur" -> { p.strokeWidth = 12f; p.pathEffect = null }
            "clôture", "cloture" -> { p.strokeWidth = 8f; p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 14f), 0f) }
            "limite" -> { p.strokeWidth = 6f; p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(30f, 16f), 0f) }
            "bordure/trottoir", "bordure", "trottoir" -> { p.strokeWidth = 10f; p.pathEffect = null }
            "axe/chemin", "axe", "chemin" -> { p.strokeWidth = 8f; p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f) }
            "ligne électrique", "ligne electrique" -> { p.strokeWidth = 6f; p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 10f), 0f) }
        }
        return p
    }

    private fun selectPolylineAt(x: Float, y: Float): PolylineFeature? {
        val b = lastBounds ?: return null
        if (width <= 0 || height <= 0) return null
        val t = buildTransform(b)

        var closest: PolylineFeature? = null
        var minDist = Float.MAX_VALUE

        features.forEach { f ->
            if (f.points.size < 2) return@forEach
            var prev = f.points.first()
            f.points.drop(1).forEach { curr ->
                val x1 = t.xToPx(prev.x)
                val y1 = t.yToPx(prev.y)
                val x2 = t.xToPx(curr.x)
                val y2 = t.yToPx(curr.y)
                val d = distancePointToSegment(x, y, x1, y1, x2, y2)
                if (d < minDist) {
                    minDist = d
                    closest = f
                }
                prev = curr
            }
        }

        val selected = if (minDist <= polylineSelectionTolerancePx) closest else null
        if (selected != null) {
            selectedPolylineId = selected.id
            invalidate()
        }
        return selected
    }

    private fun distancePointToSegment(
        px: Float,
        py: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        if (dx == 0f && dy == 0f) return hypot(px - x1, py - y1)
        val t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)
        val clamped = t.coerceIn(0f, 1f)
        val cx = x1 + clamped * dx
        val cy = y1 + clamped * dy
        return hypot(px - cx, py - cy)
    }

    companion object {
        private const val LINEAR_NO_SURFACE = "Ligne électrique"
    }

    var onPolylineFinished: ((PolylineFeature) -> Unit)? = null
}
