package app.agency.core.tools

import app.agency.core.brain.SnapshotReader
import app.agency.core.llm.ToolSpec
import app.agency.core.model.DayRecord
import app.agency.core.model.PlanSectionKey
import app.agency.core.model.TrainingKind
import app.agency.core.rules.CoachRules
import app.agency.core.store.AgencyStore
import app.agency.core.structured.JsonSchema
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tool 1 `get_today` (arch 1.4): today's numbers, every one computed by code, with today's slots from the day planner and their
 * status (T11). `riding` is the nerve's (出門跑單 to 收工, T13/T12). Record ids are what the model may cite.
 */
class GetTodayTool(
    private val store: AgencyStore,
    private val snapshot: SnapshotReader,
    private val riding: suspend () -> Boolean = { false },
) : AgencyTool {
    override val spec = ToolSpec(NAME, "今天的紀錄和剩下的額度（程式算好的數字）。", JsonSchema.obj())

    override suspend fun run(args: JsonObject): JsonElement {
        val profile = snapshot.profile()
        val rules = snapshot.rules()
        val day = snapshot.today(profile)
        val t = snapshot.records(day)
        val week = store.meals.between(snapshot.weekStart(day).toString(), day.toString())
        val eaten = t.meals.sumOf { it.kcal }
        val protein = t.meals.sumOf { m -> m.items.sumOf { it.proteinG ?: 0.0 } }
        val override = store.days.get(day.toString())
        return buildJsonObject {
            put("day", day.toString())
            put("day_type", dayType(day, override?.restDay == true))
            put("slots", buildJsonArray {
                snapshot.slots(day, t, profile).forEach { st ->
                    add(buildJsonObject {
                        put("key", st.key.name.lowercase()); put("label", st.slot.label); put("time", st.slot.time)
                        put("status", st.status.name.lowercase()); put("record_ids", buildJsonArray { st.recordIds.forEach { add(JsonPrimitive(it)) } })
                    })
                }
            })
            put("meals", buildJsonArray {
                t.meals.forEach { m ->
                    add(buildJsonObject {
                        put("id", m.id); put("time", snapshot.time(m.at)); put("kcal", m.kcal); put("verified", m.verified)
                        put("items", m.items.joinToString("、") { it.name }.take(ROW_TEXT))
                    })
                }
            })
            put("kcal", buildJsonObject {
                put("eaten", eaten); put("left", rules.quotaKcal - eaten); put("meal_cap", rules.mealCapKcal)
                put("floor_left", maxOf(0, rules.floorKcal - eaten))
            })
            put("protein", buildJsonObject {
                put("eaten", protein.toInt()); put("left", CoachRules.remainingProtein(rules.proteinG, protein))
            })
            put("water", buildJsonObject {
                put("target_half", app.agency.core.engine.WaterPlan.target(day, snapshot.plans.specs().water, profile.joinDate))
                put("done_half", t.water.sumOf { it.halfBottles })
            })
            put("training", buildJsonObject {
                put("core", t.training.any { it.kind == TrainingKind.CORE7 })
                put("strength", t.training.any { it.kind == TrainingKind.STRENGTH })
                put("stretch", t.training.any { it.kind == TrainingKind.STRETCH })
            })
            put("condition", t.condition?.let { c -> buildJsonObject { put("score", c.score); put("ill", c.ill) } } ?: JsonNull)
            put("cheat", buildJsonObject {
                put("used_this_week", week.count { it.cheat }); put("declared_today", override?.cheatSlot != null)
            })
            put("zero_cal_week", week.sumOf { m -> m.items.count { it.zeroCal } })
            put("riding", riding())
        }
    }

    companion object {
        const val NAME = "get_today"
        const val ROW_TEXT = 60

        /** Plan v3 §4: class days Mon/Tue/Thu/Fri, delivery days Wed/Sat/Sun; 「今天休息」 makes it a rest day. */
        fun dayType(day: LocalDate, rest: Boolean): String = when {
            rest -> "rest"
            day.dayOfWeek in setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY) -> "class"
            else -> "no_class"
        }
    }
}

/** Tool 2 `query_records`: summaries of one kind of record over at most [MAX_DAYS] days, at most [MAX_ROWS] rows (newest). */
class QueryRecordsTool(private val store: AgencyStore, private val snapshot: SnapshotReader) : AgencyTool {
    override val spec = ToolSpec(
        NAME,
        "查一種紀錄的摘要：kind 是種類，from 和 to 是日期（YYYY-MM-DD，最多 $MAX_DAYS 天）。",
        JsonSchema.obj("kind" to JsonSchema.enum(KINDS), "from" to JsonSchema.str(), "to" to JsonSchema.str()),
    )

