package app.agency.core.rules

/**
 * Invented rules for tests. Nothing here is a real person's number: deliberately odd synthetic values.
 * Class days Monday to Friday; a made-up food table.
 */
object Fixtures {
    fun rules(): Rules = Rules(
        callName = "阿星",
        stageName = "STAR",
        memberColor = "#88AAFF",
        classWeekdays = listOf(1, 2, 3, 4, 5),
        classDay = DayTimes(
            wake = "06:40", firstMeal = "11:40", classEnd = "15:30", walk = "", dinner = "18:20", leaveBy = "19:50",
            nightMeal = "21:40", bedtime = "00:50", bedtimeGoal = "00:10",
        ),
        freeDay = DayTimes(
            wake = "09:20", firstMeal = "12:20", classEnd = "", walk = "14:40", dinner = "18:20", leaveBy = "19:50",
            nightMeal = "21:40", bedtime = "01:40", bedtimeGoal = "00:40",
        ),
        diet = Diet(
            kcal = 2100, proteinG = 110, minKcal = 1500, mealCapKcal = 950, firstMealKcal = 850, dinnerKcal = 850,
            nightMealKcal = 400,
            banned = listOf(BannedFood("炸物", listOf("炸", "酥")), BannedFood("含糖飲料", listOf("奶茶", "汽水"))),
            allowed = listOf("氣炸地瓜"),
            zeroCalDrinksPerWeek = 2, caffeineCutoff = "19:40", freeMealFrom = "", freeMealsPerWeek = 0, estimateMarginPct = 12,
        ),
        foods = listOf(
            Food("測試雞腿便當", 700, 40.0, aliases = listOf("雞腿便當")),
            Food("測試豆漿", 200, 14.0, aliases = listOf("豆漿")),
            Food("測試雞胸", 120, 24.0, aliases = listOf("雞胸")),
        ),
        water = WaterRule(bottleMl = 900, bottlesAtStart = 1, bottlesGoal = 3, rampAfterDays = 10),
        training = TrainingRule(
            coreMinutes = 6, strengthRounds = 4, pushUps = 12, squats = 18, plankSeconds = 50, stretchMinutes = 9,
            morningStretchMinutes = 6, vocalBasicsMinutes = 12, cardioMinutes = 12, cardioPerWeek = 2, postureMinutes = 4,
            walkMinutes = 25, danceMinutes = 35, vocalMinutes = 25, expressionMinutes = 12, expressionPerWeek = 3,
        ),
        ride = RideRule(daysPerWeek = 5, dailyIncome = 900, weeklyIncome = 4500),
        body = BodyRule(startKg = 82.0, goalKg = 74.0, pauseBelowKg = 71.0, neverBelowKg = 61.0, weighWeekday = 3, fastLossKgPerWeek = 0.8),
        sleep = SleepRule(shiftMinutesPerWeek = 20),
        study = StudyRule(
            daily = "語言", twiceWeekly = listOf("聲樂", "舞蹈"), rotation = listOf("禮儀", "理財", "保養"),
            maxPerDay = 2, extraPerDay = 1,
        ),
        money = MoneyRule(livingPct = 80, funPct = 20, foodPerDayMin = 150, foodPerDayMax = 250),
        grooming = listOf(Groom("指甲", 9), Groom("修眉", 16)),
    )
}
