package app.dshmobile.coach

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Hand-written JSON for the coach's files (the app has no serialization plugin). Unknown or bad fields fall back, never throw. */
internal object CoachJson {
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.dbl(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.arr(k: String): List<JsonObject> = (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    fun encode(s: CoachState): JsonObject = buildJsonObject {
        put("version", s.version)
        put("seeded", s.seeded)
        put("meals", buildJsonArray { s.meals.forEach { add(meal(it)) } })
        put("weights", buildJsonArray {
            s.weights.forEach { w ->
                add(buildJsonObject {
                    put("id", w.id); put("at", w.at); put("day", w.day); put("kg", w.kg)
                    w.waistCm?.let { put("waist", it) }
                })
            }
        })
        put("food", buildJsonArray { s.food.forEach { add(food(it)) } })
        put("pending", buildJsonArray {
            s.pending.forEach { p ->
                add(buildJsonObject {
                    put("mealId", p.mealId); put("itemIndex", p.itemIndex); put("name", p.name); put("counted", p.countedKcal); put("askedAt", p.askedAt)
                })
            }
        })
        put("trainings", buildJsonArray {
            s.trainings.forEach { t ->
                add(buildJsonObject { put("id", t.id); put("at", t.at); put("day", t.day); t.rounds?.let { put("rounds", it) } })
            }
        })
        s.baseline?.let { b ->
            put("baseline", buildJsonObject {
                b.heightCm?.let { put("height", it) }; b.weightKg?.let { put("weight", it) }
                b.photo?.let { put("photo", it) }; b.recording?.let { put("recording", it) }
                b.pushups?.let { put("pushups", it) }; b.plankSec?.let { put("plank", it) }
                put("skipped", buildJsonArray { b.skipped.forEach { add(JsonPrimitive(it.name)) } })
                b.step?.let { put("step", it.name) }
            })
        }
    }

    private fun meal(m: Meal) = buildJsonObject {
        put("id", m.id); put("at", m.at); put("day", m.day); put("text", m.text)
        put("cheat", m.cheat); put("offPlan", m.offPlan); put("verified", m.verified)
        put("breaches", buildJsonArray { m.breaches.forEach { add(JsonPrimitive(it)) } })
        m.evidence?.let { put("evidence", it) }
        put("items", buildJsonArray {
            m.items.forEach { i ->
                add(buildJsonObject {
                    put("name", i.name); put("portion", i.portion); put("kcal", i.kcal)
                    i.proteinG?.let { put("protein", it) }; i.fatG?.let { put("fat", it) }; i.carbsG?.let { put("carbs", it) }
                    put("fried", i.fried); put("source", i.source.name); put("tableEstimated", i.tableEstimated)
                    i.estimateHigh?.let { put("estimateHigh", it) }
                    if (i.zeroCal) put("zeroCal", true)
                })
            }
        })
    }

    private fun food(f: FoodEntry) = buildJsonObject {
        put("name", f.name)
        put("aliases", buildJsonArray { f.aliases.forEach { add(JsonPrimitive(it)) } })
        put("kcal", f.kcal)
        f.proteinG?.let { put("protein", it) }; f.fatG?.let { put("fat", it) }; f.carbsG?.let { put("carbs", it) }
        put("fried", f.fried); put("estimated", f.estimated); put("seeded", f.seeded); put("addedAt", f.addedAt)
    }

    fun decode(e: JsonElement): CoachState {
        val o = e as? JsonObject ?: return CoachState()
        return CoachState(
            version = o.int("version") ?: 1,
            seeded = o.bool("seeded") ?: false,
            meals = o.arr("meals").mapNotNull(::decodeMeal),
            weights = o.arr("weights").mapNotNull { w ->
                WeightEntry(w.str("id") ?: return@mapNotNull null, w.long("at") ?: 0, w.str("day") ?: return@mapNotNull null, w.dbl("kg") ?: return@mapNotNull null, w.dbl("waist"))
            },
            food = o.arr("food").mapNotNull(::decodeFood),
            pending = o.arr("pending").mapNotNull { p ->
                PendingFood(p.str("mealId") ?: return@mapNotNull null, p.int("itemIndex") ?: 0, p.str("name") ?: "", p.int("counted") ?: 0, p.long("askedAt") ?: 0)
            },
            trainings = o.arr("trainings").mapNotNull { t ->
                TrainingEntry(t.str("id") ?: return@mapNotNull null, t.long("at") ?: 0, t.str("day") ?: return@mapNotNull null, t.int("rounds"))
            },
            baseline = (o["baseline"] as? JsonObject)?.let { b ->
                Baseline(
                    heightCm = b.dbl("height"), weightKg = b.dbl("weight"), photo = b.str("photo"), recording = b.str("recording"),
                    pushups = b.int("pushups"), plankSec = b.int("plank"),
                    skipped = (b["skipped"] as? JsonArray)?.mapNotNull { e ->
                        (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { n -> runCatching { BaselineStep.valueOf(n) }.getOrNull() }
                    }.orEmpty().toSet(),
                    step = b.str("step")?.let { n -> runCatching { BaselineStep.valueOf(n) }.getOrNull() },
                )
            },
        )
    }

    private fun decodeMeal(m: JsonObject): Meal? {
        val id = m.str("id") ?: return null
        val day = m.str("day") ?: return null
        return Meal(
        id = id, at = m.long("at") ?: 0, day = day, text = m.str("text").orEmpty(),
        items = m.arr("items").mapNotNull { i ->
            MealItem(
                name = i.str("name") ?: return@mapNotNull null, portion = i.dbl("portion") ?: 1.0, kcal = i.int("kcal") ?: 0,
                proteinG = i.dbl("protein"), fatG = i.dbl("fat"), carbsG = i.dbl("carbs"), fried = i.bool("fried") ?: false,
                source = runCatching { KcalSource.valueOf(i.str("source").orEmpty()) }.getOrDefault(KcalSource.ESTIMATE),
                tableEstimated = i.bool("tableEstimated") ?: false, estimateHigh = i.int("estimateHigh"), zeroCal = i.bool("zeroCal") ?: false,
            )
        },
        cheat = m.bool("cheat") ?: false, offPlan = m.bool("offPlan") ?: false, verified = m.bool("verified") ?: false, evidence = m.str("evidence"),
        // A meal saved before breaches existed (v0.8.3) could only be off plan for fried food.
        breaches = (m["breaches"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?: if (m.bool("offPlan") == true) listOf(Breach.FRIED.name) else emptyList(),
    )
    }

    private fun decodeFood(f: JsonObject): FoodEntry? {
        val name = f.str("name") ?: return null
        val kcal = f.int("kcal") ?: return null
        return FoodEntry(
        name = name,
        aliases = (f["aliases"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty(),
        kcal = kcal, proteinG = f.dbl("protein"), fatG = f.dbl("fat"), carbsG = f.dbl("carbs"),
        fried = f.bool("fried") ?: false, estimated = f.bool("estimated") ?: false, seeded = f.bool("seeded") ?: false, addedAt = f.long("addedAt") ?: 0,
    )
    }

    fun encodeMessage(m: CoachMessage): String = buildJsonObject {
        put("id", m.id); put("at", m.at); put("who", m.who.name); put("text", m.text)
        m.kind?.let { put("kind", it) }
        m.role?.let { put("role", it.name) }
    }.toString()

    fun decodeMessage(e: JsonElement): CoachMessage? {
        val o = e as? JsonObject ?: return null
        val who = runCatching { Speaker.valueOf(o.str("who").orEmpty()) }.getOrNull() ?: return null
        return CoachMessage(
            o.str("id") ?: return null, o.long("at") ?: 0, who, o.str("text") ?: return null, o.str("kind"),
            o.str("role")?.let { r -> runCatching { AgencyRole.valueOf(r) }.getOrNull() },
        )
    }
}
