package com.nicholas.onote.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nicholas.onote.data.NoteStore
import com.nicholas.onote.data.PageMode
import com.nicholas.onote.drawing.FingerGesture
import com.nicholas.onote.drawing.GestureAction
import com.nicholas.onote.drawing.PageBackground
import com.nicholas.onote.drawing.DrawingEngine
import com.nicholas.onote.settings.AppSettings
import com.nicholas.onote.settings.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    engine: DrawingEngine?,
    store: NoteStore,
    onBack: () -> Unit,
    onRestored: () -> Unit,
    onBackupAll: () -> Unit,
    onDriveFolderLinked: (android.net.Uri) -> Unit
) {
    var aboutOpen by remember { mutableStateOf(false) }
    var backupStatus by remember { mutableStateOf("") }

    val context = LocalContext.current
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            onDriveFolderLinked(uri)
            backupStatus = "Drive folder linked – creating ONote folder…"
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val json = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            val ok = json != null && store.restoreFromBackup(json)
            backupStatus = if (ok) "Notebooks restored" else "Restore failed"
            if (ok) onRestored()
        }
    }

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

            SectionTitle("Gestures")

            Text(
                "Assign an action to each finger gesture. Move gestures pan the " +
                    "page; taps, double taps and holds trigger the chosen action.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            for (gesture in FingerGesture.entries) {
                GestureRow(
                    gesture = gesture,
                    current = settings.gestureAction(gesture.key)
                ) { action ->
                    settings.updateGestureAction(gesture.key, action)
                }
            }

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

            ToggleRow(
                title = "Confirm before trashing",
                subtitle = "Ask before each notebook moves to the trash",
                checked = settings.confirmTrashDialog
            ) {
                settings.updateConfirmTrashDialog(it)
            }

            HorizontalDivider()

            SectionTitle("Backup & sync (Google Drive)")

            Text(
                "Link any Google Drive folder once; ONote then makes an \"ONote\" " +
                    "folder inside it and every notebook is saved there as its own " +
                    "\"<name>.onote\" file (with its images inside), so you can open " +
                    "or share any notebook on its own.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("Drive folder", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { folderLauncher.launch(null) },
                    enabled = settings.driveFolderUri == null
                ) {
                    Text("Choose folder…")
                }
                OutlinedButton(
                    onClick = {
                        settings.updateDriveFolder(null, null)
                        backupStatus = ""
                    },
                    enabled = settings.driveFolderUri != null
                ) {
                    Text("Unlink")
                }
                if (settings.driveFolderUri != null) {
                    Text(
                        "Linked",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Button(
                onClick = onBackupAll,
                enabled = settings.driveFolderUri != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Back up all notebooks now")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("application/json")) }) {
                    Text("Restore old backup…")
                }
            }
            if (backupStatus.isNotBlank()) {
                Text(backupStatus, style = MaterialTheme.typography.bodySmall)
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

@Composable
private fun GestureRow(
    gesture: FingerGesture,
    current: GestureAction,
    onChanged: (GestureAction) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val options = if (gesture.isMoveGesture) {
        GestureAction.entries.filter { it.moveAction }
    } else {
        listOf(GestureAction.NONE) + GestureAction.entries.filter { !it.moveAction }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            gesture.displayName,
            style = MaterialTheme.typography.bodyLarge,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Box {
            TextButton(onClick = { menuOpen = true }) {
                Text(
                    current.displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (current == GestureAction.NONE)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                for (option in options) {
                    DropdownMenuItem(
                        text = { Text(option.displayName, maxLines = 1) },
                        onClick = {
                            menuOpen = false
                            onChanged(option)
                        }
                    )
                }
            }
        }
    }
}