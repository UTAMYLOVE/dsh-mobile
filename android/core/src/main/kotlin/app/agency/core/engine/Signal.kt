package app.agency.core.engine

import app.agency.core.model.TrainingKind
import app.agency.core.schedule.SlotKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What he said about his body (code decides from his words, never the model): only these two can lighten training. */
enum class IllKind(val zh: String) { SICK("生病"), INJURY("受傷"), UNKNOWN("不舒服") }

/** The grooming cycles (plan v3 §9 儀容, owner 2026-11-22): shave every 2 days, nails Sundays, brows every 2 weeks, hair 4–6 weeks. */
enum class GroomingItem(val zh: String) { SHAVE("刮鬍子"), NAILS("剪指甲（手和腳）"), BROWS("修眉毛"), HAIR("剪頭髮") }

/**
 * Something that happened (agency-links §4 `Signal.kt`). Features only emit signals and read the day state; links turn them
 * into effects. [id] makes a signal count once (an alarm that fires twice, a message sent twice); [at] is epoch millis;
 * [source] names the feature that emitted it. Each kind says which batch feeds it.
 */
@Serializable
sealed interface Signal {
    val id: String
    val at: Long
    val source: String

    /** The first event of a coach day (alarm or message); id `day:<yyyy-MM-dd>`. */
    @Serializable @SerialName("day_started")
    data class DayStarted(override val id: String, override val at: Long, val day: String, override val source: String = "clock") : Signal

    /**
     * The first alarm tick after 入社建檔 (id [REMINDERS_ON], so once ever): reminders run from here; a slot that was due before
     * it (the hours before he finished 入社建檔, or before this version was installed) is never chased.
     */
    @Serializable @SerialName("reminders_on")
    data class RemindersOn(override val id: String, override val at: Long, override val source: String = "scheduler") : Signal

    /** He signed in (sign-in command now; the score with T19). */
    @Serializable @SerialName("woke_up")
    data class WokeUp(override val id: String, override val at: Long, override val source: String = "sign") : Signal

    /** [late]: after the latest sign-out of the day (owner 2026-11-26: only then it is a breach of the rules). */
    @Serializable @SerialName("signed_out")
    data class SignedOut(override val id: String, override val at: Long, override val source: String = "sign", val late: Boolean = false) : Signal

    /** He said he is ill or hurt; [word] is the code-matched word (「發燒」), never his message. */
    @Serializable @SerialName("unwell")
    data class Unwell(override val id: String, override val at: Long, val kind: IllKind, val word: String, override val source: String = "chat") : Signal

    @Serializable @SerialName("mood_low")
    data class MoodLow(override val id: String, override val at: Long, override val source: String = "chat") : Signal

    /** He asked to do less training without being ill or hurt (agency-links §3b: the answer is 「今天照表」). */
    @Serializable @SerialName("reduce_asked")
    data class ReduceAsked(override val id: String, override val at: Long, override val source: String = "chat") : Signal

    @Serializable @SerialName("hungry_morning")
    data class HungryMorning(override val id: String, override val at: Long, override val source: String = "command") : Signal

    @Serializable @SerialName("rest_day")
    data class RestDay(override val id: String, override val at: Long, override val source: String = "command") : Signal

    /** A 休假餐 declared for [day]'s [slot] (L7, T14 reads it for the breach check). */
    @Serializable @SerialName("cheat_declared")
    data class CheatDeclared(override val id: String, override val at: Long, val day: String, val slot: SlotKey, override val source: String = "command") : Signal

    @Serializable @SerialName("ride_start")
    data class RideStart(override val id: String, override val at: Long, override val source: String = "command") : Signal

    /** He said he is sleepy (code-matched words): no riding while sleepy (L22). */
    @Serializable @SerialName("sleepy")
    data class Sleepy(override val id: String, override val at: Long, override val source: String = "chat") : Signal

    /** 「這週考試」 (owner 2026-11-26): the Mon–Sun week of [monday] has one lesson a day; at most twice a semester. */
    @Serializable @SerialName("exam_week")
    data class ExamWeek(override val id: String, override val at: Long, val monday: String, override val source: String = "command") : Signal

    /** The manual weather switch of the day (no weather service): [kind] `RAIN` or `HOT`, switched [on] or off. */
    @Serializable @SerialName("weather")
    data class Weather(override val id: String, override val at: Long, val kind: String, val on: Boolean, override val source: String = "command") : Signal

