package com.nicholas.onote.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.nicholas.onote.drawing.DrawingEngine

@Composable
fun ONoteApp() {
    val engine = remember { DrawingEngine() }
    NotebookScreen(engine = engine)
}