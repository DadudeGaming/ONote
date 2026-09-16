package com.nicholas.onote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nicholas.onote.drawing.DrawingCanvasView
import com.nicholas.onote.drawing.DrawingEngine

private val Palette = listOf(
    0xFF1A1A1A.toInt(),
    0xFF3B5BD6.toInt(),
    0xFFC62828.toInt(),
    0xFF2E7D32.toInt(),
    0xFFF57F17.toInt(),
    0xFF7B1FA2.toInt(),
    0xFF0097A7.toInt()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookScreen(engine: DrawingEngine) {

    var palmRejection by rememberSaveable { mutableStateOf(true) }
    var debugEnabled by rememberSaveable { mutableStateOf(true) }
    var colorIndex by rememberSaveable { mutableStateOf(0) }
    var widthValue by rememberSaveable { mutableStateOf(4f) }

    var canvasView by remember { mutableStateOf<DrawingCanvasView?>(null) }

    engine.activeColor = Palette[colorIndex]
    engine.activeWidth = widthValue

    fun invalidateCanvas() {
        canvasView?.invalidate()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("oNote") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                actions = {
                    ToolbarTextButton("Undo") { engine.undo() }
                    ToolbarTextButton("Redo") { engine.redo() }
                    ToolbarTextButton("Clear") { engine.clear(); invalidateCanvas() }
                    ToolbarTextButton("Reset") {
                        engine.transform.reset()
                        invalidateCanvas()
                    }
                    ToolbarTextButton(if (debugEnabled) "HUD ON" else "HUD OFF") {
                        debugEnabled = !debugEnabled
                        engine.debugEnabled = debugEnabled
                        invalidateCanvas()
                    }
                }
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Palm", style = MaterialTheme.typography.labelMedium)
                        Swatch("On", selected = palmRejection) {
                            palmRejection = true
                            engine.palmRejection = true
                        }
                        Swatch("Off", selected = !palmRejection) {
                            palmRejection = false
                            engine.palmRejection = false
                        }
                        Spacer(Modifier.width(8.dp))

                        for ((i, color) in Palette.withIndex()) {
                            ColorDot(
                                color = Color(color),
                                selected = i == colorIndex,
                                onClick = {
                                    colorIndex = i
                                    engine.activeColor = color
                                }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Width", style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = widthValue,
                            onValueChange = {
                                widthValue = it
                                engine.activeWidth = it
                            },
                            valueRange = 1f..16f,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        )
                        Text(
                            "${(widthValue).toInt()}px",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    ) { innerPadding ->

        AndroidView(
            factory = { context -> DrawingCanvasView(context, engine) },
            update = { view -> canvasView = view },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }
}

@Composable
private fun ToolbarTextButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(text)
    }
}

@Composable
private fun Swatch(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        modifier = Modifier
            .size(34.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = CircleShape
            )
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ColorDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    val ring = if (selected) 3.dp else 1.dp
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(color, CircleShape)
            .border(
                width = ring,
                color = if (selected) MaterialTheme.colorScheme.primary
                else Color.LightGray,
                shape = CircleShape
            )
            .clickable(onClick = onClick)
    )
}