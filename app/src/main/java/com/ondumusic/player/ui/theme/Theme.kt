package com.ondumusic.player.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme
import com.ondumusic.player.settings.AccentColor

/**
 * The app icon's own blue. Both ends come out of the icon's background gradient: light
 * mode takes the middle of it, dark mode the top, because the middle goes muddy against
 * a dark ground.
 */
private val IconBlueLight = Color(0.219f, 0.406f, 0.946f)
private val IconBlueDark = Color(0.300f, 0.519f, 1.000f)

/** The iOS system colours the other presets are named after, so an accent means the same on both apps. */
fun AccentColor.accentSeed(dark: Boolean): Color = when (this) {
    AccentColor.Blue, AccentColor.Wallpaper -> if (dark) IconBlueDark else IconBlueLight
    AccentColor.Indigo -> Color(0xFF5856D6)
    AccentColor.Purple -> Color(0xFFAF52DE)
    AccentColor.Pink -> Color(0xFFFF2D55)
    AccentColor.Red -> Color(0xFFFF3B30)
    AccentColor.Orange -> Color(0xFFFF9500)
    AccentColor.Yellow -> Color(0xFFFFCC00)
    AccentColor.Green -> Color(0xFF34C759)
    AccentColor.Teal -> Color(0xFF30B0C7)
}

@Composable
fun OnduTheme(
    accent: AccentColor = AccentColor.Blue,
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (accent == AccentColor.Wallpaper) {
        val context = LocalContext.current
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        // Fidelity keeps the primary colour at the accent the user picked rather than a
        // muted tone of it; the rest of the scheme is derived around it.
        rememberDynamicColorScheme(seedColor = accent.accentSeed(dark), isDark = dark, style = PaletteStyle.Fidelity)
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
