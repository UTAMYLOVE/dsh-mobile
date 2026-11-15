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
        // Half a petal per step: the rosette always comes to rest on a heavy or a mid tick.
        assertEquals(36f, stutter(1.5f), .001f)
        assertEquals(72f, stutter(3.0f), .01f)
        // The capped slam falls in no wider than the cap, then squashes and rests exactly as the plain one.
        assertEquals(1.15f, slamCapped(0f, 1.15f), .001f)
        assertEquals(1.32f, slamCapped(0f, 2f), .001f)
        assertEquals(1f, slamCapped(0f, .9f), .001f)
        assertEquals(slam(.6f), slamCapped(.6f, 1.15f), .0001f)
        assertEquals(1f, slamCapped(1f, 1.15f), .001f)
        for (i in 0..100) assertTrue(slamCapped(i / 100f, 1.15f) <= 1.15f + 1e-4f)
    }

    @Test fun longRunsAssembleTheRosetteDuringTheAccessBeat() {
        for (len in 5000..8000 step 500) {
            val m = IntroMarks.of(len)
            assertEquals(m.a + 560f * m.pace, m.build, .01f)
            // It starts while 需要訪問許可 is up, not after a long empty dial.
            assertTrue("$len: starts during ACCESS", m.build < m.b - 200f)
        }
    }

    @Test fun theVerifiedCaptionOnlyShowsWhenItCanStayUp() {
        // At 3 s and below 03's caption would be up for under 100 ms before it must go: the slam has the beat to itself.
        for (len in listOf(1500, 2200, 3000)) assertTrue("$len", !IntroMarks.of(len).verifiedCaption())
        val m8 = IntroMarks.of(8000)
        assertTrue(m8.verifiedCaption())
        assertEquals(m8.hit + 480f, m8.verifiedOut(), .01f)
        for (len in 1500..8000 step 100) {
            val m = IntroMarks.of(len)
            assertTrue("$len: gone before d", m.verifiedOut() + 120f * m.pace <= m.d - 9f * m.pace)
        }
    }

    @Test fun theSakuraSilhouetteValleysSitAtSeventyPercent() {
        val radii = (0 until 360).map { sakuraRadius(it) }
        assertEquals(.70f, radii.min(), .005f)
        assertTrue(radii.max() <= 1f)
        // Petal 0 points straight up (270° with y down) and is notched at its tip.
        assertTrue(sakuraRadius(270) < sakuraRadius(266))
    }
}
