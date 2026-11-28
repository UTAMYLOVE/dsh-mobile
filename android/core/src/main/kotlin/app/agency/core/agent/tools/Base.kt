package app.agency.core.agent.tools

import app.agency.core.agent.Moment
import app.agency.core.agent.Tool
import app.agency.core.agent.ToolCtx
import app.agency.core.agent.ToolOut
import app.agency.core.base.Days
import app.agency.core.day.Violations
import app.agency.core.llm.ToolSpec
import app.agency.core.plan.ItemStatus
import app.agency.core.plan.PlanItem
import app.agency.core.rules.RulesCheck
import app.agency.core.stage.Feature
import app.agency.core.stage.Stages
import app.agency.core.store.Entry
import app.agency.core.store.Kind
import app.agency.core.store.Violation
import app.agency.core.store.add
import app.agency.core.day.ViolationCode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A tool that can also run from a tap: [noteLine] is the small NOTE line written for it (「✓ 已記：喝水一壺」). */
interface Noted {
    fun noteLine(out: ToolOut): String
}

/**
 * The common frame of every tool: a strict schema, argument errors turned into [ToolOut.error] (never an exception), and
 * helpers for "this feature is closed" and for today's fresh numbers.
 */
internal abstract class BaseTool(
    name: String,
    description: String,
    parameters: JsonObject,
    override val feature: Feature?,
    override val direct: Boolean = false,
) : Tool, Noted {
    override val spec = ToolSpec(name, description, parameters)

    final override suspend fun run(args: JsonObject, ctx: ToolCtx): ToolOut = try {
        exec(Args(args), ctx)
    } catch (e: BadArg) {
        ToolOut.error(e.message ?: "參數不對。")
    }

    protected abstract suspend fun exec(a: Args, ctx: ToolCtx): ToolOut

    /** The default note: what the facts call `note`, or the tool's own name. */
    override fun noteLine(out: ToolOut): String =
        if (!out.ok) "沒記到：" + F.forHim(out.facts["error"]?.jsonPrimitive?.content ?: "")
        else "✓ " + (out.facts["note"]?.jsonPrimitive?.content ?: "已記")

    /** Open at his stage (a tool without a feature is always open). */
    fun isOpen(ctx: ToolCtx): Boolean = feature?.openAt(ctx.stage.stage) ?: true
}

/** Small shared pieces: time words, today's numbers, the closed-feature wording, violation lines. */
internal object F {
    /**
     * An error written for 小葵 made fit for his screen: the argument names in （） go, and a message that still has Latin
     * letters (an enum list) becomes a plain "try again".
     */
    fun forHim(message: String): String {
        val s = message.replace(Regex("""（[A-Za-z_ ]+）"""), "").trim()
        return if (s.any { it in 'A'..'Z' || it in 'a'..'z' }) "這一下沒有成功，請再試一次。" else s
    }

    fun hm(t: LocalTime): String = "${t.hour}:${t.minute.toString().padStart(2, '0')}"

    fun hm(at: Instant, zone: ZoneId): String = hm(at.atZone(zone).toLocalTime())

    fun day(ctx: ToolCtx): LocalDate = Days.today(ctx.clock)

    fun moment(ctx: ToolCtx): Moment = Moment.of(ctx.store, ctx.clock, ctx.rules)

    inline fun <reified T> write(ctx: ToolCtx, kind: String, value: T): Entry {
        val now = ctx.clock.now()
        return ctx.store.add(Days.of(now, ctx.clock.zone), now, kind, value)
    }

    fun obj(block: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)

    fun arr(items: List<JsonElement>): JsonArray = JsonArray(items)

    fun strs(items: List<String>): JsonArray = JsonArray(items.map { JsonPrimitive(it) })

    /** One decimal at most, so 1.25 bottles stays readable and 40.0 g is just 40. */
    fun num(v: Double): JsonPrimitive {
        val r = Math.round(v * 100) / 100.0
        return if (r == Math.floor(r)) JsonPrimitive(r.toLong()) else JsonPrimitive(r)
    }

    fun bottles(ml: Int, bottleMl: Int): Double = if (bottleMl <= 0) 0.0 else Math.round(ml * 100.0 / bottleMl) / 100.0

