package com.pocketledger

import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ThemePreferencesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Before fun setup() { preferences.edit().clear().commit() }

    @Test fun `missing or unrecognized theme follows system while explicit themes override it`() {
        assertEquals(ThemeMode.SYSTEM, ThemePreferences.read(preferences))
        preferences.edit().putString("theme_mode", "unknown-future-value").commit()
        assertEquals(ThemeMode.SYSTEM, ThemePreferences.read(preferences))
        assertTrue(ThemeMode.SYSTEM.isDark(true))
        assertFalse(ThemeMode.SYSTEM.isDark(false))
        assertTrue(ThemeMode.DARK.isDark(false))
        assertFalse(ThemeMode.LIGHT.isDark(true))
    }

    @Test fun `appearance survives a fresh preferences read without changing budget or import settings`() {
        preferences.edit().putLong("budget_cents", 123456).putBoolean("automatic_sms", true).commit()
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.SYSTEM)) {
            ThemePreferences.save(preferences, mode)
            val reloaded = context.createConfigurationContext(context.resources.configuration)
                .getSharedPreferences("settings", Context.MODE_PRIVATE)
            assertEquals(mode, ThemePreferences.read(reloaded))
            assertEquals(123456L, reloaded.getLong("budget_cents", 0))
            assertTrue(reloaded.getBoolean("automatic_sms", false))
        }
    }

    @Test fun `both themes have readable text on main surfaces and distinct visible chart lines`() {
        fun contrast(first: Color, second: Color): Float {
            val a = first.luminance()
            val b = second.luminance()
            return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
        }
        for (colors in listOf(ledgerLightColors, ledgerDarkColors)) {
            val pairs = listOf(colors.onBackground to colors.background, colors.onSurface to colors.surface,
                colors.onSurface to colors.surfaceContainer, colors.onSurface to colors.surfaceContainerHigh,
                colors.onSurface to colors.surfaceContainerHighest, colors.onSurfaceVariant to colors.surfaceVariant,
                colors.onPrimary to colors.primary, colors.onPrimaryContainer to colors.primaryContainer,
                colors.onSecondary to colors.secondary, colors.onSecondaryContainer to colors.secondaryContainer,
                colors.onTertiary to colors.tertiary)
            pairs.forEach { (text, background) -> assertTrue("Text contrast must reach 4.5:1", contrast(text, background) >= 4.5f) }
            assertNotEquals(colors.primary, colors.tertiary)
            assertTrue("Spending line contrast", contrast(colors.primary, colors.surfaceContainerHighest) >= 3f)
            assertTrue("Balance line contrast", contrast(colors.tertiary, colors.surfaceContainerHighest) >= 3f)
        }
    }
}
