package app.agency.core.stage

import kotlinx.serialization.Serializable

/**
 * Everything that can be switched on. [stage] is the stage that opens it (0 = always on). A feature that is not open is never
 * scheduled, chased, counted or suggested; a voluntary report for it is still recorded.
 */
enum class Feature(val stage: Int, val label: String) {
    MEMO(0, "備忘錄"),
    MONEY(0, "記帳"),

    LESSONS(1, "理論課"),

    CHECK_IN(2, "簽到簽退"),
    MEALS(2, "三餐"),
    WATER(2, "喝水"),
    CORE(2, "核心"),
    STRENGTH(2, "肌力"),
    STRETCH(2, "收操伸展"),
    RIDE(2, "跑單"),
    WEIGHT(2, "量體重"),

    MORNING_STRETCH(3, "起床伸展"),
    VOCAL_BASICS(3, "聲樂基本功"),
    CARDIO(3, "體能"),
    POSTURE(3, "靠牆站姿"),
    SUNSCREEN(3, "防曬"),
    MOOD(3, "心情"),
    WALK(3, "快走"),
    SINGING(3, "唱歌分析"),

    DANCE(4, "舞蹈"),
    VOCAL(4, "聲樂練習"),
    EXPRESSION(4, "表情鏡頭"),
    WEEKLY_EVAL(4, "每週評鑑"),
    MONTHLY_EVAL(4, "月評"),
    SPOT_CHECK(4, "突擊檢查"),
    THEME_WEEK(4, "主題週"),
    EARLY_BED(4, "睡覺時間往前調"),
    REAL_TASK(4, "真人任務"),
    GROOMING(4, "儀容"),
    BODY_PHOTO(4, "全身對比照"),
    ;

    fun openAt(stage: Int): Boolean = this.stage <= stage
}

/** One finished 7-day round of a stage. */
@Serializable
data class StageRound(
    val stage: Int,
    /** ISO dates, both included. */
    val from: String,
    val to: String,
    val completionPct: Int,
    val violations: Int,
    val passed: Boolean,
    /** Plain reasons it did not pass (for 小葵 to tell him), empty when passed. */
    val short: List<String> = emptyList(),
)

/** The stored stage document ([app.agency.core.store.Doc.STAGE]). [joinedOn] / [startedOn] are ISO logical days. */
@Serializable
data class StageState(
    val joinedOn: String,
    val stage: Int = 1,
    val startedOn: String = joinedOn,
    val rounds: List<StageRound> = emptyList(),
    /** Passed weeks in a row (drives the trainee level once stage 4 is open). */
    val passStreak: Int = 0,
    val passedWeeks: Int = 0,
    /** "C", "B" or "A". */
    val level: String = "C",
) {
    companion object {
        const val LAST_STAGE = 4
        const val ROUND_DAYS = 7
    }
}
