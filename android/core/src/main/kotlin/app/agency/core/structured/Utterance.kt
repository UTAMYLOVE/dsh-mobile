package app.agency.core.structured

import app.agency.core.engine.Tone
import app.agency.core.guard.Evidence
import app.agency.core.guard.GuardMode
import app.agency.core.guard.GuardResult
import app.agency.core.guard.OutputGuard
import app.agency.core.memory.MemoryBlock
import app.agency.core.memory.MemoryRequest
import app.agency.core.model.MemoryOp
import app.agency.core.model.MessageMode
import app.agency.core.model.RoleKey
import app.agency.core.persona.Roles
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * One reply in a role's voice. [ask], [options] and [recommended] exist for 討論 mode (one question per reply; options become
 * chips, the recommended one marked). [memory] holds at most two proposed memory events (applied by code, not here).
 */
data class Utterance(
    val text: String,
    val ask: String? = null,
    val options: List<String> = emptyList(),
    val recommended: Int? = null,
    val memory: List<MemoryRequest> = emptyList(),
) {
    companion object {
        const val TEXT_CHARS = 160
        const val MAX_OPTIONS = 4
        const val OPTION_CHARS = 20
        const val MAX_MEMORY = 2
        private val OPS = listOf("add", "update", "delete")

        val DEFAULTS: Map<String, JsonElement> = mapOf(
            "ask" to JsonNull, "options" to JsonArray(emptyList()), "recommended" to JsonNull, "memory" to JsonArray(emptyList()),
        )

        val SCHEMA: JsonObject = with(JsonSchema) {
            obj(
                "text" to str(),
                "ask" to nullable(str()),
                "options" to arr(str()),
                "recommended" to nullable(int()),
                "memory" to arr(
                    obj(
                        "op" to enum(OPS), "block" to enum(MemoryBlock.KEYS), "item_id" to nullable(str()),
                        "text" to nullable(str()), "quote" to str(),
                    ),
                ),
            )
        }

        /** What the model is told about the reply's shape (JSON mode needs the word json and an example). */
        val FORMAT = """
只輸出一個 json 物件，不要其他文字：
{"text":"要說的話（最多 $TEXT_CHARS 字，最多四句）","ask":null,"options":[],"recommended":null,
 "memory":[{"op":"add|update|delete","block":"${MemoryBlock.KEYS.joinToString("|")}","item_id":null,"text":"要記的事（最多八十字，不要數字）","quote":"他這一輪原話裡的一小段（二到二十字）"}]}
memory 只放他這一輪親口說的、以後用得到的事，最多兩件；沒有就給空陣列。quote 照抄他的原話。update 和 delete 的 item_id 填記得的事前面的編號（例如記三）。
""".trimIndent()

        val SPEC: StructuredSpec<Utterance> = object : StructuredSpec<Utterance> {
            override val schema = SCHEMA
            override val defaults = DEFAULTS
            override fun decode(o: JsonObject): Decoded<Utterance> = Utterance.decode(o)
        }

        fun decode(o: JsonObject): Decoded<Utterance> {
            val v = mutableListOf<Violation>()
            val text = str(o["text"])?.trim().orEmpty()
            if (text.isEmpty()) v += Violation("text", "要有要說的話")
            if (chars(text) > TEXT_CHARS) v += Violation("text", "最多 $TEXT_CHARS 個字，現在 ${chars(text)} 個")
            val ask = str(o["ask"])?.trim()?.takeIf { it.isNotEmpty() }
            val options = (o["options"] as? JsonArray).orEmpty().mapIndexedNotNull { i, e ->
                val s = str(e)?.trim()
                if (s == null || chars(s) !in 1..OPTION_CHARS) v += Violation("options[$i]", "每個選項一到 $OPTION_CHARS 個字")
                s
            }
            if (options.size > MAX_OPTIONS) v += Violation("options", "最多 $MAX_OPTIONS 個選項")
            val recommended = (o["recommended"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { p ->
                p.intOrNull ?: -1.also { v += Violation("recommended", "要是整數或 null") }
            }
            if (recommended != null && recommended !in options.indices) v += Violation("recommended", "要是 options 裡的位置（從 0 算），或 null")
            val memoryRaw = (o["memory"] as? JsonArray).orEmpty()
            if (memoryRaw.size > MAX_MEMORY) v += Violation("memory", "一次最多記 $MAX_MEMORY 件事")
            val memory = memoryRaw.mapIndexedNotNull { i, e ->
                val m = e as? JsonObject
                val op = m?.let { str(it["op"]) }?.takeIf { it in OPS }
                val block = m?.let { str(it["block"]) }?.takeIf { MemoryBlock.of(it) != null }
                if (op == null || block == null) {
                    v += Violation("memory[$i]", "op 要是 ${OPS.joinToString("、")}，block 要是 ${MemoryBlock.KEYS.joinToString("、")}")
                    return@mapIndexedNotNull null
                }
                MemoryRequest(MemoryOp.valueOf(op.uppercase()), block, str(m["item_id"]), str(m["text"])?.trim(), str(m["quote"]))
            }
            if (v.isNotEmpty()) return Decoded(null, v)
            return Decoded(Utterance(text, ask, options, recommended, memory))
        }

        /**
         * The check a reply passes before it is shown: the shape [mode] asks for, then the output guard on [text][Utterance.text],
         * [ask][Utterance.ask] and every option. Sentences the guard drops are taken out; nothing left, a hard rule, or a dropped
         * question or option sends the reasons back to the model.
         */
        fun checker(evidence: Evidence, mode: MessageMode, tone: Tone): (Utterance) -> Checked<Utterance> = { u ->
            val v = mutableListOf<Violation>()
            when (mode) {
                MessageMode.NORMAL -> if (u.ask != null || u.options.isNotEmpty()) v += Violation("ask", "現在不是討論，ask 要是 null，options 要是空的")
                MessageMode.DISCUSS -> {
                    if (u.ask == null) v += Violation("ask", "討論時要問他一個問題")
                    if (u.options.isNotEmpty() && u.recommended == null) v += Violation("recommended", "有選項時要標出推薦哪一個")
                }
            }
            val guardMode = if (mode == MessageMode.DISCUSS) GuardMode.DISCUSS else GuardMode.NORMAL
            // In 討論 the question is part of the reply: counted together, so the text itself may not ask another one.
            val whole = OutputGuard.check(u.text + (u.ask?.let { "\n$it" } ?: ""), evidence, guardMode, tone)
            val body = OutputGuard.check(u.text, evidence, GuardMode.NORMAL, tone)
            v += reasons("text", whole)
            if (body.kept.isBlank()) v += Violation("text", "每一句都不能用")
            u.ask?.let { a -> OutputGuard.check(a, evidence, GuardMode.NORMAL, tone).takeIf { it.kept != a.trim() }?.let { v += reasons("ask", it, all = true) } }
            u.options.forEachIndexed { i, o ->
                OutputGuard.check(o, evidence, GuardMode.NORMAL, tone).takeIf { it.kept != o.trim() || it.hard.isNotEmpty() }
                    ?.let { v += reasons("options[$i]", it, all = true) }
            }
            if (v.isEmpty()) Checked.Accept(u.copy(text = body.kept)) else Checked.Reject(v.distinct())
        }

        /** The fixed reply when the model gives none that passes: never empty, never colder than the tone code chose. */
        fun template(role: RoleKey, tone: Tone): Utterance = Utterance(Roles.template(role, tone))

        /** The guard's findings as retry reasons: hard rules always; dropped sentences only when [all] (or nothing is left). */
        fun reasons(field: String, r: GuardResult, all: Boolean = false): List<Violation> {
            val listed = if (all || r.kept.isBlank()) r.violations else r.hard
            return listed.map { Violation(field, it.detail) }.distinct()
        }

        private fun chars(s: String) = s.codePointCount(0, s.length)
        private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
