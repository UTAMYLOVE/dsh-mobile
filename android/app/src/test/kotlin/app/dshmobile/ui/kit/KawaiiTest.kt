package app.dshmobile.ui.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KawaiiTest {
    private val kawaii = KawaiiMix()

    @Test fun amountZeroFloatsNothing() {
        assertEquals(emptyList<KawaiiParticle>(), Kawaii.particles(kawaii.copy(amount = 0)))
    }

    @Test fun amountAndSwitchesDecideWhatFloats() {
        val full = Kawaii.particles(kawaii.copy(amount = 100))
        assertEquals(Kawaii.MAX_PARTICLES + Kawaii.MAX_CLOUDS, full.size)
        val noClouds = Kawaii.particles(kawaii.copy(clouds = false))
        assertTrue(noClouds.none { it.kind == KawaiiKind.CLOUD })
        val petalsOnly = Kawaii.particles(kawaii.copy(sparkles = false, hearts = false, clouds = false))
        assertTrue(petalsOnly.isNotEmpty() && petalsOnly.all { it.kind == KawaiiKind.PETAL })
        val cloudsOnly = Kawaii.particles(kawaii.copy(petals = false, sparkles = false, hearts = false))
        assertTrue(cloudsOnly.isNotEmpty() && cloudsOnly.all { it.kind == KawaiiKind.CLOUD })
    }

    @Test fun theSameSettingsAlwaysDrawTheSamePicture() {
        assertEquals(Kawaii.particles(kawaii), Kawaii.particles(kawaii))
    }

    @Test fun everythingStaysAroundTheScreenAtAnyTime() {
        val all = Kawaii.particles(kawaii.copy(amount = 100))
        for (t in listOf(0f, 1.5f, 37f, 1000f, 123456f)) for (p in all) {
            val at = Kawaii.place(p, t)
            assertTrue("x off screen: ${p.kind} $t ${at[0]}", at[0] in -.4f..1.4f)
            assertTrue("y off screen: ${p.kind} $t ${at[1]}", at[1] in -.1f..1.7f)
            assertTrue(at[3] in 0f..1f)
        }
    }

    @Test fun speedIsAPaceThatNeverStopsOrRuns() {
        assertTrue(Kawaii.pace(0) > 0f)
        assertTrue(Kawaii.pace(100) > Kawaii.pace(40))
        assertEquals(Kawaii.pace(100), Kawaii.pace(500))
    }
}
