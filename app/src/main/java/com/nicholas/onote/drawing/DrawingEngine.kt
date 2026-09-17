package com.nicholas.onote.drawing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nicholas.onote.data.PageSource
import kotlin.math.hypot

/**
 * Holds all drawing state: the in-progress stroke, finalized strokes, a
 * lightweight command history (strokes added, erased, cleared — all undoable),
 * the eraser state and the page appearance.
 *
 * Only low-frequency settings ([palmRejection], [debugEnabled], [toolMode],
 * [pageBackground]) are Compose state so toolbar controls recompose; the hot
 * per-pointer-event path stays free of Compose state.
 */
class DrawingEngine {

    val transform = CameraTransform()

    var palmRejection by mutableStateOf(true)
    var debugEnabled by mutableStateOf(true)
    var toolMode by mutableStateOf(ToolMode.PEN)
    var pageBackground by mutableStateOf(PageBackground.RULED)
    var paperColor: Int = 0xFFFFFFFF.toInt()

    /** The desk surface surrounding the paper page in "Pages" mode. */
    var deskColor: Int = 0xFF9A9A9A.toInt()
    var eraseRadius = 26f

    /**
     * When true ("Pages" notebooks running a vertically stacked flow), the
     * shared camera transform is never replaced by [applyDocument]/[snapshotTo]
     * — it belongs to the whole flow, not to individual pages.
     */
    var preserveCamera: Boolean = false

    // Pen appearance.
    var activeColor: Int = 0xFF1A1A1A.toInt()
    var activeWidth: Float = 4f
    var activeTool: Tool = Tool.BALLPOINT

    private val _strokes = ArrayList<CompletedStroke>()
    val strokes: List<CompletedStroke> get() = _strokes
    val strokeCount: Int get() = _strokes.size

    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Bumped on every change to the undo/redo stacks so toolbar buttons update. */
    var editCount by mutableStateOf(0)
        private set

    var activeStroke: ActiveStroke? = null
        private set

    // Eraser bookkeeping.
    private val pendingErase = LinkedHashSet<CompletedStroke>()
    var isErasing = false
        private set

    /** Invoked whenever the page content changes (stroke added/erased/cleared). */
    var onChanged: () -> Unit = {}

    /** Replaces this engine's page with [source]'s strokes, paper and camera. */
    fun applyDocument(source: PageSource) {
        _strokes.clear()
        _strokes.addAll(source.strokes)
        pageBackground = source.pageBackground
        if (!preserveCamera) {
            transform.apply(source.cameraZoom, source.cameraOffsetX, source.cameraOffsetY)
        }
        undoStack.clear()
        redoStack.clear()
        editCount++
    }

    /** Copies the current page (strokes, paper, camera) back into [source]. */
    fun snapshotTo(source: PageSource) {
        source.strokes.clear()
        source.strokes.addAll(_strokes)
        source.pageBackground = pageBackground
        if (!preserveCamera) {
            source.cameraZoom = transform.zoom
            source.cameraOffsetX = transform.offsetX
            source.cameraOffsetY = transform.offsetY
        }
    }

    fun beginStroke(x: Float, y: Float, pressure: Float, timestamp: Long) {
        val stroke = ActiveStroke(activeColor, activeWidth, activeTool)
        stroke.points.add(StrokePoint(x, y, pressure, timestamp))
        activeStroke = stroke
    }

    fun addPoint(x: Float, y: Float, pressure: Float, timestamp: Long) {
        activeStroke?.points?.add(StrokePoint(x, y, pressure, timestamp))
    }

    fun endStroke() {
        val stroke = activeStroke ?: return
        val completed =
            CompletedStroke(stroke.color, stroke.points, stroke.baseWidth, stroke.tool)
        _strokes.add(completed)
        undoStack.addLast(Edit.Add(completed))
        redoStack.clear()
        activeStroke = null
        editCount++
        onChanged()
    }

    fun cancelStroke() {
        activeStroke = null
    }

    fun beginErase() {
        isErasing = true
        pendingErase.clear()
    }

    /**
     * Every stroke with a point within [eraseRadius] of (x, y) is removed
     * immediately (stroke eraser) so the user sees results in real time; the
     * removal becomes a single undoable edit when the eraser pointer lifts.
     */
    fun addErasePoint(x: Float, y: Float) {
        val r2 = eraseRadius * eraseRadius
        for (stroke in _strokes) {
            if (stroke in pendingErase) continue
            var hit = false
            for (p in stroke.points) {
                val dx = p.x - x
                val dy = p.y - y
                if (dx * dx + dy * dy <= r2) {
                    hit = true
                    break
                }
            }
            if (hit) pendingErase.add(stroke)
        }
        if (pendingErase.isNotEmpty()) {
            _strokes.removeAll(pendingErase)
        }
    }

    fun endErase() {
        isErasing = false
        if (pendingErase.isNotEmpty()) {
            undoStack.addLast(Edit.Remove(pendingErase.toList()))
            redoStack.clear()
            pendingErase.clear()
            editCount++
            onChanged()
        }
    }

    fun cancelErase() {
        isErasing = false
        pendingErase.clear()
    }

