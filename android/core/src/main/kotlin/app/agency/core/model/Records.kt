package app.agency.core.model

/** A record that belongs to one coach day: [day] is `yyyy-MM-dd` (a day starts at 05:00), [at] epoch millis. */
interface DayRecord {
    val id: String
    val day: String
    val at: Long
}

/**
 * A meal he reported. [text] is his words (≤ 200 characters); [breaches] are the rule labels recorded at the time (history is
 * never recomputed); [verified] means a photo exists ([photoMediaId]), otherwise the meal is 「未證實」.
 */
data class Meal(
    override val id: String,
    override val day: String,
    override val at: Long,
    val slotKey: String? = null,
    val text: String,
    val items: List<MealItem> = emptyList(),
    val cheat: Boolean = false,
    val breaches: List<String> = emptyList(),
    val verified: Boolean = false,
    val photoMediaId: String? = null,
    val orderId: String? = null,
    val legacy: Boolean = false,
    /** 補交 (T15c): a photo picked from the gallery later than the meal; its capture time (null: handed in on time). */
    val lateAt: Long? = null,
) : DayRecord {
    val kcal: Int get() = items.sumOf { it.kcal }
}

/** An estimated item waiting for his label numbers (expires after 7 days). */
data class PendingLabel(val mealId: String, val itemIdx: Int, val name: String, val countedKcal: Int, val askedAt: Long)

enum class OrderStatus { PROPOSED, CONFIRMED, EATEN, DROPPED }

/** The settled 「這餐點什麼」 for one slot; [at] is when it was created. */
data class MealOrder(
    override val id: String,
    override val day: String,
    override val at: Long,
    val slotKey: String,
    val items: List<String>,
    val kcal: Int,
    val proteinG: Double?,
    val placeResultId: String? = null,
    val status: OrderStatus = OrderStatus.PROPOSED,
) : DayRecord

/** A place he saved (only after his confirm). */
data class FavoritePlace(
    val id: String,
    val title: String,
    val url: String?,
    val area: String?,
    val note: String?,
    val savedAt: Long,
    val sourceResultId: String? = null,
)

enum class SearchKind { PLACE, VIDEO }

/** A cached search result (evidence for a card); kept 7 days unless something refers to it. */
data class SearchResult(
    val id: String,
    val kind: SearchKind,
    val query: String,
    val url: String,
    val title: String,
    val summary: String?,
    val date: String?,
    val authority: Double?,
    val rerank: Double?,
    val fetchedAt: Long,
)

/** Water in half bottles (1 = half a 1 L bottle). */
data class WaterLog(
    override val id: String,
    override val day: String,
    override val at: Long,
    val halfBottles: Int,
    val photoMediaId: String? = null,
) : DayRecord

/** [CARDIO] is the 10-minute 體能 (開合跳、波比跳、深蹲跳), three days a week (owner 2026-11-26). */
enum class TrainingKind { CORE7, STRENGTH, STRETCH, WALK, CARDIO }

/** One training report; [detailJson] holds reps per move, plank seconds and the last round's extra. */
data class TrainingLog(
    override val id: String,
    override val day: String,
    override val at: Long,
    val kind: TrainingKind,
    val rounds: Int? = null,
    val detailJson: String? = null,
    val reduced: Boolean = false,
    val verified: Boolean = false,
    val legacy: Boolean = false,
) : DayRecord

/** The morning 狀態打分 (one per day). */
data class ConditionLog(
    val day: String,
    val at: Long,
    val score: Int,
    val sleep: Int? = null,
    val energy: Int? = null,
    val ill: Boolean = false,
    val note: String? = null,
)

enum class SignKind { IN, OUT, RIDE_START, RIDE_END }

/** Sign-in/out and riding marks; a sign-out holds the 睡前回顧 ([reviewGood], [reviewFix]). */
data class SignLog(
    override val id: String,
    override val day: String,
    override val at: Long,
    val kind: SignKind,
    val onTime: Boolean? = null,
    val reviewGood: String? = null,
    val reviewFix: String? = null,
) : DayRecord

data class WeightLog(
    override val id: String,
    override val day: String,
    override val at: Long,
    val kg: Double,
    val waistCm: Double? = null,
) : DayRecord

/**
 * 入社建檔 (id [JOIN]) and later month-end baselines. Photos and the recording are media ids; only the numbers ever reach a
 * prompt. [skipped] and [step] hold onboarding step names.
 */
data class Baseline(
    val id: String,
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    val photoFront: String? = null,
    val photoSide: String? = null,
    val photoBack: String? = null,
    val recording: String? = null,
    val pushupsMax: Int? = null,
    val plankMaxSec: Int? = null,
    val skipped: List<String> = emptyList(),
    val step: String? = null,
    val completedAt: Long? = null,
) {
    companion object {
        const val JOIN = "join"
    }
}

enum class MediaKind { PHOTO, AUDIO }
/** [MENU], [RECEIPT] and [BODY] (T15b/T15c) are the photos the image-capable model may read; recordings never leave the phone. */
enum class MediaPurpose { MEAL, BASELINE, LABEL, WATER, AVATAR, LESSON, MENU, RECEIPT, BODY }

/** A media file's record; the encrypted bytes live in the vault as `<id>.bin`. [bytes] and [sha256] are of the plaintext. */
data class Media(
    val id: String,
    val kind: MediaKind,
    val purpose: MediaPurpose,
    val mime: String,
    val bytes: Long,
    val sha256: String,
    val createdAt: Long,
)

/** 外送收入 of one coach day (owner 2026-11-26: the week's total counts, not one day); a second report replaces it. */
data class DeliveryIncome(val day: String, val amount: Int, val at: Long)
