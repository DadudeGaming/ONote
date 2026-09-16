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

/**
 * The low-level drawing surface. Renders in document space under a
 * translate+scale transform, so strokes never change when the user pans/zooms.
 *
 * A custom [View] (rather than a Compose Canvas) is used on purpose: raw
 * [MotionEvent]s give us per-point pressure/tool/pointer info and let us
 * `invalidate()` without Compose recomposition, which is what keeps the pen
 * trail feeling immediate.
 */
class DrawingCanvasView(
    context: Context,
    val engine: DrawingEngine
) : View(context) {

    val input = StylusInputHandler(engine) { invalidate() }

    private val pagePaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val gridPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = 0xFFD6E6F5.toInt()
        strokeWidth = 1f
        isAntiAlias = false
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

        canvas.drawRect(0f, 0f, w, h, pagePaint)

        val t = engine.transform
        canvas.save()
        canvas.translate(t.offsetX, t.offsetY)
        canvas.scale(t.zoom, t.zoom)

        drawGrid(canvas, w, h)

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

    private fun drawGrid(canvas: Canvas, w: Float, h: Float) {
        val t = engine.transform
        val left = t.screenToDocX(0f)
        val right = t.screenToDocX(w)
        val top = t.screenToDocY(0f)
        val bottom = t.screenToDocY(h)

        var x = floor(left / gridStep) * gridStep
        while (x <= right) {
            canvas.drawLine(x, top, x, bottom, gridPaint)
            x += gridStep
        }
        var y = floor(top / gridStep) * gridStep
        while (y <= bottom) {
            canvas.drawLine(left, y, right, y, gridPaint)
            y += gridStep
        }
    }

    private fun drawDebugHud(canvas: Canvas, w: Float, h: Float) {
        val t = engine.transform
        val active = engine.activeStroke
        val mode = input.mode.name
        val lastP = active?.points?.lastOrNull()
        val stylus = input.stylusPointersActive()
        val touch = input.touchPointersActive()

        val lines = listOf(
            "${Build.MANUFACTURER.uppercase()} ${Build.MODEL} " +
                "SDK ${Build.VERSION.SDK_INT} | ${width}x${height}px",
            "mode=$mode | stylus ptrs=$stylus touch ptrs=$touch | palmRej=${engine.palmRejection}",
            "strokes=${engine.strokeCount}" +
                if (active != null) " +active(${active.points.size} pts)" else "",
            "lastPt p=${lastP?.let { "%.2f".format(it.pressure) } ?: "--"} " +
                "w=${StrokeRenderer.pressureWidth(engine.activeWidth, lastP?.pressure ?: 0f).let { "%.1f".format(it) }}",
            "zoom=${"%.2f".format(t.zoom)} off=(${"%.0f".format(t.offsetX)},${"%.0f".format(t.offsetY)})",
            "events=${input.totalEvents} move=${input.moveEvents} " +
                "avgMove=%.1fms".format(input.avgMoveIntervalMs),
            "finger=${engine.activeWidth.toInt()}px ${engine.activeTool.name}"
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