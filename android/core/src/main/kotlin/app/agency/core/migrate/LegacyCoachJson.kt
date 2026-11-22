package app.agency.core.migrate

import app.agency.core.model.FoodEntry
import app.agency.core.model.KcalSource
import app.agency.core.model.MealItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/*
 * The coach's files as v0.8.3 (state `version` 1) and the 5a94d3c agency build (`version` 2, or a v1 file it saved again)
 * wrote them: `filesDir/coach/state.json` and `messages.jsonl`. These types are the files' own shape, kept apart from the
 * agency model; LegacyMapper turns them into agency records.
 */

/** A rule a legacy meal broke, as the 5a94d3c build named it. */
object LegacyBreach {
    const val FRIED = "FRIED"
    const val OVER700 = "OVER700"
    const val ZEROCAL = "ZEROCAL"
}

/** The 入社建檔 steps the 5a94d3c build asked (names as stored). */
enum class LegacyBaselineStep { BODY, PHOTO, RECORDING, PUSHUPS, PLANK }

data class LegacyMeal(
    val id: String,
    val at: Long,
    val day: String,
    val text: String,
    val items: List<MealItem>,
    val cheat: Boolean,
    val offPlan: Boolean,
    val verified: Boolean = false,
    val evidence: String? = null,
    /** Breach names; a v0.8.3 meal (no `breaches` key) that was off plan could only have been fried food. */
    val breaches: List<String> = if (offPlan) listOf(LegacyBreach.FRIED) else emptyList(),
)

data class LegacyWeight(val id: String, val at: Long, val day: String, val kg: Double, val waistCm: Double?)

data class LegacyPending(val mealId: String, val itemIndex: Int, val name: String, val countedKcal: Int, val askedAt: Long)

data class LegacyTraining(val id: String, val at: Long, val day: String, val rounds: Int?)

/** [photo] and [recording] are file names in `coach/baseline/`. */
data class LegacyBaseline(
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    val photo: String? = null,
    val recording: String? = null,
    val pushups: Int? = null,
    val plankSec: Int? = null,
    val skipped: Set<LegacyBaselineStep> = emptySet(),
    val step: LegacyBaselineStep? = null,
)

data class LegacyState(
    val version: Int = 1,
    val seeded: Boolean = false,
    val meals: List<LegacyMeal> = emptyList(),
    val weights: List<LegacyWeight> = emptyList(),
    val food: List<FoodEntry> = emptyList(),
    val pending: List<LegacyPending> = emptyList(),
    val trainings: List<LegacyTraining> = emptyList(),
    val baseline: LegacyBaseline? = null,
    /** True when the 5a94d3c build wrote the file (it always writes `trainings`, also over a version 1 file). */
    val agencyBuild: Boolean = false,
)

enum class LegacySpeaker { OWNER, COACH }

/** The two roles of the 5a94d3c build. */
enum class LegacyRole {
    AGENT, BODY;

    companion object {
        val BODY_KINDS = setOf("meal", "craving", "weight", "food_info", "training")

        /** The role of a message saved before roles existed (v0.8.3): body matters go to the 體態管理老師. */
        fun forKind(kind: String?): LegacyRole = if (kind in BODY_KINDS) BODY else AGENT
    }
}

data class LegacyMessage(
    val id: String,
    val at: Long,
    val who: LegacySpeaker,
    val text: String,
    val kind: String?,
    val role: LegacyRole? = null,
)

/**
 * The tolerant decoders the coach used on its own files (moved here from the app's `CoachJson`, which now calls them).
 * Unknown or bad fields fall back, a record without its id is dropped, nothing throws on a parsed tree.
 */
