package app.dshmobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.agency.core.agent.Attachment
import app.agency.core.agent.CompanyApi
import app.agency.core.agent.SayResult
import app.dshmobile.ui.home.HomeViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h828dp-xhdpi")
class HomeScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun home(company: DemoCompany = DemoCompany(), graph: UiGraph = demoGraph(company, MemoryPanelPrefs())) {
        rule.show { UiRoot(graph) }
        rule.until { rule.onAllNodesWithTagCount("conversation") > 0 }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test fun homeRendersTheDemo() {
        home()
        rule.onNodeWithText("小葵姐").assertIsDisplayed()
        rule.onNodeWithText("第 2 階段 · 第 3 天").assertIsDisplayed()
        // the sticker and the meal card in the conversation both show it
        rule.onAllNodesWithText("650 大卡")[0].assertExists()
        rule.onNodeWithText("半壺").assertIsDisplayed()
        rule.onNodeWithTag("next_pill").assertIsDisplayed()
        rule.onNodeWithText("晚餐，拍張照").assertIsDisplayed()
        rule.onNodeWithText("午餐吃了烤雞腿便當").assertIsDisplayed()
        rule.onNodeWithTag("chip_吃完了").assertIsDisplayed()
    }

    @Test fun stickersFoldWithOneTapAndUnfoldWithOne() {
        home()
        rule.onNodeWithTag("sticker_1").assertIsDisplayed()
        rule.onNodeWithTag("toggle_stickers").performClick()
        assertEquals(0, rule.onAllNodesWithTagCount("sticker_1"))
        rule.onNodeWithTag("toggle_stickers").performClick()
        rule.onNodeWithTag("sticker_1").assertIsDisplayed()
    }

    @Test fun handbookIsFoldedByDefaultAndOpensAndClosesWithOneTap() {
        home()
        assertEquals(0, rule.onAllNodesWithTagCount("handbook"))
        rule.onNodeWithTag("toggle_handbook").performClick()
        rule.onNodeWithTag("handbook").assertIsDisplayed()
        rule.onNodeWithText("今天的手帳").assertIsDisplayed()
        rule.onNodeWithText("簽到").assertIsDisplayed()
        rule.onNodeWithText("今天改了什麼").assertExists()
        rule.onNodeWithTag("toggle_handbook").performClick()
        assertEquals(0, rule.onAllNodesWithTagCount("handbook"))
    }

    @Test fun chipsFoldWithOneTapAndUnfoldWithOne() {
        home()
        rule.onNodeWithTag("chip_吃完了").assertIsDisplayed()
        rule.onNodeWithTag("toggle_chips").performClick()
        assertEquals(0, rule.onAllNodesWithTagCount("chip_吃完了"))
        rule.onNodeWithTag("toggle_chips").performClick()
        rule.onNodeWithTag("chip_吃完了").assertIsDisplayed()
    }