    /** 全勤小卡: [days] days in a row with every item that was due done (sent on the day after the 7th and the 30th). */
    @Serializable @SerialName("attendance")
    data class Attendance(override val id: String, override val at: Long, val days: Int, override val source: String = "clock") : Signal

    @Serializable @SerialName("ride_end")
    data class RideEnd(override val id: String, override val at: Long, override val source: String = "command") : Signal

    /** A meal record (T14 emits it): its slot, kcal, protein and breaches. */
    @Serializable @SerialName("meal_logged")
    data class MealLogged(
        override val id: String,
        override val at: Long,
        val slot: SlotKey?,
        val kcal: Int,
        val proteinG: Double,
        val breaches: List<String> = emptyList(),
        /** The declared 休假餐 (L7): banned food was allowed in it. */
        val cheat: Boolean = false,
        override val source: String = "meal",
    ) : Signal

    /** Water in half bottles (T17). */
    @Serializable @SerialName("water_logged")
    data class WaterLogged(override val id: String, override val at: Long, val halves: Int, override val source: String = "water") : Signal

    /**
     * A training report (T18); [easy] is his 「還能多做幾下」 ≥ 2 on the last round of the strength rounds, [step] the progression
     * step they were done at (L15 counts only sessions of the current step), [rounds] the rounds done.
     */
    @Serializable @SerialName("training_done")
    data class TrainingDone(
        override val id: String,
        override val at: Long,
        val kind: TrainingKind,
        val easy: Boolean = false,
        val step: Int = 0,
        val rounds: Int? = null,
        override val source: String = "training",
        /** The rounds were done in full (every move at the plan's number). */
        val full: Boolean = true,
        /** He said 「輕鬆」 with this report. */
        val saidEasy: Boolean = false,
    ) : Signal

    /** 「肌力好輕鬆」 said on its own (訓練升級 needs it at least twice). */
    @Serializable @SerialName("felt_easy")
    data class FeltEasy(override val id: String, override val at: Long, override val source: String = "chat") : Signal

    @Serializable @SerialName("grooming_done")
    data class GroomingDone(override val id: String, override val at: Long, val item: GroomingItem, override val source: String = "command") : Signal

    /** Required slots due and missing at a fire ([index] 0 = the push at the slot, then one per 30-minute chase). */
    @Serializable @SerialName("gap_due")
    data class GapDue(override val id: String, override val at: Long, val keys: List<SlotKey>, val index: Int, override val source: String = "scheduler") : Signal

    /**
     * The required slots of coach day [day] that ended undone (the brain emits it at the next day's start, T12; id
     * `missed:<day>`). L14 reads a streak of these.
     */
    @Serializable @SerialName("day_missed")
    data class DayMissed(override val id: String, override val at: Long, val day: String, val keys: List<SlotKey>, override val source: String = "clock") : Signal

    /** Chained by L9: is the day still under the floor? */
    @Serializable @SerialName("floor_check")
    data class FloorCheck(override val id: String, override val at: Long, override val source: String = "link") : Signal

    /** A weigh-in (the scale, later; typed weights with T14). */
    @Serializable @SerialName("weight_logged")
    data class WeightLogged(override val id: String, override val at: Long, val kg: Double, override val source: String = "weight") : Signal

    /** Money spent (L16): a price he wrote with a meal (T14); 記帳 itself is stage 6. [image] is 形象用品 (娛樂費). */
    @Serializable @SerialName("spend_logged")
    data class SpendLogged(override val id: String, override val at: Long, val amount: Int, val image: Boolean, override val source: String = "money") : Signal

    /**
     * The end of a Mon–Sun week (L20; the brain emits it at the next week's first day start, id `week:<monday>`), with the
     * numbers code counted over its days: required items [due] and [done] (the training ones [trainingDue], [trainingDone]),
     * [breaches], polaroids added ([photocards]), whether the week's real-person task was done ([realTask]), whether the week is
     * [onTarget], and how many weeks were on target so far, this one included ([weeksOnTarget]).
     */
    @Serializable @SerialName("week_ended")
    data class WeekEnded(
        override val id: String,
        override val at: Long,
        val monday: String,
        val due: Int = 0,
        val done: Int = 0,
        val trainingDue: Int = 0,
        val trainingDone: Int = 0,
        val breaches: Int = 0,
        val photocards: Int = 0,
        val realTask: Boolean = false,
        val onTarget: Boolean = false,
        val weeksOnTarget: Int = 0,
        /** His level now (C in stage 1). */
        val level: String = "C",
        /** Whether the real-person task counted this week (it opens in its rollout week). */
        val realTaskRequired: Boolean = true,
        override val source: String = "clock",
    ) : Signal

