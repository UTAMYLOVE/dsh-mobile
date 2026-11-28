package app.agency.core.sim

import app.agency.core.agent.Hit
import app.agency.core.agent.SearchPort
import app.agency.core.llm.ChatRequest
import app.agency.core.llm.ChatResult
import app.agency.core.llm.LlmClient
import app.agency.core.llm.Purpose
import app.agency.core.llm.ToolCall
import app.agency.core.llm.Turn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A scripted "competent model" for the simulation. It reads what a real model would read (his sentence, the context block, the
 * tool results) and answers the way a good manager would: the right tool calls for what he said, then one or two short
 * sentences quoting numbers from the tool results only. Everything it cannot handle, every guard rewrite request and every
 * tool error is recorded in [problems], so the simulation fails on them.
 */
class SimModel : LlmClient {
    val problems = ArrayList<String>()
    /** Tool names called during the current owner turn (reset by the driver). */
    val called = ArrayList<String>()
    /** The score she passes to check_in when he says 早安 (null = let him tap the dots). */
    var checkInScore: Int? = 4
    /** Tool errors the driver expects (a refused rest day…) are not problems. */
    var expectError = false
    var lastReply = ""
    private var ids = 0

    private fun ok(text: String?, calls: List<ToolCall> = emptyList()): ChatResult =
        ChatResult.Ok(text, calls, Turn.Assistant.synthetic(text, calls), null)

    override suspend fun chat(request: ChatRequest): ChatResult = when (request.purpose) {
        Purpose.QUIZ -> ok(quiz(request))
        Purpose.JUDGE -> ok("""{"ok":true,"reason":""}""")
        Purpose.SUMMARY -> ok("之前他照計畫在做，沒有特別的事。")
        Purpose.INTAKE -> ok("{}")
        else -> turn(request)
    }

    // ---------------------------------------------------------------- quizzes

    private fun quiz(r: ChatRequest): String {
        val prompt = (r.turns.last() as Turn.User).text
        val count = Regex("""出 (\d+) 題""").find(prompt)?.groupValues?.get(1)?.toInt() ?: 3
        val material = prompt.substringAfterLast("教材：\n")
        val sentences = material.split('。').map { it.trim() }.filter { it.length >= 8 }
        val qs = buildJsonArray {
            for (i in 0 until count) {
                val quote = sentences[i % sentences.size]
                add(buildJsonObject {
                    put("q", "第 ${i + 1} 題：教材裡怎麼說？")
                    put("options", buildJsonArray { listOf("甲", "乙", "丙", "丁").forEach { add(JsonPrimitive("選項$it$i")) } })
                    put("answer", 0)
                    put("quote", quote)
                })
            }
        }
        return buildJsonObject { put("questions", qs) }.toString()
    }

    // ---------------------------------------------------------------- the conversation

    private fun turn(r: ChatRequest): ChatResult {
        val names = r.tools.map { it.name }.toSet()
        if (names == setOf("remember", "note_today")) return ok("沒有")
        val last = r.turns.last()
        if (last is Turn.ToolResult) return ok(compose(r))
        last as Turn.User
        val text = last.text
        if ("〔檢查沒過" in text) {
            problems += "guard asked for a rewrite: " + text.substringAfter("〕").trim()
            return ok("好，照計畫來。")
        }
        if ("〔前面的回覆都沒通過檢查" in text) { problems += "neutral line requested"; return ok("好。") }
        if ("〔這一輪工具已經用太多次" in text) { problems += "too many tool rounds"; return ok("好。") }
        // The last user turn is this moment's context followed by his message.
        val owner = text.substringAfterLast("\n\n").removePrefix("〔他現在說的話〕\n").trim()
        val context = text
        val calls = plan(owner, context, last.images.isNotEmpty())
        if (calls == null) {
            problems += "the model does not know what to do with 「$owner」"
            return ok("嗯，我記下了。")
        }
        if (calls.isEmpty()) return ok(textOnly(owner))
        called += calls.map { it.first }
        return ok(null, calls.map { (n, a) -> ToolCall("sim_${++ids}", n, a.toString()) })
    }

    /** True when [owner] is something this model handles (a plan item's Say tap, a chip). */
    fun handles(owner: String): Boolean = plan(owner, "", false) != null

    private fun textOnly(owner: String): String = when (owner) {
        "我吃完了" -> "吃了什麼？拍張照給我，我幫你記。"
        else -> "好。"
    }

    private fun args(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)

    private val FOODS = mapOf("測試雞腿便當" to (700 to 40.0), "測試豆漿" to (200 to 14.0), "測試雞胸" to (120 to 24.0), "炸雞排" to (600 to 30.0))

