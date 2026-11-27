package app.agency.core.brain

import app.agency.core.brain.handlers.BodyWords
import app.agency.core.brain.handlers.CheatDeclare
import app.agency.core.brain.handlers.CravingDesk
import app.agency.core.brain.handlers.MealDesk
import app.agency.core.brain.handlers.MealDiscussion
import app.agency.core.brain.handlers.MealTask
import app.agency.core.brain.handlers.AreaText
import app.agency.core.brain.handlers.MealOutcome
import app.agency.core.brain.handlers.MealWords
import app.agency.core.brain.handlers.WeightDesk
import app.agency.core.brain.handlers.WaterDesk
import app.agency.core.brain.handlers.HungryMorning
import app.agency.core.brain.handlers.IdolProfile
import app.agency.core.brain.handlers.RestDay
import app.agency.core.brain.handlers.Sign
import app.agency.core.brain.handlers.ReviewDesk
import app.agency.core.brain.handlers.Onboarding
import app.agency.core.brain.handlers.OnboardingStatus
import app.agency.core.brain.handlers.OnboardingStep
import app.agency.core.engine.IdolCard
import app.agency.core.engine.SlotStatus
import app.agency.core.llm.Thinking
import app.agency.core.llm.Thoughts
import app.agency.core.llm.Turn
import app.agency.core.llm.Wire
import app.agency.core.memory.MemoryApplier
import app.agency.core.memory.UndoResult
import app.agency.core.model.MemoryEvent
import app.agency.core.model.MemoryOp
import app.agency.core.model.MessageRefs
import app.agency.core.model.Proposal
import app.agency.core.model.ProposalStatus
import app.agency.core.model.StoredThinking
import app.agency.core.engine.Effect
import app.agency.core.engine.Fire
import app.agency.core.engine.Gaps
import app.agency.core.engine.GroomingItem
import app.agency.core.engine.Links
import app.agency.core.engine.NextFire
import app.agency.core.engine.Nudger
import app.agency.core.engine.Post
import app.agency.core.engine.PostChannel
import app.agency.core.engine.Tick
import app.agency.core.engine.IdolCardFacts
import app.agency.core.engine.Nerve
import app.agency.core.engine.NerveResult
import app.agency.core.engine.Signal
import app.agency.core.engine.Stickers
import app.agency.core.engine.ToneDecider
import app.agency.core.engine.ToneInputs
import app.agency.core.guard.Evidence
import app.agency.core.guard.OutputGuard
import app.agency.core.guard.SecondPassJudge
import app.agency.core.llm.Fail
import app.agency.core.llm.LlmClient
import app.agency.core.llm.ProviderChoice
import app.agency.core.llm.Purpose
import app.agency.core.memory.MemoryBlocks
import app.agency.core.model.Media
import app.agency.core.model.EffectStatus
import app.agency.core.model.Message
import app.agency.core.model.NerveEffect
import app.agency.core.model.MessageMode
import app.agency.core.model.RoleKey
import app.agency.core.model.Who
import app.agency.core.model.WordedBy
import app.agency.core.persona.PromptBuilder
import app.agency.core.persona.PromptInput
import app.agency.core.persona.ReplyKind
import app.agency.core.schedule.SlotKey
import app.agency.core.structured.NudgeLine
import app.agency.core.persona.Roles
import app.agency.core.rules.FoodTable
import app.agency.core.rules.RulesBook
import app.agency.core.store.AgencyStore
import app.agency.core.structured.Intake
import app.agency.core.structured.Structured
import app.agency.core.structured.StructuredCaller
import app.agency.core.time.AgencyClock
import app.agency.core.tools.ToolRegistry
import app.agency.core.tools.RoundContext
import app.agency.core.tools.chatTools
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one queue every inbound event goes through (arch 1.3): one at a time, so a message, a button and an alarm never
 * interleave writes. His message is stored before any model call, so a failed call never loses it; with no network at all it
 * stays marked 「沒送出」 and [resend] answers the same row later.
 *
 * Code first: 入社建檔 answers, 藝名, his yes/no to the one waiting proposal, illness and 「今天照表」 (the nerve), 「先這樣」 in a
 * discussion, command words and attempts to change 公司規章 never reach a model. Anything else goes to the data clerk (Intake)
 * for its kind; conversation goes to the manager's [ChatLoop] with the ten tools (T12), in 討論 mode while a discussion is
 * open, whose reply has passed the output guard or is the template. When the provider gives no answer the reply is the
 * template plus a fixed line saying why ([failLine]); [lastFail] keeps that reason for 設定 › 模型與金鑰. On a day over the
 * quota or with a breach, or while he discusses food, a reply that passed the guard also goes to the second-pass judge
 * (arch 1.9b) on the data clerk's client. Her thinking behind every reply is kept and sent back to the provider that made it
 * (owner's choice 乙; [Thoughts]).
 */
class Brain(
    private val store: AgencyStore,
    private val llm: LlmClient,
    private val provider: () -> ProviderChoice,
    private val clock: AgencyClock,
    /**
     * The data clerk's and the second-pass judge's client: the cheapest model of the same provider (the app wires it; never
     * another provider).
     */
    private val intakeLlm: LlmClient = llm,
    /** One line per event worth a diagnostic (the app's error log); never a key, a prompt, a reply or thinking. */
    private val log: (String) -> Unit = {},
    /** Nearby food search (T16, DeepSeek Search through the network gate); null leaves `search_places` without results. */
    private val search: app.agency.core.llm.SearchClient? = null,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()

    /** 連動中樞 (T11b): features emit signals, links answer them. */
    val nerve = Nerve(store, clock, newId)
    private val snapshot = SnapshotReader(store, clock) { nerve.changes(it) }
    private val structured = StructuredCaller(intakeLlm, provider)
    private val judge = SecondPassJudge(intakeLlm, provider)
    private val loop = ChatLoop(llm, provider, judge = judge)
    private val proposals = ProposalApplier(store, clock, newId)
    private val onboarding = Onboarding(store, clock, newId)
    private val idol = IdolProfile(store)
    private val restDay = RestDay(store, snapshot.plans)
    private val hungry = HungryMorning(store)
    private val cheat = CheatDeclare(store, snapshot.plans, clock.zone)
    private val sign = Sign(store, clock.zone, newId, snapshot.plans)
    private val conditions = app.agency.core.brain.handlers.ConditionDesk(store, newId)
    private val review = app.agency.core.brain.handlers.ReviewDesk(store)
    private val meals = MealDesk(store, snapshot, clock, newId)
    private val cravings = CravingDesk(store, snapshot, clock, meals)
    private val weights = WeightDesk(store, snapshot, clock, newId)
    private val water = WaterDesk(store, snapshot, clock, newId)
    private val training = app.agency.core.brain.handlers.TrainingDesk(store, snapshot, clock, newId)
    private val income = app.agency.core.brain.handlers.IncomeDesk(store)
    private val memoDesk = app.agency.core.brain.handlers.MemoDesk(store, clock.zone, newId)
    private val lessons = app.agency.core.brain.handlers.LessonDesk(store, clock, newId, structured, search) { snapshot.today(snapshot.profile()) }

    private val _busy = MutableStateFlow(false)
    /** True while a message is being answered. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _revision = MutableStateFlow(0L)
    /** Bumped after every message written, so the conversation on screen reloads. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val _lastFail = MutableStateFlow<Fail?>(null)
    /** Why the last model call gave no answer (null after a call that answered). */
    val lastFail: StateFlow<Fail?> = _lastFail.asStateFlow()

    /** Answers one message of his; returns what was written (his message first). Blank text writes nothing. */
    suspend fun onText(text: String): List<Message> = mutex.withLock {
        val clean = text.trim().take(MAX_OWNER_CHARS)
        if (clean.isEmpty()) return@withLock emptyList()
        _busy.value = true
        try {
            val owner = Message(newId(), clock.millis(), Who.OWNER, text = clean)
            store.messages.insert(owner)
            bump()
            // The day's start first (its lines, a 討論 question of L14 too, are part of what he is answering).
            val started = ensureDay()
            val history = store.messages.recent(FactsBuilder.MAX_MESSAGES + 1).filter { it.id != owner.id }.takeLast(FactsBuilder.MAX_MESSAGES)
            val replies = answer(owner, history)
            val mine = store.messages.get(owner.id) ?: owner
            (listOf(mine) + started + replies).sortedWith(compareBy({ it.at }, { if (it.id == owner.id) 0 else 1 })).also { bump() }
        } finally {
            _busy.value = false
        }
    }

    /**
     * 重送 on a line of his marked 「沒送出」: answered again as if just sent; once it gets through the mark goes, on the same row
     * (no second copy). Still no network: it stays marked. Returns what was written (his line first); empty for a line that is
     * not his or not marked.
     */
    suspend fun resend(messageId: String): List<Message> = mutex.withLock {
        val mine = store.messages.get(messageId)?.takeIf { it.who == Who.OWNER && it.unsent } ?: return@withLock emptyList()
        _busy.value = true
        try {
            val history = store.messages.recent(FactsBuilder.MAX_MESSAGES + 1).filter { it.id != mine.id }.takeLast(FactsBuilder.MAX_MESSAGES)
            val started = ensureDay()
            val replies = answer(mine.copy(unsent = false), history)
            val now = store.messages.get(mine.id) ?: mine
            val after = if (replies.isNotEmpty() && now.unsent) now.copy(unsent = false).also { store.messages.upsert(it) } else now
            (listOf(after) + started + replies).also { bump() }
        } finally {
            _busy.value = false
        }
    }

    /**
     * One signal from outside a message (the alarm's gaps, a button, a later feature): the day's start first, then the signal;
     * what the links did. A signal id already handled does nothing.
     */
    suspend fun signal(s: Signal): NerveResult = mutex.withLock {
        val r = dayStarted() + nerve.emit(s)
        bump()
        r
    }

    /** The first event of a coach day starts it (once: the id is the day). Returns the lines its links said. */
    private suspend fun ensureDay(): List<Message> = dayStarted().messages

    private suspend fun dayStarted(): NerveResult {
        val profile = snapshot.profile()
        val day = snapshot.today(profile)
        val id = "day:$day"
        val fresh = store.nerve.signal(id) == null
        val r = nerve.emit(Signal.DayStarted(id, clock.millis(), day.toString()))
        return if (fresh && profile.onboardingDone) r + missedDays(day, profile) + weekEnded(day, profile) + attendance(day, profile) else r
    }

    /**
     * 全勤小卡 (L27): at a day's start, the days in a row up to yesterday (after the reminders started) with every required item
     * that was due done; the link answers 7 and 30.
     */
    private suspend fun attendance(today: java.time.LocalDate, profile: app.agency.core.model.Profile): NerveResult {
        if (!rollout().open(app.agency.core.schedule.Feature.ATTENDANCE)) return NerveResult()
        val since = remindersSince() ?: return NerveResult()
        val sinceDay = snapshot.dayOf(since, profile)
        var n = 0
        var d = today.minusDays(1)
        while (!d.isBefore(sinceDay) && n <= Links.L27Attendance.DAYS.max()) {
            val due = snapshot.slots(d, snapshot.records(d), profile).filter { it.slot.required && !it.dueAt.isBefore(since) }
            if (due.isEmpty() || due.any { it.status != SlotStatus.DONE }) break
            n++
            d = d.minusDays(1)
        }
        if (n !in Links.L27Attendance.DAYS) return NerveResult()
        return nerve.emit(Signal.Attendance("attendance:$today", clock.millis(), n))
    }

    /** The 全勤小卡 on the wall (L27): the day each was given and its days in a row, newest first. */
    suspend fun attendanceCards(): List<Pair<String, Int>> = store.nerve.effectsWith(EffectStatus.DONE)
        .filter { it.link == Links.L27Attendance.id && it.kind == app.agency.core.model.EffectKind.RECORD && it.because == Links.L27Attendance.CARD }
        .mapNotNull { e -> (Nerve.decodeEffect(e.json) as? Effect.Record)?.detail?.toIntOrNull()?.let { e.day to it } }
        .sortedByDescending { it.first }

    /**
     * At the first day start of a week, the week before (L20), once (the id is its Monday): what code counts over its days
     * after the reminders started ([app.agency.core.engine.WeekReport]), the real-person task, and the weeks on target so far.
     */
    private suspend fun weekEnded(today: java.time.LocalDate, profile: app.agency.core.model.Profile): NerveResult {
        val monday = snapshot.weekStart(today).minusDays(7)
        val id = "week:$monday"
        if (store.nerve.signal(id) != null) return NerveResult()
        val since = remindersSince() ?: return NerveResult()
        val sinceDay = snapshot.dayOf(since, profile)
        val sunday = monday.plusDays(6)
        if (sinceDay.isAfter(sunday)) return NerveResult()
        val days = (0L..6L).map { monday.plusDays(it) }.filter { !it.isBefore(sinceDay) }.map { d ->
            val records = snapshot.records(d)
            val cards = store.nerve.effects(d.toString()).count { it.link == Links.L11Completion.id && it.because == Links.L11Completion.PHOTOCARD }
            // A breach: a meal against the rules, or a sign-out after the latest time.
            val late = records.signs.count { it.kind == app.agency.core.model.SignKind.OUT && it.onTime == false }
            app.agency.core.engine.WeekReport.Day(d, snapshot.slots(d, records, profile), records.meals.sumOf { it.breaches.size } + late, cards)
        }
        val signals = store.nerve.signals(monday.minusDays(WEEKS_LOOKBACK_DAYS).toString(), sunday.toString())
        val real = signals.any { it.type == "real_task_done" && it.day >= monday.toString() }
        // Only weeks on target in a row count (owner 2026-11-27): the streak before this one.
        val earlier = app.agency.core.engine.WeekReport.streak(signals.filter { it.type == "week_ended" }.mapNotNull { Signal.decode(it.json) as? Signal.WeekEnded })
        val realRequired = rollout().open(app.agency.core.schedule.Feature.REAL_TASK)
        return nerve.emit(app.agency.core.engine.WeekReport.of(id, clock.millis(), monday, days, since, real, earlier, profile.level, realRequired))
    }

    /**
     * At a day's start, what each of the last days left undone (L14 reads the streak): only days after the reminders started,
     * oldest first (once per day: the id is the day).
     */
    private suspend fun missedDays(today: java.time.LocalDate, profile: app.agency.core.model.Profile): NerveResult {
        val since = remindersSince()?.let { snapshot.dayOf(it, profile) } ?: return NerveResult()
        var r = NerveResult()
        for (back in Links.L14Discuss.DAYS downTo 1) {
            val d = today.minusDays(back.toLong())
            if (!d.isAfter(since)) continue
            val keys = snapshot.slots(d, snapshot.records(d), profile).filter { it.slot.required && it.status == SlotStatus.MISSED }.map { it.key }
            r += nerve.emit(Signal.DayMissed("missed:$d", clock.millis(), d.toString(), keys))
        }
        return r
    }

    /**
     * An alarm fire (arch 1.6): the day starts if it has not, the gaps due now become one push (or a 30-minute chase), and the
     * link reminders due now go out; each post is a nudge row and, when it says something new, a company line. Also run after
     * his own events, so a filled gap clears and a reminder held back during the ride goes out at 收工.
     */
    suspend fun tick(): Tick = mutex.withLock {
        val r = tickLocked()
        bump()
        r
    }

    private suspend fun tickLocked(): Tick {
        dayStarted()
        val profile = snapshot.profile()
        // 備忘錄 reminders he asked for (T12b): at their time, once, signed out or not.
        val memoPosts = memoPosts(profile)
        // Reminders start once 入社建檔 is walked through (it is step 0: the welcome is not buried under reminders).
        if (!profile.onboardingDone) return Tick(memoPosts, gapsCleared = true, next = memoDesk.next(clock.millis())?.let { Fire(it, app.agency.core.engine.FireKind.NOTE) })
        return tickReminders(profile).let { it.copy(posts = memoPosts + it.posts) }
    }

    private suspend fun memoPosts(profile: app.agency.core.model.Profile): List<Post> {
        val now = clock.millis()
        val riding = nerve.state(snapshot.today(profile)).riding
        return memoDesk.due(now, riding).mapIndexed { i, (memo, p) ->
            val (n, m) = Nudger.record(p, now + i, RoleKey.MANAGER, newMessage = true)
            store.nudges.insert(n)
            m?.let { store.messages.insert(it.copy(kind = app.agency.core.brain.handlers.MemoDesk.KIND, refs = MessageRefs(memo = memo.id))) }
            p
        }
    }

    private suspend fun tickReminders(profile: app.agency.core.model.Profile): Tick {
        val now = clock.now()
        nerve.emit(Signal.RemindersOn(Signal.REMINDERS_ON, now.toEpochMilli()))
        val since = remindersSince() ?: now
        val day = snapshot.today(profile)
        val states = snapshot.slots(day, snapshot.records(day), profile)
        val state = nerve.state(day)
        val today = store.nudges.between(dayStart(day, profile), now.toEpochMilli() + 1)
        val lastGap = today.filter { it.gapKeys.isNotEmpty() }.maxByOrNull { it.at }
        val quiet = state.signedOutAt != null
        val gaps = if (quiet) emptyList() else Gaps.of(states, now).filter { !it.dueAt.isBefore(since) }
        val posts = mutableListOf<Post>()
        val sent = mutableListOf<String>()
        var at = now.toEpochMilli()
        if (Nudger.gapPushDue(gaps, lastGap, now)) {
            val index = today.count { it.gapKeys.isNotEmpty() }
            val keys = gaps.map { it.key }
            val fresh = lastGap == null || keys.any { it.name !in lastGap.gapKeys }
            nerve.emit(Signal.GapDue("gap:$day:${now.toEpochMilli() / 60_000}", now.toEpochMilli(), keys, index)).notifies
                .filter { it.link == Links.L12Chase.id }.forEach { e ->
                    val channel = if (state.riding) PostChannel.RIDING else PostChannel.SCHEDULE
                    val alert = if (state.riding) Nudger.ridingAlert(today, state.rideStartAt) else true
                    val p = Post(Nudger.GAP_KEY, newId(), e.because, channel, alert, keys, keys.first(), word = index % 2 == 0)
                    posts += p
                    sent += e.id
                    val (n, m) = Nudger.record(p, at++, Nudger.roleOf(keys), newMessage = fresh)
                    store.nudges.insert(n)
                    m?.let { store.messages.insert(it) }
                }
        }
        val due = nerve.dueNotifies(now.toEpochMilli())
        // An older chase that was never posted, anything left from an earlier day, and a reminder that was due before the
        // reminders started, is not posted any more.
        // A water reminder whose water is in by now has nothing to say (L8, L10).
        val drunk = due.filter { e ->
            val n = Nerve.decodeEffect(e.json) as? Effect.Notify
            n?.unlessWaterHalves?.let { state.waterHalves >= it } == true || n?.expiresAt?.let { now.toEpochMilli() > it } == true ||
                // The 收工 reminder once he is home.
                (n?.unlessHome == true && state.rideEndAt != null && (state.rideStartAt == null || state.rideEndAt >= state.rideStartAt))
        }
        val stale = due.filter { it.link == Links.L12Chase.id || it.day != day.toString() || (it.dueAt ?: it.at) < since.toEpochMilli() } + drunk
        stale.forEach { store.nerve.setStatus(it.id, EffectStatus.EXPIRED, now.toEpochMilli()) }
        if (!quiet) {
            (due - stale.toSet()).filter { Nudger.postable(it, state.riding) }.forEach { e ->
                val notify = Nerve.decodeEffect(e.json) as Effect.Notify
                val p = Post(notify.key, newId(), notify.text, if (state.riding) PostChannel.RIDING else PostChannel.SCHEDULE, alert = true)
                posts += p
                sent += e.id
                val (n, m) = Nudger.record(p, at++, RoleKey.MANAGER, newMessage = true)
                store.nudges.insert(n)
                m?.let { store.messages.insert(it) }
            }
        }
        nerve.markSent(sent)
        if (!quiet && !state.riding) premeal(day, profile, states, at).let { posts += it }
        return Tick(posts, gapsCleared = gaps.isEmpty(), next = nextFireLocked())
    }

    /**
     * The body teacher's pre-meal suggestion (T16): from [MealDiscussion.BEFORE_MIN] minutes before 第一餐 or 晚餐 until the slot,
     * once a slot a day (the nudge id is the day and the slot), not when the meal is in already. Code's numbers and names; the
     * model may word it later (guarded, and judged: it is about food).
     */
    private suspend fun premeal(day: java.time.LocalDate, profile: app.agency.core.model.Profile, states: List<app.agency.core.engine.SlotState>, at: Long): List<Post> {
        val now = clock.millis()
        val plan = snapshot.plan(day)
        val minute = Links.minuteOf(day, now, clock.zone)
        val out = mutableListOf<Post>()
        for (slot in MealDiscussion.proposed(plan)) {
            val due = MealDiscussion.dueMinute(plan, slot) ?: continue
            val st = states.firstOrNull { it.key == slot } ?: continue
            if (minute < due || st.status != SlotStatus.LATER) continue
            val id = "${MealDiscussion.PREMEAL}$day:${slot.name}"
            if (store.nudges.get(id) != null) continue
            val todays = store.meals.onDay(day.toString())
            val eaten = todays.sumOf { it.kcal }
            val protein = todays.sumOf { m -> m.items.sumOf { it.proteinG ?: 0.0 } }
            val eatenSlots = todays.mapNotNull { SlotKey.parse(it.slotKey) }.toSet()
            val rules = snapshot.rules()
            val room = MealDiscussion.room(plan, rules, slot, eaten, protein, eatenSlots, minute)
            val left = plan.slots.count { it.key in SlotKey.MEALS && it.key !in eatenSlots && it.minute >= (plan.slot(slot)?.minute ?: 0) }
            val text = MealDiscussion.suggestion(plan, slot, rules, room, MealDiscussion.usualProtein(rules, protein, left), meals.table(), store.places.favorites())
            // No slot: tapping it opens the conversation, not the meal camera (nothing is eaten yet).
            val p = Post(MealDiscussion.PREMEAL.trimEnd(':'), id, text, PostChannel.SCHEDULE, alert = true, slot = null, word = true)
            val (n, m) = Nudger.record(p, at + out.size, RoleKey.BODY, newMessage = true)
            store.nudges.insert(n)
            m?.let { store.messages.insert(it.copy(kind = MealDiscussion.PLAN_KIND)) }
            out += p
        }
        return out
    }

    /** The next pre-meal suggestion still to come today (an alarm wakes for it). */
    private suspend fun nextPremeal(day: java.time.LocalDate, states: List<app.agency.core.engine.SlotState>): Instant? {
        val plan = snapshot.plan(day)
        return MealDiscussion.proposed(plan).filter { s -> states.firstOrNull { it.key == s }?.status == SlotStatus.LATER }
            .filter { store.nudges.get("${MealDiscussion.PREMEAL}$day:${it.name}") == null }
            .mapNotNull { MealDiscussion.dueMinute(plan, it)?.let { m -> Nudger.at(day, m, clock.zone) } }
            .filter { it.isAfter(clock.now()) }.minOrNull()
    }

    /** When the next alarm should fire (arch 1.6 `NextFire`). */
    suspend fun nextFire(): Fire? = mutex.withLock { nextFireLocked() }

    private suspend fun nextFireLocked(): Fire? {
        val memo = memoDesk.next(clock.millis())?.let { Fire(it, app.agency.core.engine.FireKind.NOTE) }
        return listOfNotNull(memo, nextReminderFire()).minWithOrNull(compareBy({ it.at }, { it.kind.ordinal }))
    }

    private suspend fun nextReminderFire(): Fire? {
        val profile = snapshot.profile()
        if (!profile.onboardingDone) return null
        val day = snapshot.today(profile)
        val now = clock.now()
        val since = remindersSince() ?: now
        val states = snapshot.slots(day, snapshot.records(day), profile).filter { !it.dueAt.isBefore(since) || it.status != app.agency.core.engine.SlotStatus.OPEN }
        val state = nerve.state(day)
        val lastGap = store.nudges.between(dayStart(day, profile), now.toEpochMilli() + 1).filter { it.gapKeys.isNotEmpty() }.maxByOrNull { it.at }
        val wake = nerve.plan(day.plusDays(1)).slot(SlotKey.WAKE)?.let { Nudger.at(day.plusDays(1), it.minute, clock.zone) }
        val note = listOfNotNull(nerve.nextNotify(now.toEpochMilli())?.let { Instant.ofEpochMilli(it) }, nextPremeal(day, states)).minOrNull()
        return NextFire.next(now, states, state.signedOutAt != null, lastGap?.let { Instant.ofEpochMilli(it.at) }, note, wake)
    }

    private suspend fun remindersSince(): Instant? = store.nerve.signal(Signal.REMINDERS_ON)?.let { Instant.ofEpochMilli(it.at) }

    private fun dayStart(day: java.time.LocalDate, profile: app.agency.core.model.Profile): Long =
        day.atStartOfDay(clock.zone).plusHours(profile.dayStartHour.toLong()).toInstant().toEpochMilli()

    /**
     * The model's wording of a push (arch 1.6 WordingWorker): one guarded NudgeLine in the routed role's voice, at most
     * [Nudger.DAILY_WORDING_CAP] a day. The nudge row and its company line take the words; null keeps the template (no key,
     * offline, the cap, a reply the guard refused).
     */
    suspend fun wordNudge(nudgeId: String): String? = mutex.withLock {
        val n = store.nudges.get(nudgeId) ?: return@withLock null
        if (n.wordedBy == WordedBy.MODEL) return@withLock null
        val profile = snapshot.profile()
        val day = snapshot.today(profile)
        val worded = store.nudges.between(dayStart(day, profile), clock.millis() + 1).count { it.wordedBy == WordedBy.MODEL }
        if (worded >= Nudger.DAILY_WORDING_CAP) return@withLock null
        val keys = n.gapKeys.mapNotNull { SlotKey.parse(it) }
        val premeal = n.id.startsWith(MealDiscussion.PREMEAL)
        val role = if (premeal) RoleKey.BODY else Nudger.roleOf(keys)
        val input = snapshot.facts(store.messages.recent(FactsBuilder.MAX_MESSAGES))
        val facts = FactsBuilder.build(input, hasCurrentMessage = false)
        val tone = ToneDecider.decide(ToneInputs(breachToday = input.today.breachToday, gentleToday = nerve.state(day).gentle))
        val system = PromptBuilder.system(
            PromptInput(
                role = Roles.of(role), mode = MessageMode.NORMAL, tone = tone, rules = input.rules,
                memory = MemoryBlocks.render(store.memory.items(), day) { at -> snapshot.dayOf(Instant.ofEpochMilli(at), profile) },
                facts = facts, reply = ReplyKind.NUDGE, companyName = profile.companyName ?: PromptInput.DEFAULT_COMPANY,
                latinNames = (OutputGuard.LATIN_WHITELIST + profile.idolNames).distinct(), memoryWrites = false,
            ),
        )
        // A suggestion names foods and his places: those are names it may use (nothing else).
        val names = if (premeal) (meals.table() + store.food.all()).flatMap { listOf(it.name) + it.aliases }.distinct() + store.places.favorites().map { it.title } else emptyList()
        val evidence = Evidence(listOf(facts.json.toString(), n.text), names, profile.idolNames, breachToday = input.today.breachToday)
        val out = structured.call(
            NudgeLine.SPEC, system, PromptBuilder.turns(facts, null, n.text), Purpose.NUDGE, NUDGE_TOKENS,
            check = NudgeLine.checker(evidence, tone), fallback = { null },
        )
        val text = (out as? Structured.Model)?.value?.text ?: return@withLock null
        // About food: the second-pass judge reads it too (arch 1.9b scope); a breach keeps the template, no answer keeps the words.
        if (premeal && judge.judge(text, SecondPassJudge.contextLine(0, input.today.breachToday, foodTalk = true)!!) is app.agency.core.guard.Verdict.Breach) return@withLock null
        store.nudges.upsert(n.copy(text = text, wordedBy = WordedBy.MODEL))
        store.messages.get(Nudger.messageId(n.id))?.let { store.messages.upsert(it.copy(text = text, wordedBy = WordedBy.MODEL)) }
        bump()
        text
    }

    /** 「今天的連動」 (the war room's card): today's effects, oldest first. */
    suspend fun links(): List<NerveEffect> = nerve.today(snapshot.today(snapshot.profile()))

    /** He cancels a 今天微調 of today from the card. */
    suspend fun cancelLink(effectId: String): Boolean = mutex.withLock { nerve.cancel(effectId).also { if (it) bump() } }

    /** His tap on a link proposal on the war room's card: the answer is written as the manager's line. */
    suspend fun decideLink(effectId: String, accept: Boolean): Message? = mutex.withLock {
        nerve.decide(effectId, accept)?.let { reply(RoleKey.MANAGER, it, WordedBy.TEMPLATE, Discussion.DECIDED_KIND) }.also { bump() }
    }

    /**
     * The proposals waiting for his yes, from the one proposal table (the links' and the conversation's alike), as their
     * cards. Lapsed ones are expired first.
     */
    suspend fun pendingProposals(): List<ProposalView> = mutex.withLock { pendingLocked().map(ProposalApplier::view) }

    /** His 好 / 不要 on a proposal card: decided by code (the nerve for its own), written as the manager's line. */
    suspend fun decide(proposalId: String, accept: Boolean): Message? = mutex.withLock { decideLocked(proposalId, accept).also { bump() } }

    private suspend fun pendingLocked(): List<Proposal> {
        proposals.expire()
        val today = snapshot.today(snapshot.profile()).toString()
        // L23's and L15's wait until he answers (they change no day); the others are today's.
        val lasting = setOf(Links.L23WeightTrend.id, Links.L15Progression.id, Links.L25Rollout.id)
        val links = nerve.pending().filter { it.day == today || it.link in lasting }.mapNotNull { it.proposalId }.toSet()
        return store.proposals.withStatus(ProposalStatus.PENDING).filter { it.kind !in ProposalApplier.NERVE_KINDS || it.id in links }
    }

    private suspend fun decideLocked(proposalId: String, accept: Boolean): Message? {
        val p = pendingLocked().firstOrNull { it.id == proposalId } ?: return null
        val line = if (p.kind in ProposalApplier.NERVE_KINDS) {
            val effect = store.nerve.effectsWith(EffectStatus.PROPOSED).firstOrNull { it.proposalId == p.id } ?: return null
            nerve.decide(effect.id, accept) ?: return null
        } else {
            proposals.decide(p, accept)
        }
        return reply(RoleKey.MANAGER, line, WordedBy.TEMPLATE, Discussion.DECIDED_KIND)
    }

    /** 撤銷 under her bubble or in 設定 › 她記得的事. */
    suspend fun undoMemory(eventId: String): UndoResult = mutex.withLock {
        MemoryApplier(store, clock, snapshot.profile().dayStartHour, newId).undo(eventId).also { if (it.ok) bump() }
    }

    /** 刪掉 in 設定 › 她記得的事: he removes an item himself (an event like hers, so it can be undone). */
    suspend fun forgetMemory(itemId: String): Boolean = mutex.withLock {
        val item = store.memory.item(itemId)?.takeIf { !it.deleted } ?: return@withLock false
        val now = clock.millis()
        store.transaction {
            store.memory.putItem(item.copy(deleted = true, updatedAt = now))
            store.memory.putEvent(MemoryEvent(newId(), now, MemoryOp.DELETE, item.block, item.id, item.text, null, null, beforeDue = item.dueDay))
        }
        bump()
        true
    }

    /** 入社建檔 on the first open (arch T07): the manager's welcome and first question, once; null when not due. */
    suspend fun startOnboardingIfDue(): Message? = mutex.withLock {
        onboarding.startIfDue()?.let { baselineLine(it) }
    }

    /** 補交 from the card: the first missing item is asked again; null when nothing is missing. */
    suspend fun reopenOnboarding(): Message? = mutex.withLock {
        onboarding.reopen()?.let { baselineLine(it) }
    }

    /** A 入社建檔 photo or the recording, already sealed in the vault; [media] is written with its link. */
    suspend fun handInBaseline(step: OnboardingStep, media: Media): Message = mutex.withLock {
        baselineLine(onboarding.handIn(step, media))
    }

    /** What 入社建檔 shows on the card (the open question, what is missing). */
    suspend fun onboardingStatus(): OnboardingStatus = onboarding.status()

    /** 藝名 and 代表色 from the card or 設定 › 練習生資料 (null leaves a field as it is); the problem with the name, or null. */
    suspend fun setIdol(stageName: String?, memberColor: String?): String? = mutex.withLock {
        idol.set(stageName, memberColor).also { if (it == null) bump() }
    }

    /** What the 練習生證 shows now (arch T10), computed by code. */
    suspend fun card(): IdolCardFacts {
        val profile = snapshot.profile()
        val day = snapshot.today(profile)
        val status = onboarding.status()
        val missing = if (status.completed && status.open == null) status.missing.size else 0
        val records = snapshot.records(day)
        val slots = snapshot.slots(day, records, profile)
        return IdolCard.build(
            profile, snapshot.rules(), records, day, snapshot.currentWeightKg(day, profile), missing,
            status.baseline?.photoFront, Stickers.fromSlots(slots, records), slots, water.target(day, profile.joinDate),
        ).let { c ->
            val r = rollout()
            val s = snapshot.plans.specs().schedule
            val w = if (r.open(app.agency.core.schedule.Feature.INCOME)) income.week(day, s.weeklyIncomeTarget, s.deliveryDaysPerWeek) else null
            c.copy(
                photocards = photocards().size, rolloutWeek = r.week, open = app.agency.core.schedule.Feature.entries.filter(r::open).toSet(),
                incomeWeek = w?.total, incomeTarget = w?.target,
            )
        }
    }

    /** 上線週數 (分週上線): the week he is in and what each week opens. */
    suspend fun rollout(): app.agency.core.schedule.RolloutSpec = snapshot.plans.specs().rollout

    /**
     * 設定 › 上線週數: he sets the week himself (1 to the last), written as the next version of the section; a week reached
     * here opens today. Returns the stored setting.
     */
    suspend fun setRolloutWeek(week: Int): app.agency.core.schedule.RolloutSpec = mutex.withLock {
        val now = clock.millis()
        val next = rollout().at(week, snapshot.today(snapshot.profile()))
        store.rules.putSection(
            app.agency.core.model.PlanSectionKey.ROLLOUT, app.agency.core.schedule.PlanSpecs.JSON.encodeToString(app.agency.core.schedule.RolloutSpec.serializer(), next),
            app.agency.core.model.PlanSection.BY_OWNER_SETTINGS, now,
        )
        bump()
        next
    }

    /**
     * 「這週考試」 (owner 2026-11-26): this Mon–Sun week has one lesson a day (training stays), at most [EXAM_WEEKS_PER_TERM] a
     * semester (Feb–Jul, Aug–Jan). The line and the signal to emit (none when refused).
     */
    private suspend fun examWeek(today: java.time.LocalDate, at: Long): Pair<String, Signal?> {
        val monday = snapshot.weekStart(today)
        val (from, to) = app.agency.core.lessons.LessonPlan.semester(today)
        val weeks = store.nerve.signals(from.toString(), to.toString()).filter { it.type == "exam_week" }
            .mapNotNull { (Signal.decode(it.json) as? Signal.ExamWeek)?.monday }.toSet()
        return when {
            monday.toString() in weeks -> "這週已經是考試週了：理論課每天 1 支，訓練照做。" to null
            weeks.size >= app.agency.core.lessons.LessonPlan.EXAM_WEEKS_PER_TERM ->
                "這學期的考試週已經用了 ${weeks.size} 次（最多 ${app.agency.core.lessons.LessonPlan.EXAM_WEEKS_PER_TERM} 次），理論課照每天 ${app.agency.core.lessons.LessonPlan.DAILY} 支。" to null
            else -> "好，這週是考試週：理論課每天 1 支，訓練照做。這學期第 ${weeks.size + 1} 次（最多 ${app.agency.core.lessons.LessonPlan.EXAM_WEEKS_PER_TERM} 次）。" to
                Signal.ExamWeek("exam:$monday", at, monday.toString())
        }
    }

    /** The line for a feature not open yet (分週上線), or null when it is open. */
    private suspend fun closed(f: app.agency.core.schedule.Feature?): String? = f?.let { rollout().takeUnless { r -> r.open(it) }?.closedLine(it) }

    private fun trainingFeature(kind: app.agency.core.model.TrainingKind?): app.agency.core.schedule.Feature = when (kind) {
        app.agency.core.model.TrainingKind.STRENGTH, app.agency.core.model.TrainingKind.STRETCH -> app.agency.core.schedule.Feature.STRENGTH
        app.agency.core.model.TrainingKind.WALK -> app.agency.core.schedule.Feature.WALK
        app.agency.core.model.TrainingKind.CARDIO -> app.agency.core.schedule.Feature.CARDIO
        else -> app.agency.core.schedule.Feature.CORE
    }

    private suspend fun baselineLine(text: String): Message =
        reply(RoleKey.MANAGER, text, WordedBy.TEMPLATE, BASELINE_KIND).also { bump() }

    private suspend fun answer(owner: Message, history: List<Message>): List<Message> {
        // An open 入社建檔 question reads his message as its answer first (code, no model); anything else goes on as usual.
        onboarding.answer(owner.text)?.let { return listOf(reply(RoleKey.MANAGER, it, WordedBy.TEMPLATE, BASELINE_KIND)) }
        idol.answer(owner.text)?.let { return listOf(reply(RoleKey.MANAGER, it, WordedBy.TEMPLATE, PROFILE_KIND)) }
        // The bedtime review's question waiting (T19): his message is its answer (code, no model).
        val profile0 = snapshot.profile()
        val today0 = snapshot.today(profile0)
        review.open(history, { snapshot.dayOf(Instant.ofEpochMilli(it), profile0).toString() }, today0.toString())?.let { open ->
            return reviewAnswer(owner, open, today0)
        }
        // The morning score typed after sign-in (「3」「4 分」).
        app.agency.core.engine.ConditionPolicy.parse(owner.text)?.let { score ->
            val d = today0.toString()
            if (store.signs.onDay(d).any { it.kind == app.agency.core.model.SignKind.IN } && store.conditions.get(d) == null) return conditionLines(score, null, null, null, owner.at)
        }
        // 「幫我記…」「提醒我…」 (T12b): his words kept by code, a time read by code; no model.
        app.agency.core.brain.handlers.MemoWords.parse(owner.text, Instant.ofEpochMilli(owner.at), clock.zone, profile0.dayStartHour)?.let { p ->
            val (memo, line) = memoDesk.save(p, owner.at)
            val m = Message(newId(), clock.millis(), Who.COMPANY, role = RoleKey.MANAGER, kind = app.agency.core.brain.handlers.MemoDesk.KIND, text = line, refs = MessageRefs(memo = memo.id), wordedBy = WordedBy.TEMPLATE)
            store.messages.insert(m)
            return listOf(m)
        }
        body(owner)?.let { return it }
        if (Router.clean(owner.text) in Discussion.END_WORDS) {
            val profile = snapshot.profile()
            if (Discussion.active(history, snapshot.today(profile)) { snapshot.dayOf(Instant.ofEpochMilli(it), profile) }) {
                return listOf(reply(RoleKey.MANAGER, Discussion.END_LINE, WordedBy.TEMPLATE, Discussion.END_KIND))
            }
        }
        // 「跟朋友吃 X」「附近有什麼」 (T16): the body teacher's turn, no clerk.
        MealDiscussion.task(owner.text)?.let { return model(owner, history, task = it) }
        return when (val route = Router.route(owner.text)) {
            is Route.Command -> if (route.kind == Router.DISCUSS_OPEN) model(owner, history, forceDiscuss = true) else command(route, owner)
            Route.RulesLocked -> listOf(reply(RoleKey.MANAGER, RulesBook.LOCKED, WordedBy.TEMPLATE, RULES_KIND))
            Route.Model -> model(owner, history)
        }
    }

    /**
     * His yes/no to the one waiting link proposal, then his body (agency-links §3b, code only): ill or hurt goes to L2 (a
     * question or a today-only proposal), asking to do less without that gets 「今天照表」; low mood marks a gentle day and goes
     * on to the manager. Null: not about these.
     */
    private suspend fun body(owner: Message): List<Message>? {
        val t = Router.clean(owner.text)
        if (t in Router.YES || t in Router.NO) {
            val waiting = pendingLocked()
            if (waiting.size == 1) decideLocked(waiting[0].id, accept = t in Router.YES)?.let { return listOf(it) }
            if (waiting.size > 1) {
                return listOf(reply(RoleKey.MANAGER, "現在有 ${waiting.size} 個提案在等你，在提案卡上一個一個按「好」或「不要」。", WordedBy.TEMPLATE, PROPOSALS_KIND))
            }
        }
        BodyWords.illness(owner.text)?.let { (kind, word) ->
            val said = nerve.emit(Signal.Unwell(newId(), owner.at, kind, word)).messages
            return said.ifEmpty { listOf(reply(RoleKey.MANAGER, "知道了，先照顧身體。今天的調整已經提過了，在指揮室的今天的連動按好或不要。", WordedBy.TEMPLATE, Nerve.KIND)) }
        }
        if (BodyWords.asksLess(owner.text)) return nerve.emit(Signal.ReduceAsked(newId(), owner.at)).messages.ifEmpty { null }
        // 「肌力好輕鬆」 on its own: a 「輕鬆」 for 訓練升級 (L15), no record of a session.
        if (app.agency.core.brain.handlers.TrainingWords.feltEasy(owner.text)) {
            nerve.emit(Signal.FeltEasy(newId(), owner.at))
            return listOf(reply(RoleKey.BODY, FELT_EASY_LINE, WordedBy.TEMPLATE, "training"))
        }
        // Sleepy on a delivery day before he is home: no riding (L22); otherwise it goes on to the manager.
        if (BodyWords.sleepy(owner.text)) nerve.emit(Signal.Sleepy(newId(), owner.at)).messages.takeIf { it.isNotEmpty() }?.let { return it }
        if (BodyWords.moodLow(owner.text)) nerve.emit(Signal.MoodLow(newId(), owner.at))
        return null
    }

    /**
     * The model's turn (arch 1.3 steps 2–6): the data clerk sorts his words, then the manager's tool loop answers, in 討論 mode
     * when one is open, he asked for it, or the clerk found it ([forceDiscuss]: his tap on 「這裡有問題」 skips the clerk). The
     * reply's memory events are applied by code; the proposals its tools made wait for his yes; her thinking is kept (乙).
     * No network at all: nothing is answered, his line is kept as 「沒送出」 for 重送 ([unsent]).
     */
    private suspend fun model(owner: Message, history: List<Message>, forceDiscuss: Boolean = false, task: MealTask? = null): List<Message> {
        val input = snapshot.facts(history)
        val facts = FactsBuilder.build(input)
        val profile = input.profile
        val today = snapshot.today(profile)
        val dayOf = { at: Long -> snapshot.dayOf(Instant.ofEpochMilli(at), profile) }
        val discussing = task == null && (forceDiscuss || Discussion.active(history, today, dayOf))
        val intake = if (forceDiscuss || task != null) null else structured.call(
            Intake.spec(owner.text), PromptBuilder.intakeSystem(), PromptBuilder.intakeTurns(facts, owner.text), Purpose.INTAKE,
            INTAKE_TOKENS, fallback = { null },
        )
        val parsed = (intake as? Structured.Model)?.value
        val kind = parsed?.kind?.let { MealWords.kindOf(it, owner.text) } ?: if (discussing) DISCUSS else if (task != null) MealDiscussion.TALK_KIND else CHAT
        val dayState = nerve.state(today)
        // L11's one 撒嬌 of the day counts: once it is given, no reply is SWEET again today.
        val tone = ToneDecider.decide(
            ToneInputs(ownerText = owner.text, intakeKind = kind, breachToday = input.today.breachToday, gentleToday = dayState.gentle, sweetToday = dayState.sweetToday),
        )
        if (intake is Structured.Template && intake.fail != null) {
            if (intake.fail == Fail.OFFLINE) return unsent(owner)
            _lastFail.value = intake.fail
            return listOf(reply(RoleKey.MANAGER, Roles.template(RoleKey.MANAGER, tone) + failLine(intake.fail), WordedBy.TEMPLATE, kind))
        }
        if (parsed != null) recorded(owner, parsed, kind)?.let { return it }
        if (Router.isRecordKind(kind)) return listOf(reply(Router.roleOf(kind), recordHint(kind), WordedBy.TEMPLATE, kind))

        // The body teacher answers a meal talk (T16): a task of his words, or more food talk right after her suggestion.
        val mealTask: MealTask? = task ?: (MealTask.Talk as MealTask).takeIf { !discussing && kind == CHAT && MealDiscussion.talking(history, owner.at) && MealDiscussion.aboutFood(owner.text, ::mentionsFood) }
        val role = if (mealTask != null) RoleKey.BODY else RoleKey.MANAGER
        val replyKind = if (mealTask != null) MealDiscussion.TALK_KIND else kind
        val mode = if (mealTask == null && (discussing || kind == DISCUSS)) MessageMode.DISCUSS else MessageMode.NORMAL
        val latin = profile.idolNames
        val rendered = MemoryBlocks.render(store.memory.items(), today, dayOf)
        val system = PromptBuilder.system(
            PromptInput(
                role = Roles.of(role), mode = mode, tone = tone, rules = input.rules, memory = rendered, facts = facts,
                companyName = profile.companyName ?: PromptInput.DEFAULT_COMPANY,
                latinNames = (OutputGuard.LATIN_WHITELIST + latin).distinct(),
                memoryWrites = true, task = mealTask?.text,
            ),
        )
        // The seed table too: on a fresh install the store has no food yet, and 給力盒子 or 全家 must not read as unknown chains.
        // His favourite places are names he may hear again; this round's search titles join them as they come (the evidence).
        val names = (FoodTable.current(0) + store.food.all()).flatMap { listOf(it.name) + it.aliases }.distinct() + store.places.favorites().map { it.title }
        val factsText = facts.json.toString()
        val foodItems = parsed?.items.orEmpty().isNotEmpty()
        val judgeContext = { shown: String -> judgeContext(input, foodItems || mealTask != null, owner.text, shown) }
        val ctx = RoundContext(owner, rendered.refs, today, role)
        val memory = MemoryApplier(store, clock, profile.dayStartHour, newId)
        val tools = ToolRegistry(chatTools(store, snapshot, clock, newId, memory, ctx, riding = { nerve.state(today).riding }, search = search))
        val replay = replay(facts.window)
        // 乙: she thinks on every reply. DeepSeek refuses a thinking request with tools when an older reply of hers in the
        // window has no reasoning to send back (imported, or written before thinking was kept): that round goes without.
        val thinking = if (provider() == ProviderChoice.DEEPSEEK && replay.old > 0) {
            log("chat thinking off this round: ${replay.old} earlier replies have no stored thinking")
            Thinking.OFF
        } else {
            Thinking.LOW
        }
        val round = ChatRound(system, PromptBuilder.turns(facts, owner, replay = replay.replay), role, mode, tone, judgeContext, thinking) { outputs ->
            Evidence(listOf(factsText) + outputs, names + ctx.searchTitles.values, latin, ownerText = owner.text, breachToday = input.today.breachToday)
        }
        val result = loop.run(round, tools)
        if (result.fail == Fail.OFFLINE && ctx.proposals.isEmpty() && ctx.memoryEvents.isEmpty()) return unsent(owner)
        _lastFail.value = result.fail
        val u = result.utterance
        if (result.fromModel && u.memory.isNotEmpty() && ctx.memoryLeft > 0) {
            ctx.memoryEvents += memory.apply(u.memory.take(ctx.memoryLeft), owner, rendered.refs).applied.map { it.id }
        }
        val text = u.text + (u.ask?.let { "\n$it" } ?: "") + (result.fail?.let(::failLine) ?: "")
        val refs = MessageRefs(
            results = ctx.searchResults.toList(),
            proposal = ctx.proposals.lastOrNull(), options = u.options, recommended = u.recommended, memory = ctx.memoryEvents.toList(),
            proposals = ctx.proposals.toList(),
        )
        val m = Message(
            newId(), clock.millis(), Who.COMPANY, role = role, kind = if (mode == MessageMode.DISCUSS) DISCUSS else replyKind,
            mode = mode, text = text, refs = refs, wordedBy = if (result.fromModel) WordedBy.MODEL else WordedBy.TEMPLATE,
        )
        store.messages.insert(m)
        // 乙 is about her replies: the body teacher's meal talk keeps no thinking (it goes back as a labelled line).
        if (result.fromModel && role == RoleKey.MANAGER) keepThinking(m, result.assistant)
        return listOf(m)
    }

    /** No network: his line stays, marked 「沒送出」, and nothing is answered (重送 tries again on the same row). */
    private suspend fun unsent(owner: Message): List<Message> {
        store.messages.upsert(owner.copy(unsent = true))
        _lastFail.value = Fail.OFFLINE
        return emptyList()
    }

    /** How the window's company lines go back ([PromptBuilder.turns]); [old] counts her replies with no stored thinking. */
    private class Window(val replay: PromptBuilder.Replay, val old: Int)

    private suspend fun replay(window: List<Message>): Window {
        val replies = window.filter(::isReply)
        val rows = store.thinking.of(replies.map { it.id }).associateBy { it.messageId }
        val now = provider().name
        val reasoning = mutableMapOf<String, String>()
        val plain = mutableSetOf<String>()
        var old = 0
        replies.forEach { m ->
            val row = rows[m.id]
            when {
                // Imported, or written before thinking was kept: her reply, with nothing to send back.
                row == null -> { plain += m.id; old++ }
                row.provider != now -> Unit // another provider's reply: a labelled line on his side
                row.wire == Wire.OPENAI.name -> reasoning[m.id] = row.payload
                // Claude's own: plain text (its blocks are bound to the round that made them, see Thoughts).
                row.wire == Wire.ANTHROPIC.name -> plain += m.id
                else -> Unit // a reply of a thinking-off round: a labelled line, so it never turns thinking off again
            }
        }
        return Window(PromptBuilder.Replay(reasoning, plain), old)
    }

    /** Her chat replies (the model's, or imported ones); code-written lines and reminders are not. */
    private fun isReply(m: Message): Boolean = m.who == Who.COMPANY && (m.legacy || (m.wordedBy == WordedBy.MODEL && m.kind in REPLY_KINDS))

    /** Keeps the thinking behind reply [m] (乙): never shown, never logged; a row also for none, so the next round can tell. */
    private suspend fun keepThinking(m: Message, assistant: Turn.Assistant?) {
        val t = assistant?.let(Thoughts::of)
        store.thinking.put(StoredThinking(m.id, m.at, provider().name, t?.first?.name ?: NO_THINKING, t?.second.orEmpty()))
        store.thinking.prune(clock.millis() - THINKING_KEEP_MS)
    }

    /**
     * A record kind the clerk found, answered by code (T14): a meal (the body teacher's lines, then what the links said: L5's
     * smaller next meal in the same bubble, L13's line of the manager after it), a craving, a label, a weight. Null for the
     * kinds without a handler yet.
     */
    private suspend fun recorded(owner: Message, parsed: Intake, kind: String): List<Message>? {
        // 分週上線: a record of a feature not open yet is not taken (never planned, chased or counted).
        val feature = when (kind) {
            "meal", "craving", "label" -> app.agency.core.schedule.Feature.MEALS
            "water" -> app.agency.core.schedule.Feature.WATER
            "training" -> trainingFeature(app.agency.core.brain.handlers.TrainingWords.kind(owner.text) ?: parsed.training?.kind?.let(app.agency.core.brain.handlers.TrainingWords::ofIntake))
            "condition" -> app.agency.core.schedule.Feature.SIGN
            else -> null
        }
        closed(feature)?.let { return said(MealOutcome(null, listOf(it)), kind) }
        val ill = nerve.state(snapshot.today(snapshot.profile())).ill != null
        val outcome: MealOutcome = when (kind) {
            "meal" -> meals.record(owner.text, parsed.items, ill).let { m ->
                val w = parsed.weightKg?.let { weights.record(it, parsed.waistCm) }
                if (w == null) m else MealOutcome(m.meal, m.lines + w.lines, m.signals + w.signals)
            }
            "craving" -> MealOutcome(null, cravings.answer(owner.text, parsed.items))
            "label" -> meals.label(parsed.label?.name ?: parsed.items.firstOrNull()?.name, parsed.label?.kcal, parsed.label?.proteinG)
            "weight" -> parsed.weightKg?.let { weights.record(it, parsed.waistCm) } ?: MealOutcome(null, listOf("沒聽到體重。說「今天體重 79.5」。"))
            "water" -> water.record(WaterDesk.halvesIn(owner.text) ?: parsed.waterHalf ?: 0, nerve.state(snapshot.today(snapshot.profile())).riding)
            "training" -> training.typed(owner.text, parsed.training)
            "condition" -> return parsed.conditionScore?.let { conditionLines(it, null, null, null, owner.at) }
            else -> return null
        }
        return said(outcome, kind)
    }

    /** The lines of a record handler as one bubble of the body teacher, then the links' answers to its signals. */
    private suspend fun said(outcome: MealOutcome, kind: String): List<Message> {
        var r = NerveResult()
        outcome.signals.forEach { r += nerve.emit(it) }
        val adjusted = r.effects.filter { it.link == Links.L5NextMeal.id && it.kind == app.agency.core.model.EffectKind.ADJUST }.map { it.because }
        val text = (outcome.lines + adjusted).joinToString("\n")
        val m = Message(
            newId(), clock.millis(), Who.COMPANY, role = RoleKey.BODY, kind = kind, text = text,
            refs = MessageRefs(records = listOfNotNull(outcome.meal?.id)), wordedBy = WordedBy.TEMPLATE,
        )
        store.messages.insert(m)
        return listOf(m) + r.messages
    }

    /**
     * The 狀態打分 picker (T19): the score, how he slept and his energy (1–5), and 生病/受傷 if so (that goes on to L2). The
     * manager's line, then what the links said.
     */
    suspend fun condition(score: Int, sleep: Int?, energy: Int?, ill: app.agency.core.engine.IllKind?): List<Message> = mutex.withLock {
        val started = ensureDay()
        (started + conditionLines(score, sleep, energy, ill, clock.millis())).also { bump() }
    }

    private suspend fun conditionLines(score: Int, sleep: Int?, energy: Int?, ill: app.agency.core.engine.IllKind?, at: Long): List<Message> {
        val day = snapshot.today(snapshot.profile()).toString()
        val done = conditions.record(day, at, score, sleep, energy, ill)
        val line = reply(RoleKey.MANAGER, done.line, WordedBy.TEMPLATE, CONDITION_KIND)
        var said = NerveResult()
        done.signals.forEach { said += nerve.emit(it) }
        return listOf(line) + said.messages
    }

    /** Today's 狀態打分 is still to give (signed in, no score yet): the picker shows. */
    suspend fun conditionDue(): Boolean {
        val d = snapshot.today(snapshot.profile()).toString()
        return store.signs.onDay(d).any { it.kind == app.agency.core.model.SignKind.IN } && store.conditions.get(d) == null
    }

    /** The review question waiting now (the sheet shows), or null. */
    suspend fun reviewOpen(): app.agency.core.brain.handlers.ReviewDesk.Open? {
        val profile = snapshot.profile()
        val history = store.messages.recent(FactsBuilder.MAX_MESSAGES)
        return review.open(history, { snapshot.dayOf(Instant.ofEpochMilli(it), profile).toString() }, snapshot.today(profile).toString())
    }

    /**
     * The bedtime review sheet (T19): both answers at once, written as his line, then answered as if typed one by one. Empty
     * when no review is waiting or an answer is blank.
     */
    suspend fun review(good: String, fix: String): List<Message> = mutex.withLock {
        if (fix.isBlank()) return@withLock emptyList()
        val profile = snapshot.profile()
        val today = snapshot.today(profile)
        val history = store.messages.recent(FactsBuilder.MAX_MESSAGES)
        val open = review.open(history, { snapshot.dayOf(Instant.ofEpochMilli(it), profile).toString() }, today.toString()) ?: return@withLock emptyList()
        if (open == ReviewDesk.Open.GOOD && good.isBlank()) return@withLock emptyList()
        val said = (if (open == ReviewDesk.Open.GOOD) "做得好：${ReviewDesk.clean(good)}\n" else "") + "明天要改：${ReviewDesk.clean(fix)}"
        val owner = Message(newId(), clock.millis(), Who.OWNER, text = said)
        store.messages.insert(owner)
        if (open == ReviewDesk.Open.GOOD) review.keep(today.toString(), ReviewDesk.Open.GOOD, good)
        (listOf(owner) + finishReview(fix, today)).also { bump() }
    }

    /** His answer to the review question waiting: the next question, or (the second) the 檢討卡, its proposal and her line. */
    private suspend fun reviewAnswer(owner: Message, open: ReviewDesk.Open, today: java.time.LocalDate): List<Message> {
        if (open == ReviewDesk.Open.GOOD) {
            review.keep(today.toString(), open, owner.text)
            return listOf(reply(RoleKey.MANAGER, ReviewDesk.ASK_FIX, WordedBy.TEMPLATE, ReviewDesk.FIX_KIND))
        }
        return finishReview(owner.text, today)
    }

    private suspend fun finishReview(fixText: String, today: java.time.LocalDate): List<Message> {
        val d = today.toString()
        review.keep(d, ReviewDesk.Open.FIX, fixText)
        val profile = snapshot.profile()
        val out = store.signs.onDay(d).filter { it.kind == app.agency.core.model.SignKind.OUT }.minByOrNull { it.at }
        val records = snapshot.records(today)
        val slots = snapshot.slots(today, records, profile).filter { it.slot.required }
        val plan = snapshot.plan(today)
        val signal = Signal.ReviewDone(
            "review:$d", clock.millis(), out?.reviewGood.orEmpty(), out?.reviewFix ?: ReviewDesk.clean(fixText),
            slots.filter { it.status == SlotStatus.DONE }.map { it.key }, slots.filter { it.status != SlotStatus.DONE && it.key != SlotKey.SIGN_OUT }.map { it.key },
            records.meals.sumOf { it.breaches.size } + records.signs.count { it.kind == app.agency.core.model.SignKind.OUT && it.onTime == false }, plan.reduction?.zh,
        )
        val said = nerve.emit(signal).messages
        val card = said.firstOrNull()?.text.orEmpty()
        // After the card in the conversation (the nerve's lines are written just after now).
        val at = maxOf(clock.millis(), (said.maxOfOrNull { it.at } ?: 0) + 1)
        val line = reviewLine(card, at)
            ?: Message(newId(), at, Who.COMPANY, role = RoleKey.MANAGER, kind = ReviewDesk.LINE_KIND, text = ReviewDesk.GOOD_NIGHT, wordedBy = WordedBy.TEMPLATE)
                .also { store.messages.insert(it) }
        return said + line
    }

    /**
     * The manager's one sentence after the 檢討卡 (arch T19: code asks, she adds one guarded sentence): a NudgeLine in her voice,
     * guarded, and judged on a day with a breach or over the quota; null keeps the template (no key, offline, refused).
     */
    private suspend fun reviewLine(card: String, at: Long): Message? {
        if (card.isEmpty()) return null
        val profile = snapshot.profile()
        val day = snapshot.today(profile)
        val input = snapshot.facts(store.messages.recent(FactsBuilder.MAX_MESSAGES))
        val facts = FactsBuilder.build(input, hasCurrentMessage = false)
        val tone = ToneDecider.decide(ToneInputs(breachToday = input.today.breachToday, gentleToday = nerve.state(day).gentle, sweetToday = ToneDecider.MAX_SWEET_PER_DAY))
        val system = PromptBuilder.system(
            PromptInput(
                role = Roles.of(RoleKey.MANAGER), mode = MessageMode.NORMAL, tone = tone, rules = input.rules,
                memory = MemoryBlocks.render(store.memory.items(), day) { at -> snapshot.dayOf(Instant.ofEpochMilli(at), profile) },
                facts = facts, reply = ReplyKind.NUDGE, companyName = profile.companyName ?: PromptInput.DEFAULT_COMPANY,
                latinNames = (OutputGuard.LATIN_WHITELIST + profile.idolNames).distinct(), memoryWrites = false,
            ),
        )
        val evidence = Evidence(listOf(facts.json.toString(), card), emptyList(), profile.idolNames, breachToday = input.today.breachToday)
        val out = structured.call(
            NudgeLine.SPEC, system, PromptBuilder.turns(facts, null, REVIEW_INSTRUCTION + "\n" + card), Purpose.NUDGE, NUDGE_TOKENS,
            check = NudgeLine.checker(evidence, tone), fallback = { null },
        )
        val text = (out as? Structured.Model)?.value?.text ?: return null
        val over = input.today.meals.sumOf { it.kcal } - input.rules.quotaKcal
        SecondPassJudge.contextLine(over, input.today.breachToday, foodTalk = mentionsFood(text))?.let { ctx ->
            if (judge.judge(text, ctx) is app.agency.core.guard.Verdict.Breach) return null
        }
        return Message(newId(), at, Who.COMPANY, role = RoleKey.MANAGER, kind = ReviewDesk.LINE_KIND, text = text, wordedBy = WordedBy.MODEL)
            .also { store.messages.insert(it) }
    }

    /**
     * A guided timer finished (T18): the core 7 minutes or the stretch ([seconds] it ran), or the strength rounds with their
     * report. The body teacher's line, then what the links said (L11's photocard and 撒嬌, L15's proposal).
     */
    suspend fun coreDone(seconds: Int): List<Message> = trainingLocked { training.core(fromTimer = true, seconds = seconds) }

    suspend fun strengthDone(report: app.agency.core.brain.handlers.StrengthReport): List<Message> = trainingLocked { training.strength(report.copy(fromTimer = true)) }

    suspend fun stretchDone(seconds: Int): List<Message> = trainingLocked { training.stretch(fromTimer = true, seconds = seconds) }

    suspend fun cardioDone(seconds: Int): List<Message> = trainingLocked { training.cardio(fromTimer = true, seconds = seconds) }

    /** 快走完成 (a tap on a no-class day). */
    suspend fun walkDone(): List<Message> = trainingLocked { training.walk() }

    private suspend fun trainingLocked(record: suspend () -> MealOutcome): List<Message> = mutex.withLock {
        val started = ensureDay()
        (started + said(record(), "training")).also { bump() }
    }

    /** Today's guided routine of [kind] (the plan's progression step and rounds), or null when it is not in today's plan. */
    suspend fun routine(kind: app.agency.core.model.TrainingKind): List<app.agency.core.engine.RoutineStep>? {
        val plan = snapshot.plan(snapshot.today(snapshot.profile()))
        return when (kind) {
            app.agency.core.model.TrainingKind.CORE7 -> app.agency.core.engine.Routine.core(plan.coreStep).takeIf { plan.slot(SlotKey.CORE) != null }
            app.agency.core.model.TrainingKind.STRENGTH -> plan.slot(SlotKey.STRENGTH)?.let { app.agency.core.engine.Routine.strength(plan.progressionStep, plan.strengthRounds) }
            app.agency.core.model.TrainingKind.STRETCH -> app.agency.core.engine.Routine.stretch(plan.stretchMin)
            app.agency.core.model.TrainingKind.WALK -> null
            app.agency.core.model.TrainingKind.CARDIO -> plan.slot(SlotKey.CARDIO)?.let { app.agency.core.engine.Routine.cardio(plan.cardioMin, app.agency.core.engine.Routine.cardioWork(plan.cardioStep)) }
        }
    }

    /** The strength moves of today's step (the report form asks the last round of each). */
    suspend fun strengthMoves(): List<app.agency.core.engine.StrengthMove> =
        app.agency.core.engine.Routine.moves(snapshot.plan(snapshot.today(snapshot.profile())).progressionStep)

    /**
     * 拍立得照片牆 (L11): one photocard per day of the first big completion, newest first, with what earned it.
     */
    suspend fun photocards(): List<Photocard> = store.nerve.effectsWith(EffectStatus.DONE)
        .filter { it.link == Links.L11Completion.id && it.kind == app.agency.core.model.EffectKind.RECORD && it.because == Links.L11Completion.PHOTOCARD }
        .mapNotNull { e -> (Nerve.decodeEffect(e.json) as? Effect.Record)?.let { r -> Photocard(e.day, app.agency.core.model.TrainingKind.valueOf(r.detail), e.at) } }
        .sortedByDescending { it.at }

    /** 拍這餐 (T15): the sealed photo of a meal ([media] is written here with its link); the body teacher's line. */
    suspend fun attachMealPhoto(media: Media): Message = mutex.withLock {
        closed(app.agency.core.schedule.Feature.MEALS)?.let { return@withLock said(MealOutcome(null, listOf(it)), MEAL_PHOTO_KIND).first().also { bump() } }
        said(meals.attachPhoto(media), MEAL_PHOTO_KIND).first().also { bump() }
    }

    /** 刪掉照片 after his confirm: the media id whose sealed file the app deletes (null: nothing was deleted). */
    suspend fun deleteMealPhoto(mealId: String): String? = mutex.withLock { meals.deletePhoto(mealId).also { if (it != null) bump() } }

    /** The label form (T15): kcal and protein typed from a package or a menu for item [itemIdx] of meal [mealId]. */
    suspend fun label(mealId: String, itemIdx: Int, kcal: Int, proteinG: Double?): Message = mutex.withLock {
        val name = store.meals.get(mealId)?.items?.getOrNull(itemIdx)?.name
        said(meals.label(name, kcal, proteinG, mealId to itemIdx), "label").first().also { bump() }
    }

    /** 設定 › 附近搜尋: his coarse area (T16); the problem with it, or null when it was kept (blank clears it). */
    suspend fun setArea(text: String): String? = mutex.withLock {
        AreaText.problem(text)?.let { return@withLock it }
        val p = snapshot.profile()
        store.profile.put(p.copy(areaText = text.trim().ifEmpty { null }))
        bump()
        null
    }

    /**
     * 設定 › 作息 喝水計畫 (T17): the day the bottle plan counts from (his own settings: a new version of the water section,
     * changed by him). The problem as he reads it, or null when it was kept.
     */
    suspend fun setWaterStart(text: String): String? = mutex.withLock {
        val d = runCatching { java.time.LocalDate.parse(text.trim()) }.getOrNull() ?: return@withLock "日期要寫成 2026-11-21 這樣。"
        val today = snapshot.today(snapshot.profile())
        if (d.isAfter(today.plusDays(WATER_START_AHEAD_DAYS))) return@withLock "最多只能訂到一個月後開始。"
        val water = snapshot.plans.specs().water.copy(startDay = d.toString())
        store.rules.putSection(
            app.agency.core.model.PlanSectionKey.WATER, app.agency.core.schedule.PlanSpecs.JSON.encodeToString(app.agency.core.schedule.WaterSpec.serializer(), water),
            app.agency.core.model.PlanSection.BY_OWNER_SETTINGS, clock.millis(),
        )
        bump()
        null
    }

    /** Today's water target in half bottles (設定 › 作息). */
    suspend fun waterTarget(): Int = snapshot.profile().let { water.target(snapshot.today(it), it.joinDate) }

    /** His saved places (設定 › 附近搜尋). */
    suspend fun favorites(): List<app.agency.core.model.FavoritePlace> = store.places.favorites()

    suspend fun deleteFavorite(id: String): Boolean = mutex.withLock { store.places.deleteFavorite(id).also { if (it) bump() } }

    /** A search result's 「存成常去店家」 (T16): a SAVE_PLACE proposal, saved only on his 好. Null when the result is gone. */
    suspend fun proposeSavePlace(resultId: String): String? = mutex.withLock {
        val r = store.places.result(resultId) ?: return@withLock null
        val id = newId()
        store.proposals.insert(
            Proposal(
                id, clock.millis(), RoleKey.BODY, app.agency.core.model.ProposalKind.SAVE_PLACE, target = r.id,
                diffJson = ProposalApplier.JSON.encodeToString(PlaceDiff.serializer(), PlaceDiff(r.id, r.title, "")), reason = "存成常去店家",
            ),
        )
        bump()
        id
    }

    // ---- 理論課 (T19c) ----

    /** 找影片 from the tab (a category, or the one with the fewest videos so far). */
    suspend fun findVideos(category: app.agency.core.model.LessonCategory? = null): List<Message> = lessonLocked { lessons.find(category) }

    /** His 好 / 不要 on a video candidate card. */
    suspend fun approveLesson(id: String): List<Message> = lessonLocked { lessons.approve(id) }

    suspend fun dropLesson(id: String): List<Message> = lessonLocked { lessons.drop(id) }

    /** 去看: the stored link of the open lesson, for his YouTube app or browser (the app never opens the page itself). */
    suspend fun openLesson(id: String): String? = mutex.withLock { lessons.open(id) }

    suspend fun lessonWatched(id: String): List<Message> = lessonLocked { lessons.watched(id) }

    suspend fun lessonKeyPoints(id: String, points: List<String>): List<Message> = lessonLocked { lessons.keyPoints(id, points) }

    /** His picks for the open questions (in order), graded by index. */
    suspend fun answerLesson(id: String, choices: List<Int>): List<Message> = lessonLocked { lessons.answer(id, choices) }

    suspend fun lessonNote(id: String, text: String): List<Message> = lessonLocked { lessons.note(id, text) }

    /** A lesson's 實作, already sealed in the vault ([media] is written with its link). */
    suspend fun handInPractical(id: String, media: Media): List<Message> = lessonLocked { lessons.practical(id, media) }

    suspend fun startReview(id: String): List<Message> = lessonLocked { lessons.startReview(id) }

    suspend fun answerReview(id: String, choices: List<Int>): List<Message> = lessonLocked { lessons.answerReview(id, choices) }

    /** The 理論課 tab (T19c). */
    suspend fun lessonBoard(): app.agency.core.brain.handlers.LessonBoard = mutex.withLock { lessons.board() }

    private suspend fun lessonLocked(act: suspend () -> app.agency.core.brain.handlers.LessonOutcome?): List<Message> = mutex.withLock {
        val started = ensureDay()
        val out = act() ?: return@withLock started.also { bump() }
        (started + lessonSaid(out)).also { bump() }
    }

    /** A lesson action's lines as one bubble of the manager (its candidate cards with it), then what the links said. */
    private suspend fun lessonSaid(out: app.agency.core.brain.handlers.LessonOutcome): List<Message> {
        val m = Message(
            newId(), clock.millis(), Who.COMPANY, role = RoleKey.MANAGER, kind = if (out.cards.isEmpty()) LESSON_KIND else LESSON_FOUND_KIND,
            text = out.lines.joinToString("\n"), refs = MessageRefs(records = out.cards), wordedBy = WordedBy.TEMPLATE,
        )
        store.messages.insert(m)
        var r = NerveResult()
        out.signals.forEach { r += nerve.emit(it) }
        return listOf(m) + r.messages
    }

    /** 「理論課」「再一支」: today's count and the open lesson's step (code only). */
    private suspend fun lessonStatus(): String {
        val b = lessons.board()
        if (!b.started) return "理論課還沒開始。說「找影片」，我只列真的搜尋結果，你按好才放進課程清單。"
        val cur = b.list.firstOrNull { !it.locked }
        val owed = snapshot.plan(snapshot.today(snapshot.profile())).slot(SlotKey.LESSONS)?.count ?: app.agency.core.lessons.LessonPlan.DAILY
        val head = "今天的理論課：通過 ${b.passedToday} 支，每天要 $owed 支。"
        return head + (cur?.let { "現在這支是「${it.category.zh}」，${it.stage.zh}，到理論課繼續。" } ?: "課程清單沒有影片了，說「找影片」補。")
    }

    /** Today's items waiting for their label (the card's 補標示). */
    /** 備忘錄 (T12b): the page's rows (not deleted), and his check-off, delete and 復原. */
    suspend fun memos(): List<app.agency.core.model.Memo> = memoDesk.list()
    suspend fun checkMemo(id: String, done: Boolean): Boolean = mutex.withLock { memoDesk.check(id, done, clock.millis()).also { if (it) bump() } }
    suspend fun deleteMemo(id: String): Boolean = mutex.withLock { memoDesk.delete(id, clock.millis()).also { if (it) bump() } }
    suspend fun restoreMemo(id: String): Boolean = mutex.withLock { memoDesk.restore(id).also { if (it) bump() } }

    suspend fun pendingLabels(): List<app.agency.core.model.PendingLabel> = meals.pendingToday()

    /** A command word (code only, no model): its line, then what the links said about its signal. */
    private suspend fun command(route: Route.Command, owner: Message): List<Message> {
        // 分週上線: a command of a feature not open yet says when it opens, nothing else.
        val feature = when {
            route.kind == "water" -> app.agency.core.schedule.Feature.WATER
            route.kind == "training" -> trainingFeature(app.agency.core.brain.handlers.TrainingWords.kind(owner.text))
            route.kind == "rest_day" || route.kind == "ride_start" || route.kind == "ride_end" -> app.agency.core.schedule.Feature.RIDE
            route.kind == "hungry_morning" || route.kind == "cheat_declare" -> app.agency.core.schedule.Feature.MEALS
            route.kind == "sign_in" || route.kind == "sign_out" -> app.agency.core.schedule.Feature.SIGN
            route.kind == Router.REAL_TASK -> app.agency.core.schedule.Feature.REAL_TASK
            route.kind.startsWith(Router.GROOMING) -> app.agency.core.schedule.Feature.GROOMING
            route.kind == Router.EXAM_WEEK -> app.agency.core.schedule.Feature.EXAM_WEEK
            route.kind == Router.INCOME || route.kind == Router.INCOME_TARGET -> app.agency.core.schedule.Feature.INCOME
            route.kind.startsWith(Router.WEATHER) -> app.agency.core.schedule.Feature.WEATHER
            else -> null
        }
        closed(feature)?.let { return listOf(reply(route.role, it, WordedBy.TEMPLATE, route.kind)) }
        // 「喝完一壺」「喝了半壺」 (T17): the body teacher's line, like any record.
        if (route.kind == "water") {
            val riding = nerve.state(snapshot.today(snapshot.profile())).riding
            return said(water.record(WaterDesk.halvesIn(owner.text) ?: 0, riding), "water")
        }
        // 「核心完成」「伸展完成」 (T18): typed, so 「未證實」; 「核心開始」 points at the timer.
        if (route.kind == "training") return said(training.typed(owner.text, null), "training")
        // 理論課 (T19c): 「找影片」 searches (real results only, as cards), 「理論課」「再一支」 says where today stands.
        if (route.kind == Router.FIND_VIDEOS) return lessonSaid(lessons.find(app.agency.core.lessons.LessonPlan.categoryIn(owner.text)))
        if (route.kind == Router.LESSON_STATUS) return lessonSaid(app.agency.core.brain.handlers.LessonOutcome(listOf(lessonStatus())))
        val profile = snapshot.profile()
        val today = snapshot.today(profile)
        val plan = nerve.plan(today)
        val at = owner.at
        var signal: Signal? = null
        var earlier: Pair<String, String>? = null
        val text = when {
            route.kind == "rest_day" -> restDay.declare(today, at).also {
                if (store.days.get(today.toString())?.restDay == true) signal = Signal.RestDay("rest:$today", at)
            }
            route.kind == "hungry_morning" -> hungry.declare(today, at).let { (line, changed) ->
                if (changed) signal = Signal.HungryMorning(newId(), at)
                line
            }
            route.kind == "cheat_declare" -> cheat.declare(owner.text, today, clock.now(), snapshot.rules()).let { r ->
                if (r.day != null && r.slot != null) signal = Signal.CheatDeclared(newId(), at, r.day.toString(), r.slot)
                r.line
            }
            route.kind == "sign_in" -> sign.signIn(plan, at).also { signal = it.signal; earlier = it.proposal }.line
            route.kind == "sign_out" -> sign.signOut(plan, at).also { signal = it.signal }.line
            route.kind == Router.REAL_TASK -> if (nerve.state(today).realTaskThisWeek) {
                "這週的真人任務已經記過了。"
            } else {
                signal = Signal.RealTaskDone(newId(), at)
                "記下：這週的真人任務做到了。跟真的人相處，就是這樣。"
            }
            route.kind == "ride_start" -> sign.rideStart(plan, at).also { signal = it.signal }.line
            route.kind == "ride_end" -> sign.rideEnd(plan, at).also { signal = it.signal }.line
            route.kind == Router.EXAM_WEEK -> examWeek(today, at).also { signal = it.second }.first
            route.kind == Router.INCOME -> {
                val amount = app.agency.core.brain.handlers.IncomeWords.report(owner.text)!!.coerceIn(0, app.agency.core.brain.handlers.IncomeDesk.MAX_AMOUNT)
                val s = snapshot.plans.specs().schedule
                val monday = snapshot.weekStart(today)
                val soFar = (0L..java.time.temporal.ChronoUnit.DAYS.between(monday, today)).count { nerve.plan(monday.plusDays(it)).delivery }
                income.record(today, amount, at, s.weeklyIncomeTarget, s.deliveryDaysPerWeek, soFar)
            }
            route.kind == Router.INCOME_TARGET -> {
                val target = app.agency.core.brain.handlers.IncomeWords.target(owner.text)!!.coerceIn(0, app.agency.core.brain.handlers.IncomeDesk.MAX_AMOUNT * 7)
                val specs = snapshot.plans.specs()
                store.rules.putSection(
                    app.agency.core.model.PlanSectionKey.SCHEDULE,
                    app.agency.core.schedule.PlanSpecs.JSON.encodeToString(app.agency.core.schedule.ScheduleSpec.serializer(), specs.schedule.copy(weeklyIncomeTarget = target)),
                    app.agency.core.model.PlanSection.BY_OWNER_SETTINGS, at,
                )
                "記下：外送週目標 $target 元（一週跑 ${specs.schedule.deliveryDaysPerWeek} 天，看一週的總額）。"
            }
            route.kind.startsWith(Router.WEATHER) -> route.kind.removePrefix(Router.WEATHER).split("_").let { (kind, sw) ->
                val on = sw == "ON"
                if (!on) store.nerve.effectsWith(EffectStatus.PENDING).filter { it.day == today.toString() && it.link == Links.L28Weather.id }
                    .forEach { store.nerve.setStatus(it.id, EffectStatus.CANCELLED, at) }
                signal = Signal.Weather(newId(), at, kind, on)
                val what = if (kind == "RAIN") "下雨" else "很熱"
                when {
                    !on -> "好，今天的「$what」提醒取消了。"
                    plan.delivery -> "記下：今天$what。出門前提醒你。"
                    else -> "記下：今天$what。今天沒有跑單，出門的話也一樣小心。"
                }
            }
            route.kind.startsWith(Router.GROOMING) -> GroomingItem.valueOf(route.kind.removePrefix(Router.GROOMING)).let { item ->
                signal = Signal.GroomingDone(newId(), at, item)
                "記下：${item.zh}。"
            }
            else -> Router.NOT_YET
        }
        val line = reply(route.role, text, WordedBy.TEMPLATE, route.kind)
        val said = signal?.let { nerve.emit(it).messages }.orEmpty()
        // After sign-in (T19): the 「提早起床」 proposal when it is due, then the morning score; after sign-out: the review.
        val after = mutableListOf<Message>()
        earlier?.let { (id, ask) ->
            after += Message(newId(), clock.millis(), Who.COMPANY, role = RoleKey.MANAGER, kind = PROPOSALS_KIND, text = ask, refs = MessageRefs(proposal = id, proposals = listOf(id)), wordedBy = WordedBy.TEMPLATE)
                .also { store.messages.insert(it) }
        }
        if (route.kind == "sign_in" && signal != null && store.conditions.get(today.toString()) == null) {
            after += reply(RoleKey.MANAGER, app.agency.core.engine.ConditionPolicy.ASK, WordedBy.TEMPLATE, CONDITION_ASK_KIND)
        }
        if (route.kind == "sign_out" && signal != null) after += reply(RoleKey.MANAGER, ReviewDesk.ASK_GOOD, WordedBy.TEMPLATE, ReviewDesk.GOOD_KIND)
        return listOf(line) + said + after
    }

    /**
     * A record kind the clerk found in words that are no command: the declarations only happen on their exact words (a guess
     * never turns today into a rest day), so the line says them; the other kinds wait for their handlers.
     */
    private fun recordHint(kind: String): String = when (kind) {
        "rest_day" -> "要換成休息日，就打「今天休息」。"
        "hungry_morning" -> "早上很餓就打「早上很餓」，豆漿會挪到早上。"
        "cheat_declare" -> CheatDeclare.FORMAT
        "sign_in" -> "要簽到就打「簽到」，或按首頁的簽到。"
        "sign_out" -> "要睡了就打「簽退」，或按首頁的簽退。"
        "condition" -> "今天狀態幾分？打一到五的數字就好。"
        else -> Router.NOT_YET
    }

    private suspend fun reply(role: RoleKey, text: String, by: WordedBy, kind: String): Message {
        val m = Message(newId(), clock.millis(), Who.COMPANY, role = role, kind = kind, text = text, wordedBy = by)
        store.messages.insert(m)
        return m
    }

    private fun bump() {
        _revision.value += 1
    }

    companion object {
        const val MAX_OWNER_CHARS = 2000
        const val INTAKE_TOKENS = 600
        const val NUDGE_TOKENS = 200
        private const val CHAT = "chat"
        private const val DISCUSS = "discuss"

        /** The kinds of her replies from the tool loop (the ones whose thinking is kept and sent back). */
        val REPLY_KINDS = setOf(CHAT, "mood", "unwell", DISCUSS)

        /** The thinking row of a reply made with thinking off (nothing to send back). */
        const val NO_THINKING = "OFF"

        /** Thinking older than this is deleted (it only ever goes back for the last ten messages). */
        const val THINKING_KEEP_MS = 30L * 24 * 3_600_000

        /** The kind of the line that says several proposals are waiting. */
        const val PROPOSALS_KIND = "proposals"

        /** The kind of the manager's 入社建檔 lines. */
        const val BASELINE_KIND = "baseline"

        /** The kind of the manager's 藝名 lines. */
        const val PROFILE_KIND = "profile"

        /** How far ahead the water plan's start may be set (T17). */
        const val WATER_START_AHEAD_DAYS = 31L

        /** The kinds of the manager's 理論課 lines (T19c); [LESSON_FOUND_KIND] carries candidate cards (`refs.records`). */
        const val LESSON_KIND = "lesson"
        const val LESSON_FOUND_KIND = "lesson_found"

        /** The kind of the body teacher's line about a meal photo (T15). */
        const val MEAL_PHOTO_KIND = "meal_photo"

        /** The kinds of the 狀態打分 lines (T19). */
        const val CONDITION_KIND = "condition"
        const val CONDITION_ASK_KIND = "condition_ask"

        /** What she is asked for after the 檢討卡 (one sentence, no question). */
        const val REVIEW_INSTRUCTION = "他剛做完睡前回顧，下面是程式寫好的檢討卡。用你的語氣說一到兩句睡前的話：具體肯定他說做得好的那件事，提醒明天要改的那件事，叫他去睡。不要問問題，不要提新的要求。"

        /** How far back the weeks on target are counted (L20). */
        const val WEEKS_LOOKBACK_DAYS = 400L

        /** The kind of the fixed 公司規章 reply. */
        const val RULES_KIND = "rules"

        /** The fixed line after the template when the provider gave no answer (code-written; no model). */
        fun failLine(fail: Fail): String = when (fail) {
            Fail.NO_KEY, Fail.NO_SETUP -> "還沒設定好模型和金鑰，請到設定的模型與金鑰。"
            Fail.KEY_REJECTED -> KEY_REJECTED
            Fail.CAP -> "今天的花費到上限了，要調整請到設定的花費上限。"
            Fail.BAD_RESPONSE, Fail.REFUSED -> "模型這次沒有好好回答，再說一次試試。"
            else -> "現在連不上模型，等一下再試。"
        }

        /**
         * Words that make a conversation one about food (his words or the reply). 喝 counts unless what is drunk is water
         * (「去喝水」「喝溫水」「喝三杯水」「喝很多水」「喝礦泉水」 are in many replies and are no food talk); a 水 followed by 果 is
         * a drink (「喝水果茶」). The common drinks are named too, for a line without 喝 (「珍奶好想要」「來一瓶豆漿」). ICU-safe:
         * no bare `}`, no `\b` next to Han (RegexOnAndroidTest runs it on the phone).
         */
        private val FOOD_TALK = Regex(
            "吃|餐|飯|麵|熱量|大卡|卡路里|食物|零食|宵夜|點心|甜點|蛋白質|份量|分量|菜單|外送|" +
                "喝(?![了完一兩二三四五六七八九十點杯口些大碗瓶罐很多夠個]*(?:礦泉水|開水|溫水|熱水|冰水|水)(?!果))|" +
                "飲料|珍奶|珍珠|奶茶|手搖|可樂|汽水|果汁|拿鐵|咖啡|啤酒|酒|含糖|" +
                "冬瓜茶|綠茶|紅茶|烏龍|豆漿|牛奶|優酪乳|氣泡飲|運動飲料|能量飲",
        )

        /** Whether [text] talks about food or a drink that is not water ([FOOD_TALK]). */
        fun mentionsFood(text: String): Boolean = FOOD_TALK.containsMatchIn(text)

        /**
         * The second-pass judge's context line for a ChatLoop turn (arch 1.9b), from code only: today's meals over the quota, a
         * breach today, or a conversation about food. Every ChatLoop turn is conversation (chat, mood, unwell, discuss: command
         * words and record kinds never get here), so food talk is any of them where the clerk found food items or his words
         * or the [reply] he would read name food.
         */
        fun judgeContext(input: FactsInput, foodItems: Boolean, ownerText: String, reply: String): String? {
            val over = input.today.meals.sumOf { it.kcal } - input.rules.quotaKcal
            val foodTalk = foodItems || mentionsFood(ownerText) || mentionsFood(reply)
            return SecondPassJudge.contextLine(over, input.today.breachToday, foodTalk)
        }

        /** KEY_REJECTED covers DeepSeek's 402 (no balance), so he does not delete a good key (FIX-T04 M2). */
        /** His 「輕鬆」 said on its own. */
        const val FELT_EASY_LINE = "記下：你說這次輕鬆。真的穩定了會自動加一級，不用問你。"

        const val KEY_REJECTED = "金鑰被拒或帳戶餘額不足，請到服務商網站確認。"
    }
}

/** One polaroid of the photocard wall (L11): the coach [day] and what earned it. */
data class Photocard(val day: String, val kind: app.agency.core.model.TrainingKind, val at: Long) {
    val zh: String get() = if (kind == app.agency.core.model.TrainingKind.CORE7) "核心七分鐘" else "肌力"
}
