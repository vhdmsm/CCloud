package com.pira.ccloud.ui.theme

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.pira.ccloud.utils.AppLanguage
import com.pira.ccloud.data.model.FontType
import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.pira.ccloud.data.model.FontSettings
import com.pira.ccloud.utils.StorageUtils

// Set of Material typography styles to start with
fun appTypography(fontFamily: FontFamily?): androidx.compose.material3.Typography {
    return androidx.compose.material3.Typography(
        bodyLarge = TextStyle(
            fontFamily = fontFamily ?: FontFamily.Default,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.5.sp
        ),
        bodyMedium = TextStyle(
            fontFamily = fontFamily ?: FontFamily.Default,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.25.sp
        ),
        bodySmall = TextStyle(
            fontFamily = fontFamily ?: FontFamily.Default,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.4.sp
        ),
        headlineLarge = TextStyle(
            fontFamily = fontFamily ?: FontFamily.Default,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            lineHeight = 40.sp,
            letterSpacing = 0.sp
        ),
        headlineMedium = TextStyle(
            fontFamily = fontFamily ?: FontFamily.Default,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            lineHeight = 36.sp,
            letterSpacing = 0.sp
        ),
        headlineSmall = TextStyle(
            fontFamily = fontFamily ?: FontFamily.Default,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            letterSpacing = 0.sp
        ),
        titleLarge = TextStyle(
            fontFamily = fontFamily ?: FontFamily.Default,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            letterSpacing = 0.sp
        ),
        titleMedium = TextStyle(
            fontFamily = fontFamily ?: FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.15.sp
        ),
        titleSmall = TextStyle(
            fontFamily = fontFamily ?: FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.1.sp
        ),
        labelLarge = TextStyle(
            fontFamily = fontFamily ?: FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.1.sp
        ),
        labelMedium = TextStyle(
            fontFamily = fontFamily ?: FontFamily.Default,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.5.sp
        ),
        labelSmall = TextStyle(
            fontFamily = fontFamily ?: FontFamily.Default,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.5.sp
        )
    )
}

// The dark cinema look: near-black screens, red accent, grey secondary text
private fun cinemaDarkScheme(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = accent.copy(alpha = 0.24f),
    onPrimaryContainer = Color.White,
    secondary = if (accent == CinemaRed) CinemaRedDark else accent,
    onSecondary = Color.White,
    secondaryContainer = CinemaSurfaceHigh,
    onSecondaryContainer = Color.White,
    tertiary = CinemaGold,
    onTertiary = Color.Black,
    background = CinemaBlack,
    onBackground = Color.White,
    surface = CinemaSurface,
    onSurface = Color.White,
    surfaceVariant = CinemaSurfaceHigh,
    onSurfaceVariant = CinemaGreyText,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = CinemaBlack,
    surfaceContainerLow = CinemaSurface,
    surfaceContainer = CinemaSurface,
    surfaceContainerHigh = CinemaSurfaceHigh,
    surfaceContainerHighest = Color(0xFF303030),
    inverseSurface = Color.White,
    inverseOnSurface = Color.Black,
    outline = CinemaOutline,
    outlineVariant = Color(0xFF333333),
    scrim = Color.Black
)

private fun cinemaLightScheme(accent: Color) = lightColorScheme(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = accent.copy(alpha = 0.14f),
    onPrimaryContainer = Color(0xFF1C1B1F),
    secondary = if (accent == CinemaRed) CinemaRedDark else accent,
    onSecondary = Color.White,
    tertiary = Color(0xFFB38600),
    onTertiary = Color.White,
    background = Color(0xFFF7F7F7),
    onBackground = Color(0xFF141414),
    surface = Color.White,
    onSurface = Color(0xFF141414),
    surfaceVariant = Color(0xFFECECEC),
    onSurfaceVariant = Color(0xFF5C5C5C),
    surfaceTint = Color.Transparent,
    outline = Color(0xFFBDBDBD)
)

@Composable
fun CCloudTheme(
    themeSettings: ThemeSettings = ThemeSettings(),
    fontSettings: FontSettings = FontSettings(), // Add font settings parameter
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val darkTheme = when (themeSettings.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    
    // Load font family based on font settings; Persian reads best in Vazirmatn unless another is chosen
    val fontType = if (AppLanguage.isPersian && fontSettings.fontType == FontType.DEFAULT) FontType.VAZIRMATN else fontSettings.fontType
    val fontFamily = FontManager.loadFontFamily(context, fontType)
    
    // The chosen accent (red by default) on the cinema neutrals
    val colorScheme = if (darkTheme) cinemaDarkScheme(themeSettings.primaryColor) else cinemaLightScheme(themeSettings.primaryColor)
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // The screens draw under the status bar, which shows their own dark (or light) top
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = appTypography(fontFamily)
    ) {
        // Persian is read right to left, so its screens are laid out mirrored
        CompositionLocalProvider(
            LocalLayoutDirection provides if (AppLanguage.isPersian) LayoutDirection.Rtl else LayoutDirection.Ltr,
            content = content
        )
    }
}