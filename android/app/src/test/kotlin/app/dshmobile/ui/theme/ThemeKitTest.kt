package app.dshmobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.GlassRecipe
import app.dshmobile.ui.fog.PictureTone
import app.dshmobile.ui.fog.effectsFor
import app.dshmobile.ui.theme.themes.AQUA
import app.dshmobile.ui.theme.themes.AccentCap
import app.dshmobile.ui.theme.themes.AccentCapFills
import app.dshmobile.ui.theme.themes.BG
import app.dshmobile.ui.theme.themes.INK
import app.dshmobile.ui.theme.themes.INK2
import app.dshmobile.ui.theme.themes.MILK
import app.dshmobile.ui.theme.themes.PINK
import app.dshmobile.ui.theme.themes.ROSE
import app.dshmobile.ui.theme.themes.W
import app.dshmobile.ui.theme.themes.a
import app.dshmobile.ui.theme.themes.alpha
import app.dshmobile.ui.theme.themes.contrast
import app.dshmobile.ui.theme.themes.count
import app.dshmobile.ui.theme.themes.faceCap
import app.dshmobile.ui.theme.themes.gradRim
import app.dshmobile.ui.theme.themes.hair
import app.dshmobile.ui.theme.themes.isPaletteLerp
import app.dshmobile.ui.theme.themes.linear
import app.dshmobile.ui.theme.themes.over
import app.dshmobile.ui.theme.themes.pick
import app.dshmobile.ui.theme.themes.raisedTo
import app.dshmobile.ui.theme.themes.solid
import app.dshmobile.ui.theme.themes.veilFloor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The shared theme kit's maths (D23): contrast, compositing, the six-colour rule (D33) and the picture floors (web §2.3). */
class ThemeKitTest {
    private val one = 1f / 255f

    private fun assertNear(message: String, expected: Float, actual: Float, tolerance: Float) =
        assertTrue("$message: expected $expected ± $tolerance, was $actual", abs(expected - actual) <= tolerance)

    private fun assertSameColour(message: String, expected: Color, actual: Color) {
        val e = listOf(expected.red, expected.green, expected.blue, expected.alpha)
        val a = listOf(actual.red, actual.green, actual.blue, actual.alpha)
        assertTrue("$message: expected $expected, was $actual", e.zip(a).all { (x, y) -> abs(x - y) <= one + 1e-4f })
    }

    // ───── contrast and over ─────

    @Test fun contrastIsWcagAndMatchesCoresNumbers() {
        assertNear("INK/BG", 7.06f, contrast(INK, BG), .01f)
        assertNear("INK2/BG", 4.58f, contrast(INK2, BG), .01f)
        assertNear("INK/PINK", 4.04f, contrast(INK, PINK), .01f)
        assertNear("white/black", 21f, contrast(Color.White, Color.Black), .001f)
        assertEquals(1f, contrast(PINK, PINK), 0f)
        assertEquals(contrast(INK, BG), contrast(BG, INK), 0f)
        // AccentCap is the darkest pink an INK label may sit on.
        assertTrue(contrast(INK, AccentCap) >= 4.5f)
    }

    @Test fun overCompositesInSrgbWithEightBitChannels() {
        // AccentCap is PINK .80 over BG, exactly.
        assertEquals(AccentCap, PINK.a(.80f).over(BG))
        assertEquals(Color(0xFF808080), W.a(.5f).over(Color.Black))
        // Opaque on top hides what is under; nothing on top shows it unchanged.
        assertEquals(INK, INK.over(BG))
        assertEquals(BG, Color.Transparent.over(BG))
        assertEquals(BG, BG.a(0f).over(BG))
        // Translucent over translucent: alpha .75, the colour weighted by what each layer shows.
        assertSameColour("black .5 over white .5", Color(0x55, 0x55, 0x55, 0xC0), Color.Black.a(.5f).over(W.a(.5f)))
        assertEquals(Color.Transparent, Color.Transparent.over(Color.Transparent))
    }

