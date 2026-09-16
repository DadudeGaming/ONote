package com.nicholas.onote.drawing

/**
 * Holds the drawing state: the in-progress stroke, finalized strokes and the
 * undo/redo stacks. Kept deliberately free of Compose state so the canvas view
 * can mutate it directly on every pointer event without recomposition.
 */
class DrawingEngine {

    val transform = CameraTransform()

    var palmRejection = true
    var debugEnabled = true

    var activeColor: Int = 0xFF1A1A1A.toInt()
    var activeWidth: Float = 4f
    var activeTool: Tool = Tool.BALLPOINT

    private val _strokes = ArrayList<CompletedStroke>()
    private val _redo = ArrayDeque<CompletedStroke>()

    val strokes: List<CompletedStroke> get() = _strokes
    val canRedo: Boolean get() = _redo.isNotEmpty()
    val strokeCount: Int get() = _strokes.size

    var activeStroke: ActiveStroke? = null
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
        val ribbon = StrokeRenderer.buildRibbon(stroke.points, stroke.baseWidth)
        _strokes.add(CompletedStroke(stroke.color, ribbon.path, ribbon.startDot, ribbon.endDot))
        activeStroke = null
        _redo.clear()
    }

    fun cancelStroke() {
        activeStroke = null
    }

    fun undo() {
        if (activeStroke != null) return
        if (_strokes.isNotEmpty()) {
            _redo.addLast(_strokes.removeAt(_strokes.lastIndex))
        }
    }

    fun redo() {
        if (_redo.isNotEmpty()) {
            _strokes.add(_redo.removeLast())
        }
    }

    fun clear() {
        activeStroke = null
        _strokes.clear()
        _redo.clear()
    }
}