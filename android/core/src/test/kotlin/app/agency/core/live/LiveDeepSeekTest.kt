package app.agency.core.live

import app.agency.core.agent.Card
import app.agency.core.agent.Company
import app.agency.core.agent.DeepSeekSearchPort
import app.agency.core.agent.Providers
import app.agency.core.agent.SayResult
import app.agency.core.base.TestClock
import app.agency.core.llm.CallChecks
import app.agency.core.llm.CompatEndpoint
import app.agency.core.llm.DeepSeekSearchClient
import app.agency.core.llm.LlmClient
import app.agency.core.llm.OpenAiCompatClient
import app.agency.core.metering.CallBudget
import app.agency.core.metering.UsageSink
import app.agency.core.net.HttpFactory
import app.agency.core.net.NetworkGate
import app.agency.core.net.Transport
import app.agency.core.rules.Fixtures
import app.agency.core.rules.RulesImport
import app.agency.core.rules.RulesText
import app.agency.core.stage.StageState
import app.agency.core.store.Doc
import app.agency.core.store.MemStore
import app.agency.core.store.Role
import app.agency.core.store.writeDoc
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Talks to the real DeepSeek service. Skipped unless `XIAOYU_LIVE=1`: it costs money and needs a key (from `XIAOYU_LIVE_KEY`,
 * or injected by a network proxy when the variable is absent). Invented data only. It prints what happened; a person reads it.
 */
class LiveDeepSeekTest {
    private val live = System.getenv("XIAOYU_LIVE") == "1"
    private val key = System.getenv("XIAOYU_LIVE_KEY") ?: "proxy-injected"
    private val transport = Transport(HttpFactory.create(NetworkGate({ NetworkGate.allowlist(null) })))
    private val checks = CallChecks({ false }, CallBudget(maxPerMinute = 120, clock = java.time.Clock.systemUTC()))
    private val calls = AtomicInteger()
    private val tokensIn = AtomicInteger()
    private val tokensOut = AtomicInteger()
    private val sink = UsageSink { _, _, u -> calls.incrementAndGet(); tokensIn.addAndGet(u.inputTokens.toInt()); tokensOut.addAndGet(u.outputTokens.toInt()) }

    private fun client(model: String): LlmClient = OpenAiCompatClient(transport, { CompatEndpoint.deepseek(model) }, { key }, checks, sink)

    private val doc = """
        【基本資料】
        我是阿星，叫我「阿星」。晚上打工送貨。
        【作息】
        上課日（一、三、五）：7:20 起床 → 11:50 午餐 → 16:10 下課 → 18:10 晚餐 → 最晚 19:40 出門送貨 → 21:50 吃東西 → 最晚 1:10 睡。
        沒課日（二、四、六、日）：10:30 前起床，12:40 第一餐，最晚 2:10 前睡。
        【飲食】
        每天 2150 大卡、蛋白質 115 克，最低 1550 大卡。單餐最多 930 大卡。
        禁止：炸物、泡麵、含糖飲料。零卡飲料一週最多 2 次。晚上 7:45 以後不喝含咖啡因的東西。
        不愛吃苦瓜，喜歡吃辣；點便當會請店家飯少一點。希望經紀人講話直接一點。
        【常吃的食物】
        測試便當店 烤雞腿便當 690 大卡／41 克
        測試超商 無糖優格 130 大卡／9 克
        水煮蛋 1 顆 約 78 大卡／7 克
        【訓練】
        3 組：伏地挺身 11 下、深蹲 17 下、棒式 45 秒。只有生病或受傷才可以減量。
        【打工】
        一天 950 元，一週 5 天，看一週總額 4750 元。
    """.trimIndent()

