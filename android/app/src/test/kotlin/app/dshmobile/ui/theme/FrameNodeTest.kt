package app.dshmobile.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogDebug
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.effectsFor
import app.dshmobile.ui.fog.flowLight
import app.dshmobile.ui.fog.glass
import app.dshmobile.ui.theme.themes.Hairline
import app.dshmobile.ui.theme.themes.over
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

/** FrameNode, the one painter of theme frames: its order, caches, fading, picture floor, segments and the CLASSIC lambda. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FrameNodeTest {
    @get:Rule val rule = createComposeRule()
    private var view: View? = null

    @Before fun on() {
        FogDebug.enabled = true
        FogDebug.reset()
        counted = 0
    }

    @After fun off() {
        FogDebug.enabled = false
    }

    @Test fun shadesFillsRimsOrnamentsThenContent() {
        val f = Frame(
            Corners.Square,
            fills = listOf(Fill.Solid(Green.copy(alpha = .5f))),
            rims = listOf(Rim.Line(2.dp, Fill.Solid(Color.Blue), inset = 1.dp)),
            shades = listOf(Drop.Hard(Color.Red, 8.dp, 8.dp)),
            ornaments = listOf(Ornament.CornerMarks(arm = 10.dp, width = 2.dp, color = Color.Magenta, outset = 4.dp, corners = setOf(Corner.TopStart))),
            ink = Color.Black, ink2 = Color.Black,
        )
        show {
            Box(Modifier.offset(20.dp, 20.dp).size(40.dp).frame(f)) { Box(Modifier.align(Alignment.Center).size(4.dp).background(Color.Black)) }
        }
        val s = shot()
        // The shade shows only outside the shape (it is clipped out): beyond the box it is red...
        assertColour("shade outside", Color.Red, s.at(64, 64))
        // ...and inside, where it lies under the half-transparent fill, the fill shows over the ground alone.
        assertColour("fill inside, no shade under it", Green.copy(alpha = .5f).over(Color.White), s.at(50, 50))
        assertColour("rim on the edge, over the fill", Color.Blue, s.at(21, 40))
        assertColour("ornament outside the corner", Color.Magenta, s.at(16, 21))
        assertColour("content over all of it", Color.Black, s.at(40, 40))
    }

    @Test fun theCacheIsBuiltOncePerSizeNotPerRedraw() {
        val f = Frame(
            Corners.Round(12f), listOf(Fill.Linear(135f, listOf(0f to Color.White, 1f to Color.Red))),
            rims = listOf(Rim.Dots(6.dp, 2.dp, Color.Blue, 3.dp, Color.White), Rim.Brush(2.dp, Color.Black, Color.White, BrushStart.TopStartPlus22dp)),
            ornaments = listOf(Ornament.Edge(Side.Bottom, 20.dp, 4.dp, Hairline(1.dp, Color.Black))),
            ink = Color.Black, ink2 = Color.Black,
        )
        var tick by mutableIntStateOf(0)
        var side by mutableIntStateOf(60)
        var draws = 0
        show { Box(Modifier.size(side.dp).frame(f).drawBehind { draws++; tick }) }
        shot()
        assertEquals(1, FogDebug.get("frame.cache"))
        val before = draws
        repeat(20) {
            tick++
            shot()
        }
        assertTrue("the frame redrew ${draws - before} times", draws - before >= 20)
        assertEquals("one cache for one size", 1, FogDebug.get("frame.cache"))
        side = 70
        shot()
        assertEquals("a new size builds a new cache", 2, FogDebug.get("frame.cache"))
    }

    @Test fun fadedHalvesTheFillsAndKeepsTheInk() {
        val f = Frame(Corners.Square, listOf(Fill.Solid(Color.Black)), ink = Color.Red, ink2 = Color.Blue)
        val half = f.faded(.5f)
        assertEquals(f.ink, half.ink)
        assertEquals(f.ink2, half.ink2)
        assertEquals(.5f, (half.fills[0] as Fill.Solid).color.alpha, .01f)
        val theme = themeWith(f)
        show {
            CompositionLocalProvider(LocalTheme provides theme) {
                Row {
                    Box(Modifier.size(40.dp).frame(f))
                    Box(Modifier.size(40.dp).frame(half))
                    // chrome's alpha fades the same way.
                    Box(Modifier.size(40.dp).chrome(Surf.PANEL, alpha = .5f) { this })
                }
            }
        }
        val s = shot()
        assertColour("full", Color.Black, s.at(20, 20))
        val grey = Color.Black.copy(alpha = .5f).over(Color.White)
        assertColour("faded", grey, s.at(60, 20))
        assertColour("chrome(alpha = .5)", grey, s.at(100, 20))
    }

    @Test fun darkPendingDashesTheRims() {
        val f = Frame(Corners.Square, emptyList(), rims = listOf(Rim.Line(2.dp, Fill.Solid(Color.Black), inset = 1.dp)), ink = Color.White, ink2 = Color.White)
        val pending = f.darkPending()
        show {
            Column {
                Box(Modifier.size(60.dp, 20.dp).frame(f))
                Box(Modifier.offset(y = 10.dp).size(60.dp, 20.dp).frame(pending))
            }
        }
        val s = shot()
        fun dark(x: Int, y: Int) = s.at(x, y).red < .5f
        val solid = (5..55).count { dark(it, 1) }
        assertEquals("the plain rim is unbroken", 51, solid)
        val row = (5..55).map { dark(it, 31) }
        val changes = row.zipWithNext().count { (a, b) -> a != b }
        assertTrue("the pending rim is dashed: ${row.count { it }} dark of 51, $changes changes", row.count { it } in 20..40 && changes >= 8)
    }

    @Test fun aDarkPictureFloorsTheLightFillsOfTextSurfacesOnly() {
        val w60 = Frame(Corners.Square, listOf(Fill.Solid(Color.White.copy(alpha = .6f))), ink = Color.Black, ink2 = Color.Black)
        var behind by mutableStateOf(true)
        val theme = themeWith(w60)
        show(ground = Color.Black) {
            CompositionLocalProvider(LocalTheme provides theme, LocalPictureBehind provides behind) {
                Row {
                    Box(Modifier.size(40.dp).chrome(Surf.PANEL) { this })
                    Box(Modifier.size(40.dp).chrome(Surf.BADGE) { this })
                }
            }
        }
        var s = shot()
        assertColour("PANEL lifted to .74", Color.White.copy(alpha = PictureFloor).over(Color.Black), s.at(20, 20))
        assertColour("BADGE left at .60", Color.White.copy(alpha = .6f).over(Color.Black), s.at(60, 20))
        behind = false
        s = shot()
        assertColour("no picture: PANEL at .60", Color.White.copy(alpha = .6f).over(Color.Black), s.at(20, 20))
    }

    @Test fun flippingThePictureLocalRedrawsTheFrameWithoutRecomposingItsContent() {
        val w60 = Frame(Corners.Square, listOf(Fill.Solid(Color.White.copy(alpha = .6f))), ink = Color.Black, ink2 = Color.Black)
        var behind by mutableStateOf(false)
        val theme = themeWith(w60)
        show(ground = Color.Black) {
            CompositionLocalProvider(LocalTheme provides theme, LocalPictureBehind provides behind) {
                Box(Modifier.size(40.dp).chrome(Surf.PANEL) { this }) { Counted() }
            }
        }
        var s = shot()
        assertColour("before", Color.White.copy(alpha = .6f).over(Color.Black), s.at(20, 20))
        assertEquals(1, counted)
        val caches = FogDebug.get("frame.cache")
        behind = true
        s = shot()
        assertColour("after the flip the frame is redrawn, floored", Color.White.copy(alpha = PictureFloor).over(Color.Black), s.at(20, 20))
        assertEquals("the content inside was not recomposed", 1, counted)
        assertEquals("the flip rebuilt no cache", caches, FogDebug.get("frame.cache"))
    }

    @Test fun segmentPiecesJoinWithoutASeam() {
        val seg = Frame(
            Corners.Round(12f), listOf(Fill.Linear(90f, listOf(0f to Color.Red, 1f to Color.Blue))),
            rims = listOf(Rim.Line(2.dp, Fill.Solid(Color.Black), inset = 1.dp)),
            ornaments = listOf(Ornament.Glow(Fill.RadialDp(0f, 0f, 24.dp, 16.dp, listOf(0f to Color.White, 1f to Color.White.copy(alpha = 0f))))),
            ink = Color.White, ink2 = Color.White,
        )
        val theme = themeWith(seg, segmentReply = seg)
        show {
            CompositionLocalProvider(LocalTheme provides theme) {
                Column {
                    Box(Modifier.size(60.dp, 30.dp).segmentChrome(user = false, first = true, last = false) { this })
                    Box(Modifier.size(60.dp, 30.dp).segmentChrome(user = false, first = false, last = false) { this })
                    Box(Modifier.size(60.dp, 30.dp).segmentChrome(user = false, first = false, last = true) { this })
                }
            }
        }
        val s = shot()
        // Down the middle, across both cuts, the colour never jumps: no rim, no gap, no restart of the gradient.
        for (y in 4..86) {
            val a = s.at(30, y)
            val b = s.at(30, y + 1)
            assertTrue("seam between y $y and ${y + 1}: $a / $b", channelGap(a, b) <= 3f / 255f)
        }
        assertTrue("no rim at the first cut", s.at(30, 30).blue > .2f || s.at(30, 30).red > .2f)
        // The rim closes only at the bubble's own top and bottom, and runs on down the side across the cuts.
        assertColour("top rim on the first piece", Color.Black, s.at(30, 1))
        assertColour("bottom rim on the last piece", Color.Black, s.at(30, 88))
        for (y in listOf(29, 30, 31, 59, 60, 61)) assertColour("side rim at y $y", Color.Black, s.at(1, y))
        // A glow anchored at the top shows on the first piece only.
        assertTrue("the top glow lights the first piece", s.at(6, 6).green > .3f)
        assertTrue("the middle piece has no glow", s.at(6, 36).green < .1f)
    }

    @Test fun ornamentsSitOnTheirCornersAndSides() {
        val f = Frame(
            Corners.Round(4f, 4f, 4f, 16f), listOf(Fill.Solid(Color.White)),
            ornaments = listOf(
                Ornament.Stub(Corner.BottomEnd, length = 12.dp, thickness = 3.dp, color = Color.Red, outside = true),
                Ornament.StartBar(3.dp, Fill.Solid(Color.Blue), inset = 6.dp),
                Ornament.Edge(Side.Top, 20.dp, gap = 4.dp, drawing = Hairline(2.dp, Green)),
                Ornament.TailMark(TailKind.Diamond, 8.dp, Color.Magenta, rim = null, rimWidth = 0.dp),
            ),
            ink = Color.Black, ink2 = Color.Black,
        )
        show(ground = Color.Gray) {
            Column {
                Box(Modifier.absoluteOffset(20.dp, 20.dp).size(60.dp, 40.dp).frame(f))
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.absoluteOffset(20.dp, 40.dp).size(60.dp, 40.dp).frame(f))
                }
            }
        }
        val s = shot()
        // LTR box: x 20..80, y 20..60.
        assertColour("stub just below the bottom-end", Color.Red, s.at(74, 61))
        assertColour("start bar on the start edge", Color.Blue, s.at(21, 40))
        assertColour("edge 4 dp above the top, centred", Green, s.at(50, 16))
        // The smallest corner (4 dp) is a top one, but ties go bottom-end first: (4, 4, 4, 16) → BottomEnd.
        assertColour("tail mark on the bottom-end corner", Color.Magenta, s.at(79, 59))
        // RTL box (laid out under the first, at y 40, moved 40 down): x 20..80, y 80..120; start and end are swapped.
        assertColour("RTL stub below the bottom-end (left)", Color.Red, s.at(26, 121))
        assertColour("RTL start bar on the right", Color.Blue, s.at(78, 100))
    }

    @Test fun chromeRunsTheClassicLambdaOnlyUnderClassic() {
        var classicCalls = 0
        var theme by mutableStateOf(themeWith(Frame(Corners.Round(16f), listOf(Fill.Solid(Color.White)), ink = Color.Black, ink2 = Color.Black)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            view = LocalView.current
            FogTheme(effectsFor(EffectLevel.FULL, false)) {
                CompositionLocalProvider(LocalTheme provides theme, LocalDensity provides Density(1f)) {
                    val shape = RoundedCornerShape(16.dp)
                    // The DOCK/HEADER chain of today: glass, then the @Composable flowLight with its InfiniteTransition.
                    Box(Modifier.size(80.dp).chrome(Surf.HEADER) { classicCalls++; glass(shape).flowLight(shape) })
                }
            }
        }
        frames(3)
        val a = shot()
        frames(20)
        val b = shot()
        assertEquals("a theme never runs the classic chain", 0, classicCalls)
        assertTrue("nothing moves on the themed header", a.sameAs(b))
        assertEquals(0, FogDebug.get("anim.frame"))
        // FlowLight draws nothing: no flow colour on the outline (the frame is plain white there).
        assertColour("the themed outline", Color.White, a.at(40, 1))
        // Under CLASSIC the same call site is today's chain: flowLight's transition moves the outline every frame.
        theme = Themes.Classic
        frames(3)
        val c = shot()
        frames(20)
        val d = shot()
        assertTrue("CLASSIC runs the chain", classicCalls >= 1)
        assertFalse("CLASSIC's flow light moves", c.sameAs(d))
    }

    // ───── helpers ─────

    private fun show(ground: Color = Color.White, content: @Composable () -> Unit) {
        rule.setContent {
            view = LocalView.current
            // One px per dp, so the probes below are dp.
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.size(200.dp, 200.dp).background(ground)) { content() }
            }
        }
    }

    private fun shot(): Bitmap {
        rule.waitForIdle()
        val v = view!!
        return Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { b -> rule.runOnUiThread { v.draw(Canvas(b)) } }
    }

    private fun frames(n: Int) = repeat(n) {
        rule.mainClock.advanceTimeBy(16)
        rule.runOnUiThread { view!!.rootView.draw(Canvas(Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888))) }
    }

    private fun Bitmap.at(x: Int, y: Int) = Color(getPixel(x, y))

    private fun channelGap(a: Color, b: Color) = maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))

    private fun assertColour(message: String, expected: Color, actual: Color) {
        assertTrue("$message: expected $expected, was $actual", channelGap(expected, actual) <= 3f / 255f)
    }

    private companion object {
        val Green = Color(0xFF00FF00)
    }
}

/** How many times [Counted] has been composed. */
private var counted = 0

@Composable
private fun Counted() {
    counted++
    Box(Modifier.size(2.dp))
}

/** A theme whose every frame is [base], with [segmentReply] for long replies. */
internal fun themeWith(base: Frame, segmentReply: Frame = base.forSegment()): Theme = Theme(
    id = ThemeId.SIGIL, mode = ColorMode.SIX, colors = Palettes.Drawn, type = ThemeType.Classic,
    frames = ThemeFrames(
        panel = base, reply = base, user = base, chip = base, chipAccent = base, field = base, dock = base,
        segTrack = base, segOn = base, segTrackOff = base, segOnOff = base, segmentReply = segmentReply,
    ),
    controls = ThemeControls(caret = Sakura.Primary, iconInk = Sakura.Text, tabInkOn = Sakura.Text, tabInkOff = Sakura.TextSecondary),
    material = materialOf(ThemeId.SIGIL, ColorMode.SIX, Palettes.Drawn),
)
