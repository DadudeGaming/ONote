package com.nicholas.onote.drawing

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.hypot

/**
 * Renders strokes as pressure-varying filled "ribbon" polygons instead of a
 * single constant-width stroked polyline, so thickness changes smoothly with
 * S Pen pressure.
 */
object StrokeRenderer {

    class Ribbon(
        val path: Path,
        val startDot: Dot?,
        val endDot: Dot?
    )

    fun pressureWidth(baseWidth: Float, pressure: Float): Float {
        val p = pressure.coerceIn(0f, 1f)
        return baseWidth * (0.25f + 0.75f * p)
    }

    fun ribbonWidth(baseWidth: Float, pressure: Float): Float =
        pressureWidth(baseWidth, pressure) * 0.5f

    fun buildRibbon(points: List<StrokePoint>, baseWidth: Float): Ribbon {
        val path = Path()
        val n = points.size
        if (n == 0) return Ribbon(path, null, null)
        if (n == 1) {
            val r = pressureWidth(baseWidth, points[0].pressure)
            path.addCircle(points[0].x, points[0].y, r, Path.Direction.CW)
            return Ribbon(path, null, null)
        }

        val half = FloatArray(n)
        var i = 0
        while (i < n) {
            half[i] = ribbonWidth(baseWidth, points[i].pressure)
            i++
        }

        val nx = FloatArray(n)
        val ny = FloatArray(n)
        i = 0
        while (i < n) {
            val (tx, ty) = when (i) {
                0 -> tangent(points[0].x, points[0].y, points[1].x, points[1].y)
                n - 1 -> tangent(points[n - 2].x, points[n - 2].y, points[n - 1].x, points[n - 1].y)
                else -> tangent(points[i - 1].x, points[i - 1].y, points[i + 1].x, points[i + 1].y)
            }
            nx[i] = -ty
            ny[i] = tx
            i++
        }

        path.moveTo(points[0].x + nx[0] * half[0], points[0].y + ny[0] * half[0])
        i = 1
        while (i < n) {
            path.lineTo(points[i].x + nx[i] * half[i], points[i].y + ny[i] * half[i])
            i++
        }
        i = n - 1
        while (i >= 0) {
            path.lineTo(points[i].x - nx[i] * half[i], points[i].y - ny[i] * half[i])
            i--
        }
        path.close()

        val startDot = Dot(points[0].x, points[0].y, half[0])
        val endDot = Dot(points[n - 1].x, points[n - 1].y, half[n - 1])
        return Ribbon(path, startDot, endDot)
    }

    fun drawCompleted(stroke: CompletedStroke, canvas: Canvas, fill: Paint) {
        fill.color = stroke.color
        canvas.drawPath(stroke.ribbon.path, fill)
        stroke.ribbon.startDot?.let {
            canvas.drawCircle(it.x, it.y, it.radius, fill)
        }
        stroke.ribbon.endDot?.let {
            canvas.drawCircle(it.x, it.y, it.radius, fill)
        }
    }

    fun drawActive(
        stroke: ActiveStroke,
        canvas: Canvas,
        fillPaint: Paint,
        scratchPath: Path
    ) {
        val ribbon = buildRibbon(stroke.points, stroke.baseWidth)
        fillPaint.color = stroke.color
        canvas.drawPath(ribbon.path, fillPaint)
        ribbon.startDot?.let {
            canvas.drawCircle(it.x, it.y, it.radius, fillPaint)
        }
        ribbon.endDot?.let {
            canvas.drawCircle(it.x, it.y, it.radius, fillPaint)
        }
    }

    private fun tangent(x0: Float, y0: Float, x1: Float, y1: Float): Pair<Float, Float> {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = hypot(dx, dy)
        if (len < 1e-4f) return Pair(1f, 0f)
        return Pair(dx / len, dy / len)
    }
}