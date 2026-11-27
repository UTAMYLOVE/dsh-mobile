package app.agency.core.rules

import app.agency.core.model.FoodEntry
import app.agency.core.model.KcalSource
import app.agency.core.model.MealItem
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

    /** The two foods plan v3 adds to his table (即食雞胸 by the convenience-store label; the soy milk is the label's). */
    fun v3Added(now: Long): List<FoodEntry> = listOf(
        FoodEntry("即食雞胸", listOf("即食雞胸肉", "超商雞胸", "7-11雞胸", "小七雞胸"), 117, 23.6, null, null, fried = false, estimated = false, seeded = true, addedAt = now),
        FoodEntry("統一高纖豆漿", listOf("高纖豆漿"), 193, 15.0, null, null, fried = false, estimated = false, seeded = true, addedAt = now),
    )

    private fun e(name: String, aliases: List<String>, kcal: Int, protein: Double, estimated: Boolean = false, now: Long) =
        FoodEntry(name, aliases, kcal, protein, null, null, fried = false, estimated = estimated, seeded = true, addedAt = now)

    /**
     * 「常吃的食物」 of the owner's latest design (2026-11-27): menu boards and labels; a range takes its high end (strict), 「約」
     * values are marked estimates. Names he reads have no Latin letters (the shop's own spelling is an alias).
     */
    fun usual(now: Long): List<FoodEntry> = listOf(
        e("給力盒子 椒鹽水煮雞胸", listOf("椒鹽水煮雞胸"), 605, 43.0, now = now),
        e("給力盒子 塔香烘烤雞胸", listOf("塔香烘烤雞胸"), 613, 43.0, now = now),
        e("給力盒子 泰式打拋雞胸", listOf("泰式打拋雞胸"), 596, 41.0, now = now),
        e("給力盒子 蒜泥醬豬肉片", listOf("蒜泥醬豬肉片"), 623, 38.0, now = now),
        e("給力盒子 十三香滷牛腱", listOf("十三香滷牛腱"), 573, 34.0, now = now),
        e("給力盒子 樹子汁蒸鱸魚", listOf("樹子汁蒸鱸魚"), 555, 34.0, now = now),
        e("給力盒子 鹽烤馬加魚", listOf("鹽烤馬加魚"), 594, 29.0, now = now),
        e("給力盒子 大厚切烤鮭魚", listOf("大厚切烤鮭魚"), 690, 44.0, now = now),
        e("給力盒子 爐烤鮮時蔬", listOf("爐烤鮮時蔬"), 510, 16.0, now = now),
        e("麥當勞 炭燒醬烤雞腿堡", listOf("炭燒醬烤雞腿堡"), 347, 22.0, now = now),
        e("麥當勞 香烙烤鷄腿堡", listOf("BLT香烙烤鷄腿堡", "香烙烤雞腿堡", "BLT香烙烤雞腿堡"), 464, 25.0, now = now),
        e("麥當勞 藜麥烤鷄沙拉", listOf("藜麥烤雞沙拉", "藜麥烤鷄沙拉"), 264, 22.0, now = now),
        e("麥當勞 大麥克", listOf("大麥克"), 600, 25.0, estimated = true, now = now),
        e("福樂 蛋白飲", listOf("福樂蛋白飲", "福樂高蛋白"), 209, 21.0, now = now),
        e("義美 高蛋白奶", listOf("義美高蛋白奶", "義美高蛋白"), 195, 20.0, now = now),
        e("低脂鮮奶", listOf("低脂牛奶"), 178, 12.8, now = now),
        e("茶葉蛋", listOf("茶葉蛋一顆"), 75, 7.0, estimated = true, now = now),
        e("煙燻燒烤雞腿", listOf("燒烤雞腿"), 244, 29.4, now = now),
        e("全家 雞肉便當", listOf("雞肉便當", "屏榮雞肉便當"), 481, 30.0, now = now),
    )

    /**
     * The table as plan v3 states it (T14): [seed] with 給力盒子 南加州烤雞腿 corrected to 714 kcal / 48 g (menu board), plus
     * [v3Added] and the owner's 「常吃的食物」 ([usual]). Lookups use his stored table first and these for anything he has not
     * stored ([withCurrent]).
     */
    fun current(now: Long): List<FoodEntry> = seed(now).map {
        if (it.name == GELI_CHICKEN) it.copy(kcal = 714, proteinG = 48.0) else it
    } + v3Added(now) + usual(now)

    /** His stored entries, then [current] for the names he has none of (a stored entry always wins). */
    fun withCurrent(stored: List<FoodEntry>): List<FoodEntry> {
        val have = stored.map { normalize(it.name) }.toSet()
        return stored + current(0).filter { normalize(it.name) !in have }
    }

    const val GELI_CHICKEN = "給力盒子 南加州烤雞腿"

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
        // Only "the owner's words contain a table key": a short generic word (三明治, 烤雞腿) never maps onto one shop's food.
        val hits = table.flatMap { e -> keys(e).filter { it.length >= 3 && n.contains(it) }.map { e to it.length } }
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