    /** The tool calls for what he said; empty = answer in words only; null = not understood. */
    private fun plan(owner: String, context: String, photo: Boolean): List<Pair<String, JsonObject>>? {
        val o = owner.trim()
        Regex("""^(第一餐|晚餐|宵夜|點心)吃了(.+)$""").find(o)?.let { m ->
            val slot = when (m.groupValues[1]) { "第一餐" -> "first"; "晚餐" -> "dinner"; "宵夜" -> "night"; else -> "extra" }
            val items = m.groupValues[2].split('、').map { it.trim() }
            return listOf("log_meal" to args {
                put("slot", slot)
                put("items", buildJsonArray {
                    items.forEach { n ->
                        val (k, p) = FOODS[n] ?: (300 to 10.0)
                        add(buildJsonObject { put("name", n); put("kcal", k); put("protein_g", p) })
                    }
                })
                put("source", "estimate")
                put("attach_photo", photo)
            })
        }
        Regex("""^幫我找今天的(.+)影片$""").find(o)?.let { m ->
            return listOf("find_videos" to args { put("subject", m.groupValues[1]); put("query", "") })
        }
        Regex("""^我完成了「(.+)」$""").find(o)?.let { m ->
            val name = m.groupValues[1]
            val id = context.lines().firstOrNull { it.contains(name) && it.contains("‹habit:") }
                ?.let { Regex("""‹habit:(\d+)›""").find(it)?.groupValues?.get(1) } ?: return if (context.isEmpty()) emptyList() else null
            return listOf("habit_done" to args { put("habit_id", id.toLong()) })
        }
        Regex("""^收工了(?:，今天賺了 (\d+) 元)?$""").find(o)?.let { m ->
            val income = m.groupValues[1].toIntOrNull()
            return listOf("ride_back" to args { if (income != null) put("income", income) else put("income", null as Int?) })
        }
        Regex("""^我體重 ([0-9.]+)$""").find(o)?.let { m ->
            return listOf("log_body" to args { put("kg", m.groupValues[1].toDouble()); put("waist_cm", null as Double?) })
        }
        Regex("""^記帳 (.+) (\d+)$""").find(o)?.let { m ->
            return listOf("log_money" to args { put("amount", m.groupValues[2].toInt()); put("bucket", "living"); put("note", m.groupValues[1]) })
        }
        return when (o) {
            "早安" -> listOf("check_in" to args { put("score", checkInScore) })
            "喝完一壺了" -> listOf("log_water" to args { put("bottles", 1); put("ml", null as Int?) })
            "我出門跑單了" -> listOf("ride_out" to args { })
            "我要睡了" -> listOf("check_out" to args { put("good", "該做的都做了"); put("improve", "早一點睡"); put("mood", null as Int?) })
            "今天上什麼課" -> listOf("lessons_today" to args { })
            "今天休息" -> listOf("set_status" to args { put("type", "REST_DAY"); put("note", "他說今天休息") })
            "我感冒了" -> listOf("set_status" to args { put("type", "SICK"); put("note", "感冒") })
            "我好了" -> listOf("set_status" to args { put("type", "RECOVERED"); put("note", "感冒好了") })
            "幫我想一個每天的小習慣" -> listOf("propose" to args {
                put("title", "每天念五個單字"); put("body", "上完語言課順手做"); put("kind", "habit"); put("value", null as Int?)
            })
            "提醒我明天交作業" -> listOf("memo_add" to args { put("text", "交作業"); put("due_at", tomorrow(context) ?: "09:00") })
            "訓練做完了" -> training(context, timer = false)
            "開始訓練" -> training(context, timer = true).take(1)
            "我吃完了" -> emptyList()
            "這週的真人任務是什麼？" -> listOf("real_task" to args { put("text", ""); put("done", false) })
            "這週的真人任務做完了" -> listOf("real_task" to args { put("text", ""); put("done", true) })
            "我擦好防曬了" -> listOf("log_task" to args {
                put("task", "SUNSCREEN"); put("rounds", null as Int?); put("minutes", null as Int?); put("feel", null as String?); put("note", "")
            })
            "我要做這週主題的小練習" -> listOf("open_timer" to args { put("task", "THEME_PRACTICE") })
            else -> null
        }
    }

    private fun tomorrow(context: String): String? =
        Regex("""現在：(\d{4}-\d{2}-\d{2})""").find(context)?.groupValues?.get(1)?.let {
            java.time.LocalDate.parse(it).plusDays(1).toString() + " 09:00"
        }

