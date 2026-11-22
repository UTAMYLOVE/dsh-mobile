package app.agency.core.brain.handlers

import app.agency.core.model.KcalSource
import app.agency.core.model.Meal
import app.agency.core.model.MealItem
import app.agency.core.rules.Breaches
import app.agency.core.schedule.DayPlan
import app.agency.core.schedule.SlotKey
import app.agency.core.schedule.SlotKind
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The word rules on top of the data clerk's kind (ported from the coach, arch 1.4 Intake): 「想吃…」 without 「吃了」 is a
 * craving whatever the clerk says, 「還沒吃」 is no meal, and a price he wrote (「120 元」) is his own number.
 */
object MealWords {
    val CRAVING = listOf("想吃", "要吃", "可以吃", "能吃", "想點", "要點", "可不可以", "能不能", "想喝", "要喝")
    val ATE = listOf("吃了", "喝了", "吃完", "剛吃", "吃過", "喝完")
    private val NOT_EATEN = Regex("還沒(?:吃|喝)|沒有?(?:吃|喝)(?!完)|不(?:吃|喝)了")
    private val PRICE = Regex("([0-9]{1,5})\\s*(?:元|塊)")

    fun isCraving(text: String): Boolean = CRAVING.any { text.contains(it) } && ATE.none { text.contains(it) }

    /** 「我還沒吃」「今天沒吃午餐」: not a report of a meal (「吃了一半沒吃完」 is). */
    fun notEaten(text: String): Boolean = NOT_EATEN.containsMatchIn(text) && ATE.none { text.contains(it) }

    /** The clerk's [kind] with the word rules on top: a craving is no meal, and nor is 「還沒吃」. */
    fun kindOf(kind: String, text: String): String = when {
        kind == "meal" && isCraving(text) -> "craving"
        kind == "meal" && notEaten(text) -> "chat"
        else -> kind
    }

    /** The price he wrote (NT$), when he wrote one. */
    fun price(text: String): Int? = PRICE.find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..20_000 }

    fun portion(p: Double): String {
        val b = BigDecimal(p).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros()
        return if (b.scale() <= 0) b.setScale(0).toPlainString() else b.toPlainString()
    }

    private val UNIT = Regex("(?:一|1)?(盤|碗|份|個|杯|盒|條|塊|包|瓶)$")

    /** 「蚵仔麵線一碗」 × 2 → 「蚵仔麵線 2碗」: the table name's own unit, never 「一碗 2份」. */
    fun amount(name: String, p: Double): String {
        val m = UNIT.find(name)
        return if (m != null && m.range.first > 0) "${name.substring(0, m.range.first)} ${portion(p)}${m.groupValues[1]}" else "$name ${portion(p)}份"
    }

    fun marginWords(marginPct: Int): String = when (marginPct) {
        0 -> "取高值"
        10 -> "取高值加一成"
        else -> "取高值加 $marginPct%"
    }

    /** Where an item's kcal came from, as he reads it. */
    fun source(i: MealItem, marginPct: Int): String = when {
        i.zeroCal && i.kcal == 0 -> "零卡飲料"
        i.source == KcalSource.LABEL -> "標示"
        i.source == KcalSource.TABLE -> if (i.tableEstimated) "食物表・估算" else "食物表"
        else -> "估算，${marginWords(marginPct)}"
    }

    fun itemLine(i: MealItem, marginPct: Int): String = "記下：${amount(i.name, i.portion)} ${i.kcal} 大卡（${source(i, marginPct)}）。"
}

/**
 * Which meal slot a report belongs to (arch T14 「the nearest open meal slot, +24 h after midnight」), pure. His own words come
 * first (午餐、晚餐、跑單…; 宵夜 is no slot); otherwise the latest meal slot of today that is due (or due within an hour) and
 * not reported yet; otherwise a part of the sitting he reported in the last
 * [Breaches.SITTING_MINUTES] minutes; otherwise none (an extra, 加餐). A late report still finds its slot: lunch reported at 15:00
 * is lunch, not dinner.
 */
object MealSlots {
    const val AHEAD_MIN = 60

    private val WORDS: List<Pair<Regex, (DayPlan) -> SlotKey?>> = listOf(
        Regex("宵夜|消夜") to { _ -> null },
        Regex("跑單|路上|騎車") to { p -> SlotKey.RIDE_MEAL.takeIf { p.slot(it) != null } },
        Regex("晚餐|晚飯") to { p -> SlotKey.DINNER.takeIf { p.slot(it) != null } },
        Regex("午餐|中餐|午飯|中午|第一餐") to { p -> p.firstMeal.takeIf { p.slot(it) != null } },
        Regex("早餐|早上") to { p -> SlotKey.MORNING_SOY.takeIf { p.slot(it) != null } },
    )

    /** The slot his words name: (true, key) — key null for 宵夜 or a slot today does not have; (false, null) when none. */
    fun named(plan: DayPlan, text: String): Pair<Boolean, SlotKey?> {
        for ((re, f) in WORDS) if (re.containsMatchIn(text)) return true to f(plan)
        return false to null
    }

    fun pick(plan: DayPlan, text: String, minute: Int, meals: List<Meal>, at: Long): SlotKey? {
        named(plan, text).let { (said, key) -> if (said) return key }
        val slots = plan.slots.filter { it.key.kind == SlotKind.MEAL }
        val eaten = meals.mapNotNull { SlotKey.parse(it.slotKey) }.toSet()
        val open = slots.filter { it.key !in eaten }
        open.filter { it.minute <= minute + AHEAD_MIN }.maxByOrNull { it.minute }?.let { return it.key }
        return meals.filter { at - it.at in 0..Breaches.SITTING_MINUTES * 60_000 }.maxByOrNull { it.at }?.slotKey?.let(SlotKey::parse)
    }

    /** The meal slot a craving is about: his words, else the one not reported yet nearest in time (past or coming), else none. */
    fun upcoming(plan: DayPlan, text: String, minute: Int, meals: List<Meal>): SlotKey? {
        named(plan, text).let { (said, key) -> if (said) return key }
        val eaten = meals.mapNotNull { SlotKey.parse(it.slotKey) }.toSet()
        return plan.slots.filter { it.key.kind == SlotKind.MEAL && it.key !in eaten }.minByOrNull { kotlin.math.abs(it.minute - minute) }?.key
    }
}
