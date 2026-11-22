package app.agency.core.brain.handlers

import app.agency.core.model.CompanyRules
import app.agency.core.model.FavoritePlace
import app.agency.core.model.FoodEntry
import app.agency.core.model.Message
import app.agency.core.model.Who
import app.agency.core.rules.MealBudget
import app.agency.core.schedule.DayPlan
import app.agency.core.schedule.DayType
import app.agency.core.schedule.SlotKey

/** What the body teacher's turn has to do (the system prompt's 【這一次要做的事】, written by code). */
sealed interface MealTask {
    val text: String

    /** 「跟朋友吃 X」 (owner 2026-11-22): one sentence picking the most compliant order there; never 「別跟朋友吃」. */
    data class Friends(val place: String) : MealTask {
        override val text = "他要跟朋友去吃「$place」。用一句話挑出那裡最合規的點法：蛋白質優先、不要炸的、不超過單餐上限，" +
            "也不超過【資料】裡這一餐今天的額度；用 propose_meal_order 把它做成點餐卡（食物表沒有的菜給 kcal_high，寧可估高）。" +
            "只有在他要你找店的時候才用 search_places。不要叫他不要跟朋友吃，跟朋友吃飯是好事。店名只能用搜尋結果裡的。"
    }

    /** 「附近有什麼」: one search for a dish that fits this meal's room, then a pick from the results only. */
    data object Nearby : MealTask {
        override val text = "他問附近有什麼可以吃。用 search_places 搜一次（菜名挑符合這一餐額度、蛋白質高、不是炸的，例如雞胸便當），" +
            "只從搜尋結果裡挑一家一樣，熱量說是估算；適合的話用 propose_meal_order 做成點餐卡並帶 place_result_id。沒有結果就直說，不准自己編店名。"
    }

    /** His answer to the pre-meal suggestion, or more talk about what to eat for the next meal. */
    data object Talk : MealTask {
        override val text = "你們在討論下一餐吃什麼。照他說的（想吃什麼、只能買超商、跟朋友吃）挑一個合規的點法，一次只問一題；" +
            "定了就用 propose_meal_order 做成點餐卡。數字只用工具和【資料】的。"
    }
}

/**
 * 「時間固定，吃什麼每餐討論」 (T16), the code parts: which words open the body teacher's meal talk, and the pre-meal suggestion
 * (30 minutes before 第一餐 and 晚餐; never the class-day 午餐, which he eats with friends and is not planned) from the food table,
 * his favourite places and what this meal may be today (L5).
 */
object MealDiscussion {
    /** The kind of the pre-meal suggestion's line. */
    const val PLAN_KIND = "meal_plan"

    /** The kind of the body teacher's lines in a meal talk. */
    const val TALK_KIND = "meal_talk"

    /** The nudge ids of the pre-meal suggestions start with this (one a slot a day). */
    const val PREMEAL = "premeal:"
    const val BEFORE_MIN = 30
    const val TALK_WINDOW_MS = 2L * 3_600_000

    /** The meals that get a suggestion: not the class-day 午餐 (with friends, owner 2026-11-22), not the fixed portions. */
    fun proposed(plan: DayPlan): List<SlotKey> = listOfNotNull(
        SlotKey.FIRST_MEAL.takeIf { plan.type != DayType.CLASS && plan.slot(it) != null },
        SlotKey.DINNER.takeIf { plan.slot(it) != null },
    )

    // 「跟朋友吃牛肉麵」「中午跟同學去吃火鍋」; not 「跟朋友吃了…」 (that is a meal he ate).
    private val FRIENDS = Regex("跟(?:朋友|同學|室友|大家|他們|她們)(?:一起)?(?:去)?吃(?![了完過])(.{1,20})")
    private val NEARBY = Regex("^(?:附近|這附近|附近有|附近有什麼|附近有什麼吃的|附近吃什麼|附近有什麼可以吃|這附近有什麼吃的)[？?。]*$")

