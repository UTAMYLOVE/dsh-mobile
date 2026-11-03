package app.dshmobile.ui.fog

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The owner's biggest complaint is lag: whatever is built for drawing must be built once per size, not once per frame. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FogDrawCacheTest {
    @get:Rule val rule = createComposeRule()

    @Before fun clean() {
        FogDebug.enabled = true
        FogDebug.reset()
    }

    @org.junit.After fun off() {
        FogDebug.enabled = false
    }

    private var view: View? = null

    /** One frame: advance the (manual) clock, then draw the window into a bitmap, which runs every draw lambda that is out of date. */
    private fun frames(n: Int) = repeat(n) {
        rule.mainClock.advanceTimeBy(16)
        rule.runOnUiThread { view!!.rootView.draw(Canvas(Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888))) }
    }

    @Test fun animatedBackgroundRingAndMeterBuildTheirDrawObjectsOnce() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            view = LocalView.current
            FogTheme(effectsFor(EffectLevel.FULL, false)) {
                Box(Modifier.size(300.dp)) {
                    FogBackground(rememberBackdrop())
                    HudRing(0.6f, 120.dp) {}
                    Meter(0.5f, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        frames(2)
        val glows = FogDebug.get("background.glows")
        val grid = FogDebug.get("background.grid")
        val ring = FogDebug.get("ring.cache")
        val meter = FogDebug.get("meter.cache")
        assertTrue("caches built at all: $glows $grid $ring $meter", glows > 0 && grid > 0 && ring > 0 && meter > 0)
        frames(60)
        assertTrue("the clock drives real frames", FogDebug.get("background.glows.draw") >= 30)
        assertEquals(glows, FogDebug.get("background.glows"))
        assertEquals(grid, FogDebug.get("background.grid"))
        assertEquals(ring, FogDebug.get("ring.cache"))
        assertEquals(meter, FogDebug.get("meter.cache"))
    }

    @Test fun glassKeepsItsCacheAcrossRedrawsOnTheSameSize() {
        rule.mainClock.autoAdvance = false
        var tick by mutableIntStateOf(0)
        rule.setContent {
            view = LocalView.current
            FogTheme(effectsFor(EffectLevel.FULL, false)) {
                val backdrop = rememberBackdrop()
                CompositionLocalProvider(LocalBackdrop provides backdrop) {
                    Box(Modifier.size(300.dp)) {
                        FogBackground(backdrop)
                        // Reading `tick` while drawing makes the whole glass layer redraw.
                        Box(Modifier.size(120.dp).glass(RoundedCornerShape(22.dp)).drawBehind { tick })
                    }
                }
            }
        }
        frames(2)
        val drawsBefore = FogDebug.get("glass.draw")
        assertTrue("glass never drew", drawsBefore > 0)
        repeat(20) { tick++; frames(2) }
        assertTrue("glass redrew ${FogDebug.get("glass.draw") - drawsBefore} times", FogDebug.get("glass.draw") - drawsBefore >= 20)
        assertEquals(1, FogDebug.get("glass.cache"))
        assertEquals(1, FogDebug.get("glass.effect"))
    }

    @Test fun movingAPanelRedrawsItsBlurLockedToTheBackground() {
        rule.mainClock.autoAdvance = false
        var x by mutableIntStateOf(0)
        rule.setContent {
            view = LocalView.current
            FogTheme(effectsFor(EffectLevel.FULL, false)) {
                val backdrop = rememberBackdrop()
                // A still background: only the panel's own move can cause a redraw.
                CompositionLocalProvider(LocalBackdrop provides backdrop, LocalPageActive provides false) {
                    Box(Modifier.size(300.dp)) {
                        FogBackground(backdrop)
                        Box(Modifier.offset(x = x.dp).size(120.dp).glass(RoundedCornerShape(22.dp)))
                    }
                }
            }
        }
        frames(2)
        val moved = FogDebug.get("glass.moved")
        val shift = FogDebug.lastBackdropShift
        x = 40
        frames(3)
        // The harness redraws everything on demand, so what is pinned here is that the node asks for the redraw itself.
        assertTrue("moving the panel did not invalidate its draw", FogDebug.get("glass.moved") > moved)
        assertNotEquals(shift, FogDebug.lastBackdropShift)
        assertEquals(1, FogDebug.get("glass.cache"))
    }

    @Test fun countUpDoesNotRecomposeItsCaller() {
        var compositions = 0
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.FULL, false)) {
                compositions++
                CountUpText(500.0, { it.toInt().toString() }, TextStyle.Default)
            }
        }
        rule.mainClock.advanceTimeBy(1200)
        assertEquals(1, compositions)
    }
}
