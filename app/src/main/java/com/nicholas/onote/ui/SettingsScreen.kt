package com.nicholas.onote.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nicholas.onote.drawing.DrawingEngine
import com.nicholas.onote.settings.AppSettings
import com.nicholas.onote.settings.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    engine: DrawingEngine,
    onBack: () -> Unit
) {
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

            SectionTitle("Input")

            ToggleRow(
                title = "Palm rejection",
                subtitle = "Ignore fingers/palm while the S Pen writes",
                checked = settings.palmRejection
            ) {
                settings.updatePalmRejection(it)
                engine.palmRejection = it
            }

            ToggleRow(
                title = "Debug HUD",
                subtitle = "On-canvas pointer, pressure and performance info",
                checked = settings.hudEnabled
            ) {
                settings.updateHudEnabled(it)
                engine.debugEnabled = it
            }

            HorizontalDivider()

            SectionTitle("About")

            val context = LocalContext.current
            val about = remember { buildAboutInfo(context) }
            for (line in about) {
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
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
        "oNote",
        "Version $version (${context.packageName})",
        "${Build.MANUFACTURER} ${Build.MODEL}",
        "Android ${Build.VERSION.RELEASE} · SDK ${Build.VERSION.SDK_INT}",
        "S Pen reported: $hasPen"
    )
}
