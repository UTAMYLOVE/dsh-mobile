package app.dshmobile.ui.fog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

/**
 * The avatar square as plain numbers (調整頭像, 「啊我的她勒」): one function turns a crop into pixels for every spot that shows
 * the picture small, the square never leaves the picture whatever its shape, and 自動's rule for portrait art.
 */
class AvatarCropTest {
    /** Tall, wide, square, tiny and odd pictures (width to height). */
    private val pictures = listOf(
        1080 to 1920, 1152 to 2048, 2048 to 1152, 1920 to 1080, 1000 to 1000, 40 to 80, 3 to 2, 1 to 1, 1 to 500, 500 to 1,
    )

    /** Crops at the corners and edges, too big, too small and not numbers at all. */
    private val crops = listOf(
        AvatarCrop(.5f, .5f, .6f), AvatarCrop(0f, 0f, 1f), AvatarCrop(1f, 1f, 1f), AvatarCrop(0f, 1f, .05f), AvatarCrop(1f, 0f, .3f),
        AvatarCrop(-3f, 7f, 5f), AvatarCrop(.5f, .21f, .55f), AvatarCrop(.9f, .1f, 0f), AvatarCrop(Float.NaN, Float.NaN, Float.NaN),
        AvatarCrop(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY),
    )

    @Test fun theSquareIsAlwaysWholeAndInsideThePicture() {
        for ((w, h) in pictures) for (c in crops) {
            val sq = avatarSquare(w, h, c)
            val what = "$w x $h, $c → $sq"
            assertTrue(what, sq.side >= 1)
            assertTrue(what, sq.side <= min(w, h))
            assertTrue(what, sq.left >= 0 && sq.top >= 0)
            assertTrue(what, sq.left + sq.side <= w)
            assertTrue(what, sq.top + sq.side <= h)
        }
        // A picture with no size at all (not read yet) still gives a whole pixel.
        assertEquals(AvatarSquare(0, 0, 1), avatarSquare(0, 0, AvatarCrop(.5f, .5f, .5f)))
        assertEquals(AvatarSquare(0, 0, 1), avatarSquare(-4, -9, AvatarCrop(.5f, .5f, .5f)))
    }

    @Test fun theSquareIsWhereTheCropSaysWhenItFits() {
        // 1000 × 2000: the short side is 1000, so size .3 is 300 px, centred on (400, 600).
        assertEquals(AvatarSquare(250, 450, 300), avatarSquare(1000, 2000, AvatarCrop(.4f, .3f, .3f)))
        // The whole short side, centred.
        assertEquals(AvatarSquare(0, 500, 1000), avatarSquare(1000, 2000, AvatarCrop.WHOLE))
        assertEquals(AvatarSquare(500, 0, 1000), avatarSquare(2000, 1000, AvatarCrop.WHOLE))
        // Its centre back as shares of the picture.
        val sq = avatarSquare(1000, 2000, AvatarCrop(.4f, .3f, .3f))
        assertEquals(.4f, sq.centerX(1000), 1e-4f)
        assertEquals(.3f, sq.centerY(2000), 1e-4f)
    }

    @Test fun aSquarePastTheEdgeIsMovedBackInNotShrunk() {
        // Centred on the top-left corner, a quarter of the short side: it slides in to touch the corner, still 250 px.
        assertEquals(AvatarSquare(0, 0, 250), avatarSquare(1000, 2000, AvatarCrop(0f, 0f, .25f)))
        assertEquals(AvatarSquare(750, 1750, 250), avatarSquare(1000, 2000, AvatarCrop(1f, 1f, .25f)))
        // Too big is the whole short side; too small is the smallest square.
        assertEquals(1000, avatarSquare(1000, 2000, AvatarCrop(.5f, .5f, 9f)).side)
        assertEquals((AvatarCrop.MIN_SIZE * 1000).toInt(), avatarSquare(1000, 2000, AvatarCrop(.5f, .5f, 0f)).side)
    }

    @Test fun fittedKeepsTheCropInsideAndIsStable() {
        for ((w, h) in pictures) for (c in crops) {
            val f = c.fitted(w, h)
            val what = "$w x $h, $c → $f"
            assertTrue(what, f.size in AvatarCrop.MIN_SIZE..1f)
            val halfX = f.size * min(w, h) / 2f / w
            val halfY = f.size * min(w, h) / 2f / h
            assertTrue(what, f.x >= halfX - 1e-5f && f.x <= 1f - halfX + 1e-5f || f.x == .5f)
            assertTrue(what, f.y >= halfY - 1e-5f && f.y <= 1f - halfY + 1e-5f || f.y == .5f)
            // Fitting twice changes nothing: what the editor shows is what is saved.
            assertEquals(what, f, f.fitted(w, h))
        }
    }

    @Test fun theRuleForATallPictureLooksNearTheTop() {
        // The owner's kind of picture: a 9:16 full-body figure with the face about a fifth of the way down.
        val tall = AvatarGuess.heuristic(1080, 1920)
        assertEquals(AvatarCrop(.5f, .21f, .55f), tall)
        val sq = avatarSquare(1080, 1920, tall)
        assertEquals(594, sq.side) // .55 of the width
        assertEquals(.21f, sq.centerY(1920), .002f)
        // Just tall enough (1.2) is tall; a little less is not. Barely tall, the square cannot sit at .21 and touches the top.
        assertEquals(.55f, AvatarGuess.heuristic(1000, 1200).size, 1e-4f)
        assertEquals(0, avatarSquare(1000, 1200, AvatarGuess.heuristic(1000, 1200)).top)
        assertEquals(.75f, AvatarGuess.heuristic(1000, 1190).size, 1e-4f)
    }

    @Test fun theRuleForAnyOtherPictureLooksALittleAboveTheMiddle() {
        for ((w, h) in listOf(1920 to 1080, 1000 to 1000, 1000 to 1100, 4000 to 500)) {
            val g = AvatarGuess.heuristic(w, h)
            assertEquals(AvatarCrop(.5f, .40f, .75f), g)
            val sq = avatarSquare(w, h, g)
            assertEquals((.75f * min(w, h)).toInt().toFloat(), sq.side.toFloat(), 1f)
            assertEquals(.40f, sq.centerY(h), 1f / h)
        }
        for ((w, h) in pictures) {
            val sq = avatarSquare(w, h, AvatarGuess.heuristic(w, h))
            assertTrue("$w x $h", sq.left >= 0 && sq.top >= 0 && sq.left + sq.side <= w && sq.top + sq.side <= h)
        }
    }

    @Test fun aFoundFaceIsFramedFiveEyeDistancesAcrossJustUnderTheEyes() {
        // Eyes 40 px apart with their middle at (300, 400) on a 1000 × 1500 photo.
        val f = AvatarGuess.fromFace(1000, 1500, 300f, 400f, 40f)
        assertEquals(.30f, f.x, 1e-4f)
        assertEquals((400f + AvatarGuess.FACE_DROP * 40f) / 1500f, f.y, 1e-4f)
        assertEquals(200f / 1000f, f.size, 1e-4f)
        // A face at the very edge, or eyes far apart, still gives a square inside the picture.
        for (g in listOf(AvatarGuess.fromFace(1000, 1500, 0f, 0f, 40f), AvatarGuess.fromFace(1000, 1500, 990f, 1495f, 900f))) {
            val sq = avatarSquare(1000, 1500, g)
            assertTrue("$g → $sq", sq.left >= 0 && sq.top >= 0 && sq.left + sq.side <= 1000 && sq.top + sq.side <= 1500)
        }
    }
}
