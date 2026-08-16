/*
 * Copyright (C) 2026 The Airgate project contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.airgate.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Clean Standard Android Light Theme (brand #0055EA accent)
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0055EA),          // Brand Blue (#0055EA)
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEFF6FF),
    onPrimaryContainer = Color(0xFF1E40AF),
    secondary = Color(0xFF0055EA),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1F5F9),
    onSecondaryContainer = Color(0xFF0F172A),
    tertiary = Color(0xFFD97706),         // Warm Amber — warning / risk accent
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF78350F),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

// High-Contrast Pure OLED Dark Mode
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF0055EA),          // Brand Blue (#0055EA)
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E293B),
    onPrimaryContainer = Color(0xFFF8FAFC),
    secondary = Color(0xFF0055EA),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF1E293B),
    onSecondaryContainer = Color(0xFFF8FAFC),
    tertiary = Color(0xFFFBBF24),         // Bright Amber — warning / risk accent
    onTertiary = Color(0xFF1F2937),
    tertiaryContainer = Color(0xFF451A03),
    onTertiaryContainer = Color(0xFFFDE68A),
    background = Color(0xFF000000),       // Pure OLED True Black (#000000)
    onBackground = Color(0xFFFFFFFF),     // Pure White Primary Text
    surface = Color(0xFF121212),          // Subtle OLED Dark Card (#121212)
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1E1E1E),   // Padded Container Surface (#1E1E1E)
    onSurfaceVariant = Color(0xFFCBD5E1), // High-Contrast Light Grey Text
    outline = Color(0xFF525252),          // High-Contrast Visible Card Border (#525252)
    outlineVariant = Color(0xFF404040),
    error = Color(0xFFEF4444),
    onError = Color.White,
    errorContainer = Color(0xFF450A0A),
    onErrorContainer = Color(0xFFFECACA)
)

// Full-screen destructive wipe surfaces. Intentionally deep crimson in both light
// and dark modes so the emergency state is unmistakable. The palette is built
// around a terminal/console framing: a near-black crimson log panel, amber warning
// beacon, and monospace log text on a crimson gradient backdrop.
object WipePalette {
    val backdropTop = Color(0xFF7F1D1D)   // full-screen gradient top
    val backdropBottom = Color(0xFF4A0E0E)// full-screen gradient bottom
    val container = Color(0xFF991B1B)     // main content container
    val containerDeep = Color(0xFF2A0707) // terminal / log panel background
    val panelBorder = Color(0xFFFCA5A5).copy(alpha = 0.28f)
    val badgeSurface = Color(0xFFFEF2F2)  // "DRY-RUN SIMULATION MODE" badge
    val badgeText = Color(0xFF991B1B)
    val bodyText = Color(0xFFFCA5A5)      // muted body copy
    val detailText = Color(0xFFFECACA)    // destructive-action list copy
    val terminalText = Color(0xFFFECACA)  // monospace log text
    val prompt = Color(0xFFFDA4AF)        // monospace prompt (">") color
    val beacon = Color(0xFFFBBF24)        // amber warning beacon accent
    val headline = Color.White
    val actionSurface = Color.White       // primary action button
    val actionText = Color(0xFF7F1D1D)
}

/**
 * App theme. By default the app renders with its fixed brand palette — the
 * #0055EA accent. The "Use System Colors" toggle in Settings opts into Material
 * You dynamic color (Android 12+): the palette then follows the device theme
 * (wallpaper/system accent) instead of the brand colour.
 */
@Composable
fun AirgateTheme(
    useSystemColors: Boolean = false,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // dynamicLightColorScheme/dynamicDarkColorScheme read many system resources and
    // are comparatively expensive; the root recomposes on every navigation, so cache
    // the result and only rebuild when the theme inputs actually change.
    val context = LocalContext.current
    val colors = remember(darkTheme, useSystemColors, context) {
        when {
            useSystemColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        content = content
    )
}

