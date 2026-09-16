package com.nicholas.onote.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nicholas.onote.data.NoteStore
import com.nicholas.onote.data.PageMode
import com.nicholas.onote.drawing.PageBackground
import com.nicholas.onote.drawing.ToolMode
import com.nicholas.onote.settings.AppSettings
import com.nicholas.onote.settings.ThemeMode
import com.nicholas.onote.ui.theme.ONoteTheme

@Composable
fun ONoteApp() {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val store = remember { NoteStore(context) }

    var homeScreen by rememberSaveable { mutableStateOf(true) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var trashOpen by rememberSaveable { mutableStateOf(false) }
    var newNotebookDialogOpen by rememberSaveable { mutableStateOf(false) }
    var notebooks by remember { mutableStateOf<List<OpenNotebook>>(emptyList()) }
    var activeIndex by remember { mutableStateOf(-1) }
    var documents by remember { mutableStateOf(store.listDocuments()) }
    var trashDocuments by remember { mutableStateOf(store.listTrash()) }

    val darkTheme = when (settings.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val active = notebooks.getOrNull(activeIndex)

    fun refreshList() {
        documents = store.listDocuments()
    }

    fun refreshTrash() {
        trashDocuments = store.listTrash()
    }

    fun save(notebook: OpenNotebook) {
        notebook.engine.snapshotTo(notebook.source())
        notebook.doc.touched()
        store.saveDocument(notebook.doc)
    }

    fun saveAll() {
        for (nb in notebooks) save(nb)
    }

    fun createNotebook(title: String, mode: PageMode, background: PageBackground) {
        val doc = store.createDocument(title, mode, background)
        settings.updateLastOpenedDoc(doc.id)
        notebooks = notebooks + OpenNotebook(doc)
        activeIndex = notebooks.lastIndex
        refreshList()
        homeScreen = false
        settingsOpen = false
        trashOpen = false
    }

    fun openDocument(docId: String) {
        val existing = notebooks.firstOrNull { it.doc.id == docId }
        if (existing != null) {
            activeIndex = notebooks.indexOf(existing)
        } else {
            store.loadDocument(docId)?.let {
                notebooks = notebooks + OpenNotebook(it)
                activeIndex = notebooks.lastIndex
            }
        }
        settings.updateLastOpenedDoc(docId)
        homeScreen = false
        settingsOpen = false
        trashOpen = false
    }

    // Resume the last-opened notebook at startup when the setting is on.
    LaunchedEffect(Unit) {
        if (settings.resumeLast && settings.lastOpenedDoc != null) {
            openDocument(settings.lastOpenedDoc!!)
        }
    }

    // Keep the active page's paper colour and ink adapting to the theme, and
    // push app-level toggles (palm/HUD) into the engine.
    LaunchedEffect(active, darkTheme) {
        if (active != null) {
            val engine = active.engine
            engine.palmRejection = settings.palmRejection
            engine.debugEnabled = settings.hudEnabled
            engine.eraseRadius = settings.eraserRadius
            val slot = settings.slot(settings.selectedSlot)
            engine.activeColor = slot.color
            engine.activeWidth = slot.width
            engine.activeTool = slot.tool
            engine.paperColor =
                if (darkTheme) AppSettings.PaperDark else AppSettings.PaperLight
            if (engine.toolMode != ToolMode.ERASER) {
                val lum = luminance(engine.activeColor)
                if (darkTheme && lum < 48) engine.activeColor = 0xFFEDEDED.toInt()
                if (!darkTheme && lum > 230) engine.activeColor = 0xFF1A1A1A.toInt()
            }
        }
    }

    ONoteTheme(darkTheme = darkTheme) {
        when {
            settingsOpen -> SettingsScreen(
                settings = settings,
                engine = active?.engine,
                onBack = { settingsOpen = false }
            )

            trashOpen -> TrashScreen(
                documents = trashDocuments,
                onRestore = { id ->
                    store.restoreFromTrash(id)
                    refreshTrash()
                    refreshList()
                },
                onDeleteForever = { id ->
                    store.deleteForever(id)
                    refreshTrash()
                },
                onBack = { trashOpen = false }
            )

            homeScreen -> HomeScreen(
                documents = documents,
                darkTheme = darkTheme,
                onNew = { newNotebookDialogOpen = true },
                onOpen = { doc -> openDocument(doc.id) },
                onOpenSettings = { settingsOpen = true },
                onOpenTrash = { refreshTrash(); trashOpen = true }
            )

            active != null -> EditorScreen(
                notebook = active,
                openNotebooks = notebooks,
                activeIndex = activeIndex,
                settings = settings,
                darkTheme = darkTheme,
                onSelectTab = { i ->
                    save(active)
                    refreshList()
                    activeIndex = i
                },
                onCloseTab = { i ->
                    save(notebooks[i])
                    refreshList()
                    notebooks = notebooks.toMutableList().also { it.removeAt(i) }
                    activeIndex =
                        if (notebooks.isEmpty()) -1 else (activeIndex.coerceAtMost(notebooks.lastIndex))
                    if (notebooks.isEmpty()) {
                        homeScreen = true
                    }
                },
                onNewTab = { newNotebookDialogOpen = true },
                onHome = {
                    saveAll()
                    refreshList()
                    homeScreen = true
                },
                onSave = { nb ->
                    save(nb)
                    refreshList()
                },
                onOpenSettings = {
                    saveAll()
                    refreshList()
                    settingsOpen = true
                },
                onDelete = {
                    val i = activeIndex
                    store.moveToTrash(notebooks[i].doc.id)
                    notebooks = notebooks.toMutableList().also { it.removeAt(i) }
                    activeIndex =
                        if (notebooks.isEmpty()) -1 else (i.coerceAtMost(notebooks.lastIndex))
                    refreshList()
                    refreshTrash()
                    if (notebooks.isEmpty()) homeScreen = true
                },
                onRenamed = { title ->
                    active.doc.title = title
                    save(active)
                    refreshList()
                }
            )
        }
    }

    if (newNotebookDialogOpen) {
        NewNotebookDialog(
            onDismiss = { newNotebookDialogOpen = false },
            onCreate = { title, mode, bg ->
                newNotebookDialogOpen = false
                createNotebook(title, mode, bg)
            }
        )
    }
}

@Composable
private fun NewNotebookDialog(
    onDismiss: () -> Unit,
    onCreate: (String, PageMode, PageBackground) -> Unit
) {
    var title by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(PageMode.INFINITE) }
    var bg by rememberSaveable { mutableStateOf(PageBackground.RULED) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New notebook") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title (optional)") },
                    singleLine = true
                )
                Text("Paper style", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (paper in PageBackground.entries) {
                        TextButton(
                            onClick = { bg = paper },
                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                containerColor = if (bg == paper) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Text(paper.displayName, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Text("Notebook type", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in PageMode.entries) {
                        TextButton(
                            onClick = { mode = m },
                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                containerColor = if (mode == m) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Text(m.displayName, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Text(
                    if (mode == PageMode.INFINITE) {
                        "One continuous page; its paper style can change anytime."
                    } else {
                        "Fixed pages; each page keeps the paper style chosen when it's added."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(title.trim().ifBlank { "Untitled" }, mode, bg) }
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun luminance(color: Int): Float {
    val r = (color shr 16) and 0xFF
    val g = (color shr 8) and 0xFF
    val b = color and 0xFF
    return 0.299f * r + 0.587f * g + 0.114f * b
}