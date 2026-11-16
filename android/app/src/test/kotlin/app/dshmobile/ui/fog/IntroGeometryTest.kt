package app.dshmobile.ui.fog

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.util.lerp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Where 「櫻紋陣」 puts things, as plain geometry: no frame needs drawing to know the circle stays clear of the words. */
class IntroGeometryTest {
    private val dp = 2f

    /** Phone, small phone, tablet-wide, and landscape with and without a status bar (sizes in dp, insets in dp). */
    private val screens = listOf(
        floatArrayOf(412f, 915f, 32f, 24f), floatArrayOf(412f, 915f, 0f, 0f), floatArrayOf(360f, 640f, 24f, 16f),
        floatArrayOf(600f, 960f, 24f, 24f), floatArrayOf(915f, 412f, 0f, 0f), floatArrayOf(915f, 412f, 24f, 0f),
    )

    private fun layouts() = screens.map { (w, h, t, b) -> IntroLayout(w * dp, h * dp, dp, t * dp, b * dp) }

    @Test fun theSigilStaysUprightAndClearOfTheBigWordsUntilTheMorph() {
        for (L in layouts()) for (len in 1500..8000 step 250) for (early in listOf(false, true)) {
            val m = IntroMarks.of(len)
            var ms = m.hit
            while (ms <= m.d) {
                val k = floorProgress(ms, m, L.floorLimit, early)
                val pulse = sigilScale(ms, m)
                if (L.landscape) {
                    val right = lerp(L.c.x, L.cf.x, k) + lerp(1f, L.rx / L.r, k) * pulse * L.r
                    assertTrue("${L.wDp}x${L.hDp} $len at $ms: right edge $right", right <= L.colLeft - 8f * dp + .5f)
                } else {
                    val bottom = lerp(L.c.y, L.cf.y, k) + lerp(1f, L.ry / L.r, k) * pulse * L.r
                    assertTrue("${L.wDp}x${L.hDp} $len at $ms: bottom $bottom", bottom <= L.captionTop - 8f * dp + .5f)
                }
                ms += 2f
            }
            // Upright through 核驗通過 (over a picture it starts down at the hit itself), lying down by the welcome, and never a
            // jump on the way.
            if (!early) assertEquals(0f, floorProgress(m.d - 61f * m.pace, m, L.floorLimit), 0f)
            assertEquals(0f, floorProgress(m.hit - 1f, m, L.floorLimit, early), 0f)
            assertEquals(1f, floorProgress(m.d + 700f * m.pace, m, L.floorLimit, early), .0001f)
            var last = 0f
            ms = m.hit
            while (ms <= m.d + 700f * m.pace) {
                val k = floorProgress(ms, m, L.floorLimit, early)
                assertTrue("$len at $ms jumps ${k - last}", k - last in -.0001f..(.06f))
                last = k
                ms += 1f
            }
        }
    }

    @Test fun theVerifiedCaptionIsGoneBeforeTheFloorReachesIt() {
        // No line art runs through 03 — VERIFIED or 權限已開啟: wherever the lying circle's bottom edge is below the caption's top,
        // their fade is over.
        for (L in layouts().filter { !it.landscape }) for (len in 1500..8000 step 250) for (early in listOf(false, true)) {
            val m = IntroMarks.of(len)
            var ms = m.hit
            while (ms <= m.d + 700f * m.pace) {
                val k = floorProgress(ms, m, L.floorLimit, early)
                val bottom = lerp(L.c.y, L.cf.y, k) + lerp(1f, L.ry / L.r, k) * sigilScale(ms, m) * L.r
                if (bottom > L.captionTop) assertTrue("$len at $ms", ms >= m.verifiedOut() + 120f * m.pace)
                ms += 2f
            }
        }
    }

    @Test fun theFloorCircleKeepsClearOfTheSkipLabel() {
        for (L in layouts()) {
            assertTrue("${L.wDp}x${L.hDp}: floor bottom ${L.cf.y + L.ry}", L.cf.y + L.ry <= L.skipBaseline - 28f * dp + .5f)
        }
    }

    @Test fun landscapeLeavesRoomAboveAndBelowRingA() {
        for (L in layouts().filter { it.landscape }) {
            assertTrue(L.c.y - L.r >= L.topLine + 16f * dp - .5f)
            assertTrue(L.c.y + L.r <= L.progressY - 16f * dp + .5f)
        }
    }

    @Test fun theCrestFloatsUpIntoTheUpperThird() {
        // At 0.385H with 66 dp the top quarter of the welcome was empty sky: the crest rises to 0.30H at 80 dp.
        val L = IntroLayout(412f * dp, 915f * dp, dp, 32f * dp, 24f * dp)
        assertEquals(Offset(L.w / 2f, .30f * L.h), L.crestC)
        assertEquals(80f * dp, L.crestR, .01f)
        // Never into the top HUD, whatever the screen; in landscape it stays where the sigil stood, beside the block.
        for (lay in layouts()) {
            if (lay.landscape) assertEquals(lay.c, lay.crestC)
            else assertTrue("${lay.wDp}x${lay.hDp}", lay.crestC.y - lay.crestR - IntroLayout.CREST_HALO * dp >= lay.hud1Top + 30f * dp - .5f)
        }
    }

    @Test fun theIdRowKeepsClearOfTheFloorCircle() {
        // 20 dp between the ID row's bottom and the floor ellipse's top: at 10 the row sat on the data ring's own text.
        for (L in layouts()) assertTrue("${L.wDp}x${L.hDp}", L.welcomeBottom <= L.cf.y - L.ry - 20f * dp + .01f)
    }

