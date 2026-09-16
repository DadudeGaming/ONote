package com.nicholas.onote.drawing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
    var eraseRadius = 26f

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

    var activeStroke: ActiveStroke? = null
        private set

    // Eraser bookkeeping.
    private val pendingErase = LinkedHashSet<CompletedStroke>()
    var isErasing = false
        private set

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
        val completed = CompletedStroke(stroke.color, stroke.points, stroke.baseWidth)
        _strokes.add(completed)
        undoStack.addLast(Edit.Add(completed))
        redoStack.clear()
        activeStroke = null
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
        }
    }

    fun cancelErase() {
        isErasing = false
        pendingErase.clear()
    }

    fun undo() {
        if (activeStroke != null || isErasing) return
        if (undoStack.isNotEmpty()) {
            val edit = undoStack.removeLast()
            edit.revert(this)
            redoStack.addLast(edit)
        }
    }

    fun redo() {
        if (activeStroke != null || isErasing) return
        if (redoStack.isNotEmpty()) {
            val edit = redoStack.removeLast()
            edit.apply(this)
            undoStack.addLast(edit)
        }
    }

    fun clear() {
        if (activeStroke != null || isErasing) return
        if (_strokes.isEmpty()) return
        undoStack.addLast(Edit.Remove(_strokes.toList()))
        redoStack.clear()
        _strokes.clear()
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