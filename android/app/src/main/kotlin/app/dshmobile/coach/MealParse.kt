package app.dshmobile.coach

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * The model's only job on the way in: turn the owner's sentence into this structure. The code checks every field and does all
 * the arithmetic; the model's numbers are only an estimate range for food the table does not know.
 */
data class ParsedItem(
    val name: String,
    val portion: Double,
    val fried: Boolean,
    val kcalLow: Int?,
    val kcalHigh: Int?,
    val proteinLow: Double?,
    val proteinHigh: Double?,
)

data class FoodInfo(val name: String?, val kcal: Int?, val proteinG: Double?, val confirm: Boolean)

data class Parsed(
    val kind: String,
    val items: List<ParsedItem> = emptyList(),
    val weightKg: Double? = null,
    val waistCm: Double? = null,
    val foodInfo: FoodInfo? = null,
    val illness: Boolean = false,
)

object MealParse {
    val KINDS = setOf("meal", "craving", "weight", "food_info", "chat")

    val SYSTEM = """
你是資料整理員，不是教練。只輸出一個 JSON 物件，不要任何其他文字、不要程式碼框。
把對話裡「最後一句我說的話」整理成下面的格式：
{"kind":"meal 或 craving 或 weight 或 food_info 或 chat",
 "items":[{"name":"照我說的品名","portion":份數,"fried":是不是炸的,"kcal_low":你估的最低大卡,"kcal_high":你估的最高大卡,"protein_low":最低蛋白質克數,"protein_high":最高蛋白質克數}],
 "weight_kg":體重或null,"waist_cm":腰圍或null,
 "food_info":{"name":品名或null,"kcal":標示上的大卡或null,"protein_g":標示上的蛋白質或null,"confirm":我是不是只說「對」「就這份」},
 "illness":我是不是說身體不舒服、頭暈之類}
kind 的意思：
- meal：我說我已經吃了、喝了什麼。
- craving：我說我想吃、要點、可不可以吃什麼（還沒吃）。
- weight：我報體重或腰圍。
- food_info：我在補上一個食物的包裝標示、份量，或確認你上次的估算。
- chat：其他所有話（找藉口、心情、聊天、問問題）。
規則：品名照我講的寫，不要改名；份數沒講就是 1；大卡範圍照一般台灣外食的份量估，寧可估高；不知道就給寬一點的範圍。
不要加總、不要算剩多少、不要給建議。
""".trimIndent()

    const val RETRY = "只輸出 JSON。"

    /** The JSON object in [text] (the model may wrap it in prose), validated; null when unusable. */
    fun parse(text: String): Parsed? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = runCatching { Json.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject }.getOrNull() ?: return null
        val kind = (o["kind"] as? JsonPrimitive)?.content?.trim()?.takeIf { it in KINDS } ?: return null
        val items = (o["items"] as? JsonArray).orEmpty().take(12).mapNotNull { e ->
            val i = e as? JsonObject ?: return@mapNotNull null
            val name = (i["name"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.length in 1..40 } ?: return@mapNotNull null
            val portion = num(i["portion"])?.takeIf { it in 0.1..10.0 } ?: 1.0
            var lo = num(i["kcal_low"])?.takeIf { it in 0.0..5000.0 }?.toInt()
            var hi = num(i["kcal_high"])?.takeIf { it in 0.0..5000.0 }?.toInt()
            if (lo != null && hi != null && lo > hi) { val t = lo; lo = hi; hi = t }
            if (hi == null) hi = lo
            var plo = num(i["protein_low"])?.takeIf { it in 0.0..500.0 }
            var phi = num(i["protein_high"])?.takeIf { it in 0.0..500.0 }
            if (plo != null && phi != null && plo > phi) { val t = plo; plo = phi; phi = t }
            ParsedItem(name, portion, bool(i["fried"]) ?: false, lo, hi, plo, phi)
        }
        val fi = (o["food_info"] as? JsonObject)?.let { f ->
            FoodInfo(
                name = (f["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.length in 1..40 },
                kcal = num(f["kcal"])?.takeIf { it in 1.0..5000.0 }?.toInt(),
                proteinG = num(f["protein_g"])?.takeIf { it in 0.0..500.0 },
                confirm = bool(f["confirm"]) ?: false,
            )
        }
        return Parsed(
            kind = kind,
            items = items,
            weightKg = num(o["weight_kg"])?.takeIf { it in 30.0..250.0 },
            waistCm = num(o["waist_cm"])?.takeIf { it in 40.0..200.0 },
            foodInfo = fi,
            illness = bool(o["illness"]) ?: false,
        )
    }

    private fun num(e: Any?): Double? = (e as? JsonPrimitive)?.let { p -> p.doubleOrNull ?: p.content.toDoubleOrNull() }
    private fun bool(e: Any?): Boolean? = (e as? JsonPrimitive)?.booleanOrNull
}
