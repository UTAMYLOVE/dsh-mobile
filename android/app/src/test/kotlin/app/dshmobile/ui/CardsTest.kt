package app.dshmobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.agency.core.agent.Card
import app.dshmobile.ui.cards.CardActions
import app.dshmobile.ui.cards.CardView
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Each card kind renders its words and its main tap reaches the API (through the actions the home screen supplies). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h828dp-xhdpi")
class CardsTest {
    @get:Rule val rule = createComposeRule()
    private val log = CopyOnWriteArrayList<String>()
    private val actions = object : CardActions {
        override fun answer(messageId: Long, cardIndex: Int, answer: String) { log += "answer:$messageId:$cardIndex:$answer" }
        override fun open(screen: String, arg: String) { log += "open:$screen:$arg" }
        override fun openUrl(url: String) { log += "url:$url" }
        override fun watch(lessonId: Long) { log += "watch:$lessonId" }
    }

    private fun card(c: Card) {
        rule.show { Column(Modifier.padding(16.dp)) { CardView(c, 7, 2, actions, DemoMedia()) } }
    }

    @Test fun scoreDotsAreTappable() {
        card(Card.Score("check_in"))
        rule.onNodeWithText("4").performClick()
        assertEquals(listOf("answer:7:2:4"), log.toList())
    }

    @Test fun mealCardShowsComputedNumbersAndMarks() {
        card(Card.MealCard(1, "午餐　記好了", 650, 40.0, 650, 2100, 40.0, 110, estimated = true, needsPhoto = true, warnings = listOf("比單餐上限多")))
        rule.onNodeWithText("午餐　記好了").assertIsDisplayed()
        rule.onNodeWithText("650 大卡").assertIsDisplayed()
        rule.onNodeWithText("蛋白質 40 克").assertIsDisplayed()
        rule.onNodeWithText("今天 650／2100").assertIsDisplayed()
        rule.onNodeWithText("估算").assertIsDisplayed()
        rule.onNodeWithText("缺照片").assertIsDisplayed()
        rule.onNodeWithText("比單餐上限多").assertIsDisplayed()
    }

    @Test fun proposalYesAndNo() {
        card(Card.Proposal(5, "早餐加一顆蛋", "多 70 大卡"))
        rule.onNodeWithText("早餐加一顆蛋").assertIsDisplayed()
        rule.onNodeWithTag("proposal_yes").performClick()
        rule.onNodeWithTag("proposal_no").performClick()
        assertEquals(listOf("answer:7:2:yes", "answer:7:2:no"), log.toList())
    }

    @Test fun answeredProposalShowsTheAnswerNotTheButtons() {
        card(Card.Proposal(5, "早餐加一顆蛋", "", Card.Answer.YES))
        rule.onNodeWithText("已答應").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTagCount("proposal_yes"))
    }

    @Test fun videosApproveDeclineAndWatch() {
        card(Card.Videos(listOf(Card.Video(31, "聲樂", "換氣的基本功", "https://example.org/a", 12))))
        rule.onNodeWithText("換氣的基本功").assertIsDisplayed()
        rule.onNodeWithText("聲樂 · 12 分鐘").assertIsDisplayed()
        rule.onNodeWithTag("video_add_31").performClick()
        rule.onNodeWithTag("video_no_31").performClick()
        rule.onNodeWithTag("video_watch_31").performClick()
        assertEquals(listOf("answer:7:2:approve:31", "answer:7:2:decline:31", "url:https://example.org/a"), log.toList())
    }

    @Test fun approvedVideoOffersWatchThroughTheLessons() {
        card(Card.Videos(listOf(Card.Video(31, "聲樂", "換氣", "https://example.org/a")), approved = listOf(31)))
        rule.onNodeWithText("已加入").assertIsDisplayed()
        rule.onNodeWithText("去看").performClick()
        assertEquals(listOf("watch:31"), log.toList())
    }

    @Test fun placesOpenTheirLink() {
        card(Card.Places(listOf(Card.Place("小樹便當", "https://example.org/p", "雞胸便當"))))
        rule.onNodeWithText("小樹便當").assertIsDisplayed()
        rule.onNodeWithTag("place_open_0").performClick()
        assertEquals(listOf("url:https://example.org/p"), log.toList())
    }

    @Test fun openCardNavigates() {
        card(Card.Open("TIMER", "CORE", "開始核心訓練"))
        rule.onNodeWithText("開始核心訓練").performClick()
        assertEquals(listOf("open:TIMER:CORE"), log.toList())
    }

    @Test fun summaryCeremonyPhotoAndFileRender() {
        card(Card.Summary("今天到現在", listOf("吃了 650 大卡", "完成 3／8")))
        rule.onNodeWithText("今天到現在").assertIsDisplayed()
        rule.onNodeWithText("· 完成 3／8").assertIsDisplayed()
    }

    @Test fun ceremonyRenders() {
        card(Card.Ceremony("stage", "第 2 階段開始了", "簽到簽退都開了。"))
        rule.onNodeWithText("第 2 階段開始了").assertIsDisplayed()
    }

    @Test fun photoAndFileRender() {
        card(Card.Photo("demo-1", "晚餐"))
        rule.onNodeWithTag("card_photo").assertIsDisplayed()
        rule.onNodeWithText("晚餐").assertIsDisplayed()
    }

    @Test fun fileChipRenders() {
        card(Card.File("菜單", 1280))
        rule.onNodeWithText("菜單").assertIsDisplayed()
        rule.onNodeWithText("1280 字").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
