package app.dshmobile.ui.fog

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class FogTokensTest {
    @Test fun easingsMatchTheMockup() {
        assertEquals(CubicBezierEasing(.22f, 1f, .36f, 1f).transform(0.5f), FogMotion.EaseOut.transform(0.5f), 0f)
        assertEquals(CubicBezierEasing(.34f, 1.56f, .64f, 1f).transform(0.5f), FogMotion.Spring.transform(0.5f), 0f)
        assertEquals(CubicBezierEasing(.2f, .8f, .2f, 1f).transform(0.5f), FogMotion.Soft.transform(0.5f), 0f)
    }

    @Test fun dimensionsMatchTheSpec() {
        assertEquals(22.dp, FogDimens.Panel)
        assertEquals(30.dp, FogDimens.Sheet)
        assertEquals(196.dp, FogDimens.DockHeight)
        assertEquals(92.dp, FogDimens.HeaderHeight)
    }

    @Test fun motionTimingsMatchTheMockup() {
        assertEquals(560, FogMotion.PAGE_MS)
        assertEquals(200, FogMotion.SAVER_PAGE_MS)
        assertEquals(550, FogMotion.PILL_MS)
        assertEquals(600, FogMotion.RISE_MS)
        assertEquals(70, FogMotion.STAGGER_MS)
        assertEquals(120, FogMotion.STAGGER_BASE_MS)
        assertEquals(6, FogMotion.STAGGER_MAX)
        assertEquals(950, FogMotion.COUNT_MS)
        assertEquals(1300, FogMotion.RING_MS)
        assertEquals(200, FogMotion.RING_DELAY_MS)
        assertEquals(1100, FogMotion.METER_MS)
        assertEquals(8_000L, FogMotion.IDLE_AFTER_MS)
        assertEquals(0.25f, FogMotion.IDLE_SPEED, 0f)
    }
}