    // ───── D33: the six-colour rule ─────

    @Test fun isPaletteLerpFollowsRulingC() {
        // BG→PINK at t .29, .58 and .80; W→BG; and the two colours allowed only as themselves.
        for (hex in listOf(0xFFF4CCD4, 0xFFFEEDEE, 0xFFE3ACBD, 0xFFE9B9C8, 0xFFF3E6E2, 0xFFB3261E)) {
            assertTrue("#${hex.toString(16)} is a palette lerp", isPaletteLerp(Color(hex)))
        }
        // The PINK–AQUA midpoint (sRGB grey), Sakura O's accent text and the neon accent are not.
        for (hex in listOf(0xFFC0BECC, 0xFF6E6084, 0xFFFF3D84)) {
            assertFalse("#${hex.toString(16)} is not a palette lerp", isPaletteLerp(Color(hex)))
        }
    }

    @Test fun isPaletteLerpTakesEveryPaletteColourAtAnyAlphaWithinItsTolerance() {
        for (c in listOf(BG, MILK, PINK, W, AQUA, ROSE, INK, INK2)) {
            assertTrue(isPaletteLerp(c))
            assertTrue("alpha is ignored", isPaletteLerp(c.a(.3f)))
        }
        assertFalse("alpha is ignored", isPaletteLerp(Color(0xFFC0BECC).a(.5f)))
        // #F4CCD4 is .21 off the BG→PINK line: inside ±3, outside ±0.
        assertFalse(isPaletteLerp(Color(0xFFF4CCD4), tolerance = 0))
        // A lone colour passes only within the tolerance of itself.
        assertTrue(isPaletteLerp(Color(0xFFF5E8E4)))   // #F3E6E2 + 2 per channel
        assertFalse(isPaletteLerp(Color(0xFFF7EAE6)))  // + 4: outside
        // Every PINK→AQUA mixture away from the ends greys out and is refused.
        assertFalse(isPaletteLerp(PINK.a(.5f).over(AQUA)))
    }

    // ───── web §2.3: veil floors and the face cap ─────

    /** One row of web.md §2.3's table: the look's text ink, veil, ground, vignette and designed veil alpha. */
    private class Look(val name: String, val ink: Color, val veil: Color, val ground: Color, val vignette: Color, val designed: Float) {
        fun floor(p05: Float?, pictureAlpha: Float = .80f, target: Float = 4.6f): Float =
            veilFloor(ink, veil, ground, pictureAlpha, p05?.let { PictureTone(it) }, designed, shade = vignette.a(.10f), target = target)
    }

    private val sakuraO = Look("sakura O", Color(0xFF4F4260), Color(0xFFFAF7FD), Color(0xFFF7F1F8), Color(0xFF543A6E), .70f)
    private val sakuraS = Look("sakura S", INK, BG, BG, INK, .70f)
    private val neonO = Look("neon O", Color(0xFF101014), Color(0xFFF4F4F6), Color(0xFF0B0B0E), Color(0xFF1E141E), .54f)
    private val neonS = Look("neon S", INK, BG, INK, INK, .54f)
    private val looks = listOf(sakuraO, sakuraS, neonO, neonS)

    @Test fun veilFloorReproducesWebTable() {
        // web.md §2.3, picture at 圖片濃淡 80 over the ground, vignette .10: p05 → sakura O, sakura S, neon O, neon S.
        val table = mapOf(
            0f to listOf(.75f, .84f, .56f, .87f),
            .02f to listOf(.70f, .81f, .54f, .84f),
            .05f to listOf(.70f, .78f, .54f, .83f),
            .10f to listOf(.70f, .74f, .54f, .80f),
            .20f to listOf(.70f, .70f, .54f, .75f),
            .40f to listOf(.70f, .70f, .54f, .63f),
        )
        for ((p05, row) in table) {
            looks.zip(row).forEach { (look, want) -> assertNear("${look.name} at p05 $p05", want, look.floor(p05), .01f) }
        }
        // Black at 圖片濃淡 100: sakura O .80, sakura S .88, neon S .88.
        assertNear("sakura O black@100", .80f, sakuraO.floor(0f, pictureAlpha = 1f), .01f)
        assertNear("sakura S black@100", .88f, sakuraS.floor(0f, pictureAlpha = 1f), .01f)
        assertNear("neon S black@100", .88f, neonS.floor(0f, pictureAlpha = 1f), .01f)
    }

