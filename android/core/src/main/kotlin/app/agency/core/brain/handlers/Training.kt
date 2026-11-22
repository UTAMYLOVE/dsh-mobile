package app.agency.core.brain.handlers

import app.agency.core.brain.SnapshotReader
import app.agency.core.engine.Progression
import app.agency.core.engine.Routine
import app.agency.core.engine.Signal
import app.agency.core.model.TrainingKind
import app.agency.core.model.TrainingLog
import app.agency.core.schedule.DayPlan
import app.agency.core.schedule.SlotKey
import app.agency.core.store.AgencyStore
import app.agency.core.structured.IntakeTraining
import app.agency.core.time.AgencyClock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One report of the strength rounds: [rounds] done (null: he did not say), [last] the reps (the plank: seconds) of each move of
 * the last round by [app.agency.core.engine.StrengthMove.key], [extra] how many more he could have done on the last round
 * (「還能多做幾下」), [fromTimer] it came from the guided timer, which ran [seconds].
 */
data class StrengthReport(
    val rounds: Int?,
    val last: Map<String, Int> = emptyMap(),
    val extra: Int? = null,
    val fromTimer: Boolean = false,
    val seconds: Int? = null,
)

/**
 * 訓練 (plan v3 §7, arch T18), code only: the core 7 minutes, the strength rounds of the current progression step, the 10-minute
 * stretch and the no-class day's brisk walk, from the guided timers or his words. The rules are fixed (owner: 「照女團，死的」):
 * no core and no rounds on a rest day, nor on a day the illness change was accepted (the stretch stays, the minimum); the
 * rounds count only when all of them are done (「今天照表」 otherwise); one of each a day. A timer run counts as proof when it ran
 * at least [PROOF_SHARE] of its timed seconds; typed reports are 「未證實」. Each record emits `training_done` (L11, L14, L15).
 */
