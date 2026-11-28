package app.agency.core.rules

import app.agency.core.base.Days

/**
 * The compact Traditional Chinese rendering of the rules that are set (blank values are left out): the system prompt carries
 * it, and the onboarding confirmation screen shows it. Times are written H:MM and every number is followed by its unit, so the
 * guard's number check reads them exactly as 小葵 will say them.
 */
object RulesText {
    fun render(rules: Rules, training: TrainingRule = rules.training): String =
        groups(rules, training).joinToString("\n") { (title, lines) -> "【$title】" + lines.joinToString("；") }

    /** The same content grouped, for a screen that wants headings. */
    fun groups(rules: Rules, training: TrainingRule = rules.training): List<Pair<String, List<String>>> {
        val out = ArrayList<Pair<String, List<String>>>()
        fun group(title: String, lines: List<String>) {
            val l = lines.filter { it.isNotBlank() }
            if (l.isNotEmpty()) out += title to l
        }
        group("作息", buildList {
            if (rules.callName.isNotBlank()) add("稱呼：${rules.callName}")
            if (rules.stageName.isNotBlank()) add("藝名：${rules.stageName}")
            if (rules.classWeekdays.isNotEmpty()) add("上課日：" + rules.classWeekdays.sorted().joinToString("、") { weekday(it) })
            add(times("上課日", rules.classDay))
            add(times("假日", rules.freeDay))
            if (rules.sleep.shiftMinutesPerWeek > 0) add("睡覺時間每週提早 ${rules.sleep.shiftMinutesPerWeek} 分鐘")
        })
        val d = rules.diet
        group("飲食", buildList {
            if (d.kcal > 0) add("每日 ${d.kcal} 大卡")
            if (d.proteinG > 0) add("蛋白質 ${d.proteinG} 克")
            if (d.minKcal > 0) add("最少 ${d.minKcal} 大卡")
            if (d.mealCapKcal > 0) add("單餐上限 ${d.mealCapKcal} 大卡")
            if (d.firstMealKcal > 0) add("第一餐 ${d.firstMealKcal} 大卡")
            if (d.dinnerKcal > 0) add("晚餐 ${d.dinnerKcal} 大卡")
            if (d.nightMealKcal > 0) add("宵夜 ${d.nightMealKcal} 大卡")
            if (d.banned.isNotEmpty()) {
                add("禁止：" + d.banned.joinToString("、") { b -> b.category + if (b.words.isEmpty()) "" else "（${b.words.joinToString("、")}）" })
            }
            if (d.allowed.isNotEmpty()) add("可以吃：" + d.allowed.joinToString("、"))
            if (d.zeroCalDrinksPerWeek > 0) add("無熱量飲料每週最多 ${d.zeroCalDrinksPerWeek} 次")
            Days.parse(d.caffeineCutoff)?.let { add("咖啡因 ${hm(it)} 以後不喝") }
            if (d.freeMealsPerWeek > 0 && d.freeMealFrom.isNotBlank()) add("從 ${d.freeMealFrom} 起每週自由餐 ${d.freeMealsPerWeek} 次")
            if (d.estimateMarginPct > 0) add("估算的熱量加 ${d.estimateMarginPct}%")
        })
        group("常吃的食物", rules.foods.map { f ->
            "${f.name} ${f.kcal} 大卡 ${num(f.proteinG)} 克蛋白質" + if (f.aliases.isEmpty()) "" else "（也叫${f.aliases.joinToString("、")}）"
        })
        val w = rules.water
        group("喝水", buildList {
            if (w.bottleMl > 0) add("水壺 ${w.bottleMl} 毫升")
            if (w.bottlesAtStart > 0) add("一開始每天 ${w.bottlesAtStart} 壺（${w.bottlesAtStart * w.bottleMl} 毫升）")
            if (w.bottlesGoal > 0) add("目標每天 ${w.bottlesGoal} 壺（${w.bottlesGoal * w.bottleMl} 毫升）")
            if (w.bottlesGoal > w.bottlesAtStart && w.bottlesAtStart > 0) add("前 ${w.rampAfterDays} 天之後每週多 0.25 壺")
        })
        val t = training
        group("訓練", buildList {
            if (t.coreMinutes > 0) add("核心 ${t.coreMinutes} 分鐘")
            if (t.strengthRounds > 0) {
                add("肌力 ${t.strengthRounds} 輪：伏地挺身 ${t.pushUps} 下、深蹲 ${t.squats} 下、棒式 ${t.plankSeconds} 秒")
            }
            if (t.stretchMinutes > 0) add("收操伸展 ${t.stretchMinutes} 分鐘")
            if (t.morningStretchMinutes > 0) add("起床伸展 ${t.morningStretchMinutes} 分鐘")
            if (t.vocalBasicsMinutes > 0) add("聲樂基本功 ${t.vocalBasicsMinutes} 分鐘")
            if (t.cardioMinutes > 0) add("體能 ${t.cardioMinutes} 分鐘" + if (t.cardioPerWeek > 0) "，每週 ${t.cardioPerWeek} 次" else "")
            if (t.postureMinutes > 0) add("靠牆站姿 ${t.postureMinutes} 分鐘")
            if (t.walkMinutes > 0) add("快走 ${t.walkMinutes} 分鐘")
            if (t.danceMinutes > 0) add("舞蹈 ${t.danceMinutes} 分鐘")
            if (t.vocalMinutes > 0) add("聲樂練習 ${t.vocalMinutes} 分鐘")
            if (t.expressionMinutes > 0) {
                add("表情鏡頭 ${t.expressionMinutes} 分鐘" + if (t.expressionPerWeek > 0) "，每週 ${t.expressionPerWeek} 次" else "")
            }
        })
        val r = rules.ride
        group("跑單", buildList {
            if (r.daysPerWeek > 0) add("每週 ${r.daysPerWeek} 天")
            if (r.dailyIncome > 0) add("每天目標 ${r.dailyIncome} 元")
            if (r.weeklyIncome > 0) add("每週目標 ${r.weeklyIncome} 元")
        })
        val b = rules.body
        group("體重", buildList {
            if (b.startKg > 0) add("起始 ${num(b.startKg)} 公斤")
            if (b.goalKg > 0) add("目標 ${num(b.goalKg)} 公斤")
            if (b.pauseBelowKg > 0) add("低於 ${num(b.pauseBelowKg)} 公斤先暫停檢討")
            if (b.neverBelowKg > 0) add("絕不低於 ${num(b.neverBelowKg)} 公斤")
            if (b.weighWeekday in 1..7) add("每${weekday(b.weighWeekday)}量體重")
            if (b.fastLossKgPerWeek > 0) add("每週瘦超過 ${num(b.fastLossKgPerWeek)} 公斤算太快")
        })
        val s = rules.study
        group("學習", buildList {
            if (s.daily.isNotBlank()) add("每天：${s.daily}")
            if (s.twiceWeekly.isNotEmpty()) add("每週兩次：${s.twiceWeekly.joinToString("、")}")
            if (s.rotation.isNotEmpty()) add("輪流：${s.rotation.joinToString("、")}")
            if (s.maxPerDay > 0) add("每天最多 ${s.maxPerDay} 堂")
            if (s.extraPerDay > 0) add("加課每天最多 ${s.extraPerDay} 堂")
            if (s.quizQuestions > 0) add("測驗 ${s.quizQuestions} 題，對 ${s.quizPass} 題通過")
            if (s.reviewAfterDays > 0) add("${s.reviewAfterDays} 天後複習")
            if (s.examWeekPerDay > 0) add("考試週每天 ${s.examWeekPerDay} 堂")
            if (s.examWeeksPerTerm > 0) add("考試週每學期最多 ${s.examWeeksPerTerm} 次")
        })
        val m = rules.money
        group("記帳", buildList {
            if (m.livingPct > 0) add("生活 ${m.livingPct}%")
            if (m.funPct > 0) add("娛樂 ${m.funPct}%")
            if (m.foodPerDayMin > 0 || m.foodPerDayMax > 0) add("每天伙食 ${m.foodPerDayMin}–${m.foodPerDayMax} 元")
        })
        group("儀容", rules.grooming.map { "${it.name} 每 ${it.everyDays} 天" })
        return out
    }

    private fun times(label: String, t: DayTimes): String {
        val parts = listOf(
            "簽到" to t.wake, "第一餐" to t.firstMeal, "下課" to t.classEnd, "快走" to t.walk, "晚餐" to t.dinner,
            "出門" to t.leaveBy, "宵夜" to t.nightMeal, "睡覺" to t.bedtime, "目標睡覺" to t.bedtimeGoal,
        ).mapNotNull { (n, v) -> Days.parse(v)?.let { "$n ${hm(it)}" } }
        return if (parts.isEmpty()) "" else "$label " + parts.joinToString("、")
    }

    fun hm(t: java.time.LocalTime): String = "${t.hour}:${t.minute.toString().padStart(2, '0')}"

    fun weekday(iso: Int): String = if (iso !in 1..7) "" else "星期" + "一二三四五六日"[iso - 1]

    private fun num(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
}
