package com.luke.tracks.tv

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import com.luke.tracks.data.PaletteChoice
import com.luke.tracks.ui.theme.LocalDarkTheme
import com.luke.tracks.ui.theme.TracksBrand
import com.luke.tracks.ui.tv.theme.TvTracksTheme
import com.luke.tracks.ui.tv.theme.TvTypography
import com.luke.tracks.ui.tv.theme.toTvColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.tv.material3.MaterialTheme as TvTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TvThemeBridgeTest {
    @get:Rule val compose = createComposeRule()

    /** tv-material's roles that Material 3 names differently. */
    private val renamed = mapOf("border" to "outline", "borderVariant" to "outlineVariant")

    @Test fun everyTvRoleComesFromItsMaterialRole() {
        for (m3 in listOf(lightColorScheme(), darkColorScheme(), distinctScheme())) {
            val tv = m3.toTvColorScheme()
            val tvRoles = colorRoles(tv)
            val m3Roles = colorRoles(m3)
            assertEquals(29, tvRoles.size)
            tvRoles.forEach { (role, color) ->
                val source = renamed[role] ?: role
                assertEquals("$role", m3Roles.getValue(source), color)
            }
        }
    }

    @Test fun bothThemesAgreeAndFollowLightAndDark() {
        val seen = mutableListOf<Triple<Color, Color, Boolean>>()
        val dark = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            TvTracksTheme(dark = dark.value, dynamic = false, seed = TracksBrand) {
                seen += Triple(M3Theme.colorScheme.primary, TvTheme.colorScheme.primary, LocalDarkTheme.current)
                assertEquals(M3Theme.colorScheme.surface, TvTheme.colorScheme.surface)
                assertEquals(TvTypography.bodyLarge.fontSize, TvTheme.typography.bodyLarge.fontSize)
                assertEquals(TvTypography.titleLarge.fontSize, M3Theme.typography.titleLarge.fontSize)
            }
        }
        compose.waitForIdle()
        dark.value = true
        compose.waitForIdle()
        val light = seen.first()
        val night = seen.last()
        assertEquals(light.first, light.second)
        assertEquals(night.first, night.second)
        assertEquals(false, light.third)
        assertEquals(true, night.third)
        assertNotEquals(light.first, night.first)
    }

    @Test fun pureBlackReachesTheTvBackground() {
        var background = Color.Unspecified
        compose.setContent {
            TvTracksTheme(dark = true, dynamic = false, seed = TracksBrand, style = PaletteChoice.TonalSpot, pureBlack = true) {
                background = TvTheme.colorScheme.background
            }
        }
        compose.waitForIdle()
        assertEquals(Color.Black, background)
    }

    @Test fun theTvScaleIsTheTenFootOne() {
        val t = TvTypography
        val sizes = listOf(t.displayLarge, t.displayMedium, t.headlineLarge, t.headlineMedium, t.titleLarge, t.titleMedium, t.bodyLarge, t.bodyMedium, t.labelLarge, t.labelMedium)
            .map { it.fontSize.value.toInt() }
        assertEquals(listOf(57, 45, 32, 28, 22, 18, 16, 14, 14, 12), sizes)
        assertTrue(t.bodyLarge.fontFamily != null)
    }

    /** Each role its own color, so a role read from the wrong source can't pass by accident. */
    private fun distinctScheme() = lightColorScheme().let { base ->
        var n = 0
        val roles = colorRoles(base).keys
        val values = roles.associateWith { Color(0xFF000000.toInt() or (++n * 0x010203)) }
        lightColorScheme(
            primary = values.getValue("primary"), onPrimary = values.getValue("onPrimary"),
            primaryContainer = values.getValue("primaryContainer"), onPrimaryContainer = values.getValue("onPrimaryContainer"),
            inversePrimary = values.getValue("inversePrimary"),
            secondary = values.getValue("secondary"), onSecondary = values.getValue("onSecondary"),
            secondaryContainer = values.getValue("secondaryContainer"), onSecondaryContainer = values.getValue("onSecondaryContainer"),
            tertiary = values.getValue("tertiary"), onTertiary = values.getValue("onTertiary"),
            tertiaryContainer = values.getValue("tertiaryContainer"), onTertiaryContainer = values.getValue("onTertiaryContainer"),
            background = values.getValue("background"), onBackground = values.getValue("onBackground"),
            surface = values.getValue("surface"), onSurface = values.getValue("onSurface"),
            surfaceVariant = values.getValue("surfaceVariant"), onSurfaceVariant = values.getValue("onSurfaceVariant"),
            surfaceTint = values.getValue("surfaceTint"),
            inverseSurface = values.getValue("inverseSurface"), inverseOnSurface = values.getValue("inverseOnSurface"),
            error = values.getValue("error"), onError = values.getValue("onError"),
            errorContainer = values.getValue("errorContainer"), onErrorContainer = values.getValue("onErrorContainer"),
            outline = values.getValue("outline"), outlineVariant = values.getValue("outlineVariant"),
            scrim = values.getValue("scrim"),
        )
    }

    /** A scheme's colors by role name, read through its getters (Color getters are name-mangled). */
    private fun colorRoles(scheme: Any): Map<String, Color> =
        scheme.javaClass.methods
            .filter { it.parameterCount == 0 && it.name.startsWith("get") && it.name.contains('-') && it.returnType == Long::class.javaPrimitiveType }
            .associate { method ->
                val role = method.name.removePrefix("get").substringBefore('-').replaceFirstChar { it.lowercase() }
                role to Color((method.invoke(scheme) as Long).toULong())
            }
}
