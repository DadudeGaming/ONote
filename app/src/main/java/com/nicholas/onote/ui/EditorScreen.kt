package com.nicholas.onote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import com.nicholas.onote.data.NoteDocument
import com.nicholas.onote.data.NotePage
import com.nicholas.onote.data.PageMode
import com.nicholas.onote.data.PageSource
import com.nicholas.onote.drawing.DrawingCanvasView
import com.nicholas.onote.drawing.DrawingEngine
import com.nicholas.onote.drawing.PageBackground
import com.nicholas.onote.drawing.ToolMode
import com.nicholas.onote.pdf.PdfExporter
import com.nicholas.onote.settings.AppSettings
import com.nicholas.onote.settings.PenSlot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * One open notebook: the persisted document plus the live drawing engine that
 * is (re)hydrated from it, and, for Pages notebooks, the page being edited.
 */
class OpenNotebook(val doc: NoteDocument) {
    var currentPageIndex by mutableStateOf(0)
    val engine = DrawingEngine()

    init {
        engine.preserveCamera = doc.pageMode == PageMode.PAGES
        engine.applyDocument(doc.sourceForPage(0))
    }

    fun source(): PageSource = doc.sourceForPage(currentPageIndex)
}

@Composable
fun EditorScreen(
    notebook: OpenNotebook,
    openNotebooks: List<OpenNotebook>,
    activeIndex: Int,
    settings: AppSettings,
    darkTheme: Boolean,
    onSelectTab: (Int) -> Unit,
    onCloseTab: (Int) -> Unit,
    onNewTab: () -> Unit,
    onHome: () -> Unit,
    onSave: (OpenNotebook) -> Unit,
    onOpenSettings: () -> Unit,
    onDelete: () -> Unit,
    onRenamed: (String) -> Unit
) {
    val engine = notebook.engine
    var canvasView by remember { mutableStateOf<DrawingCanvasView?>(null) }
    var penDialogIndex by remember { mutableStateOf(-1) }
    var eraserDialogOpen by remember { mutableStateOf(false) }
    var paperMenuOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var renameDialogOpen by remember { mutableStateOf(false) }
    var deleteDialogOpen by remember { mutableStateOf(false) }
    var addPageDialogOpen by remember { mutableStateOf(false) }
    var pageMenuFor by remember { mutableStateOf<Int?>(null) }
    var pageRenameFor by remember { mutableStateOf<Int?>(null) }
    var deleteTargetPage by remember { mutableStateOf<Int?>(null) }

    val pagesMode = notebook.doc.pageMode == PageMode.PAGES

    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) {
            onSave(notebook)
            PdfExporter.export(context.contentResolver, uri, notebook.doc)
        }
    }

    // Apply the themed paper colour synchronously so the canvas never has a
    // white first frame in dark mode.
    engine.paperColor = if (darkTheme) AppSettings.PaperDark else AppSettings.PaperLight
    engine.deskColor = if (darkTheme) AppSettings.DeskDark else AppSettings.DeskLight

    fun invalidate() = canvasView?.invalidate()

    /** Switches the engine to another page, saving the current one first. */
    fun changeActivePage(to: Int) {
        if (notebook.doc.pages.isEmpty()) return
        val clamped = to.coerceIn(0, notebook.doc.pages.lastIndex)
        if (clamped == notebook.currentPageIndex) return
        onSave(notebook)
        notebook.currentPageIndex = clamped
        engine.applyDocument(notebook.source())
        if (pagesMode) {
            canvasView?.setFlowPages(notebook.doc.pages, clamped)
            canvasView?.scrollToPage(clamped)
        }
        invalidate()
    }

    fun addPage(background: PageBackground) {
        onSave(notebook)
        notebook.doc.pages.add(NotePage(UUID.randomUUID().toString(), background))
        notebook.currentPageIndex = notebook.doc.pages.lastIndex
        engine.applyDocument(notebook.source())
        if (pagesMode) {
            canvasView?.setFlowPages(notebook.doc.pages, notebook.currentPageIndex)
            canvasView?.scrollToPage(notebook.currentPageIndex)
        }
        invalidate()
    }

    fun deletePageAt(index: Int) {
        if (notebook.doc.pages.size <= 1) return
        onSave(notebook)
        notebook.doc.pages.removeAt(index)
        notebook.currentPageIndex = notebook.currentPageIndex.coerceIn(0, notebook.doc.pages.lastIndex)
        engine.applyDocument(notebook.source())
        if (pagesMode) {
            canvasView?.setFlowPages(notebook.doc.pages, notebook.currentPageIndex)
            canvasView?.scrollToPage(notebook.currentPageIndex)
        }
        invalidate()
    }

    // Debounced save whenever the page content changes.
    LaunchedEffect(notebook.doc.id) {
        val scope = this
        var job: Job? = null
        engine.onChanged = {
            job?.cancel()
            job = scope.launch {
                delay(700)
                onSave(notebook)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabStrip(
            openNotebooks = openNotebooks,
            activeIndex = activeIndex,
            onSelect = onSelectTab,
            onClose = onCloseTab,
            onNew = onNewTab,
            onHome = onHome
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            key(notebook.doc.id) {
                AndroidView(
                    factory = { context ->
                        DrawingCanvasView(context, engine, pagesMode = pagesMode)
                    },
                    update = { view ->
                        canvasView = view
                        view.input.panEnabled = pagesMode && engine.palmRejection
                        view.input.pageFlowEnabled = pagesMode
                        view.input.pageCount = maxOf(1, notebook.doc.pages.size)
                        view.input.flowIndex = notebook.currentPageIndex
                        view.input.onDoubleTap2 = {
                            engine.undo()
                            invalidate()
                            onSave(notebook)
                        }
                        view.input.onDoubleTap3 = {
                            engine.redo()
                            invalidate()
                            onSave(notebook)
                        }
                        view.input.onActivePageChanged = { idx ->
                            if (idx != notebook.currentPageIndex) changeActivePage(idx)
                        }
                        view.input.onLongPress3 = { idx ->
                            engine.snapshotTo(notebook.source())
                            onSave(notebook)
                            pageMenuFor = idx
                        }
                        if (pagesMode) {
                            view.setFlowPages(notebook.doc.pages, notebook.currentPageIndex)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            LandingIsland(
                engine = engine,
                settings = settings,
                canUndo = engine.canUndo,
                canRedo = engine.canRedo,
                onUndo = { engine.undo(); invalidate() },
                onRedo = { engine.redo(); invalidate() },
                onSelectSlot = { i ->
                    val slot = settings.slot(i)
                    engine.activeColor = slot.color
                    engine.activeWidth = slot.width
                    engine.activeTool = slot.tool
                    engine.toolMode = ToolMode.PEN
                    settings.updateSelectedSlot(i)
                },
                onEditSlot = { i -> penDialogIndex = i },
                onToggleEraser = {
                    if (engine.toolMode == ToolMode.ERASER) eraserDialogOpen = true
                    else {
                        engine.toolMode = ToolMode.ERASER
                        invalidate()
                    }
                },
                onSetPaper = {
                    engine.pageBackground = it
                    invalidate()
                    onSave(notebook)
                },
                paperMenuOpen = paperMenuOpen,
                onPaperMenu = { paperMenuOpen = it },
                moreMenuOpen = moreMenuOpen,
                onMoreMenu = { moreMenuOpen = it },
                pagesMode = pagesMode,
                onSettings = onOpenSettings,
                onRename = { renameDialogOpen = true },
                onAddPage = { addPageDialogOpen = true },
                onDelete = { deleteDialogOpen = true },
                onExport = { exportLauncher.launch(sanitizeFileName(notebook.doc.title) + ".pdf") },
                onTogglePalm = {
                    engine.palmRejection = !engine.palmRejection
                    settings.updatePalmRejection(engine.palmRejection)
                    canvasView?.input?.panEnabled = pagesMode && engine.palmRejection
                    invalidate()
                },
                onToggleHud = {
                    engine.debugEnabled = !engine.debugEnabled
                    settings.updateHudEnabled(engine.debugEnabled)
                    invalidate()
                }
            )
        }
    }

    if (penDialogIndex >= 0) {
        PenSlotDialog(
            index = penDialogIndex,
            slot = settings.slot(penDialogIndex),
            onDismiss = { penDialogIndex = -1 }
        ) { updated ->
            settings.updatePenSlot(penDialogIndex, updated)
            if (penDialogIndex == settings.selectedSlot && engine.toolMode == ToolMode.PEN) {
                engine.activeColor = updated.color
                engine.activeWidth = updated.width
                engine.activeTool = updated.tool
            }
            penDialogIndex = -1
        }
    }

    if (eraserDialogOpen) {
        AlertDialog(
            onDismissRequest = { eraserDialogOpen = false },
            title = { Text("Eraser size") },
            text = {
                Column {
                    Text("${settings.eraserRadius.toInt()}px", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = settings.eraserRadius,
                        onValueChange = {
                            settings.updateEraserRadius(it)
                            engine.eraseRadius = it
                        },
                        valueRange = 10f..60f
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { eraserDialogOpen = false }) { Text("Done") }
            }
        )
    }

    if (renameDialogOpen) {
        RenameDialog(
            heading = "Rename notebook",
            current = notebook.doc.title,
            onDismiss = { renameDialogOpen = false },
            onRename = {
                onRenamed(it)
                renameDialogOpen = false
            }
        )
    }

    if (deleteDialogOpen) {
        AlertDialog(
            onDismissRequest = { deleteDialogOpen = false },
            title = { Text("Move to trash?") },
            text = { Text("\"${notebook.doc.title}\" will move to the trash. You can restore it from Home.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteDialogOpen = false
                    onDelete()
                }) { Text("Move to trash") }
            },
            dismissButton = {
                TextButton(onClick = { deleteDialogOpen = false }) { Text("Cancel") }
            }
        )
    }

    if (addPageDialogOpen) {
        AddPageDialog(
            onDismiss = { addPageDialogOpen = false },
            onAdd = {
                addPage(it)
                addPageDialogOpen = false
            }
        )
    }

    val menuPage = pageMenuFor
    if (menuPage != null && menuPage < notebook.doc.pages.size) {
        AlertDialog(
            onDismissRequest = { pageMenuFor = null },
            title = { Text("Page ${menuPage + 1}") },
            text = {
                Text(
                    notebook.doc.pages[menuPage].title.ifBlank { "(untitled page)" },
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pageRenameFor = menuPage
                    pageMenuFor = null
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = {
                    deleteTargetPage = menuPage
                    pageMenuFor = null
                }) { Text("Delete") }
            }
        )
    }

    val renameFor = pageRenameFor
    if (renameFor != null && renameFor < notebook.doc.pages.size) {
        RenameDialog(
            heading = "Rename page",
            current = notebook.doc.pages[renameFor].title,
            onDismiss = { pageRenameFor = null },
            onRename = { newTitle ->
                notebook.doc.pages[renameFor].title = newTitle
                canvasView?.setFlowPages(notebook.doc.pages, notebook.currentPageIndex)
                invalidate()
                onSave(notebook)
                pageRenameFor = null
            }
        )
    }

    val deletePage = deleteTargetPage
    if (deletePage != null) {
        AlertDialog(
            onDismissRequest = { deleteTargetPage = null },
            title = { Text("Delete page?") },
            text = {
                Text(if (notebook.doc.pages.size <= 1) "A notebook needs at least one page."
                else "Page ${deletePage + 1} will be deleted.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTargetPage = null
                        deletePageAt(deletePage)
                    },
                    enabled = notebook.doc.pages.size > 1
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargetPage = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun TabStrip(
    openNotebooks: List<OpenNotebook>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onNew: () -> Unit,
    onHome: () -> Unit
) {
    Column(modifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onHome, modifier = Modifier.size(42.dp)) {
                Icon(
                    ONoteIcons.Home,
                    contentDescription = "All notebooks",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for ((i, nb) in openNotebooks.withIndex()) {
                    TabChip(
                        title = nb.doc.title,
                        selected = i == activeIndex,
                        showClose = openNotebooks.size > 1,
                        onClick = { onSelect(i) },
                        onClose = { onClose(i) }
                    )
                }
            }
            IconButton(onClick = onNew, modifier = Modifier.size(42.dp)) {
                Icon(
                    ONoteIcons.Plus,
                    contentDescription = "New notebook",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun TabChip(
    title: String,
    selected: Boolean,
    showClose: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer
    else Color.Transparent
    val content = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = if (showClose) 2.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = content,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(110.dp)
        )
        if (showClose) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    ONoteIcons.Close,
                    contentDescription = "Close",
                    tint = content,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * The compact floating toolbar, pinned near the top of the page so the whole
 * lower area feels like writing space.
 */
@Composable
private fun LandingIsland(
    engine: DrawingEngine,
    settings: AppSettings,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSelectSlot: (Int) -> Unit,
    onEditSlot: (Int) -> Unit,
    onToggleEraser: () -> Unit,
    onSetPaper: (PageBackground) -> Unit,
    paperMenuOpen: Boolean,
    onPaperMenu: (Boolean) -> Unit,
    moreMenuOpen: Boolean,
    onMoreMenu: (Boolean) -> Unit,
    pagesMode: Boolean,
    onSettings: () -> Unit,
    onRename: () -> Unit,
    onAddPage: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onTogglePalm: () -> Unit,
    onToggleHud: () -> Unit
) {
    val auf = MaterialTheme.colorScheme.onSurfaceVariant
    val eraserActive = engine.toolMode == ToolMode.ERASER

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            modifier = Modifier.padding(top = 10.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = 3.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                for (i in 0 until 6) {
                    val slot = settings.slot(i)
                    val selected = settings.selectedSlot == i && engine.toolMode == ToolMode.PEN
                    PenSlotDot(
                        slot = slot,
                        selected = selected,
                        onClick = { if (selected) onEditSlot(i) else onSelectSlot(i) }
                    )
                }

                IslandDivider()
                IconButton(onClick = onToggleEraser, modifier = Modifier.size(32.dp)) {
                    Icon(
                        ONoteIcons.Eraser,
                        contentDescription = "Eraser",
                        tint = if (eraserActive) MaterialTheme.colorScheme.primary else auf,
                        modifier = Modifier.size(22.dp)
                    )
                }

                IslandDivider()
                IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.size(32.dp)) {
                    Icon(ONoteIcons.UndoArrow, contentDescription = "Undo", modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.size(32.dp)) {
                    Icon(ONoteIcons.RedoArrow, contentDescription = "Redo", modifier = Modifier.size(22.dp))
                }

                if (!pagesMode) {
                    IslandDivider()
                    Box {
                        IconButton(onClick = { onPaperMenu(true) }, modifier = Modifier.size(32.dp)) {
                            Icon(ONoteIcons.Page, contentDescription = "Paper", modifier = Modifier.size(22.dp))
                        }
                        DropdownMenu(expanded = paperMenuOpen, onDismissRequest = { onPaperMenu(false) }) {
                            for (bg in PageBackground.entries) {
                                DropdownMenuItem(
                                    text = { Text(bg.displayName) },
                                    onClick = {
                                        onPaperMenu(false)
                                        onSetPaper(bg)
                                    }
                                )
                            }
                        }
                    }
                }

                IslandDivider()
                Box {
                    IconButton(onClick = { onMoreMenu(true) }, modifier = Modifier.size(32.dp)) {
                        Icon(ONoteIcons.MoreVert, contentDescription = "More actions", modifier = Modifier.size(22.dp))
                    }
                    DropdownMenu(expanded = moreMenuOpen, onDismissRequest = { onMoreMenu(false) }) {
                        DropdownMenuItem(
                            text = { Text(if (engine.palmRejection) "Turn palm rejection off" else "Turn palm rejection on") },
                            onClick = {
                                onMoreMenu(false)
                                onTogglePalm()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (engine.debugEnabled) "Hide debug HUD" else "Show debug HUD") },
                            onClick = {
                                onMoreMenu(false)
                                onToggleHud()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                onMoreMenu(false)
                                onRename()
                            }
                        )
                        if (pagesMode) {
                            DropdownMenuItem(
                                text = { Text("Add page") },
                                onClick = {
                                    onMoreMenu(false)
                                    onAddPage()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Export PDF") },
                            onClick = {
                                onMoreMenu(false)
                                onExport()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = {
                                onMoreMenu(false)
                                onSettings()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Move to trash") },
                            onClick = {
                                onMoreMenu(false)
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PenSlotDot(slot: PenSlot, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            ONoteIcons.Pen,
            contentDescription = slot.tool.displayName,
            tint = Color(slot.color),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun IslandDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(20.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
private fun RenameDialog(
    heading: String,
    current: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var text by remember(current) { mutableStateOf(current) }
    var cleared by remember(current) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(heading) },
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
                onClick = { if (text.isNotBlank()) onRename(text.trim()) },
                enabled = text.isNotBlank()
            ) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun AddPageDialog(
    onDismiss: () -> Unit,
    onAdd: (PageBackground) -> Unit
) {
    var bg by remember { mutableStateOf(PageBackground.RULED) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New page") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(bg) }) { Text("Add page") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun sanitizeFileName(title: String): String {
    val clean = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    return clean.ifBlank { "Notebook" }
}