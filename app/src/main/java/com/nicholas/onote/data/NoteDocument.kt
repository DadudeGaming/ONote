package com.nicholas.onote.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nicholas.onote.drawing.CompletedStroke
import com.nicholas.onote.drawing.PageBackground
import kotlin.math.floor

/**
 * Infinite notebooks are one continuous page whose paper style can change at
 * any time. Pages notebooks are a set of fixed-size pages; each page picks its
 * paper style when it is created and keeps it.
 */
enum class PageMode(val displayName: String) {
    INFINITE("Infinite"),
    PAGES("Pages")
}

/** The physical orientation of a fixed-size page. */
enum class Orientation(val displayName: String) {
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape")
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
    override var pageBackground: PageBackground,
    var orientation: Orientation = Orientation.PORTRAIT
) : PageSource {
    override val strokes = ArrayList<CompletedStroke>()

    /** Imported images laid out on this page (page-local coordinates). */
    val images = ArrayList<PlacedImage>()

    override var cameraZoom = 1f
    override var cameraOffsetX = 0f
    override var cameraOffsetY = 0f

    /** Optional page heading shown at the top of the paper. */
    var title: String = ""

    /** Document-space size of this page given its [orientation]. */
    val width: Float
        get() = if (orientation == Orientation.PORTRAIT) PAGE_WIDTH else PAGE_HEIGHT
    val height: Float
        get() = if (orientation == Orientation.PORTRAIT) PAGE_HEIGHT else PAGE_WIDTH

    companion object {
        /** Fixed document-space page size (A4 aspect) for "Pages" notebooks. */
        const val PAGE_WIDTH = 990f
        const val PAGE_HEIGHT = 1400f

        /** Vertical gap between stacked pages in document space. */
        const val PAGE_GAP = 80f
    }
}

/**
 * Lays every fixed page out vertically one after another so the user scrolls
 * down through a notebook like a PDF. Page *i* occupies document y from
 * `[pageTop(i), pageTop(i) + pages[i].height]` — the layout follows each
 * page's own size, so landscape and portrait pages stack cleanly.
 */
object PageFlow {
    const val STRIDE = NotePage.PAGE_HEIGHT + NotePage.PAGE_GAP

    /** Document-space y of the top edge of page [index]. */
    fun pageTop(index: Int): Float = index * STRIDE

    /** Document-space y of the top edge of page [index] in [pages]. */
    fun pageTop(index: Int, pages: List<NotePage>): Float {
        if (pages.isEmpty()) return 0f
        var y = 0f
        for (i in 0 until minOf(index, pages.size)) {
            y += pages[i].height + NotePage.PAGE_GAP
        }
        return y
    }

    /** Total document-space height of the whole stack of [pages]. */
    fun totalHeight(pages: List<NotePage>): Float {
        if (pages.isEmpty()) return NotePage.PAGE_HEIGHT
        return pageTop(pages.size - 1, pages) + pages.last().height
    }

    /** Which page owns the document-space y coordinate. */
    fun indexForDocY(y: Float, count: Int): Int {
        if (count <= 1) return 0
        return floor(y / STRIDE).toInt().coerceIn(0, count - 1)
    }

    /** Which page owns the document-space y coordinate in [pages]. */
    fun indexForDocY(y: Float, pages: List<NotePage>): Int {
        if (pages.isEmpty()) return 0
        var acc = 0f
        for (i in pages.indices) {
            if (y < acc + pages[i].height) return i
            acc += pages[i].height + NotePage.PAGE_GAP
        }
        return pages.lastIndex
    }

    /** Largest page width in the stack, used for centering and clamping. */
    fun maxWidth(pages: List<NotePage>): Float =
        pages.maxOfOrNull { it.width } ?: NotePage.PAGE_WIDTH
}

/**
 * An imported image (or PDF/camera capture) placed on a page. Coordinates are
 * page-local: x/y from the page's top-left; strokes use doc-space x and
 * page-local y, so images render alongside them under the same clip without
 * extra math.
 */
class PlacedImage(
    val id: String,

    /** Internal file path (under the app's files dir) holding the bitmap. */
    val path: String,

    var x: Float = 0f,
    var y: Float = 0f,
    var w: Float = 320f,
    var h: Float = 320f
)

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

    /** Imported images on infinite notebooks (doc-space coordinates). */
    val images = ArrayList<PlacedImage>()

    override var pageBackground: PageBackground = PageBackground.RULED
    override var cameraZoom = 1f
    override var cameraOffsetX = 0f
    override var cameraOffsetY = 0f
    val pages = ArrayList<NotePage>()

    /** Defaults used when inserting a new page (set in Document settings). */
    var insertBackground: PageBackground = PageBackground.RULED
    var insertOrientation: Orientation = Orientation.PORTRAIT

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