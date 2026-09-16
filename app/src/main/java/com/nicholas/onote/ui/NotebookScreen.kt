package com.nicholas.onote.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nicholas.onote.drawing.DrawingCanvasView
import com.nicholas.onote.drawing.DrawingEngine
import com.nicholas.onote.drawing.PageBackground
import com.nicholas.onote.drawing.ToolMode
import com.nicholas.onote.settings.AppSettings

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
fun NotebookScreen(
    engine: DrawingEngine,
    settings: AppSettings,
    onOpenSettings: () -> Unit
) {
    var canvasView by remember { mutableStateOf<DrawingCanvasView?>(null) }
    var colorIndex by rememberSaveable { mutableStateOf(0) }
    var widthValue by rememberSaveable { mutableStateOf(engine.activeWidth) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var bgMenuOpen by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    engine.activeColor = Palette[colorIndex]
    engine.activeWidth = widthValue

    fun invalidate() = canvasView?.invalidate()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text("oNote · ${engine.pageBackground.displayName}")
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    actions = {
                        ToolbarTextButton("Undo") { engine.undo(); invalidate() }
                        ToolbarTextButton("Redo") { engine.redo(); invalidate() }
                        ToolbarTextButton("Clear") { engine.clear(); invalidate() }
                        ToolbarTextButton("Reset") { engine.transform.reset(); invalidate() }
                        Box {
                            ToolbarTextButton("More") { moreMenuOpen = true }
                            DropdownMenu(
                                expanded = moreMenuOpen,
                                onDismissRequest = { moreMenuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    onClick = {
                                        moreMenuOpen = false
                                        onOpenSettings()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("About") },
                                    onClick = {
                                        moreMenuOpen = false
                                        showAbout = true
                                    }
                                )
                            }
                        }
                    }
                )

                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            ToolChip(
                                label = "Pen",
                                selected = engine.toolMode == ToolMode.PEN
                            ) { engine.toolMode = ToolMode.PEN }
                            ToolChip(
                                label = "Eraser",
                                selected = engine.toolMode == ToolMode.ERASER
                            ) { engine.toolMode = ToolMode.ERASER }
                            Spacer(Modifier.width(8.dp))
                            Text("Width", style = MaterialTheme.typography.labelMedium)
                            Slider(
                                value = widthValue,
                                onValueChange = {
                                    widthValue = it
                                    engine.activeWidth = it
                                },
                                valueRange = 1f..16f,
                                modifier = Modifier.width(220.dp)
                            )
                            Text(
                                "${widthValue.toInt()}px",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("Color", style = MaterialTheme.typography.labelMedium)
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
                            Spacer(Modifier.weight(1f))
                            Box {
                                ToolbarTextButton(
                                    "Paper: ${engine.pageBackground.displayName} ▾"
                                ) { bgMenuOpen = true }
                                DropdownMenu(
                                    expanded = bgMenuOpen,
                                    onDismissRequest = { bgMenuOpen = false }
                                ) {
                                    for (bg in PageBackground.entries) {
                                        DropdownMenuItem(
                                            text = { Text(bg.displayName) },
                                            onClick = {
                                                engine.pageBackground = bg
                                                bgMenuOpen = false
                                                invalidate()
                                            }
                                        )
                                    }
                                }
                            }
                            ToolbarTextButton(
                                if (engine.palmRejection) "Palm On" else "Palm Off"
                            ) {
                                engine.palmRejection = !engine.palmRejection
                                settings.updatePalmRejection(engine.palmRejection)
                                invalidate()
                            }
                            ToolbarTextButton(
                                if (engine.debugEnabled) "HUD On" else "HUD Off"
                            ) {
                                engine.debugEnabled = !engine.debugEnabled
                                settings.updateHudEnabled(engine.debugEnabled)
                                invalidate()
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
        ) {
            AndroidView(
                factory = { context -> DrawingCanvasView(context, engine) },
                update = { view -> canvasView = view },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
}

@Composable
private fun ToolbarTextButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(text)
    }
}

@Composable
private fun ToolChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val container = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surface
    val content = if (selected) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurface
    Surface(
        shape = CircleShape,
        color = container,
        modifier = Modifier
            .size(width = 96.dp, height = 34.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = content, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ColorDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    val ring = if (selected) 3.dp else 1.dp
    Box(
        modifier = Modifier
            .size(30.dp)
            .background(color, CircleShape)
            .border(
                width = ring,
                color = if (selected) MaterialTheme.colorScheme.primary
                else Color.Gray,
                shape = CircleShape
            )
            .clickable(onClick = onClick)
    )
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lines = remember { buildAboutInfo(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About oNote") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (line in lines) {
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    )
}

private fun buildAboutInfo(context: Context): List<String> {
    val pm = context.packageManager
    val version = runCatching {
        pm.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0)
        ).versionName
    }.getOrDefault("?")
    val hasPen = runCatching {
        pm.hasSystemFeature("com.sec.feature.spen_usp") ||
            pm.hasSystemFeature("android.hardware.stylus")
    }.getOrDefault(false)
    return listOf(
        "oNote milestone 2",
        "Version $version (${context.packageName})",
        "${Build.MANUFACTURER} ${Build.MODEL}",
        "Android ${Build.VERSION.RELEASE} · SDK ${Build.VERSION.SDK_INT}",
        "S Pen reported: $hasPen"
    )
}
