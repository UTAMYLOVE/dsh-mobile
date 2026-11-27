package app.agency.core.brain.handlers

import app.agency.core.brain.SnapshotReader
import app.agency.core.engine.HandIn
import app.agency.core.engine.MonthlyEval
import app.agency.core.engine.MonthlyResult
import app.agency.core.engine.Practice
import app.agency.core.engine.Signal
import app.agency.core.engine.WeeklyResult
import app.agency.core.model.Media
import app.agency.core.model.TrainingKind
import app.agency.core.model.TrainingLog
import app.agency.core.schedule.DayPlan
import app.agency.core.schedule.SlotKey
import app.agency.core.schedule.feature
import app.agency.core.store.AgencyStore
import app.agency.core.time.AgencyClock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The practice items of rollout weeks 3 and 4 ([Practice]), code only: a guided timer's report (proof when it ran at least
 * [TrainingDesk.PROOF_SHARE] of its time; typed is 「未證實」), a tap (防曬, 主題週實作, a 小習慣), a photo (突擊檢查 within its ten
 * minutes, the Sunday 全身對比照) and the evaluations' hand-ins. Each record is a [TrainingLog] of the item's kind and emits
 * `training_done`. Not in today's plan: the line says why (not open yet, not today, the check's time is over).
 */
class PracticeDesk(
    private val store: AgencyStore,
    private val snapshot: SnapshotReader,
    private val clock: AgencyClock,
    private val newId: () -> String,
) {
    private suspend fun plan(): DayPlan = snapshot.plan(snapshot.today(snapshot.profile()))

    private suspend fun count(plan: DayPlan, kind: TrainingKind) = store.training.onDay(plan.day.toString()).count { it.kind == kind }

    /** Why [key] cannot be handed in now, or null. */
    suspend fun refusal(plan: DayPlan, key: SlotKey): String? {
        val slot = plan.slot(key)
        if (slot == null) {
            val r = snapshot.plans.specs().rollout
            if (!r.open(key.feature())) return r.closedLine(key.feature())
            return "今天沒有排${key.zh}。"
        }
        if (count(plan, TrainingKind.valueOf(key.name)) >= (slot.count ?: 1)) return "今天的${key.zh}已經記過了。"
        slot.closesMinute?.let { closes ->
            if (app.agency.core.engine.Links.minuteOf(plan.day, clock.millis(), clock.zone) >= closes) return "${key.zh}超過十分鐘了，這次算沒做到。下次一收到就拍。"
        }
        return null
    }

    private suspend fun write(plan: DayPlan, kind: TrainingKind, detail: String, verified: Boolean): Signal {
        val now = clock.millis()
        val log = TrainingLog(newId(), plan.day.toString(), now, kind, null, detail, verified = verified)
        store.training.insert(log)
        return Signal.TrainingDone("training:${log.id}", now, kind)
    }

    /** A timed item: from the timer ([seconds] it ran) or typed. */
    suspend fun timed(kind: TrainingKind, fromTimer: Boolean, seconds: Int?): MealOutcome {
        val item = Practice.of(kind)?.takeIf { it.handIn == HandIn.TIMER } ?: return MealOutcome(null, listOf(TrainingDesk.WHICH))
        val plan = plan()
        refusal(plan, item.key)?.let { return MealOutcome(null, listOf(it)) }
        val min = Practice.minutes(kind, snapshot.plans.specs().practice)
        val ok = fromTimer && seconds != null && seconds >= min * 60 * TrainingDesk.PROOF_SHARE
        val detail = buildJsonObject {
            put("minutes", min)
            seconds?.let { put("seconds", it) }
            put("timer", fromTimer)
        }.toString()
        val signal = write(plan, kind, detail, ok)
        val proof = when {
            ok -> "照計時做完"
            fromTimer -> "計時只跑了 ${TrainingDesk.minutes(seconds ?: 0)}，未證實"
            else -> "打字回報，未證實"
        }
        return MealOutcome(null, listOf("記下：${item.key.zh} $min 分鐘（$proof）。"), listOf(signal))
    }

    /** A tap item: 防曬, the 主題週 practical, one 小習慣. */
    suspend fun tap(kind: TrainingKind): MealOutcome {
        val item = Practice.of(kind)?.takeIf { it.handIn == HandIn.TAP } ?: return MealOutcome(null, listOf(TrainingDesk.WHICH))
        val plan = plan()
        refusal(plan, item.key)?.let { return MealOutcome(null, listOf(it)) }
        val signal = write(plan, kind, "{}", verified = false)
        val left = (plan.slot(item.key)?.count ?: 1) - count(plan, kind)
        val more = if (kind == TrainingKind.HABIT && left > 0) "還有 $left 個小習慣。" else ""
        return MealOutcome(null, listOf("記下：${item.key.zh}。$more"), listOf(signal))
    }

    /** A photo item ([media] is sealed and stored already): a 突擊檢查 within its minutes, or the Sunday 全身對比照. */
    suspend fun photo(kind: TrainingKind, media: Media): MealOutcome {
        val item = Practice.of(kind)?.takeIf { it.handIn == HandIn.PHOTO } ?: return MealOutcome(null, listOf(TrainingDesk.WHICH))
        val plan = plan()
        refusal(plan, item.key)?.let { return MealOutcome(null, listOf(it)) }
        val signal = write(plan, kind, buildJsonObject { put("media", media.id) }.toString(), verified = true)
        val line = if (kind == TrainingKind.SURPRISE_CHECK) "突擊檢查收到，準時。" else "這週的全身對比照收到，排進時間軸了。"
        return MealOutcome(null, listOf(line), listOf(signal))
    }

    /** 每週評鑑: this week's song and dance, one take each (the screen has no redo). */
    suspend fun weekly(song: String, dance: String): MealOutcome {
        val plan = plan()
        refusal(plan, SlotKey.WEEKLY_EVAL)?.let { return MealOutcome(null, listOf(it)) }
        val signal = write(plan, TrainingKind.WEEKLY_EVAL, JSON.encodeToString(WeeklyResult.serializer(), WeeklyResult(song, dance)), verified = true)
        return MealOutcome(null, listOf("每週評鑑收到：歌一次、舞一次。自己看一次，寫下一個進步、一個問題。"), listOf(signal))
    }

    /** The 月評s so far, oldest first. */
    suspend fun months(): List<MonthlyResult> = store.training.between(SnapshotReader.EARLIEST, "9999-12-31")
        .filter { it.kind == TrainingKind.MONTHLY_EVAL }.sortedBy { it.at }
        .mapNotNull { l -> l.detailJson?.let { runCatching { JSON.decodeFromString(MonthlyResult.serializer(), it) }.getOrNull() } }

    /**
     * 月評 handed in ([result] with its scores already settled by the caller): written, the month's personal task set from the
     * weakest item (the practice section's next version, from tomorrow), the result line; [interview] when two months in a row
     * showed no progress.
     */
    suspend fun monthly(result: MonthlyResult): Pair<MealOutcome, Boolean> {
        val plan = plan()
        refusal(plan, SlotKey.MONTHLY_EVAL)?.let { return MealOutcome(null, listOf(it)) to false }
        val before = months()
        val task = MonthlyEval.weakest(result.scores)?.name
        val r = result.copy(task = task)
        val signal = write(plan, TrainingKind.MONTHLY_EVAL, JSON.encodeToString(MonthlyResult.serializer(), r), verified = true)
        if (task != null) {
            val specs = snapshot.plans.specs()
            store.rules.putSection(
                app.agency.core.model.PlanSectionKey.PRACTICE,
                app.agency.core.schedule.PlanSpecs.JSON.encodeToString(
                    app.agency.core.schedule.PracticeSpec.serializer(),
                    specs.practice.copy(personalTask = task, personalTaskFrom = plan.day.plusDays(1).toString()),
                ),
                app.agency.core.model.PlanSection.BY_MIGRATION, clock.millis(),
            )
        }
        r.weightKg?.let { kg -> store.weights.insert(app.agency.core.model.WeightLog(newId(), plan.day.toString(), clock.millis(), kg, r.waistCm)) }
        return MealOutcome(null, listOf(MonthlyEval.announce(r, before.lastOrNull())), listOf(signal)) to MonthlyEval.interviewDue(before + r)
    }

    /** The Sunday 全身對比照 in time order (the timeline): day and media id. */
    suspend fun bodyTimeline(): List<Pair<String, String>> = store.training.between(SnapshotReader.EARLIEST, "9999-12-31")
        .filter { it.kind == TrainingKind.BODY_PHOTO }.sortedBy { it.at }
        .mapNotNull { l -> l.detailJson?.let { Regex("\"media\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }?.let { l.day to it } }

    companion object {
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** 面談 after two months in a row without progress (定稿 §10): one question, his choice. */
        const val INTERVIEW = "月評連兩個月沒有進步。我們談一下：先換練法，還是先找出卡住的那一項？"
        val INTERVIEW_OPTIONS = listOf("換練法", "找出卡住的那一項")
    }
}
