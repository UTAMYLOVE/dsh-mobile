package app.dshmobile.ui.agency

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.agency.core.brain.ProposalApplier
import app.agency.core.metering.Provider
import app.agency.core.model.PlanSectionKey
import app.agency.core.model.Proposal
import app.agency.core.model.ProposalKind
import app.agency.core.model.ProposalStatus
import app.agency.core.model.RoleKey
import app.agency.core.model.Who
import app.agency.core.schedule.PlanChange
import app.agency.core.schedule.PlanOp
import app.agency.core.schedule.PlanStore
import app.dshmobile.DshMobileApp
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.AgencyTestKit
import app.dshmobile.agency.AgencyTestKit.Companion.waitForTag
import app.dshmobile.agency.AgencyTestKit.Companion.tapTag
import app.dshmobile.agency.AgencyTestKit.Companion.waitForText
import app.dshmobile.agency.StartState
import app.dshmobile.ui.settings.MemoryTags
import app.dshmobile.ui.settings.SettingsPage
import app.dshmobile.ui.settings.SettingsTags
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The conversation on screen (T12): 「沒送出」 with 重送 (the same row, no copy), a proposal card that changes the plan only on
 * his 好, the 討論 chips, the grey memory line with 撤銷, and 設定 › 她記得的事. Everything is invented.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h1400dp-mdpi")
