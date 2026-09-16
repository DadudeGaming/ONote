package com.nicholas.onote.drawing

import android.util.Log
import android.view.MotionEvent
import kotlin.math.hypot

/**
 * Distinguishes S Pen from finger/palm input and drives the drawing engine.
 *
 * Palm rejection rules:
 *  - While any stylus is down, every touch pointer is ignored (a hand resting on
 *    the screen writes nothing).
 *  - With palm rejection enabled, one finger does nothing; two or more fingers
 *    pan/zoom. A stylus never gestures.
 *  - With palm rejection disabled, any pointer draws like the original
 *    prototype.
 */
class StylusInputHandler(
    private val engine: DrawingEngine,
    private val invalidate: () -> Unit
) {

    enum class Mode { IDLE, STROKE, GESTURE }

    private val pointers = HashMap<Int, Int>() // pointerId -> toolType

    var strokePointerId = -1
        private set
    var mode = Mode.IDLE
        private set

    // Gesture anchors captured when the gesture begins.
    private var startFocalX = 0f
    private var startFocalY = 0f
    private var startAvgSpan = 1f
    private var startZoom = 1f
    private var startOffsetX = 0f
    private var startOffsetY = 0f

    // Debug instrumentation.
    var totalEvents = 0
        private set
    var moveEvents = 0
        private set
    var avgMoveIntervalMs = 0f
        private set
    private var lastMoveTime = 0L
    private val intervalWindow = FloatArray(32)
    private var windowIdx = 0
    private var windowCount = 0

    fun handleEvent(event: MotionEvent): Boolean {
        totalEvents++
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onActionDown(event)
            MotionEvent.ACTION_POINTER_DOWN -> onActionPointerDown(event)
            MotionEvent.ACTION_MOVE -> onActionMove(event)
            MotionEvent.ACTION_POINTER_UP -> onActionPointerUp(event)
            MotionEvent.ACTION_UP -> onActionUp(event, cancelled = false)
            MotionEvent.ACTION_CANCEL -> onActionUp(event, cancelled = true)
        }
        invalidate()
        return true
    }

    fun stylusPointersActive(): Int =
        pointers.count { it.value == MotionEvent.TOOL_TYPE_STYLUS }

    fun touchPointersActive(): Int =
        pointers.count { it.value != MotionEvent.TOOL_TYPE_STYLUS }

    private fun onActionDown(event: MotionEvent) {
        val id = event.getPointerId(0)
        val tool = event.getToolType(0)
        pointers[id] = tool

        // S Pen always draws. With palm rejection off, fingers draw too.
        if (tool == MotionEvent.TOOL_TYPE_STYLUS || !engine.palmRejection) {
            beginStroke(event, 0)
        }
    }

    private fun onActionPointerDown(event: MotionEvent) {
        val idx = event.actionIndex
        val id = event.getPointerId(idx)
        val tool = event.getToolType(idx)
        pointers[id] = tool

        if (strokePointerId >= 0) return    // stylus is drawing; this is a palm
        if (!engine.palmRejection) return   // legacy all-fingers-draw mode
        if (tool == MotionEvent.TOOL_TYPE_STYLUS) return

        if (touchPointerIds().size >= 2) {
            startGesture(event)
        }
    }

    private fun onActionMove(event: MotionEvent) {
        if (strokePointerId >= 0) {
            val idx = event.findPointerIndex(strokePointerId)
            if (idx >= 0) {
                engine.addPoint(
                    event.getX(idx), event.getY(idx), event.getPressure(idx), event.eventTime
                )
            }
        }
        if (mode == Mode.GESTURE) {
            updateGesture(event)
        }
        updateMoveStats(event.eventTime)
    }

    private fun onActionPointerUp(event: MotionEvent) {
        val idx = event.actionIndex
        val id = event.getPointerId(idx)

        if (id == strokePointerId) {
            engine.endStroke()
            strokePointerId = -1
        }
        pointers.remove(id)

        if (mode == Mode.GESTURE && touchPointerIds().size < 2) {
            mode = Mode.IDLE
        }
        if (mode == Mode.STROKE && strokePointerId == -1) {
            mode = Mode.IDLE
        }
    }

    private fun onActionUp(event: MotionEvent, cancelled: Boolean) {
        if (strokePointerId >= 0) {
            if (cancelled) engine.cancelStroke() else engine.endStroke()
            strokePointerId = -1
        }
        mode = Mode.IDLE
        pointers.clear()
    }

    private fun beginStroke(event: MotionEvent, idx: Int) {
        strokePointerId = event.getPointerId(idx)
        mode = Mode.STROKE
        engine.beginStroke(
            event.getX(idx), event.getY(idx), event.getPressure(idx), event.eventTime
        )
        Log.d(
            TAG,
            "stroke start tool=${toolName(event.getToolType(idx))} " +
                "p=${String.format("%.2f", event.getPressure(idx))} " +
                "t=${System.currentTimeMillis()}"
        )
    }

    private fun touchPointerIds(): List<Int> =
        pointers.filterValues { it != MotionEvent.TOOL_TYPE_STYLUS }.keys.toList()

    /**
     * Average position and mean distance-to-center of every touch pointer
     * currently reported by [event]. Using the centroid makes arbitrary
     * finger counts behave predictably for pan/zoom.
     */
    private fun focalAndSpan(event: MotionEvent): Triple<Float, Float, Float> {
        var fx = 0f
        var fy = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            val id = event.getPointerId(i)
            if (pointers[id] != null && pointers[id] != MotionEvent.TOOL_TYPE_STYLUS) {
                fx += event.getX(i)
                fy += event.getY(i)
                count++
            }
        }
        if (count == 0) return Triple(0f, 0f, 0f)
        fx /= count
        fy /= count
        var sum = 0f
        for (i in 0 until event.pointerCount) {
            val id = event.getPointerId(i)
            if (pointers[id] != null && pointers[id] != MotionEvent.TOOL_TYPE_STYLUS) {
                sum += hypot(event.getX(i) - fx, event.getY(i) - fy)
            }
        }
        return Triple(fx, fy, sum / count)
    }

    private fun startGesture(event: MotionEvent) {
        val (fx, fy, avg) = focalAndSpan(event)
        if (avg < 1e-3f) return
        mode = Mode.GESTURE
        startZoom = engine.transform.zoom
        startOffsetX = engine.transform.offsetX
        startOffsetY = engine.transform.offsetY
        startFocalX = fx
        startFocalY = fy
        startAvgSpan = avg
        Log.d(TAG, "gesture start fingers=${touchPointerIds().size} zoom=$startZoom")
    }

    private fun updateGesture(event: MotionEvent) {
        val (fx, fy, avg) = focalAndSpan(event)
        if (avg < 1e-3f || startAvgSpan < 1e-3f) return

        val k = avg / startAvgSpan
        val newZoom = (startZoom * k).coerceIn(
            CameraTransform.MIN_ZOOM, CameraTransform.MAX_ZOOM
        )
        val docFocalX = (startFocalX - startOffsetX) / startZoom
        val docFocalY = (startFocalY - startOffsetY) / startZoom

        val transform = engine.transform
        transform.apply(newZoom, fx - docFocalX * newZoom, fy - docFocalY * newZoom)
    }

    private fun updateMoveStats(time: Long) {
        if (lastMoveTime != 0L && time > lastMoveTime) {
            intervalWindow[windowIdx] = (time - lastMoveTime).toFloat()
            windowIdx = (windowIdx + 1) % intervalWindow.size
            if (windowCount < intervalWindow.size) windowCount++
        }
        lastMoveTime = time
        moveEvents++
        if (windowCount > 0) {
            var sum = 0f
            for (i in 0 until windowCount) sum += intervalWindow[i]
            avgMoveIntervalMs = sum / windowCount
        }
    }

    private fun toolName(tool: Int): String = when (tool) {
        MotionEvent.TOOL_TYPE_STYLUS -> "stylus"
        MotionEvent.TOOL_TYPE_ERASER -> "eraser"
        MotionEvent.TOOL_TYPE_FINGER -> "finger"
        else -> "unknown($tool)"
    }

    companion object {
        private const val TAG = "oNote.Input"
    }
}