    @Test fun veilFloorFallsAsThePictureLightensAndKeepsTheDesignedVeilForLightPictures() {
        for (look in looks) {
            var last = Float.MAX_VALUE
            for (i in 0..100) {
                val f = look.floor(i / 100f)
                assertTrue("${look.name}: floor rose from $last to $f at p05 ${i / 100f}", f <= last)
                assertTrue(f >= look.designed && f <= .96f)
                last = f
            }
            for (p05 in listOf(.5f, .7f, 1f)) assertEquals("${look.name} at p05 $p05", look.designed, look.floor(p05), 0f)
            // No tone measured yet: assume black.
            assertEquals(look.floor(0f), look.floor(null), 0f)
            // The status band's 3:1 never needs more veil than text's 4.6:1.
            assertTrue(look.floor(0f, target = 3f) <= look.floor(0f))
        }
        // An ink that can never reach the target stops at the cap, but never below a designed veil above it.
        assertEquals(.96f, veilFloor(W, W, BG, .8f, PictureTone(0f), .5f), 0f)
        assertEquals(.98f, veilFloor(W, W, BG, .8f, PictureTone(0f), .98f), 0f)
    }

    @Test fun faceCapIsWaveNumbers() {
        // Wave S: a black face may show at most α .18 behind INK on BG; wave O allows .50.
        assertNear("wave S black", .18f, faceCap(INK, BG, PictureTone(0f)), .01f)
        assertNear("wave O black", .50f, faceCap(Color(0xFF0B0D10), Color(0xFFF7F8FA), PictureTone(0f)), .01f)
        assertEquals(faceCap(INK, BG, PictureTone(0f)), faceCap(INK, BG, null), 0f)
        assertEquals("a light face never needs a cap", 1f, faceCap(INK, BG, PictureTone(.6f)), 0f)
        // The cap is what it says: at it the ink still reads 4.6:1, a step above it does not.
        val cap = faceCap(INK, BG, PictureTone(0f))
        assertTrue(contrast(INK, Color.Black.a(cap).over(BG)) >= 4.55f)
        assertTrue(contrast(INK, Color.Black.a(cap + .02f).over(BG)) < 4.6f)
    }

    @Test fun raisedToLiftsEveryStopByTheSameAmountUpToTheCap() {
        val stops = listOf(0f to W.a(.5f), .6f to W.a(.7f), 1f to PINK.a(.6f))
        val raised = stops.raisedTo(.8f)
        assertEquals(stops.map { it.first }, raised.map { it.first })
        assertNear("first", .8f, raised[0].second.alpha, one)
        assertNear("second capped", .96f, raised[1].second.alpha, one)
        assertNear("third", .9f, raised[2].second.alpha, one)
        assertEquals(PINK.copy(alpha = 1f), raised[2].second.copy(alpha = 1f))
        assertSame("already above the floor", stops, stops.raisedTo(.4f))
        // A stop already above the cap is never lowered.
        assertEquals(1f, listOf(0f to W.a(.2f), 1f to W).raisedTo(.5f)[1].second.alpha, 0f)
        assertEquals(emptyList<Pair<Float, Color>>(), emptyList<Pair<Float, Color>>().raisedTo(.5f))
    }

    // ───── shorthands ─────

