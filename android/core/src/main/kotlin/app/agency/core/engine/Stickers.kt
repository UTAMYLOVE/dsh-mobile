package app.agency.core.engine

import app.agency.core.brain.TodayRecords
import app.agency.core.model.SignKind
import app.agency.core.model.TrainingKind
import app.agency.core.schedule.SlotKey
import app.agency.core.schedule.SlotKind

/** One sticker of the day's 手帳 strip (plan v3 §17): one per done item. [zh] is the label under it. */
enum class StickerKind(val zh: String) {
    SIGN_IN("簽到"),
    MEAL("吃飯"),
    WATER("喝水"),
    CORE("核心"),
    STRENGTH("肌力"),
    STRETCH("伸展"),
    WALK("快走"),
    CARDIO("體能"),
    RIDE("跑單"),
    SIGN_OUT("簽退"),
    LESSON("理論課"),
    VOCAL("聲樂"),
    DANCE("舞蹈"),
    POSTURE("站姿"),
    BEAUTY("防曬保養"),
    EVAL("評鑑"),
    CHECK("突擊檢查"),
    HABIT("小習慣"),
}

/** A sticker earned today; [ref] is the record (or slot) it stands for, so the same item never earns two. */
data class Sticker(val kind: StickerKind, val ref: String)

/**
 * Today's done items → stickers, computed by code. [fromRecords] counts today's records by kind (one sticker per meal, per
 * water report, per training of each kind, one per sign-in, ride and sign-out); once the day planner knows the slots (T11),
 * the card uses [fromSlots] (one sticker per done slot of the plan).
 */
object Stickers {
    fun fromRecords(r: TodayRecords): List<Sticker> = buildList {
        r.signs.firstOrNull { it.kind == SignKind.IN }?.let { add(Sticker(StickerKind.SIGN_IN, it.id)) }
        r.meals.forEach { add(Sticker(StickerKind.MEAL, it.id)) }
        r.water.forEach { add(Sticker(StickerKind.WATER, it.id)) }
        TrainingKind.entries.forEach { k ->
            r.training.firstOrNull { it.kind == k }?.let { add(Sticker(training(k), it.id)) }
        }
        r.signs.firstOrNull { it.kind == SignKind.RIDE_START }?.let { add(Sticker(StickerKind.RIDE, it.id)) }
        r.signs.lastOrNull { it.kind == SignKind.OUT }?.let { add(Sticker(StickerKind.SIGN_OUT, it.id)) }
    }.sortedBy { it.kind.ordinal }

    /** One sticker per done slot of today's plan, plus one per water report (water has no slot). */
    fun fromSlots(states: List<SlotState>, r: TodayRecords): List<Sticker> = buildList {
        states.filter { it.status == SlotStatus.DONE }.forEach { st ->
            add(Sticker(of(st.key), st.key.name))
        }
        r.water.forEach { add(Sticker(StickerKind.WATER, it.id)) }
    }.sortedBy { it.kind.ordinal }

    fun of(key: SlotKey): StickerKind = when (key.kind) {
        SlotKind.MEAL -> StickerKind.MEAL
        SlotKind.RIDE -> StickerKind.RIDE
        SlotKind.SIGN -> if (key == SlotKey.WAKE) StickerKind.SIGN_IN else StickerKind.SIGN_OUT
        SlotKind.STUDY -> StickerKind.LESSON
        SlotKind.PRACTICE -> practice(key)
        SlotKind.TRAINING -> when (key) {
            SlotKey.CORE -> StickerKind.CORE
            SlotKey.STRENGTH -> StickerKind.STRENGTH
            SlotKey.WALK -> StickerKind.WALK
            SlotKey.CARDIO -> StickerKind.CARDIO
            else -> StickerKind.STRETCH
        }
    }

    fun training(k: TrainingKind): StickerKind = when (k) {
        TrainingKind.CORE7 -> StickerKind.CORE
        TrainingKind.STRENGTH -> StickerKind.STRENGTH
        TrainingKind.STRETCH -> StickerKind.STRETCH
        TrainingKind.WALK -> StickerKind.WALK
        TrainingKind.CARDIO -> StickerKind.CARDIO
        else -> SlotKey.parse(k.name)?.let(::practice) ?: StickerKind.STRETCH
    }

    private fun practice(key: SlotKey): StickerKind = when (key) {
        SlotKey.VOCAL_BASICS, SlotKey.VOCAL_PRACTICE -> StickerKind.VOCAL
        SlotKey.DANCE, SlotKey.EXPRESSION -> StickerKind.DANCE
        SlotKey.POSTURE -> StickerKind.POSTURE
        SlotKey.SUNSCREEN -> StickerKind.BEAUTY
        SlotKey.WEEKLY_EVAL, SlotKey.MONTHLY_EVAL, SlotKey.BODY_PHOTO -> StickerKind.EVAL
        SlotKey.SURPRISE_CHECK -> StickerKind.CHECK
        SlotKey.HABIT, SlotKey.THEME_TASK, SlotKey.PERSONAL_TASK -> StickerKind.HABIT
        else -> StickerKind.STRETCH
    }
}
