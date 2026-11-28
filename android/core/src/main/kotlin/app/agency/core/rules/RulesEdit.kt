package app.agency.core.rules

/** What setting one field did: the updated rules, or why the text was not accepted (in his language). */
sealed interface EditResult {
    data class Ok(val rules: Rules) : EditResult
    data class Bad(val message: String) : EditResult
}

/**
 * One editable rule value for the settings page. [value] is the current value as text (blank when unset), [hint] says how to
 * write it, [multiline] fields take one entry per line. [set] parses text into an updated copy of the rules it is given.
 */
class RuleField(
    val key: String,
    val label: String,
    val value: String,
    val hint: String = "",
    val multiline: Boolean = false,
    val set: (Rules, String) -> EditResult,
)

class RuleGroup(val label: String, val fields: List<RuleField>)

/**
 * Every rule as labelled text fields, so the settings page can show and edit them all without knowing the data classes. Blank
 * text always clears a value (0, "" or an empty list).
 */
object RulesEdit {
    fun groups(rules: Rules): List<RuleGroup> {
        val r = rules
        return listOf(
            RuleGroup("基本", listOf(
                text("callName", "稱呼", r.callName) { x, v -> x.copy(callName = v) },
                text("stageName", "藝名", r.stageName) { x, v -> x.copy(stageName = v) },
                RuleField("memberColor", "代表色", r.memberColor, "#RRGGBB") { x, t ->
                    val v = t.trim()
                    if (v.isEmpty() || Regex("^#[0-9A-Fa-f]{6}$").matches(v)) EditResult.Ok(x.copy(memberColor = v.uppercase()))
                    else EditResult.Bad("顏色要寫成 # 加六個字，例如 #88AAFF")
                },
                RuleField("classWeekdays", "上課日", r.classWeekdays.joinToString("、") { RulesText.weekday(it) }, "例如：一、二、三、四、五") { x, t ->
                    weekdays(t)?.let { EditResult.Ok(x.copy(classWeekdays = it)) } ?: EditResult.Bad("星期請寫一到日，用頓號分開")
                },
            )),
            RuleGroup("上課日作息", times("classDay", r.classDay) { x, d -> x.copy(classDay = d) }, ),
            RuleGroup("假日作息", times("freeDay", r.freeDay) { x, d -> x.copy(freeDay = d) }),
            RuleGroup("飲食", listOf(
                int("diet.kcal", "每日熱量（大卡）", r.diet.kcal, 800..5000) { x, v -> x.copy(diet = x.diet.copy(kcal = v)) },
                int("diet.proteinG", "每日蛋白質（克）", r.diet.proteinG, 0..400) { x, v -> x.copy(diet = x.diet.copy(proteinG = v)) },
                int("diet.minKcal", "最少熱量（大卡）", r.diet.minKcal, 500..5000) { x, v -> x.copy(diet = x.diet.copy(minKcal = v)) },
                int("diet.mealCapKcal", "單餐上限（大卡）", r.diet.mealCapKcal, 100..5000) { x, v -> x.copy(diet = x.diet.copy(mealCapKcal = v)) },
                int("diet.firstMealKcal", "第一餐（大卡）", r.diet.firstMealKcal, 50..5000) { x, v -> x.copy(diet = x.diet.copy(firstMealKcal = v)) },
                int("diet.dinnerKcal", "晚餐（大卡）", r.diet.dinnerKcal, 50..5000) { x, v -> x.copy(diet = x.diet.copy(dinnerKcal = v)) },
                int("diet.nightMealKcal", "宵夜（大卡）", r.diet.nightMealKcal, 50..5000) { x, v -> x.copy(diet = x.diet.copy(nightMealKcal = v)) },
                RuleField(
                    "diet.banned", "禁止的食物", r.diet.banned.joinToString("\n") { b -> b.category + "：" + b.words.joinToString("、") },
                    "一行一類：類別：例子、例子", multiline = true,
                ) { x, t ->
                    val list = lines(t).map { line ->
                        val (cat, words) = line.split('：', ':', limit = 2).let { it[0].trim() to it.getOrElse(1) { "" } }
                        BannedFood(cat, items(words))
                    }.filter { it.category.isNotEmpty() }
                    EditResult.Ok(x.copy(diet = x.diet.copy(banned = list)))
                },
                list("diet.allowed", "可以吃的", r.diet.allowed) { x, v -> x.copy(diet = x.diet.copy(allowed = v)) },
                int("diet.zeroCalDrinksPerWeek", "無熱量飲料每週次數", r.diet.zeroCalDrinksPerWeek, 0..50) { x, v ->
                    x.copy(diet = x.diet.copy(zeroCalDrinksPerWeek = v))
                },
                time("diet.caffeineCutoff", "咖啡因最晚", r.diet.caffeineCutoff) { x, v -> x.copy(diet = x.diet.copy(caffeineCutoff = v)) },
                int("diet.freeMealsPerWeek", "每週自由餐次數", r.diet.freeMealsPerWeek, 0..7) { x, v -> x.copy(diet = x.diet.copy(freeMealsPerWeek = v)) },
                RuleField("diet.freeMealFrom", "自由餐開始日", r.diet.freeMealFrom, "YYYY-MM-DD") { x, t ->
                    val v = t.trim()
                    if (v.isEmpty() || runCatching { java.time.LocalDate.parse(v) }.isSuccess) EditResult.Ok(x.copy(diet = x.diet.copy(freeMealFrom = v)))
                    else EditResult.Bad("日期要寫成 YYYY-MM-DD")
                },
                int("diet.estimateMarginPct", "估算加成（%）", r.diet.estimateMarginPct, 0..100) { x, v -> x.copy(diet = x.diet.copy(estimateMarginPct = v)) },
            )),
            RuleGroup("常吃的食物", listOf(
                RuleField(
                    "foods", "食物表",
                    r.foods.joinToString("\n") { f -> "${f.name} ${f.kcal} ${num(f.proteinG)}" + if (f.aliases.isEmpty()) "" else " " + f.aliases.joinToString("、") },
                    "一行一樣：名稱 熱量 蛋白質 別名、別名", multiline = true,
                ) { x, t ->
                    val out = ArrayList<Food>()
                    for (line in lines(t)) {
                        val p = line.split(Regex("""\s+"""))
                        val kcal = p.getOrNull(1)?.toIntOrNull()
                        val protein = p.getOrNull(2)?.toDoubleOrNull()
                        if (p.size < 3 || kcal == null || protein == null || kcal !in 0..5000 || protein !in 0.0..300.0) {
                            return@RuleField EditResult.Bad("這一行看不懂：「$line」。格式：名稱 熱量 蛋白質 別名")
                        }
                        out += Food(p[0], kcal, protein, items(p.drop(3).joinToString("、")))
                    }
                    EditResult.Ok(x.copy(foods = out))
                },
            )),
            RuleGroup("喝水", listOf(
                int("water.bottleMl", "水壺容量（毫升）", r.water.bottleMl, 100..5000) { x, v -> x.copy(water = x.water.copy(bottleMl = v)) },
                int("water.bottlesAtStart", "一開始每天幾壺", r.water.bottlesAtStart, 0..10) { x, v -> x.copy(water = x.water.copy(bottlesAtStart = v)) },
                int("water.bottlesGoal", "最後每天幾壺", r.water.bottlesGoal, 0..10) { x, v -> x.copy(water = x.water.copy(bottlesGoal = v)) },
                int("water.rampAfterDays", "幾天後開始加量", r.water.rampAfterDays, 1..365) { x, v -> x.copy(water = x.water.copy(rampAfterDays = v)) },
            )),
            RuleGroup("訓練", trainingFields(r.training)),
            RuleGroup("跑單", listOf(
                int("ride.daysPerWeek", "每週天數", r.ride.daysPerWeek, 0..7) { x, v -> x.copy(ride = x.ride.copy(daysPerWeek = v)) },
                int("ride.dailyIncome", "每天收入目標（元）", r.ride.dailyIncome, 0..1_000_000) { x, v -> x.copy(ride = x.ride.copy(dailyIncome = v)) },
                int("ride.weeklyIncome", "每週收入目標（元）", r.ride.weeklyIncome, 0..10_000_000) { x, v -> x.copy(ride = x.ride.copy(weeklyIncome = v)) },
            )),
            RuleGroup("體重", listOf(
                kg("body.startKg", "起始體重（公斤）", r.body.startKg) { x, v -> x.copy(body = x.body.copy(startKg = v)) },
                kg("body.goalKg", "目標體重（公斤）", r.body.goalKg) { x, v -> x.copy(body = x.body.copy(goalKg = v)) },
                kg("body.pauseBelowKg", "低於多少先暫停（公斤）", r.body.pauseBelowKg) { x, v -> x.copy(body = x.body.copy(pauseBelowKg = v)) },
                kg("body.neverBelowKg", "絕不低於（公斤）", r.body.neverBelowKg) { x, v -> x.copy(body = x.body.copy(neverBelowKg = v)) },
                RuleField("body.weighWeekday", "量體重的星期", if (r.body.weighWeekday in 1..7) RulesText.weekday(r.body.weighWeekday) else "", "例如：一") { x, t ->
                    val d = weekdays(t)
                    if (d == null || d.size > 1) EditResult.Bad("寫一個星期，例如：一")
                    else EditResult.Ok(x.copy(body = x.body.copy(weighWeekday = d.firstOrNull() ?: 0)))
                },
                RuleField("body.fastLossKgPerWeek", "每週瘦超過多少算太快（公斤）", num0(r.body.fastLossKgPerWeek)) { x, t ->
                    dbl(t, 0.0..5.0)?.let { EditResult.Ok(x.copy(body = x.body.copy(fastLossKgPerWeek = it))) } ?: EditResult.Bad("請寫 0 到 5 之間的數字")
                },
            )),
            RuleGroup("睡眠", listOf(
                int("sleep.shiftMinutesPerWeek", "睡覺時間每週提早（分鐘）", r.sleep.shiftMinutesPerWeek, 0..120) { x, v -> x.copy(sleep = SleepRule(v)) },
            )),
            RuleGroup("學習", listOf(
                text("study.daily", "每天的科目", r.study.daily) { x, v -> x.copy(study = x.study.copy(daily = v)) },
                list("study.twiceWeekly", "每週兩次的科目", r.study.twiceWeekly) { x, v -> x.copy(study = x.study.copy(twiceWeekly = v)) },
                list("study.rotation", "輪流的科目", r.study.rotation) { x, v -> x.copy(study = x.study.copy(rotation = v)) },
                int("study.maxPerDay", "每天最多幾堂", r.study.maxPerDay, 0..10) { x, v -> x.copy(study = x.study.copy(maxPerDay = v)) },
                int("study.extraPerDay", "加課每天最多幾堂", r.study.extraPerDay, 0..10) { x, v -> x.copy(study = x.study.copy(extraPerDay = v)) },
                int("study.quizQuestions", "測驗題數", r.study.quizQuestions, 1..10) { x, v -> x.copy(study = x.study.copy(quizQuestions = v)) },
                int("study.quizPass", "對幾題算通過", r.study.quizPass, 1..10) { x, v -> x.copy(study = x.study.copy(quizPass = v)) },
                int("study.reviewAfterDays", "幾天後複習", r.study.reviewAfterDays, 1..60) { x, v -> x.copy(study = x.study.copy(reviewAfterDays = v)) },
                int("study.examWeekPerDay", "考試週每天幾堂", r.study.examWeekPerDay, 0..10) { x, v -> x.copy(study = x.study.copy(examWeekPerDay = v)) },
                int("study.examWeeksPerTerm", "考試週每學期幾次", r.study.examWeeksPerTerm, 0..10) { x, v -> x.copy(study = x.study.copy(examWeeksPerTerm = v)) },
            )),
            RuleGroup("記帳", listOf(
                int("money.livingPct", "生活（%）", r.money.livingPct, 0..100) { x, v -> x.copy(money = x.money.copy(livingPct = v)) },
                int("money.funPct", "娛樂（%）", r.money.funPct, 0..100) { x, v -> x.copy(money = x.money.copy(funPct = v)) },
                int("money.foodPerDayMin", "每天伙食最少（元）", r.money.foodPerDayMin, 0..100_000) { x, v -> x.copy(money = x.money.copy(foodPerDayMin = v)) },
                int("money.foodPerDayMax", "每天伙食最多（元）", r.money.foodPerDayMax, 0..100_000) { x, v -> x.copy(money = x.money.copy(foodPerDayMax = v)) },
            )),
            RuleGroup("儀容", listOf(
                RuleField("grooming", "儀容項目", r.grooming.joinToString("\n") { "${it.name} ${it.everyDays}" }, "一行一項：名稱 每幾天", multiline = true) { x, t ->
                    val out = ArrayList<Groom>()
                    for (line in lines(t)) {
                        val p = line.split(Regex("""\s+"""))
                        val n = p.getOrNull(1)?.toIntOrNull()
                        if (p.size != 2 || n == null || n !in 1..365) return@RuleField EditResult.Bad("這一行看不懂：「$line」。格式：名稱 天數")
                        out += Groom(p[0], n)
                    }
                    EditResult.Ok(x.copy(grooming = out))
                },
            )),
        )
    }

