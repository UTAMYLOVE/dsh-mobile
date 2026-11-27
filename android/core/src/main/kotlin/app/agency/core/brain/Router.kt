package app.agency.core.brain

import app.agency.core.model.RoleKey
import app.agency.core.rules.RulesBook

/** Where one message of his goes. */
sealed interface Route {
    /** An exact command word: its handler (code) answers, or [Router.NOT_YET] from the routed role while it has none yet. */
    data class Command(val kind: String, val role: RoleKey) : Route

    /** An attempt to change 公司規章 in chat: the fixed reply ([app.agency.core.rules.RulesBook.LOCKED]), nothing changes. */
    data object RulesLocked : Route

    /** Not a command word: the data clerk (Intake) decides the kind, then chat goes to ChatLoop. */
    data object Model : Route
}

/**
 * The code fast paths of arch 1.3 step 1 and the role routing of 1.3, in code (no model). A command word goes to its handler
 * (rest day, 休假餐, 「早上很餓」 since T11); a record kind without a handler yet gets [NOT_YET] from the role it belongs to; an
 * attempt to change 公司規章 gets the fixed reply; chat, mood, unwell and discussion go to the manager's ChatLoop.
 */
object Router {
    const val NOT_YET = "這個功能下一版開"

    /** His 「這裡有問題」 (a button or typed): the manager opens 討論 (T12). */
    const val DISCUSS_OPEN = "discuss_open"

    /** 「真人任務完成」 (L20): the week's real-person task is done. */
    const val REAL_TASK = "real_task"

    /** 「找影片」「找聲樂影片」 (T19c): code searches for 理論課 candidates. */
    const val FIND_VIDEOS = "find_videos"

    /** 「理論課」「再一支」 (T19c): where today's lessons stand (more on demand: the next one is always open after a pass). */
    const val LESSON_STATUS = "lesson_status"

    /** 「這週考試」 (owner 2026-11-26): 理論課 one a day this week, at most twice a semester. */
    const val EXAM_WEEK = "exam_week"

    /** The manual weather switches (no weather service): 「今天下雨」「今天很熱」 and their off words. */
    const val WEATHER = "weather:"

    /** 「外送 1200 元」 (a day's delivery income) and 「外送週目標 N」. */
    const val INCOME = "income"
    const val INCOME_TARGET = "income_target"

    /** The kind prefix of a grooming report (「刮好了」 → `grooming:SHAVE`). */
    const val GROOMING = "grooming:"

    /** His exact yes / no to the one waiting proposal. */
    val YES = setOf("好", "好啊", "好的", "好喔", "可以", "確認", "就這樣", "嗯好")
    val NO = setOf("不要", "不用", "不好", "不要改")

    fun clean(text: String): String = TRIM.replace(text, "")

    /** The kinds of arch 1.4's Intake that are records (or record commands); the rest is conversation. */
    val RECORD_KINDS = setOf(
        "meal", "craving", "weight", "label", "training", "water", "sign_in", "sign_out", "condition", "rest_day",
        "cheat_declare", "hungry_morning",
    )

    /** Meal, craving, weight, label, food, water, training → 體態管理老師; everything else → 經紀人 (arch 1.3). */
    private val BODY_KINDS = setOf("meal", "craving", "weight", "label", "food", "water", "training", "progression")

