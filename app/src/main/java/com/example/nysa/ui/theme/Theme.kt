package com.example.nysa.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** True while the approved Liquid Glass look is on; false = the plain black theme. */
val LocalGlassEnabled = staticCompositionLocalOf { true }


private fun nySaScheme(glass: Boolean) = darkColorScheme(
    primary = NySaViolet,
    background = if (glass) NySaBackground else NySaBackgroundPlain,
    surface = if (glass) NySaSurface else NySaSurfacePlain,
    surfaceVariant = if (glass) NySaSurfaceAlt else NySaSurfaceAltPlain,
    onBackground = NySaText,
    onSurface = NySaText,
    onPrimary = Color.Black,
    error = NySaRed
)

@Composable
fun NySaTheme(
    glass: Boolean = true,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalGlassEnabled provides glass) {
        MaterialTheme(
            colorScheme = nySaScheme(glass),
            content = content
        )
    }
}
