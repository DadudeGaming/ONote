package com.nicholas.onote.drawing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.view.MotionEvent
import android.view.View
import com.nicholas.onote.data.NotePage
import com.nicholas.onote.data.PlacedImage
import com.nicholas.onote.data.PageFlow
import java.io.File
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The low-level drawing surface. Renders in document space under a
 * translate+scale transform, so stroke coordinates never change when the user
 * pans/zooms.
 *
 * In **Pages** mode the notebook's pages are stacked vertically in document
 * space ([PageFlow]); each page is a fixed white paper surface on a darker
 * "desk" background, and the user scrolls through them like a PDF. Landscape
 * and portrait pages mix freely, each centered on the stack's centre line.
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

    /** Invoked when the user keeps scrolling past the last page (auto-add). */
    var onRequestNewPage: (() -> Unit)? = null
    private var pendingNewPage = false

    /** Imported images for infinite notebooks (doc-space coords). */
    var infiniteImages: List<PlacedImage> = emptyList()

    /** Set by the editor while the user is placing/moving/resizing images. */
    var imageToolActive = false

    /** Invoked after an image is moved/resized/added/deleted (persist). */
    var onImagesChanged: (() -> Unit)? = null
    private var selectedImageId: String? = null

    // Image placement gesture state.
    private var imgDragImage: PlacedImage? = null
    private var imgDragMode = 0          // 1 = move, 2 = resize, 3 = delete
    private var imgStartX = 0f
    private var imgStartY = 0f
    private var imgOrigX = 0f
    private var imgOrigY = 0f
    private var imgOrigW = 0f
    private var imgOrigH = 0f
    private var imgMoved = false

    private val bitmapCache = HashMap<String, Bitmap>()
    private val imagePaint = Paint().apply { isFilterBitmap = true }
    private val selectionPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFF1A73E8.toInt()
    }
    private val handlePaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0xFF1A73E8.toInt()
    }
    private val deleteBadgePaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0xFFD93025.toInt()
    }
    private val deleteBadgeTextPaint = Paint().apply {
        isAntiAlias = true
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
    }

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

    /** Desk clearance at the top; 0 so a scrolled-up page is cut exactly at the
     * bottom of the tab bar (the canvas starts right where the bar ends). */
    private val topSlackPx: Float
        get() = TOP_SLACK_DP * resources.displayMetrics.density
    /** No bottom cutoff: the page stack may reach all the way to the bottom edge. */
    private val bottomSlackPx: Float
        get() = BOTTOM_SLACK_DP * resources.displayMetrics.density
    private val marginPx: Float
        get() = MARGIN_DP * resources.displayMetrics.density

    init {
        isClickable = true
        isFocusable = true
        setWillNotDraw(false)
        input.pageFlowEnabled = pagesMode
        input.pageCount = 1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // While the image tool is on, let a finger directly select/move/resize/
        // delete images. If the touch misses every image we deliberately don't
        // consume it, so two-finger pan and pen drawing keep working.
        if (imageToolActive && handleImageTouch(event)) return true
        input.handleEvent(event)
        return true
    }

    // The pointer currently driving an image drag (finger or pen).
    private var imgDragPointerId = -1

    /** Selects / moves / resizes / deletes the image under the pointer. */
    private fun handleImageTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (imgDragImage != null) return true
                val idx = event.actionIndex
                val (px, py) = toPageXY(event.getX(idx), event.getY(idx))
                val images = activeImages()
                imgDragImage = null
                imgDragMode = 0
                imgMoved = false
                for (im in images.asReversed()) {
                    val right = im.x + im.w
                    val bottom = im.y + im.h
                    if (px in im.x..right && py in im.y..bottom) {
                        imgDragImage = im
                        imgDragPointerId = event.getPointerId(idx)
                        imgStartX = px
                        imgStartY = py
                        imgOrigX = im.x
                        imgOrigY = im.y
                        imgOrigW = im.w
                        imgOrigH = im.h
                        val handle = imageHitPx / engine.transform.zoom
                        imgDragMode = when {
                            px > right - handle && py > bottom - handle -> 2   // resize corner
                            px < im.x + handle && py < im.y + handle -> 3      // delete badge
                            else -> 1                                           // move body
                        }
                        selectedImageId = im.id
                        invalidate()
                        return true
                    }
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val drag = imgDragImage ?: return false
                val pi = event.findPointerIndex(imgDragPointerId)
                if (pi < 0) return false
                val (px, py) = toPageXY(event.getX(pi), event.getY(pi))
                when (imgDragMode) {
                    1 -> {
                        val nx = imgOrigX + (px - imgStartX)
                        val ny = imgOrigY + (py - imgStartY)
                        drag.x = nx
                        drag.y = ny
                        imgMoved = true
                        invalidate()
                    }
                    2 -> {
                        val newW = max(MIN_IMAGE_SIZE, imgOrigW + (px - imgStartX))
                        val newH = max(
                            MIN_IMAGE_SIZE,
                            imgOrigH + (newW - imgOrigW) * (imgOrigH / max(1f, imgOrigW))
                        )
                        drag.w = newW
                        drag.h = max(MIN_IMAGE_SIZE, newH)
                        imgMoved = true
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) != imgDragPointerId) return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val drag = imgDragImage
                if (drag != null) {
                    if (imgDragMode == 3 && !imgMoved) {
                        imagesList().remove(drag)
                        selectedImageId = null
                        bitmapCache.remove(drag.path)
                        onImagesChanged?.invoke()
                        invalidate()
                    } else {
                        onImagesChanged?.invoke()
                    }
                    imgDragImage = null
                    imgDragMode = 0
                    imgDragPointerId = -1
                    return true
                }
            }
        }
        return false
    }

    /** Starts placing the given image (enters image tool mode). */
    fun startPlacingImage(imageId: String) {
        imageToolActive = true
        selectedImageId = imageId
        invalidate()
    }

    fun clearImageSelection() {
        imageToolActive = false
        selectedImageId = null
        imgDragImage = null
        imgDragMode = 0
        imgDragPointerId = -1
        invalidate()
    }

    private fun activeImages(): List<PlacedImage> =
        if (pagesMode) flowPages.getOrNull(flowActiveIndex)?.images ?: emptyList()
        else infiniteImages

    private fun imagesList(): ArrayList<PlacedImage> =
        if (pagesMode) flowPages.getOrNull(flowActiveIndex)?.images
            as? ArrayList<PlacedImage> ?: infiniteImages as ArrayList<PlacedImage>
        else (infiniteImages as ArrayList<PlacedImage>)

    /** Screen -> page-local coordinates under the active page/flow. */
    private fun toPageXY(screenX: Float, screenY: Float): Pair<Float, Float> {
        val t = engine.transform
        val docX = t.screenToDocX(screenX)
        val docY = t.screenToDocY(screenY)
        if (!pagesMode) return Pair(docX, docY)
        val top = PageFlow.pageTop(flowActiveIndex, flowPages)
        val page = flowPages.getOrNull(flowActiveIndex)
        val left = if (page != null) (PageFlow.maxWidth(flowPages) - page.width) / 2f else 0f
        return Pair(docX - left, docY - top)
    }

    private fun bitmapFor(path: String): Bitmap? {
        bitmapCache[path]?.let { if (!it.isRecycled) return it }
        val f = File(context.filesDir, path)
        if (!f.isFile) return null
        val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return null
        bitmapCache[path] = bmp
        return bmp
    }

    private fun drawImages(canvas: Canvas, images: List<PlacedImage>, pageX: Float, top: Float) {
        if (images.isEmpty()) return
        canvas.save()
        canvas.translate(pageX, top)
        val selected = selectedImageId
        for (im in images) {
            val bmp = bitmapFor(im.path) ?: continue
            val right = im.x + im.w
            val bottom = im.y + im.h
            canvas.drawBitmap(bmp, null, RectF(im.x, im.y, right, bottom), imagePaint)
            if (imageToolActive && im.id == selected) {
                val draw = imageHandlePx / engine.transform.zoom
                canvas.drawRect(im.x, im.y, right, bottom, selectionPaint)
                canvas.drawCircle(right, bottom, draw, handlePaint)
                val badgeR = draw * 1.8f
                canvas.drawCircle(im.x, im.y, badgeR, deleteBadgePaint)
                deleteBadgeTextPaint.textSize = badgeR * 1.4f
                canvas.drawText(
                    "×", im.x, im.y - (deleteBadgeTextPaint.fontMetrics.ascent + deleteBadgeTextPaint.fontMetrics.descent) / 2f,
                    deleteBadgeTextPaint
                )
            }
        }
        canvas.restore()
    }

    /** On-screen size of the blue touch targets around a selected image. */
    private val imageHitPx: Float get() = 30f * resources.displayMetrics.density

    /** On-screen radius of the drawn resize dot / delete badge. */
    private val imageHandlePx: Float get() = 10f * resources.displayMetrics.density

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
            input.pageTopOf = { PageFlow.pageTop(it, pages) }
            input.pageIndexAtY = { PageFlow.indexForDocY(it, pages) }
        }
        pendingNewPage = false
    }

    /**
     * Positions the flow so page [index] starts near the top of the viewport
     * (pinned at the top so the page edge lines up with the bottom of the tab bar).
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
        val docTop = PageFlow.pageTop(clamped, flowPages)
        val docH = PageFlow.totalHeight(flowPages) * t.zoom
        val desired = topSlackPx - docTop * t.zoom
        val minY = viewH - bottomSlackPx - docH
        val maxY = topSlackPx
        val offY = if (minY >= maxY) maxY else desired.coerceIn(minY, maxY)
        if (offY != t.offsetY) {
            t.apply(t.zoom, t.offsetX, offY)
        }
    }

    /** Re-positions the camera so the widest paper page fills the viewport width. */
    fun fitPageToWidth() {
        if (!pagesMode) return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW < 1f || viewH < 1f) return
        val maxW = PageFlow.maxWidth(flowPages)
        val marginX = 32f * resources.displayMetrics.density
        var zoom = (viewW - 2f * marginX) / maxW
        zoom = zoom.coerceIn(CameraTransform.MIN_ZOOM, CameraTransform.MAX_ZOOM)
        val offsetX = (viewW - maxW * zoom) / 2f
        val offsetY = topSlackPx
        engine.transform.apply(zoom, offsetX, offsetY)
    }

    /**
     * Keeps the whole page flow roughly on-screen so the user cannot pan it
     * fully out of view.
     *
     * Horizontal movement is tightly limited: the page stack can never slide
     * far enough to expose the desk on either side, so "side to side" scrolling
     * is essentially disabled at fit zoom. Vertically the stack is clamped so
     * its top never rises above the canvas top edge (i.e. past the tab bar);
     * pressing past the bottom of
     * the last page requests a new page via [onRequestNewPage].
     */
    fun clampTransform() {
        if (!pagesMode) return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW < 1f || viewH < 1f) return
        val t = engine.transform
        val maxW = PageFlow.maxWidth(flowPages)
        val pageW = maxW * t.zoom
        val totalDocH = PageFlow.totalHeight(flowPages) * t.zoom

        // Horizontal: page edges can never leave the viewport by more than the
        // margin, so sideways scrolling ends once the page fills the width.
        val minX = viewW - pageW - marginPx
        val maxX = marginPx
        var offX = t.offsetX
        if (minX >= maxX) offX = (minX + maxX) / 2f else offX = offX.coerceIn(minX, maxX)

        // Vertical: the flow top is always pinned at or below the top-bar slack,
        // and its bottom stays above the bottom edge. Even when the whole flow
        // fits on screen it is never centred above that line, so pages can't
        // ride up under the top bar.
        val minY = viewH - bottomSlackPx - totalDocH
        val maxY = topSlackPx
        var offY = t.offsetY
        if (minY >= maxY) {
            offY = maxY   // fits – pin the top at the slack line
        } else {
            val trigger = NotePage.PAGE_GAP * t.zoom * 0.5f
            // Only auto-add after a page that actually has content, so scrolling
            // past a deliberately blank page doesn't spawn endless empty ones.
            // The active page's ink lives in the engine (not yet flushed into the
            // NotePage), so include it – otherwise you'd have to switch pages
            // before a fresh stroke could unlock auto-add.
            val lastIdx = flowPages.lastIndex
            val last = flowPages.getOrNull(lastIdx)
            val engineInk = flowActiveIndex == lastIdx &&
                (engine.strokes.isNotEmpty() ||
                    (engine.activeStroke?.points?.size ?: 0) > 1)
            val hasContent = last != null && (
                last.strokes.isNotEmpty() || last.title.isNotBlank() ||
                    last.images.isNotEmpty() || engineInk
                )
            if (hasContent && offY < minY - trigger && !pendingNewPage) {
                pendingNewPage = true
                onRequestNewPage?.invoke()
            } else if (offY >= minY) {
                pendingNewPage = false
            }
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
        drawImages(canvas, infiniteImages, 0f, 0f)
        drawStrokes(canvas)
        canvas.restore()
    }

    // ---- Pages mode ---------------------------------------------------------

    private fun drawPagesMode(canvas: Canvas, w: Float, h: Float) {
        // Desk background fills the viewport.
        deskPaint.color = engine.deskColor
        canvas.drawRect(0f, 0f, w, h, deskPaint)

        val t = engine.transform
        val count = flowPages.size

        // Hard clip below the top band: no page can ever be drawn above this
        // line, so scrolled-up pages are always visibly cut off at the top bar.
        canvas.save()
        canvas.clipRect(0f, topSlackPx, w, h)

        canvas.save()
        canvas.translate(t.offsetX, t.offsetY)
        canvas.scale(t.zoom, t.zoom)

        val first = if (count == 0) 0 else PageFlow.indexForDocY(t.screenToDocY(0f), flowPages)
        val last = if (count == 0) 0 else PageFlow.indexForDocY(t.screenToDocY(h), flowPages)
        for (i in first..last) {
            drawFlowPage(canvas, i)
        }

        canvas.restore()
        canvas.restore()
    }

    private fun drawFlowPage(canvas: Canvas, index: Int) {
        val page = flowPages.getOrNull(index) ?: return
        val top = PageFlow.pageTop(index, flowPages)
        val maxW = PageFlow.maxWidth(flowPages)
        val x = (maxW - page.width) / 2f
        val w = page.width
        val h = page.height

        // Paper rectangle.
        pagePaint.color = engine.paperColor
        canvas.drawRect(x, top, x + w, top + h, pagePaint)

        // Clipped background + images + strokes.
        canvas.save()
        canvas.clipRect(x, top, x + w, top + h)
        drawPageBackground(canvas, page.pageBackground, x, x + w, top, top + h)
        drawImages(canvas, page.images, x, top)
        canvas.save()
        canvas.translate(0f, top)   // engine strokes are page-local in y
        if (index == flowActiveIndex) {
            drawStrokes(canvas)
        } else {
            drawCompletedStrokes(canvas, page.strokes)
        }
        canvas.restore()

        val title = page.title
        if (title.isNotBlank()) {
            val isDarkPaper = gridColor(engine.paperColor) == 0xFF4A4A4A.toInt()
            titlePaint.color = if (isDarkPaper) 0xFFEDEDED.toInt() else 0xFF444A52.toInt()
            canvas.drawText(title, x + w / 2f, top + 78f, titlePaint)
        }

        canvas.restore()

        // Subtle border around page.
        canvas.drawRect(x, top, x + w, top + h, pageBorderPaint)
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

    companion object {
        // Cut off is exactly at the top bar: the canvas starts below the tab
        // strip, so a 0 slack puts the page edge flush against the bar's bottom.
        private const val TOP_SLACK_DP = 0f
        // No bottom cutoff - content may scroll to the very bottom edge.
        private const val BOTTOM_SLACK_DP = 0f
        private const val MARGIN_DP = 48f
        private const val MIN_IMAGE_SIZE = 40f
    }
}