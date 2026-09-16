package com.nicholas.onote.drawing

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Build
import android.view.MotionEvent
import android.view.View
import com.nicholas.onote.data.NotePage
import com.nicholas.onote.data.PageFlow
import kotlin.math.floor

/**
 * The low-level drawing surface. Renders in document space under a
 * translate+scale transform, so stroke coordinates never change when the user
 * pans/zooms.
 *
 * In **Pages** mode the notebook's pages are stacked vertically in document
 * space ([PageFlow]); each page is a fixed white paper surface on a darker
 * "desk" background, and the user scrolls down through them like a PDF.
 * Strokes are clipped to their page so the desk area cannot be written on.
 * Only the active page's engine content is rendered; other pages come straight
 * from their `NotePage` data.
 *
 * In **Infinite** mode the page fills the whole viewport as before.
 */
class DrawingCanvasView(
    context: Context,
    val engine: DrawingEngine,
    /** True when the notebook is a fixed-size "Pages" notebook. */
    val pagesMode: Boolean = false
) : View(context) {

    val input = StylusInputHandler(engine) {
        if (pagesMode) clampTransform()
        invalidate()
    }

    /** All pages in the flow, aligned to their [PageFlow] positions. */
    var flowPages: List<NotePage> = emptyList()

    /** Index (into [flowPages]) whose strokes live in the engine. */
    var flowActiveIndex: Int = 0

    private val pagePaint = Paint().apply { style = Paint.Style.FILL }

    /** Solid colour filling the desk behind the paper page. */
    private val deskPaint = Paint().apply { style = Paint.Style.FILL }

    private val pageBorderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0x44000000.toInt()
        isAntiAlias = true
    }

    private val gridPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = false
    }

    private val dotPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val strokeFill = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val highlightFill = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        color = 0xFF555555.toInt()
        textSize = 40f
        textAlign = Paint.Align.CENTER
    }

    private val hudPaint = Paint().apply {
        isAntiAlias = true
        color = 0xFF9E1500.toInt()
        @Suppress("DEPRECATION")
        textSize = 11f * resources.displayMetrics.scaledDensity
        typeface = Typeface.MONOSPACE
    }

    private val hudBgPaint = Paint().apply {
        color = 0xCCFFFFFF.toInt()
    }

    private val scratchPath = Path()

    private val gridStep = 32f

    init {
        isClickable = true
        isFocusable = true
        setWillNotDraw(false)
        input.pageFlowEnabled = pagesMode
        input.pageCount = 1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        input.handleEvent(event)
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (pagesMode) fitPageToWidth()
    }

    /**
     * Replaces the flow page list and active index, and re-syncs the input
     * handler's page-routing fields.
     */
    fun setFlowPages(pages: List<NotePage>, activeIndex: Int) {
        flowPages = pages
        flowActiveIndex = activeIndex
        if (input.pageFlowEnabled) {
            input.pageCount = pages.size
            input.flowIndex = activeIndex
        }
    }

    /**
     * Positions the flow so page [index] starts near the top of the viewport
     * (or centres the whole flow when it fits on screen).
     */
    fun scrollToPage(index: Int) {
        if (!pagesMode || flowPages.isEmpty()) return
        val last = flowPages.size - 1
        val clamped = index.coerceIn(0, last)
        flowActiveIndex = clamped
        if (input.pageFlowEnabled) input.flowIndex = clamped

        val t = engine.transform
        val viewH = height.toFloat()
        if (viewH < 1f) return
        val topSlack = 64f * resources.displayMetrics.density
        val bottomSlack = 64f * resources.displayMetrics.density
        val docTop = PageFlow.pageTop(clamped)
        val docH = (PageFlow.pageTop(last) + NotePage.PAGE_HEIGHT) * t.zoom
        val desired = topSlack - docTop * t.zoom
        val minY = viewH - bottomSlack - docH
        val maxY = topSlack
        val offY = if (minY >= maxY) (viewH - docH) / 2f else desired.coerceIn(minY, maxY)
        if (offY != t.offsetY) {
            t.apply(t.zoom, t.offsetX, offY)
        }
    }

    /** Re-positions the camera so the paper page fills the viewport width. */
    fun fitPageToWidth() {
        if (!pagesMode) return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW < 1f || viewH < 1f) return
        val pageW = NotePage.PAGE_WIDTH
        val pageH = NotePage.PAGE_HEIGHT
        val marginX = 32f * resources.displayMetrics.density
        val topMargin = 64f * resources.displayMetrics.density
        val bottomMargin = 64f * resources.displayMetrics.density
        var zoom = (viewW - 2f * marginX) / pageW
        zoom = zoom.coerceIn(CameraTransform.MIN_ZOOM, CameraTransform.MAX_ZOOM)
        val scaledPageH = pageH * zoom
        val offsetX = (viewW - pageW * zoom) / 2f
        val offsetY = when {
            scaledPageH + topMargin + bottomMargin <= viewH ->
                (viewH - scaledPageH) / 2f                    // page fits vertically – centre
            else -> topMargin                                   // scrollable – pin top
        }
        engine.transform.apply(zoom, offsetX, offsetY)
    }

    /**
     * Keeps the whole page flow roughly on-screen so the user cannot pan it
     * fully out of view. Vertical clamping is done against the full stack of
     * pages (PDF-like "can't scroll past the ends").
     */
    fun clampTransform() {
        if (!pagesMode) return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW < 1f || viewH < 1f) return
        val t = engine.transform
        val pageW = NotePage.PAGE_WIDTH * t.zoom

        val totalDocH =
            if (flowPages.isEmpty()) NotePage.PAGE_HEIGHT
            else (PageFlow.pageTop(flowPages.size - 1) + NotePage.PAGE_HEIGHT) * t.zoom

        val slack = 48f
        val topSlack = 64f * resources.displayMetrics.density
        val bottomSlack = 64f * resources.displayMetrics.density

        // Horizontal: keep the page stack's visible region overlapping the view.
        val minX = slack - pageW
        val maxX = viewW - slack
        var offX = t.offsetX
        if (minX >= maxX) offX = (minX + maxX) / 2f else offX = offX.coerceIn(minX, maxX)

        // Vertical: flow top can't sink below topSlack, flow bottom stays above
        // viewH - bottomSlack ("can't scroll past the ends").
        val minY = viewH - bottomSlack - totalDocH
        val maxY = topSlack
        var offY = t.offsetY
        if (minY >= maxY) {
            offY = (viewH - totalDocH) / 2f   // whole flow fits – keep centred
        } else {
            offY = offY.coerceIn(minY, maxY)
        }

        if (offX != t.offsetX || offY != t.offsetY) {
            // CameraTransform fields are private-set; apply whole transform.
            t.apply(t.zoom, offX, offY)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        if (pagesMode) {
            drawPagesMode(canvas, w, h)
        } else {
            drawInfiniteMode(canvas, w, h)
        }

        if (engine.debugEnabled) {
            drawDebugHud(canvas, w, h)
        }
    }

    // ---- Infinite mode (original behaviour) ---------------------------------

    private fun drawInfiniteMode(canvas: Canvas, w: Float, h: Float) {
        pagePaint.color = engine.paperColor
        canvas.drawRect(0f, 0f, w, h, pagePaint)

        val t = engine.transform
        canvas.save()
        canvas.translate(t.offsetX, t.offsetY)
        canvas.scale(t.zoom, t.zoom)

        drawBackground(canvas, t)
        drawStrokes(canvas)
        canvas.restore()
    }

    // ---- Pages mode ---------------------------------------------------------

    private fun drawPagesMode(canvas: Canvas, w: Float, h: Float) {
        // Desk background fills the viewport.
        deskPaint.color = engine.deskColor
        canvas.drawRect(0f, 0f, w, h, deskPaint)

        val t = engine.transform
        val pageW = NotePage.PAGE_WIDTH
        val pageH = NotePage.PAGE_HEIGHT
        val count = flowPages.size

        canvas.save()
        canvas.translate(t.offsetX, t.offsetY)
        canvas.scale(t.zoom, t.zoom)

        val first = if (count == 0) 0 else PageFlow.indexForDocY(t.screenToDocY(0f), count)
        val last = if (count == 0) 0 else PageFlow.indexForDocY(t.screenToDocY(h), count)
        for (i in first..last) {
            drawFlowPage(canvas, t, i, pageW, pageH)
        }

        canvas.restore()
    }

    private fun drawFlowPage(
        canvas: Canvas,
        t: CameraTransform,
        index: Int,
        pageW: Float,
        pageH: Float
    ) {
        val top = PageFlow.pageTop(index)
        val page = flowPages.getOrNull(index) ?: return

        // Paper rectangle.
        pagePaint.color = engine.paperColor
        canvas.drawRect(0f, top, pageW, top + pageH, pagePaint)

        // Clipped background + strokes.
        canvas.save()
        canvas.clipRect(0f, top, pageW, top + pageH)

        if (index == flowActiveIndex) {
            drawPageBackground(canvas, engine.pageBackground, 0f, pageW, top, top + pageH)
            canvas.save()
            canvas.translate(0f, top)   // engine strokes are page-local
            drawStrokes(canvas)
            canvas.restore()
        } else {
            drawPageBackground(canvas, page.pageBackground, 0f, pageW, top, top + pageH)
            drawCompletedStrokes(canvas, page.strokes)
        }

        val title = page.title
        if (title.isNotBlank()) {
            val isDarkPaper = gridColor(engine.paperColor) == 0xFF4A4A4A.toInt()
            titlePaint.color = if (isDarkPaper) 0xFFEDEDED.toInt() else 0xFF444A52.toInt()
            canvas.drawText(title, pageW / 2f, top + 78f, titlePaint)
        }

        canvas.restore()

        // Subtle border around page.
        canvas.drawRect(0f, top, pageW, top + pageH, pageBorderPaint)
    }

    // ---- Shared drawing helpers ---------------------------------------------

    private fun drawCompletedStrokes(canvas: Canvas, strokes: List<CompletedStroke>) {
        for (stroke in strokes) {
            val fill = if (stroke.tool == Tool.HIGHLIGHTER) {
                StrokeRenderer.prepareHighlightFill(highlightFill, engine.paperColor)
                highlightFill
            } else {
                StrokeRenderer.prepareInkFill(strokeFill)
                strokeFill
            }
            StrokeRenderer.drawCompleted(stroke, canvas, fill)
        }
    }

    private fun drawStrokes(canvas: Canvas) {
        drawCompletedStrokes(canvas, engine.strokes)
        engine.activeStroke?.let { active ->
            val fill = if (active.tool == Tool.HIGHLIGHTER) {
                StrokeRenderer.prepareHighlightFill(highlightFill, engine.paperColor)
                highlightFill
            } else {
                StrokeRenderer.prepareInkFill(strokeFill)
                strokeFill
            }
            StrokeRenderer.drawActive(active, canvas, fill, scratchPath)
        }
    }

    private fun drawBackground(canvas: Canvas, t: CameraTransform) {
        if (engine.pageBackground == PageBackground.BLANK) return

        val left = t.screenToDocX(0f)
        val right = t.screenToDocX(width.toFloat())
        val top = t.screenToDocY(0f)
        val bottom = t.screenToDocY(height.toFloat())

        drawPageBackground(canvas, engine.pageBackground, left, right, top, bottom)
    }

    /** Draws the pattern for [bg] across the given document-space bounds. */
    private fun drawPageBackground(
        canvas: Canvas,
        bg: PageBackground,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float
    ) {
        if (bg == PageBackground.BLANK) return

        val paintColor = gridColor(engine.paperColor)
        gridPaint.color = paintColor
        gridPaint.strokeWidth = 1f / engine.transform.zoom
        dotPaint.color = paintColor

        var step = gridStep
        while (step * engine.transform.zoom < 16f) step *= 2f

        when (bg) {
            PageBackground.BLANK -> {}
            PageBackground.RULED -> drawHorizontalLines(canvas, left, right, top, bottom, step)
            PageBackground.GRAPH -> {
                drawHorizontalLines(canvas, left, right, top, bottom, step)
                drawVerticalLines(canvas, left, right, top, bottom, step)
            }
            PageBackground.DOT -> {
                val r = 1.5f / engine.transform.zoom
                var x = floor(left / step) * step
                while (x <= right) {
                    var y = floor(top / step) * step
                    while (y <= bottom) {
                        canvas.drawCircle(x, y, r, dotPaint)
                        y += step
                    }
                    x += step
                }
            }
        }
    }

    private fun drawHorizontalLines(
        canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float, step: Float
    ) {
        var y = floor(top / step) * step
        while (y <= bottom) {
            canvas.drawLine(left, y, right, y, gridPaint)
            y += step
        }
    }

    private fun drawVerticalLines(
        canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float, step: Float
    ) {
        var x = floor(left / step) * step
        while (x <= right) {
            canvas.drawLine(x, top, x, bottom, gridPaint)
            x += step
        }
    }

    private fun gridColor(paper: Int): Int {
        val r = (paper shr 16) and 0xFF
        val g = (paper shr 8) and 0xFF
        val b = paper and 0xFF
        val luminance = 0.299f * r + 0.587f * g + 0.114f * b
        return if (luminance < 128f) 0xFF4A4A4A.toInt() else 0xFFD8E6F6.toInt()
    }

    private fun drawDebugHud(canvas: Canvas, w: Float, h: Float) {
        val t = engine.transform
        val active = engine.activeStroke
        val lastP = active?.points?.lastOrNull()
        val eraser = engine.toolMode == ToolMode.ERASER
        val palmOff = engine.palmRejection == false
        val lines = listOf(
            "${Build.MANUFACTURER.uppercase()} ${Build.MODEL} SDK ${Build.VERSION.SDK_INT}" +
                " ${width}x${height}px",
            "mode=${input.mode.name} tool=${engine.toolMode.name} bg=${engine.pageBackground.name}" +
                if (eraser) " (radius=${engine.eraseRadius.toInt()})" else "",
            "ptrs stylus=${input.stylusPointersActive()} touch=${input.touchPointersActive()}" +
                " palm=${engine.palmRejection}",
            "strokes=${engine.strokeCount}" +
                if (engine.isErasing) " +erasing" else
                    if (active != null) "+active(${active.points.size}pts)" else "",
            "lastP p=${lastP?.let { "%.2f".format(it.pressure) } ?: "--"} " +
                if (palmOff) "palmrej=OFF" else "",
            "zoom=${"%.2f".format(t.zoom)} off=(${"%.0f".format(t.offsetX)},${"%.0f".format(t.offsetY)})",
            "events=${input.totalEvents} move=${input.moveEvents}" +
                " avgMove=%.1fms".format(input.avgMoveIntervalMs),
            "pen ${engine.activeWidth.toInt()}px ${engine.activeTool.name} " +
                "undo=${if (engine.canUndo) "Y" else "n"} redo=${if (engine.canRedo) "Y" else "n"}" +
                " flow=${flowActiveIndex + 1}/${flowPages.size}"
        )

        val lineHeight = hudPaint.fontSpacing
        val boxH = lineHeight * lines.size + 20f
        val boxW = w - 24f
        val left = 12f
        val top = h - boxH - 12f

        canvas.drawRoundRect(left, top, left + boxW, top + boxH, 8f, 8f, hudBgPaint)

        var y = top + lineHeight
        for (line in lines) {
            canvas.drawText(line, left + 12f, y, hudPaint)
            y += lineHeight
        }
    }
}