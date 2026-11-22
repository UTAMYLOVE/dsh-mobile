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
    /** Records the decoders left out (a required field missing), per [ImportKind] name. */
    val dropped: Map<String, Int> = emptyMap(),
) {
    /** How many records the file holds (meals, weights, food, pending labels, trainings, and the baseline). */
    val records: Int get() = meals.size + weights.size + food.size + pending.size + trainings.size + (if (baseline == null) 0 else 1)
}

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

    /**
     * The complete records of a cut state file (a crash or a full disk leaves a prefix of what the coach wrote, see
     * `damaged-state.json`): every top-level value that ends before the cut, and every complete element of the top-level
     * array the cut falls in. A record cut in the middle is left out. Null when the text is not the start of a JSON object or
     * no record is left.
     */
    fun salvageState(text: String): LegacyState? {
        val o = Salvage(text).topLevel() ?: return null
        return decode(o).takeIf { it.records > 0 }
    }

    /** A scanner that knows strings (with escapes), objects and arrays, enough to find where each complete value ends. */
    private class Salvage(private val s: String) {
        private var i = 0

        private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        /** The end (exclusive) of the value that starts at [from], or -1 when the text ends before the value does. */
        private fun end(from: Int): Int {
            if (from >= s.length) return -1
            val c = s[from]
            if (c == '"') return stringEnd(from)
            if (c == '{' || c == '[') {
                var depth = 0
                var j = from
                while (j < s.length) {
                    when (s[j]) {
                        '"' -> { j = stringEnd(j); if (j < 0) return -1; continue }
                        '{', '[' -> depth++
                        '}', ']' -> { depth--; if (depth == 0) return j + 1 }
                    }
                    j++
                }
                return -1
            }
            // A number, true, false or null: complete only when something follows it (a cut "12" may have been "125").
            var j = from
            while (j < s.length && s[j] !in ",}] \t\r\n") j++
            return if (j < s.length) j else -1
        }

        private fun stringEnd(from: Int): Int {
            var j = from + 1
            while (j < s.length) {
                when (s[j]) {
                    '\\' -> j += 2
                    '"' -> return j + 1
                    else -> j++
                }
            }
            return -1
        }

        private fun parse(from: Int, to: Int): JsonElement? = runCatching { Json.parseToJsonElement(s.substring(from, to)) }.getOrNull()

        /** The complete elements of the array starting at [i] (the text ends inside it). */
        private fun cutArray(): JsonArray {
            val out = mutableListOf<JsonElement>()
            i++ // [
            while (true) {
                ws()
                if (i >= s.length || s[i] == ']') break
                if (s[i] == ',') { i++; continue }
                val e = end(i)
                if (e < 0) break
                out += parse(i, e) ?: break
                i = e
            }
            return JsonArray(out)
        }

        fun topLevel(): JsonObject? {
            ws()
            if (i >= s.length || s[i] != '{') return null
            i++
            val fields = linkedMapOf<String, JsonElement>()
            while (true) {
                ws()
                if (i >= s.length || s[i] == '}') break
                if (s[i] == ',') { i++; continue }
                if (s[i] != '"') break
                val keyEnd = end(i)
                if (keyEnd < 0) break
                val key = (parse(i, keyEnd) as? JsonPrimitive)?.content ?: break
                i = keyEnd
                ws()
                if (i >= s.length || s[i] != ':') break
                i++
                ws()
                val e = end(i)
                if (e >= 0) {
                    fields[key] = parse(i, e) ?: break
                    i = e
                } else {
                    // The cut falls in this value: keep the complete elements of an array, nothing of anything else.
                    if (i < s.length && s[i] == '[') fields[key] = cutArray()
                    break
                }
            }
            return JsonObject(fields)
        }
    }

    fun decode(e: JsonElement): LegacyState {
        val o = e as? JsonObject ?: return LegacyState()
        val dropped = linkedMapOf<String, Int>()
        fun drop(kind: ImportKind) { dropped[kind.name] = (dropped[kind.name] ?: 0) + 1 }
        /** The objects of the array [key], decoded by [f]; each element it cannot use is counted as dropped. */
        fun <T> records(key: String, kind: ImportKind, f: (JsonObject) -> T?): List<T> =
            ((o[key] as? JsonArray) ?: emptyList()).mapNotNull { el -> (el as? JsonObject)?.let(f) ?: null.also { drop(kind) } }
        return LegacyState(
            version = o.int("version") ?: 1,
            seeded = o.bool("seeded") ?: false,
            meals = records("meals", ImportKind.MEALS) { decodeMeal(it) { drop(ImportKind.MEAL_ITEMS) } },
            weights = records("weights", ImportKind.WEIGHTS) { w ->
                LegacyWeight(
                    w.str("id") ?: return@records null, w.long("at") ?: 0, w.str("day") ?: return@records null,
                    w.dbl("kg") ?: return@records null, w.dbl("waist"),
                )
            },
            food = records("food", ImportKind.FOOD, ::decodeFood),
            pending = records("pending", ImportKind.PENDING) { p ->
                LegacyPending(p.str("mealId") ?: return@records null, p.int("itemIndex") ?: 0, p.str("name") ?: "", p.int("counted") ?: 0, p.long("askedAt") ?: 0)
            },
            trainings = records("trainings", ImportKind.TRAININGS) { t ->
                LegacyTraining(t.str("id") ?: return@records null, t.long("at") ?: 0, t.str("day") ?: return@records null, t.int("rounds"))
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
            dropped = dropped,
        )
    }

    private fun decodeMeal(m: JsonObject, dropItem: () -> Unit): LegacyMeal? {
        val id = m.str("id") ?: return null
        val day = m.str("day") ?: return null
        return LegacyMeal(
            id = id, at = m.long("at") ?: 0, day = day, text = m.str("text").orEmpty(),
            items = ((m["items"] as? JsonArray) ?: emptyList()).mapNotNull { el ->
                val i = el as? JsonObject
                val name = i?.str("name") ?: return@mapNotNull null.also { dropItem() }
                MealItem(
                    name = name, portion = i.dbl("portion") ?: 1.0, kcal = i.int("kcal") ?: 0,
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
