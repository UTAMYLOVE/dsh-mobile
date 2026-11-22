package app.agency.core.tools

import app.agency.core.brain.OrderDiff
import app.agency.core.brain.OrderItem
import app.agency.core.brain.PlaceDiff
import app.agency.core.brain.ProposalApplier
import app.agency.core.brain.SnapshotReader
import app.agency.core.guard.OutputGuard
import app.agency.core.llm.ToolSpec
import app.agency.core.memory.MemoryApplier
import app.agency.core.memory.MemoryBlock
import app.agency.core.memory.MemoryRequest
import app.agency.core.model.MemoryOp
import app.agency.core.model.Message
import app.agency.core.model.PlanSectionKey
import app.agency.core.model.Proposal
import app.agency.core.model.ProposalKind
import app.agency.core.model.RoleKey
import app.agency.core.rules.CoachRules
import app.agency.core.rules.FoodTable
import app.agency.core.schedule.PlanChange
import app.agency.core.schedule.PlanChanges
import app.agency.core.schedule.PlanPath
import app.agency.core.schedule.SlotKey
import app.agency.core.schedule.SlotKind
import app.agency.core.store.AgencyStore
import app.agency.core.structured.JsonSchema
import app.agency.core.time.AgencyClock
import java.time.LocalDate
import kotlin.math.ceil
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * What one ChatLoop round (one message of his) shares among its tools: his message (the source of a memory event), the
 * memory references the prompt showed, and what the round did, so the reply can show it: the proposals it created, the memory
 * events it applied, the search results it got (a place must come from these).
 */
class RoundContext(val owner: Message, val memoryRefs: Map<String, String>, val today: LocalDate, val role: RoleKey = RoleKey.MANAGER) {
    val proposals = mutableListOf<String>()
    val memoryEvents = mutableListOf<String>()
    val searchResults = mutableSetOf<String>()

    /** How many more memory events this turn may apply (at most [MemoryApplier.MAX_PER_TURN] in all). */
    val memoryLeft: Int get() = MemoryApplier.MAX_PER_TURN - memoryEvents.size
}