    @Test fun shorthandsBuildTheContractTypes() {
        assertEquals(PINK.copy(alpha = .8f), PINK.a(.8f))
        assertEquals(Fill.Solid(INK), solid(INK))
        assertEquals(Fill.Linear(90f, listOf(0f to W, 1f to BG)), linear(90f, 0f to W, 1f to BG))
        assertEquals(Rim.Line(1.dp, Fill.Solid(ROSE), 2.dp, dash = 3.dp to 2.dp), hair(1.dp, ROSE, 2.dp, dash = 3.dp to 2.dp))
        assertEquals(Rim.Line(1.dp, Fill.Linear(180f, listOf(0f to W)), .5.dp, 1.dp, 2.dp), gradRim(1.dp, listOf(0f to W), inset = .5.dp, dx = 1.dp, dy = 2.dp))
        assertEquals(listOf(Fill.Solid(BG), Fill.Solid(PINK.a(.8f))), AccentCapFills)
        assertEquals(listOf(0f to W.a(.25f), 1f to PINK.a(.5f)), listOf(0f to W.a(.5f), 1f to PINK).alpha(.5f))
        assertEquals("original", ColorMode.ORIGINAL.pick("original", "six"))
        assertEquals("six", ColorMode.SIX.pick("original", "six"))
    }

    @Test fun levelsCountMovingPartsAndFollowTheEffects() {
        assertEquals(7, ThemeLevel.FULL.count(7))
        assertEquals(4, ThemeLevel.BALANCED.count(7))
        assertEquals(3, ThemeLevel.BALANCED.count(6))
        assertEquals(0, ThemeLevel.STILL.count(7))
        assertEquals(ThemeLevel.FULL, effectsFor(EffectLevel.FULL, false).themeLevel)
        assertEquals(ThemeLevel.BALANCED, effectsFor(EffectLevel.BALANCED, false).themeLevel)
        assertEquals(ThemeLevel.STILL, effectsFor(EffectLevel.SAVER, false).themeLevel)
        assertEquals(ThemeLevel.STILL, effectsFor(EffectLevel.FULL, true).themeLevel)
    }

    // ───── Frame's own transforms (D13, web §2.6) ─────

    private val edge = EdgeDrawing { _, _, _ -> { } }
    private val runner = Runner(2400, 900, OutlineDrawing { _, _ -> { } })
    private val rich = Frame(
        Corners.Round(22f),
        fills = listOf(solid(W.a(.8f)), linear(140f, 0f to W, 1f to PINK.a(.6f))),
        rims = listOf(
            hair(1.dp, ROSE.a(.4f)),
            // The drawn tokens: petal storm's dots and one-stroke's brush are translucent (drawn.md), so "× .5" and "at .5" differ.
            Rim.Dots(6.dp, 2.dp, PINK.a(.9f), 4.dp, W.a(.6f), glow = AQUA.a(.5f)),
            Rim.Brush(2.dp, ROSE.a(.78f), W.a(.6f), BrushStart.Tail),
        ),
        shades = listOf(Drop.Soft(ROSE.a(.2f), 12.dp, dy = 4.dp), Drop.Hard(INK, 2.dp, 2.dp)),
        ornaments = listOf(
            Ornament.CornerMarks(8.dp, 1.dp, INK, 0.dp, setOf(Corner.TopStart), inner = PINK),
            Ornament.Glow(Fill.RadialDp(0f, 0f, 40.dp, 20.dp, listOf(0f to AQUA.a(.6f), 1f to AQUA.a(0f)))),
            Ornament.Edge(Side.Bottom, 64.dp, 2.dp, edge),
        ),
        glass = GlassRecipe.Classic,
        ink = INK, ink2 = INK2, runner = runner,
    )

