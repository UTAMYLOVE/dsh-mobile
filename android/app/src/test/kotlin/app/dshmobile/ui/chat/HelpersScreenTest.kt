package app.dshmobile.ui.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.dshmobile.chat.Helper
import app.dshmobile.chat.HelperState
import app.dshmobile.chat.HelperTiming
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
class HelpersScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun lostHelperOffersRecreateAndExamplesFillTheForm() {
        val lost = Helper("h1", "s1", "晨報", "整理晨報", HelperTiming.Daily("07:50"))
        val recreated = mutableListOf<String>()
        val created = mutableListOf<Triple<String, String, HelperTiming>>()
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.SAVER, reducedMotion = true)) {
                HelpersPage(
                    listOf(lost), { HelperState.LOST }, { "今天晴" }, 0L, {}, { _, _ -> }, { recreated += it.id }, {},
                    onCreate = { n, t, timing -> created += Triple(n, t, timing) },
                )
            }
        }
        rule.onNodeWithTag(HelperTags.RECREATE + "h1").performClick()
        assertEquals(listOf("h1"), recreated)
        rule.onNodeWithTag(HelperTags.EXAMPLE + "1").performScrollTo().performClick()
        rule.onNodeWithTag(HelperTags.CREATE).performScrollTo().performClick()
        assertEquals("盤後整理", created.single().first)
        assertEquals(HelperTiming.Weekly(5, "15:10"), created.single().third)
    }
}