    @Test fun theHeavyTicksSitOnThePetals() {
        // Five heavy ticks on the petals (every 72°, the first straight up) and five mid ones between them: not a 12-hour dial.
        val heavy = (0 until SigilKit.TICKS).filter { SigilKit.tickFrom(it) == .78f }.map { it * 360f / SigilKit.TICKS }
        assertEquals(listOf(0f, 72f, 144f, 216f, 288f), heavy)
        val mid = (0 until SigilKit.TICKS).filter { SigilKit.tickFrom(it) == .80f }.map { it * 360f / SigilKit.TICKS }
        assertEquals(listOf(36f, 108f, 180f, 252f, 324f), mid)
    }

    @Test fun theWindowFramesTheAvatarSquareOnAnyScreen() {
        // The avatar square (here round a face at (30 %, 35 %), a quarter of the short side across) fills the window exactly, its
        // centre on the sigil's centre, phone or wide.
        val crop = AvatarCrop(.30f, .35f, .25f)
        for (L in listOf(IntroLayout(412f * dp, 915f * dp, dp, 0f, 0f), IntroLayout(600f * dp, 960f * dp, dp, 0f, 0f))) {
            for ((iw, ih) in listOf(1000f to 1600f, 900f to 1600f, 1080f to 1920f)) {
                val out = Placement()
                windowCrop(iw, ih, L, crop, out)
                val sq = avatarSquare(iw.toInt(), ih.toInt(), crop)
                val fx = out.ox + (sq.left + sq.side / 2f) * out.k
                val fy = out.oy + (sq.top + sq.side / 2f) * out.k
                assertTrue("${L.wDp}: $iw x $ih lands ${hypot(fx - L.c.x, fy - L.c.y) / dp} dp off", hypot(fx - L.c.x, fy - L.c.y) <= .5f)
                assertEquals("the square is the window", 2f * .30f * L.r, sq.side * out.k, .01f)
            }
        }
        // Any picture covers the whole window box (no edge inside the circle), whatever its shape and wherever the square is.
        val L = IntroLayout(412f * dp, 915f * dp, dp, 0f, 0f)
        val half = .30f * L.r
        for ((iw, ih) in listOf(1600f to 1000f, 1200f to 1200f, 600f to 2400f, 40f to 80f)) {
            for (c in listOf(crop, AvatarCrop(0f, 0f, 1f), AvatarCrop(1f, 1f, .05f), AvatarGuess.heuristic(iw.toInt(), ih.toInt()))) {
                val out = Placement()
                windowCrop(iw, ih, L, c, out)
                assertTrue(out.ox <= L.c.x - half + .01f && out.ox + iw * out.k >= L.c.x + half - .01f)
                assertTrue(out.oy <= L.c.y - half + .01f && out.oy + ih * out.k >= L.c.y + half - .01f)
            }
        }
    }

    @Test fun theOpeningCircleNeverShowsAPictureEdge() {
        for (L in layouts()) for ((iw, ih) in listOf(1000f to 1600f, 1600f to 1000f, 1200f to 1200f)) {
            val from = Placement()
            val to = Placement()
            val crop = AvatarGuess.heuristic(iw.toInt(), ih.toInt())
            val sq = avatarSquare(iw.toInt(), ih.toInt(), crop)
            windowCrop(iw, ih, L, crop, from)
            cover(iw, ih, L.w, L.h, 1.06f, .5f, .5f, .5f * L.w, .5f * L.h, to)
            val rMax = max(max(hypot(L.c.x, L.c.y), hypot(L.w - L.c.x, L.c.y)), max(hypot(L.c.x, L.h - L.c.y), hypot(L.w - L.c.x, L.h - L.c.y)))
            val out = Placement()
            for (i in 0..50) {
                val ki = i / 50f
                val r = lerp(.30f * L.r, rMax, ki)
                irisPlacement(iw, ih, L, sq.centerX(iw.toInt()), sq.centerY(ih.toInt()), from, to, ki, r, out)
                val left = max(0f, L.c.x - r); val top = max(0f, L.c.y - r)
                val right = minOf(L.w, L.c.x + r); val bottom = minOf(L.h, L.c.y + r)
                assertTrue("$iw x $ih at $ki: left", out.ox <= left + .01f)
                assertTrue("$iw x $ih at $ki: top", out.oy <= top + .01f)
                assertTrue("$iw x $ih at $ki: right", out.ox + iw * out.k >= right - .01f)
                assertTrue("$iw x $ih at $ki: bottom", out.oy + ih * out.k >= bottom - .01f)
            }
            // Fully open, it is exactly the welcome's crop, so nothing jumps when the iris-in ends.
            irisPlacement(iw, ih, L, sq.centerX(iw.toInt()), sq.centerY(ih.toInt()), from, to, 1f, rMax, out)
            assertEquals(to.k, out.k, to.k * 1e-4f)
            assertTrue(abs(to.ox - out.ox) < .5f && abs(to.oy - out.oy) < .5f)
        }
    }

    @Test fun theSakuraMonIsFivePetalsWithNotches() {
        // Valleys at .70, tips near .99 with a notch at .88: what the crest's mon and the exit iris are cut from.
        assertEquals(.70f, sakuraRadius(270 + 36), .001f)
        assertEquals(.88f, sakuraRadius(270), .001f)
        assertTrue(sakuraRadius(270 + 6) > .97f)
    }
}
