package app.dshmobile.ui.fog

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
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

/** With real blur on, each glass surface re-blurs the background: only what is on screen may hold a blur layer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GlassLayersTest {
    @get:Rule val rule = createComposeRule()
    private var view: View? = null
    private var base = 0

    @Before fun enable() {
        FogDebug.enabled = true
        FogDebug.reset()
        base = BlurLayers.active
    }

    @After fun off() {
        FogDebug.enabled = false
    }

    private fun frames(n: Int) = repeat(n) {
        rule.mainClock.advanceTimeBy(16)
        rule.runOnUiThread { view!!.rootView.draw(Canvas(Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888))) }
    }

    private val scroll = androidx.compose.foundation.ScrollState(0)

    private fun show(level: EffectLevel, panels: Int, shown: () -> Boolean = { true }) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            view = LocalView.current
            FogTheme(effectsFor(level, false)) {
                val backdrop = rememberBackdrop()
                CompositionLocalProvider(LocalBackdrop provides backdrop, LocalPageActive provides false) {
                    Box(Modifier.size(300.dp)) {
                        FogBackground(backdrop)
                        if (shown()) {
                            // A viewport of 300 dp, panels of 100 dp with 10 dp between: never more than three fit whole, four with a sliver.
                            Column(Modifier.size(300.dp).verticalScroll(scroll), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                                repeat(panels) { Box(Modifier.fillMaxWidth().height(100.dp).glass(RoundedCornerShape(16.dp)).background(Color.Transparent)) }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun blurLayersAreBoundedByWhatIsOnScreen() {
        show(EffectLevel.FULL, panels = 30)
        frames(3)
        val atTop = BlurLayers.active - base
        assertTrue("some visible panels blur: $atTop", atTop >= 3)
        assertTrue("30 panels, but only the visible ones hold a layer: $atTop", atTop <= 4)
        // Scroll to the middle and the end: the layers follow the screen, they do not pile up.
        for (to in listOf(1500, 2800)) {
            rule.runOnUiThread { runBlocking { scroll.scrollTo(to) } }
            frames(3)
            val now = BlurLayers.active - base
            assertTrue("scrolled to $to: $now layers", now in 3..4)
        }
    }

    @Test fun noBlurLayersAtBalancedOrSaver() {
        show(EffectLevel.BALANCED, panels = 10)
        frames(3)
        assertEquals(0, BlurLayers.active - base)
    }

    @Test fun layersAreGivenBackWhenBlurGoesOffAndWhenTheSurfacesLeave() {
        var level by mutableStateOf(EffectLevel.FULL)
        var shown by mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            view = LocalView.current
            FogTheme(effectsFor(level, false)) {
                val backdrop = rememberBackdrop()
                CompositionLocalProvider(LocalBackdrop provides backdrop, LocalPageActive provides false) {
                    Box(Modifier.size(300.dp)) {
                        FogBackground(backdrop)
                        if (shown) Box(Modifier.size(100.dp).glass(RoundedCornerShape(16.dp)))
                    }
                }
            }
        }
        frames(3)
        assertEquals(1, BlurLayers.active - base)
        level = EffectLevel.BALANCED // the governor stepping down, say
        frames(3)
        assertEquals("blur off gives the layer back", 0, BlurLayers.active - base)
        level = EffectLevel.FULL
        frames(3)
        assertEquals(1, BlurLayers.active - base)
        shown = false
        frames(3)
        assertEquals("a surface that leaves gives its layer back", 0, BlurLayers.active - base)
    }

    @Test fun debugCountersDoNothingUnlessSwitchedOn() {
        FogDebug.enabled = false
        FogDebug.reset()
        FogDebug.count("x")
        assertEquals(0, FogDebug.get("x"))
        FogDebug.enabled = true
        FogDebug.count("x")
        assertEquals(1, FogDebug.get("x"))
        assertFalse(FogDebug.enabled.not())
    }

    @Test fun textLayoutsAreComputedOncePerStringAndBounded() {
        var computed = 0
        val cache = StringCache<String>(3) { computed++; it.uppercase() }
        repeat(10) { assertEquals("A", cache["a"]) }
        assertEquals(1, computed)
        cache["b"]; cache["c"]; cache["d"]
        assertEquals(3, cache.size)
        cache["a"]
        assertEquals("the oldest was dropped", 5, computed)
    }

    /** The panel's rise is a graphicsLayer move, which never reaches onGloballyPositioned: arriving has to redraw the blur anyway. */
    @Test fun aPanelThatRoseInHasItsBlurLockedToTheBackgroundAtTheEnd() {
        rule.mainClock.autoAdvance = false
        var pos = androidx.compose.ui.geometry.Offset.Zero
        rule.setContent {
            view = LocalView.current
            FogTheme(effectsFor(EffectLevel.FULL, false)) {
                val backdrop = rememberBackdrop()
                CompositionLocalProvider(LocalBackdrop provides backdrop, LocalPageActive provides false) {
                    Box(Modifier.size(300.dp)) {
                        FogBackground(backdrop)
                        Box(Modifier.androidx_offset(40, 60)) {
                            FogPanel(
                                title = "t",
                                modifier = Modifier.size(200.dp, 120.dp).onGloballyPositioned { pos = it.positionInWindow() },
                            ) {}
                        }
                    }
                }
            }
        }
        frames(3) // mid-rise, before the animation's delay even ends
        rule.mainClock.advanceTimeBy(3_000)
        frames(3)
        assertEquals(-pos, FogDebug.lastBackdropShift)
    }

    private fun Modifier.androidx_offset(x: Int, y: Int) = this.offset(x.dp, y.dp)
}
