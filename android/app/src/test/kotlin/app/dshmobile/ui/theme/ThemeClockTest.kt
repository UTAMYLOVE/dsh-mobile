package app.dshmobile.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.dshmobile.remote.MuxState
import app.dshmobile.ui.MainUiState
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.Effects
import app.dshmobile.ui.fog.FogBackground
import app.dshmobile.ui.fog.FogDebug
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.LocalIdle
import app.dshmobile.ui.fog.LocalPageActive
import app.dshmobile.ui.fog.effectsFor
import app.dshmobile.ui.fog.rememberBackdrop
import app.dshmobile.ui.shell.ComposerUi
import app.dshmobile.ui.shell.FogShell
import app.dshmobile.ui.shell.ShellNav
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

/**
 * The theme clock as the backdrop drives it (plan §2.4.4, R12, E5): the backdrop holds it only while it moves and motion is
 * allowed, so SAVER, reduced motion and an inactive page cost no ticks; idle slows it to a tick per 66 ms; a backdrop's frameMs
 * caps its ticks and idlePause stops them; and a still backdrop never reads the clock while something else makes it tick (eng
 * E19), so a running status dot never redraws the backdrop. FogShell wires the driver, the clock and the backdrop together.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeClockTest {
    @get:Rule val rule = createComposeRule()
    private var view: View? = null

    @Before fun on() {
        FogDebug.enabled = true
        FogDebug.reset()
    }

    @After fun off() {
        FogDebug.enabled = false
    }

    /** A backdrop whose motion layer paints a bar at a place that follows t (so its pixels show the time it was drawn at). */
    private class Moving(
        override val moves: Boolean,
        override val frameMs: Long = 0L,
        override val idlePause: Boolean = false,
        override val stillAt: Float = .5f,
    ) : ThemeBackdrop {
        private val plain = ThemeDrawing { { } }
        private val motion = ThemeDrawing { { t -> drawRect(Color.Blue, size = Size(4f + (t * 50f) % 100f, 4f)) } }
        override fun ground(scene: BackdropScene): ThemeDrawing = plain
        override fun motion(scene: BackdropScene): ThemeDrawing = motion
    }

    private fun themed(backdrop: ThemeBackdrop): Theme {
        val base = Themes.of(ThemeId.SIGIL, ColorMode.SIX)
        return Theme(base.id, base.mode, base.colors, base.type, base.frames, controls = base.controls, backdrop = backdrop, bars = base.bars, material = base.material)
    }

    /** The backdrop with the driver, as FogShell has them, on [clock], with [idle] and an [active] page; plus [extra] holders. */
    private fun show(
        theme: Theme, effects: () -> Effects, clock: ThemeClock, idle: State<Boolean> = mutableStateOf(false),
        active: () -> Boolean = { true }, extraHold: () -> Boolean = { false },
    ) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            view = LocalView.current
            FogTheme(effects(), theme) {
                CompositionLocalProvider(LocalThemeClock provides clock, LocalIdle provides idle, LocalPageActive provides active()) {
                    ThemeClockDriver(clock, theme.backdrop, idle)
                    clock.Hold(extraHold())
                    Box(Modifier.size(320.dp, 640.dp)) { FogBackground(rememberBackdrop(), Modifier.fillMaxSize(), null) }
                }
            }
        }
        frames(3)
    }

    private fun frames(n: Int) = repeat(n) {
        rule.mainClock.advanceTimeBy(16)
        draw()
    }

    private fun draw() = rule.runOnUiThread { view!!.rootView.draw(Canvas(Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888))) }

    private fun ticksOver(frames: Int): Int {
        val before = FogDebug.get("clock.tick")
        frames(frames)
        return FogDebug.get("clock.tick") - before
    }

    @Test fun aMovingBackdropTicksAtFullAndNotAtSaverOrWithReducedMotion() {
        val clock = ThemeClock()
        var effects by mutableStateOf(effectsFor(EffectLevel.FULL, false))
        show(themed(Moving(moves = true)), { effects }, clock)
        assertEquals("the backdrop holds the clock", 1, clock.holders)
        assertTrue("FULL ticks every frame", ticksOver(60) >= 55)
        effects = effectsFor(EffectLevel.SAVER, false)
        frames(2)
        assertEquals("SAVER: nothing holds the clock", 0, clock.holders)
        assertEquals("SAVER: 0 ticks over 60 frames", 0, ticksOver(60))
        effects = effectsFor(EffectLevel.FULL, reducedMotion = true)
        frames(2)
        assertEquals("reduced motion: 0 ticks over 60 frames", 0, ticksOver(60))
        effects = effectsFor(EffectLevel.BALANCED, false)
        frames(2)
        assertTrue("BALANCED moves", ticksOver(30) >= 25)
    }

    @Test fun aStillBackdropNeverHoldsTheClock() {
        val clock = ThemeClock()
        show(themed(Moving(moves = false)), { effectsFor(EffectLevel.FULL, false) }, clock)
        assertEquals(0, clock.holders)
        assertEquals(0, ticksOver(60))
    }

    @Test fun anInactivePageCostsNoTicks() {
        val clock = ThemeClock()
        var active by mutableStateOf(false)
        show(themed(Moving(moves = true)), { effectsFor(EffectLevel.FULL, false) }, clock, active = { active })
        assertEquals("page inactive: 0 ticks", 0, ticksOver(60))
        active = true
        frames(2)
        assertTrue(ticksOver(30) >= 25)
    }

    @Test fun idleSlowsTheClockToATickPer66Ms() {
        val clock = ThemeClock()
        val idle = mutableStateOf(true)
        show(themed(Moving(moves = true)), { effectsFor(EffectLevel.FULL, false) }, clock, idle)
        val t0 = clock.time.value
        val ticks = ticksOver(62) // about a second
        assertTrue("idle: $ticks ticks in a second", ticks in 10..16)
        assertTrue("and a quarter speed: ${clock.time.value - t0} s", clock.time.value - t0 < .35f)
    }

    @Test fun aBackdropsFrameMsCapsItsTicks() {
        val clock = ThemeClock()
        show(themed(Moving(moves = true, frameMs = 33)), { effectsFor(EffectLevel.FULL, false) }, clock)
        val ticks = ticksOver(62) // about a second at 16 ms a frame
        assertTrue("frameMs 33: $ticks ticks in a second", ticks in 20..31)
    }

    @Test fun idlePauseStopsTheBackdropWhileIdleAndStartsAgainOnATouch() {
        val clock = ThemeClock()
        val idle = mutableStateOf(true)
        show(themed(Moving(moves = true, idlePause = true)), { effectsFor(EffectLevel.FULL, false) }, clock, idle)
        assertEquals("idlePause: 0 ticks while idle", 0, ticksOver(62))
        idle.value = false
        frames(2)
        assertTrue("awake again", ticksOver(30) >= 25)
    }

    /**
     * eng E19 / E5: while another drawing holds the clock and it ticks, a still backdrop's draw never reads the clock, so a tick
     * never redraws it (Robolectric redraws every node on each draw, so the test records the snapshot reads of a whole draw, as
     * ThemeMarkTest does). The control: a moving backdrop's motion layer does read it.
     */
    @Test fun aStillBackdropIsNotRedrawnWhileAMarkHoldsTheClock() {
        val clock = ThemeClock()
        var theme by mutableStateOf(themed(Moving(moves = false)))
        rule.mainClock.autoAdvance = false
        val idle = mutableStateOf(false)
        rule.setContent {
            view = LocalView.current
            FogTheme(effectsFor(EffectLevel.FULL, false), theme) {
                CompositionLocalProvider(LocalThemeClock provides clock, LocalIdle provides idle) {
                    ThemeClockDriver(clock, theme.backdrop, idle)
                    clock.Hold(true) // a running status dot elsewhere
                    Box(Modifier.size(320.dp, 640.dp)) { FogBackground(rememberBackdrop(), Modifier.fillMaxSize(), null) }
                }
            }
        }
        frames(3)
        val before = FogDebug.get("clock.tick")
        repeat(60) {
            rule.mainClock.advanceTimeBy(16)
            assertFalse("the still backdrop read the clock while drawing (frame $it)", readsDuringDraw().any { r -> r === clock.source })
        }
        assertTrue("the clock ticked meanwhile: ${FogDebug.get("clock.tick") - before}", FogDebug.get("clock.tick") - before >= 55)
        theme = themed(Moving(moves = true))
        frames(2)
        assertTrue("a moving backdrop reads the clock in its motion layer", readsDuringDraw().any { r -> r === clock.source })
    }

    /** FogShell provides the clock and drives it: a moving backdrop ticks in the shell at FULL and not at SAVER. */
    @Test fun theShellDrivesTheThemeClockForItsBackdrop() {
        var effects by mutableStateOf(effectsFor(EffectLevel.FULL, false))
        val theme = themed(Moving(moves = true))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            view = LocalView.current
            FogTheme(effects, theme) {
                FogShell(
                    status = MainUiState.Ready, connection = MuxState.OPEN, nav = ShellNav(),
                    composer = ComposerUi("", {}, enabled = true, canSend = false, running = false, onSend = {}, onStop = {}),
                    onRetry = {}, onExportErrorLog = {},
                ) { }
            }
        }
        frames(3)
        assertTrue("the shell's theme clock ticks for a moving backdrop", ticksOver(30) >= 25)
        effects = effectsFor(EffectLevel.SAVER, false)
        frames(2)
        assertEquals("SAVER: still", 0, ticksOver(60))
    }

    private fun readsDuringDraw(): Set<Any> {
        val reads = HashSet<Any>()
        rule.runOnUiThread {
            Snapshot.observe(readObserver = { reads += it }) { view!!.rootView.draw(Canvas(Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888))) }
        }
        return reads
    }
}
