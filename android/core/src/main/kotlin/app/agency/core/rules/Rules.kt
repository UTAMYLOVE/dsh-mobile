package app.agency.core.rules

import kotlinx.serialization.Serializable

/**
 * The company rules: every number, time and list that is his. All blank / zero by default (this repository is public; his
 * values are entered on the phone, by pasting his own document at onboarding or in 設定). Times are "HH:mm" strings, "" = not
 * set. A feature whose values are missing is "not set up": [RulesCheck] names what is missing.
 *
 * Chat can never change this; only the settings page can, after two confirmations.
 */
@Serializable
data class Rules(
    /** What 小葵 calls him. */
    val callName: String = "",
    val stageName: String = "",
    /** Member colour as "#RRGGBB". */
    val memberColor: String = "",
    /** ISO weekdays with classes (1 = Monday … 7 = Sunday). */
    val classWeekdays: List<Int> = emptyList(),
    val classDay: DayTimes = DayTimes(),
    val freeDay: DayTimes = DayTimes(),
    val diet: Diet = Diet(),
    val foods: List<Food> = emptyList(),
    val water: WaterRule = WaterRule(),
    val training: TrainingRule = TrainingRule(),
    val ride: RideRule = RideRule(),
    val body: BodyRule = BodyRule(),
    val sleep: SleepRule = SleepRule(),
    val study: StudyRule = StudyRule(),
    val money: MoneyRule = MoneyRule(),
    val grooming: List<Groom> = emptyList(),
    val level: LevelRule = LevelRule(),
)

/** The clock of one kind of day. */
@Serializable
data class DayTimes(
    /** Latest wake-up / sign-in time. */
    val wake: String = "",
    val firstMeal: String = "",
    /** When classes end (class days only). */
    val classEnd: String = "",
    val walk: String = "",
    val dinner: String = "",
    /** Latest time to leave for delivery work. */
    val leaveBy: String = "",
    val nightMeal: String = "",
    /** Sign-out deadline today (a time after midnight belongs to the same logical day). */
    val bedtime: String = "",
    /** Where [bedtime] is heading once the earlier-bedtime feature is open. */
    val bedtimeGoal: String = "",
)

@Serializable
data class Diet(
    val kcal: Int = 0,
    val proteinG: Int = 0,
    val minKcal: Int = 0,
    val mealCapKcal: Int = 0,
    val firstMealKcal: Int = 0,
    val dinnerKcal: Int = 0,
    val nightMealKcal: Int = 0,
    /** Banned categories with example words, e.g. "炸物". Matching is by [BannedFood.words]. */
    val banned: List<BannedFood> = emptyList(),
    /** Names that are never treated as banned even if a banned word matches. */
    val allowed: List<String> = emptyList(),
    val zeroCalDrinksPerWeek: Int = 0,
    /** No caffeine after this time. */
    val caffeineCutoff: String = "",
    /** ISO date from which a weekly free meal is allowed, "" = never. */
    val freeMealFrom: String = "",
    val freeMealsPerWeek: Int = 0,
    /** A model's estimate is raised by this percentage (「熱量算嚴格一點」). */
    val estimateMarginPct: Int = 10,
)

@Serializable
data class BannedFood(val category: String, val words: List<String> = emptyList())

/** One row of his own food table: exact numbers that beat any estimate. */
@Serializable
data class Food(val name: String, val kcal: Int, val proteinG: Double, val aliases: List<String> = emptyList(), val note: String = "")

@Serializable
data class WaterRule(val bottleMl: Int = 0, val bottlesAtStart: Int = 0, val bottlesGoal: Int = 0, val rampAfterDays: Int = 14)

@Serializable
data class TrainingRule(
    val coreMinutes: Int = 0,
    val strengthRounds: Int = 0,
    val pushUps: Int = 0,
    val squats: Int = 0,
    val plankSeconds: Int = 0,
    val stretchMinutes: Int = 0,
    val morningStretchMinutes: Int = 0,
    val vocalBasicsMinutes: Int = 0,
    val cardioMinutes: Int = 0,
    val cardioPerWeek: Int = 0,
    val postureMinutes: Int = 0,
    val walkMinutes: Int = 0,
    val danceMinutes: Int = 0,
    val vocalMinutes: Int = 0,
    val expressionMinutes: Int = 0,
    val expressionPerWeek: Int = 0,
)

@Serializable
data class RideRule(val daysPerWeek: Int = 0, val dailyIncome: Int = 0, val weeklyIncome: Int = 0)

@Serializable
data class BodyRule(
    val startKg: Double = 0.0,
    val goalKg: Double = 0.0,
    /** Below this the plan pauses for a review. */
    val pauseBelowKg: Double = 0.0,
    /** Never push below this, whatever he asks. */
    val neverBelowKg: Double = 0.0,
    /** ISO weekday of the weekly weigh-in. */
    val weighWeekday: Int = 0,
    /** Losing more than this per week two weeks running → propose more kcal. */
    val fastLossKgPerWeek: Double = 0.0,
)

@Serializable
data class SleepRule(val shiftMinutesPerWeek: Int = 0)

@Serializable
data class StudyRule(
    /** The subject with one lesson every day. */
    val daily: String = "",
    /** Subjects with two lessons a week. */
    val twiceWeekly: List<String> = emptyList(),
    /** The rest, in rotation. */
    val rotation: List<String> = emptyList(),
    val maxPerDay: Int = 0,
    val extraPerDay: Int = 0,
    val quizQuestions: Int = 3,
    val quizPass: Int = 2,
    val reviewAfterDays: Int = 2,
    val examWeekPerDay: Int = 1,
    val examWeeksPerTerm: Int = 2,
)

@Serializable
data class MoneyRule(val livingPct: Int = 0, val funPct: Int = 0, val foodPerDayMin: Int = 0, val foodPerDayMax: Int = 0)

@Serializable
data class Groom(val name: String, val everyDays: Int)

/** How the weekly result turns into the trainee level. Not personal: defaults are the app's own. */
@Serializable
data class LevelRule(val passPct: Int = 90, val weeksToB: Int = 4, val weeksToA: Int = 12, val talkAtViolations: Int = 3)
