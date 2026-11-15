package app.dshmobile.ui.fog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The shared timing and the small pure pieces every look builds on: IntroPlan, the capped clock, the twist, the seasons. */
class IntroPlanTest {
    private val beats = arrayOf(IntroBeat.move(700f), IntroBeat.hold(1f, 200f), IntroBeat.move(400f), IntroBeat.hold(2f), IntroBeat.move(380f))

    @Test fun longerRunsOnlyHoldLonger() {
        val p3 = IntroPlan.of(3000f, *beats)
        val p8 = IntroPlan.of(8000f, *beats)
        for (p in listOf(p3, p8)) {
            assertEquals(1f, p.pace, 0f)
            // The moves keep their own length at every length...
            assertEquals(700f, p.length(0), .01f)
            assertEquals(400f, p.length(2), .01f)
            assertEquals(380f, p.length(4), .01f)
            assertEquals(p.total, p.end(4), .01f)
        }
        // ...and the spare time goes to the holds by weight (2:1).
        val spare8 = 8000f - 1480f - 200f
        assertEquals(200f + spare8 / 3f, p8.length(1), .1f)
        assertEquals(2f * spare8 / 3f, p8.length(3), .1f)
        assertTrue(p8.length(1) > p3.length(1))
    }

    @Test fun aShortRunTightensTheMovesAndKeepsTheHoldsMinimum() {
        val p = IntroPlan.of(1200f, *beats)
        assertTrue(p.pace < 1f)
        assertEquals(200f, p.length(1), .01f)
        assertEquals(0f, p.length(3), .01f)
        assertEquals(1200f, p.end(4), .01f)
        assertEquals(700f * p.pace, p.length(0), .01f)
    }

    @Test fun beatsAndProgress() {
        val p = IntroPlan.of(3000f, *beats)
        assertEquals(0, p.beat(0f))
        assertEquals(1, p.beat(800f))
        assertEquals(4, p.beat(2999f))
        assertEquals(0f, p.k(0f, 0), 0f)
        assertEquals(.5f, p.k(350f, 0), .001f)
        assertEquals(1f, p.k(5000f, 4), 0f)
        val timing = IntroTiming.of(p, still = p.end(3) - 1f)
        assertEquals(p.at(4), timing.exit, 0f)
        assertEquals(3000f, timing.total, 0f)
    }

    @Test fun theClockAdvancesAtMostAHundredMsAFrame() {
        // A cold start that froze the main thread for 600 ms used to jump beats; the clock now pauses instead.
        assertEquals(100f, IntroHostConst.MAX_STEP_MS, 0f)
    }

    @Test fun theRosetteWindsBackThenSnapsHome() {
        val m = IntroMarks.of(3000)
        assertEquals(0f, sigilTwist(m.c - 1f, m), 0f)
        assertEquals(-15f, sigilTwist(m.hit - .01f, m), .1f)
        // A damped swing past home and back, settled within 480 ms.
        val swing = (0..480 step 5).map { sigilTwist(m.hit + it, m) }
        assertTrue("overshoots past home", swing.max() > 4f)
        assertTrue("swings back", swing.drop(40).min() < -1f)
        assertEquals(0f, sigilTwist(m.hit + 480f, m), .01f)
        assertEquals(0f, sigilTwist(m.e, m), .01f)
    }

    @Test fun theOffsetFlashIsShortAndOnce() {
        assertEquals(0f, splitFlash(-1f), 0f)
        assertEquals(.6f, splitFlash(60f), .001f)
        assertTrue(splitFlash(150f) in .01f..0.59f)
        assertEquals(0f, splitFlash(250f), 0f)
        assertEquals(0f, splitFlash(2000f), 0f)
    }

    @Test fun theSeasons() {
        assertEquals(IntroSeason.SAKURA, IntroSeason.of(LocalDate.of(2027, 3, 1)))
        assertEquals(IntroSeason.SAKURA, IntroSeason.of(LocalDate.of(2027, 4, 30)))
        assertEquals(IntroSeason.NONE, IntroSeason.of(LocalDate.of(2026, 11, 15)))
        assertEquals(IntroSeason.SNOW, IntroSeason.of(LocalDate.of(2026, 12, 1)))
        assertEquals(IntroSeason.SNOW, IntroSeason.of(LocalDate.of(2027, 2, 28)))
        // Both looks of the 外觀 setting carry the season; the cosy one keeps the default particles.
        assertEquals(IntroFx(season = IntroSeason.SNOW), IntroFx.of(Look(), LocalDate.of(2027, 1, 5)))
        val kawaii = IntroFx.of(Look(style = BackdropStyle.KAWAII, petals = false, amount = 100), LocalDate.of(2027, 4, 1))
        assertEquals(IntroSeason.SAKURA, kawaii.season)
        assertEquals(false, kawaii.petals)
    }

    @Test fun particleCountsFollowTheLevelAndTheAmount() {
        assertEquals(14, introCount(EffectLevel.FULL, IntroFx(), 14, 10, 6))
        assertEquals(10, introCount(EffectLevel.BALANCED, IntroFx(), 14, 10, 6))
        assertEquals(6, introCount(EffectLevel.SAVER, IntroFx(), 14, 10, 6))
        assertEquals(6, introCount(EffectLevel.FULL, IntroFx(), 14, 10, 6, reduced = true))
        assertEquals(0, introCount(EffectLevel.FULL, IntroFx(scale = 0f), 14, 10, 6))
        assertEquals(20, introCount(EffectLevel.FULL, IntroFx(scale = 1.4f), 14, 10, 6))
    }
}