    override suspend fun run(args: JsonObject): JsonElement {
        val kind = ToolRegistry.str(args, "kind").takeIf { it in KINDS } ?: throw BadToolArgs("kind", "要是其中一個：${KINDS.joinToString("、")}")
        val from = date(args, "from")
        val to = date(args, "to")
        if (to < from) throw BadToolArgs("to", "to 不能比 from 早")
        if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) throw BadToolArgs("from", "最多查 $MAX_DAYS 天")
        val f = from.toString()
        val t = to.toString()
        val rows: List<JsonObject> = when (kind) {
            "meal" -> store.meals.between(f, t).map { m ->
                row(m) {
                    put("kcal", m.kcal); put("items", m.items.joinToString("、") { it.name }.take(GetTodayTool.ROW_TEXT))
                    put("cheat", m.cheat); put("verified", m.verified); put("breaches", m.breaches.size)
                }
            }
            "water" -> store.water.between(f, t).map { w -> row(w) { put("half", w.halfBottles) } }
            "training" -> store.training.between(f, t).map { r ->
                row(r) { put("kind", r.kind.name.lowercase()); r.rounds?.let { put("rounds", it) }; put("reduced", r.reduced) }
            }
            "sign" -> store.signs.between(f, t).map { s -> row(s) { put("kind", s.kind.name.lowercase()) } }
            "weight" -> store.weights.between(f, t).map { w -> row(w) { put("kg", w.kg); w.waistCm?.let { put("waist_cm", it) } } }
            else -> store.conditions.between(f, t).map { c ->
                buildJsonObject { put("day", c.day); put("score", c.score); put("ill", c.ill) }
            }
        }
        return buildJsonObject {
            put("rows", buildJsonArray { rows.takeLast(MAX_ROWS).forEach { add(it) } })
            if (rows.size > MAX_ROWS) put("more", rows.size - MAX_ROWS)
        }
    }

    private fun row(r: DayRecord, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("id", r.id); put("day", r.day); put("time", snapshot.time(r.at)); extra()
    }

    private fun date(args: JsonObject, key: String): LocalDate {
        val s = ToolRegistry.str(args, key)
        return runCatching { LocalDate.parse(s) }.getOrNull() ?: throw BadToolArgs(key, "日期要寫成 YYYY-MM-DD")
    }

    companion object {
        const val NAME = "query_records"
        const val MAX_DAYS = 14
        const val MAX_ROWS = 40
        val KINDS = listOf("meal", "water", "training", "condition", "sign", "weight")
    }
}

/** Tool 6 `get_plan`: one section as it stands. 公司規章 are readable here and never writable by any tool. */
class GetPlanTool(private val store: AgencyStore, private val snapshot: SnapshotReader) : AgencyTool {
    override val spec = ToolSpec(NAME, "讀計畫的一部分；rules 是公司規章（只能讀）。", JsonSchema.obj("section" to JsonSchema.enum(SECTIONS)))

    override suspend fun run(args: JsonObject): JsonElement {
        val section = ToolRegistry.str(args, "section").takeIf { it in SECTIONS }
            ?: throw BadToolArgs("section", "要是其中一個：${SECTIONS.joinToString("、")}")
        if (section == "rules") {
            val r = snapshot.rules()
            return buildJsonObject {
                put("section", section)
                put("quota_kcal", r.quotaKcal); put("protein_g", r.proteinG); put("fat_g", r.fatG); put("carbs_g", r.carbsG)
                put("floor_kcal", r.floorKcal); put("meal_cap_kcal", r.mealCapKcal); put("cheat_meals_per_week", r.cheatMealsPerWeek)
                put("zero_cal_per_week", r.zeroCalPerWeek)
                put("goals_kg", buildJsonArray { r.goalsKg.sortedDescending().forEach { add(JsonPrimitive(it)) } })
            }
        }
        val stored = store.rules.section(PlanSectionKey.valueOf(section.uppercase()))
        return buildJsonObject {
            put("section", section)
            if (stored == null) {
                put("set", false)
            } else {
                put("set", true)
                put("version", stored.version)
                put("values", runCatching { Json.parseToJsonElement(stored.json) }.getOrElse { JsonNull })
            }
        }
    }

    companion object {
        const val NAME = "get_plan"
        val SECTIONS = listOf("schedule", "meals", "training", "water", "rules")
    }
}

/** The read tools of stage 1's first cut (T06): 1, 2 and 6. A chat round gets all ten ([chatTools], T12). */
fun readTools(store: AgencyStore, snapshot: SnapshotReader): List<AgencyTool> =
    listOf(GetTodayTool(store, snapshot), QueryRecordsTool(store, snapshot), GetPlanTool(store, snapshot))
