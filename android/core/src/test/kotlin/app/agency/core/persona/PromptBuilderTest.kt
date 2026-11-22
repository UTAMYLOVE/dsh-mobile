package app.agency.core.persona

import app.agency.core.brain.FactsBuilder
import app.agency.core.brain.Fixtures
import app.agency.core.engine.Tone
import app.agency.core.llm.Turn
import app.agency.core.model.CompanyRules
import app.agency.core.memory.MemoryBlocks
import app.agency.core.memory.RenderedMemory
import app.agency.core.model.MemoryItem
import app.agency.core.model.Message
import app.agency.core.model.MessageMode
import app.agency.core.model.RoleKey
import app.agency.core.model.Who
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {
    private val today = LocalDate.of(2026, 11, 20)
    private val facts = FactsBuilder.build(Fixtures.input())

    private fun input(role: RoleCard = Roles.MANAGER, mode: MessageMode = MessageMode.NORMAL, tone: Tone = Tone.COLD, reply: ReplyKind = ReplyKind.UTTERANCE) =
        PromptInput(role, mode, tone, CompanyRules(), memory, facts, reply)

    private val memory = MemoryBlocks.render(listOf(MemoryItem("4f1c2d3e-0000-4000-8000-123456789abc", "likes", "不吃地瓜", 0, 0, null)), today) { today }

    @Test fun theManagerPromptHasTheFourApprovedExamplesVerbatimAndTheForbiddenList() {
        val s = PromptBuilder.system(input())
        val verbatim = listOf(
            "他「姐姐你好兇欸」→ 你「你自己說要高壓的，我只是照辦而已。而且你今天表現很好，該稱讚的我都有稱讚。現在只剩最後一件事：去睡覺。晚安，明天見。」",
            "他「還沒吃」→ 你「喔，那是我太快記了，這包先不算，吃了才算。雞胸已經在你手上的話，現在就吃，吃完拍給我，然後就去睡。」",
            "他「還沒出門你好兇」→ 你「好啦，我放軟一點。慢慢來就好，外套穿上再出門，現在外面冷。買一包雞胸，回來吃完就睡。我在這邊等你的照片。」",
            "他「愛你」→ 你「哈，收到。我會一直在這裡盯著你吃雞胸。快去買，吃完就睡，明天晚上還有三輪要做。」",
        )
        verbatim.forEach { assertTrue(it, s.contains(it)) }
        Roles.FORBIDDEN.forEach { assertTrue(it, s.contains(it)) }
        assertTrue(s.contains("不知道"))
        assertTrue(s.contains("繁體中文"))
        assertTrue(s.contains("醫療診斷"))
    }

    @Test fun theSectionsComeInTheFixedOrder() {
        val s = PromptBuilder.system(input(tone = Tone.GENTLE))
        val marks = listOf("【公司】", "【公司規章", "【你的角色：經紀人】", "【模式：一般】", "【這一次的語氣：放軟】", "【你記得的事", "【資料：")
        val at = marks.map { s.indexOf(it) }
        assertTrue(at.toString(), at.all { it >= 0 } && at == at.sorted())
        // The stable prefix (base, rules, role, mode) is the same whatever the tone, memory or facts.
        val other = PromptBuilder.system(input(tone = Tone.SWEET).copy(memory = RenderedMemory.EMPTY))
        val prefixEnd = s.indexOf("【這一次的語氣")
        assertEquals(s.substring(0, prefixEnd), other.substring(0, prefixEnd))
    }

    @Test fun rulesComeFromCompanyRules() {
        val s = PromptBuilder.rules(CompanyRules(quotaKcal = 1800, mealCapKcal = 750, goalsKg = listOf(68.0, 75.0)))
        assertTrue(s.contains("每日 1800 大卡"))
        assertTrue(s.contains("單餐不超過 750 大卡"))
        assertTrue(s.contains("75 公斤，再來 68 公斤"))
    }

    @Test fun modesAndKinds() {
        assertTrue(PromptBuilder.system(input(mode = MessageMode.DISCUSS)).contains("只問一個問題"))
        assertTrue(PromptBuilder.system(input(reply = ReplyKind.NUDGE)).contains("【模式：提醒】"))
        assertTrue(PromptBuilder.system(input()).contains("json"))
        val body = PromptBuilder.system(input(role = Roles.BODY))
        assertTrue(body.contains("【你的角色：體態管理老師】"))
        assertFalse(body.contains("姐姐你好兇欸"))
    }

    @Test fun thePromptNeverHoldsAFilePathAUrlOrAPhoto() {
        val owner = Message("now-1", 99, Who.OWNER, text = "照片在 /data/user/0/app/files/agency/media/x.bin 看 https://a.example.com")
        val history = listOf(
            Message("h1", 1, Who.OWNER, text = "我在 content://media/external/images/9 拍的"),
            Message("h2", 2, Who.COMPANY, role = RoleKey.BODY, text = "收到 www.example.tw"),
        )
        val f = FactsBuilder.build(Fixtures.input(history = history))
        val all = PromptBuilder.system(input().copy(facts = f)) + PromptBuilder.turns(f, owner).joinToString { it.toString() }
        for (never in listOf("http", "www.", "content://", "/data/", ".bin", "media-photo-77", "某某區")) assertFalse(never, all.contains(never))
    }

    @Test fun turnsStartWithHimMergeSidesAndEndWithHisMarkedMessage() {
        val history = listOf(
            Message("a", 1, Who.COMPANY, role = RoleKey.MANAGER, text = "早"),
            Message("b", 2, Who.OWNER, text = "起來了"),
            Message("c", 3, Who.COMPANY, role = RoleKey.MANAGER, text = "簽到"),
            Message("d", 4, Who.COMPANY, role = RoleKey.BODY, text = "喝水"),
        )
        val f = FactsBuilder.build(Fixtures.input(history = history))
        val turns = PromptBuilder.turns(f, Message("now", 5, Who.OWNER, text = "好"))
        assertEquals(
            listOf(Turn.User("起來了"), Turn.AssistantText("【經紀人】簽到\n【體態管理老師】喝水"), Turn.User("【他剛說】\n好")),
            turns,
        )
        assertEquals(Turn.User("【程式】午餐還沒回報。"), PromptBuilder.turns(FactsBuilder.build(Fixtures.input(history = emptyList()), false), null, "午餐還沒回報。").single())
    }

    @Test fun laterRolesAreCardsThatHaveNotArrived() {
        assertEquals(10, Roles.ALL.size)
        assertTrue(Roles.LATER.none { it.enabledIn(Roles.STAGE) })
        assertTrue(Roles.MANAGER.enabledIn(Roles.STAGE) && Roles.BODY.enabledIn(Roles.STAGE))
        assertTrue(Roles.ALL.all { it.forbidden == Roles.FORBIDDEN && it.pushToRealPeople })
    }

    @Test fun noIdReachesThePrompt() {
        val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val owner = Message("9b2e7c41-1111-4222-8333-0123456789ab", 99, Who.OWNER, text = "好")
        val s = PromptBuilder.system(input())
        assertFalse(uuid.containsMatchIn(s))
        assertTrue(s.contains("[記1] 不吃地瓜"))
        assertFalse(uuid.containsMatchIn(PromptBuilder.turns(facts, owner).joinToString { it.toString() }))
    }

    @Test fun dishNamesAreAllowedInThePrompt() {
        assertTrue(PromptBuilder.system(input()).contains("菜名可以講（例如雞腿便當、燙青菜）"))
        assertTrue(Roles.MANAGER.tones.getValue(Tone.COLD).contains("這次不撒嬌"))
        assertTrue(Roles.BODY.tones.getValue(Tone.GENTLE).contains("這次不撒嬌"))
        assertTrue(Roles.MANAGER.tones.getValue(Tone.GENTLE).contains("不要說「沒關係」"))
    }

    @Test fun aChatWindowSendsHerRepliesWithReasoningAndCodeLinesOnHisSide() {
        val window = listOf(
            app.agency.core.model.Message("a", 1, app.agency.core.model.Who.OWNER, text = "早安"),
            app.agency.core.model.Message("b", 2, app.agency.core.model.Who.COMPANY, role = app.agency.core.model.RoleKey.MANAGER, text = "早點睡。"),
            app.agency.core.model.Message("c", 3, app.agency.core.model.Who.COMPANY, role = app.agency.core.model.RoleKey.MANAGER, text = "還缺：午餐。"),
            app.agency.core.model.Message("d", 4, app.agency.core.model.Who.OWNER, text = "好"),
            app.agency.core.model.Message("e", 5, app.agency.core.model.Who.COMPANY, role = app.agency.core.model.RoleKey.BODY, text = "記得吃早餐。", legacy = true),
        )
        val facts = app.agency.core.brain.CallFacts(kotlinx.serialization.json.JsonObject(emptyMap()), window)
        val turns = PromptBuilder.turns(facts, app.agency.core.model.Message("f", 6, app.agency.core.model.Who.OWNER, text = "在嗎"), replay = PromptBuilder.Replay(mapOf("b" to "想"), setOf("e")))
        assertEquals(5, turns.size)
        assertEquals(app.agency.core.llm.Turn.User("早安"), turns[0])
        val b = turns[1] as app.agency.core.llm.Turn.Assistant
        assertEquals("早點睡。", b.text)
        assertEquals(app.agency.core.llm.Turn.User(PromptBuilder.CONTEXT + "【經紀人】還缺：午餐。\n好"), turns[2])
        assertEquals(app.agency.core.llm.Turn.AssistantText("【體態管理老師】記得吃早餐。"), turns[3])
        assertEquals(app.agency.core.llm.Turn.User("【他剛說】\n在嗎"), turns[4])
    }
}
