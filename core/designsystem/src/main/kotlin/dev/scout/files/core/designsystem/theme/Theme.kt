package dev.scout.files.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = ScoutPrimary,
    onPrimary = ScoutOnPrimary,
    primaryContainer = ScoutPrimaryContainer,
    onPrimaryContainer = ScoutOnPrimaryContainer,
    secondary = ScoutSecondary,
    onSecondary = ScoutOnSecondary,
    secondaryContainer = ScoutSecondaryContainer,
    onSecondaryContainer = ScoutOnSecondaryContainer,
    tertiary = ScoutTertiary,
    onTertiary = ScoutOnTertiary,
    tertiaryContainer = ScoutTertiaryContainer,
    onTertiaryContainer = ScoutOnTertiaryContainer,
    background = ScoutBackground,
    onBackground = ScoutOnBackground,
    surface = ScoutSurface,
    onSurface = ScoutOnSurface,
    surfaceVariant = ScoutSurfaceVariant,
    onSurfaceVariant = ScoutOnSurfaceVariant,
)

private val DarkColorScheme = darkColorScheme(
    primary = ScoutDarkPrimary,
    onPrimary = ScoutDarkOnPrimary,
    primaryContainer = ScoutDarkPrimaryContainer,
    onPrimaryContainer = ScoutDarkOnPrimaryContainer,
    secondary = ScoutDarkSecondary,
    onSecondary = ScoutDarkOnSecondary,
    secondaryContainer = ScoutDarkSecondaryContainer,
    onSecondaryContainer = ScoutDarkOnSecondaryContainer,
    tertiary = ScoutDarkTertiary,
    onTertiary = ScoutDarkOnTertiary,
    tertiaryContainer = ScoutDarkTertiaryContainer,
    onTertiaryContainer = ScoutDarkOnTertiaryContainer,
    background = ScoutDarkBackground,
    onBackground = ScoutDarkOnBackground,
    surface = ScoutDarkSurface,
    onSurface = ScoutDarkOnSurface,
    surfaceVariant = ScoutDarkSurfaceVariant,
    onSurfaceVariant = ScoutDarkOnSurfaceVariant,
)

@Composable
fun ScoutTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
