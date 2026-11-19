package com.pira.ccloud.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.edit

class ThemeManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)
    
    fun saveThemeSettings(settings: ThemeSettings) {
        prefs.edit {
            putString("theme_mode", settings.themeMode.name)
            putInt("primary_color", settings.primaryColor.toArgb())
            // Removed secondary_color as it's no longer used
        }
    }
    
    fun loadThemeSettings(): ThemeSettings {
        moveToCinemaLook()
        val themeModeStr = prefs.getString("theme_mode", ThemeMode.DARK.name) ?: ThemeMode.DARK.name
        val themeMode = try {
            ThemeMode.valueOf(themeModeStr)
        } catch (e: IllegalArgumentException) {
            ThemeMode.DARK
        }
        
        val primaryColor = prefs.getInt("primary_color", defaultPrimaryColor.toArgb())
        // Removed secondary_color as it's no longer used
        
        return ThemeSettings(
            themeMode = themeMode,
            primaryColor = Color(primaryColor)
            // Removed secondaryColor as it's no longer used
        )
    }

    /**
     * Once, when updating from a version before the cinema look: the old defaults (purple, follow
     * the system) were saved on every launch, so they are moved to the new ones (red, dark). A
     * color or mode the user picked themselves is kept.
     */
    private fun moveToCinemaLook() {
        if (prefs.getInt("style_version", 0) >= CINEMA_STYLE_VERSION) return
        prefs.edit {
            if (prefs.getInt("primary_color", legacyDefaultPrimaryColor.toArgb()) == legacyDefaultPrimaryColor.toArgb()) {
                putInt("primary_color", defaultPrimaryColor.toArgb())
            }
            if (prefs.getString("theme_mode", ThemeMode.SYSTEM.name) == ThemeMode.SYSTEM.name) {
                putString("theme_mode", ThemeMode.DARK.name)
            }
            putInt("style_version", CINEMA_STYLE_VERSION)
        }
    }

    private companion object {
        const val CINEMA_STYLE_VERSION = 2
    }
}

@Composable
fun rememberThemeManager(): ThemeManager {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember { ThemeManager(context) }
}

@Composable
fun rememberAppThemeSettings(): MutableState<ThemeSettings> {
    val themeManager = rememberThemeManager()
    val settings = remember { mutableStateOf(themeManager.loadThemeSettings()) }
    return settings
}