    /** A closed feature's tool still records, then says so in these words. */
    fun closedNote(feature: Feature): String =
        "「${feature.label}」還沒開放（第 ${feature.stage} 階段才開始）。這次先記下來，但不算進今天、不會提醒、也不算違規。"

    /** The plan item as facts: id, title, status, times, detail. */
    fun item(i: PlanItem): JsonObject = obj {
        put("id", i.id)
        put("title", i.title)
        put("status", statusWord(i.status))
        i.at?.let { put("at", hm(it)) }
        i.until?.let { put("until", hm(it)) }
        if (i.detail.isNotBlank()) put("detail", i.detail)
    }

    fun statusWord(s: ItemStatus): String = when (s) {
        ItemStatus.DONE -> "完成"
        ItemStatus.PENDING -> "還沒到／待做"
        ItemStatus.LATE -> "晚了，還可以做"
        ItemStatus.MISSED -> "錯過"
        ItemStatus.OFF -> "今天取消"
    }

    /** Today's numbers after a write: progress, food, water, the next thing. Only what is open and set up. */
    fun today(m: Moment): JsonObject = obj {
        val s = m.state
        val r = m.rules
        put("date", m.day.toString())
        put("time", hm(m.now, m.clock.zone))
        put("stage", Stages.label(m.stage, m.day))
        put("done", m.plan.done)
        put("total", m.plan.total)
        if (Feature.MEALS.openAt(m.stage.stage) && r.diet.kcal > 0) {
            put("kcal", s.kcal)
            put("kcal_target", r.diet.kcal)
            put("kcal_left", s.leftKcal)
            put("protein_g", num(s.proteinG))
            if (r.diet.proteinG > 0) {
                put("protein_target_g", r.diet.proteinG)
                put("protein_left_g", num((r.diet.proteinG - s.proteinG).coerceAtLeast(0.0)))
            }
            if (RulesCheck.ready(r, Feature.MEALS)) {
                put("next_meal_budget_kcal", obj {
                    put("第一餐", s.budget(app.agency.core.store.MealSlot.FIRST))
                    put("晚餐", s.budget(app.agency.core.store.MealSlot.DINNER))
                    put("宵夜", s.budget(app.agency.core.store.MealSlot.NIGHT))
                })
            }
        }
        if (Feature.WATER.openAt(m.stage.stage) && r.water.bottleMl > 0) {
            val target = s.waterTargetMl(s.dayNumber)
            put("water_ml", s.waterMl)
            put("water_target_ml", target)
            put("water_bottles", num(bottles(s.waterMl, r.water.bottleMl)))
            put("water_target_bottles", num(bottles(target, r.water.bottleMl)))
            put("water_left_ml", (target - s.waterMl).coerceAtLeast(0))
        }
        m.plan.next?.let { put("next", item(it)) }
    }

    /** Runs the write-time violation checks and lists what they newly found, in his words. */
    fun violations(ctx: ToolCtx): List<String> {
        val now = ctx.clock.now()
        val written = Violations.onWrite(ctx.store, ctx.rules, ctx.stage, Days.of(now, ctx.clock.zone), now, ctx.clock.zone)
        return written.mapNotNull { e -> runCatching { e.read<Violation>() }.getOrNull() }.map(::violationWord)
    }

    fun violationWord(v: Violation): String {
        val label = ViolationCode.values().firstOrNull { it.name == v.code }?.label ?: v.code
        return if (v.note.isBlank()) label else "$label：${v.note}"
    }

    /** Facts of a voluntary report for a closed feature. */
    fun closed(feature: Feature, entry: Entry?, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject = obj {
        put("open", false)
        entry?.let { put("entry_id", it.id) }
        put("note", closedNote(feature))
        extra()
    }

    fun entryIdOf(out: ToolOut): Long? = out.facts["entry_id"]?.jsonPrimitive?.content?.toLongOrNull()

    fun isKind(e: Entry?, kind: String) = e != null && e.kind == kind

    val TODAY_ONLY = setOf(Kind.MEAL, Kind.WATER, Kind.MONEY, Kind.DRINK, Kind.BODY, Kind.MOOD)
}
