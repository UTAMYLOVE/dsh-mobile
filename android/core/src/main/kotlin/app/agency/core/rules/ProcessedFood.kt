package app.agency.core.rules

import app.agency.core.model.CompanyRules

/** 公司規章's banned list (plan v3 §5): the categories, each with the words that name it. */
enum class BannedCategory(val zh: String, val words: List<String>) {
    INSTANT_NOODLES("泡麵", listOf("泡麵", "速食麵", "杯麵", "碗麵", "科學麵", "王子麵")),
    SNACKS("餅乾洋芋片零食", listOf("餅乾", "洋芋片", "零食", "薯片", "爆米花", "蝦味先", "可樂果", "乖乖", "孔雀餅乾")),
    SWEET_BAKERY("蛋糕甜麵包甜甜圈", listOf("蛋糕", "甜麵包", "甜甜圈", "菠蘿麵包", "奶酥", "泡芙", "蛋塔", "鬆餅", "可頌", "布朗尼")),
    PROCESSED_MEAT("加工肉品", listOf("香腸", "熱狗", "火腿", "培根", "雞塊", "肉鬆", "貢丸", "魚丸", "甜不辣", "臘肉")),
    SUGARY_DRINKS("含糖飲料", listOf("含糖", "珍奶", "珍珠奶茶", "奶茶", "手搖", "可樂", "汽水", "果汁", "全糖", "半糖", "微糖", "冬瓜茶", "多多")),
    FRIED("炸物", listOf("炸", "酥", "鹽酥雞", "薯條", "雞排", "天婦羅", "排骨酥")),
    ;

    companion object {
        val KEYS: List<String> = entries.map { it.name }
    }
}

/**
 * Which banned categories a food name falls in (code, by words). The foods the plan names as his usual ones are never banned
 * (即食雞胸, 統一高纖豆漿, 給力盒子, 全家烤雞三明治, 麥當勞嫩煎雞腿堡／藜麥烤雞沙拉, the 21:00 swaps 福樂蛋白飲 and 義美高蛋白奶), and a
 * zero-calorie drink is not a sugary one
 * (it counts in its own weekly limit).
 */
object ProcessedFood {
    val ALLOWED = listOf(
        "即食雞胸", "雞胸", "高纖豆漿", "統一高纖豆漿", "給力盒子", "烤雞三明治", "紐奧良烤雞", "嫩煎雞腿堡", "藜麥烤雞沙拉", "藜麥烤鷄沙拉",
        // The 21:00 swaps (owner 2026-11-26): 福樂 has added sugar but is his allowed protein drink (2–3 a week).
        "福樂蛋白飲", "福樂 蛋白飲", "福樂高蛋白", "高蛋白奶",
    )
    private val ZERO = listOf("零卡", "無糖", "0卡", "零糖")

    fun categories(name: String): Set<BannedCategory> {
        if (ALLOWED.any { name.contains(it) }) return emptySet()
        val zero = ZERO.any { name.contains(it) }
        return BannedCategory.entries.filter { c -> (c != BannedCategory.SUGARY_DRINKS || !zero) && c.words.any { name.contains(it) } }.toSet()
    }

    /** The rules' banned keys; an empty list (rules written before T11) means the whole list of plan v3. */
    fun banned(rules: CompanyRules): Set<BannedCategory> =
        rules.banned.ifEmpty { BannedCategory.KEYS }.mapNotNull { k -> BannedCategory.entries.firstOrNull { it.name == k } }.toSet()

    /** The banned categories [name] breaks under [rules] (none inside a declared 休假餐: [cheat]). */
    fun breaches(name: String, rules: CompanyRules, cheat: Boolean = false): Set<BannedCategory> =
        if (cheat) emptySet() else categories(name) intersect banned(rules)
}