class ChatT12ScreenTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp
    private val tmp: File = Files.createTempDirectory("chat-t12").toFile()
    private val kit = AgencyTestKit(tmp)
    private var scenario: ActivityScenario<AgencyActivity>? = null
    private val server = MockWebServer()

    /** The fake DeepSeek: offline while [online] is false; the reply's text, question, options and memory are set per test. */
    @Volatile private var online = true
    @Volatile private var text = "收到。"
    @Volatile private var ask: String? = null
    @Volatile private var options: List<String> = emptyList()
    @Volatile private var memory = JSONArray()

    private fun startServer() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!online) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                val body = request.body.readUtf8()
                val content = if (body.contains("資料整理員")) {
                    JSONObject().put("kind", "chat").toString()
                } else if (body.contains("你是審稿員")) {
                    JSONObject().put("violation", false).put("class", "none").put("reason", "").toString()
                } else {
                    JSONObject().put("text", text).put("ask", ask ?: JSONObject.NULL).put("options", JSONArray(options))
                        .put("recommended", if (options.isEmpty()) JSONObject.NULL else 0).put("memory", memory).toString()
                }
                val message = JSONObject().put("role", "assistant").put("content", content).put("reasoning_content", "想一下")
                val json = JSONObject().put("id", "fake").put("choices", JSONArray().put(JSONObject().put("index", 0).put("message", message).put("finish_reason", "stop")))
                    .put("usage", JSONObject().put("prompt_tokens", 10).put("completion_tokens", 5).put("prompt_cache_hit_tokens", 0).put("prompt_cache_miss_tokens", 10))
                return MockResponse().setHeader("Content-Type", "application/json").setBody(json.toString())
            }
        }
        server.start()
    }

    private fun launch() {
        startServer()
        AgencyTestKit.swap(app, "keyVault", AgencyTestKit.MemoryKeys(Provider.DEEPSEEK to "sk-test-0123456789abcdef"))
        AgencyApp.testOverrides = kit.overrides(server.url("/"))
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
        rule.waitForTag(AgencyTags.HOME)
    }

    @After fun tearDown() {
        scenario?.close()
        runBlocking { runCatching { app.agency.stopForTest() } }
        AgencyApp.testOverrides = null
        server.shutdown()
        tmp.deleteRecursively()
    }

    private fun ready() = app.agency.start.value as StartState.Ready

    private fun type(t: String) {
        rule.onNodeWithTag(AgencyTags.COMPOSER).performTextInput(t)
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag(AgencyTags.SEND).assertIsEnabled() }.isSuccess }
        rule.onNodeWithTag(AgencyTags.SEND).performClick()
    }

    @Test fun anUnsentLineIsMarkedAndOneTapResendsTheSameRow() {
        launch()
        online = false
        type("姐姐在嗎")
        val mine = { runBlocking { ready().store.messages.recent(20) }.single { it.who == Who.OWNER } }
        rule.waitUntil(30_000) { runCatching { mine().unsent }.getOrDefault(false) }
        val id = mine().id
        rule.waitForTag(ChatTags.UNSENT + id)
        rule.waitForTag(ChatTags.RESEND + id)
        online = true
        text = "在。去喝水。"
        rule.onNodeWithTag(ChatTags.RESEND + id).performClick()
        rule.waitForText("在。去喝水。", 30_000)
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(ChatTags.UNSENT + id).fetchSemanticsNodes().isEmpty() }
        val owners = runBlocking { ready().store.messages.recent(20) }.filter { it.who == Who.OWNER }
        assertEquals(listOf(id), owners.map { it.id })
        assertFalse(owners.single().unsent)
    }

    @Test fun aProposalCardChangesThePlanOnlyOnHisYes() {
        launch()
        val diff = ProposalApplier.JSON.encodeToString(PlanChange.serializer(), PlanChange("SCHEDULE", listOf(PlanOp("class_wake", "上課日起床", "08:30", "08:20"))))
        runBlocking {
            ready().store.proposals.insert(Proposal("p-1", System.currentTimeMillis(), RoleKey.MANAGER, ProposalKind.PLAN_CHANGE, "SCHEDULE", diff, "早一點起來吃早餐"))
        }
        type("刮好了") // any line reloads the screen
        rule.waitForTag(ProposalTags.CARD + "p-1")
        rule.waitForText("上課日起床：08:30 → 08:20")
        assertEquals("08:30", runBlocking { PlanStore(ready().store).specs() }.schedule.classWake)
        rule.tapTag(ProposalTags.YES + "p-1")
        rule.waitForText("好，改了：上課日起床 08:30 → 08:20。")
        assertEquals("08:20", runBlocking { PlanStore(ready().store).specs() }.schedule.classWake)
        assertEquals(ProposalStatus.ACCEPTED, runBlocking { ready().store.proposals.get("p-1") }!!.status)
        assertEquals(2, runBlocking { ready().store.rules.section(PlanSectionKey.SCHEDULE) }!!.version)
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(ProposalTags.CARD + "p-1").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun aDiscussionQuestionHasChipsAndATapAnswersWithIt() {
        launch()
        text = "好，先弄清楚。"
        ask = "是起床太早，還是晚上太晚睡？"
        options = listOf("起床太早", "太晚睡")
        type("這裡有問題")
        rule.waitForText("是起床太早，還是晚上太晚睡？", 30_000)
        rule.waitForTag(ChatTags.OPTION + 0)
        ask = "那要早多少？"
        options = listOf("十分鐘", "二十分鐘")
        rule.onNodeWithTag(ChatTags.OPTION + 0).performClick()
        rule.waitUntil(30_000) { runBlocking { ready().store.messages.recent(20) }.any { it.who == Who.OWNER && it.text == "起床太早" } }
        rule.waitForText("那要早多少？", 30_000)
    }

    @Test fun whatSheRemembersShowsUnderHerReplyAndInSettingsAndCanBeUndone() {
        launch()
        text = "記住了。"
        memory = JSONArray().put(JSONObject().put("op", "add").put("block", "likes").put("item_id", JSONObject.NULL).put("text", "他不吃香菜").put("quote", "我不吃香菜"))
        type("我不吃香菜")
        rule.waitForText("小葵姐記下：他不吃香菜", 30_000)
        val ev = runBlocking { ready().store.memory.events() }.single()
        rule.onNodeWithTag(ChatTags.UNDO + ev.id).performClick()
        rule.waitUntil(10_000) { runBlocking { ready().store.memory.items() }.isEmpty() }
        rule.waitForText("已撤銷")
        // 設定 › 她記得的事: the history shows it; the item is gone.
        rule.onNodeWithTag(AgencyTags.TAB + AgencyTab.SETTINGS.name).performClick()
        rule.onNodeWithTag(SettingsTags.ENTRY + SettingsPage.MEMORY.name).performScrollTo().performClick()
        rule.waitForTag(MemoryTags.EVENT + ev.id)
        assertTrue(rule.onAllNodesWithTag(MemoryTags.ITEM, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test fun inSettingsHeDeletesAnItemAndCanUndoThat() {
        launch()
        text = "記住了。"
        memory = JSONArray().put(JSONObject().put("op", "add").put("block", "likes").put("item_id", JSONObject.NULL).put("text", "他不吃香菜").put("quote", "我不吃香菜"))
        type("我不吃香菜")
        rule.waitForText("小葵姐記下：他不吃香菜", 30_000)
        val item = runBlocking { ready().store.memory.items() }.single()
        rule.onNodeWithTag(AgencyTags.TAB + AgencyTab.SETTINGS.name).performClick()
        rule.onNodeWithTag(SettingsTags.ENTRY + SettingsPage.MEMORY.name).performScrollTo().performClick()
        rule.waitForTag(MemoryTags.ITEM + item.id)
        rule.onNodeWithTag(MemoryTags.DELETE + item.id).performScrollTo().performClick()
        rule.waitUntil(10_000) { runBlocking { ready().store.memory.items() }.isEmpty() }
        rule.waitForText("你刪掉：他不吃香菜")
        val del = runBlocking { ready().store.memory.events() }.first()
        rule.onNodeWithTag(MemoryTags.UNDO + del.id).performScrollTo().performClick()
        rule.waitUntil(10_000) { runBlocking { ready().store.memory.items() }.size == 1 }
    }
}
