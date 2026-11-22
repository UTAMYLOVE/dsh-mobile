package app.agency.core.structured

import app.agency.core.guard.CoachNumbers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** One food he named. kcal and protein are the model's estimate range, used only for food the table does not know. */
data class IntakeItem(
    val name: String,
    val portion: Double = 1.0,
    val fried: Boolean = false,
    val processed: Boolean = false,
    val zeroCal: Boolean = false,
    val kcalLow: Int? = null,
    val kcalHigh: Int? = null,
    val proteinLow: Double? = null,
    val proteinHigh: Double? = null,
)

/** Numbers from a package label or a menu he typed. */
data class IntakeLabel(val name: String?, val kcal: Int?, val proteinG: Double?)

data class IntakeTraining(val kind: String, val rounds: Int?, val lastRoundExtra: Int?)

/**
 * His sentence as structure (the model's only job on the way in). Every field is checked by code: ranges as the old
 * `MealParse`, and any figure that must be his (weight, waist, label numbers, rounds, extra reps, condition score) is kept only
 * when he wrote that number himself. The kind is not final either: the handlers apply the word rules (craving, training,
 * negation) on top (T14).
 */
data class Intake(
    val kind: String,
    val items: List<IntakeItem> = emptyList(),
    val weightKg: Double? = null,
    val waistCm: Double? = null,
    val label: IntakeLabel? = null,
    val waterHalf: Int? = null,
    val training: IntakeTraining? = null,
    val conditionScore: Int? = null,
    val illness: Boolean = false,
) {
    companion object {
        val KINDS = listOf(
            "meal", "craving", "weight", "label", "training", "water", "sign_in", "sign_out", "condition", "rest_day",
            "cheat_declare", "hungry_morning", "unwell", "mood", "discuss", "chat",
        )
        val TRAINING_KINDS = listOf("core7", "strength", "stretch", "walk")
        const val MAX_ITEMS = 12

        /** The data clerk's prompt (no persona, no facts): the word json and an example are there for JSON mode. */
        val SYSTEM = """
你是資料整理員，不是教練。只輸出一個 json 物件，不要任何其他文字、不要程式碼框。
把對話裡「最後一句我說的話」整理成這個格式（每個欄位都要有，沒有就填 null、false 或空陣列）：
{"kind":"${KINDS.joinToString("|")}",
 "items":[{"name":"照我說的品名","portion":1,"fried":false,"processed":false,"zero_cal":false,"kcal_low":300,"kcal_high":450,"protein_low":20,"protein_high":30}],
 "weight_kg":null,"waist_cm":null,
 "label":{"name":"品名","kcal":117,"protein_g":24},
 "water_half":null,
 "training":{"kind":"${TRAINING_KINDS.joinToString("|")}","rounds":3,"last_round_extra":2},
 "condition_score":null,
 "illness":false}
kind 的意思：meal 我說已經吃了、喝了什麼；craving 我想吃、要點、可不可以吃（還沒吃）；weight 報體重或腰圍；label 補包裝標示或菜單上的數字；
training 做完核心、肌力、伸展或快走；water 喝了水（一壺是 2 個半壺）；sign_in 起床簽到；sign_out 要睡了；condition 早上狀態打分（一到五）；
rest_day 要休息一天；cheat_declare 宣告休假餐；hungry_morning 早上很餓；unwell 身體不舒服；mood 講心情；discuss 覺得計畫不適合想討論；chat 其他。
規則：品名照我講的寫，不要改名；份數沒講就是 1；大卡範圍照一般台灣外食的份量估，寧可估高；不知道就給寬一點的範圍。
體重、腰圍、標示數字、輪數、狀態分數只寫我自己說出的數字，沒說就是 null。不要加總、不要算剩多少、不要給建議。
""".trimIndent()

        /** Every top-level key the clerk may leave out, with what it means then. */
        val DEFAULTS: Map<String, JsonElement> = mapOf(
            "items" to JsonArray(emptyList()), "weight_kg" to JsonNull, "waist_cm" to JsonNull, "label" to JsonNull,
            "water_half" to JsonNull, "training" to JsonNull, "condition_score" to JsonNull, "illness" to JsonPrimitive(false),
        )

        val SCHEMA: JsonObject = with(JsonSchema) {
            obj(
                "kind" to enum(KINDS),
                "items" to arr(
                    obj(
                        "name" to str(), "portion" to num(), "fried" to bool(), "processed" to bool(), "zero_cal" to bool(),
                        "kcal_low" to nullable(int()), "kcal_high" to nullable(int()),
                        "protein_low" to nullable(num()), "protein_high" to nullable(num()),
                    ),
                ),
                "weight_kg" to nullable(num()),
                "waist_cm" to nullable(num()),
                "label" to nullable(obj("name" to nullable(str()), "kcal" to nullable(int()), "protein_g" to nullable(num()))),
                "water_half" to nullable(int()),
                "training" to nullable(obj("kind" to enum(TRAINING_KINDS), "rounds" to nullable(int()), "last_round_extra" to nullable(int()))),
                "condition_score" to nullable(int()),
                "illness" to bool(),
            )
        }

        /** The spec for one sentence of his: [ownerText] decides which of his figures survive. */
        fun spec(ownerText: String): StructuredSpec<Intake> = object : StructuredSpec<Intake> {
            override val schema = SCHEMA
            override val defaults = DEFAULTS
            override fun decode(o: JsonObject): Decoded<Intake> = Intake.decode(o, ownerText)
        }

        fun decode(o: JsonObject, ownerText: String): Decoded<Intake> {
            val v = mutableListOf<Violation>()
            val kind = str(o["kind"])?.trim()
            if (kind == null || kind !in KINDS) v += Violation("kind", "必須是其中一個：${KINDS.joinToString("、")}")
            val items = (o["items"] as? JsonArray).orEmpty().take(MAX_ITEMS).mapIndexedNotNull { idx, e ->
                val i = e as? JsonObject
                val name = i?.let { str(it["name"])?.trim() }
                if (i == null || name == null || name.length !in 1..40) {
                    v += Violation("items[$idx].name", "品名要照他說的寫，一到四十個字")
                    return@mapIndexedNotNull null
                }
                var lo = num(i["kcal_low"])?.takeIf { it in 0.0..5000.0 }?.toInt()
                var hi = num(i["kcal_high"])?.takeIf { it in 0.0..5000.0 }?.toInt()
                if (lo != null && hi != null && lo > hi) lo = hi.also { hi = lo }
                if (hi == null) hi = lo
                var plo = num(i["protein_low"])?.takeIf { it in 0.0..500.0 }
                var phi = num(i["protein_high"])?.takeIf { it in 0.0..500.0 }
                if (plo != null && phi != null && plo > phi) plo = phi.also { phi = plo }
                IntakeItem(
                    name = name,
                    portion = num(i["portion"])?.takeIf { it in 0.1..10.0 } ?: 1.0,
                    fried = bool(i["fried"]) ?: false,
                    processed = bool(i["processed"]) ?: false,
                    zeroCal = bool(i["zero_cal"]) ?: false,
                    kcalLow = lo, kcalHigh = hi, proteinLow = plo, proteinHigh = phi,
                )
            }
            val training = (o["training"] as? JsonObject)?.let { t ->
                val tk = str(t["kind"])?.takeIf { it in TRAINING_KINDS }
                if (tk == null) {
                    v += Violation("training.kind", "必須是其中一個：${TRAINING_KINDS.joinToString("、")}")
                    null
                } else {
                    IntakeTraining(
                        tk,
                        rounds = owners(num(t["rounds"])?.takeIf { it in 1.0..20.0 }, ownerText)?.toInt(),
                        lastRoundExtra = owners(num(t["last_round_extra"])?.takeIf { it in 0.0..50.0 }, ownerText)?.toInt(),
                    )
                }
            }
            val label = (o["label"] as? JsonObject)?.let { l ->
                IntakeLabel(
                    name = str(l["name"])?.trim()?.takeIf { it.length in 1..40 },
                    kcal = owners(num(l["kcal"])?.takeIf { it in 1.0..5000.0 }, ownerText)?.toInt(),
                    proteinG = owners(num(l["protein_g"])?.takeIf { it in 0.0..500.0 }, ownerText),
                )
            }
            if (v.isNotEmpty()) return Decoded(null, v)
            return Decoded(
                Intake(
                    kind = kind!!,
                    items = items,
                    weightKg = owners(num(o["weight_kg"])?.takeIf { it in 30.0..250.0 }, ownerText),
                    waistCm = owners(num(o["waist_cm"])?.takeIf { it in 40.0..200.0 }, ownerText),
                    label = label,
                    waterHalf = num(o["water_half"])?.takeIf { it in 1.0..4.0 && it % 1.0 == 0.0 }?.toInt(),
                    training = training,
                    conditionScore = owners(num(o["condition_score"])?.takeIf { it in 1.0..5.0 && it % 1.0 == 0.0 }, ownerText)?.toInt(),
                    illness = bool(o["illness"]) ?: false,
                ),
            )
        }

        private fun owners(x: Double?, ownerText: String): Double? = CoachNumbers.ownersOnly(x, ownerText)
        private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { p -> p.doubleOrNull ?: p.content.toDoubleOrNull() }
        private fun bool(e: JsonElement?): Boolean? = (e as? JsonPrimitive)?.booleanOrNull
    }
}
