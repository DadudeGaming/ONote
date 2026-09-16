package com.nicholas.onote.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.nicholas.onote.drawing.DrawingEngine
import com.nicholas.onote.settings.AppSettings
import com.nicholas.onote.settings.ThemeMode
import com.nicholas.onote.ui.theme.ONoteTheme

private enum class AppScreen { Notebook, Settings }

@Composable
fun ONoteApp() {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val engine = remember { DrawingEngine() }

    engine.palmRejection = settings.palmRejection
    engine.debugEnabled = settings.hudEnabled

    val darkTheme = when (settings.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    ONoteTheme(darkTheme = darkTheme) {
        var screen by rememberSaveable { mutableStateOf(AppScreen.Notebook) }

        when (screen) {
            AppScreen.Notebook -> NotebookScreen(
                engine = engine,
                settings = settings,
                onOpenSettings = { screen = AppScreen.Settings }
            )
            AppScreen.Settings -> SettingsScreen(
                settings = settings,
                engine = engine,
                onBack = { screen = AppScreen.Notebook }
            )
        }
    }
}