    /** Exact command words (button words he may also type), by kind. */
    private val COMMANDS: Map<String, String> = buildMap {
        listOf("今天休息").forEach { put(it, "rest_day") }
        listOf(Discussion.OPEN_WORD, "這個有問題", "這不適合我").forEach { put(it, DISCUSS_OPEN) }
        listOf("早上很餓").forEach { put(it, "hungry_morning") }
        listOf("喝完一壺", "喝了一壺", "喝了半壺", "喝完半壺").forEach { put(it, "water") }
        listOf("簽到", "起床", "起床了").forEach { put(it, "sign_in") }
        listOf("簽退", "我要睡了", "要睡了").forEach { put(it, "sign_out") }
        listOf("出門了", "出門跑單", "出門").forEach { put(it, "ride_start") }
        listOf("收工", "收工了", "跑完了").forEach { put(it, "ride_end") }
        listOf("核心開始", "肌力開始", "體能開始", "伸展開始", "核心完成", "核心做完了", "肌力完成", "體能完成", "體能做完了", "伸展完成", "伸展做完了", "快走完成", "快走完了").forEach { put(it, "training") }
        listOf(app.agency.core.engine.RealTasks.DONE_WORDS, "真人任務做完了", "真人任務做好了", "真人任務做到了").forEach { put(it, REAL_TASK) }
        listOf("理論課", "今天的理論課", "再一支", "再來一支", "多看一支", "再看一支", "多一支", "下一支").forEach { put(it, LESSON_STATUS) }
        listOf("這週考試", "這周考試", "這週要考試", "這周要考試", "這週期中考", "這週期末考").forEach { put(it, EXAM_WEEK) }
        listOf("今天下雨", "下雨了", "外面下雨").forEach { put(it, WEATHER + "RAIN_ON") }
        listOf("今天沒下雨", "雨停了", "沒下雨了").forEach { put(it, WEATHER + "RAIN_OFF") }
        listOf("今天很熱", "大熱天", "今天好熱", "外面很熱").forEach { put(it, WEATHER + "HOT_ON") }
        listOf("今天不熱", "不熱了").forEach { put(it, WEATHER + "HOT_OFF") }
        listOf("刮好了", "鬍子刮好了", "刮鬍子了", "刮好鬍子了").forEach { put(it, GROOMING + "SHAVE") }
        listOf("指甲剪好了", "剪指甲了", "指甲剪了").forEach { put(it, GROOMING + "NAILS") }
        listOf("眉毛修好了", "修眉了", "修好眉毛了").forEach { put(it, GROOMING + "BROWS") }
        listOf("頭髮剪好了", "剪頭髮了", "剪好頭髮了").forEach { put(it, GROOMING + "HAIR") }
    }

    /** A 休假餐 declaration starts with one of these (「休假餐 星期四晚餐」, 「這週休假餐是明天晚餐」). */
    private val CHEAT_PREFIXES = listOf("休假餐", "這週休假餐", "這周休假餐", "本週休假餐")

    /** 「找影片」「再找影片」「幫我找聲樂影片」「找英文的課」: at most ten characters of category in between. */
    private val FIND = Regex("^(?:再|幫我|再幫我)?找(?:一些|幾支|新的|更多)?(.{0,10}?)(?:的)?(?:影片|課程|課)$")

    private val TRIM = Regex("^[\\s　。！!？?，,、～~]+|[\\s　。！!？?，,、～~]+$")

    fun route(text: String): Route {
        val t = TRIM.replace(text, "")
        COMMANDS[t]?.let { return Route.Command(it, roleOf(it)) }
        if (CHEAT_PREFIXES.any { t.startsWith(it) }) return Route.Command("cheat_declare", RoleKey.MANAGER)
        if (FIND.matches(t)) return Route.Command(FIND_VIDEOS, RoleKey.MANAGER)
        if (app.agency.core.brain.handlers.IncomeWords.target(t) != null) return Route.Command(INCOME_TARGET, RoleKey.MANAGER)
        if (app.agency.core.brain.handlers.IncomeWords.report(t) != null) return Route.Command(INCOME, RoleKey.MANAGER)
        if (RulesBook.isChangeAttempt(t)) return Route.RulesLocked
        return Route.Model
    }

    fun isRecordKind(kind: String): Boolean = kind in RECORD_KINDS

    fun roleOf(kind: String): RoleKey = if (kind in BODY_KINDS) RoleKey.BODY else RoleKey.MANAGER
}
