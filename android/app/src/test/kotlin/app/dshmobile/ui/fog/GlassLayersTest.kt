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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
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
        FogSemantics.exposeBlur = true
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

    /** Every blur layer re-blurs the background each frame: past [BlurLayers.MAX] a surface draws the no-blur fill instead. */
    @Test fun noMoreThanFourBlurLayersLiveAtOnce() {
        var count by mutableStateOf(6)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            view = LocalView.current
            FogTheme(effectsFor(EffectLevel.FULL, false)) {
                val backdrop = rememberBackdrop()
                CompositionLocalProvider(LocalBackdrop provides backdrop, LocalPageActive provides false) {
                    Box(Modifier.size(300.dp)) {
                        FogBackground(backdrop)
                        Column {
                            repeat(count) { Box(Modifier.size(40.dp).glass(RoundedCornerShape(16.dp))) }
                        }
                    }
                }
            }
        }
        frames(3)
        assertEquals("six surfaces on screen, four layers", BlurLayers.MAX, BlurLayers.active - base)
        assertEquals("the rest report no blur", BlurLayers.MAX, blurFlags().count { it })
        count = 5 // one with a layer leaves: one of the others takes the freed slot
        frames(3)
        assertEquals(BlurLayers.MAX, BlurLayers.active - base)
        count = 3
        frames(3)
        assertEquals(3, BlurLayers.active - base)
        assertEquals(3, blurFlags().count { it })
    }

    /** The dock is drawn after the page: at the cap it still blurs, taking a page panel's layer (that panel draws the plain fill). */
    @Test fun theFloatingChromeOutranksPagePanelsAtTheCap() {
        val floor = BlurFloor() // only pages get one (FogShell)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            view = LocalView.current
            FogTheme(effectsFor(EffectLevel.FULL, false)) {
                val backdrop = rememberBackdrop()
                CompositionLocalProvider(LocalBackdrop provides backdrop, LocalPageActive provides false) {
                    Box(Modifier.size(300.dp)) {
                        FogBackground(backdrop)
                        CompositionLocalProvider(LocalBlurFloor provides floor) {
                            Column { repeat(5) { Box(Modifier.size(40.dp).glass(RoundedCornerShape(16.dp))) } }
                        }
                        Box(Modifier.offset(y = 250.dp).size(200.dp, 40.dp).testTag("dock").glass(RoundedCornerShape(30.dp)))
                    }
                }
            }
        }
        frames(3)
        assertEquals(BlurLayers.MAX, BlurLayers.active - base)
        rule.onNodeWithTag("dock").assert(SemanticsMatcher.expectValue(FogSemantics.Blur, true))
        assertEquals(BlurLayers.MAX, blurFlags().count { it })
    }

    /** A panel taller than the screen records only the part the window shows (a full-size layer can pass the GPU's texture limit). */
    @Test fun aTallPanelRecordsOnlyWhatTheWindowShows() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            view = LocalView.current
            FogTheme(effectsFor(EffectLevel.FULL, false)) {
                val backdrop = rememberBackdrop()
                CompositionLocalProvider(LocalBackdrop provides backdrop, LocalPageActive provides false) {
                    Box(Modifier.size(300.dp)) {
                        FogBackground(backdrop)
                        Column(Modifier.size(300.dp).verticalScroll(scroll)) {
                            Box(Modifier.fillMaxWidth().height(3_000.dp).glass(RoundedCornerShape(16.dp)))
                        }
                    }
                }
            }
        }
        frames(3)
        val viewport = with(rule.density) { 300.dp.roundToPx() }
        assertEquals(1, BlurLayers.active - base)
        assertTrue("layer ${FogDebug.lastLayerSize} for a ${viewport}px viewport", FogDebug.lastLayerSize.height in 1..viewport + LayerSnapPx)
        rule.runOnUiThread { runBlocking { scroll.scrollTo(4_000) } }
        frames(3)
        assertTrue("scrolled: layer ${FogDebug.lastLayerSize}", FogDebug.lastLayerSize.height in 1..viewport + 2 * LayerSnapPx)
    }

    /** What a page has under the floating dock is faded out there: it holds no layer, and a panel half under it records only the top. */
    @Test fun glassBelowTheDockFloorHoldsNoLayer() {
        val floor = BlurFloor()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            view = LocalView.current
            FogTheme(effectsFor(EffectLevel.FULL, false)) {
                val backdrop = rememberBackdrop()
                CompositionLocalProvider(LocalBackdrop provides backdrop, LocalPageActive provides false, LocalBlurFloor provides floor) {
                    Box(Modifier.size(300.dp)) {
                        FogBackground(backdrop)
                        Box(Modifier.offset(y = 200.dp).fillMaxWidth().height(80.dp).glass(RoundedCornerShape(16.dp)))
                    }
                }
            }
        }
        frames(3)
        assertEquals("no floor: it blurs", 1, BlurLayers.active - base)
        floor.y = with(rule.density) { 150.dp.toPx() }
        frames(3)
        assertEquals("all of it under the dock", 0, BlurLayers.active - base)
        assertEquals(listOf(false), blurFlags())
        floor.y = with(rule.density) { 220.dp.toPx() }
        frames(3)
        assertEquals(1, BlurLayers.active - base)
        assertTrue("only the top 20 dp: ${FogDebug.lastLayerSize}", FogDebug.lastLayerSize.height <= with(rule.density) { 20.dp.roundToPx() } + LayerSnapPx)
    }

    /** Glass is a semantics node only so tests can read [FogSemantics.Blur]: with the flag off it adds no node of its own. */
    @Test fun glassReportsBlurOnlyWhileTheTestFlagIsOn() {
        FogSemantics.exposeBlur = false
        try {
            show(EffectLevel.FULL, panels = 2)
            frames(2)
            assertEquals(emptyList<Boolean>(), blurFlags())
            assertEquals("it still blurs", 2, BlurLayers.active - base)
        } finally {
            FogSemantics.exposeBlur = true
        }
    }

    private fun blurFlags(): List<Boolean> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(FogSemantics.Blur), useUnmergedTree = true).fetchSemanticsNodes().map { it.config[FogSemantics.Blur] }

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

    @Test fun aFullCountUpMeasuresOnlyAboutFifteenGlyphs() {
        var measured = 0
        val cache = GlyphCache { measured++; it }
        val seen = StringBuilder()
        // 0 to 1,234,567.89 in 57 frames, formatted like the overview's numbers.
        for (frame in 0..57) {
            val v = 1_234_567.89 * frame / 57
            cache.forEach("$" + String.format(java.util.Locale.US, "%,.2f", v)) { seen.append(it) }
        }
        assertTrue("measured $measured glyphs for ${seen.length} drawn", measured <= 15)
        assertTrue(seen.length > 300)
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
