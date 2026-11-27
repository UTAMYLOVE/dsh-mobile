package app.agency.core.engine

import app.agency.core.model.CompanyRules
import app.agency.core.model.EffectKind
import app.agency.core.model.EffectStatus
import app.agency.core.model.Message
import app.agency.core.model.MessageMode
import app.agency.core.model.MessageRefs
import app.agency.core.model.NerveEffect
import app.agency.core.model.Proposal
import app.agency.core.model.ProposalKind
import app.agency.core.model.ProposalStatus
import app.agency.core.model.StoredSignal
import app.agency.core.model.Who
import app.agency.core.model.WordedBy
import app.agency.core.rules.CoachRules
import app.agency.core.schedule.DayAdjust
import app.agency.core.schedule.DayPlan
import app.agency.core.schedule.PlanSpecs
import app.agency.core.schedule.PlanStore
import app.agency.core.schedule.Reduction
import app.agency.core.store.AgencyStore
import app.agency.core.time.AgencyClock
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.Json

/** What one signal did: the effects written, the lines said (as messages) and the notifications to post. */
data class NerveResult(val effects: List<NerveEffect> = emptyList(), val messages: List<Message> = emptyList()) {
    operator fun plus(o: NerveResult) = NerveResult(effects + o.effects, messages + o.messages)
    val notifies: List<NerveEffect> get() = effects.filter { it.kind == EffectKind.NOTIFY }
}

/**
 * 連動中樞 (agency-links §4): a signal comes in → stored (once: its id de-dups) → today's state recomputed from the signals →
 * every link runs in order → effects applied: a 今天微調 only within [AdjustBounds] (with its line, cancellable for the day), a
 * proposal into the proposal table (applied only on his yes), a notification left for the scheduler, a line written as a
 * company message, a note kept. Chains go at most [MAX_DEPTH] levels; each link answers at most its daily cap of signals.
 * Company rules are read, never written: no effect can carry them.
 *
 * Not thread safe on its own: the brain's queue calls it (one event at a time).
 */