    /** Applies [text] to the field [key] of [rules]; unknown keys are refused. */
    fun set(rules: Rules, key: String, text: String): EditResult {
        val f = groups(rules).flatMap { it.fields }.firstOrNull { it.key == key } ?: return EditResult.Bad("沒有這個欄位")
        return f.set(rules, text)
    }

    private fun trainingFields(t: TrainingRule): List<RuleField> {
        fun tf(key: String, label: String, v: Int, max: Int, f: (TrainingRule, Int) -> TrainingRule) =
            int("training.$key", label, v, 0..max) { x, n -> x.copy(training = f(x.training, n)) }
        return listOf(
            tf("coreMinutes", "核心（分鐘）", t.coreMinutes, 300) { x, n -> x.copy(coreMinutes = n) },
            tf("strengthRounds", "肌力輪數", t.strengthRounds, 20) { x, n -> x.copy(strengthRounds = n) },
            tf("pushUps", "伏地挺身（下）", t.pushUps, 500) { x, n -> x.copy(pushUps = n) },
            tf("squats", "深蹲（下）", t.squats, 500) { x, n -> x.copy(squats = n) },
            tf("plankSeconds", "棒式（秒）", t.plankSeconds, 1200) { x, n -> x.copy(plankSeconds = n) },
            tf("stretchMinutes", "收操伸展（分鐘）", t.stretchMinutes, 300) { x, n -> x.copy(stretchMinutes = n) },
            tf("morningStretchMinutes", "起床伸展（分鐘）", t.morningStretchMinutes, 300) { x, n -> x.copy(morningStretchMinutes = n) },
            tf("vocalBasicsMinutes", "聲樂基本功（分鐘）", t.vocalBasicsMinutes, 300) { x, n -> x.copy(vocalBasicsMinutes = n) },
            tf("cardioMinutes", "體能（分鐘）", t.cardioMinutes, 300) { x, n -> x.copy(cardioMinutes = n) },
            tf("cardioPerWeek", "體能每週次數", t.cardioPerWeek, 7) { x, n -> x.copy(cardioPerWeek = n) },
            tf("postureMinutes", "靠牆站姿（分鐘）", t.postureMinutes, 300) { x, n -> x.copy(postureMinutes = n) },
            tf("walkMinutes", "快走（分鐘）", t.walkMinutes, 300) { x, n -> x.copy(walkMinutes = n) },
            tf("danceMinutes", "舞蹈（分鐘）", t.danceMinutes, 300) { x, n -> x.copy(danceMinutes = n) },
            tf("vocalMinutes", "聲樂練習（分鐘）", t.vocalMinutes, 300) { x, n -> x.copy(vocalMinutes = n) },
            tf("expressionMinutes", "表情鏡頭（分鐘）", t.expressionMinutes, 300) { x, n -> x.copy(expressionMinutes = n) },
            tf("expressionPerWeek", "表情鏡頭每週次數", t.expressionPerWeek, 7) { x, n -> x.copy(expressionPerWeek = n) },
        )
    }

