package app.dshmobile.ui.shell

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.dshmobile.chat.ContextFill
import app.dshmobile.ui.chat.nextEffort
import app.dshmobile.ui.chat.statusSegments
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.effectsFor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port")
class StatusLineTest {
    @get:Rule val rule = createComposeRule()

    @Test fun segmentsFollowTheOwnersOrder() {
        val segs = statusSegments(null, "flash", "max", 2, ContextFill(46_000, 200_000), 45.4, 12_300, 0.123)
        assertEquals(listOf("model", "effort", "review", "context", "speed", "tokens", "cost"), segs.map { it.id })
        assertEquals(listOf("FLASH", "思考 最高", "審查 2", "上下文 23%", "45 tok/s", "12.3K tok", "$0.12"), segs.map { it.text })
        val c = StatusLineConfig(listOf("cost", "model"))
        assertEquals(listOf("cost", "model"), c.apply(segs).map { it.id })
        assertEquals(listOf("work", "model", "effort", "review"), statusSegments("排隊中：1 則等上一輪結束", "pro", null, 0, null, null, null, null).map { it.id })
    }

    @Test fun configParsesTogglesAndMoves() {
        assertEquals(StatusLineConfig.DEFAULT, StatusLineConfig.parse(null).order)
        assertEquals(emptyList<String>(), StatusLineConfig.parse("").order)
        assertEquals(listOf("cost", "model"), StatusLineConfig.parse("cost,bogus,model,cost").order)
        val c = StatusLineConfig(listOf("model", "effort", "cost"))
        assertEquals(listOf("model", "cost"), c.toggled("effort").order)
        assertEquals(listOf("model", "effort", "cost", "tokens"), c.toggled("tokens").order)
        assertEquals(listOf("effort", "model", "cost"), c.moved("effort", -1).order)
        assertEquals(c, c.moved("model", -1))
    }

    @Test fun effortCycles() {
        assertEquals(listOf("low", "high", "max", "off", null), listOf<String?>(null, "low", "high", "max", "off").map(::nextEffort))
    }

    @Test fun tappingASegmentReportsIt() {
        val taps = mutableListOf<String>()
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.SAVER, reducedMotion = true)) {
                StatusLine(listOf(StatusSeg("model", "FLASH"), StatusSeg("context", "上下文 23%", .23f)), { taps += it })
            }
        }
        rule.onNodeWithTag(StatusTags.SEG + "context").performClick()
        rule.onNodeWithTag(StatusTags.SEG + "model").performClick()
        assertEquals(listOf("context", "model"), taps)
    }
}
