package app.agency.core.model

/** Where an item's kcal came from: the package label, the food table, or an estimate (shown 標示／食物表／估算). */
enum class KcalSource { LABEL, TABLE, ESTIMATE }

data class MealItem(
    val name: String,
    val portion: Double,
    val kcal: Int,
    val proteinG: Double?,
    val fatG: Double?,
    val carbsG: Double?,
    val fried: Boolean,
    val source: KcalSource,
    /** A table hit whose table entry is itself an estimate. */
    val tableEstimated: Boolean = false,
    /** The model's high end before the margin (estimates only). */
    val estimateHigh: Int? = null,
    /** A zero-calorie drink (公司規章: only a few a week). */
    val zeroCal: Boolean = false,
    /** Processed food (a banned category key applies; see 公司規章). */
    val processed: Boolean = false,
)

data class FoodEntry(
    val name: String,
    val aliases: List<String>,
    val kcal: Int,
    val proteinG: Double?,
    val fatG: Double?,
    val carbsG: Double?,
    val fried: Boolean,
    val estimated: Boolean,
    val seeded: Boolean,
    val addedAt: Long,
    val processed: Boolean = false,
    val updatedAt: Long = addedAt,
)