    @Test fun panelStateIsRemembered() {
        val prefs = MemoryPanelPrefs()
        val a = HomeViewModel(DemoCompany(), kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined), prefs)
        assertTrue(a.stickersOpen.value); assertFalse(a.handbookOpen.value); assertTrue(a.chipsOpen.value)
        a.toggleStickers(); a.toggleHandbook(); a.toggleChips()
        val b = HomeViewModel(DemoCompany(), kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined), prefs)
        assertFalse(b.stickersOpen.value); assertTrue(b.handbookOpen.value); assertFalse(b.chipsOpen.value)
    }

    @Test fun tappingAChipSendsIt() {
        val company = DemoCompany()
        home(company)
        rule.onNodeWithTag("chip_喝完一壺").performClick()
        rule.until { company.calls.contains("say:喝完一壺:0") }
        rule.until { rule.onAllNodesWithTagCount("thinking") == 0 }
        rule.onNodeWithText("收到，喝完一壺。").assertExists()
    }

    @Test fun theNextPillDoesTheItemsTap() {
        val company = DemoCompany()
        home(company)
        // the next item opens the camera; the demo camera returns a photo that is sent with the meal's name
        rule.onNodeWithTag("next_pill").performClick()
        rule.until { company.calls.any { it.startsWith("say:這是我的晚餐，請幫我記下來:1") } }
    }

    @Test fun handbookRowsRouteTheirTaps() {
        val company = DemoCompany()
        home(company)
        rule.onNodeWithTag("toggle_handbook").performClick()
        rule.onNodeWithTag("item_water").performClick()
        rule.until { company.calls.any { it.startsWith("direct:log_water") } }
        rule.onNodeWithTag("item_task:CARDIO").performClick()
        rule.until { company.calls.contains("say:我做完體能了:0") }
    }

    @Test fun anOpenItemNavigatesToItsPage() {
        home()
        rule.onNodeWithTag("toggle_handbook").performClick()
        rule.onNodeWithTag("item_lesson:1").performClick()
        rule.until { rule.onAllNodesWithTagCount("overlay") > 0 }
        rule.onNodeWithText("理論課").assertExists()
    }

    @Test fun aFailedMessageShowsResendAndResends() {
        val company = DemoCompany(failedMessage = true)
        home(company)
        rule.onNodeWithText("沒送出 · 重送").assertExists()
        rule.onNodeWithText("沒送出 · 重送").performClick()
        rule.until { company.calls.any { it.startsWith("resend:") } }
        rule.until { rule.onAllNodes(androidx.compose.ui.test.hasText("沒送出 · 重送")).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun sendingFailureLeavesAResendLine() {
        val company = DemoCompany(failNext = true)
        home(company)
        rule.onNodeWithTag("input").performTextInput("我吃完了晚餐")
        rule.onNodeWithTag("send").performClick()
        rule.until { rule.onAllNodes(androidx.compose.ui.test.hasText("沒送出 · 重送")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun sendingShowsHisBubbleAtOnceAndThinking() {
        val gate = CompletableDeferred<Unit>()
        val demo = DemoCompany()
        val slow = object : CompanyApi by demo {
            override suspend fun say(text: String, attachments: List<Attachment>): SayResult {
                gate.await()
                return demo.say(text, attachments)
            }
        }
        home(graph = demoGraph(demo, MemoryPanelPrefs()).let { g ->
            UiGraph(slow, g.media, g.lessons, g.timer, g.memo, g.body, g.money, g.trainee, g.singing, g.eval, g.onboarding, g.settings, g.backup, g.oldData, g.panels)
        })
        rule.onNodeWithTag("input").performTextInput("今天有點累")
        rule.onNodeWithTag("send").performClick()
        rule.until { rule.onAllNodes(androidx.compose.ui.test.hasText("今天有點累")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("小葵姐正在想…").assertExists()
        gate.complete(Unit)
        rule.until { rule.onAllNodes(androidx.compose.ui.test.hasText("小葵姐正在想…")).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("收到，今天有點累。").assertExists()
    }

    @Test fun attachmentsAppearAboveTheInputAndCanBeRemoved() {
        home()
        rule.onNodeWithTag("plus").performClick()
        rule.onNodeWithTag("menu_gallery").performClick()
        rule.until { rule.onAllNodesWithTagCount("attachment_1") > 0 }
        rule.onNodeWithTag("attachment_0").assertIsDisplayed()
        rule.onAllNodes(androidx.compose.ui.test.hasTestTag("remove_attachment"))[0].performClick()
        rule.until { rule.onAllNodesWithTagCount("attachment_1") == 0 }
        assertEquals(1, rule.onAllNodesWithTagCount("attachment_0"))
    }

    @Test fun theMicIsShownWhenAvailableAndSendReplacesItWhenTyping() {
        home()
        rule.onNodeWithTag("mic").assertIsDisplayed()
        rule.onNodeWithTag("input").performTextInput("好")
        rule.onNodeWithTag("send").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTagCount("mic"))
    }
}
