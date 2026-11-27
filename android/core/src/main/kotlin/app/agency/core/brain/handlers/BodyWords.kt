package app.agency.core.brain.handlers

import app.agency.core.engine.IllKind

/**
 * His words about his body, read by code (agency-links §3b: 「減量是程式判斷，經紀人不能自己答應」). Only 生病 (fever, cold,
 * stomach, pain) or 受傷 can lighten training; 累、痠、沒睡好、不想 cannot, however it is said.
 */
object BodyWords {
    private val SICK = listOf(
        "發燒", "發高燒", "感冒", "流鼻水", "鼻塞", "喉嚨痛", "咳嗽", "拉肚子", "腹瀉", "腸胃炎", "腸胃不舒服", "胃痛", "肚子痛",
        "想吐", "吐了", "頭痛", "偏頭痛", "頭暈", "生病了", "生病", "病了", "腸胃",
    )
    /** Owner 2026-11-26: a sharp pain or joint pain counts as an injury, and he stops at once (soreness never does). */
    val PAIN = listOf("刺痛", "關節痛", "關節很痛", "手腕痛", "肩膀刺痛", "膝蓋刺痛", "下背刺痛")
    private val INJURY = listOf("受傷", "扭到", "拉傷", "跌倒", "摔車", "骨折", "撞到", "割到", "燙到", "破皮", "腳踝痛", "膝蓋痛") + PAIN

    private val UNCLEAR = listOf("不舒服", "身體怪怪", "人不太好", "身體不太好")
    private val NEGATION = Regex("(?:沒有|沒|不是|不會|沒在)$")

    private val MOOD = listOf("心情不好", "難過", "低落", "沮喪", "好煩", "很煩", "壓力好大", "壓力很大", "想哭", "崩潰", "憂鬱", "焦慮")

    /** Asking to do less (no illness word): 「今天照表」. */
    private val LESS = Regex(
        "少做|少練|少一輪|不想練|不想做|不要練|不練了|今天不練|可以不練|可以不要|可不可以不要|能不能不要|減量|減少訓練|跳過訓練|" +
            "偷懶一天|放假一天|休息一天不練|不做肌力|不做核心|肌力不做|核心不做|兩輪就好|做一輪就好|做兩輪",
    )
    private val TRAINING = Regex("練|訓練|肌力|核心|伸展|輪|運動|做")

    private fun found(text: String, words: List<String>): String? = words.sortedByDescending { it.length }.firstOrNull { w ->
        var i = text.indexOf(w)
        var ok = false
        while (i >= 0 && !ok) {
            ok = !NEGATION.containsMatchIn(text.substring(maxOf(0, i - 3), i))
            i = text.indexOf(w, i + 1)
        }
        ok
    }

    /** 受傷 or 生病 (with the word that said it), an unclear 「不舒服」, or null. A negated word (「沒有發燒」) does not count. */
    fun illness(text: String): Pair<IllKind, String>? {
        found(text, INJURY)?.let { return IllKind.INJURY to it }
        found(text, SICK)?.let { return IllKind.SICK to it }
        found(text, UNCLEAR)?.let { return IllKind.UNKNOWN to it }
        return null
    }

    /** The word was a sharp or joint pain: stop now. */
    fun isPain(word: String): Boolean = word in PAIN

    private val SLEEPY = listOf("好睏", "很睏", "超睏", "睏死", "好想睡", "很想睡", "想睡覺", "想睡", "愛睏", "打瞌睡", "眼睛快閉上", "快睡著")

    /** He is sleepy (「好睏」「想睡」; not 「我要睡了」, the sign-out). */
    fun sleepy(text: String): Boolean = found(text, SLEEPY) != null

    fun moodLow(text: String): Boolean = found(text, MOOD) != null

    /** He asks to do less training. */
    fun asksLess(text: String): Boolean = LESS.containsMatchIn(text) && (TRAINING.containsMatchIn(text) || text.contains("減量"))
}