private fun JsonObject.optStr(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.optInt(key: String, min: Int, max: Int): Int? {
    val e = this[key] ?: return null
    if (e is JsonNull) return null
    val v = (e as? JsonPrimitive)?.intOrNull ?: throw BadToolArgs(key, "要是整數或 null")
    if (v !in min..max) throw BadToolArgs(key, "要在 $min 到 $max 之間")
    return v
}

private fun JsonObject.optNum(key: String, min: Double, max: Double): Double? {
    val e = this[key] ?: return null
    if (e is JsonNull) return null
    val v = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: throw BadToolArgs(key, "要是數字或 null")
    if (v < min || v > max) throw BadToolArgs(key, "要在 ${CoachRules.kg(min)} 到 ${CoachRules.kg(max)} 之間")
    return v
}

private fun chars(s: String) = s.codePointCount(0, s.length)

/** A short text of the model's that he will read on a card: no Latin letters, nothing the guard bans. */
private fun cardText(key: String, raw: String, max: Int): String {
    val t = raw.trim()
    if (t.isEmpty()) throw BadToolArgs(key, "要寫")
    if (chars(t) > max) throw BadToolArgs(key, "最多 $max 個字")
    if (t.any { it in 'A'..'Z' || it in 'a'..'z' }) throw BadToolArgs(key, "不要用英文字母")
    OutputGuard.bannedLabel(t, full = true)?.let { throw BadToolArgs(key, "不能這樣寫：$it") }
    return t
}

/** Tool 3 `lookup_food`: the food table only; never invents a number. */
class LookupFoodTool(private val store: AgencyStore) : AgencyTool {
    override val spec = ToolSpec(NAME, "在常吃食物表查一樣食物的熱量和蛋白質；查不到就是沒有，不准自己編。", JsonSchema.obj("query" to JsonSchema.str()))

    override suspend fun run(args: JsonObject): JsonElement {
        val q = ToolRegistry.str(args, "query").trim()
        if (q.isEmpty() || chars(q) > MAX_QUERY) throw BadToolArgs("query", "一到 $MAX_QUERY 個字")
        val table = table(store)
        val match = FoodTable.lookup(table, q)
        val n = FoodTable.normalize(q)
        val candidates = (FoodTable.named(table, q) + table.filter { n.length >= 2 && FoodTable.normalize(it.name).contains(n) })
            .map { it.name }.distinct().filter { it != match?.name }.take(MAX_CANDIDATES)
        return buildJsonObject {
            put("match", match?.let { e ->
                buildJsonObject { put("name", e.name); put("kcal", e.kcal); e.proteinG?.let { put("protein_g", it) } ?: put("protein_g", JsonNull); put("estimated", e.estimated) }
            } ?: JsonNull)
            put("candidates", buildJsonArray { candidates.forEach { add(JsonPrimitive(it)) } })
        }
    }

    companion object {
        const val NAME = "lookup_food"
        const val MAX_QUERY = 30
        const val MAX_CANDIDATES = 3

        /** The seed table and his own entries (a stored entry wins over a seed of the same name). */
        suspend fun table(store: AgencyStore) = (store.food.all() + FoodTable.seed(0)).distinctBy { it.name }
    }
}

/** Tool 4 `compute`: the only arithmetic the model may ask for, done by code on today's numbers. */
class ComputeTool(private val snapshot: SnapshotReader) : AgencyTool {
    override val spec = ToolSpec(
        NAME,
        "程式幫你算：after_eating 吃了 kcal 大卡、protein_g 克蛋白質之後還剩多少；protein_plan 剩下的蛋白質怎麼分到還沒吃的餐；" +
            "over_quota 今天超過多少；next_goal 下一個體重目標。用不到的參數給 null。",
        JsonSchema.obj("op" to JsonSchema.enum(OPS), "kcal" to JsonSchema.nullable(JsonSchema.int()), "protein_g" to JsonSchema.nullable(JsonSchema.num())),
    )

    override suspend fun run(args: JsonObject): JsonElement {
        val op = ToolRegistry.str(args, "op").takeIf { it in OPS } ?: throw BadToolArgs("op", "要是其中一個：${OPS.joinToString("、")}")
        val profile = snapshot.profile()
        val rules = snapshot.rules()
        val day = snapshot.today(profile)
        val records = snapshot.records(day)
        val eaten = records.meals.sumOf { it.kcal }
        val protein = records.meals.sumOf { m -> m.items.sumOf { it.proteinG ?: 0.0 } }
        return when (op) {
            "after_eating" -> {
                val kcal = args.optInt("kcal", 0, MAX_KCAL) ?: throw BadToolArgs("kcal", "after_eating 要有 kcal")
                val p = args.optNum("protein_g", 0.0, MAX_PROTEIN) ?: 0.0
                buildJsonObject {
                    put("kcal_left_after", rules.quotaKcal - eaten - kcal)
                    put("protein_left_after", CoachRules.remainingProtein(rules.proteinG, protein + p))
                    put("within_meal_cap", kcal <= rules.mealCapKcal)
                    put("above_floor", eaten + kcal >= rules.floorKcal)
                }
            }
            "protein_plan" -> {
                val left = CoachRules.remainingProtein(rules.proteinG, protein)
                val meals = snapshot.slots(day, records, profile)
                    .filter { it.slot.key.kind == SlotKind.MEAL && it.status.name in setOf("LATER", "OPEN") }.map { it.slot.label }
                buildJsonObject {
                    put("protein_left", left)
                    put("meals_left", buildJsonArray { meals.forEach { add(JsonPrimitive(it)) } })
                    put("per_meal_g", if (meals.isEmpty()) JsonNull else JsonPrimitive(ceil(left.toDouble() / meals.size).toInt()))
                }
            }
            "over_quota" -> buildJsonObject {
                put("over_kcal", maxOf(0, eaten - rules.quotaKcal))
                put("tomorrow_kcal", rules.quotaKcal)
            }
            else -> {
                val kg = snapshot.currentWeightKg(day, profile)
                buildJsonObject {
                    put("current_kg", kg?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("next_goal_kg", kg?.let { CoachRules.nextGoal(rules.goalsKg, it) }?.let { JsonPrimitive(it) } ?: JsonNull)
                }
            }
        }
    }

    companion object {
        const val NAME = "compute"
        const val MAX_KCAL = 3000
        const val MAX_PROTEIN = 300.0
        val OPS = listOf("after_eating", "protein_plan", "over_quota", "next_goal")
    }
}

/**
 * Tool 5 `search_places`: nearby food search comes with T16 (DeepSeek Search, query built by code from the dish and his
 * area). Until then it answers with no results, so no place can be named.
 */
class SearchPlacesTool : AgencyTool {
    override val spec = ToolSpec(NAME, "搜尋附近哪裡有這道菜。只能講搜尋結果裡的店。", JsonSchema.obj("dish" to JsonSchema.str()))

    override suspend fun run(args: JsonObject): JsonElement {
        val dish = ToolRegistry.str(args, "dish").trim()
        if (dish.isEmpty() || chars(dish) > MAX_DISH) throw BadToolArgs("dish", "一到 $MAX_DISH 個字")
        return buildJsonObject {
            put("results", JsonArray(emptyList()))
            put("note", "附近搜尋下一版才開，現在沒有結果；不要講任何店名")
        }
    }

    companion object {
        const val NAME = "search_places"
        const val MAX_DISH = 20
    }
}

/**
 * Tool 7 `propose_change`: a pending plan change he confirms (times only; company rules are not in the schema at all). Code
 * checks every op against the section and the day's order ([PlanChanges]); the card shows before → after.
 */
class ProposeChangeTool(
    private val store: AgencyStore,
    private val snapshot: SnapshotReader,
    private val clock: AgencyClock,
    private val newId: () -> String,
    private val ctx: RoundContext,
) : AgencyTool {
    override val spec = ToolSpec(
        NAME,
        "提議改計畫的時間（他按好才會改）。section 是哪一段；ops 每一項是 path（要改的欄位）和 value（新的時間 HH:mm，" +
            "start_day 用 YYYY-MM-DD）；reason 一句話說為什麼（最多 $REASON 字）。公司規章不能改。",
        JsonSchema.obj(
            "section" to JsonSchema.enum(SECTIONS),
            "ops" to JsonSchema.arr(JsonSchema.obj("path" to JsonSchema.enum(PlanPath.KEYS), "value" to JsonSchema.str())),
            "reason" to JsonSchema.str(),
        ),
    )

    override suspend fun run(args: JsonObject): JsonElement {
        val sectionKey = ToolRegistry.str(args, "section")
        if (sectionKey !in SECTIONS) throw BadToolArgs("section", "要是其中一個：${SECTIONS.joinToString("、")}；公司規章只有他自己能在設定改")
        val section = PlanSectionKey.valueOf(sectionKey.uppercase())
        val ops = (args["ops"] as? JsonArray ?: throw BadToolArgs("ops", "要是陣列")).mapIndexed { i, e ->
            val o = e as? JsonObject ?: throw BadToolArgs("ops[$i]", "要是物件")
            (o.optStr("path") ?: throw BadToolArgs("ops[$i].path", "要有 path")) to (o.optStr("value") ?: throw BadToolArgs("ops[$i].value", "要有 value"))
        }
        val reason = cardText("reason", ToolRegistry.str(args, "reason"), REASON)
        val checked = PlanChanges.check(snapshot.plans.specs(), section, ops, ctx.today)
        if (checked is PlanChanges.Checked.Bad) throw BadToolArgs(checked.problem.field, checked.problem.reason)
        val change = (checked as PlanChanges.Checked.Ok).change
        val id = newId()
        store.proposals.insert(
            Proposal(id, clock.millis(), ctx.role, ProposalKind.PLAN_CHANGE, target = section.name, diffJson = ProposalApplier.JSON.encodeToString(PlanChange.serializer(), change), reason = reason),
        )
        ctx.proposals += id
        return buildJsonObject {
            put("proposal_id", id)
            put("status", "pending_owner")
            put("note", "已經做成提案卡，他按好才會改；不要說已經改好")
        }
    }

    companion object {
        const val NAME = "propose_change"
        const val REASON = 80
        val SECTIONS = listOf("schedule", "meals", "training", "water")
    }
}

/** Tool 8 `propose_meal_order`: what to order for a meal slot; kcal from the food table (code), within the single-meal cap. */
class ProposeMealOrderTool(
    private val store: AgencyStore,
    private val snapshot: SnapshotReader,
    private val clock: AgencyClock,
    private val newId: () -> String,
    private val ctx: RoundContext,
) : AgencyTool {
    override val spec = ToolSpec(
        NAME,
        "提議這一餐點什麼（他按好才算數）。slot 是哪一餐；items 每一樣是 name（食物表裡的名字）和 portion（份數）；" +
            "place_result_id 是這一輪搜尋結果的編號，沒有就給 null。熱量由程式算。",
        JsonSchema.obj(
            "slot" to JsonSchema.enum(SLOTS),
            "items" to JsonSchema.arr(JsonSchema.obj("name" to JsonSchema.str(), "portion" to JsonSchema.num())),
            "place_result_id" to JsonSchema.nullable(JsonSchema.str()),
        ),
    )

    override suspend fun run(args: JsonObject): JsonElement {
        val slot = ToolRegistry.str(args, "slot").takeIf { it in SLOTS } ?: throw BadToolArgs("slot", "要是其中一個：${SLOTS.joinToString("、")}")
        val raw = args["items"] as? JsonArray ?: throw BadToolArgs("items", "要是陣列")
        if (raw.isEmpty() || raw.size > MAX_ITEMS) throw BadToolArgs("items", "一到 $MAX_ITEMS 樣")
        val table = LookupFoodTool.table(store)
        val items = raw.mapIndexed { i, e ->
            val o = e as? JsonObject ?: throw BadToolArgs("items[$i]", "要是物件")
            val name = o.optStr("name")?.trim()?.takeIf { it.isNotEmpty() } ?: throw BadToolArgs("items[$i].name", "要有名字")
            val portion = o.optNum("portion", 0.1, 10.0) ?: throw BadToolArgs("items[$i].portion", "要有份數")
            val entry = FoodTable.lookup(table, name) ?: throw BadToolArgs("items[$i].name", "食物表沒有「${name.take(20)}」，先用 lookup_food 查")
            val item = FoodTable.tableItem(entry, name, portion, modelSaysFried = false)
            OrderItem(entry.name, portion, item.kcal, item.proteinG)
        }
        val place = args.optStr("place_result_id")
        if (place != null && place !in ctx.searchResults) throw BadToolArgs("place_result_id", "只能用這一輪搜尋結果的編號")
        val rules = snapshot.rules()
        val profile = snapshot.profile()
        val eaten = snapshot.records(snapshot.today(profile)).meals.sumOf { it.kcal }
        val kcal = items.sumOf { it.kcal }
        if (kcal > rules.mealCapKcal) throw BadToolArgs("items", "一共 $kcal 大卡，超過單餐上限 ${rules.mealCapKcal}，換小一點的")
        val protein = if (items.all { it.proteinG != null }) items.sumOf { it.proteinG!! } else null
        val id = newId()
        val key = SlotKey.valueOf(slot.uppercase())
        val diff = OrderDiff(key.name, items, kcal, protein, place)
        store.proposals.insert(
            Proposal(id, clock.millis(), RoleKey.BODY, ProposalKind.MEAL_ORDER, target = key.name, diffJson = ProposalApplier.JSON.encodeToString(OrderDiff.serializer(), diff), reason = "${key.zh}點這個"),
        )
        ctx.proposals += id
        return buildJsonObject {
            put("proposal_id", id)
            put("kcal", kcal)
            put("protein_g", protein?.let { JsonPrimitive(it) } ?: JsonNull)
            put("within_meal_cap", true)
            put("kcal_left_after", rules.quotaKcal - eaten - kcal)
        }
    }

    companion object {
        const val NAME = "propose_meal_order"
        const val MAX_ITEMS = 6
        val SLOTS = SlotKey.MEALS.map { it.name.lowercase() }
    }
}

/** Tool 9 `save_place`: a search result of this round becomes a 常去店家 only after his yes. */
class SavePlaceTool(
    private val store: AgencyStore,
    private val clock: AgencyClock,
    private val newId: () -> String,
    private val ctx: RoundContext,
) : AgencyTool {
    override val spec = ToolSpec(
        NAME,
        "提議把這一輪搜尋到的一家店存成常去店家（他按好才存）。result_id 是搜尋結果的編號；note 最多 $NOTE 字。",
        JsonSchema.obj("result_id" to JsonSchema.str(), "note" to JsonSchema.str()),
    )

    override suspend fun run(args: JsonObject): JsonElement {
        val rid = ToolRegistry.str(args, "result_id")
        if (rid !in ctx.searchResults) throw BadToolArgs("result_id", "只能用這一輪搜尋結果的編號")
        val r = store.places.result(rid) ?: throw BadToolArgs("result_id", "找不到這個搜尋結果")
        val note = ToolRegistry.str(args, "note").let { if (it.isBlank()) "" else cardText("note", it, NOTE) }
        val id = newId()
        store.proposals.insert(
            Proposal(id, clock.millis(), ctx.role, ProposalKind.SAVE_PLACE, target = rid, diffJson = ProposalApplier.JSON.encodeToString(PlaceDiff.serializer(), PlaceDiff(rid, r.title, note)), reason = "存成常去店家"),
        )
        ctx.proposals += id
        return buildJsonObject { put("proposal_id", id); put("status", "pending_owner") }
    }

    companion object {
        const val NAME = "save_place"
        const val NOTE = 40
    }
}

/** Tool 10 `remember`: memory events, checked and applied by code (at most two in a turn, with the reply's own). */
class RememberTool(private val memory: MemoryApplier, private val ctx: RoundContext) : AgencyTool {
    override val spec = ToolSpec(
        NAME,
        "記下他這一輪親口說的、以後用得到的事（最多兩件）。op 是 add、update 或 delete；block 是區塊；item_id 是記得的事前面的編號" +
            "（add 給 null）；text 是要記的事（最多八十字，不要數字；delete 給 null）；quote 照抄他這一輪原話裡的一小段。",
        JsonSchema.obj(
            "events" to JsonSchema.arr(
                JsonSchema.obj(
                    "op" to JsonSchema.enum(listOf("add", "update", "delete")), "block" to JsonSchema.enum(MemoryBlock.KEYS),
                    "item_id" to JsonSchema.nullable(JsonSchema.str()), "text" to JsonSchema.nullable(JsonSchema.str()), "quote" to JsonSchema.str(),
                ),
            ),
        ),
    )

    override suspend fun run(args: JsonObject): JsonElement {
        val raw = args["events"] as? JsonArray ?: throw BadToolArgs("events", "要是陣列")
        if (raw.isEmpty()) throw BadToolArgs("events", "至少一件")
        val requests = raw.mapIndexed { i, e ->
            val o = e as? JsonObject ?: throw BadToolArgs("events[$i]", "要是物件")
            val op = o.optStr("op")?.takeIf { it in setOf("add", "update", "delete") } ?: throw BadToolArgs("events[$i].op", "要是 add、update 或 delete")
            val block = o.optStr("block")?.takeIf { MemoryBlock.of(it) != null } ?: throw BadToolArgs("events[$i].block", "要是 ${MemoryBlock.KEYS.joinToString("、")}")
            MemoryRequest(MemoryOp.valueOf(op.uppercase()), block, o.optStr("item_id"), o.optStr("text")?.trim(), o.optStr("quote"))
        }
        val allowed = requests.take(ctx.memoryLeft.coerceAtLeast(0))
        val outcome = memory.apply(allowed, ctx.owner, ctx.memoryRefs)
        ctx.memoryEvents += outcome.applied.map { it.id }
        return buildJsonObject {
            put("applied", buildJsonArray { outcome.applied.forEach { add(JsonPrimitive(it.after ?: it.before ?: "")) } })
            put("rejected", buildJsonArray {
                outcome.rejected.forEach { r -> add(buildJsonObject { put("index", r.index); put("reason", r.reason) }) }
                (allowed.size until requests.size).forEach { i -> add(buildJsonObject { put("index", i); put("reason", "一輪最多記 ${MemoryApplier.MAX_PER_TURN} 件事") }) }
            })
        }
    }

    companion object {
        const val NAME = "remember"
    }
}

/**
 * The ten tools of a chat round (arch 1.4), in a fixed order so `tools` stay byte-identical on every step: the read tools
 * (1, 2, 6), then 3, 4, 5 and the proposal and memory tools (7–10) bound to this round's [ctx].
 */
fun chatTools(
    store: AgencyStore,
    snapshot: SnapshotReader,
    clock: AgencyClock,
    newId: () -> String,
    memory: MemoryApplier,
    ctx: RoundContext,
    riding: suspend () -> Boolean = { false },
): List<AgencyTool> = listOf(
    GetTodayTool(store, snapshot, riding), QueryRecordsTool(store, snapshot), LookupFoodTool(store), ComputeTool(snapshot),
    SearchPlacesTool(), GetPlanTool(store, snapshot), ProposeChangeTool(store, snapshot, clock, newId, ctx),
    ProposeMealOrderTool(store, snapshot, clock, newId, ctx), SavePlaceTool(store, clock, newId, ctx), RememberTool(memory, ctx),
)
