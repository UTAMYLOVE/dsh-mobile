package app.dshmobile.coach

import app.agency.core.migrate.LegacyCoachJson
import app.agency.core.model.FoodEntry
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Hand-written JSON for the coach's files. Unknown or bad fields fall back, never throw (the decoders live in `:core`). */
internal object CoachJson {
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

    /** The decoders moved to `:core` (`LegacyCoachJson`), where the importer reads the same files the same way. */
    fun decode(e: JsonElement): CoachState = LegacyCoachJson.decode(e).let { s ->
        CoachState(
            version = s.version,
            seeded = s.seeded,
            meals = s.meals.map { m -> Meal(m.id, m.at, m.day, m.text, m.items, m.cheat, m.offPlan, m.verified, m.evidence, m.breaches) },
            weights = s.weights.map { WeightEntry(it.id, it.at, it.day, it.kg, it.waistCm) },
            food = s.food,
            pending = s.pending.map { PendingFood(it.mealId, it.itemIndex, it.name, it.countedKcal, it.askedAt) },
            trainings = s.trainings.map { TrainingEntry(it.id, it.at, it.day, it.rounds) },
            baseline = s.baseline?.let { b ->
                Baseline(
                    heightCm = b.heightCm, weightKg = b.weightKg, photo = b.photo, recording = b.recording,
                    pushups = b.pushups, plankSec = b.plankSec,
                    skipped = b.skipped.map { BaselineStep.valueOf(it.name) }.toSet(), step = b.step?.let { BaselineStep.valueOf(it.name) },
                )
            },
        )
    }

    fun encodeMessage(m: CoachMessage): String = buildJsonObject {
        put("id", m.id); put("at", m.at); put("who", m.who.name); put("text", m.text)
        m.kind?.let { put("kind", it) }
        m.role?.let { put("role", it.name) }
    }.toString()

    fun decodeMessage(e: JsonElement): CoachMessage? = LegacyCoachJson.decodeMessage(e)?.let { m ->
        CoachMessage(m.id, m.at, Speaker.valueOf(m.who.name), m.text, m.kind, m.role?.let { AgencyRole.valueOf(it.name) })
    }
}