    private fun training(context: String, timer: Boolean): List<Pair<String, JsonObject>> {
        if (context.isEmpty()) return listOf("log_task" to args { })
        val out = ArrayList<Pair<String, JsonObject>>()
        for (line in context.lines()) {
            val id = Regex("""‹task:(CORE|STRENGTH|STRETCH|CARDIO)›""").find(line)?.groupValues?.get(1) ?: continue
            if ("〔還沒到／待做〕" !in line && "〔晚了，還可以做〕" !in line) continue
            if ("收工後" in line) continue
            val rounds = if (id == "STRENGTH") Regex("""(\d+) 輪""").find(line)?.groupValues?.get(1)?.toInt() else null
            out += if (timer) "open_timer" to args { put("task", id) } else "log_task" to args {
                put("task", id); put("rounds", rounds); put("minutes", null as Int?); put("feel", "ok"); put("note", "")
            }
        }
        return out
    }

    // ---------------------------------------------------------------- her answer

    private fun compose(r: ChatRequest): String {
        val results = r.turns.takeLastWhile { it is Turn.ToolResult }.map { it as Turn.ToolResult }
        val context = r.turns.filterIsInstance<Turn.User>().lastOrNull { "〔現在的情況" in it.text }?.text.orEmpty()
        val parts = ArrayList<String>()
        var today: JsonObject? = null
        for (res in results) {
            val o = runCatching { Json.parseToJsonElement(res.content).jsonObject }.getOrNull() ?: continue
            if (res.isError) {
                if (!expectError) problems += "tool error: ${res.content}"
                parts += "這次沒有記成功，" + (o["error"]?.jsonPrimitive?.content?.substringBefore("。") ?: "") + "。"
                continue
            }
            o.s("note")?.let { parts += it.trimEnd('。') + "。" }
            (o["videos"] as? JsonArray)?.let { vs ->
                val titles = vs.mapNotNull { (it as? JsonObject)?.s("title") }
                if (titles.isNotEmpty()) parts += "有這幾支：" + titles.joinToString("、") { "《$it》" } + "。"
            }
            (o["changed_items"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { c ->
                parts += "程式調整了：" + c.joinToString("；") { it.jsonPrimitive.content } + "。"
            }
            (o["week_income"] as? JsonObject)?.let { w ->
                w.s("target")?.let { parts += "本週 ${w.s("income")} 元，目標 $it 元，還差 ${w.s("left")} 元。" }
            }
            (o["today"] as? JsonObject)?.let { today = it }
            if (o.s("score") != null && o.s("checked_in_at") != null) {
                // Numbers from the context block, not the tool: the guard must accept these too.
                Regex("""今天的睡覺（簽退）時間：(\d{1,2}:\d{2})""").find(context)?.let { parts += "今天 ${it.groupValues[1]} 前簽退。" }
                Regex("""肌力 (\d+) 輪，每輪伏地挺身 (\d+) 下""").find(context)?.let {
                    parts += "今天肌力 ${it.groupValues[1]} 輪，每輪伏地挺身 ${it.groupValues[2]} 下。"
                }
            }
            if (o["slots"] != null) {
                Regex("""這一輪到現在完成 (\d+)／(\d+) 項（(\d+)%，過關要 (\d+)%）""").find(context)?.let {
                    parts += "這一輪做到 ${it.groupValues[3]}%，要 ${it.groupValues[4]}% 才過關。"
                }
                Regex("""加入第 (\d+) 天""").find(context)?.let { parts += "你來公司第 ${it.groupValues[1]} 天了。" }
            }
        }
        today?.let { t ->
            if (t.s("kcal") != null) parts += "今天 ${t.s("kcal")} 大卡，目標 ${t.s("kcal_target")} 大卡，還剩 ${t.s("kcal_left")?.removePrefix("-")} 大卡。"
            if (t.s("water_ml") != null) parts += "水 ${t.s("water_ml")} 毫升，目標 ${t.s("water_target_ml")} 毫升。"
            (t["next"] as? JsonObject)?.let { n -> parts += "下一件事：${n.s("title")}" + (n.s("at")?.let { " $it" } ?: "") + "。" }
        }
        lastReply = parts.joinToString("").ifBlank { "好。" }
        return lastReply
    }

    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
}

/** Real-search stand-in: every call returns four new videos with enough material for a quiz. */
class SimSearch : SearchPort {
    private var n = 0
    var calls = 0

    override suspend fun videos(query: String): List<Hit> {
        calls++
        val subject = query.substringBefore(' ')
        return (1..4).map {
            n++
            Hit(
                title = "${subject}入門第${n}課",
                url = "https://video.example.test/v$n",
                summary = "第${n}課的摘要",
                content = "第${n}課重點一：開口之前先深呼吸再慢慢說。第${n}課重點二：每天固定練習十分鐘就會進步。" +
                    "第${n}課重點三：錄下自己的聲音再回放檢查一次。第${n}課重點四：遇到不懂的地方要寫進筆記。",
            )
        }
    }

    override suspend fun places(query: String): List<Hit> =
        listOf(Hit("測試健康餐盒店", "https://place.example.test/1", "雞胸便當"), Hit("測試輕食坊", "https://place.example.test/2", "沙拉"))
}