object LegacyCoachJson {
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.dbl(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.arr(k: String): List<JsonObject> = (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    private fun strings(e: JsonElement?): List<String>? =
        (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

    private fun step(name: String?): LegacyBaselineStep? = name?.let { n -> LegacyBaselineStep.entries.firstOrNull { it.name == n } }

    /** A state file's text; null when it is not JSON at all (the coach then tried the next file). */
    fun parseState(text: String): LegacyState? = try {
        decode(Json.parseToJsonElement(text))
    } catch (_: Exception) {
        null
    }

    fun decode(e: JsonElement): LegacyState {
        val o = e as? JsonObject ?: return LegacyState()
        return LegacyState(
            version = o.int("version") ?: 1,
            seeded = o.bool("seeded") ?: false,
            meals = o.arr("meals").mapNotNull(::decodeMeal),
            weights = o.arr("weights").mapNotNull { w ->
                LegacyWeight(
                    w.str("id") ?: return@mapNotNull null, w.long("at") ?: 0, w.str("day") ?: return@mapNotNull null,
                    w.dbl("kg") ?: return@mapNotNull null, w.dbl("waist"),
                )
            },
            food = o.arr("food").mapNotNull(::decodeFood),
            pending = o.arr("pending").mapNotNull { p ->
                LegacyPending(p.str("mealId") ?: return@mapNotNull null, p.int("itemIndex") ?: 0, p.str("name") ?: "", p.int("counted") ?: 0, p.long("askedAt") ?: 0)
            },
            trainings = o.arr("trainings").mapNotNull { t ->
                LegacyTraining(t.str("id") ?: return@mapNotNull null, t.long("at") ?: 0, t.str("day") ?: return@mapNotNull null, t.int("rounds"))
            },
            baseline = (o["baseline"] as? JsonObject)?.let { b ->
                LegacyBaseline(
                    heightCm = b.dbl("height"), weightKg = b.dbl("weight"), photo = b.str("photo"), recording = b.str("recording"),
                    pushups = b.int("pushups"), plankSec = b.int("plank"),
                    skipped = (b["skipped"] as? JsonArray)?.mapNotNull { s -> (s as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::step) }.orEmpty().toSet(),
                    step = step(b.str("step")),
                )
            },
            agencyBuild = (o.int("version") ?: 1) >= 2 || o["trainings"] is JsonArray,
        )
    }

    private fun decodeMeal(m: JsonObject): LegacyMeal? {
        val id = m.str("id") ?: return null
        val day = m.str("day") ?: return null
        return LegacyMeal(
            id = id, at = m.long("at") ?: 0, day = day, text = m.str("text").orEmpty(),
            items = m.arr("items").mapNotNull { i ->
                MealItem(
                    name = i.str("name") ?: return@mapNotNull null, portion = i.dbl("portion") ?: 1.0, kcal = i.int("kcal") ?: 0,
                    proteinG = i.dbl("protein"), fatG = i.dbl("fat"), carbsG = i.dbl("carbs"), fried = i.bool("fried") ?: false,
                    source = runCatching { KcalSource.valueOf(i.str("source").orEmpty()) }.getOrDefault(KcalSource.ESTIMATE),
                    tableEstimated = i.bool("tableEstimated") ?: false, estimateHigh = i.int("estimateHigh"), zeroCal = i.bool("zeroCal") ?: false,
                )
            },
            cheat = m.bool("cheat") ?: false, offPlan = m.bool("offPlan") ?: false, verified = m.bool("verified") ?: false,
            evidence = m.str("evidence"),
            breaches = strings(m["breaches"]) ?: if (m.bool("offPlan") == true) listOf(LegacyBreach.FRIED) else emptyList(),
        )
    }

    private fun decodeFood(f: JsonObject): FoodEntry? {
        val name = f.str("name") ?: return null
        val kcal = f.int("kcal") ?: return null
        return FoodEntry(
            name = name, aliases = strings(f["aliases"]).orEmpty(),
            kcal = kcal, proteinG = f.dbl("protein"), fatG = f.dbl("fat"), carbsG = f.dbl("carbs"),
            fried = f.bool("fried") ?: false, estimated = f.bool("estimated") ?: false, seeded = f.bool("seeded") ?: false,
            addedAt = f.long("addedAt") ?: 0,
        )
    }

    fun decodeMessage(e: JsonElement): LegacyMessage? {
        val o = e as? JsonObject ?: return null
        val who = LegacySpeaker.entries.firstOrNull { it.name == o.str("who") } ?: return null
        return LegacyMessage(
            o.str("id") ?: return null, o.long("at") ?: 0, who, o.str("text") ?: return null, o.str("kind"),
            o.str("role")?.let { r -> LegacyRole.entries.firstOrNull { it.name == r } },
        )
    }

    /** One `messages.jsonl` line; null for a blank, cut or foreign line (the coach skipped those too). */
    fun parseMessageLine(line: String): LegacyMessage? =
        if (line.isBlank()) null else runCatching { decodeMessage(Json.parseToJsonElement(line)) }.getOrNull()

    /** Every message in a `messages.jsonl` text (not only the last 300 the coach kept in memory) and how many lines were unreadable. */
    fun parseMessages(text: String): Pair<List<LegacyMessage>, Int> {
        var bad = 0
        val out = text.lineSequence().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            parseMessageLine(line) ?: run { bad++; null }
        }.toList()
        return out to bad
    }
}
