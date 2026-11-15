package app.dshmobile.ui.fog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroMarksTest {
    @Test fun everyLengthKeepsItsBeatsInOrderAndTheWelcomeLong() {
        for (len in 1500..8000 step 100) {
            val m = IntroMarks.of(len)
            val marks = floatArrayOf(0f, m.a, m.b, m.c, m.hit, m.d, m.e, m.total)
            for (i in 1 until marks.size) assertTrue("$len: mark $i not after ${i - 1}", marks[i] > marks[i - 1])
            assertEquals(len.toFloat(), m.total, .01f)
            assertTrue("$len: the scan ends by c", m.scanStart + m.scanMs <= m.c + .01f)
            assertTrue("$len: the sigil starts after a", m.a <= m.build)
            assertTrue("$len: the sigil is built by c", m.build + 540f * m.pace <= m.c + .01f)
            assertTrue("$len: the welcome is at least 45 %", (m.total - m.d) / m.total >= .45f)
            assertEquals(len < 2200, m.compact)
        }
    }

    @Test fun thePhasesComeInOrder() {
        val m = IntroMarks.of(3000)
        val seen = (0..3000 step 10).map { m.phase(it.toFloat()) }.distinct()
        assertEquals(IntroPhase.entries.toList(), seen)
        assertEquals(IntroStep.PERMISSION, IntroPhase.ACCESS.step)
        assertEquals(IntroStep.CHECKING, IntroPhase.CHARGE.step)
        assertEquals(IntroStep.PASSED, IntroPhase.VERIFIED.step)
        assertEquals(IntroStep.WELCOME, IntroPhase.EXIT.step)
    }

    @Test fun theMarksAtTheAnchorsAndInBetween() {
        val m3 = IntroMarks.of(3000)
        assertEquals(1200f, m3.hit, .01f)
        assertEquals(2620f, m3.e, .01f)
        assertEquals(1f, m3.pace, .0001f)
        val m4 = IntroMarks.of(4000)
        assertEquals(132f, m4.a, .01f)
        assertEquals(792f, m4.b, .01f)
        assertEquals(1448f, m4.c, .01f)
        assertEquals(1592f, m4.hit, .01f)
        assertEquals(2024f, m4.d, .01f)
        assertEquals(3608f, m4.e, .01f)
        assertEquals(.8f, IntroMarks.of(1500).pace, .0001f)
        // Out of range lengths are clamped.
        assertEquals(1500f, IntroMarks.of(200).total, .01f)
        assertEquals(8000f, IntroMarks.of(60_000).total, .01f)
    }

    @Test fun theHelperCurves() {
        assertEquals(1.32f, slam(0f), .001f)
        assertEquals(1f, slam(1f), .001f)
        assertTrue(slam(.55f) < 1f)
        assertEquals(0f, flash(1199f, 1200f, .7f), 0f)
        assertEquals(.7f, flash(1260f, 1200f, .7f), .001f)
        assertEquals(0f, flash(1580f, 1200f, .7f), 0f)
        assertEquals(0f, nudge(-1f), 0f)
        assertEquals(2f, nudge(40f), .001f)
        assertEquals(0f, nudge(140f), 0f)
        assertEquals(0f, stutter(0f), .001f)
        assertEquals(22.5f, stutter(1.5f), .001f)
        assertEquals(45f, stutter(3.0f), .01f)
    }

    @Test fun theSakuraSilhouetteValleysSitAtSeventyPercent() {
        val radii = (0 until 360).map { sakuraRadius(it) }
        assertEquals(.70f, radii.min(), .005f)
        assertTrue(radii.max() <= 1f)
        // Petal 0 points straight up (270° with y down) and is notched at its tip.
        assertTrue(sakuraRadius(270) < sakuraRadius(266))
    }
}
