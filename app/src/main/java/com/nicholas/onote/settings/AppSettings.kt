package com.nicholas.onote.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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