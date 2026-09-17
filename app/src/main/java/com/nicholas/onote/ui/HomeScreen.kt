package com.nicholas.onote.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nicholas.onote.data.NoteDocument
import com.nicholas.onote.data.PageMode
import com.nicholas.onote.pdf.PdfExporter
import com.nicholas.onote.settings.AppSettings
import java.io.File
import android.widget.Toast

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    documents: List<NoteDocument>,
    darkTheme: Boolean,
    settings: AppSettings,
    onNew: () -> Unit,
    onOpen: (NoteDocument) -> Unit,
    onRename: (NoteDocument, String) -> Unit,
    onDelete: (NoteDocument, Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTrash: () -> Unit,
    onDriveSaved: (Uri, NoteDocument) -> Unit,
    onImportOnote: (String) -> Unit,
    onShareOnote: (NoteDocument) -> Unit,
    onRemoveFromDrive: (NoteDocument) -> Unit,
    imagesDir: File
) {
    var menuTarget by remember { mutableStateOf<NoteDocument?>(null) }
    var renameTarget by remember { mutableStateOf<NoteDocument?>(null) }
    var deleteTarget by remember { mutableStateOf<NoteDocument?>(null) }
    var confirmTrash by remember { mutableStateOf(settings.confirmTrashDialog) }
    var dontAskTrash by remember { mutableStateOf(false) }
    var newMenuOpen by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val target = menuTarget
        if (uri != null && target != null) {
            PdfExporter.export(context.contentResolver, uri, target, context.filesDir)
        }
        menuTarget = null
    }

    val driveBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val target = menuTarget
        if (uri != null && target != null) {
            onDriveSaved(uri, target)
        }
        menuTarget = null
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val json = runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
            }.getOrNull()
            if (json != null) {
                onImportOnote(json)
            } else {
                Toast.makeText(context, "Couldn't read that file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ONote") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                actions = {
                    IconButton(onClick = onOpenTrash) {
                        Icon(ONoteIcons.Trash, contentDescription = "Trash")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(ONoteIcons.Settings, contentDescription = "Settings")
                    }
                    Box {
                        FilledTonalButton(onClick = { newMenuOpen = true }) { Text("New") }
                        DropdownMenu(
                            expanded = newMenuOpen,
                            onDismissRequest = { newMenuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("New notebook") },
                                onClick = {
                                    newMenuOpen = false
                                    onNew()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Import from .onote…") },
                                onClick = {
                                    newMenuOpen = false
                                    importLauncher.launch(
                                        arrayOf("application/json", "application/octet-stream")
                                    )
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (documents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No notebooks yet",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        "Tap New to start writing",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(documents, key = { it.id }) { doc ->
                    DocumentCard(
                        doc = doc,
                        darkTheme = darkTheme,
                        imagesDir = imagesDir,
                        backedUp = settings.driveLink(doc.id) != null,
                        onClick = { onOpen(doc) },
                        onLongClick = { menuTarget = doc },
                        onMenu = { menuTarget = doc }
                    )
                }
            }
        }
    }

    // Long-press (or ⋮) options for a document.
    val menuDoc = menuTarget
    if (menuDoc != null) {
        AlertDialog(
            onDismissRequest = { menuTarget = null },
            title = { Text(menuDoc.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DialogAction("Open", description = "Start writing") {
                        menuTarget = null
                        onOpen(menuDoc)
                    }
                    DialogAction("Rename", description = "Change the title") {
                        menuTarget = null
                        renameTarget = menuDoc
                    }
                    DialogAction("Export PDF", description = "Save this notebook as a PDF") {
                        exportLauncher.launch(sanitizeFileName(menuDoc.title) + ".pdf")
                    }
                    DialogAction(
                        "Export .onote…",
                        description = "Save a shareable single-file copy"
                    ) {
                        driveBackupLauncher.launch(sanitizeFileName(menuDoc.title) + ".onote")
                    }
                    DialogAction(
                        "Share .onote",
                        description = "Send this notebook to another ONote user"
                    ) {
                        menuTarget = null
                        onShareOnote(menuDoc)
                    }
                    DialogAction("Move to trash", description = "Restore it later from Home",
                        destructive = true) {
                        menuTarget = null
                        if (confirmTrash) {
                            deleteTarget = menuDoc
                        } else {
                            onDelete(menuDoc, false)
                        }
                    }
                    if (settings.driveLink(menuDoc.id) != null ||
                        settings.driveFolderUri != null
                    ) {
                        DialogAction(
                            "Remove from Google Drive",
                            description = "Delete this notebook's Drive backup",
                            destructive = true
                        ) {
                            menuTarget = null
                            onRemoveFromDrive(menuDoc)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { menuTarget = null }) { Text("Cancel") }
            }
        )
    }

    // Confirmation before trashing (can be disabled once).
    val deleteDoc = deleteTarget
    if (deleteDoc != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Move to trash?") },
            text = {
                Column {
                    Text("\"${deleteDoc.title}\" will move to the trash. You can restore it from Home.")
                    Row(
                        modifier = Modifier
                            .clickable { dontAskTrash = !dontAskTrash }
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = dontAskTrash, onCheckedChange = { dontAskTrash = it })
                        Text("Don't ask again", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (dontAskTrash) {
                        settings.updateConfirmTrashDialog(false)
                        confirmTrash = false
                    }
                    onDelete(deleteDoc, false)
                    deleteTarget = null
                }) { Text("Move to trash") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    val renameDoc = renameTarget
    if (renameDoc != null) {
        var text by remember(renameDoc.id) { mutableStateOf(renameDoc.title) }
        var cleared by remember(renameDoc.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename notebook") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.onFocusChanged { state ->
                        if (state.isFocused && !cleared) {
                            cleared = true
                            text = ""
                        }
                    },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRename(renameDoc, text.trim())
                        renameTarget = null
                    },
                    enabled = text.isNotBlank()
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DialogAction(
    label: String,
    description: String,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val color = if (destructive) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = color)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentCard(
    doc: NoteDocument,
    darkTheme: Boolean,
    imagesDir: File,
    backedUp: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenu: () -> Unit
) {
    val paper =
        if (darkTheme) AppSettings.PaperDark else AppSettings.PaperLight
    val thumb = remember(doc.id, doc.updatedAt, doc.title, darkTheme, imagesDir) {
        NotebookThumb.render(doc, darkTheme, imagesDir)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .size(110.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(paper)),
                contentAlignment = Alignment.Center
            ) {
                val bitmap = thumb
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Preview of ${doc.title}",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(6.dp))
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (backedUp) {
                        Icon(
                            ONoteIcons.Cloud,
                            contentDescription = "Backed up to Google Drive",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(doc.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                }
                Text(
                    if (doc.pageMode == PageMode.PAGES)
                        "${doc.pageMode.displayName} · ${doc.pages.size} pages"
                    else "${doc.pageBackground.displayName} · ${doc.strokes.size} strokes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    relativeTime(doc.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onMenu, modifier = Modifier.size(36.dp)) {
                Icon(
                    ONoteIcons.MoreVert,
                    contentDescription = "Options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun relativeTime(time: Long): String {
    val now = System.currentTimeMillis()
    val diff = (now - time).coerceAtLeast(0L)
    val min = diff / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 60 * 24 -> "${min / 60} h ago"
        else -> "${min / (60 * 24)} d ago"
    }
}

private fun sanitizeFileName(title: String): String {
    val clean = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    return clean.ifBlank { "Notebook" }
}