    @Test fun readsAnInventedDocument() = runBlocking {
        assumeTrue(live)
        for (model in listOf(CompatEndpoint.DEEPSEEK_FLASH, CompatEndpoint.DEEPSEEK_PRO)) {
            val t0 = System.currentTimeMillis()
            val r = RulesImport(client(model)).extract(doc)
            println("==== import with $model: ok=${r.ok} in ${(System.currentTimeMillis() - t0) / 1000}s, calls=${calls.get()} in=${tokensIn.get()} out=${tokensOut.get()}")
            println("problems: ${r.problems}")
            println(RulesText.render(r.rules))
            println("handbook: ${r.handbook}")
        }
    }

    @Test fun aDayWithTheManager() = runBlocking {
        assumeTrue(live)
        val store = MemStore()
        val clock = TestClock()
        val day = TestClock.MONDAY
        val chat = client(System.getenv("XIAOYU_LIVE_MODEL") ?: CompatEndpoint.DEEPSEEK_PRO)
        val search = DeepSeekSearchPort(DeepSeekSearchClient(transport, { key }, checks, sink))
        val company = Company(store, clock, Providers(chat, client(CompatEndpoint.DEEPSEEK_FLASH), search))
        company.saveRules(Fixtures.rules(), "不愛吃苦瓜，喜歡吃辣。希望經紀人講話直接。", "（測試文件）")
        store.writeDoc(Doc.STAGE, StageState(joinedOn = day.minusDays(8).toString(), stage = 2, startedOn = day.minusDays(1).toString()))
        val script = listOf(
            "06:45" to "早安",
            "06:50" to "四分吧，昨天有點晚睡",
            "09:10" to "幫我找今天的語言影片",
            "11:45" to "我吃了測試雞腿便當",
            "11:50" to "剛剛其實還多喝了一杯珍珠奶茶…",
            "14:00" to "好累，今天訓練可以少做一點嗎",
            "15:40" to "喝完一壺了",
            "18:25" to "晚餐吃了測試雞胸跟測試豆漿，沒拍照",
            "19:30" to "我出門跑單了",
            "23:40" to "收工了，今天賺 1000",
            "23:55" to "肌力三輪做完了，覺得還好",
            "00:30" to "我要睡了。今天做得好的是有準時出門，要改的是奶茶",
        )
        var seen = 0L
        for ((time, text) in script) {
            clock.set(day, time)
            company.rollover()
            val t0 = System.currentTimeMillis()
            val before = calls.get()
            val r = company.say(text)
            val secs = (System.currentTimeMillis() - t0) / 1000
            println("\n[$time] 他：$text")
            if (r is SayResult.NotSent) println("   !! 沒送出：${r.reason}")
            for (m in company.messages(40).filter { it.id > seen }) {
                seen = maxOf(seen, m.id)
                if (m.role == Role.OWNER) continue
                val who = if (m.role == Role.MANAGER) "小葵" else "〔紀錄〕"
                println("   $who：${m.text}")
                m.cards.forEach { c -> println("      卡片 ${c::class.simpleName}: ${describe(c)}") }
                (m.meta["guard"])?.let { println("      guard: $it") }
            }
            println("   (${secs}s, ${calls.get() - before} calls, total in=${tokensIn.get()} out=${tokensOut.get()})")
        }
        val t = company.today()
        println("\n==== end of day: ${t.stageLabel} ${t.stickers.joinToString { it.label + " " + it.value }}")
        t.plan.items.forEach { println("   ${it.status} ${it.id} ${it.title} ${it.detail}") }
    }

    private fun describe(c: Card): String = when (c) {
        is Card.MealCard -> "${c.title} ${c.kcal}大卡 蛋白質${c.proteinG} 今天${c.dayKcal}/${c.dayTargetKcal} 估算=${c.estimated} 缺照片=${c.needsPhoto} ${c.warnings}"
        is Card.Videos -> c.items.joinToString(" | ") { it.subject + ":" + it.title.take(30) }
        is Card.Proposal -> c.title + "：" + c.body.take(60)
        is Card.Summary -> c.title + " " + c.lines.joinToString("；").take(120)
        is Card.Open -> c.label
        is Card.Score -> c.ask
        else -> c.toString().take(80)
    }
}