class TrainingDesk(
    private val store: AgencyStore,
    private val snapshot: SnapshotReader,
    private val clock: AgencyClock,
    private val newId: () -> String,
) {
    private suspend fun plan(): DayPlan = snapshot.plan(snapshot.today(snapshot.profile()))

    private suspend fun done(plan: DayPlan, kind: TrainingKind) = store.training.onDay(plan.day.toString()).any { it.kind == kind }

    /** Why [key] is not owed today, or null when it is in today's plan. */
    private fun notToday(plan: DayPlan, key: SlotKey, what: String): String? = when {
        plan.slot(key) != null -> null
        plan.rest -> "今天是休息日，不做$what。伸展可以做。"
        plan.reduction != null -> "今天只改這一天：${what}停一天，伸展照做就好。不用補做。"
        key == SlotKey.WALK -> "今天沒有排快走（上課日走路上學就算）。"
        else -> "今天沒有排$what。"
    }

    private fun verified(fromTimer: Boolean, seconds: Int?, timed: Int) = fromTimer && seconds != null && seconds >= timed * PROOF_SHARE

    private suspend fun write(plan: DayPlan, kind: TrainingKind, rounds: Int?, detail: String, verified: Boolean, easy: Boolean = false): Signal {
        val now = clock.millis()
        val log = TrainingLog(newId(), plan.day.toString(), now, kind, rounds, detail, reduced = false, verified = verified)
        store.training.insert(log)
        return Signal.TrainingDone("training:${log.id}", now, kind, easy, plan.progressionStep, rounds)
    }

    private fun proof(fromTimer: Boolean, verified: Boolean, seconds: Int?): String = when {
        verified -> "照計時做完"
        fromTimer -> "計時只跑了 ${minutes(seconds ?: 0)}，未證實"
        else -> "打字回報，未證實"
    }

    /** The core 7 minutes (the timer's [seconds], or typed). */
    suspend fun core(fromTimer: Boolean, seconds: Int?): MealOutcome {
        val plan = plan()
        notToday(plan, SlotKey.CORE, "核心")?.let { return MealOutcome(null, listOf(it)) }
        if (done(plan, TrainingKind.CORE7)) return MealOutcome(null, listOf("今天的核心已經記過了。"))
        val ok = verified(fromTimer, seconds, Routine.timedSeconds(Routine.CORE))
        val detail = buildJsonObject {
            put("moves", Routine.CORE.size)
            seconds?.let { put("seconds", it) }
            put("timer", fromTimer)
        }.toString()
        val signal = write(plan, TrainingKind.CORE7, null, detail, ok)
        return MealOutcome(null, listOf("記下：核心七分鐘（${proof(fromTimer, ok, seconds)}）。"), listOf(signal))
    }

    /** The strength rounds: all of today's rounds, or 「今天照表」 and nothing is written. */
    suspend fun strength(r: StrengthReport): MealOutcome {
        val plan = plan()
        notToday(plan, SlotKey.STRENGTH, "肌力")?.let { return MealOutcome(null, listOf(it)) }
        if (done(plan, TrainingKind.STRENGTH)) return MealOutcome(null, listOf("今天的肌力已經記過了。"))
        val need = plan.strengthRounds
        if (r.rounds != null && r.rounds < need) {
            return MealOutcome(null, listOf("今天照表：肌力要做滿 $need 輪才算完成。做完再回報。"))
        }
        val moves = Routine.moves(plan.progressionStep)
        val timed = Routine.timedSeconds(Routine.strength(plan.progressionStep, need))
        val ok = verified(r.fromTimer, r.seconds, timed)
        val last = moves.mapNotNull { m -> r.last[m.key]?.let { m to it } }
        val full = last.size == moves.size && last.all { (m, n) -> n >= (m.reps ?: m.seconds ?: 0) }
        val easy = full && Progression.easy(r.extra)
        val detail = buildJsonObject {
            put("step", plan.progressionStep)
            put("last", buildJsonObject { last.forEach { (m, n) -> put(m.key, n) } })
            r.extra?.let { put("extra", it) }
            r.seconds?.let { put("seconds", it) }
            put("timer", r.fromTimer)
        }.toString()
        val signal = write(plan, TrainingKind.STRENGTH, r.rounds ?: need, detail, ok, easy)
        val lines = mutableListOf<String>()
        lines += if (last.isEmpty()) {
            "記下：肌力 $need 輪（${if (r.fromTimer) proof(true, ok, r.seconds) else "打字回報，沒有次數，未證實"}）。"
        } else {
            val said = last.joinToString("、") { (m, n) -> m.name + " " + (if (m.seconds != null) "$n 秒" else "$n 下") }
            val more = r.extra?.let { "；還能多做 $it 下" }.orEmpty()
            "記下：肌力 $need 輪（最後一輪：$said$more；${proof(r.fromTimer, ok, r.seconds)}）。"
        }
        last.filter { (m, n) -> n < (m.reps ?: m.seconds ?: 0) }.forEach { (m, n) ->
            val unit = if (m.seconds != null) "秒" else "下"
            lines += "最後一輪${m.name} $n $unit，表定 ${m.reps ?: m.seconds} $unit，下次做滿。"
        }
        if (plan.slot(SlotKey.STRETCH) != null && !done(plan, TrainingKind.STRETCH)) lines += "接著伸展 ${plan.stretchMin} 分鐘。"
        return MealOutcome(null, lines, listOf(signal))
    }

    /** The stretch (any day: a rest day's is allowed, a sick day's is the minimum). */
    suspend fun stretch(fromTimer: Boolean, seconds: Int?): MealOutcome {
        val plan = plan()
        if (done(plan, TrainingKind.STRETCH)) return MealOutcome(null, listOf("今天的伸展已經記過了。"))
        val ok = verified(fromTimer, seconds, plan.stretchMin * 60)
        val detail = buildJsonObject {
            put("minutes", plan.stretchMin)
            seconds?.let { put("seconds", it) }
            put("timer", fromTimer)
        }.toString()
        val signal = write(plan, TrainingKind.STRETCH, null, detail, ok)
        val lines = mutableListOf("記下：伸展 ${plan.stretchMin} 分鐘（${proof(fromTimer, ok, seconds)}）。")
        val owed = listOf(SlotKey.CORE to TrainingKind.CORE7, SlotKey.STRENGTH to TrainingKind.STRENGTH)
            .filter { (k, t) -> plan.slot(k) != null && !done(plan, t) }
        if (owed.isEmpty()) lines += "今天的訓練都做完了。"
        return MealOutcome(null, lines, listOf(signal))
    }

    /** The no-class day's brisk walk (a tap). */
    suspend fun walk(): MealOutcome {
        val plan = plan()
        notToday(plan, SlotKey.WALK, "快走")?.let { return MealOutcome(null, listOf(it)) }
        if (done(plan, TrainingKind.WALK)) return MealOutcome(null, listOf("今天的快走已經記過了。"))
        val signal = write(plan, TrainingKind.WALK, null, buildJsonObject { put("minutes", plan.walkMin) }.toString(), verified = false)
        return MealOutcome(null, listOf("記下：快走 ${plan.walkMin} 分鐘。"), listOf(signal))
    }

    /**
     * His words about training (a command word, or the clerk's `training`): the words decide what (「核心」「肌力」「伸展」「快走」)
     * and whether it is done (「還沒做」 is not); rounds and 「還能多做幾下」 from his own words.
     */
    suspend fun typed(text: String, said: IntakeTraining?): MealOutcome {
        if (TrainingWords.started(text)) return MealOutcome(null, listOf(START))
        if (TrainingWords.notDone(text)) return MealOutcome(null, listOf("做完再回報。"))
        val kind = TrainingWords.kind(text) ?: said?.kind?.let(TrainingWords::ofIntake) ?: return MealOutcome(null, listOf(WHICH))
        return when (kind) {
            TrainingKind.CORE7 -> core(fromTimer = false, seconds = null)
            TrainingKind.STRENGTH -> strength(StrengthReport(TrainingWords.rounds(text) ?: said?.rounds, extra = TrainingWords.extra(text) ?: said?.lastRoundExtra))
            TrainingKind.STRETCH -> stretch(fromTimer = false, seconds = null)
            TrainingKind.WALK -> walk()
        }
    }

    companion object {
        /** A timer run is proof when it ran at least this share of its timed seconds (paused time not counted). */
        const val PROOF_SHARE = 0.8

        const val START = "按首頁的「核心」「肌力」或「伸展」開計時，照著做，做完會自動回報。"
        const val WHICH = "說清楚做了哪一項：核心、肌力、伸展還是快走。"

        fun minutes(seconds: Int): String = if (seconds < 60) "$seconds 秒" else "${seconds / 60} 分鐘"
    }
}

