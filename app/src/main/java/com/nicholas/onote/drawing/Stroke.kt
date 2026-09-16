package com.nicholas.onote.drawing

import android.graphics.Path

enum class Tool {
    BALLPOINT,
    PENCIL,
    FOUNTAIN,
    MARKER,
    HIGHLIGHTER
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
 * A finalized stroke with its ribbon path cached so redraws never recompute the
 * polygon. `startDot`/`endDot` are the round caps rendered as circles.
 */
class CompletedStroke(
    val color: Int,
    val path: Path,
    val startDot: Dot?,
    val endDot: Dot?
)