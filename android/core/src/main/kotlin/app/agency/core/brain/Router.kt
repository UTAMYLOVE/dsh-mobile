package app.agency.core.brain

import app.agency.core.model.RoleKey

/** Where one message of his goes. */
sealed interface Route {
    /** A record kind whose handler has not landed yet (T13-T19): the routed role answers [Router.NOT_YET]. */
    data class NotYet(val kind: String, val role: RoleKey) : Route

    /** Not a command word: the data clerk (Intake) decides the kind, then chat goes to ChatLoop. */
    data object Model : Route
}

/**
 * The code fast paths of arch 1.3 step 1 and the role routing of 1.3, in code (no model). Stage 1's first cut has no handler
 * for any record yet, so every record kind, whether a command word here or a kind the clerk returns, gets [NOT_YET] from the
 * role it belongs to; chat, mood, unwell and discussion go to the manager's ChatLoop.
 */
object Router {
    const val NOT_YET = "這個功能下一版開"

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
        listOf("早上很餓").forEach { put(it, "hungry_morning") }
        listOf("喝完一壺", "喝了一壺", "喝了半壺", "喝完半壺").forEach { put(it, "water") }
        listOf("簽到", "起床", "起床了").forEach { put(it, "sign_in") }
        listOf("簽退", "我要睡了", "要睡了").forEach { put(it, "sign_out") }
        listOf("出門了", "出門跑單", "收工").forEach { put(it, "ride") }
        listOf("核心開始", "核心完成", "伸展完成").forEach { put(it, "training") }
    }

    /** A 休假餐 declaration starts with one of these (「休假餐 星期四晚餐」, 「這週休假餐是明天晚餐」). */
    private val CHEAT_PREFIXES = listOf("休假餐", "這週休假餐", "這周休假餐", "本週休假餐")

    private val TRIM = Regex("^[\\s　。！!？?，,、～~]+|[\\s　。！!？?，,、～~]+$")

    fun route(text: String): Route {
        val t = TRIM.replace(text, "")
        COMMANDS[t]?.let { return Route.NotYet(it, roleOf(it)) }
        if (CHEAT_PREFIXES.any { t.startsWith(it) }) return Route.NotYet("cheat_declare", RoleKey.MANAGER)
        return Route.Model
    }

    fun isRecordKind(kind: String): Boolean = kind in RECORD_KINDS

    fun roleOf(kind: String): RoleKey = if (kind in BODY_KINDS) RoleKey.BODY else RoleKey.MANAGER
}
