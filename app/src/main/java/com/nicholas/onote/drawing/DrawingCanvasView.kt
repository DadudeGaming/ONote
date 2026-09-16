package com.nicholas.onote.drawing

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Build
import android.view.MotionEvent
import android.view.View
import kotlin.math.floor
import kotlin.math.max

/**
 * The low-level drawing surface. Renders in document space under a
 * translate+scale transform, so stroke coordinates never change when the user
 * pans/zooms. Page appearance (paper color, ruled/graph/dot background) is
 * drawn in the same document space so it stays glued to the page.
 */
class DrawingCanvasView(
    context: Context,
    val engine: DrawingEngine
) : View(context) {

    val input = StylusInputHandler(engine) { invalidate() }

    private val pagePaint = Paint().apply { style = Paint.Style.FILL }

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
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        input.handleEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        pagePaint.color = engine.paperColor
        canvas.drawRect(0f, 0f, w, h, pagePaint)

        val t = engine.transform
        canvas.save()
        canvas.translate(t.offsetX, t.offsetY)
        canvas.scale(t.zoom, t.zoom)

        drawBackground(canvas, t)

        for (stroke in engine.strokes) {
            StrokeRenderer.drawCompleted(stroke, canvas, strokeFill)
        }
        engine.activeStroke?.let {
            StrokeRenderer.drawActive(it, canvas, strokeFill, scratchPath)
        }

        canvas.restore()

        if (engine.debugEnabled) {
            drawDebugHud(canvas, w, h)
        }
    }

    private fun drawBackground(canvas: Canvas, t: CameraTransform) {
        if (engine.pageBackground == PageBackground.BLANK) return

        val left = t.screenToDocX(0f)
        val right = t.screenToDocX(width.toFloat())
        val top = t.screenToDocY(0f)
        val bottom = t.screenToDocY(height.toFloat())

        val paintColor = gridColor(engine.paperColor)
        gridPaint.color = paintColor
        dotPaint.color = paintColor

        // Grow the grid spacing at low zoom so we never draw an excessive
        // number of elements per frame.
        var step = gridStep
        while (step * t.zoom < 12f) step += gridStep

        when (engine.pageBackground) {
            PageBackground.BLANK -> {}
            PageBackground.RULED -> drawHorizontalLines(canvas, left, right, top, bottom, step)
            PageBackground.GRAPH -> {
                drawHorizontalLines(canvas, left, right, top, bottom, step)
                drawVerticalLines(canvas, left, right, top, bottom, step)
            }
            PageBackground.DOT -> {
                val r = max(1f, 2f / t.zoom)
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
                "undo=${if (engine.canUndo) "Y" else "n"} redo=${if (engine.canRedo) "Y" else "n"}"
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