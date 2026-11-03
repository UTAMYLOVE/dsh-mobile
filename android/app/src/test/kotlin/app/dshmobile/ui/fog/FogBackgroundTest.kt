package app.dshmobile.ui.fog

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/** The background is on screen all the time: what it costs per frame, idle and at each level, and the grid's perspective. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FogBackgroundTest {
    @get:Rule val rule = createComposeRule()
    private var view: View? = null

    @Before fun on() {
        FogDebug.enabled = true
        FogDebug.reset()
    }

    @After fun off() {
        FogDebug.enabled = false
        FogSwitches.plainBackground = false
    }

    private fun frames(n: Int) = repeat(n) {
        rule.mainClock.advanceTimeBy(16)
        rule.runOnUiThread { view!!.rootView.draw(Canvas(Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888))) }
    }

    private fun show(level: EffectLevel, idle: Boolean = false) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            view = LocalView.current
            FogTheme(effectsFor(level, false)) {
                CompositionLocalProvider(LocalIdle provides mutableStateOf(idle)) {
                    Box(Modifier.size(300.dp)) { FogBackground(rememberBackdrop()) }
                }
            }
        }
        frames(3)
    }

    /**
     * Idle, the drift slows to a quarter and the clock ticks about every 66 ms instead of every frame (B1-I2). Each tick is a
     * redraw request (the draw lambdas read the clock), so ticks are what the screen pays for.
     */
    @Test fun anIdleBackgroundTicksAboutFifteenTimesASecond() {
        show(EffectLevel.FULL, idle = true)
        val before = FogDebug.get("clock.tick")
        frames(62) // about a second
        val ticks = FogDebug.get("clock.tick") - before
        assertTrue("idle: $ticks ticks in a second", ticks in 10..18)
    }

    @Test fun aBusyBackgroundTicksEveryFrame() {
        show(EffectLevel.FULL, idle = false)
        val before = FogDebug.get("clock.tick")
        frames(62)
        val ticks = FogDebug.get("clock.tick") - before
        assertTrue("busy: $ticks ticks in a second", ticks >= 55)
    }

    /** SAVER is the safe mode: the grid's lines are projected once and drawn flat (no 3D transform, no offscreen), and nothing ticks. */
    @Test fun saverDrawsTheGridFlatAndNothingTicks() {
        show(EffectLevel.SAVER)
        frames(30)
        assertEquals("no 3D grid in SAVER", 0, FogDebug.get("background.grid.depth"))
        assertTrue(FogDebug.get("background.grid.flat") > 0)
        assertEquals(0, FogDebug.get("clock.tick"))
    }

    @Test fun fullDrawsTheGridInPerspective() = depth(EffectLevel.FULL)

    @Test fun balancedDrawsTheGridInPerspective() = depth(EffectLevel.BALANCED)

    private fun depth(level: EffectLevel) {
        show(level)
        frames(10)
        assertEquals(0, FogDebug.get("background.grid.flat"))
        assertTrue(FogDebug.get("background.grid.depth") > 0)
    }

    /** The bisecting switch for renderer crashes: only the pink ground, nothing animated. */
    @Test fun thePlainBackgroundSwitchDrawsNoGlowsAndNoGrid() {
        FogSwitches.plainBackground = true
        show(EffectLevel.FULL)
        frames(10)
        assertEquals(0, FogDebug.get("background.glows"))
        assertEquals(0, FogDebug.get("background.grid"))
        rule.onNode(SemanticsMatcher.expectValue(FogSemantics.Animating, false), useUnmergedTree = true).assertExists()
    }

    // ---------- the grid's geometry (the mockup: perspective(340px), rotateX(68deg), slab 200% x 42%) ----------

    private val w = 412f
    private val h = 915f
    private val floor = GridSlab(w, h, 1f, ceiling = false)
    private val ceiling = GridSlab(w, h, 1f, ceiling = true)

    @Test fun theFloorsNearEdgeSitsOnTheScreensBottomAndItsFarEdgeRecedesUpAndNarrower() {
        val nearL = floor.project(0f, floor.height)
        val nearR = floor.project(floor.width, floor.height)
        val farL = floor.project(0f, 0f)
        val farR = floor.project(floor.width, 0f)
        assertEquals(h, nearL.y, .01f)
        assertEquals("the near edge keeps its width (-50% .. 150%)", -w / 2f, nearL.x, .01f)
        assertEquals(w * 1.5f, nearR.x, .01f)
        assertTrue("the far edge is above the near edge", farL.y < nearL.y)
        assertTrue("the far edge is narrower", farR.x - farL.x < (nearR.x - nearL.x) * .6f)
        // CSS: perspective-origin at the slab's centre, so the far edge rises to about 169 px above the bottom (not 70, which a
        // camera at the rotation's origin gives).
        assertEquals(169f, h - farL.y, 2f)
        assertEquals("centred", w / 2f, (farL.x + farR.x) / 2f, .01f)
    }

    @Test fun theCeilingIsTheFloorMirrored() {
        for (v in listOf(0f, 100f, 300f, floor.height)) {
            val f = floor.project(44f, v)
            val c = ceiling.project(44f, v)
            assertEquals(f.x, c.x, .01f)
            assertEquals(h - f.y, c.y, .01f)
        }
    }

    @Test fun theMatrixIsTheSameProjection() {
        for (s in listOf(floor, ceiling)) for (u in listOf(0f, 200f, 824f)) for (v in listOf(0f, 150f, s.height)) {
            val m = s.matrix.map(Offset(u, v))
            val p = s.project(u, v)
            assertTrue("($u,$v) ${s.ceiling}: $m vs $p", abs(m.x - p.x) < .05f && abs(m.y - p.y) < .05f)
        }
    }

    @Test fun theMaskFadesFromTenToEightyFivePercent() {
        val hh = floor.height
        assertEquals(1f, floor.fade(hh), 0f)
        assertEquals(1f, floor.fade(hh * .9f), .001f)
        assertEquals(0f, floor.fade(hh * .15f), .001f)
        assertEquals(.5f, floor.fade(hh * .525f), .001f)
    }
}
