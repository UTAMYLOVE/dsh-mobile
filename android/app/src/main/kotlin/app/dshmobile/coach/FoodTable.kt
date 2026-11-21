package app.dshmobile.coach

import java.text.Normalizer
import kotlin.math.ceil

/** The 常吃食物表: lookup by the owner's words, and the strict kcal of an item (spec §4, decision D4). */
object FoodTable {
    /** The five entries the owner gave (spec §4), exactly. */
    fun seed(now: Long): List<FoodEntry> = listOf(
        FoodEntry("給力盒子 南加州烤雞腿", listOf("南加州烤雞腿", "給力盒子烤雞腿"), 629, 51.0, null, null, fried = false, estimated = false, seeded = true, addedAt = now),
        FoodEntry("全家 紐奧良風味烤雞鮮蔬三明治", listOf("紐奧良風味烤雞鮮蔬三明治", "紐奧良烤雞三明治"), 205, null, null, null, fried = false, estimated = false, seeded = true, addedAt = now),
        FoodEntry("炸臭豆腐一盤", listOf("炸臭豆腐", "臭豆腐"), 565, null, null, null, fried = true, estimated = true, seeded = true, addedAt = now),
        FoodEntry("蚵仔煎一盤", listOf("蚵仔煎"), 516, null, null, null, fried = false, estimated = true, seeded = true, addedAt = now),
        FoodEntry("蚵仔麵線一碗", listOf("蚵仔麵線"), 415, null, null, null, fried = false, estimated = true, seeded = true, addedAt = now),
    )

    val FRIED_WORDS = listOf("炸", "酥", "鹽酥", "鹹酥", "天婦羅", "薯條", "雞塊", "雞排")

    private val PUNCT = Regex("[\\s·・、，,。.()（）「」]")
    private val TRAILING_QTY = Regex("(一|1)?(盤|碗|份|個|杯|盒|條|塊)$")
    private val LEADING = Regex("^(我吃了|吃了|一)")

    fun normalize(s: String): String {
        var t = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
        t = PUNCT.replace(t, "")
        t = TRAILING_QTY.replace(t, "")
        t = LEADING.replace(t, "")
        return t
    }

    private fun keys(e: FoodEntry): List<String> = (listOf(e.name) + e.aliases).map(::normalize).filter { it.isNotEmpty() }

    /** The table entry [name] means, or null when none or more than one could be meant. */
    fun lookup(table: List<FoodEntry>, name: String): FoodEntry? {
        val n = normalize(name)
        if (n.isEmpty()) return null
        val exact = table.filter { e -> keys(e).any { it == n } }
        if (exact.size == 1) return exact[0]
        if (exact.size > 1) return null
        // Containment: the longest key (at least 3 characters) wins, unless two entries tie.
        val hits = table.flatMap { e -> keys(e).filter { it.length >= 3 && (n.contains(it) || it.contains(n) && n.length >= 3) }.map { e to it.length } }
        if (hits.isEmpty()) return null
        val best = hits.maxOf { it.second }
        val top = hits.filter { it.second == best }.map { it.first }.distinct()
        return top.singleOrNull()
    }

    /** Entries named anywhere in [text] (the offline fallback). */
    fun named(table: List<FoodEntry>, text: String): List<FoodEntry> {
        val n = normalize(text)
        return table.filter { e -> keys(e).any { it.length >= 2 && n.contains(it) } }
    }

    fun isFried(name: String, modelSaysFried: Boolean, entry: FoodEntry?): Boolean =
        modelSaysFried || entry?.fried == true || FRIED_WORDS.any { name.contains(it) }

    /** A table hit, per portion, rounded up. */
    fun tableItem(entry: FoodEntry, said: String, portion: Double, modelSaysFried: Boolean): MealItem = MealItem(
        name = entry.name, portion = portion, kcal = ceil(entry.kcal * portion - 1e-9).toInt(),
        proteinG = entry.proteinG?.let { it * portion }, fatG = entry.fatG?.let { it * portion }, carbsG = entry.carbsG?.let { it * portion },
        fried = isFried(said, modelSaysFried, entry) || entry.fried, source = KcalSource.TABLE, tableEstimated = entry.estimated,
    )

    /** Strict estimate: the high end, times the portion, plus [marginPct] %, rounded up. Protein takes the low end (never over-credited). */
    fun estimateKcal(kcalHigh: Int, portion: Double, marginPct: Int): Int =
        ceil(kcalHigh * portion * (100 + marginPct) / 100.0 - 1e-9).toInt()

    fun estimateItem(name: String, portion: Double, fried: Boolean, kcalHigh: Int, proteinLow: Double?, marginPct: Int): MealItem = MealItem(
        name = name, portion = portion, kcal = estimateKcal(kcalHigh, portion, marginPct),
        proteinG = proteinLow?.let { it * portion }, fatG = null, carbsG = null,
        fried = isFried(name, fried, null), source = KcalSource.ESTIMATE, estimateHigh = kcalHigh,
    )
}
