package com.nicholas.onote.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Small hand-rolled vector icons so the toolbar stays dependency-free
 * (material-icons-* adds build weight and is only used for a handful of glyphs).
 */
object ONoteIcons {

    val UndoArrow = buildIcon(
        "ONoteIcons.Undo",
        "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 " +
            "3.16,-1.88 5.12,-1.88 3.54,0 6.55,2.31 7.6,5.5l2.37,-0.78" +
            "C21.08,11.03 17.15,8 12.5,8z"
    )

    val RedoArrow = buildIcon(
        "ONoteIcons.Redo",
        "M18.4,10.6C16.55,8.99 14.15,8 11.5,8c-4.65,0 -8.58,3.03 -9.96,7.22L3.9,16" +
            "c1.05,-3.19 4.05,-5.5 7.6,-5.5 1.95,0 3.73,0.72 5.12,1.88L13,16h9V7l-3.6,3.6z"
    )

    val Eraser = buildIcon(
        "ONoteIcons.Eraser",
        "M16.24,3.56l4.2,4.2c0.78,0.78 0.78,2.05 0,2.83l-9.66,9.66" +
            "c-0.78,0.78 -2.05,0.78 -2.83,0l-4.2,-4.2c-0.78,-0.78 -0.78,-2.05 0,-2.83" +
            "l9.66,-9.66C14.19,2.78 15.46,2.78 16.24,3.56zM18.3,9.63l-3.54,-3.54" +
            "L4.9,15.95l3.54,3.54L18.3,9.63z"
    )

    val Page = buildIcon(
        "ONoteIcons.Page",
        "M14,2H6C4.9,2 4,2.9 4,4v16c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V8L14,2z" +
            "M16,18H8v-2h8V18zM16,14H8v-2h8V14zM13,9V3.5L18.5,9H13z"
    )

    val Close = buildIcon(
        "ONoteIcons.Close",
        "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 " +
            "17.59,19 19,17.59 13.41,12z"
    )

    val Pen = buildIcon(
        "ONoteIcons.Pen",
        "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25z" +
            "M20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 " +
            "-1.02,-0.39 -1.41,0l-1.83,1.83l3.75,3.75l1.83,-1.83z"
    )

    val ChevronLeft = buildIcon(
        "ONoteIcons.ChevronLeft",
        "M15.41,7.41L14,6l-6,6 6,6 1.41,-1.41L10.83,12z"
    )

    val ChevronRight = buildIcon(
        "ONoteIcons.ChevronRight",
        "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z"
    )

    val Plus = buildIcon(
        "ONoteIcons.Plus",
        "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"
    )

    val Trash = buildIcon(
        "ONoteIcons.Trash",
        "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12z" +
            "M19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z"
    )

    val Home = buildIcon(
        "ONoteIcons.Home",
        "M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z"
    )

    val MoreVert = buildIcon(
        "ONoteIcons.MoreVert",
        "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2z" +
            "M12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z" +
            "M12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z"
    )

    private fun buildIcon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).addPath(
            pathData = addPathNodes(pathData),
            fill = SolidColor(Color.Black)
        ).build()
}