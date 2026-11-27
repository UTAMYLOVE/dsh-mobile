package app.agency.core.engine

import kotlinx.serialization.Serializable

/** The five 月評 scores (定稿 §10, each 1–5). */
enum class EvalItem(val zh: String) { VOCAL("聲樂"), DANCE("舞蹈"), VISUAL("視覺表現"), STAR("明星潛力"), ATTITUDE("態度") }

/** Who gave a score: code computed it, the image model suggested it (with its basis), or he scored himself. */
enum class ScoreBy(val zh: String) { CODE("程式算"), MODEL("人工智慧建議"), SELF("自評") }

@Serializable
data class EvalScore(val item: EvalItem, val score: Int, val by: ScoreBy, val basis: String = "")

/**
 * One 月評 as kept in its record (the 月評 row's detail): the hand-ins (media ids stay on the phone), his numbers, the scores, and
 * the month's personal task it set. [month] is `yyyy-MM`.
 */
@Serializable
data class MonthlyResult(
    val month: String,
    val song: String? = null,
    val dance: String? = null,
    val rotating: String? = null,
    val rotatingKind: String? = null,
    val bodyPhoto: String? = null,
    val weightKg: Double? = null,
    val waistCm: Double? = null,
    val pushups: Int? = null,
    val plankSec: Int? = null,
    val scores: List<EvalScore> = emptyList(),
    val task: String? = null,
) {
    fun score(item: EvalItem): Int? = scores.firstOrNull { it.item == item }?.score
    val total: Int get() = scores.sumOf { it.score }
}

/** 每週評鑑 as kept: this week's song and dance, each one take (no redo). */
@Serializable
data class WeeklyResult(val song: String? = null, val dance: String? = null)

/**
 * 月評 by code (定稿 §10; refs M §8): what can be computed is computed — 態度 from the month's completion and breaches, 聲樂 from
 * the pitch analysis of the solo song when there is one — the rest is the image model's suggestion with its basis, or his own
 * score. Compared with last month; two months in a row without progress → the manager's 面談. The month's one personal task
 * is the weakest item (10 minutes a day).
 */
object MonthlyEval {
    /** 態度: the month's completion share (0–100) and its breaches. */
    fun attitude(completionPct: Int, breaches: Int): Int {
        val base = when {
            completionPct >= 95 -> 5
            completionPct >= 90 -> 4
            completionPct >= 80 -> 3
            completionPct >= 65 -> 2
            else -> 1
        }
        return (base - if (breaches >= 3) 1 else 0).coerceIn(1, 5)
    }

    /** 聲樂 from the solo song's 音準 share (0–100, code's analysis). */
    fun vocal(inTunePct: Int): Int = when {
        inTunePct >= 90 -> 5
        inTunePct >= 80 -> 4
        inTunePct >= 65 -> 3
        inTunePct >= 50 -> 2
        else -> 1
    }

    /** Per item: this month minus last month (only items both have). */
    fun delta(now: MonthlyResult, before: MonthlyResult): Map<EvalItem, Int> =
        EvalItem.entries.mapNotNull { i -> val a = now.score(i); val b = before.score(i); if (a != null && b != null) i to a - b else null }.toMap()

    /** Some item went up. */
    fun improved(now: MonthlyResult, before: MonthlyResult): Boolean = delta(now, before).values.any { it > 0 }

    /** [history] oldest first, this month last: the last two comparisons both without progress → 面談. */
    fun interviewDue(history: List<MonthlyResult>): Boolean {
        if (history.size < 3) return false
        val (a, b, c) = history.takeLast(3)
        return !improved(b, a) && !improved(c, b)
    }

    /** The weakest item (the lowest score; ties go to the first in [EvalItem] order). */
    fun weakest(scores: List<EvalScore>): EvalItem? = scores.minWithOrNull(compareBy({ it.score }, { it.item.ordinal }))?.item

    /** What the 10 minutes a day are for, by item name (null: none yet). */
    fun taskText(item: String?): String = when (runCatching { EvalItem.valueOf(item ?: "") }.getOrNull()) {
        EvalItem.VOCAL -> "聲樂：長音穩定。一個音唱滿八拍，錄下來聽，抖的地方再唱。"
        EvalItem.DANCE -> "舞蹈：律動和身體分離，對鏡子做，拍子不能掉。"
        EvalItem.VISUAL -> "視覺表現：對手機鏡頭練表情和角度，挑一張最好的。"
        EvalItem.STAR -> "明星潛力：三十秒自我介紹，對鏡頭講，眼神不飄。"
        EvalItem.ATTITUDE -> "態度：睡前寫下明天第一件要做的事，隔天照做。"
        null -> "這個月的弱點，照小葵姐說的練。"
    }

    /** The result line he reads (an announcement, kept private: no ranking). */
    fun announce(now: MonthlyResult, before: MonthlyResult?): String = buildString {
        append("月評結果（${now.month}）：")
        append(now.scores.joinToString("、") { "${it.item.zh} ${it.score}（${it.by.zh}）" })
        append("。")
        before?.let { b ->
            val d = delta(now, b)
            if (d.isNotEmpty()) append("跟上個月比：" + d.entries.joinToString("、") { (i, v) -> i.zh + if (v > 0) "＋$v" else if (v < 0) "－${-v}" else "持平" } + "。")
        }
        now.task?.let { append("這個月的個人任務：${taskText(it)}每天 10 分鐘。") }
    }
}
