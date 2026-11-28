package app.agency.core.rules

/**
 * Invented rules for tests. Nothing here is a real person's number: round values chosen only to make arithmetic easy to read.
 * Class days Monday to Friday; a made-up food table.
 */
object Fixtures {
    fun rules(): Rules = Rules(
        callName = "阿星",
        stageName = "STAR",
        memberColor = "#88AAFF",
        classWeekdays = listOf(1, 2, 3, 4, 5),
        classDay = DayTimes(
            wake = "07:00", firstMeal = "12:00", classEnd = "16:00", walk = "", dinner = "18:00", leaveBy = "19:30",
            nightMeal = "21:30", bedtime = "01:00", bedtimeGoal = "00:00",
        ),
        freeDay = DayTimes(
            wake = "10:00", firstMeal = "12:30", classEnd = "", walk = "15:00", dinner = "18:00", leaveBy = "19:30",
            nightMeal = "21:30", bedtime = "02:00", bedtimeGoal = "01:00",
        ),
        diet = Diet(
            kcal = 2000, proteinG = 100, minKcal = 1400, mealCapKcal = 900, firstMealKcal = 800, dinnerKcal = 800,
            nightMealKcal = 400,
            banned = listOf(BannedFood("炸物", listOf("炸", "酥")), BannedFood("含糖飲料", listOf("奶茶", "汽水"))),
            allowed = listOf("氣炸地瓜"),
            zeroCalDrinksPerWeek = 2, caffeineCutoff = "20:00", freeMealFrom = "", freeMealsPerWeek = 0, estimateMarginPct = 10,
        ),
        foods = listOf(
            Food("測試雞腿便當", 700, 40.0, aliases = listOf("雞腿便當")),
            Food("測試豆漿", 200, 14.0, aliases = listOf("豆漿")),
            Food("測試雞胸", 120, 24.0, aliases = listOf("雞胸")),
        ),
        water = WaterRule(bottleMl = 1000, bottlesAtStart = 1, bottlesGoal = 2, rampAfterDays = 14),
        training = TrainingRule(
            coreMinutes = 5, strengthRounds = 3, pushUps = 10, squats = 15, plankSeconds = 40, stretchMinutes = 8,
            morningStretchMinutes = 5, vocalBasicsMinutes = 10, cardioMinutes = 10, cardioPerWeek = 3, postureMinutes = 3,
            walkMinutes = 30, danceMinutes = 30, vocalMinutes = 20, expressionMinutes = 10, expressionPerWeek = 2,
        ),
        ride = RideRule(daysPerWeek = 6, dailyIncome = 1000, weeklyIncome = 6000),
        body = BodyRule(startKg = 80.0, goalKg = 72.0, pauseBelowKg = 70.0, neverBelowKg = 60.0, weighWeekday = 1, fastLossKgPerWeek = 1.0),
        sleep = SleepRule(shiftMinutesPerWeek = 15),
        study = StudyRule(
            daily = "語言", twiceWeekly = listOf("聲樂", "舞蹈"), rotation = listOf("禮儀", "理財", "保養"),
            maxPerDay = 3, extraPerDay = 1,
        ),
        money = MoneyRule(livingPct = 90, funPct = 10, foodPerDayMin = 200, foodPerDayMax = 300),
        grooming = listOf(Groom("指甲", 7), Groom("修眉", 14)),
    )
}