    private fun times(prefix: String, d: DayTimes, put: (Rules, DayTimes) -> Rules): List<RuleField> {
        fun pick(r: Rules) = if (prefix == "classDay") r.classDay else r.freeDay
        fun tt(key: String, label: String, v: String, f: (DayTimes, String) -> DayTimes) =
            time("$prefix.$key", label, v) { x, s -> put(x, f(pick(x), s)) }
        return listOf(
            tt("wake", "簽到", d.wake) { x, s -> x.copy(wake = s) },
            tt("firstMeal", "第一餐", d.firstMeal) { x, s -> x.copy(firstMeal = s) },
            tt("classEnd", "下課", d.classEnd) { x, s -> x.copy(classEnd = s) },
            tt("walk", "快走", d.walk) { x, s -> x.copy(walk = s) },
            tt("dinner", "晚餐", d.dinner) { x, s -> x.copy(dinner = s) },
            tt("leaveBy", "出門跑單", d.leaveBy) { x, s -> x.copy(leaveBy = s) },
            tt("nightMeal", "宵夜", d.nightMeal) { x, s -> x.copy(nightMeal = s) },
            tt("bedtime", "睡覺（簽退）", d.bedtime) { x, s -> x.copy(bedtime = s) },
            tt("bedtimeGoal", "睡覺時間的目標", d.bedtimeGoal) { x, s -> x.copy(bedtimeGoal = s) },
        )
    }

