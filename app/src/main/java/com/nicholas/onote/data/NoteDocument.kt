package com.nicholas.onote.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nicholas.onote.drawing.CompletedStroke
import com.nicholas.onote.drawing.PageBackground

/**
 * Infinite notebooks are one continuous page whose paper style can change at
 * any time. Pages notebooks are a set of fixed-size pages; each page picks its
 * paper style when it is created and keeps it.
 */
enum class PageMode(val displayName: String) {
    INFINITE("Infinite"),
    PAGES("Pages")
}

/**
 * Anything a [com.nicholas.onote.drawing.DrawingEngine] can paint on: either a
 * whole infinite notebook or one fixed page.
 */
interface PageSource {
    val strokes: ArrayList<CompletedStroke>
    var pageBackground: PageBackground
    var cameraZoom: Float
    var cameraOffsetX: Float
    var cameraOffsetY: Float
}

class NotePage(
    val id: String,
    override var pageBackground: PageBackground
) : PageSource {
    override val strokes = ArrayList<CompletedStroke>()
    override var cameraZoom = 1f
    override var cameraOffsetX = 0f
    override var cameraOffsetY = 0f
}

/**
 * A saved notebook: metadata, page mode and either continuous ink ([strokes],
 * infinite notebooks) or a collection of [pages].
 */
class NoteDocument(
    val id: String,
    title: String,
    var createdAt: Long,
    var updatedAt: Long,
    var pageMode: PageMode = PageMode.INFINITE
) : PageSource {
    var title by mutableStateOf(title)
    override val strokes = ArrayList<CompletedStroke>()
    override var pageBackground: PageBackground = PageBackground.RULED
    override var cameraZoom = 1f
    override var cameraOffsetX = 0f
    override var cameraOffsetY = 0f
    val pages = ArrayList<NotePage>()

    fun touched() {
        updatedAt = System.currentTimeMillis()
    }

    fun sourceForPage(index: Int): PageSource =
        if (pageMode == PageMode.PAGES && pages.isNotEmpty()) {
            pages[index.coerceIn(0, pages.lastIndex)]
        } else {
            this
        }
}