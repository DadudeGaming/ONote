package com.nicholas.onote.drawing

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import com.nicholas.onote.data.PageFlow
import kotlin.math.hypot
import kotlin.math.max

/**
 * Distinguishes S Pen from finger/palm input and drives the drawing engine.
 *
 * Palm rejection rules:
 *  - While any stylus is down, every touch pointer is ignored (a hand resting on
 *    the screen writes nothing).
 *  - With palm rejection enabled, one finger nothing-but-pan (when page flow is
 *    active), two fingers pan/zoom, three fingers hold-for-menu or double-tap.
 *    A stylus never gestures.
 *  - With palm rejection disabled, any pointer draws like the original
 *    prototype.
 *
 * In "Pages" notebooks (`[pageFlowEnabled]`) all pages are stacked vertically;
 * [beginStroke] routes the pen to whichever page sits under the stylus and
 * records points in that page's local coordinate system.
 *
 * Finger gestures (pages & infinite): 2-finger double-tap = undo,
 * 3-finger double-tap = redo, 3-finger long-press = page menu ([onLongPress3]).
 */
class StylusInputHandler(
    private val engine: DrawingEngine,
    private val invalidate: () -> Unit
) {

    enum class Mode { IDLE, TOOL, GESTURE, PAN }

    private val pointers = HashMap<Int, Int>() // pointerId -> toolType

    var strokePointerId = -1
        private set
    var mode = Mode.IDLE
        private set

    /**
     * When true (Pages notebooks with palm rejection on), a single finger
     * drags the page instead of doing nothing – giving a PDF-like scroll.
     */
    var panEnabled = false

    // ---- Page flow -----------------------------------------------------------

    /** True when this notebook is a fixed-size "Pages" notebook (vertical flow). */
    var pageFlowEnabled = false

    /** Number of pages in the flow; used to route a stroke to the right page. */
    var pageCount = 1

    /** The page the engine is currently hydrated from. */
    var flowIndex = 0

    /** Invoked when a pen stroke should start on a different page. */
    var onActivePageChanged: ((Int) -> Unit)? = null

    // ---- Finger gestures ------------------------------------------------------

    /** 2-finger double-tap → undo. */
    var onDoubleTap2: (() -> Unit)? = null

    /** 3-finger double-tap → redo. */
    var onDoubleTap3: (() -> Unit)? = null

    /** 3-finger long-press → page menu; the Int is the page index under the hold. */
    var onLongPress3: ((Int) -> Unit)? = null

    // One-finger pan anchors.
    private var panPointerId = -1
    private var lastPanX = 0f
    private var lastPanY = 0f

    /**
     * True while the S Pen side button is held with the tool on a pen:
     * "hold button -> temporarily erase, release -> back to pen".
     */
    var tempEraser = false
        private set

    // Gesture anchors captured when the gesture begins.
    private var startFocalX = 0f
    private var startFocalY = 0f
    private var startAvgSpan = 1f
    private var startZoom = 1f
    private var startOffsetX = 0f
    private var startOffsetY = 0f

    // Multi-finger tap / hold bookkeeping.
    private val fingerOrigins = HashMap<Int, FloatArray>() // id -> [startX, startY]
    private var gestureDownTime = 0L
    private var gestureMaxFingers = 0
    private var gestureMaxDist = 0f
    private var gestureFocalX = 0f
    private var gestureFocalY = 0f
    private var lastTapFingers = 0
    private var lastTapTime = 0L
    private var holdFired = false
    private var holdGeneration = 0
    private var holdCheck: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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

        if (tool != MotionEvent.TOOL_TYPE_STYLUS) {
            trackFingerDown(event, 0)
        }

        // S Pen always draws. With palm rejection off, fingers draw too.
        if (tool == MotionEvent.TOOL_TYPE_STYLUS || !engine.palmRejection) {
            beginStroke(event, 0)
        } else if (panEnabled && tool == MotionEvent.TOOL_TYPE_FINGER) {
            beginPan(event, 0)
        }
    }

    private fun trackFingerDown(event: MotionEvent, idx: Int) {
        val id = event.getPointerId(idx)
        if (!fingerOrigins.containsKey(id)) {
            fingerOrigins[id] = floatArrayOf(event.getX(idx), event.getY(idx))
        }
        if (gestureDownTime == 0L) gestureDownTime = event.eventTime
        gestureMaxFingers = max(gestureMaxFingers, touchPointerIds().size)
        if (gestureMaxFingers >= 3) scheduleHold()
    }

    private fun beginPan(event: MotionEvent, idx: Int) {
        panPointerId = event.getPointerId(idx)
        lastPanX = event.getX(idx)
        lastPanY = event.getY(idx)
        mode = Mode.PAN
    }

    private fun onActionPointerDown(event: MotionEvent) {
        val idx = event.actionIndex
        val id = event.getPointerId(idx)
        val tool = event.getToolType(idx)
        pointers[id] = tool

        if (tool != MotionEvent.TOOL_TYPE_STYLUS) {
            trackFingerDown(event, idx)
        }

        if (strokePointerId >= 0) return    // stylus is drawing; this is a palm
        if (!engine.palmRejection) return   // legacy all-fingers-draw mode
        if (tool == MotionEvent.TOOL_TYPE_STYLUS) {
            // Pen joins while a finger is panning/gesturing: the pen takes over.
            cancelFingerGesture()
            cancelHoldScheduling()
            beginStroke(event, idx)
            return
        }

        when (touchPointerIds().size) {
            2 -> startGesture(event)
            else -> cancelFingerGesture()   // 3+ fingers: hold / double-tap only
        }
    }

    private fun onActionMove(event: MotionEvent) {
        if (strokePointerId >= 0) {
            val idx = event.findPointerIndex(strokePointerId)
            if (idx >= 0) {
                val (gx, gy) = globalDoc(event, idx)
                val (lx, ly) = toLocal(gx, gy)
                if (erasing(event)) {
                    engine.addErasePoint(lx, ly)
                } else {
                    engine.addPoint(lx, ly, event.getPressure(idx), event.eventTime)
                }
            }
        }
        trackFingerMove(event)
        if (mode == Mode.PAN) {
            if (stylusPointersActive() > 0) {
                endPan()
            } else {
                val idx = event.findPointerIndex(panPointerId)
                if (idx >= 0) {
                    val x = event.getX(idx)
                    val y = event.getY(idx)
                    engine.transform.pan(x - lastPanX, y - lastPanY)
                    lastPanX = x
                    lastPanY = y
                }
            }
        }
        if (mode == Mode.GESTURE) {
            if (touchPointerIds().size >= 3) {
                cancelFingerGesture()
            } else {
                updateGesture(event)
            }
        }
        updateMoveStats(event.eventTime)
    }

    private fun onActionPointerUp(event: MotionEvent) {
        val idx = event.actionIndex
        val id = event.getPointerId(idx)

        if (id == strokePointerId) {
            finishTool(false)
        }
        if (id == panPointerId) {
            endPan()
        }
        fingerOrigins.remove(id)
        pointers.remove(id)

        if (mode == Mode.GESTURE && touchPointerIds().size < 2) {
            mode = Mode.IDLE
        }
        if (mode == Mode.TOOL && strokePointerId == -1) {
            mode = Mode.IDLE
        }

        // When the final finger lifts, evaluate whether the touch was a tap.
        if (touchPointerIds().isEmpty()) {
            evaluateTapEnd(event.eventTime, cancelled = false)
        }
    }

    private fun onActionUp(event: MotionEvent, cancelled: Boolean) {
        finishTool(cancelled)
        endPan()
        mode = Mode.IDLE
        evaluateTapEnd(event.eventTime, cancelled)
        pointers.clear()
        fingerOrigins.clear()
        gestureDownTime = 0L
        gestureMaxFingers = 0
        gestureMaxDist = 0f
        cancelHold()
    }

    private fun endPan() {
        panPointerId = -1
        if (mode == Mode.PAN) mode = Mode.IDLE
    }

    private fun cancelFingerGesture() {
        endPan()
        mode = Mode.IDLE
    }

    private fun finishTool(cancelled: Boolean) {
        if (strokePointerId < 0) return
        val eraser = erasing(null)
        if (cancelled) {
            if (eraser) engine.cancelErase() else engine.cancelStroke()
        } else {
            if (eraser) engine.endErase() else engine.endStroke()
        }
        if (eraser) {
            Log.d(TAG, "erase end strokesRemovedRemaining=${engine.strokeCount}")
        }
        tempEraser = false
        strokePointerId = -1
        mode = Mode.IDLE
    }

    /** True if the current stroke should erase (tool is eraser, or button held). */
    private fun erasing(event: MotionEvent?): Boolean {
        if (engine.toolMode == ToolMode.ERASER) return true
        if (tempEraser) return true
        if (event != null && stylusButtonHeld(event)) {
            tempEraser = true
            return true
        }
        return false
    }

    private fun stylusButtonHeld(event: MotionEvent): Boolean =
        event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY) ||
            (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0

    // ---- Page-flow routing ----------------------------------------------------

    /** Screen coords -> shared document-space coords. */
    private fun globalDoc(event: MotionEvent, idx: Int): Pair<Float, Float> {
        val t = engine.transform
        return Pair(t.screenToDocX(event.getX(idx)), t.screenToDocY(event.getY(idx)))
    }

    /** Global doc coords -> the active page's local coords. */
    private fun toLocal(gx: Float, gy: Float): Pair<Float, Float> {
        if (!pageFlowEnabled) return Pair(gx, gy)
        return Pair(gx, gy - PageFlow.pageTop(flowIndex))
    }

    private fun beginStroke(event: MotionEvent, idx: Int) {
        strokePointerId = event.getPointerId(idx)
        mode = Mode.TOOL
        val (gx, gy) = globalDoc(event, idx)
        if (pageFlowEnabled) {
            val target = PageFlow.indexForDocY(gy, pageCount)
            if (target != flowIndex) {
                flowIndex = target
                onActivePageChanged?.invoke(target)
            }
        }
        val (lx, ly) = toLocal(gx, gy)
        if (erasing(event)) {
            engine.beginErase()
            engine.addErasePoint(lx, ly)
        } else {
            engine.beginStroke(lx, ly, event.getPressure(idx), event.eventTime)
        }
        Log.d(
            TAG,
            "tool start mode=${engine.toolMode.name} tool=${toolName(event.getToolType(idx))} " +
                "p=${String.format("%.2f", event.getPressure(idx))}" +
                if (tempEraser) " buttonEraser" else ""
        )
    }

    private fun touchPointerIds(): List<Int> =
        pointers.filterValues { it != MotionEvent.TOOL_TYPE_STYLUS }.keys.toList()

    // ---- Finger tracking (tap / hold) ---------------------------------------

    private fun trackFingerMove(event: MotionEvent) {
        if (fingerOrigins.isEmpty()) return
        var maxMove = 0f
        for ((id, origin) in fingerOrigins) {
            val pi = event.findPointerIndex(id)
            if (pi >= 0) {
                val d = hypot(event.getX(pi) - origin[0], event.getY(pi) - origin[1])
                if (d > maxMove) maxMove = d
            }
        }
        gestureMaxDist = max(gestureMaxDist, maxMove)
        if (gestureMaxFingers >= 3 && maxMove > HOLD_MOVE_PX) cancelHoldScheduling()
        if (gestureMaxFingers >= 3) {
            val (fx, fy, _) = focalAndSpan(event)
            gestureFocalX = fx
            gestureFocalY = fy
        }
    }

    private fun scheduleHold() {
        if (holdFired || !pageFlowEnabled) return
        val gen = ++holdGeneration
        val check = Runnable {
            if (gen != holdGeneration) return@Runnable
            if (holdFired) return@Runnable
            if (gestureMaxFingers >= 3 && touchPointerIds().size >= 3 &&
                gestureMaxDist < HOLD_MOVE_PX
            ) {
                holdFired = true
                val t = engine.transform
                val docY = t.screenToDocY(gestureFocalY)
                val idx = PageFlow.indexForDocY(docY, pageCount)
                onLongPress3?.invoke(idx)
            }
        }
        holdCheck?.let { mainHandler.removeCallbacks(it) }
        holdCheck = check
        mainHandler.postDelayed(check, HOLD_DELAY_MS)
    }

    private fun cancelHoldScheduling() {
        holdGeneration++
        holdCheck?.let { mainHandler.removeCallbacks(it) }
    }

    private fun cancelHold() {
        holdGeneration++
        holdCheck?.let { mainHandler.removeCallbacks(it) }
    }

    private fun evaluateTapEnd(time: Long, cancelled: Boolean) {
        cancelHold()
        if (cancelled || stylusPointersActive() > 0) {
            lastTapFingers = 0
            return
        }
        val fingers = gestureMaxFingers
        val duration = time - gestureDownTime
        val move = gestureMaxDist
        gestureMaxDist = 0f
        gestureMaxFingers = 0
        gestureDownTime = 0L
        val fired = holdFired
        holdFired = false

        if (fired || fingers < 2 || duration > TAP_MAX_MS || move > TAP_MOVE_PX) {
            lastTapFingers = 0
            return
        }

        if (lastTapFingers == fingers && time - lastTapTime <= DOUBLE_TAP_MS) {
            when (fingers) {
                2 -> onDoubleTap2?.invoke()
                3 -> onDoubleTap3?.invoke()
            }
            lastTapFingers = 0
            lastTapTime = 0L
        } else {
            lastTapFingers = fingers
            lastTapTime = time
        }
    }

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
        panPointerId = -1
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
        private const val TAP_MAX_MS = 350L
        private const val DOUBLE_TAP_MS = 300L
        private const val HOLD_DELAY_MS = 600L
        private const val TAP_MOVE_PX = 28f
        private const val HOLD_MOVE_PX = 28f
    }
}