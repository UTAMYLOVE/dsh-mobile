package app.dshmobile.ui.kit

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** His 代表色 as the accent (arch T10): text on it is always readable (≥ 4.5:1), and a colour that already is stays as it is. */
class MemberColourTest {
    private fun readable(a: Accent) = MemberColour.contrast(a.fill, a.onFill) >= MemberColour.MIN_CONTRAST

    @Test fun aVeryLightColourKeepsItsColourWithDarkText() {
        val a = MemberColour.accent("#FFFDF5")
        assertFalse(a.adjusted)
        assertEquals(Sakura.Text, a.onFill)
        assertEquals("#FFFDF5", MemberColour.hex(a.fill))
    }

    @Test fun aVeryDarkColourKeepsItsColourWithWhiteText() {
        val a = MemberColour.accent("#0A0A23")
        assertFalse(a.adjusted)
        assertEquals(Color.White, a.onFill)
    }

    @Test fun aMiddleColourThatReadsBadlyIsMovedInLightnessOnly() {
        for (hex in listOf("#808080", "#FF5FA2", "#E0457B", "#3D9970")) {
            val picked = MemberColour.parse(hex)!!
            assertTrue("$hex fails as it is", MemberColour.contrast(picked, Sakura.Text) < 4.5 && MemberColour.contrast(picked, Color.White) < 4.5)
            val a = MemberColour.accentOf(picked)
            assertTrue(hex, a.adjusted)
            assertTrue("$hex → ${MemberColour.hex(a.fill)}", readable(a))
            val before = MemberColour.toOklab(picked)
            val after = MemberColour.toOklab(a.fill)
            // Hue kept: the a/b direction stays (within sRGB clipping); only lightness moved, by less than half the range.
            assertTrue(hex, MemberColour.lightnessShift(picked, a.fill) < 0.5)
            if (kotlin.math.hypot(before[1], before[2]) > 0.05) {
                val dot = before[1] * after[1] + before[2] * after[2]
                assertTrue("$hex hue turned", dot > 0)
            }
        }
    }

    @Test fun everyPresetIsReadable() {
        MemberColour.PRESETS.forEach { (zh, hex) -> assertTrue(zh, readable(MemberColour.accent(hex))) }
    }

    @Test fun noColourOrABadOneIsTheSakuraPink() {
        assertEquals(Accent.DEFAULT, MemberColour.accent(null))
        assertEquals(Accent.DEFAULT, MemberColour.accent("粉紅"))
        assertNull(MemberColour.parse("#12345"))
        // The bright pink, made readable (WCAG AA) by moving its lightness only a little.
        assertTrue(readable(Accent.DEFAULT))
        assertTrue(MemberColour.lightnessShift(Sakura.Primary, Accent.DEFAULT.fill) < 0.1)
    }

    @Test fun oklabRoundTrips() {
        val c = MemberColour.parse("#8E2440")!!
        assertEquals("#8E2440", MemberColour.hex(MemberColour.fromOklab(MemberColour.toOklab(c))))
    }
}
