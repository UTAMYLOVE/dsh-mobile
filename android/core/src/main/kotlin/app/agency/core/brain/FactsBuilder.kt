package app.agency.core.brain

import app.agency.core.model.CompanyRules
import app.agency.core.model.ConditionLog
import app.agency.core.model.Meal
import app.agency.core.model.Message
import app.agency.core.model.Profile
import app.agency.core.model.SignLog
import app.agency.core.model.TrainingLog
import app.agency.core.model.WaterLog
import app.agency.core.model.WeightLog
import app.agency.core.model.Who
import app.agency.core.rules.CoachRules
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One past day, computed by code from records (never by a model). */
data class DaySummary(val day: String, val reported: Boolean, val kcal: Int, val proteinG: Double, val breaches: Int, val onPlan: Boolean)

/** Today's records (coach day [day]). */
data class TodayRecords(
    val day: String,
    val meals: List<Meal> = emptyList(),
    val water: List<WaterLog> = emptyList(),
    val training: List<TrainingLog> = emptyList(),
    val signs: List<SignLog> = emptyList(),
    val condition: ConditionLog? = null,
    val weights: List<WeightLog> = emptyList(),
)

data class FactsInput(
    val rules: CompanyRules,
    val profile: Profile,
    val currentWeightKg: Double?,
    val today: TodayRecords,
    /** Past days, newest first; at most [FactsBuilder.MAX_SUMMARIES] are used. */
    val summaries: List<DaySummary>,
    /** The stored conversation before this turn, oldest first. */
    val history: List<Message>,
    val zone: ZoneId,
)

/** What one call may carry: the 【資料】 object and the message window (his current message is added by the caller). */
data class CallFacts(val json: JsonObject, val window: List<Message>)

/**
 * Data minimisation (plan v3 §19), in one place: a call carries only the company rules, his basic profile (height, current
 * weight, next goal, stage name), today's records, at most [MAX_SUMMARIES] daily summaries and at most [MAX_MESSAGES]
 * messages counting his current one (each cut to [MESSAGE_CHARS]). Never a photo or media id, a file path, a search URL, his
 * area, money, a key, meal text beyond item names, or his bedtime-review words.
 */
object FactsBuilder {
    const val MAX_SUMMARIES = 7
    const val MAX_MESSAGES = 10
    const val MESSAGE_CHARS = 800

    private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
    private val LINK = Regex("(?i)(https?|content|file)://\\S*|www\\.\\S*|[A-Za-z0-9-]+\\.(com|tw|net|org|io|app)(/\\S*)?")
    private val PATH = Regex("(?:/(?:data|storage|sdcard|mnt|system|cache)|/?[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]*\\.(?:bin|jpg|jpeg|png|webp|m4a|wav|mp4|json|db))\\S*")

    fun build(input: FactsInput, hasCurrentMessage: Boolean = true): CallFacts =
        CallFacts(facts(input), window(input.history, hasCurrentMessage))

    /** The last messages before this turn: [MAX_MESSAGES] minus one when his current message goes with them. */
    fun window(history: List<Message>, hasCurrentMessage: Boolean = true): List<Message> =
        history.filter { it.who != Who.SYSTEM }
            .takeLast(MAX_MESSAGES - if (hasCurrentMessage) 1 else 0)
            .map { it.copy(text = clean(it.text)) }

    /** A message as it may go out: no links or file paths, at most [MESSAGE_CHARS] characters. */
    fun clean(text: String): String = PATH.replace(LINK.replace(text, "（連結）"), "（檔案）").take(MESSAGE_CHARS)

    fun facts(i: FactsInput): JsonObject {
        val r = i.rules
        val t = i.today
        val eaten = t.meals.sumOf { it.kcal }
        val protein = t.meals.sumOf { m -> m.items.sumOf { it.proteinG ?: 0.0 } }
        fun time(at: Long) = HHMM.format(Instant.ofEpochMilli(at).atZone(i.zone))
        return buildJsonObject {
            put("rules", buildJsonObject {
                put("quota_kcal", r.quotaKcal); put("protein_g", r.proteinG); put("fat_g", r.fatG); put("carbs_g", r.carbsG)
                put("floor_kcal", r.floorKcal); put("meal_cap_kcal", r.mealCapKcal)
                put("cheat_meals_per_week", r.cheatMealsPerWeek); put("zero_cal_per_week", r.zeroCalPerWeek)
            })
            put("profile", buildJsonObject {
                i.profile.stageName?.let { put("stage_name", it) }
                i.profile.heightCm?.let { put("height_cm", it) }
                i.currentWeightKg?.let { kg ->
                    put("current_weight_kg", kg)
                    CoachRules.nextGoal(r.goalsKg, kg)?.let { put("next_goal_kg", it) }
                }
            })
            put("today", buildJsonObject {
                put("day", t.day)
                put("meals", buildJsonArray {
                    t.meals.forEach { m ->
                        add(buildJsonObject {
                            put("id", m.id); put("time", time(m.at)); m.slotKey?.let { put("slot", it) }
                            put("kcal", m.kcal); put("verified", m.verified); put("cheat", m.cheat)
                            put("breaches", buildJsonArray { m.breaches.forEach { b -> add(JsonPrimitive(b)) } })
                            put("items", buildJsonArray {
                                m.items.forEach { it2 ->
                                    add(buildJsonObject {
                                        put("name", it2.name); put("kcal", it2.kcal); it2.proteinG?.let { p -> put("protein_g", p) }
                                        put("source", it2.source.name.lowercase())
                                    })
                                }
                            })
                        })
                    }
                })
                put("kcal", buildJsonObject {
                    put("eaten", eaten); put("left", r.quotaKcal - eaten); put("floor_left", maxOf(0, r.floorKcal - eaten))
                })
                put("protein", buildJsonObject {
                    put("eaten", protein.toInt()); put("left", CoachRules.remainingProtein(r.proteinG, protein))
                })
                put("water_half", t.water.sumOf { it.halfBottles })
                put("training", buildJsonArray {
                    t.training.forEach { tr ->
                        add(buildJsonObject {
                            put("id", tr.id); put("time", time(tr.at)); put("kind", tr.kind.name.lowercase())
                            tr.rounds?.let { put("rounds", it) }; put("reduced", tr.reduced)
                        })
                    }
                })
                put("signs", buildJsonArray {
                    t.signs.forEach { s ->
                        add(buildJsonObject {
                            put("id", s.id); put("time", time(s.at)); put("kind", s.kind.name.lowercase())
                            s.onTime?.let { put("on_time", it) }
                        })
                    }
                })
                t.condition?.let { c ->
                    put("condition", buildJsonObject {
                        put("score", c.score); c.sleep?.let { put("sleep", it) }; c.energy?.let { put("energy", it) }; put("ill", c.ill)
                    })
                }
                put("weights", buildJsonArray {
                    t.weights.forEach { w ->
                        add(buildJsonObject { put("id", w.id); put("kg", w.kg); w.waistCm?.let { put("waist_cm", it) } })
                    }
                })
            })
            put("last_days", buildJsonArray {
                i.summaries.take(MAX_SUMMARIES).forEach { s ->
                    add(buildJsonObject {
                        put("day", s.day); put("reported", s.reported); put("kcal", s.kcal); put("protein_g", s.proteinG.toInt())
                        put("breaches", s.breaches); put("on_plan", s.onPlan)
                    })
                }
            })
        }
    }
}
