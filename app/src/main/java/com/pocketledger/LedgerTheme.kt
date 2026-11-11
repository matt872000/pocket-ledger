package com.pocketledger

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

enum class ThemeMode(val label: String) {
    SYSTEM("System"), LIGHT("Light"), DARK("Dark");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}

object ThemePreferences {
    fun read(preferences: SharedPreferences): ThemeMode =
        ThemeMode.entries.firstOrNull { it.name == preferences.getString("theme_mode", null) } ?: ThemeMode.SYSTEM

    fun save(preferences: SharedPreferences, mode: ThemeMode) {
        preferences.edit().putString("theme_mode", mode.name).apply()
    }
}

internal val ledgerLightColors = lightColorScheme(
    primary = Color(0xFF21665B), onPrimary = Color.White,
    primaryContainer = Color(0xFFDAEDE3), onPrimaryContainer = Color(0xFF102D25),
    secondary = Color(0xFF586B62), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDAE7DE), onSecondaryContainer = Color(0xFF142B22),
    tertiary = Color(0xFF396EAF), onTertiary = Color.White,
    background = Color(0xFFF6F7F2), onBackground = Color(0xFF191D19),
    surface = Color(0xFFFCFDF8), onSurface = Color(0xFF191D19),
    surfaceVariant = Color(0xFFE1E6DE), onSurfaceVariant = Color(0xFF414942),
    surfaceContainer = Color(0xFFECEFE8), surfaceContainerHigh = Color(0xFFE6EAE2),
    surfaceContainerHighest = Color(0xFFE1E6DE), surfaceContainerLow = Color(0xFFF2F5ED),
    surfaceContainerLowest = Color.White
)

internal val ledgerDarkColors = darkColorScheme(
    primary = Color(0xFF93D5BF), onPrimary = Color(0xFF00382B),
    primaryContainer = Color(0xFF254F44), onPrimaryContainer = Color(0xFFB5EFDA),
    secondary = Color(0xFFB7CCC2), onSecondary = Color(0xFF24372E),
    secondaryContainer = Color(0xFF3B4D44), onSecondaryContainer = Color(0xFFDAE7DE),
    tertiary = Color(0xFFA8C8FF), onTertiary = Color(0xFF003063),
    background = Color(0xFF101512), onBackground = Color(0xFFE0E6DE),
    surface = Color(0xFF101512), onSurface = Color(0xFFE0E6DE),
    surfaceVariant = Color(0xFF414942), onSurfaceVariant = Color(0xFFC1C9BF),
    surfaceContainer = Color(0xFF1B211C), surfaceContainerHigh = Color(0xFF252B26),
    surfaceContainerHighest = Color(0xFF303630), surfaceContainerLow = Color(0xFF171D18),
    surfaceContainerLowest = Color(0xFF0B100D),
    outline = Color(0xFF8B938A), outlineVariant = Color(0xFF414942)
)

@Composable
fun ThemeSettings(mode: ThemeMode, onMode: (ThemeMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleLarge)
        Text("Choose a theme. System follows your phone's dark mode setting.", style = MaterialTheme.typography.bodySmall)
        Column(Modifier.selectableGroup()) {
            ThemeMode.entries.forEach { option ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected = mode == option, onClick = { onMode(option) }, role = Role.RadioButton),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == option, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(option.label)
                }
            }
        }
    }
}
