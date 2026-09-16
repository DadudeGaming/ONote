package com.nicholas.onote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nicholas.onote.data.PageMode
import com.nicholas.onote.drawing.PageBackground
import com.nicholas.onote.drawing.DrawingEngine
import com.nicholas.onote.settings.AppSettings
import com.nicholas.onote.settings.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    engine: DrawingEngine?,
    onBack: () -> Unit
) {
    var aboutOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionTitle("Appearance")

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Theme", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (mode in ThemeMode.entries) {
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { settings.updateThemeMode(mode) },
                            label = { Text(mode.displayName) }
                        )
                    }
                }
            }

            HorizontalDivider()

            SectionTitle("Input")

            ToggleRow(
                title = "Palm rejection",
                subtitle = "Ignore fingers/palm while the S Pen writes",
                checked = engine?.palmRejection ?: settings.palmRejection
            ) {
                settings.updatePalmRejection(it)
                engine?.palmRejection = it
            }

            ToggleRow(
                title = "Debug HUD",
                subtitle = "On-canvas pointer, pressure and performance info",
                checked = engine?.debugEnabled ?: settings.hudEnabled
            ) {
                settings.updateHudEnabled(it)
                engine?.debugEnabled = it
            }

            HorizontalDivider()

            SectionTitle("New notebooks")

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Default paper style", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (paper in PageBackground.entries) {
                        FilterChip(
                            selected = settings.defaultPaper == paper,
                            onClick = { settings.updateDefaultPaper(paper) },
                            label = { Text(paper.displayName) }
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Default notebook type", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in PageMode.entries) {
                        FilterChip(
                            selected = settings.defaultPageMode == m,
                            onClick = { settings.updateDefaultPageMode(m) },
                            label = { Text(m.displayName) }
                        )
                    }
                }
            }

            ToggleRow(
                title = "Resume last notebook",
                subtitle = "Open the last notebook you had open when starting ONote",
                checked = settings.resumeLast
            ) {
                settings.updateResumeLast(it)
            }

            HorizontalDivider()

            TextButton(onClick = { aboutOpen = true }) { Text("About ONote") }
        }
    }

    if (aboutOpen) {
        AboutDialog(onDismiss = { aboutOpen = false })
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChanged: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.size(8.dp))
        Switch(checked = checked, onCheckedChange = onChanged)
    }
}