/** His words about training (code rules on top of the data clerk, arch T18 「negation rules kept」). */
object TrainingWords {
    private val NOT_DONE = Regex("還沒(?:有)?(?:做|練)|沒(?:有)?(?:做|練)(?!完)|不(?:做|練)了|等(?:一)?下(?:再)?(?:做|練)|待會|晚點(?:再)?(?:做|練)|要去(?:做|練)")
    private val STARTED = Regex("^(?:核心|肌力|伸展)開始$")
    private val ROUNDS = Regex("([0-9一二兩三四五六])\\s*輪")
    private val EXTRA = Regex("(?:還能|還可以|可以再)多做\\s*([0-9一二兩三四五六七八九十]+)\\s*下")

    fun notDone(text: String): Boolean = NOT_DONE.containsMatchIn(text)

    fun started(text: String): Boolean = STARTED.matches(text.trim())

    fun kind(text: String): TrainingKind? = when {
        text.contains("核心") -> TrainingKind.CORE7
        text.contains("伸展") || text.contains("拉筋") -> TrainingKind.STRETCH
        text.contains("快走") || text.contains("散步") -> TrainingKind.WALK
        Regex("肌力|伏地挺身|深蹲|棒式|划船|臀橋|輪").containsMatchIn(text) -> TrainingKind.STRENGTH
        else -> null
    }

    fun ofIntake(kind: String): TrainingKind? = when (kind) {
        "core7" -> TrainingKind.CORE7
        "strength" -> TrainingKind.STRENGTH
        "stretch" -> TrainingKind.STRETCH
        "walk" -> TrainingKind.WALK
        else -> null
    }

    private fun number(s: String): Int? = s.toIntOrNull() ?: mapOf("一" to 1, "二" to 2, "兩" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10)[s]

    /** 「做了兩輪」 → 2 (the slot's own name 「肌力三輪」 reads as 3, as it should). */
    fun rounds(text: String): Int? = ROUNDS.find(text)?.groupValues?.get(1)?.let(::number)

    /** 「最後一輪還能多做三下」 → 3. */
    fun extra(text: String): Int? = EXTRA.find(text)?.groupValues?.get(1)?.let(::number)
}