class Nerve(
    private val store: AgencyStore,
    private val clock: AgencyClock,
    private val newId: () -> String,
    val links: List<Link> = Links.all(),
) {
    private val plans = PlanStore(store, clock.zone)

    fun dayOf(at: Long, dayStartHour: Int = 5): LocalDate = CoachRules.coachDay(Instant.ofEpochMilli(at), clock.zone, dayStartHour)

    private suspend fun dayStartHour(): Int = store.profile.get()?.dayStartHour ?: 5

    /** Today's 今天微調 and the accepted illness change, for the day planner. */
    suspend fun changes(day: LocalDate): Pair<List<DayAdjust>, Reduction?> {
        val effects = store.nerve.effects(day.toString())
        return DayStateBuilder.adjustments(effects) to DayStateBuilder.reduction(effects)
    }

    suspend fun plan(day: LocalDate): DayPlan {
        val (adjust, reduction) = changes(day)
        return plans.plan(day, adjust, reduction)
    }

    /** The day state of [day] now. */
    suspend fun state(day: LocalDate): DayState {
        val effects = store.nerve.effects(day.toString())
        val signals = store.nerve.signals(day.minusDays(DayStateBuilder.LOOKBACK_DAYS).toString(), day.toString())
        val progression = store.nerve.effectsWith(EffectStatus.PROPOSED).any { it.link == Links.L15Progression.id }
        // 理論課 (T19c): what is left in 「課程清單」 once it started, and the 複習考 of today not taken yet.
        val lessons = store.lessons.all()
        val left = if (lessons.any { it.listedAt != null }) app.agency.core.lessons.LessonPlan.queue(lessons).size else null
        val reviews = lessons.count { it.reviewDay == day.toString() && it.reviewedAt == null }
        return DayStateBuilder.build(day, plan(day), signals, effects).copy(pendingProgression = progression, lessonsLeft = left, reviewsDue = reviews)
    }

    /** Takes one signal (a repeat of a handled id does nothing). */
    suspend fun emit(signal: Signal): NerveResult = emit(signal, 0, null)

    /** Takes one signal that belongs to [day], not to the day of its time (a sign-out after the day boundary, S1). */
    suspend fun emitFor(signal: Signal, day: LocalDate): NerveResult = emit(signal, 0, null, day)

    private suspend fun emit(signal: Signal, depth: Int, cause: String?, forDay: LocalDate? = null): NerveResult {
        val day = forDay ?: dayOf(signal.at, dayStartHour())
        val stored = StoredSignal(signal.id, day.toString(), signal.at, Signal.type(signal), Signal.encode(signal), depth, cause)
        if (!store.nerve.insertSignal(stored)) return NerveResult()
        val state = state(day)
        val base = plans.plan(day)
        val rules = store.rules.current() ?: CompanyRules()
        val ctx = LinkContext(clock.now(), clock.zone, rules, plans.specs(), store.profile.get()?.joinDate)
        var result = NerveResult()
        var msgAt = maxOf(signal.at, clock.millis())
        for (link in links) {
            val answered = store.nerve.effects(day.toString()).filter { it.link == link.id }.map { it.signalId }.toSet()
            if (answered.size >= link.dailyCap) continue
            // 分週上線: a link of a feature not open yet answers nothing.
            if (link.feature?.let { !ctx.specs.open(it) } == true) continue
            val effects = link.fire(signal, state, ctx)
            for (e in effects) {
                val row = { kind: EffectKind, status: EffectStatus, because: String, proposal: String?, due: Long? ->
                    NerveEffect(newId(), day.toString(), clock.millis(), link.id, signal.id, kind, encodeEffect(e), because, status, proposal, due)
                }
                when (e) {
                    is Effect.AdjustToday -> {
                        if (!AdjustBounds.allows(e.change, state.plan, base, ctx.specs.meals.firstMealGapMin)) continue
                        result += write(row(EffectKind.ADJUST, EffectStatus.ACTIVE, e.because, null, null))
                        if (e.change == DayAdjust.SoyMorning) soyMorning(day, true)
                    }
                    is Effect.Proposal -> {
                        val pid = newId()
                        val kind = when {
                            e.progression != null -> ProposalKind.PROGRESSION
                            e.rolloutWeek != null -> ProposalKind.ROLLOUT_NEXT
                            e.fixTomorrow != null -> ProposalKind.TOMORROW_FIX
                            e.todayOnly -> ProposalKind.TODAY_CHANGE
                            else -> ProposalKind.RULES_SUGGESTION
                        }
                        val role = if (kind == ProposalKind.PROGRESSION) app.agency.core.model.RoleKey.BODY else Links.SPEAKER
                        store.proposals.insert(Proposal(pid, clock.millis(), role, kind, target = link.id, diffJson = encodeEffect(e), reason = e.because))
                        result += write(row(EffectKind.PROPOSAL, EffectStatus.PROPOSED, e.because, pid, null))
                    }
                    is Effect.Notify -> result += write(row(EffectKind.NOTIFY, EffectStatus.PENDING, e.text, null, e.at ?: signal.at))
                    is Effect.Say -> {
                        result += write(row(EffectKind.SAY, EffectStatus.DONE, e.text, null, null))
                        msgAt += 1
                        val m = Message(
                            newId(), msgAt, Who.COMPANY, role = e.role, kind = KIND,
                            mode = if (e.discuss) MessageMode.DISCUSS else MessageMode.NORMAL, text = e.text,
                            refs = MessageRefs(options = e.options), wordedBy = WordedBy.TEMPLATE,
                        )
                        store.messages.insert(m)
                        result += NerveResult(messages = listOf(m))
                    }
                    is Effect.Record -> result += write(row(EffectKind.RECORD, EffectStatus.DONE, e.what, null, null))
                    is Effect.Progress -> {
                        val ladder = Progression.Ladder.valueOf(e.ladder)
                        val t = plans.specs().training
                        val next = Progression.up(t, ladder, day)
                        store.rules.putSection(
                            app.agency.core.model.PlanSectionKey.TRAINING, PlanSpecs.JSON.encodeToString(app.agency.core.schedule.TrainingSpec.serializer(), next),
                            link.id, clock.millis(),
                        )
                        result += write(row(EffectKind.RECORD, EffectStatus.DONE, e.because, null, null))
                    }
                    is Effect.Chain -> if (depth + 1 < MAX_DEPTH) result += emit(e.signal, depth + 1, signal.id, forDay)
                }
            }
        }
        return result
    }

    private suspend fun write(e: NerveEffect): NerveResult {
        store.nerve.insertEffect(e)
        return NerveResult(effects = listOf(e))
    }

    private suspend fun soyMorning(day: LocalDate, on: Boolean) {
        val o = store.days.get(day.toString()) ?: if (on) app.agency.core.model.DayOverride(day.toString(), declaredAt = clock.millis()) else return
        store.days.put(o.copy(soyMorning = on))
    }

    /** Today's 「今天的連動」: every effect of [day], oldest first. */
    suspend fun today(day: LocalDate): List<NerveEffect> = store.nerve.effects(day.toString())

    /** He cancels a 今天微調 of today (recorded: the row stays, CANCELLED). False when it is not one, or not today's. */
    suspend fun cancel(effectId: String): Boolean {
        val e = store.nerve.effect(effectId) ?: return false
        val today = dayOf(clock.millis(), dayStartHour()).toString()
        if (e.kind != EffectKind.ADJUST || e.status != EffectStatus.ACTIVE || e.day != today) return false
        store.nerve.setStatus(e.id, EffectStatus.CANCELLED, clock.millis())
        if ((decodeEffect(e.json) as? Effect.AdjustToday)?.change == DayAdjust.SoyMorning) soyMorning(LocalDate.parse(e.day), false)
        return true
    }

    /** The link proposals still waiting for him (yesterday's today-only ones expire first). */
    suspend fun pending(): List<NerveEffect> {
        expire()
        return store.nerve.effectsWith(EffectStatus.PROPOSED)
    }

    /** Today-only proposals of an earlier day lapse unanswered: the plan stood (L2 「沒回答就照原計畫」). */
    suspend fun expire() {
        val today = dayOf(clock.millis(), dayStartHour()).toString()
        store.nerve.effectsWith(EffectStatus.PROPOSED).filter { it.day < today }.forEach { e ->
            val p = (decodeEffect(e.json) as? Effect.Proposal) ?: return@forEach
            // Tomorrow's fix (L19) is answered the night it is made: by the next day it has nothing left to set up.
            if (!p.todayOnly && p.fixTomorrow == null) return@forEach
            store.nerve.setStatus(e.id, EffectStatus.EXPIRED, clock.millis())
            e.proposalId?.let { id -> store.proposals.get(id)?.let { store.proposals.upsert(it.copy(status = ProposalStatus.EXPIRED, decidedAt = clock.millis())) } }
        }
    }

    /**
     * His answer to a link proposal (a tap, or 「好」/「不要」 while it is the only one). A today-only illness change applies only
     * when it came from L2 for an illness or injury signal of today; anything else (another day, another origin) is refused.
     * Returns the line to say, or null when there is no such proposal.
     */
    suspend fun decide(effectId: String, accept: Boolean): String? {
        expire()
        val e = store.nerve.effect(effectId) ?: return null
        if (e.kind != EffectKind.PROPOSAL || e.status != EffectStatus.PROPOSED) return null
        val p = decodeEffect(e.json) as? Effect.Proposal ?: return null
        val now = clock.millis()
        if (!accept) {
            mark(e, EffectStatus.REJECTED, ProposalStatus.REJECTED, now)
            return if (p.reduction != null) "好，今天照原本的表。" else "好，不改。"
        }
        if (p.reduction != null) {
            val origin = store.nerve.signal(e.signalId)?.let { Signal.decode(it.json) } as? Signal.Unwell
            val today = dayOf(now, dayStartHour()).toString()
            if (e.link != Links.L2Illness.id || origin == null || origin.kind == IllKind.UNKNOWN || e.day != today) {
                mark(e, EffectStatus.EXPIRED, ProposalStatus.EXPIRED, now)
                return "這個調整已經不能用了，今天照表。"
            }
            mark(e, EffectStatus.ACCEPTED, ProposalStatus.ACCEPTED, now)
            return "好，只改今天：${p.change}不用明天補做，好好休息。"
        }
        p.progression?.let { next -> return progress(e, next, now) }
        p.rolloutWeek?.let { w -> return openWeek(e, w, now) }
        p.habit?.let { subject -> return addHabit(e, subject, now) }
        p.fixTomorrow?.let { fix ->
            mark(e, EffectStatus.ACCEPTED, ProposalStatus.ACCEPTED, now)
            emit(Signal.FixPlanned("fix:${e.id}", now, LocalDate.parse(e.day).plusDays(1).toString(), fix), 1, e.signalId)
            return "好，明天起床簽到的時候我提醒你：$fix"
        }
        mark(e, EffectStatus.ACCEPTED, ProposalStatus.ACCEPTED, now)
        return p.suggestQuota?.let { "好。公司規章要你自己改：到設定的公司規章，把每天的熱量改成 $it，確認兩次。" } ?: "好。"
    }

    /**
     * His yes to the next strength step (L15): checked again against the training plan as it is now (another change since
     * makes it lapse), then the next version of the training section, changed by this proposal.
     */
    private suspend fun progress(e: NerveEffect, next: Int, now: Long): String {
        val specs = plans.specs()
        if (specs.training.progressionStep != next - 1) {
            mark(e, EffectStatus.EXPIRED, ProposalStatus.EXPIRED, now)
            return "訓練在這之後改過了，這個升級對不上，沒有改。"
        }
        val t = specs.training.copy(progressionStep = next)
        val written = store.rules.putSection(
            app.agency.core.model.PlanSectionKey.TRAINING, PlanSpecs.JSON.encodeToString(app.agency.core.schedule.TrainingSpec.serializer(), t),
            e.proposalId ?: e.id, now,
        )
        mark(e, EffectStatus.ACCEPTED, ProposalStatus.ACCEPTED, now)
        e.proposalId?.let { id -> store.proposals.get(id)?.let { store.proposals.upsert(it.copy(appliedVersion = written.version)) } }
        val rules = store.rules.current() ?: CompanyRules()
        return "好，下一次肌力開始：${Progression.note(t)}。加量的日子蛋白質一定要吃夠 ${rules.proteinG} 克。"
    }

    /** His yes to the next rollout week (L25): only from the week before it (another change since makes it lapse). */
    private suspend fun openWeek(e: NerveEffect, w: Int, now: Long): String {
        val r = plans.specs().rollout
        if (r.week != w - 1) {
            mark(e, EffectStatus.EXPIRED, ProposalStatus.EXPIRED, now)
            return "上線週數在這之後改過了，這張卡對不上，沒有改。"
        }
        val next = r.next(dayOf(now, dayStartHour()))
        store.rules.putSection(
            app.agency.core.model.PlanSectionKey.ROLLOUT, PlanSpecs.JSON.encodeToString(app.agency.core.schedule.RolloutSpec.serializer(), next),
            e.proposalId ?: e.id, now,
        )
        mark(e, EffectStatus.ACCEPTED, ProposalStatus.ACCEPTED, now)
        return "好，第 ${next.week} 週開始。新開放：${next.opensIn(next.week).joinToString("、") { it.zh }}。"
    }

    /** His yes to a 學了就用 habit (L33): checked again (one at a time), then the practice section's next version, from tomorrow. */
    private suspend fun addHabit(e: NerveEffect, subject: String, now: Long): String {
        val specs = plans.specs()
        val cat = app.agency.core.model.LessonCategory.parse(subject)
        val o = cat?.let { Habits.offer(it) }
        if (o == null || specs.practice.habits.any { it.category == subject }) {
            mark(e, EffectStatus.EXPIRED, ProposalStatus.EXPIRED, now)
            return "這個小習慣已經有了，沒有再加。"
        }
        val from = dayOf(now, dayStartHour()).plusDays(1)
        val h = app.agency.core.schedule.Habit(e.id, o.text, subject, from.toString(), o.at)
        store.rules.putSection(
            app.agency.core.model.PlanSectionKey.PRACTICE, PlanSpecs.JSON.encodeToString(app.agency.core.schedule.PracticeSpec.serializer(), specs.practice.copy(habits = specs.practice.habits + h)),
            e.proposalId ?: e.id, now,
        )
        mark(e, EffectStatus.ACCEPTED, ProposalStatus.ACCEPTED, now)
        return "好，明天起每天：${o.text}。做完說「小習慣完成」。"
    }

    private suspend fun mark(e: NerveEffect, status: EffectStatus, ps: ProposalStatus, at: Long) {
        store.nerve.setStatus(e.id, status, at)
        e.proposalId?.let { id -> store.proposals.get(id)?.let { store.proposals.upsert(it.copy(status = ps, decidedAt = at)) } }
    }

    /** Notifications due by [now] and not posted yet, oldest first. */
    suspend fun dueNotifies(now: Long): List<NerveEffect> =
        store.nerve.effectsWith(EffectStatus.PENDING).filter { (it.dueAt ?: it.at) <= now }

    /** The next notification time after [now], if any is waiting. */
    suspend fun nextNotify(now: Long): Long? = store.nerve.effectsWith(EffectStatus.PENDING).mapNotNull { it.dueAt }.filter { it > now }.minOrNull()

    suspend fun markSent(ids: List<String>) = ids.forEach { store.nerve.setStatus(it, EffectStatus.SENT, clock.millis()) }

    companion object {
        /** A signal and at most two chained levels after it. */
        const val MAX_DEPTH = 3

        /** The kind of a line a link said. */
        const val KIND = "link"

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }
        fun encodeEffect(e: Effect): String = JSON.encodeToString(Effect.serializer(), e)
        fun decodeEffect(json: String): Effect? = runCatching { JSON.decodeFromString(Effect.serializer(), json) }.getOrNull()
    }
}