    /**
     * Ends the pen stroke, but first checks whether it was actually a quick
     * scribble-out gesture (rapid back-and-forth, little net travel, dense
     * reversals). A recognisable scribble erases itself plus every stroke it
     * passes within [radius] of — recorded as a single undoable action — so
     * ordinary writing is never misread as an erase.
     */
    fun endStrokeOrScribble(radius: Float) {
        val stroke = activeStroke ?: return
        val points = stroke.points
        if (isScribbleGesture(points) && penHitsInk(points, radius)) {
            val completed =
                CompletedStroke(stroke.color, points, stroke.baseWidth, stroke.tool)
            _strokes.add(completed)
            val removed = ArrayList<CompletedStroke>()
            for (existing in _strokes) {
                if (existing === completed || strokeHitsPoints(existing, points, radius)) {
                    removed.add(existing)
                }
            }
            _strokes.removeAll(removed)
            undoStack.addLast(Edit.Remove(removed))
            redoStack.clear()
            activeStroke = null
            editCount++
            onChanged()
            return
        }
        endStroke()
    }

    /**
     * A scribble is quick, self-contained zig-zag: many direction reversals
     * per unit of length, ending near where it started, staying in a compact
     * region. Handwriting — even cursive "n"s, "m"s and "he" runs — travels
     * too far and turns too rarely to qualify.
     */
    private fun isScribbleGesture(points: List<StrokePoint>): Boolean {
        if (points.size < 16) return false
        val dt = points.last().timestamp - points.first().timestamp
        if (dt < 0 || dt > 900L) return false

        var pathLen = 0f
        var flips = 0
        var prevDx = 0
        var prevDy = 0
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE

        for (i in 1 until points.size) {
            val dx = points[i].x - points[i - 1].x
            val dy = points[i].y - points[i - 1].y
            pathLen += hypot(dx, dy)
            flips = countFlip(prevDx, dx, flips) { prevDx = it }
            flips = countFlip(prevDy, dy, flips) { prevDy = it }
            if (points[i].x < minX) minX = points[i].x
            if (points[i].y < minY) minY = points[i].y
            if (points[i].x > maxX) maxX = points[i].x
            if (points[i].y > maxY) maxY = points[i].y
        }

        val first = points.first()
        val last = points.last()
        val net = hypot(last.x - first.x, last.y - first.y)
        val width = maxX - minX
        val height = maxY - minY
        if (pathLen < 50f || flips < 6) return false
        return net < pathLen * 0.30f &&
            width < pathLen * 0.75f &&
            height < pathLen * 0.30f &&
            flips * 18f >= pathLen
    }

    /** Counts a left/right (or up/down) direction reversal for delta [d]. */
    private fun countFlip(prev: Int, d: Float, count: Int, update: (Int) -> Unit): Int {
        val dir = if (d > 1.2f) 1 else if (d < -1.2f) -1 else 0
        if (dir != 0) {
            if (prev != 0 && dir != prev) return count + 1
            update(dir)
        }
        return count
    }

    private fun strokeHitsPoints(
        stroke: CompletedStroke,
        points: List<StrokePoint>,
        radius: Float
    ): Boolean {
        val r2 = radius * radius
        for (p in stroke.points) {
            for (s in points) {
                val dx = p.x - s.x
                val dy = p.y - s.y
                if (dx * dx + dy * dy <= r2) return true
            }
        }
        return false
    }

    /** True only when the gesture actually crosses existing ink. */
    private fun penHitsInk(points: List<StrokePoint>, radius: Float): Boolean {
        val r2 = radius * radius
        for (other in _strokes) {
            for (p in other.points) {
                for (s in points) {
                    val dx = p.x - s.x
                    val dy = p.y - s.y
                    if (dx * dx + dy * dy <= r2) return true
                }
            }
        }
        return false
    }

    fun undo() {
        if (activeStroke != null || isErasing) return
        if (undoStack.isNotEmpty()) {
            val edit = undoStack.removeLast()
            edit.revert(this)
            redoStack.addLast(edit)
            editCount++
            onChanged()
        }
    }

    fun redo() {
        if (activeStroke != null || isErasing) return
        if (redoStack.isNotEmpty()) {
            val edit = redoStack.removeLast()
            edit.apply(this)
            undoStack.addLast(edit)
            editCount++
            onChanged()
        }
    }

    fun clear() {
        if (activeStroke != null || isErasing) return
        if (_strokes.isEmpty()) return
        undoStack.addLast(Edit.Remove(_strokes.toList()))
        redoStack.clear()
        _strokes.clear()
        editCount++
        onChanged()
    }

    private sealed class Edit {
        abstract fun apply(engine: DrawingEngine)
        abstract fun revert(engine: DrawingEngine)

        class Add(val stroke: CompletedStroke) : Edit() {
            override fun apply(engine: DrawingEngine) {
                engine._strokes.add(stroke)
            }

            override fun revert(engine: DrawingEngine) {
                engine._strokes.remove(stroke)
            }
        }

        class Remove(val strokes: List<CompletedStroke>) : Edit() {
            override fun apply(engine: DrawingEngine) {
                engine._strokes.removeAll(strokes.toSet())
            }

            override fun revert(engine: DrawingEngine) {
                engine._strokes.addAll(strokes)
            }
        }
    }
}