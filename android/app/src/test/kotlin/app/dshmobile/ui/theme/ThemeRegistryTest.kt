package app.dshmobile.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.dshmobile.ui.fog.BackdropStyle
import app.dshmobile.ui.fog.FogFonts
import app.dshmobile.ui.fog.GlassRecipe
import app.dshmobile.ui.fog.Intro
import app.dshmobile.ui.fog.IntroStyle
import app.dshmobile.ui.fog.Look
import app.dshmobile.ui.fog.PictureAlign
import app.dshmobile.ui.theme.themes.contrast
import app.dshmobile.ui.theme.themes.isPaletteLerp
import app.dshmobile.ui.theme.themes.over
import app.dshmobile.ui.theme.themes.provisionalTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The theme registry (core C4 identity rules, D31, E10) and what every theme carries: CLASSIC empty, the others whole. Checks on
 * [Themes.all] hold for every real theme too; the placeholder's own recipe (flat, still, solid fills, Line rims, palette text
 * roles) is checked on [provisionals], so nothing here changes when a P-task replaces its stub (the real themes' contrast and
 * purity are F8's ThemeContractTest).
 */
class ThemeRegistryTest {
    private val drawn = listOf(ThemeId.SIGIL, ThemeId.STARDUST, ThemeId.ONE_STROKE, ThemeId.PETAL_STORM, ThemeId.TUNNEL)
    private val web = listOf(ThemeId.WEB_SAKURA, ThemeId.WEB_NEON, ThemeId.WEB_WAVE, ThemeId.WEB_ACCESS)
    private fun others() = Themes.all().filterNot { it.classic }

    @Test fun allIsTheFourteenDistinctThemes() {
        val all = Themes.all()
        assertEquals(14, all.size)
        for (i in all.indices) for (j in i + 1 until all.size) assertNotSame("${all[i].id} ${all[i].mode}", all[i], all[j])
        val expected = listOf(ThemeId.CLASSIC to ColorMode.SIX) + drawn.map { it to ColorMode.SIX } +
            web.flatMap { id -> ColorMode.entries.map { id to it } }
        assertEquals(expected.toSet(), all.map { it.id to it.mode }.toSet())
        assertSame(Themes.Classic, all.single { it.classic })
    }

    @Test fun ofKeepsOneInstancePerRealPalette() {
        for (id in ThemeId.entries) for (mode in ColorMode.entries) {
            val t = Themes.of(id, mode)
            assertSame("$id $mode is cached", t, Themes.of(id, mode))
            assertEquals(id, t.id)
            assertEquals(id.ownColours, t.ownColours)
            assertEquals(if (id.ownColours) mode else ColorMode.SIX, t.mode)
            assertSame(Palettes.of(id, mode), t.colors)
        }
        for (mode in ColorMode.entries) assertSame(Themes.Classic, Themes.of(ThemeId.CLASSIC, mode))
        for (id in drawn) assertSame("$id has one instance", Themes.of(id, ColorMode.ORIGINAL), Themes.of(id, ColorMode.SIX))
        for (id in web) assertNotSame("$id has two", Themes.of(id, ColorMode.ORIGINAL), Themes.of(id, ColorMode.SIX))
    }

    @Test fun theLookWearsItsResolvedTheme() {
        assertSame(Themes.of(ThemeId.SIGIL, ColorMode.SIX), Look().appTheme)
        assertSame(Themes.of(ThemeId.TUNNEL, ColorMode.SIX), Look(intro = Intro(style = IntroStyle.TUNNEL)).appTheme)
        assertSame(Themes.Classic, Look(theme = ThemeId.CLASSIC).appTheme)
        assertSame(Themes.of(ThemeId.WEB_NEON, ColorMode.ORIGINAL), Look(theme = ThemeId.WEB_NEON).appTheme)
        assertSame(Palettes.NeonSix, Look(theme = ThemeId.WEB_NEON, themeColors = ColorMode.SIX).appTheme.colors)
        // A drawn theme ignores the stored 顏色.
        assertSame(Look(theme = ThemeId.STARDUST).appTheme, Look(theme = ThemeId.STARDUST, themeColors = ColorMode.SIX).appTheme)
    }

    @Test fun classicIsTodaysAppAndCarriesNothing() {
        val c = Themes.Classic
        assertTrue(c.classic)
        assertEquals(ThemeId.CLASSIC, c.id)
        assertEquals(ColorMode.SIX, c.mode)
        assertSame(ThemeColors.Classic, c.colors)
        assertEquals(ThemeType.Classic, c.type)
        assertEquals(FogFonts.Orbitron, c.type.display)
        assertEquals(FogFonts.ChakraPetch, c.type.latin)
        assertNull(c.frames)
        assertNull(c.controls)
        assertNull(c.backdrop)
        assertNull(c.bars)
        assertNull(c.heading)
        assertNull(c.screen)
        assertEquals(ThemeEdges(), c.edges)
        assertEquals(ThemeMarks(), c.marks)
        assertEquals(Sweep.Glow(Sakura.Primary.copy(alpha = .45f)), c.sweep)
        assertSame(SakuraColors, c.material.scheme)
        assertSame(SakuraShapes, c.material.shapes)
        // CLASSIC keeps today's glass and blur.
        assertTrue(c.blurs)
    }

    @Test fun everyOtherThemeIsWhole() {
        for (t in others()) {
            val name = "${t.id} ${t.mode}"
            assertNotNull(name, t.frames)
            val frames = t.frames!!
            assertNotNull(name, t.controls)
            assertNotNull(name, t.backdrop)
            assertNotNull(name, t.bars)
            // D31: every Surf resolves.
            assertEquals(Surf.entries.size, frames.all().size)
            for (role in Surf.entries) assertSame("$name $role", frames.of(role), frames.all()[role.ordinal])
        }
    }

    @Test fun thePlaceholderIsStillFlatAndMarkless() {
        for (t in provisionals()) {
            val name = "${t.id} ${t.mode}"
            // Still, no glass, no marks or edges; the bars and the M3 skin are the palette's own.
            assertFalse(name, t.backdrop!!.moves)
            assertFalse(name, t.blurs)
            assertEquals(ThemeMarks(), t.marks)
            assertEquals(ThemeEdges(), t.edges)
            assertEquals(name, SystemBars.of(t.colors), t.bars)
            assertEquals(name, materialOf(t.id, t.mode, t.colors).scheme.primary, t.material.scheme.primary)
        }
    }

    @Test fun themeTextComesFromTextRolesNeverFromAccentOrCool() {
        for (t in provisionals()) {
            val c = t.colors
            val text = setOf(c.ink, c.ink2, c.skyInk, c.quietInk, c.accentText, c.onAccent, c.onInk, c.onInk2)
            val name = "${t.id} ${t.mode}"
            for (style in listOf(t.type.numbers, t.type.label, t.type.heading)) assertTrue(name, style.color in text)
            val inks = t.frames!!.all().flatMap { listOf(it.ink, it.ink2) } + listOf(t.controls!!.tabInkOn, t.controls!!.tabInkOff)
            for (ink in inks) {
                assertTrue("$name: ink $ink is a text role", ink in text)
                assertTrue("$name: ink $ink is the accent or cool", ink != c.accent && ink != c.cool)
            }
        }
    }

    /** The roles whose light fills FrameNode lifts to α .74 over a dark picture (§2.4.1, D9). */
    private val floored = setOf(
        Surf.PANEL, Surf.HEADER, Surf.SHEET, Surf.POPUP, Surf.PROMPT, Surf.REPLY, Surf.LIVE, Surf.CARD, Surf.CARD_COOL,
        Surf.NOTICE, Surf.FIELD, Surf.CODE, Surf.TILE, Surf.DOCK, Surf.CHIP, Surf.CHIP_ACCENT, Surf.CHIP_COOL, Surf.MODEL_CHIP,
        Surf.BAR_BUTTON, Surf.BAR_BUTTON_ACCENT, Surf.SEG_TRACK, Surf.SEG_ON, Surf.SEG_TRACK_OFF, Surf.SEG_ON_OFF,
    )

    @Test fun everyFrameReadsOverItsFillAndTheFlooredOnesOverADarkPicture() {
        for (t in provisionals()) {
            val c = t.colors
            for (role in Surf.entries) {
                val f = t.frames!!.of(role)
                val name = "${t.id} ${t.mode} $role"
                // On the stage, as F8's contract check 2 measures.
                val flat = flatten(f, c.stage)
                assertTrue("$name ink ${contrast(f.ink, flat)}", contrast(f.ink, flat) >= 4.5f)
                assertTrue("$name ink2 ${contrast(f.ink2, flat)}", contrast(f.ink2, flat) >= 4.5f)
                if (role !in floored) continue
                // Over a dark picture (LocalPictureBehind): light fills floored to .74, over black .8 on the ground.
                val dark = flatten(f, Color.Black.copy(alpha = .8f).over(c.ground), floor = true)
                assertTrue("$name ink over a dark picture ${contrast(f.ink, dark)}", contrast(f.ink, dark) >= 4.5f)
            }
        }
    }

    @Test fun sixColourThemesUseOnlyPaletteLerps() {
        for (t in provisionals().filter { !it.ownColours || it.mode == ColorMode.SIX }) {
            val name = "${t.id} ${t.mode}"
            val colours = t.frames!!.all().flatMap { f ->
                f.fills.flatMap { colours(it) } + f.rims.flatMap { r -> (r as Rim.Line).let { colours(it.brush) } } +
                    listOf(f.ink, f.ink2)
            } + with(t.controls!!) { listOf(caret, iconInk, tabInkOn, tabInkOff) } +
                listOf(t.type.numbers.color, t.type.label.color, t.type.heading.color, t.colors.ground, t.colors.stage, t.bars!!.window)
            for (colour in colours) assertTrue("$name: $colour", isPaletteLerp(colour))
        }
    }

    @Test fun blursOnlyWithAGlassFrame() {
        val plain = provisionalTheme(ThemeId.SIGIL, ColorMode.SIX)
        val frames = plain.frames!!
        val glassy = Theme(
            plain.id, plain.mode, plain.colors, plain.type,
            frames = frames.copy(dock = frames.dock.copy(glass = GlassRecipe.Classic)), material = plain.material,
        )
        assertTrue(glassy.blurs)
        assertFalse(plain.blurs)
    }

    @Test fun barsFollowTheWindow() {
        assertEquals(SystemBars(Sakura.Background, darkStatus = false, darkNav = false), SystemBars.of(Palettes.Drawn))
        assertEquals(SystemBars(Color(0xFF0B0B0E), darkStatus = true, darkNav = true), SystemBars.of(Palettes.NeonOwn))
        assertEquals(SystemBars(Sakura.Text, darkStatus = true, darkNav = true), SystemBars.of(Palettes.NeonSix))
    }

    @Test fun framesDefaultToTheirNearestKin() {
        val f = provisionalTheme(ThemeId.SIGIL, ColorMode.SIX).frames!!
        assertEquals(Frame.clear(f.panel.ink, f.panel.ink2), f.bar)
        assertSame(f.panel, f.header)
        assertSame(f.chip, f.float)
        assertSame(f.chipAccent, f.badge)
        assertSame(f.reply, f.live)
        assertEquals(f.user.faded(.5f), f.userPending)
        assertEquals(f.user.forSegment(), f.segmentUser)
        // A light-ink (dark) user box waits with dashed rims at full strength instead of fading.
        val dark = Frame(
            Corners.Pill, listOf(Fill.Solid(Sakura.Text)), listOf(Rim.Line(1.dp, Fill.Solid(Sakura.Primary))),
            ink = Color.White, ink2 = Color.White,
        )
        val withDark = ThemeFrames(f.panel, f.reply, dark, f.chip, f.chipAccent, f.field, f.dock, f.segTrack, f.segOn, f.segTrackOff, f.segOnOff)
        assertEquals(dark.darkPending(), withDark.userPending)
    }

    @Test fun eachThemeIsBuiltQuickly() {
        // Warm class loading first (Compose, fonts, palettes), so the first id does not pay for it.
        Themes.all()
        for (t in Themes.all()) {
            Themes.resetForTest()
            val start = System.nanoTime()
            val built = Themes.of(t.id, t.mode)
            val ms = (System.nanoTime() - start) / 1e6
            println("Themes.of(${t.id}, ${t.mode}) built in ${"%.2f".format(ms)} ms")
            assertTrue("${t.id} ${t.mode} took $ms ms", ms < 100.0)
            if (!t.classic) assertNotSame("a fresh build after the reset", t, built)
        }
        Themes.resetForTest()
    }

    // ───── helpers ─────

    /** The frame's solid fills over [under] (all placeholder fills are solid); [floor] lifts light fills to α .74 (D9). */
    private fun flatten(f: Frame, under: Color, floor: Boolean = false): Color = f.fills.fold(under) { u, fill ->
        val c = (fill as Fill.Solid).color
        val lifted = if (floor && c.luminance() > .6f && c.alpha < .74f) c.copy(alpha = .74f) else c
        lifted.over(u)
    }

    private fun colours(f: Fill): List<Color> = when (f) {
        is Fill.Solid -> listOf(f.color)
        is Fill.Linear -> f.stops.map { it.second }
        is Fill.Radial -> f.stops.map { it.second }
        is Fill.RadialDp -> f.stops.map { it.second }
        is Fill.Lines -> listOf(f.color)
        is Fill.Halftone -> listOf(f.color)
    }
}

/** The placeholder itself for every non-CLASSIC (id, mode), built fresh: what the stubs return until their P-tasks land. */
private fun provisionals(): List<Theme> = ThemeId.entries.filter { it != ThemeId.CLASSIC }.flatMap { id ->
    if (id.ownColours) ColorMode.entries.map { provisionalTheme(id, it) } else listOf(provisionalTheme(id, ColorMode.SIX))
}

/** What needs a real composition or a real Path: the CompositionLocals' defaults, the corner shapes and the placeholder's sky. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeLocalsAndShapesTest {
    @get:Rule val rule = createComposeRule()

    @Test fun localsDefaultToClassicAndFollowTheProvidedTheme() {
        val neon = Themes.of(ThemeId.WEB_NEON, ColorMode.ORIGINAL)
        var outside: ThemeColors? = null
        var inside: ThemeColors? = null
        var theme: Theme? = null
        var ink: Color? = null
        var behind: Boolean? = null
        rule.setContent {
            outside = ThemeColors.current
            theme = LocalTheme.current
            ink = LocalInk.current
            behind = LocalPictureBehind.current
            CompositionLocalProvider(LocalTheme provides neon) { inside = ThemeColors.current }
        }
        rule.waitForIdle()
        assertSame(Themes.Classic, theme)
        assertSame(ThemeColors.Classic, outside)
        assertSame(Palettes.NeonOwn, inside)
        assertEquals(Sakura.Text, ink)
        assertEquals(false, behind)
    }

    @Test fun cornersMakeTheirShapes() {
        assertEquals(RoundedCornerShape(22.dp, 22.dp, 8.dp, 22.dp), frame(Corners.Round(22f, 22f, 8f, 22f)).shape)
        assertEquals(RoundedCornerShape(22.dp), frame(Corners.Round(22f)).shape)
        assertEquals(RoundedCornerShape(30), frame(Corners.Percent(30)).shape)
        assertEquals(RoundedCornerShape(50), frame(Corners.Pill).shape)
        assertEquals(RectangleShape, frame(Corners.Square).shape)
        assertEquals(CutCornerShape(4.dp, 0.dp, 4.dp, 0.dp), frame(Corners.Cut(4f, 0f, 4f, 0f)).shape)
        assertEquals(RoundedCornerShape(22.dp, 6.dp, 22.dp, 22.dp), frame(Corners.Leaf(22f, Corner.TopEnd, 6f)).shape)
    }

    @Test fun aFlickReachesItsTipOutsideTheBoxOnItsCorner() {
        val box = Size(200f, 80f)
        val density = Density(2f)
        fun bounds(corner: Corner, dir: LayoutDirection = LayoutDirection.Ltr): Rect =
            frame(Corners.Flick(18f, corner)).shape.createOutline(box, dir, density).bounds
        // The tip is 6 dp out and 3 dp down from the corner: 12 and 6 px at density 2; the rest stays inside the box.
        assertRect(Rect(0f, 0f, 212f, 86f), bounds(Corner.BottomEnd))
        assertRect(Rect(-12f, 0f, 200f, 86f), bounds(Corner.BottomStart))
        assertRect(Rect(0f, -6f, 212f, 80f), bounds(Corner.TopEnd))
        assertRect(Rect(-12f, -6f, 200f, 80f), bounds(Corner.TopStart))
        // Start and end follow the layout direction.
        assertRect(Rect(-12f, 0f, 200f, 86f), bounds(Corner.BottomEnd, LayoutDirection.Rtl))
        // A frame keeps its corners when faded, and its shape is equal (built again from the same corners).
        val f = frame(Corners.Flick(18f, Corner.BottomEnd))
        assertEquals(f.shape, f.faded(.5f).shape)
    }

    /**
     * What the placeholder backdrop draws, with no picture: below the status band the sky's text (skyInk, and the clear BAR
     * frame's ink) reaches 4.5:1, and the band gives the bar icons 3:1 (§2.4.5). 霓虹's dark ground stays only in the status and
     * navigation bands (its stage fills the rest), and the insets are read in draw.
     */
    @Test fun thePlaceholderSkyReadsBelowTheStatusBand() {
        val themes = provisionals()
        assertEquals("WEB_NEON's two palettes frame a light stage in a dark ground", 2, themes.count { it.colors.ground != it.colors.stage })
        val insetTop = mutableFloatStateOf(24f)
        val navInset = mutableFloatStateOf(16f)
        val none = mutableFloatStateOf(0f)
        val shell = ShellGeometry(insetTop, none, none, none, none, navInset, none)
        var view: View? = null
        var scene: BackdropScene? = null
        rule.setContent {
            view = LocalView.current
            val measurer = rememberTextMeasurer()
            val s = remember(measurer) {
                BackdropScene(
                    hasPicture = false, style = BackdropStyle.COSY, face = null, avatar = null, opacity = 1f,
                    align = PictureAlign.CENTER, level = ThemeLevel.STILL, shell = shell, intro = Intro(), measurer = measurer,
                    pictureTone = null, faceTone = null,
                )
            }
            scene = s
            // One px per dp, so the insets (window px) and the probes below are the same numbers.
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Row {
                    for (t in themes) {
                        val drawing = remember(t, s) { t.backdrop!!.ground(s) }
                        Box(Modifier.size(PROBE_W.dp, PROBE_H.dp).drawWithCache { val d = drawing.build(this); onDrawBehind { d(0f) } })
                    }
                }
            }
        }
        fun pixels(): Bitmap {
            rule.waitForIdle()
            val v = view!!
            return Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { b -> rule.runOnUiThread { v.draw(Canvas(b)) } }
        }
        fun Bitmap.at(i: Int, y: Int) = Color(getPixel(i * PROBE_W + PROBE_W / 2, y))

        var shot = pixels()
        themes.forEachIndexed { i, t ->
            val c = t.colors
            val name = "${t.id} ${t.mode}"
            val band = shot.at(i, 10)
            val sky = shot.at(i, 100)
            val icons = if (t.bars!!.darkStatus) Color.White else Color(0xFF1F1F1F)
            assertTrue("$name: status icons ${contrast(icons, band)}", contrast(icons, band) >= 3f)
            assertTrue("$name: skyInk ${contrast(c.skyInk, sky)}", contrast(c.skyInk, sky) >= 4.5f)
            assertTrue("$name: the clear bar's ink ${contrast(t.frames!!.bar.ink, sky)}", contrast(t.frames!!.bar.ink, sky) >= 4.5f)
            assertColour("$name status band", c.ground, band)
            assertColour("$name sky", c.stage, sky)
            assertColour("$name sky under the band", c.stage, shot.at(i, 30))
            assertColour("$name navigation band", c.ground, shot.at(i, PROBE_H - 6))
            // E11: the same scene gets the same drawing.
            assertSame(name, t.backdrop!!.ground(scene!!), t.backdrop!!.ground(scene!!))
        }
        // A taller status bar redraws the band without a new drawing.
        insetTop.floatValue = 40f
        shot = pixels()
        themes.forEachIndexed { i, t -> assertColour("${t.id} ${t.mode} the band follows insetTop", t.colors.ground, shot.at(i, 30)) }
    }

    private fun assertColour(message: String, expected: Color, actual: Color) {
        val e = listOf(expected.red, expected.green, expected.blue)
        val a = listOf(actual.red, actual.green, actual.blue)
        assertTrue("$message: expected $expected, was $actual", e.zip(a).all { (x, y) -> abs(x - y) <= 1.5f / 255f })
    }

    private fun frame(corners: Corners) = Frame(corners, emptyList(), ink = Sakura.Text, ink2 = Sakura.TextSecondary)

    private fun assertRect(expected: Rect, actual: Rect) {
        val e = listOf(expected.left, expected.top, expected.right, expected.bottom)
        val a = listOf(actual.left, actual.top, actual.right, actual.bottom)
        assertTrue("expected $expected, was $actual", e.zip(a).all { (x, y) -> abs(x - y) < .5f })
    }

    private companion object {
        /** One probe column per placeholder (13 × 20 px fits the 320 px test window). */
        const val PROBE_W = 20
        const val PROBE_H = 200
    }
}