    private fun text(key: String, label: String, v: String, f: (Rules, String) -> Rules) =
        RuleField(key, label, v) { x, t -> EditResult.Ok(f(x, t.trim())) }

    private fun int(key: String, label: String, v: Int, range: IntRange, f: (Rules, Int) -> Rules) =
        RuleField(key, label, if (v == 0) "" else v.toString(), "${range.first}～${range.last}") { x, t ->
            val s = t.trim()
            if (s.isEmpty()) return@RuleField EditResult.Ok(f(x, 0))
            val n = s.toIntOrNull()
            if (n == null || (n != 0 && n !in range)) EditResult.Bad("請寫 ${range.first} 到 ${range.last} 之間的整數")
            else EditResult.Ok(f(x, n))
        }

    private fun kg(key: String, label: String, v: Double, f: (Rules, Double) -> Rules) =
        RuleField(key, label, num0(v), "30～250") { x, t ->
            dbl(t, 30.0..250.0)?.let { EditResult.Ok(f(x, it)) } ?: EditResult.Bad("請寫 30 到 250 之間的數字")
        }

    private fun time(key: String, label: String, v: String, f: (Rules, String) -> Rules) =
        RuleField(key, label, v, "HH:mm，過了午夜照寫，例如 02:00") { x, t ->
            RulesValidate.time(t)?.let { EditResult.Ok(f(x, it)) } ?: EditResult.Bad("時間要寫成 HH:mm，例如 07:30")
        }

