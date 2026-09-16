package com.nicholas.onote.drawing

import android.graphics.Path

enum class Tool {
    BALLPOINT,
    PENCIL,
    FOUNTAIN,
    MARKER,
    HIGHLIGHTER
}

enum class ToolMode(val displayName: String) {
    PEN("Pen"),
    ERASER("Eraser")
}

enum class PageBackground(val displayName: String) {
    BLANK("Blank"),
    RULED("Ruled"),
    GRAPH("Graph"),
    DOT("Dot Grid")
}

/**
 * A single sampled point of a stroke in document coordinates.
 */
data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val timestamp: Long
)

/**
 * The stroke being built right now. Kept separate from completed strokes so the
 * drawing layer can re-render it cheaply on every pointer move.
 */
class ActiveStroke(
    val color: Int,
    val baseWidth: Float,
    val tool: Tool
) {
    val points = ArrayList<StrokePoint>()
}

class Dot(
    val x: Float,
    val y: Float,
    val radius: Float
)

/**
 * A finalized stroke. [points] are kept (needed for erasing, selection,
 * persistence) while [ribbon] caches the rendered polygon so redraws never
 * recompute it.
 */
class CompletedStroke(
    val color: Int,
    val points: ArrayList<StrokePoint>,
    val baseWidth: Float
) {
    var ribbon: StrokeRenderer.Ribbon = StrokeRenderer.buildRibbon(points, baseWidth)
        private set

    fun rebuildRibbon() {
        ribbon = StrokeRenderer.buildRibbon(points, baseWidth)
    }
}