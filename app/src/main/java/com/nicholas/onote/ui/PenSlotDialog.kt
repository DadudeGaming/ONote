package com.nicholas.onote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nicholas.onote.drawing.Tool
import com.nicholas.onote.settings.PenSlot

private val PresetPalette = listOf(
    0xFF1A1A1A, 0xFFFFFFFF, 0xFF3B5BD6, 0xFF2196F3, 0xFF0097A7,
    0xFF2E7D32, 0xFFF57F17, 0xFFEF6C00, 0xFFC62828, 0xFFE91E63,
    0xFF7B1FA2, 0xFFFFD600
).map { it.toInt() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PenSlotDialog(
    index: Int,
    slot: PenSlot,
    onDismiss: () -> Unit,
    onSave: (PenSlot) -> Unit
) {
    var hex by remember(slot.color) { mutableStateOf(toHex(slot.color)) }
    var width by remember(slot.width) { mutableStateOf(slot.width) }
    var tool by remember(slot.tool) { mutableStateOf(slot.tool) }

    val hexOk = hex.removePrefix("#").matches(Regex("[0-9a-fA-F]{6}"))
    val previewColor = if (hexOk) parseHex(hex) else slot.color

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Slot ${index + 1}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColorSwatch(previewColor, selected = hexOk)
                    Spacer(Modifier.size(12.dp))
                    OutlinedTextField(
                        value = hex,
                        onValueChange = {
                            hex = it.filter { c -> c.isLetterOrDigit() || c == '#' }.take(7)
                        },
                        label = { Text("Hex color") },
                        singleLine = true,
                        isError = !hexOk && hex.isNotEmpty(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.size(150.dp, 54.dp)
                    )
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (r in PresetPalette) {
                        ColorSwatch(
                            color = r,
                            selected = !hexOk && r == previewColor,
                            onClick = { hex = toHex(r) }
                        )
                    }
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (t in Tool.entries) {
                        FilterChip(
                            selected = tool == t,
                            onClick = { tool = t },
                            label = { Text(t.displayName) }
                        )
                    }
                }

                Column {
                    Text(
                        "Thickness  ${width.toInt()}px",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Slider(
                        value = width,
                        onValueChange = { width = it },
                        valueRange = 1f..32f
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!hexOk) return@TextButton
                    onSave(PenSlot(tool = tool, color = parseHex(hex), width = width))
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ColorSwatch(color: Int, selected: Boolean, onClick: (() -> Unit)? = null) {
    val shape = CircleShape
    var modifier = Modifier
        .size(30.dp)
        .background(Color(color), shape)
        .border(
            width = if (selected) 3.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
            shape = shape
        )
    if (onClick != null) modifier = modifier.clickable(onClick = onClick)
    Box(modifier)
}

private fun toHex(color: Int): String = String.format("%06X", 0xFFFFFF and color)

private fun parseHex(hex: String): Int {
    val clean = hex.removePrefix("#")
    return (0xFF shl 24) or clean.toLong(16).toInt()
}