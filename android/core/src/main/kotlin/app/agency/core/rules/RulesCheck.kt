package app.agency.core.rules

import app.agency.core.base.Days
import app.agency.core.stage.Feature

/**
 * Which rule values a feature needs before it can be scheduled. A feature with a missing value is "not set up": the planner
 * leaves its items out and 小葵 is told the names returned here so she can ask him to fill them in 設定.
 *
 * A value is unset when it is 0, blank or empty (a malformed time counts as blank). Names are Traditional Chinese because they
 * are shown to him.
 */
object RulesCheck {
    /** Names of the values [feature] needs and [rules] does not have yet; empty = ready. */
    fun missing(rules: Rules, feature: Feature): List<String> {
        val out = ArrayList<String>()
        fun need(ok: Boolean, name: String) {
            if (!ok) out += name
        }
        fun time(value: String, name: String) = need(Days.parse(value) != null, name)
        fun both(name: String, pick: (DayTimes) -> String) {
            time(pick(rules.classDay), "上課日$name")
            time(pick(rules.freeDay), "假日$name")
        }
        val t = rules.training
        val d = rules.diet
        when (feature) {
            Feature.CHECK_IN -> {
                both("簽到時間") { it.wake }
                both("睡覺時間") { it.bedtime }
            }
            Feature.MEALS -> {
                need(d.kcal > 0, "每日熱量")
                need(d.mealCapKcal > 0, "單餐熱量上限")
                need(d.firstMealKcal > 0, "第一餐熱量")
                need(d.dinnerKcal > 0, "晚餐熱量")
                need(d.nightMealKcal > 0, "宵夜熱量")
                both("第一餐時間") { it.firstMeal }
                both("晚餐時間") { it.dinner }
                both("宵夜時間") { it.nightMeal }
            }
            Feature.WATER -> {
                need(rules.water.bottleMl > 0, "水壺容量")
                need(rules.water.bottlesAtStart > 0, "起始喝水壺數")
                need(rules.water.bottlesGoal > 0, "目標喝水壺數")
                both("出門時間") { it.leaveBy }
            }
            Feature.CORE -> need(t.coreMinutes > 0, "核心分鐘數")
            Feature.STRENGTH -> {
                need(t.strengthRounds > 0, "肌力輪數")
                need(t.pushUps > 0, "伏地挺身下數")
                need(t.squats > 0, "深蹲下數")
                need(t.plankSeconds > 0, "棒式秒數")
            }
            Feature.STRETCH -> need(t.stretchMinutes > 0, "收操伸展分鐘數")
            Feature.RIDE -> both("出門時間") { it.leaveBy }
            Feature.WEIGHT -> need(rules.body.weighWeekday in 1..7, "量體重的星期")
            Feature.MORNING_STRETCH -> need(t.morningStretchMinutes > 0, "起床伸展分鐘數")
            Feature.VOCAL_BASICS -> need(t.vocalBasicsMinutes > 0, "聲樂基本功分鐘數")
            Feature.CARDIO -> {
                need(t.cardioMinutes > 0, "體能分鐘數")
                need(t.cardioPerWeek > 0, "每週體能次數")
            }
            Feature.POSTURE -> need(t.postureMinutes > 0, "靠牆站姿分鐘數")
            Feature.SUNSCREEN -> both("出門時間") { it.leaveBy }
            Feature.WALK -> {
                need(t.walkMinutes > 0, "快走分鐘數")
                time(rules.freeDay.walk, "假日快走時間")
            }
            Feature.DANCE -> need(t.danceMinutes > 0, "舞蹈分鐘數")
            Feature.VOCAL -> need(t.vocalMinutes > 0, "聲樂練習分鐘數")
            Feature.EXPRESSION -> {
                need(t.expressionMinutes > 0, "表情鏡頭分鐘數")
                need(t.expressionPerWeek > 0, "每週表情鏡頭次數")
            }
            Feature.EARLY_BED -> {
                need(rules.sleep.shiftMinutesPerWeek > 0, "每週提早睡覺分鐘數")
                both("目標睡覺時間") { it.bedtimeGoal }
            }
            Feature.GROOMING -> need(rules.grooming.isNotEmpty(), "儀容項目")
            else -> Unit
        }
        return out
    }

    /** True when [feature] has everything it needs. */
    fun ready(rules: Rules, feature: Feature): Boolean = missing(rules, feature).isEmpty()
}
