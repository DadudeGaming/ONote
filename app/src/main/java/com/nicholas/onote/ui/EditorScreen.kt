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
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.derivedStateOf
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
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.core.content.FileProvider
import com.nicholas.onote.data.NoteDocument
import com.nicholas.onote.data.NotePage
import com.nicholas.onote.data.Orientation
import com.nicholas.onote.data.PageMode
import com.nicholas.onote.data.PageSource
import com.nicholas.onote.data.PlacedImage
import com.nicholas.onote.drawing.DrawingCanvasView
import com.nicholas.onote.drawing.DrawingEngine
import com.nicholas.onote.drawing.GestureAction
import com.nicholas.onote.drawing.PageBackground
import com.nicholas.onote.drawing.ToolMode
import com.nicholas.onote.pdf.PdfExporter
import com.nicholas.onote.settings.AppSettings
import com.nicholas.onote.settings.PenSlot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
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
    onDelete: (deleteFromDrive: Boolean) -> Unit,
    onRenamed: (String) -> Unit,
    onDriveSaved: (Uri, NoteDocument) -> Unit,
    onShareOnote: (NoteDocument) -> Unit,
    onRemoveFromDrive: () -> Unit
) {
    val engine = notebook.engine
    var canvasView by remember { mutableStateOf<DrawingCanvasView?>(null) }
    var penDialogIndex by remember { mutableStateOf(-1) }
    var eraserDialogOpen by remember { mutableStateOf(false) }
    var paperMenuOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var renameDialogOpen by remember { mutableStateOf(false) }
    var deleteDialogOpen by remember { mutableStateOf(false) }
    var docSettingsOpen by remember { mutableStateOf(false) }
    var pageMenuFor by remember { mutableStateOf<Int?>(null) }
    var pageRenameFor by remember { mutableStateOf<Int?>(null) }
    var deleteTargetPage by remember { mutableStateOf<Int?>(null) }

    val pagesMode = notebook.doc.pageMode == PageMode.PAGES

    // Derived so the undo/redo buttons light up whenever the history changes
    // (e.g. the 2-finger double-tap undo gesture).
    val canUndo by remember { derivedStateOf { engine.editCount; engine.canUndo } }
    val canRedo by remember { derivedStateOf { engine.editCount; engine.canRedo } }

    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) {
            onSave(notebook)
            PdfExporter.export(context.contentResolver, uri, notebook.doc, context.filesDir)
        }
    }

    var imageImportDialogOpen by remember { mutableStateOf(false) }
    var imageAsPage by remember { mutableStateOf(false) }
    var imageToolsActive by remember { mutableStateOf(false) }
    var dontAskTrash by remember { mutableStateOf(false) }
    var pageDontAsk by remember { mutableStateOf(false) }

    val driveBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) onDriveSaved(uri, notebook.doc)
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
            // Change which page the engine is hydrated from WITHOUT moving the
            // camera - otherwise a pen stroke landing on another page jerks the
            // view, smearing a huge vertical line.
            canvasView?.setFlowPages(notebook.doc.pages, clamped)
        }
        invalidate()
    }

    fun insertPage(page: NotePage) {
        onSave(notebook)
        notebook.doc.pages.add(page)
        notebook.currentPageIndex = notebook.doc.pages.lastIndex
        engine.applyDocument(notebook.source())
        if (pagesMode) {
            canvasView?.setFlowPages(notebook.doc.pages, notebook.currentPageIndex)
            canvasView?.scrollToPage(notebook.currentPageIndex)
        }
        invalidate()
    }

    fun addPage(background: PageBackground, orientation: Orientation) {
        insertPage(NotePage(UUID.randomUUID().toString(), background, orientation))
    }

    /** Runs whatever action a user-assigned gesture maps to. */
    fun handleGestureAction(action: GestureAction, pageIndex: Int) {
        when (action) {
            GestureAction.NONE, GestureAction.MOVE_PAGE, GestureAction.PAN_ZOOM -> {}
            GestureAction.UNDO -> {
                engine.undo()
                invalidate()
                onSave(notebook)
            }
            GestureAction.REDO -> {
                engine.redo()
                invalidate()
                onSave(notebook)
            }
            GestureAction.PAGE_MENU -> {
                if (pagesMode && pageIndex in notebook.doc.pages.indices) {
                    onSave(notebook)
                    pageMenuFor = pageIndex
                }
            }
            GestureAction.NEW_PAGE -> if (pagesMode) {
                addPage(notebook.doc.insertBackground, notebook.doc.insertOrientation)
            }
            GestureAction.TOGGLE_ERASER -> {
                if (engine.toolMode == ToolMode.ERASER) eraserDialogOpen = true
                else {
                    engine.toolMode = ToolMode.ERASER
                    invalidate()
                }
            }
            GestureAction.TOGGLE_PALM -> {
                engine.palmRejection = !engine.palmRejection
                settings.updatePalmRejection(engine.palmRejection)
                invalidate()
            }
            GestureAction.TOGGLE_HUD -> {
                engine.debugEnabled = !engine.debugEnabled
                settings.updateHudEnabled(engine.debugEnabled)
                invalidate()
            }
            GestureAction.HOME -> onHome()
            GestureAction.NEW_NOTEBOOK -> onNewTab()
        }
    }

    /** Imports an image onto the active page (pages mode) or doc centre (infinite). */
    fun placeImageOnActive(path: String, sourceW: Int, sourceH: Int) {
        if (sourceW <= 0 || sourceH <= 0) return
        onSave(notebook)
        val id = UUID.randomUUID().toString()
        val img = if (pagesMode) {
            val page = notebook.doc.pages.getOrNull(notebook.currentPageIndex) ?: return
            var w = page.width * 0.7f
            var h = w * (sourceH.toFloat() / sourceW)
            if (h > page.height * 0.55f) {
                h = page.height * 0.55f
                w = h * (sourceW.toFloat() / sourceH)
            }
            PlacedImage(id, path, (page.width - w) / 2f, 140f, w, h)
        } else {
            val vw = (canvasView?.width ?: 1000).toFloat().coerceAtLeast(400f)
            val vh = (canvasView?.height ?: 1000).toFloat().coerceAtLeast(400f)
            val t = engine.transform
            val cx = t.screenToDocX(vw / 2f)
            val cy = t.screenToDocY(vh / 2f)
            val w = 420f
            val h = w * (sourceH.toFloat() / sourceW)
            PlacedImage(id, path, cx - w / 2f, cy - h / 2f, w, h)
        }
        if (pagesMode) {
            notebook.doc.pages[notebook.currentPageIndex].images.add(img)
        } else {
            notebook.doc.images.add(img)
            canvasView?.infiniteImages = notebook.doc.images
        }
        canvasView?.startPlacingImage(id)
        imageToolsActive = true
        invalidate()
    }

    /** Imports an image as a new page that fits the picture. */
    fun insertImageAsPage(path: String, sourceW: Int, sourceH: Int) {
        if (sourceW <= 0 || sourceH <= 0) return
        val page = NotePage(
            UUID.randomUUID().toString(),
            notebook.doc.insertBackground,
            notebook.doc.insertOrientation
        )
        var w = page.width - 120f
        var h = w * (sourceH.toFloat() / sourceW)
        if (h > page.height - 320f) {
            h = page.height - 320f
            w = h * (sourceW.toFloat() / sourceH)
        }
        page.images.add(PlacedImage(UUID.randomUUID().toString(), path, 60f, 160f, w, h))
        insertPage(page)
    }

    /** Renders every PDF page as a new notebook page (raster image). */
    fun importPdfAsPages(uri: Uri) {
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return
        onSave(notebook)
        var count = 0
        runCatching {
            PdfRenderer(fd).use { renderer ->
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { pdfPage ->
                        val w = pdfPage.width
                        val h = pdfPage.height
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        pdfPage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val path = writeBitmapToImages(context, bmp)
                        if (path != null) {
                            val orientation =
                                if (w > h) Orientation.LANDSCAPE else Orientation.PORTRAIT
                            val notePage = NotePage(
                                UUID.randomUUID().toString(),
                                PageBackground.RULED,
                                orientation
                            )
                            val pw0 = notePage.width - 160f
                            var ph = pw0 * (h.toFloat() / w)
                            if (ph > notePage.height - 200f) {
                                ph = notePage.height - 200f
                            }
                            val pw = ph * (w.toFloat() / h)
                            notePage.images.add(
                                PlacedImage(
                                    UUID.randomUUID().toString(),
                                    path, 80f, 120f, pw, ph
                                )
                            )
                            notebook.doc.pages.add(notePage)
                            count++
                        }
                    }
                }
            }
        }
        runCatching { fd.close() }
        if (count > 0) {
            notebook.currentPageIndex = notebook.doc.pages.lastIndex
            engine.applyDocument(notebook.source())
            canvasView?.setFlowPages(notebook.doc.pages, notebook.currentPageIndex)
            canvasView?.scrollToPage(notebook.currentPageIndex)
            invalidate()
            onSave(notebook)
        }
    }

    // Picker/camera launchers are declared after the import helpers they use.
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val path = uri?.let { copyUriToImages(context, it) }
        if (path != null) {
            val (w, h) = imageDims(context, path)
            if (imageAsPage) insertImageAsPage(path, w, h) else placeImageOnActive(path, w, h)
        }
        imageAsPage = false
        onSave(notebook)
    }

    val pdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) importPdfAsPages(uri)
    }

    val cameraUri = remember {
        val dir = File(context.filesDir, "camera")
        dir.mkdirs()
        FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            File(dir, "capture_${System.currentTimeMillis()}.jpg")
        )
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        if (ok) {
            val path = copyUriToImages(context, cameraUri)
            if (path != null) {
                val (w, h) = imageDims(context, path)
                placeImageOnActive(path, w, h)
                onSave(notebook)
            }
        }
    }

    fun setPageBackground(index: Int, background: PageBackground) {
        notebook.doc.pages[index].pageBackground = background
        if (index == notebook.currentPageIndex) {
            engine.pageBackground = background
        }
        canvasView?.setFlowPages(notebook.doc.pages, notebook.currentPageIndex)
        invalidate()
        onSave(notebook)
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
            backedUp = { settings.driveLink(it) != null },
            onSelect = onSelectTab,
            onClose = onCloseTab,
            onNew = onNewTab,
            onHome = onHome,
            onSettings = onOpenSettings
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            key(notebook.doc.id) {
                AndroidView(
                    factory = { context ->
                        DrawingCanvasView(context, engine, pagesMode = pagesMode)
                    },
                    update = { view ->
                        canvasView = view
                        view.onRequestNewPage = {
                            addPage(notebook.doc.insertBackground, notebook.doc.insertOrientation)
                        }
                        view.input.pageFlowEnabled = pagesMode
                        view.input.pageCount = maxOf(1, notebook.doc.pages.size)
                        view.input.flowIndex = notebook.currentPageIndex
                        view.input.gestureAction = { key -> settings.gestureAction(key) }
                        view.input.onGestureAction = { action, pageIndex ->
                            handleGestureAction(action, pageIndex)
                        }
                        view.input.onActivePageChanged = { idx ->
                            if (idx != notebook.currentPageIndex) changeActivePage(idx)
                        }
                        if (pagesMode) {
                            view.setFlowPages(notebook.doc.pages, notebook.currentPageIndex)
                        }
                        view.infiniteImages = if (pagesMode) emptyList() else notebook.doc.images
                        view.imageToolActive = imageToolsActive
                        view.onImagesChanged = {
                            onSave(notebook)
                            invalidate()
                        }
                        // Push app toggles into the engine synchronously (before the
                        // first frame), and re-invalidate so the debug HUD can't
                        // linger after being switched off.
                        engine.palmRejection = settings.palmRejection
                        engine.debugEnabled = settings.hudEnabled
                        engine.eraseRadius = settings.eraserRadius
                        view.invalidate()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            LandingIsland(
                engine = engine,
                settings = settings,
                canUndo = canUndo,
                canRedo = canRedo,
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
                onRename = { renameDialogOpen = true },
                onAddPage = {
                    addPage(notebook.doc.insertBackground, notebook.doc.insertOrientation)
                },
                onDocumentSettings = { docSettingsOpen = true },
                onDelete = {
                    if (settings.confirmTrashDialog) {
                        deleteDialogOpen = true
                    } else {
                        onDelete(false)
                    }
                },
                onExport = { exportLauncher.launch(sanitizeFileName(notebook.doc.title) + ".pdf") },
                onDriveBackup = {
                    driveBackupLauncher.launch(sanitizeFileName(notebook.doc.title) + ".onote")
                },
                onShareOnote = { onShareOnote(notebook.doc) },
                onRemoveFromDrive = { onRemoveFromDrive() },
                showRemoveFromDrive = settings.driveLink(notebook.doc.id) != null ||
                    settings.driveFolderUri != null,
                onTogglePalm = {
                    engine.palmRejection = !engine.palmRejection
                    settings.updatePalmRejection(engine.palmRejection)
                    invalidate()
                },
                onToggleHud = {
                    engine.debugEnabled = !engine.debugEnabled
                    settings.updateHudEnabled(engine.debugEnabled)
                    invalidate()
                },
                onInsertImage = {
                    imageAsPage = false
                    imageImportDialogOpen = true
                },
                onImportPdf = { pdfPicker.launch("application/pdf") },
                onCamera = { cameraLauncher.launch(cameraUri) },
                imageToolsActive = imageToolsActive,
                onToggleImageTools = {
                    imageToolsActive = !imageToolsActive
                    if (!imageToolsActive) canvasView?.clearImageSelection()
                },
                hasImages = if (pagesMode)
                    notebook.doc.pages.any { it.images.isNotEmpty() }
                else notebook.doc.images.isNotEmpty()
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
            text = {
                Column {
                    Text("\"${notebook.doc.title}\" will move to the trash. You can restore it from Home.")
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
                    if (dontAskTrash) settings.updateConfirmTrashDialog(false)
                    deleteDialogOpen = false
                    onDelete(false)
                }) { Text("Move to trash") }
            },
            dismissButton = {
                TextButton(onClick = { deleteDialogOpen = false }) { Text("Cancel") }
            }
        )
    }

    if (docSettingsOpen) {
        DocumentSettingsDialog(
            background = notebook.doc.insertBackground,
            orientation = notebook.doc.insertOrientation,
            onDismiss = { docSettingsOpen = false },
            onApply = { bg, orientation ->
                notebook.doc.insertBackground = bg
                notebook.doc.insertOrientation = orientation
                onSave(notebook)
                docSettingsOpen = false
            }
        )
    }

    val menuPage = pageMenuFor
    if (menuPage != null && menuPage < notebook.doc.pages.size) {
        val page = notebook.doc.pages[menuPage]
        AlertDialog(
            onDismissRequest = { pageMenuFor = null },
            title = { Text("Page ${menuPage + 1}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        page.title.ifBlank { "(untitled page)" },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text("Paper style", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (paper in PageBackground.entries) {
                            TextButton(
                                onClick = { setPageBackground(menuPage, paper) },
                                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                    containerColor = if (page.pageBackground == paper)
                                        MaterialTheme.colorScheme.primaryContainer
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
                TextButton(onClick = {
                    pageRenameFor = menuPage
                    pageMenuFor = null
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (settings.confirmDeletePage) {
                        deleteTargetPage = menuPage
                        pageMenuFor = null
                    } else {
                        pageMenuFor = null
                        deletePageAt(menuPage)
                    }
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
                Column {
                    Text(if (notebook.doc.pages.size <= 1) "A notebook needs at least one page."
                    else "Page ${deletePage + 1} will be deleted.")
                    Row(
                        modifier = Modifier
                            .clickable { pageDontAsk = !pageDontAsk }
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = pageDontAsk, onCheckedChange = { pageDontAsk = it })
                        Text("Don't ask again", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (pageDontAsk) settings.updateConfirmDeletePage(false)
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

    if (imageImportDialogOpen) {
        AlertDialog(
            onDismissRequest = { imageImportDialogOpen = false },
            title = { Text("Insert image") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    DialogTextAction("Place on this page", "Tap to move, corner to resize") {
                        imageImportDialogOpen = false
                        imageAsPage = false
                        imagePicker.launch("image/*")
                    }
                    if (pagesMode) {
                        DialogTextAction("New page from image", "Fits the picture on its own page") {
                            imageImportDialogOpen = false
                            imageAsPage = true
                            imagePicker.launch("image/*")
                        }
                    }
                    if (pagesMode) {
                        DialogTextAction("Import PDF…", "Each page becomes a notebook page") {
                            imageImportDialogOpen = false
                            pdfPicker.launch("application/pdf")
                        }
                    }
                    DialogTextAction("Camera", "Capture and place on the active page") {
                        imageImportDialogOpen = false
                        cameraLauncher.launch(cameraUri)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { imageImportDialogOpen = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DialogTextAction(label: String, description: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Copies a content [uri] into the app's images folder; returns the store-relative path. */
private fun copyUriToImages(context: Context, uri: Uri): String? {
    val name = "images/" + UUID.randomUUID().toString() + ".img"
    val target = File(context.filesDir, name)
    val ok = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { input.copyTo(it) }
        } != null
    }.getOrDefault(false)
    return if (ok) name else null
}

/** Decodes just the header to learn an image's pixel size (cheap, no full decode). */
private fun imageDims(context: Context, relPath: String): Pair<Int, Int> {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(File(context.filesDir, relPath).absolutePath, opts)
    return (opts.outWidth to opts.outHeight)
}

/** Saves [bmp] as a PNG beside the app's other images; returns the relative path. */
private fun writeBitmapToImages(context: Context, bmp: Bitmap): String? {
    val name = "images/" + UUID.randomUUID().toString() + ".png"
    val ok = runCatching {
        File(context.filesDir, name).outputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bmp.recycle()
        true
    }.getOrDefault(false)
    return if (ok) name else null
}

@Composable
private fun TabStrip(
    openNotebooks: List<OpenNotebook>,
    activeIndex: Int,
    backedUp: (String) -> Boolean,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onNew: () -> Unit,
    onHome: () -> Unit,
    onSettings: () -> Unit
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
                        backedUp = backedUp(nb.doc.id),
                        showClose = openNotebooks.size > 1,
                        onClick = { onSelect(i) },
                        onClose = { onClose(i) }
                    )
                }
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(42.dp)) {
                Icon(
                    ONoteIcons.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurface
                )
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
    backedUp: Boolean,
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
            .padding(start = 8.dp, end = if (showClose) 2.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (backedUp) {
            Icon(
                ONoteIcons.Cloud,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(14.dp)
            )
        }
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
    onRename: () -> Unit,
    onAddPage: () -> Unit,
    onDocumentSettings: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onDriveBackup: () -> Unit,
    onShareOnote: () -> Unit,
    onRemoveFromDrive: () -> Unit,
    showRemoveFromDrive: Boolean,
    onTogglePalm: () -> Unit,
    onToggleHud: () -> Unit,
    onInsertImage: () -> Unit,
    onImportPdf: () -> Unit,
    onCamera: () -> Unit,
    imageToolsActive: Boolean,
    onToggleImageTools: () -> Unit,
    hasImages: Boolean
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
                IconButton(
                    onClick = onToggleImageTools,
                    enabled = hasImages,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        ONoteIcons.Image,
                        contentDescription = "Arrange images",
                        tint = if (imageToolsActive) MaterialTheme.colorScheme.primary else auf,
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
                            leadingIcon = {
                                Icon(ONoteIcons.Pen, contentDescription = null, modifier = Modifier.size(20.dp))
                            },
                            text = { Text(if (engine.palmRejection) "Palm rejection: off" else "Palm rejection: on") },
                            onClick = {
                                onMoreMenu(false)
                                onTogglePalm()
                            }
                        )
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(ONoteIcons.Page, contentDescription = null, modifier = Modifier.size(20.dp))
                            },
                            text = { Text(if (engine.debugEnabled) "Debug HUD: on" else "Debug HUD: off") },
                            onClick = {
                                onMoreMenu(false)
                                onToggleHud()
                            }
                        )
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(ONoteIcons.Page, contentDescription = null, modifier = Modifier.size(20.dp))
                            },
                            text = { Text("Document…") },
                            onClick = {
                                onMoreMenu(false)
                                onDocumentSettings()
                            }
                        )
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(ONoteIcons.Pen, contentDescription = null, modifier = Modifier.size(20.dp))
                            },
                            text = { Text("Rename") },
                            onClick = {
                                onMoreMenu(false)
                                onRename()
                            }
                        )
                        if (pagesMode) {
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(ONoteIcons.Plus, contentDescription = null, modifier = Modifier.size(20.dp))
                                },
                                text = { Text("Add page") },
                                onClick = {
                                    onMoreMenu(false)
                                    onAddPage()
                                }
                            )
                        }
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(ONoteIcons.Image, contentDescription = null, modifier = Modifier.size(20.dp))
                            },
                            text = { Text("Insert image…") },
                            onClick = {
                                onMoreMenu(false)
                                onInsertImage()
                            }
                        )
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(ONoteIcons.Camera, contentDescription = null, modifier = Modifier.size(20.dp))
                            },
                            text = { Text("Camera…") },
                            onClick = {
                                onMoreMenu(false)
                                onCamera()
                            }
                        )
                        if (pagesMode) {
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(ONoteIcons.Page, contentDescription = null, modifier = Modifier.size(20.dp))
                                },
                                text = { Text("Import PDF…") },
                                onClick = {
                                    onMoreMenu(false)
                                    onImportPdf()
                                }
                            )
                        }
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(ONoteIcons.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                            },
                            text = { Text("Export PDF") },
                            onClick = {
                                onMoreMenu(false)
                                onExport()
                            }
                        )
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(ONoteIcons.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                            },
                            text = { Text("Export .onote…") },
                            onClick = {
                                onMoreMenu(false)
                                onDriveBackup()
                            }
                        )
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(ONoteIcons.Share, contentDescription = null, modifier = Modifier.size(20.dp))
                            },
                            text = { Text("Share .onote") },
                            onClick = {
                                onMoreMenu(false)
                                onShareOnote()
                            }
                        )
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(ONoteIcons.Trash, contentDescription = null, modifier = Modifier.size(20.dp))
                            },
                            text = { Text("Move to trash") },
                            onClick = {
                                onMoreMenu(false)
                                onDelete()
                            }
                        )
                        if (showRemoveFromDrive) {
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(ONoteIcons.Cloud, contentDescription = null, modifier = Modifier.size(20.dp))
                                },
                                text = { Text("Remove from Google Drive") },
                                onClick = {
                                    onMoreMenu(false)
                                    onRemoveFromDrive()
                                }
                            )
                        }
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
private fun DocumentSettingsDialog(
    background: PageBackground,
    orientation: Orientation,
    onDismiss: () -> Unit,
    onApply: (PageBackground, Orientation) -> Unit
) {
    var bg by remember { mutableStateOf(background) }
    var orient by remember { mutableStateOf(orientation) }

    @Composable
    fun PaperChip(label: String, selected: Boolean, onClick: () -> Unit) {
        TextButton(
            onClick = onClick,
            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Document settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("New pages use", style = MaterialTheme.typography.labelLarge)
                Text("Paper style", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (paper in PageBackground.entries) {
                        PaperChip(paper.displayName, bg == paper) { bg = paper }
                    }
                }
                Text("Orientation", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (o in Orientation.entries) {
                        PaperChip(o.displayName, orient == o) { orient = o }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(bg, orient) }) { Text("Done") }
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