    /** What his words ask the body teacher to do, or null. */
    fun task(text: String): MealTask? {
        val t = text.trim()
        if (NEARBY.matches(t)) return MealTask.Nearby
        FRIENDS.find(t)?.let { m ->
            val place = m.groupValues[1].trim().trimEnd('。', '！', '!', '？', '?', '，', ',').ifEmpty { return null }
            return MealTask.Friends(place)
        }
        return null
    }

    // Words of a meal talk that name no food themselves (「只能買超商」「我想去麥當勞」).
    private val ABOUT = Regex("超商|便利商店|全家|小七|麥當勞|外帶|點餐|點什麼|買什麼|不想吃|吃不下|太貴|便當店|自助餐")

    /** His words are about what to eat next (the brain's food words, or these). */
    fun aboutFood(text: String, mentionsFood: (String) -> Boolean): Boolean = mentionsFood(text) || ABOUT.containsMatchIn(text)

    /**
     * A meal talk is still on: the body teacher's latest line is a suggestion or a talk line of the last two hours (a link's
     * note in between, such as the week's real-person task, does not end it).
     */
    fun talking(history: List<Message>, now: Long): Boolean =
        history.lastOrNull { it.who == Who.COMPANY && it.kind != app.agency.core.engine.Nerve.KIND }?.let { it.kind in setOf(PLAN_KIND, TALK_KIND) && now - it.at <= TALK_WINDOW_MS } == true

    /** The pre-meal line (code's numbers and names only). */
    fun suggestion(
        plan: DayPlan,
        slot: SlotKey,
        rules: CompanyRules,
        room: Int,
        proteinG: Int,
        table: List<FoodEntry>,
        favorites: List<FavoritePlace>,
    ): String {
        val s = plan.slot(slot)
        val label = s?.label ?: slot.zh
        val pick = MealPicker.fits(table, rules, room).firstOrNull()
        val fav = favorites.take(2).map { it.title }
        return buildString {
            append("${label} ${s?.time.orEmpty()} 快到了：今天建議 $room 大卡以內")
            if (proteinG > 0) append("、蛋白質至少 $proteinG 克")
            append("。")
            if (pick != null) append("可以吃${MealPicker.line(pick)}。")
            if (fav.isNotEmpty()) append("常去的店：${fav.joinToString("、")}。")
            append("想吃別的就說「今天想吃…」，跟朋友吃就說「跟朋友吃…」，要找附近的店就說「附近有什麼」。")
        }
    }

    /** The minute the suggestion for [slot] is due. */
    fun dueMinute(plan: DayPlan, slot: SlotKey): Int? = plan.slot(slot)?.minute?.minus(BEFORE_MIN)

    fun usualProtein(rules: CompanyRules, eatenProteinG: Double, mealsLeft: Int): Int =
        if (mealsLeft <= 0) 0 else kotlin.math.ceil(app.agency.core.rules.CoachRules.remainingProtein(rules.proteinG, eatenProteinG).toDouble() / mealsLeft).toInt()

    /** The room the suggestion names: this meal's budget today ([MealBudget.room]). */
    fun room(plan: DayPlan, rules: CompanyRules, slot: SlotKey, eatenKcal: Int, eatenProteinG: Double, eatenSlots: Set<SlotKey>, minute: Int): Int =
        MealBudget.room(plan, rules, slot, eatenKcal, eatenProteinG, eatenSlots, minute) ?: MealBudget.usual(slot, plan)
}

/** 設定 › 附近搜尋: the coarse area he types (a district or so), never an address and never the phone's location. */
object AreaText {
    const val MAX = 20
    private val PRECISE = Regex("[0-9０-９]|號|巷|弄|樓|段")

    /** The problem with [text] as he reads it, or null when it can be kept (blank clears it). */
    fun problem(text: String): String? {
        val t = text.trim()
        return when {
            t.isEmpty() -> null
            t.codePointCount(0, t.length) > MAX -> "最多 $MAX 個字，填到區就好。"
            PRECISE.containsMatchIn(t) -> "只填大概的地區（例如大安區），不要填門牌、巷弄或樓層。"
            t.any { it in 'A'..'Z' || it in 'a'..'z' } -> "用中文寫地區。"
            else -> null
        }
    }
}
