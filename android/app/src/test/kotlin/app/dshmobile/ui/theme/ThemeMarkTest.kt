package app.dshmobile.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogDebug
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.LocalPageActive
import app.dshmobile.ui.fog.effectsFor
import app.dshmobile.ui.theme.themes.Hairline
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** ThemeMark and the ThemeClock (D10, §2.4.4), and the other drawn slots: dividers, progress bars and the themed controls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeMarkTest {
    @get:Rule val rule = createComposeRule()
    private var view: View? = null

    @Before fun on() {
        FogDebug.enabled = true
        FogDebug.reset()
    }

    @After fun off() {
        FogDebug.enabled = false
    }

    /**
     * A [side]-dp mark that paints its box red with a white bar at x = (t / 1.7 s mod 1) · width: its pixels show the time it was
     * drawn at (1.7 s, so 37 s is not a whole number of turns).
     */
    private fun timed(moves: Boolean, stillAt: Float = .25f, draws: IntArray? = null, side: Float = 30f) = Mark(
        DpSize(side.dp, side.dp),
        ThemeDrawing {
            { t ->
                draws?.let { it[0]++ }
                drawRect(Color.Red)
                val x = (((t / 1.7f) % 1f) + 1f) % 1f * size.width
                drawRect(Color.White, Offset(x, 0f), Size(3f, size.height))
            }
        },
        moves = moves,
        stillAt = stillAt,
    )

    /** A [side]-dp mark of one [color]. */
    private fun solid(color: Color, side: Float) = Mark(DpSize(side.dp, side.dp), ThemeDrawing { { drawRect(color) } })

    @Test fun theLayoutIsTheModifiersAndTheArtIsCentredUnclipped() {
        val big = timed(moves = false)
        val small = timed(moves = false, side = 4f)
        show {
            Box(Modifier.size(100.dp).background(Color.Black)) {
                ThemeMark(big, live = false, Modifier.align(Alignment.Center).size(8.dp).testTag("big"))
                ThemeMark(small, live = false, Modifier.align(Alignment.TopStart).size(20.dp).testTag("small"))
                ThemeMark(big, live = false, Modifier.align(Alignment.BottomEnd).testTag("none"))
            }
        }
        assertEquals(IntSize(8, 8), rule.onNodeWithTag("big").fetchSemanticsNode().size)
        assertEquals(IntSize(20, 20), rule.onNodeWithTag("small").fetchSemanticsNode().size)
        assertEquals("no size given, no size taken", IntSize(0, 0), rule.onNodeWithTag("none").fetchSemanticsNode().size)
        val s = shot()
        // The 30 dp art round the 8 dp slot at (50, 50): 35..65, drawn past the slot.
        assertColour("left of the slot", Color.Red, s.at(38, 50))
        assertColour("right of the slot", Color.Red, s.at(62, 50))
        assertColour("outside the art", Color.Black, s.at(32, 50))
        // The still bar: stillAt .25 s, .25 / 1.7 of 30 dp, from x 35 + 4.4.
        assertColour("the still's bar", Color.White, s.at(41, 50))
    }

    @Test fun motionOffShowsTheStillWhateverTheClockSays() {
        val clock = ThemeClock()
        val mark = timed(moves = true, stillAt = .25f)
        var effects by mutableStateOf(effectsFor(EffectLevel.SAVER, false))
        rule.setContent {
            view = LocalView.current
            FogTheme(effects) {
                CompositionLocalProvider(LocalThemeClock provides clock, LocalDensity provides Density(1f)) {
                    Box(Modifier.size(60.dp).background(Color.Black)) { ThemeMark(mark, live = true, Modifier.size(30.dp)) }
                }
            }
        }
        for ((name, e) in listOf("SAVER" to effectsFor(EffectLevel.SAVER, false), "reduced motion" to effectsFor(EffectLevel.FULL, true))) {
            effects = e
            clock.source.floatValue = 0f
            val a = shot()
            clock.source.floatValue = 37f
            val b = shot()
            assertTrue("$name: t = 0 and t = 37 s draw the same still", a.sameAs(b))
            assertEquals("$name holds no clock", 0, clock.holders)
        }
        // At FULL the same mark moves with the clock (the control).
        effects = effectsFor(EffectLevel.FULL, false)
        clock.source.floatValue = .1f
        val a = shot()
        clock.source.floatValue = 37.6f
        val b = shot()
        assertFalse("at FULL the mark follows the clock", a.sameAs(b))
        assertEquals(1, clock.holders)
    }

    @Test fun onlyAMovingLiveMarkOnTheShownPageHoldsTheClock() {
        val clock = ThemeClock()
        val moving = timed(moves = true)
        val still = timed(moves = false)
        var active by mutableStateOf(false)
        var live by mutableStateOf(true)
        var mark by mutableStateOf(moving)
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.FULL, false)) {
                CompositionLocalProvider(LocalThemeClock provides clock, LocalPageActive provides active) {
                    ThemeMark(mark, live = live, Modifier.size(10.dp))
                }
            }
        }
        rule.waitForIdle()
        assertEquals("an inactive page holds no clock", 0, clock.holders)
        active = true
        rule.waitForIdle()
        assertEquals(1, clock.holders)
        live = false
        rule.waitForIdle()
        assertEquals("not live", 0, clock.holders)
        live = true
        mark = still
        rule.waitForIdle()
        assertEquals("a mark that does not move", 0, clock.holders)
    }

    /**
     * E5 / eng E19(c): a drawing is redrawn by a tick only if its draw reads the clock. Robolectric draws in software, re-running
     * every draw lambda on each draw, so the test records which snapshot states the whole window's draw reads: with the clock held
     * and ticking, a still mark never reads it; a moving mark (the control) does.
     */
    @Test fun aStillMarkIsNotRedrawnWhileAnotherMarkHoldsTheClock() {
        val clock = ThemeClock()
        val idle = mutableStateOf(false)
        val moving = timed(moves = true)
        val still = timed(moves = false)
        var withMoving by mutableStateOf(false)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            view = LocalView.current
            FogTheme(effectsFor(EffectLevel.FULL, false)) {
                CompositionLocalProvider(LocalThemeClock provides clock) {
                    ThemeClockDriver(clock, null, idle)
                    // Something else holds the clock (a running status dot elsewhere), so it ticks.
                    clock.Hold(true)
                    Row {
                        ThemeMark(still, live = true, Modifier.size(30.dp))
                        if (withMoving) ThemeMark(moving, live = true, Modifier.size(30.dp))
                    }
                }
            }
        }
        frames(3)
        val ticks0 = FogDebug.get("clock.tick")
        val t0 = clock.time.value
        repeat(20) {
            rule.mainClock.advanceTimeBy(16)
            assertFalse("the still mark read the clock while drawing (frame $it)", readsDuringDraw().any { r -> r === clock.source })
        }
        assertTrue("the clock ticks while held: ${FogDebug.get("clock.tick") - ticks0}", FogDebug.get("clock.tick") - ticks0 >= 15)
        assertTrue("time moved on", clock.time.value > t0)
        // The control: a moving mark reads the clock in its draw, so each tick redraws it.
        withMoving = true
        frames(2)
        assertTrue("the moving mark reads the clock", readsDuringDraw().any { r -> r === clock.source })
        assertEquals(2, clock.holders)
    }

    /** Every snapshot state object read while the whole window is drawn once. */
    private fun readsDuringDraw(): Set<Any> {
        val reads = HashSet<Any>()
        rule.runOnUiThread {
            Snapshot.observe(readObserver = { reads += it }) { view!!.rootView.draw(Canvas(Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888))) }
        }
        return reads
    }

    @Test fun nothingHoldingTheClockMeansNoTicks() {
        val clock = ThemeClock()
        val still = timed(moves = false)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            view = LocalView.current
            FogTheme(effectsFor(EffectLevel.FULL, false)) {
                CompositionLocalProvider(LocalThemeClock provides clock) {
                    ThemeClockDriver(clock, null, mutableStateOf(false))
                    ThemeMark(still, live = true, Modifier.size(30.dp))
                }
            }
        }
        frames(30)
        assertEquals(0, FogDebug.get("clock.tick"))
        assertEquals(0f, clock.time.value, 0f)
    }

    @Test fun aThemedDividerKeepsOneDpAndClassicRunsTodaysLine() {
        val theme = themeWith(Frame(Corners.Square, emptyList(), ink = Color.Black, ink2 = Color.Black))
        var t by mutableStateOf(Themes.Classic)
        show {
            CompositionLocalProvider(LocalTheme provides t) {
                Column(Modifier.width(100.dp).background(Color.Black)) {
                    Box(Modifier.height(10.dp))
                    ThemeDivider(Hairline(3.dp, Color.Red), Modifier.testTag("divider")) { Box(Modifier.fillMaxWidth().height(4.dp).testTag("classic")) }
                    Box(Modifier.height(10.dp))
                }
            }
        }
        assertEquals(4, rule.onNodeWithTag("classic").fetchSemanticsNode().size.height)
        t = theme
        rule.waitForIdle()
        assertEquals(IntSize(100, 1), rule.onNodeWithTag("divider").fetchSemanticsNode().size)
        val s = shot()
        // The 3 dp line through the 1 dp row (y 10) reaches past it.
        assertColour("the line", Color.Red, s.at(50, 10))
        assertColour("past the row", Color.Red, s.at(50, 11))
    }

    @Test fun aThemedProgressBarDrawsItsSkinInTheCallSitesBox() {
        val head = solid(Color.Magenta, 6f)
        val skin = ProgressSkin(height = 6.dp, track = Fill.Solid(Color.Blue), fill = Fill.Solid(Color.Red), warn = Fill.Solid(Color.Yellow), head = head)
        val theme = Theme(
            ThemeId.SIGIL, ColorMode.SIX, Palettes.Drawn, ThemeType.Classic,
            frames = themeWith(Frame(Corners.Square, emptyList(), ink = Color.Black, ink2 = Color.Black)).frames,
            marks = ThemeMarks(progress = skin),
            material = materialOf(ThemeId.SIGIL, ColorMode.SIX, Palettes.Drawn),
        )
        var t by mutableStateOf(Themes.Classic)
        var warn by mutableStateOf(false)
        show {
            CompositionLocalProvider(LocalTheme provides t) {
                Box(Modifier.size(100.dp, 30.dp).background(Color.Black)) {
                    ThemeProgress(.25f, warn, Modifier.align(Alignment.Center).fillMaxWidth().height(3.dp).testTag("bar")) {
                        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(3.dp).testTag("classic"))
                    }
                }
            }
        }
        rule.onNodeWithTag("classic").fetchSemanticsNode()
        t = theme
        rule.waitForIdle()
        assertEquals("the call site's box", IntSize(100, 3), rule.onNodeWithTag("bar").fetchSemanticsNode().size)
        var s = shot()
        // The 6 dp bar is drawn centred on the 3 dp box (y 13.5..16.5 → 12..18), past it.
        assertColour("filled to a quarter", Color.Red, s.at(15, 13))
        assertColour("track beyond", Color.Blue, s.at(60, 16))
        // The head sits at the fill's end (x 25).
        assertColour("head", Color.Magenta, s.at(25, 15))
        warn = true
        s = shot()
        assertColour("warn fill", Color.Yellow, s.at(10, 15))
    }

    @Test fun themedControlsKeepTheirTagsStateAndSize() {
        val thumb = timed(moves = false, side = 12f)
        val track = ProgressSkin(height = 4.dp, track = Fill.Solid(Color.Blue), fill = Fill.Solid(Color.Red))
        val plain = themeWith(Frame(Corners.Square, emptyList(), ink = Color.Black, ink2 = Color.Black))
        val skinned = Theme(
            ThemeId.SIGIL, ColorMode.SIX, Palettes.Drawn, ThemeType.Classic,
            frames = plain.frames,
            controls = ThemeControls(
                caret = Sakura.Primary, iconInk = Sakura.Text, tabInkOn = Sakura.Text, tabInkOff = Sakura.TextSecondary,
                switch = SwitchSkin({ SwitchDefaults.colors(checkedTrackColor = Color.Red) }, thumb), slider = SliderSkin(thumb, track),
            ),
            material = materialOf(ThemeId.SIGIL, ColorMode.SIX, Palettes.Drawn),
        )
        val themes = listOf(Themes.Classic, plain, skinned)
        val on = List(themes.size) { mutableStateOf(false) }
        val values = List(themes.size) { mutableFloatStateOf(.3f) }
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.SAVER, true)) {
                Column {
                    themes.forEachIndexed { i, t ->
                        CompositionLocalProvider(LocalTheme provides t) {
                            ThemedSwitch(on[i].value, { on[i].value = it }, Modifier.testTag("switch-$i"))
                            ThemedSlider(values[i].floatValue, { values[i].floatValue = it }, Modifier.fillMaxWidth().testTag("slider-$i"))
                        }
                    }
                }
            }
        }
        val sizes = themes.indices.map { i ->
            rule.onNodeWithTag("switch-$i").assertIsOff().performClick().assertIsOn()
            val slider = rule.onNodeWithTag("slider-$i").fetchSemanticsNode()
            assertEquals("theme $i", .3f, slider.config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
            rule.onNodeWithTag("switch-$i").fetchSemanticsNode().size to slider.size
        }
        assertEquals("a theme without a skin lays them out as CLASSIC", sizes[0], sizes[1])
        assertEquals("a skin keeps their size", sizes[0], sizes[2])
    }

    // ───── helpers ─────

    private fun show(content: @Composable () -> Unit) {
        rule.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalDensity provides Density(1f)) { content() }
        }
    }

    private fun shot(): Bitmap {
        rule.waitForIdle()
        val v = view!!
        return Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { b -> rule.runOnUiThread { v.draw(Canvas(b)) } }
    }

    private fun frames(n: Int) = repeat(n) {
        rule.mainClock.advanceTimeBy(16)
        draw()
    }

    private fun draw() = rule.runOnUiThread { view!!.rootView.draw(Canvas(Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888))) }

    private fun Bitmap.at(x: Int, y: Int) = Color(getPixel(x, y))

    private fun assertColour(message: String, expected: Color, actual: Color) {
        val gap = maxOf(abs(expected.red - actual.red), abs(expected.green - actual.green), abs(expected.blue - actual.blue))
        assertTrue("$message: expected $expected, was $actual", gap <= 3f / 255f)
    }
}