    @Test fun fadedScalesFillsRimsOrnamentsAndGlassKeepsInkAndDropsShades() {
        val f = rich.faded(.5f)
        assertEquals(listOf(solid(W.a(.4f)), linear(140f, 0f to W.a(.5f), 1f to PINK.a(.3f))), f.fills)
        assertEquals(hair(1.dp, ROSE.a(.2f)), f.rims[0])
        // faded multiplies (D13): PINK .9 → .45, ROSE .78 → .39 (to one 8-bit step).
        val dots = f.rims[1] as Rim.Dots
        assertSameColour("faded dots", PINK.a(.45f), dots.color)
        assertEquals(Rim.Dots(6.dp, 2.dp, dots.color, 4.dp, W.a(.3f), glow = AQUA.a(.25f)), dots)
        val brush = f.rims[2] as Rim.Brush
        assertSameColour("faded brush", ROSE.a(.39f), brush.ink)
        assertEquals(Rim.Brush(2.dp, brush.ink, W.a(.3f), BrushStart.Tail), brush)
        assertEquals(emptyList<Drop>(), f.shades)
        assertEquals(Ornament.CornerMarks(8.dp, 1.dp, INK.a(.5f), 0.dp, setOf(Corner.TopStart), inner = PINK.a(.5f)), f.ornaments[0])
        assertEquals(Ornament.Glow(Fill.RadialDp(0f, 0f, 40.dp, 20.dp, listOf(0f to AQUA.a(.3f), 1f to AQUA.a(0f)))), f.ornaments[1])
        // An edge drawing's colours are its own: it is wrapped (drawn through one alpha layer), and fading twice multiplies.
        val faded = f.ornaments[2] as Ornament.Edge
        assertNotSame(edge, faded.drawing)
        assertEquals(Ornament.Edge(Side.Bottom, 64.dp, 2.dp, faded.drawing), faded)
        assertEquals(rich.faded(.25f).ornaments[2], f.faded(.5f).ornaments[2])
        // Glass: the recipe's stops and its lift (added to every fill stop when it does not blur) scaled, its shadows dropped,
        // the rest kept.
        val g = f.glass!!
        assertEquals(GlassRecipe.Classic.fill.alpha(.5f), g.fill)
        assertEquals(GlassRecipe.Classic.rim!!.alpha(.5f), g.rim)
        assertEquals(.08f, g.lift, 1e-6f)
        assertEquals(emptyList<Drop.Soft>(), g.shadows)
        assertEquals(GlassRecipe.Classic.copy(fill = g.fill, lift = g.lift, rim = g.rim, shadows = emptyList()), g)
        // Ink stays readable (D13); shape and runner unchanged.
        assertEquals(INK, f.ink)
        assertEquals(INK2, f.ink2)
        assertEquals(rich.corners, f.corners)
        assertSame(runner, f.runner)
    }

    @Test fun forSegmentDropsShadesAndTurnsDotsAndBrushIntoHalfLines() {
        val s = rich.forSegment()
        // Dots and Brush become a 1 dp line of their colour at alpha .5 (not × .5; drawn §2.6 step 5: ROSE .5); Line rims stay.
        assertEquals(listOf(hair(1.dp, ROSE.a(.4f)), hair(1.dp, PINK.a(.5f)), hair(1.dp, ROSE.a(.5f))), s.rims)
        assertEquals(emptyList<Drop>(), s.shades)
        assertEquals(rich.fills, s.fills)
        assertEquals(rich.ornaments, s.ornaments)
        assertEquals(INK, s.ink)
    }

    @Test fun darkPendingDashesLinesAndKeepsTheFills() {
        val p = rich.darkPending()
        assertEquals(hair(1.dp, ROSE.a(.4f), dash = 4.dp to 3.dp), p.rims[0])
        assertEquals(rich.rims.drop(1), p.rims.drop(1))
        assertEquals(emptyList<Drop>(), p.shades)
        assertEquals(rich.fills, p.fills)
        assertEquals(hair(1.dp, ROSE.a(.4f), dash = 2.dp to 2.dp), rich.darkPending(2.dp to 2.dp).rims[0])
    }

    @Test fun clearIsATransparentFrame() {
        val c = Frame.clear(INK, INK2)
        assertEquals(emptyList<Fill>(), c.fills)
        assertEquals(emptyList<Rim>(), c.rims)
        assertEquals(emptyList<Drop>(), c.shades)
        assertEquals(emptyList<Ornament>(), c.ornaments)
        assertEquals(null, c.glass)
        assertEquals(INK, c.ink)
        assertEquals(INK2, c.ink2)
    }
}