    /** The morning 狀態打分 (T19): [score] 1–5; [ill] when he marked 生病 or 受傷 on the picker. */
    @Serializable @SerialName("condition_scored")
    data class ConditionScored(override val id: String, override val at: Long, val score: Int, val ill: IllKind? = null, override val source: String = "sign") : Signal

    /**
     * The bedtime review answered (T19, L19): what code counted of the day ([done], [missed] required slots, [breaches], the
     * accepted illness change [reduction]) and his two answers ([good] one thing done well, [fix] one thing to fix tomorrow).
     */
    @Serializable @SerialName("review_done")
    data class ReviewDone(
        override val id: String,
        override val at: Long,
        val good: String,
        val fix: String,
        val done: List<SlotKey> = emptyList(),
        val missed: List<SlotKey> = emptyList(),
        val breaches: Int = 0,
        val reduction: String? = null,
        override val source: String = "sign",
    ) : Signal

    /** His yes to the review's 「明天要改」 (L19): the manager says [text] at the sign-in of [forDay]. */
    @Serializable @SerialName("fix_planned")
    data class FixPlanned(override val id: String, override val at: Long, val forDay: String, val text: String, override val source: String = "link") : Signal

    /** The week's real-person task done (L20; 「真人任務完成」). */
    @Serializable @SerialName("real_task_done")
    data class RealTaskDone(override val id: String, override val at: Long, override val source: String = "command") : Signal

    /**
     * A 理論課 lesson passed (T19c): its quiz (first-round [score] of three, [retries] fresh questions after it, or [fallback] to
     * 「寫下三個重點」) and his note are in. [category] is the [app.agency.core.model.LessonCategory] name.
     */
    @Serializable @SerialName("lesson_passed")
    data class LessonPassed(
        override val id: String,
        override val at: Long,
        val lessonId: String,
        val category: String,
        val score: Int? = null,
        val retries: Int = 0,
        val fallback: Boolean = false,
        override val source: String = "lesson",
    ) : Signal

    /** The 兩天後複習考 of a lesson answered (recorded only): right answers out of three. */
    @Serializable @SerialName("lesson_reviewed")
    data class LessonReviewed(override val id: String, override val at: Long, val lessonId: String, val category: String, val score: Int, override val source: String = "lesson") : Signal

    /** A lesson's 實作 handed in (a recording or a photo, sealed on the phone): only that it was handed in goes anywhere. */
    @Serializable @SerialName("practical_handed_in")
    data class PracticalHandedIn(override val id: String, override val at: Long, val lessonId: String, val category: String, override val source: String = "lesson") : Signal

    /**
     * 心情 at sign-out (one tap, 定稿 §2/§3): [level] 1 低落, 2 普通, 3 不錯; [lowDays] the days in a row up to today with a 低落 (code
     * counted), which L30 answers at three.
     */
    @Serializable @SerialName("mood")
    data class Mood(override val id: String, override val at: Long, val day: String, val level: Int, val lowDays: Int, override val source: String = "sign") : Signal

    /** Two 月評s in a row without progress (定稿 §10): the manager's 面談 (L31); id `interview:<yyyy-MM>`. */
    @Serializable @SerialName("month_interview")
    data class MonthInterview(override val id: String, override val at: Long, override val source: String = "eval") : Signal

    companion object {
        const val REMINDERS_ON = "reminders:on"

        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }

        fun encode(s: Signal): String = JSON.encodeToString(serializer(), s)
        fun decode(json: String): Signal? = runCatching { JSON.decodeFromString(serializer(), json) }.getOrNull()

        /** The stored type name (the serial name). */
        fun type(s: Signal): String = JSON.encodeToJsonElement(serializer(), s).let { (it as kotlinx.serialization.json.JsonObject)["type"]!!.toString().trim('"') }
    }
}
