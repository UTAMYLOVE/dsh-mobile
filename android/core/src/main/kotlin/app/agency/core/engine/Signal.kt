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

    @Serializable @SerialName("signed_out")
    data class SignedOut(override val id: String, override val at: Long, override val source: String = "sign") : Signal

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

    /** A training report (T18); [easy] is his 「還能多做幾下」 ≥ 2 on the last round. */
    @Serializable @SerialName("training_done")
    data class TrainingDone(override val id: String, override val at: Long, val kind: TrainingKind, val easy: Boolean = false, override val source: String = "training") : Signal

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

    /** The end of a Mon–Sun week (stage 2, L20). */
    @Serializable @SerialName("week_ended")
    data class WeekEnded(override val id: String, override val at: Long, val monday: String, override val source: String = "clock") : Signal

    companion object {
        const val REMINDERS_ON = "reminders:on"

        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }

        fun encode(s: Signal): String = JSON.encodeToString(serializer(), s)
        fun decode(json: String): Signal? = runCatching { JSON.decodeFromString(serializer(), json) }.getOrNull()

        /** The stored type name (the serial name). */
        fun type(s: Signal): String = JSON.encodeToJsonElement(serializer(), s).let { (it as kotlinx.serialization.json.JsonObject)["type"]!!.toString().trim('"') }
    }
}
