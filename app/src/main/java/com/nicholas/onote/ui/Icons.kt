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

    val Download = buildIcon(
        "ONoteIcons.Download",
        "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z"
    )

val Settings = buildIcon(
        "ONoteIcons.Settings",
        "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94 0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6 3.6,1.62 3.6,3.6 -1.62,3.6 -3.6,3.6z"
    )

    val Image = buildIcon(
        "ONoteIcons.Image",
        "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5C3.9,3 3,3.9 3,5v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2z" +
            "M8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z"
    )

    val Camera = buildIcon(
        "ONoteIcons.Camera",
        "M12,15.2c1.77,0 3.2,-1.43 3.2,-3.2s-1.43,-3.2 -3.2,-3.2 -3.2,1.43 -3.2,3.2 1.43,3.2 3.2,3.2z" +
            "M9,2L7.17,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 " +
            "-0.9,-2 -2,-2h-3.17L15,2H9zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z"
    )

    val Cloud = buildIcon(
        "ONoteIcons.Cloud",
        "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96z"
    )

    val Share = buildIcon(
        "ONoteIcons.Share",
        "M18,16.08c-0.76,0 -1.44,0.3 -1.96,0.77L8.91,12.7c0.05,-0.23 0.09,-0.46 0.09,-0.7s-0.04,-0.47 " +
            "-0.09,-0.7l7.05,-4.11c0.54,0.5 1.25,0.81 2.04,0.81 1.66,0 3,-1.34 3,-3s-1.34,-3 -3,-3 " +
            "-3,1.34 -3,3c0,0.24 0.04,0.47 0.09,0.7L8.04,9.81C7.5,9.31 6.79,9 6,9c-1.66,0 -3,1.34 -3,3 " +
            "s1.34,3 3,3c0.79,0 1.5,-0.31 2.04,-0.81l7.12,4.16c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.61 " +
            "1.31,2.92 2.92,2.92s2.92,-1.31 2.92,-2.92 -1.31,-2.92 -2.92,-2.92z"
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