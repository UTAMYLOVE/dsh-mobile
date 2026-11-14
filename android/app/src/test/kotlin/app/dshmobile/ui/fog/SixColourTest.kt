package app.dshmobile.ui.fog

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The six-colour recolour keeps skin, whites and alpha, and moves the colourful parts to the palette (the owner, 2026-11-15). */
class SixColourTest {
    private fun argb(r: Int, g: Int, b: Int, a: Int = 255) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun r(p: Int) = (p shr 16) and 0xFF
    private fun g(p: Int) = (p shr 8) and 0xFF
    private fun b(p: Int) = p and 0xFF
    private fun one(p: Int, strength: Float = 1f, aqua: Float = SixColour.DEFAULT_AQUA) = intArrayOf(p).also { SixColour.recolour(it, strength, aqua) }[0]

    @Test fun strengthZeroLeavesThePictureAsItIs() {
        val pixels = intArrayOf(argb(255, 0, 0), argb(10, 20, 30), argb(200, 180, 60, 128))
        val copy = pixels.copyOf()
        SixColour.recolour(pixels, 0f)
        assertArrayEquals(copy, pixels)
    }

    @Test fun skinStaysSkin() {
        // Light and shaded skin tones of the kind in the owner's picture: the first try turned them pink (「膚色都沒了」).
        for (skin in listOf(argb(237, 193, 191), argb(243, 214, 213), argb(236, 200, 202), argb(222, 178, 160))) {
            val out = one(skin)
            assertTrue("skin moved: ${Integer.toHexString(skin)} -> ${Integer.toHexString(out)}", abs(r(out) - r(skin)) <= 3 && abs(g(out) - g(skin)) <= 3 && abs(b(out) - b(skin)) <= 3)
        }
    }

    @Test fun whiteStaysWhiteAndAlphaIsKept() {
        val out = one(argb(255, 255, 255, 77))
        assertEquals(77, out ushr 24)
        assertTrue(r(out) >= 248 && g(out) >= 248 && b(out) >= 248)
    }

    @Test fun redBecomesPink() {
        val out = one(argb(230, 20, 30), aqua = 0f)
        // #DC9FB4-like: red first, then blue above green.
        assertTrue("not pink: ${Integer.toHexString(out)}", r(out) > b(out) && b(out) > g(out))
        assertTrue("still harsh: ${Integer.toHexString(out)}", g(out) > 60)
    }

    @Test fun deepRedTurnsAquaOnlyWithAquaAccents() {
        val deep = argb(120, 10, 20)
        val pink = one(deep, aqua = 0f)
        val aqua = one(deep, aqua = 1f)
        assertTrue("aqua 0 should stay pink: ${Integer.toHexString(pink)}", r(pink) > g(pink))
        assertTrue("aqua 1 should be light aqua: ${Integer.toHexString(aqua)}", b(aqua) > r(aqua) && g(aqua) > r(aqua) && g(aqua) > 150)
    }

    @Test fun blackBecomesASoftPlumNotBlack() {
        val out = one(argb(0, 0, 0))
        assertTrue("still black: ${Integer.toHexString(out)}", r(out) >= 45)
        assertTrue("not plum: ${Integer.toHexString(out)}", r(out) > g(out) && b(out) > g(out))
    }

    @Test fun blueBecomesAquaAndGoldStaysWarm() {
        val blue = one(argb(30, 60, 220))
        assertTrue("not aqua: ${Integer.toHexString(blue)}", b(blue) > r(blue) && g(blue) > r(blue))
        val gold = one(argb(212, 175, 55))
        assertTrue("gold lost its warmth: ${Integer.toHexString(gold)}", r(gold) > b(gold) && g(gold) > b(gold))
    }
}
