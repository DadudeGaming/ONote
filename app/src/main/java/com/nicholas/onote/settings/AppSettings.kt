package com.nicholas.onote.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nicholas.onote.drawing.GestureAction
import com.nicholas.onote.drawing.PageBackground
import com.nicholas.onote.drawing.Tool
import com.nicholas.onote.data.PageMode
import org.json.JSONArray
import org.json.JSONObject

enum class ThemeMode(val displayName: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark")
}

/**
 * One quick-select writing implement on the floating toolbar. Highlighters are
 * rendered translucent via the renderer's alpha tint and blend mode.
 */
data class PenSlot(
    val tool: Tool,
    val color: Int,
    val width: Float
)

/**
 * Persisted user preferences. Holds Compose state so reading them in
 * composition triggers recomposition; changes written through the set* helpers
 * are persisted to SharedPreferences immediately.
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    var themeMode: ThemeMode by mutableStateOf(loadThemeMode())
    var palmRejection: Boolean by mutableStateOf(prefs.getBoolean(KEY_PALM, true))
    var hudEnabled: Boolean by mutableStateOf(prefs.getBoolean(KEY_HUD, true))

    var selectedSlot: Int by mutableStateOf(prefs.getInt(KEY_ACTIVE_SLOT, 0))
    var eraserRadius: Float by mutableStateOf(prefs.getFloat(KEY_ERASE, 26f))
    var penSlots: List<PenSlot> by mutableStateOf(loadPenSlots())

    var defaultPaper: PageBackground by mutableStateOf(
        runCatching { PageBackground.valueOf(prefs.getString(KEY_DEFAULT_PAPER, null) ?: "RULED") }
            .getOrDefault(PageBackground.RULED)
    )
    var defaultPageMode: PageMode by mutableStateOf(
        runCatching { PageMode.valueOf(prefs.getString(KEY_DEFAULT_MODE, null) ?: "INFINITE") }
            .getOrDefault(PageMode.INFINITE)
    )
    var resumeLast: Boolean by mutableStateOf(prefs.getBoolean(KEY_RESUME, true))
    var lastOpenedDoc: String? by mutableStateOf(prefs.getString(KEY_LAST_DOC, null))

    /** Per-gesture action overrides (figure key → action). Missing keys = defaults. */
    var gestureActions: Map<String, GestureAction> by mutableStateOf(loadGestureActions())

    /** When false, "Move to trash" acts immediately with no confirmation. */
    var confirmTrashDialog: Boolean by mutableStateOf(prefs.getBoolean(KEY_CONFIRM_TRASH, true))

    /** When false, "Delete page" removes the page without an "are you sure". */
    var confirmDeletePage: Boolean by mutableStateOf(prefs.getBoolean(KEY_CONFIRM_PAGE, true))

    /** driveLinks: notebook id → content:// URI backing it up on Google Drive. */
    private val driveLinks = HashMap<String, String>().apply {
        val raw = prefs.getString(KEY_DRIVE_LINKS, null) ?: return@apply
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                put(o.getString("id"), o.getString("uri"))
            }
        }
    }

    /** A linked Google Drive folder (SAF tree URI); notebooks auto-save there. */
    var driveFolderUri: String? by mutableStateOf(prefs.getString(KEY_DRIVE_FOLDER, null))

    /** The "ONote" folder inside [driveFolderUri] where backups are written. */
    var driveFolderDocId: String? by mutableStateOf(prefs.getString(KEY_DRIVE_FOLDER_ID, null))

    fun driveLink(docId: String): String? = driveLinks[docId]

    fun updateDriveLink(docId: String, uri: String) {
        driveLinks[docId] = uri
        persistDriveLinks()
    }

    fun updateDriveFolder(uri: String?, folderId: String?) {
        driveFolderUri = uri
        driveFolderDocId = folderId
        prefs.edit().apply {
            if (uri == null) remove(KEY_DRIVE_FOLDER) else putString(KEY_DRIVE_FOLDER, uri)
            if (folderId == null) remove(KEY_DRIVE_FOLDER_ID)
            else putString(KEY_DRIVE_FOLDER_ID, folderId)
        }.apply()
    }

    fun removeDriveLink(docId: String) {
        driveLinks.remove(docId)
        persistDriveLinks()
    }

    fun gestureAction(key: String): GestureAction =
        gestureActions[key] ?: DefaultGestures[key] ?: GestureAction.NONE

    fun updateGestureAction(key: String, action: GestureAction) {
        gestureActions = gestureActions + (key to action)
        persistGestureActions()
    }

    private fun loadGestureActions(): Map<String, GestureAction> {
        val raw = prefs.getString(KEY_GESTURES, null) ?: return emptyMap()
        return runCatching {
            val arr = JSONArray(raw)
            buildMap {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    put(o.getString("key"), GestureAction.valueOf(o.getString("action")))
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun persistGestureActions() {
        val arr = JSONArray()
        for ((key, action) in gestureActions) {
            val o = JSONObject()
            o.put("key", key)
            o.put("action", action.name)
            arr.put(o)
        }
        prefs.edit().putString(KEY_GESTURES, arr.toString()).apply()
    }

    private fun persistDriveLinks() {
        val arr = JSONArray()
        for ((id, uri) in driveLinks) {
            val o = JSONObject()
            o.put("id", id)
            o.put("uri", uri)
            arr.put(o)
        }
        prefs.edit().putString(KEY_DRIVE_LINKS, arr.toString()).apply()
    }

    fun updateThemeMode(mode: ThemeMode) {
        themeMode = mode
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    fun updateDefaultPaper(paper: PageBackground) {
        defaultPaper = paper
        prefs.edit().putString(KEY_DEFAULT_PAPER, paper.name).apply()
    }

    fun updateDefaultPageMode(mode: PageMode) {
        defaultPageMode = mode
        prefs.edit().putString(KEY_DEFAULT_MODE, mode.name).apply()
    }

    fun updateResumeLast(enabled: Boolean) {
        resumeLast = enabled
        prefs.edit().putBoolean(KEY_RESUME, enabled).apply()
    }

    fun updateLastOpenedDoc(id: String) {
        lastOpenedDoc = id
        prefs.edit().putString(KEY_LAST_DOC, id).apply()
    }

    fun updateConfirmTrashDialog(enabled: Boolean) {
        confirmTrashDialog = enabled
        prefs.edit().putBoolean(KEY_CONFIRM_TRASH, enabled).apply()
    }

    fun updateConfirmDeletePage(enabled: Boolean) {
        confirmDeletePage = enabled
        prefs.edit().putBoolean(KEY_CONFIRM_PAGE, enabled).apply()
    }

    fun updatePalmRejection(enabled: Boolean) {
        palmRejection = enabled
        prefs.edit().putBoolean(KEY_PALM, enabled).apply()
    }

    fun updateHudEnabled(enabled: Boolean) {
        hudEnabled = enabled
        prefs.edit().putBoolean(KEY_HUD, enabled).apply()
    }

    fun updateSelectedSlot(index: Int) {
        selectedSlot = index
        prefs.edit().putInt(KEY_ACTIVE_SLOT, index).apply()
    }

    fun updateEraserRadius(radius: Float) {
        eraserRadius = radius
        prefs.edit().putFloat(KEY_ERASE, radius).apply()
    }

    fun updatePenSlot(index: Int, slot: PenSlot) {
        if (index !in penSlots.indices) return
        penSlots = penSlots.toMutableList().also { it[index] = slot }
        persistPenSlots()
    }

    fun slot(index: Int): PenSlot =
        penSlots.getOrElse(index) { DefaultPenSlots[index.coerceIn(0, 5)] }

    private fun loadPenSlots(): List<PenSlot> {
        val raw = prefs.getString(KEY_SLOTS, null) ?: return DefaultPenSlots
        return runCatching {
            val arr = JSONArray(raw)
            List(6) { i ->
                val o = arr.getJSONObject(i)
                PenSlot(
                    tool = runCatching { Tool.valueOf(o.getString("tool")) }
                        .getOrDefault(Tool.BALLPOINT),
                    color = o.getInt("color"),
                    width = o.optDouble("width", 4.0).toFloat()
                )
            }
        }.getOrDefault(DefaultPenSlots)
    }

    private fun persistPenSlots() {
        val arr = JSONArray()
        for (s in penSlots) {
            val o = JSONObject()
            o.put("tool", s.tool.name)
            o.put("color", s.color)
            o.put("width", s.width.toDouble())
            arr.put(o)
        }
        prefs.edit().putString(KEY_SLOTS, arr.toString()).apply()
    }

    private fun loadThemeMode(): ThemeMode {
        val name = prefs.getString(KEY_THEME, null) ?: return ThemeMode.SYSTEM
        return runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.SYSTEM)
    }

    companion object {
        private const val PREFS_FILE = "onote_settings"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_PALM = "palm_rejection"
        private const val KEY_HUD = "hud_enabled"
        private const val KEY_SLOTS = "pen_slots"
        private const val KEY_ACTIVE_SLOT = "active_slot"
        private const val KEY_ERASE = "eraser_radius"
        private const val KEY_DEFAULT_PAPER = "default_paper"
        private const val KEY_DEFAULT_MODE = "default_mode"
        private const val KEY_RESUME = "resume_last"
        private const val KEY_LAST_DOC = "last_opened_doc"
        private const val KEY_CONFIRM_TRASH = "confirm_trash_dialog"
        private const val KEY_CONFIRM_PAGE = "confirm_delete_page"
        private const val KEY_DRIVE_LINKS = "drive_links"
        private const val KEY_DRIVE_FOLDER = "drive_folder"
        private const val KEY_DRIVE_FOLDER_ID = "drive_folder_id"
        private const val KEY_GESTURES = "gesture_actions"

        val DefaultGestures = mapOf(
            // Moves: 1 finger pans, 2 fingers pan & zoom (the classic two-finger
            // scroll), 3/4 fingers do nothing until assigned.
            "move1" to GestureAction.MOVE_PAGE,
            "move2" to GestureAction.PAN_ZOOM,
            "move3" to GestureAction.NONE,
            "move4" to GestureAction.NONE,
            // Taps: none by default.
            "tap1" to GestureAction.NONE,
            "tap2" to GestureAction.NONE,
            "tap3" to GestureAction.NONE,
            "tap4" to GestureAction.NONE,
            // Double taps: 2 = undo, 3 = redo (current behaviour).
            "dtap1" to GestureAction.NONE,
            "dtap2" to GestureAction.UNDO,
            "dtap3" to GestureAction.REDO,
            "dtap4" to GestureAction.NONE,
            // Holds: 3 fingers = page menu (current behaviour).
            "hold1" to GestureAction.NONE,
            "hold2" to GestureAction.NONE,
            "hold3" to GestureAction.PAGE_MENU,
            "hold4" to GestureAction.NONE
        )

        val DefaultPenSlots = listOf(
            PenSlot(Tool.BALLPOINT, 0xFF1A1A1A.toInt(), 4f),
            PenSlot(Tool.BALLPOINT, 0xFF3B5BD6.toInt(), 4f),
            PenSlot(Tool.BALLPOINT, 0xFFC62828.toInt(), 4f),
            PenSlot(Tool.BALLPOINT, 0xFF2E7D32.toInt(), 4f),
            PenSlot(Tool.PENCIL, 0xFF795548.toInt(), 3f),
            PenSlot(Tool.HIGHLIGHTER, 0xFFFFD600.toInt(), 40f)
        )

        /** Light/dark paper colour (dark is grey, not pure black). */
        const val PaperLight = 0xFFFFFFFF.toInt()
        const val PaperDark = 0xFF1E1E1F.toInt()

        /** Desk surface around the paper page in "Pages" mode (darker than paper). */
        const val DeskLight = 0xFF8A8A8A.toInt()
        const val DeskDark = 0xFF0B0B0C.toInt()
    }
}