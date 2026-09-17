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
 *  - With palm rejection enabled, fingers never draw; gestures (move / tap /
 *    double-tap / hold with 1-4 fingers) do whatever the [gestureAction] map
 *    says - see [GestureConfig] / AppSettings. A stylus never gestures.
 *  - With palm rejection disabled, any pointer draws like the original
 *    prototype.
 *
 * In "Pages" notebooks (`[pageFlowEnabled]`) all pages are stacked vertically;
 * [beginStroke] routes the pen to whichever page sits under the stylus and
 * records points in that page's local coordinate system. Page geometry is
 * provided by [pageTopOf]/[pageIndexAtY] so mixed orientations stack cleanly.
 *
 * Configurable finger gestures: whatever action is assigned to a gesture fires
 * through [onGestureAction]; the default map keeps 2-finger double-tap = undo,
 * 3-finger double-tap = redo, 3-finger long-press = page menu.
 *
 * Scribble-to-erase: a quick back-and-forth wiggle with the pen (≥ 10 points,
 * multiple reversals, little net travel) is treated as a "scribble erase" —
 * whatever ink the wiggle crosses is removed when the pen lifts.
 */
class StylusInputHandler(
    private val engine: DrawingEngine,
    private val invalidate: () -> Unit
) {

    enum class Mode { IDLE, TOOL, GESTURE }

    private val pointers = HashMap<Int, Int>() // pointerId -> toolType

    var strokePointerId = -1
        private set
    var mode = Mode.IDLE
        private set

    // ---- Page flow -----------------------------------------------------------

    /** True when this notebook is a fixed-size "Pages" notebook (vertical flow). */
    var pageFlowEnabled = false

    /** Number of pages in the flow; used to route a stroke to the right page. */
    var pageCount = 1

    /** The page the engine is currently hydrated from. */
    var flowIndex = 0

    /** Document-space y of page [i]'s top edge (set by the canvas). */
    var pageTopOf: ((Int) -> Float)? = null

    /** Which page owns document-space y (set by the canvas). */
    var pageIndexAtY: ((Float) -> Int)? = null

    /** Invoked when a pen stroke should start on a different page. */
    var onActivePageChanged: ((Int) -> Unit)? = null

    // ---- Configurable finger gestures ----------------------------------------

    /** Looks up the configured action for a gesture key (e.g. "dtap2"). */
    var gestureAction: ((String) -> GestureAction)? = null

    /** Fires when a gesture runs; the Int is the page index under the gesture. */
    var onGestureAction: ((GestureAction, Int) -> Unit)? = null

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

    // Multi-finger tap / hold / move bookkeeping.
    private val fingerOrigins = HashMap<Int, FloatArray>() // id -> [startX, startY]
    private var gestureDownTime = 0L
    private var gestureMaxFingers = 0
    private var gestureMaxDist = 0f
    private var gestureFocalX = 0f
    private var gestureFocalY = 0f

    // Active pan/zoom drag.
    private var dragActive = false
    private var dragMoveOnly = false

    private var lastTapFingers = 0
    private var lastTapTime = 0L
    private var holdFired = false
    private var holdGeneration = 0
    private var holdCheck: Runnable? = null
    private var pendingTapGeneration = 0
    private var pendingTapCheck: Runnable? = null
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
        } else {
            beginTouchGesture()
        }
    }

    private fun trackFingerDown(event: MotionEvent, idx: Int) {
        val id = event.getPointerId(idx)
        if (!fingerOrigins.containsKey(id)) {
            fingerOrigins[id] = floatArrayOf(event.getX(idx), event.getY(idx))
        }
        if (gestureDownTime == 0L) gestureDownTime = event.eventTime
        gestureMaxFingers = max(gestureMaxFingers, touchPointerIds().size)
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

        val count = touchPointerIds().size
        if (count > 4) {
            cancelFingerGesture()   // more fingers than we model: bail out
            return
        }
        if (mode != Mode.GESTURE) mode = Mode.GESTURE

        // A new finger arrives: the mapping may change (e.g. 1-finger pan
        // becomes 2-finger pan/zoom, or whatever the user assigned).
        val act = actionFor(FingerGesture.move(count)?.key ?: "")
        dragActive = act == GestureAction.MOVE_PAGE || act == GestureAction.PAN_ZOOM
        dragMoveOnly = act == GestureAction.MOVE_PAGE
        if (dragActive) beginDragAnchors(event)
        scheduleHold(count)
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
        if (mode == Mode.GESTURE) {
            val count = touchPointerIds().size
            if (count > 4) {
                cancelFingerGesture()
            } else if (dragActive) {
                val act = actionFor(FingerGesture.move(count)?.key ?: "")
                when {
                    act == GestureAction.PAN_ZOOM -> updateGesture(event)
                    act == GestureAction.MOVE_PAGE -> updatePanOnly(event)
                    else -> dragActive = false
                }
            } else if (count == 1 && gestureMaxDist > MOVE_SLOP_PX) {
                // A single finger moved: start a 1-finger pan if mapped to one.
                val act = actionFor(FingerGesture.move(1)?.key ?: "")
                if (act == GestureAction.MOVE_PAGE || act == GestureAction.PAN_ZOOM) {
                    dragActive = true
                    dragMoveOnly = true
                    beginDragAnchors(event)
                }
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
        fingerOrigins.remove(id)
        pointers.remove(id)

        if (mode == Mode.TOOL && strokePointerId == -1) {
            mode = Mode.IDLE
        }
        if (mode == Mode.GESTURE) {
            val count = touchPointerIds().size
            if (count == 0) {
                dragActive = false
                mode = Mode.IDLE
                evaluateTapEnd(event.eventTime, cancelled = false)
            } else if (dragActive) {
                val act = actionFor(FingerGesture.move(count)?.key ?: "")
                dragActive = act == GestureAction.MOVE_PAGE || act == GestureAction.PAN_ZOOM
                dragMoveOnly = act == GestureAction.MOVE_PAGE
            }
        }
    }

    private fun onActionUp(event: MotionEvent, cancelled: Boolean) {
        finishTool(cancelled)
        mode = Mode.IDLE
        evaluateTapEnd(event.eventTime, cancelled)
        pointers.clear()
        fingerOrigins.clear()
        gestureDownTime = 0L
        gestureMaxFingers = 0
        gestureMaxDist = 0f
        dragActive = false
        cancelHold()
        cancelPendingTap()
    }

    private fun cancelFingerGesture() {
        mode = Mode.IDLE
        dragActive = false
    }

    private fun finishTool(cancelled: Boolean) {
        if (strokePointerId < 0) return
        val eraser = erasing(null)
        if (cancelled) {
            if (eraser) engine.cancelErase() else engine.cancelStroke()
        } else if (eraser) {
            engine.endErase()
            Log.d(TAG, "erase end strokesRemovedRemaining=${engine.strokeCount}")
        } else {
            // Pen lift: decide between a normal stroke and scribble-to-erase.
            engine.endStrokeOrScribble(scribbleRadius)
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
        val top = pageTopOf?.invoke(flowIndex) ?: PageFlow.pageTop(flowIndex)
        return Pair(gx, gy - top)
    }

    private fun beginStroke(event: MotionEvent, idx: Int) {
        strokePointerId = event.getPointerId(idx)
        mode = Mode.TOOL
        val (gx, gy) = globalDoc(event, idx)
        if (pageFlowEnabled) {
            val target = pageIndexAtY?.invoke(gy)
                ?: PageFlow.indexForDocY(gy, pageCount)
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

    /** Erase radius for a scribble: at least the eraser size, scaled by pen. */
    private val scribbleRadius: Float
        get() = max(engine.eraseRadius, engine.activeWidth * 3f).coerceAtLeast(24f)

    private fun touchPointerIds(): List<Int> =
        pointers.filterValues { it != MotionEvent.TOOL_TYPE_STYLUS }.keys.toList()

    // ---- Finger tracking (move / tap / hold) ----------------------------------

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
        if (maxMove > HOLD_MOVE_PX) cancelHoldScheduling()
        val (fx, fy, _) = focalAndSpan(event)
        gestureFocalX = fx
        gestureFocalY = fy
    }

    private fun scheduleHold(fingers: Int) {
        val act = actionFor(FingerGesture.hold(fingers)?.key ?: "")
        if (act == GestureAction.NONE || holdFired) return
        val gen = ++holdGeneration
        val check = Runnable {
            if (gen != holdGeneration) return@Runnable
            if (holdFired) return@Runnable
            if (touchPointerIds().size != fingers || fingers > 4) return@Runnable
            if (gestureMaxDist >= HOLD_MOVE_PX) return@Runnable
            holdFired = true
            fireAction(act, engine.transform.screenToDocY(gestureFocalY))
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

    private fun schedulePendingTap(action: GestureAction, docY: Float) {
        val gen = ++pendingTapGeneration
        val check = Runnable {
            if (gen != pendingTapGeneration) return@Runnable
            fireAction(action, docY)
        }
        pendingTapCheck?.let { mainHandler.removeCallbacks(it) }
        pendingTapCheck = check
        mainHandler.postDelayed(check, DOUBLE_TAP_MS)
    }

    private fun cancelPendingTap() {
        pendingTapGeneration++
        pendingTapCheck?.let { mainHandler.removeCallbacks(it) }
    }

    private fun evaluateTapEnd(time: Long, cancelled: Boolean) {
        cancelHold()
        if (cancelled || stylusPointersActive() > 0) {
            lastTapFingers = 0
            lastTapTime = 0L
            return
        }
        val fingers = gestureMaxFingers
        val duration = time - gestureDownTime
        val move = gestureMaxDist
        val dragged = dragActive
        val fired = holdFired
        gestureMaxDist = 0f
        gestureMaxFingers = 0
        gestureDownTime = 0L
        dragActive = false
        holdFired = false

        if (fired || dragged || fingers !in 1..4 || duration > TAP_MAX_MS || move > TAP_MOVE_PX) {
            lastTapFingers = 0
            lastTapTime = 0L
            return
        }

        // A second tap with the same count = double-tap.
        if (lastTapFingers == fingers && time - lastTapTime <= DOUBLE_TAP_MS) {
            cancelPendingTap()
            val dt = actionFor(FingerGesture.doubleTap(fingers)?.key ?: "")
            if (dt != GestureAction.NONE) {
                fireAction(dt, engine.transform.screenToDocY(gestureFocalY))
            }
            lastTapFingers = 0
            lastTapTime = 0L
        } else {
            // First tap: fire the single-tap action after a short delay, so a
            // quick second tap can turn it into a double-tap instead.
            lastTapFingers = fingers
            lastTapTime = time
            val tapAct = actionFor(FingerGesture.tap(fingers)?.key ?: "")
            if (tapAct != GestureAction.NONE) {
                schedulePendingTap(tapAct, engine.transform.screenToDocY(gestureFocalY))
            }
        }
    }

    private fun actionFor(key: String): GestureAction =
        gestureAction?.invoke(key) ?: GestureAction.NONE

    private fun fireAction(action: GestureAction, docY: Float) {
        if (action == GestureAction.NONE) return
        val idx = pageIndexAtY?.invoke(docY)
            ?: PageFlow.indexForDocY(docY, pageCount)
        onGestureAction?.invoke(action, idx)
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

    private fun beginDragAnchors(event: MotionEvent) {
        val (fx, fy, avg) = focalAndSpan(event)
        mode = Mode.GESTURE
        startZoom = engine.transform.zoom
        startOffsetX = engine.transform.offsetX
        startOffsetY = engine.transform.offsetY
        startFocalX = fx
        startFocalY = fy
        startAvgSpan = avg
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

    /** Pan-only (no zoom) for the "Move page" action. */
    private fun updatePanOnly(event: MotionEvent) {
        val (fx, fy, _) = focalAndSpan(event)
        val transform = engine.transform
        val offX = startOffsetX + (fx - startFocalX)
        val offY = startOffsetY + (fy - startFocalY)
        transform.apply(transform.zoom, offX, offY)
    }

    private fun beginTouchGesture() {
        mode = Mode.GESTURE
        dragActive = false
        scheduleHold(1)
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
        private const val MOVE_SLOP_PX = 24f
    }
}