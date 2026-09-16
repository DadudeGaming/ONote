package com.nicholas.onote.drawing

/**
 * Maps between document coordinates (stroke data lives here, never mutated by
 * zoom/pan) and screen coordinates.
 *
 *   screen = doc * zoom + offset
 */
class CameraTransform {

    var zoom = 1f
        private set
    var offsetX = 0f
        private set
    var offsetY = 0f
        private set

    fun docToScreenX(dx: Float): Float = dx * zoom + offsetX
    fun docToScreenY(dy: Float): Float = dy * zoom + offsetY
    fun screenToDocX(sx: Float): Float = (sx - offsetX) / zoom
    fun screenToDocY(sy: Float): Float = (sy - offsetY) / zoom

    fun pan(dx: Float, dy: Float) {
        offsetX += dx
        offsetY += dy
    }

    /**
     * Multiplies current zoom by [factor], keeping the document point under
     * (focalX, focalY) anchored in place.
     */
    fun zoomAt(factor: Float, focalX: Float, focalY: Float) {
        val target = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (target == zoom) return
        val k = target / zoom
        offsetX = focalX - (focalX - offsetX) * k
        offsetY = focalY - (focalY - offsetY) * k
        zoom = target
    }

    fun reset() {
        zoom = 1f
        offsetX = 0f
        offsetY = 0f
    }

    /** Applies an absolute gesture result (produced by the two-finger handler). */
    fun apply(zoom: Float, offsetX: Float, offsetY: Float) {
        this.zoom = zoom
        this.offsetX = offsetX
        this.offsetY = offsetY
    }

    companion object {
        const val MIN_ZOOM = 0.25f
        const val MAX_ZOOM = 6.0f
    }
}