    private fun list(key: String, label: String, v: List<String>, f: (Rules, List<String>) -> Rules) =
        RuleField(key, label, v.joinToString("、"), "用頓號分開") { x, t -> EditResult.Ok(f(x, items(t))) }

    private fun items(t: String): List<String> = t.split('、', ',', '，', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    private fun lines(t: String): List<String> = t.lines().map { it.trim() }.filter { it.isNotEmpty() }

    private val WEEKDAY = "一二三四五六日"

    /** 「一、三、五」, 「1,3,5」 or 「星期一」 → ISO weekdays; null when something is not a weekday. */
    private fun weekdays(t: String): List<Int>? {
        val out = ArrayList<Int>()
        for (raw in items(t.replace(" ", "、"))) {
            val s = raw.removePrefix("星期").removePrefix("週").removePrefix("禮拜")
            val n = s.toIntOrNull() ?: (WEEKDAY.indexOf(s.replace("天", "日")).takeIf { s.length == 1 && it >= 0 }?.plus(1))
            if (n == null || n !in 1..7) return null
            out += n
        }
        return out.distinct().sorted()
    }

    /** Blank → 0. */
    private fun dbl(t: String, range: ClosedFloatingPointRange<Double>): Double? {
        val s = t.trim()
        if (s.isEmpty()) return 0.0
        val v = s.toDoubleOrNull() ?: return null
        return if (v == 0.0 || v in range) v else null
    }

    private fun num(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
    private fun num0(v: Double): String = if (v == 0.0) "" else num(v)
}
