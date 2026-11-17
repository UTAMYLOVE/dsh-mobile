package app.dshmobile.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogFonts
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.effectsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The M3 scheme and shapes of every theme (core §3.1): CLASSIC and the six-colour schemes are today's SakuraColors exactly. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThemeMaterialTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val drawn = listOf(ThemeId.SIGIL, ThemeId.STARDUST, ThemeId.ONE_STROKE, ThemeId.PETAL_STORM, ThemeId.TUNNEL)
    private val white = Color(0xFFFFFFFF)

    @Test fun theClassicPaletteBuildsSakuraColorsFieldByField() {
        assertSameScheme("themeScheme(Classic)", SakuraColors, themeScheme(ThemeColors.Classic))
    }

    @Test fun classicWearsSakuraColorsAndSakuraShapesThemselves() {
        for (mode in ColorMode.entries) {
            val skin = materialOf(ThemeId.CLASSIC, mode, ThemeColors.Classic)
            assertSame(SakuraColors, skin.scheme)
            assertSame(SakuraShapes, skin.shapes)
        }
    }

    @Test fun everyDrawnSchemeAndTheThreePlainSixSchemesAreSakuraColors() {
        for (id in drawn) for (mode in ColorMode.entries) {
            assertSameScheme("$id $mode", SakuraColors, skinOf(id, mode).scheme)
        }
        for (id in listOf(ThemeId.WEB_SAKURA, ThemeId.WEB_NEON, ThemeId.WEB_WAVE)) {
            assertSameScheme("$id SIX", SakuraColors, skinOf(id, ColorMode.SIX).scheme)
        }
        // Three drawn looks keep today's shapes too.
        for (id in listOf(ThemeId.STARDUST, ThemeId.ONE_STROKE, ThemeId.PETAL_STORM)) assertEquals("$id", SakuraShapes, skinOf(id, ColorMode.SIX).shapes)
    }

    @Test fun primariesOutlinesAndShapesFollowCoreTable() {
        for (id in ThemeId.entries) for (mode in ColorMode.entries) {
            val want = expected(id, mode)
            val skin = skinOf(id, mode)
            val label = "$id $mode"
            assertEquals("$label primary", want.primary, skin.scheme.primary)
            assertEquals("$label onPrimary", want.onPrimary, skin.scheme.onPrimary)
            assertEquals("$label outline", want.outline, skin.scheme.outline)
            assertEquals("$label shapes", want.shapes, skin.shapes)
        }
    }

    @Test fun everyOtherRoleOfTheSchemeComesFromThePalette() {
        for (id in ThemeId.entries) for (mode in ColorMode.entries) {
            val c = Palettes.of(id, mode)
            val s = skinOf(id, mode).scheme
            val label = "$id $mode"
            val pairs = listOf(
                "primaryContainer" to (s.primaryContainer to c.paper), "onPrimaryContainer" to (s.onPrimaryContainer to c.ink),
                "inversePrimary" to (s.inversePrimary to s.primary), "secondary" to (s.secondary to c.cool),
                "onSecondary" to (s.onSecondary to c.ink), "secondaryContainer" to (s.secondaryContainer to c.milk),
                "onSecondaryContainer" to (s.onSecondaryContainer to c.ink), "tertiary" to (s.tertiary to c.line),
                "onTertiary" to (s.onTertiary to c.paper), "background" to (s.background to c.stage),
                "onBackground" to (s.onBackground to c.ink), "surface" to (s.surface to c.stage), "onSurface" to (s.onSurface to c.ink),
                "surfaceVariant" to (s.surfaceVariant to c.paper), "onSurfaceVariant" to (s.onSurfaceVariant to c.ink2),
                "surfaceTint" to (s.surfaceTint to s.primary), "surfaceBright" to (s.surfaceBright to c.paper),
                "surfaceDim" to (s.surfaceDim to c.milk), "surfaceContainerLowest" to (s.surfaceContainerLowest to c.paper),
                "surfaceContainerLow" to (s.surfaceContainerLow to c.paper), "surfaceContainer" to (s.surfaceContainer to c.paper),
                "surfaceContainerHigh" to (s.surfaceContainerHigh to c.paper),
                "surfaceContainerHighest" to (s.surfaceContainerHighest to c.paper),
                "outlineVariant" to (s.outlineVariant to c.milk), "error" to (s.error to c.error), "onError" to (s.onError to c.onError),
            )
            for ((role, v) in pairs) assertEquals("$label $role", v.second, v.first)
        }
    }

    @Test fun theOwnColourPrimariesCarryLabelsOnPaperAndWhiteOnThem() {
        val cases = listOf(ThemeId.WEB_SAKURA, ThemeId.WEB_NEON, ThemeId.WEB_WAVE, ThemeId.WEB_ACCESS).map { it to ColorMode.ORIGINAL } +
            (ThemeId.WEB_ACCESS to ColorMode.SIX)
        for ((id, mode) in cases) {
            val c = Palettes.of(id, mode)
            val s = skinOf(id, mode).scheme
            val onPaper = contrast(s.primary, c.paper)
            assertTrue("$id $mode primary on paper = $onPaper", onPaper >= 4.5f)
            val onFill = contrast(s.onPrimary, s.primary)
            assertTrue("$id $mode onPrimary on primary = $onFill", onFill >= 4.5f)
        }
    }

    /**
     * core C5's second bullet (ruling R-C5): inside FogTheme(effects, Themes.of(id, mode)) the M3 primary, onPrimary, outline and
     * shapes are core §3.1's table, for every one of the 14 themes; the whole scheme is the theme's own skin.
     */
    @Test fun fogThemeHandsEveryThemesSkinToItsControls() {
        val themes = Themes.all()
        assertEquals(14, themes.size)
        val seen = mutableMapOf<Theme, Pair<ColorScheme, Shapes>>()
        rule.setContent {
            for (theme in themes) {
                FogTheme(effectsFor(EffectLevel.SAVER, false), theme) {
                    seen[theme] = MaterialTheme.colorScheme to MaterialTheme.shapes
                }
            }
        }
        rule.waitForIdle()
        assertEquals(themes.size, seen.size)
        for (theme in themes) {
            val label = "${theme.id} ${theme.mode}"
            val (scheme, shapes) = seen.getValue(theme)
            val want = expected(theme.id, theme.mode)
            assertEquals("$label primary", want.primary, scheme.primary)
            assertEquals("$label onPrimary", want.onPrimary, scheme.onPrimary)
            assertEquals("$label outline", want.outline, scheme.outline)
            assertEquals("$label shapes", want.shapes, shapes)
            assertSameScheme(label, theme.material.scheme, scheme)
        }
    }

    /** FogComponentsTest:155 still holds: FogTheme(effects) is CLASSIC, with Orbitron on display only; a theme brings its face. */
    @Test fun fogThemeWithoutAThemeKeepsOrbitronOnDisplayAndTheSystemFontElsewhere() {
        var display: FontFamily? = null
        var headline: FontFamily? = null
        var body: FontFamily? = null
        var theme: Theme? = null
        val other = ThemeType.Classic.copy(display = FogFonts.Quicksand)
        val base = Themes.of(ThemeId.SIGIL, ColorMode.SIX)
        val custom = Theme(base.id, base.mode, base.colors, other, base.frames, controls = base.controls, backdrop = base.backdrop, bars = base.bars, material = base.material)
        var customDisplay: FontFamily? = null
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.FULL, false)) {
                display = MaterialTheme.typography.displaySmall.fontFamily
                headline = MaterialTheme.typography.headlineMedium.fontFamily
                body = MaterialTheme.typography.bodyLarge.fontFamily
                theme = LocalTheme.current
            }
            FogTheme(effectsFor(EffectLevel.FULL, false), custom) { customDisplay = MaterialTheme.typography.displayLarge.fontFamily }
        }
        rule.waitForIdle()
        assertEquals(FogFonts.Orbitron, display)
        assertEquals(FogFonts.Orbitron, headline)
        assertTrue("body keeps the system font: $body", body != FogFonts.Orbitron && body != FogFonts.ChakraPetch)
        assertSame(Themes.Classic, theme)
        assertEquals(FogFonts.Quicksand, customDisplay)
    }

    /**
     * FogTheme provides the theme, its ink, and (eng E31) the content colour: a theme's own ink outside a Surface, while CLASSIC
     * leaves the content colour exactly as it was.
     */
    @Test fun fogThemeProvidesTheThemeItsInkAndTheContentColour() {
        val outside = Color(0xFF123456)
        val got = mutableMapOf<Theme, Triple<Theme, Color, Color>>()
        val themes = Themes.all()
        rule.setContent {
            CompositionLocalProvider(LocalContentColor provides outside) {
                for (t in themes) FogTheme(effectsFor(EffectLevel.SAVER, false), t) {
                    got[t] = Triple(LocalTheme.current, LocalInk.current, LocalContentColor.current)
                }
            }
        }
        rule.waitForIdle()
        for (t in themes) {
            val (theme, ink, content) = got.getValue(t)
            val label = "${t.id} ${t.mode}"
            assertSame(label, t, theme)
            assertEquals("$label ink", t.colors.ink, ink)
            assertEquals("$label content colour", if (t.classic) outside else t.colors.ink, content)
        }
        assertEquals(Sakura.Text, got.getValue(Themes.Classic).second)
    }

    // ---- helpers ----

    private fun skinOf(id: ThemeId, mode: ColorMode): MaterialSkin = materialOf(id, mode, Palettes.of(id, mode))

    private class Want(val primary: Color, val onPrimary: Color, val outline: Color, val shapes: Shapes)

    /** core §3.1's materialOf table. */
    private fun expected(id: ThemeId, mode: ColorMode): Want {
        val pink = Sakura.Primary
        val ink = Sakura.Text
        val own = id.ownColours && mode == ColorMode.ORIGINAL
        return when (id) {
            ThemeId.CLASSIC -> Want(pink, ink, pink, radii(12, 16, 22, 30, 30))
            ThemeId.SIGIL, ThemeId.TUNNEL -> Want(pink, ink, pink, radii(12, 16, 20, 20, 20))
            ThemeId.STARDUST, ThemeId.ONE_STROKE, ThemeId.PETAL_STORM -> Want(pink, ink, pink, radii(12, 16, 22, 30, 30))
            ThemeId.WEB_SAKURA -> radii(12, 18, 24, 24, 24).let {
                if (own) Want(Color(0xFF6E6084), white, Color(0xFF6E6084), it) else Want(pink, ink, pink, it)
            }
            ThemeId.WEB_NEON -> radii(9, 9, 14, 14, 14).let {
                if (own) Want(Color(0xFFC2185B), white, Color(0xFFFF3D84), it) else Want(pink, ink, pink, it)
            }
            ThemeId.WEB_WAVE -> radii(8, 14, 18, 18, 18).let {
                if (own) Want(Color(0xFF253EDC), white, Color(0xFF5B6672), it) else Want(pink, ink, pink, it)
            }
            ThemeId.WEB_ACCESS -> radii(0, 0, 0, 0, 0).let {
                if (own) Want(Color(0xFF0A0A0A), white, Color(0xFF0A0A0A), it) else Want(ink, white, ink, it)
            }
        }
    }

    private fun radii(xs: Int, s: Int, m: Int, l: Int, xl: Int) = Shapes(
        extraSmall = RoundedCornerShape(xs.dp), small = RoundedCornerShape(s.dp), medium = RoundedCornerShape(m.dp),
        large = RoundedCornerShape(l.dp), extraLarge = RoundedCornerShape(xl.dp),
    )

    /** ColorScheme has no equals: compare every Color getter (they return the packed Long under a mangled name). */
    private fun assertSameScheme(label: String, want: ColorScheme, got: ColorScheme) {
        val getters = ColorScheme::class.java.methods.filter {
            it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == java.lang.Long.TYPE
        }
        assertTrue("ColorScheme has ${getters.size} colour getters", getters.size >= 36)
        for (g in getters) {
            val w = Color((g.invoke(want) as Long).toULong())
            val v = Color((g.invoke(got) as Long).toULong())
            assertEquals("$label ${g.name.substringBefore('-')}", w, v)
        }
    }

    private fun contrast(a: Color, b: Color): Float {
        fun lin(v: Float): Double = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        fun lum(c: Color): Double = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
        val x = lum(a)
        val y = lum(b)
        return ((max(x, y) + .05) / (min(x, y) + .05)).toFloat()
    }
}
