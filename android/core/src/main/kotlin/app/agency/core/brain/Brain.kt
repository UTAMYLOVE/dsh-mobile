package app.agency.core.brain

import app.agency.core.brain.handlers.BodyWords
import app.agency.core.brain.handlers.CheatDeclare
import app.agency.core.brain.handlers.HungryMorning
import app.agency.core.brain.handlers.IdolProfile
import app.agency.core.brain.handlers.RestDay
import app.agency.core.brain.handlers.Sign
import app.agency.core.brain.handlers.Onboarding
import app.agency.core.brain.handlers.OnboardingStatus
import app.agency.core.brain.handlers.OnboardingStep
import app.agency.core.engine.IdolCard
import app.agency.core.engine.GroomingItem
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
import app.agency.core.model.Message
import app.agency.core.model.NerveEffect
import app.agency.core.model.MessageMode
import app.agency.core.model.RoleKey
import app.agency.core.model.Who
import app.agency.core.model.WordedBy
import app.agency.core.persona.PromptBuilder
import app.agency.core.persona.PromptInput
import app.agency.core.persona.Roles
import app.agency.core.rules.FoodTable
import app.agency.core.rules.RulesBook
import app.agency.core.store.AgencyStore
import app.agency.core.structured.Intake
import app.agency.core.structured.Structured
import app.agency.core.structured.StructuredCaller
import app.agency.core.time.AgencyClock
import app.agency.core.tools.ToolRegistry
import app.agency.core.tools.readTools
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one queue every inbound event goes through (arch 1.3): one at a time, so a message, a button and an alarm never
 * interleave writes. His message is stored before any model call, so a failed call never loses it.
 *
 * Stage 1's first cut (T06): a command word or a record kind gets [Router.NOT_YET] from the role it belongs to; anything else
 * goes to the data clerk (Intake) for its kind, then chat, mood and unwell go to the manager's [ChatLoop] (NORMAL mode, tools
 * 1, 2 and 6), whose reply has passed the output guard or is the template. When the provider gives no answer the reply is the
 * template plus a fixed line saying why ([failLine]); [lastFail] keeps that reason for 設定 › 模型與金鑰. On a day over the
 * quota or with a breach, or while he discusses food, a reply that passed the guard also goes to the second-pass judge
 * (arch 1.9b) on the data clerk's client.
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
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()

    /** 連動中樞 (T11b): features emit signals, links answer them. */
    val nerve = Nerve(store, clock, newId)
    private val snapshot = SnapshotReader(store, clock) { nerve.changes(it) }
    private val structured = StructuredCaller(intakeLlm, provider)
    private val loop = ChatLoop(llm, provider, judge = SecondPassJudge(intakeLlm, provider))
    private val tools = ToolRegistry(readTools(store, snapshot))
    private val onboarding = Onboarding(store, clock, newId)
    private val idol = IdolProfile(store)
    private val restDay = RestDay(store, snapshot.plans)
    private val hungry = HungryMorning(store)
    private val cheat = CheatDeclare(store, snapshot.plans, clock.zone)
    private val sign = Sign(store, clock.zone, newId)

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
            val history = store.messages.recent(FactsBuilder.MAX_MESSAGES)
            val owner = Message(newId(), clock.millis(), Who.OWNER, text = clean)
            store.messages.insert(owner)
            bump()
            val started = ensureDay()
            (listOf(owner) + started + answer(owner, history)).sortedWith(compareBy({ it.at }, { if (it.id == owner.id) 0 else 1 })).also { bump() }
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
        return nerve.emit(Signal.DayStarted("day:$day", clock.millis(), day.toString()))
    }

    /** 「今天的連動」 (the war room's card): today's effects, oldest first. */
    suspend fun links(): List<NerveEffect> = nerve.today(snapshot.today(snapshot.profile()))

    /** He cancels a 今天微調 of today from the card. */
    suspend fun cancelLink(effectId: String): Boolean = mutex.withLock { nerve.cancel(effectId).also { if (it) bump() } }

    /** His tap on a link proposal: the answer is written as the manager's line. */
    suspend fun decideLink(effectId: String, accept: Boolean): Message? = mutex.withLock {
        nerve.decide(effectId, accept)?.let { reply(RoleKey.MANAGER, it, WordedBy.TEMPLATE, Nerve.KIND) }.also { bump() }
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
            status.baseline?.photoFront, Stickers.fromSlots(slots, records), slots,
        )
    }

    private suspend fun baselineLine(text: String): Message =
        reply(RoleKey.MANAGER, text, WordedBy.TEMPLATE, BASELINE_KIND).also { bump() }

    private suspend fun answer(owner: Message, history: List<Message>): List<Message> {
        // An open 入社建檔 question reads his message as its answer first (code, no model); anything else goes on as usual.
        onboarding.answer(owner.text)?.let { return listOf(reply(RoleKey.MANAGER, it, WordedBy.TEMPLATE, BASELINE_KIND)) }
        idol.answer(owner.text)?.let { return listOf(reply(RoleKey.MANAGER, it, WordedBy.TEMPLATE, PROFILE_KIND)) }
        body(owner)?.let { return it }
        return when (val route = Router.route(owner.text)) {
            is Route.Command -> command(route, owner)
            Route.RulesLocked -> listOf(reply(RoleKey.MANAGER, RulesBook.LOCKED, WordedBy.TEMPLATE, RULES_KIND))
            Route.Model -> listOf(model(owner, history))
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
            val waiting = nerve.pending().filter { it.day == snapshot.today(snapshot.profile()).toString() || it.link == "L23" }
            if (waiting.size == 1) {
                nerve.decide(waiting[0].id, accept = t in Router.YES)?.let { return listOf(reply(RoleKey.MANAGER, it, WordedBy.TEMPLATE, Nerve.KIND)) }
            }
        }
        BodyWords.illness(owner.text)?.let { (kind, word) ->
            val said = nerve.emit(Signal.Unwell(newId(), owner.at, kind, word)).messages
            return said.ifEmpty { listOf(reply(RoleKey.MANAGER, "知道了，先照顧身體。今天的調整已經提過了，在指揮室的今天的連動按好或不要。", WordedBy.TEMPLATE, Nerve.KIND)) }
        }
        if (BodyWords.asksLess(owner.text)) return nerve.emit(Signal.ReduceAsked(newId(), owner.at)).messages.ifEmpty { null }
        if (BodyWords.moodLow(owner.text)) nerve.emit(Signal.MoodLow(newId(), owner.at))
        return null
    }

    private suspend fun model(owner: Message, history: List<Message>): Message {
        val input = snapshot.facts(history)
        val facts = FactsBuilder.build(input)
        val intake = structured.call(
            Intake.spec(owner.text), PromptBuilder.intakeSystem(), PromptBuilder.intakeTurns(facts, owner.text), Purpose.INTAKE,
            INTAKE_TOKENS, fallback = { null },
        )
        val parsed = (intake as? Structured.Model)?.value
        val kind = parsed?.kind ?: CHAT
        val gentleToday = nerve.state(snapshot.today(input.profile)).gentle
        val tone = ToneDecider.decide(ToneInputs(ownerText = owner.text, intakeKind = kind, breachToday = input.today.breachToday, gentleToday = gentleToday))
        if (intake is Structured.Template && intake.fail != null) {
            _lastFail.value = intake.fail
            return reply(RoleKey.MANAGER, Roles.template(RoleKey.MANAGER, tone) + failLine(intake.fail), WordedBy.TEMPLATE, kind)
        }
        if (Router.isRecordKind(kind)) return reply(Router.roleOf(kind), recordHint(kind), WordedBy.TEMPLATE, kind)

        val profile = input.profile
        val today = snapshot.today(profile)
        val dayOf = { at: Long -> snapshot.dayOf(Instant.ofEpochMilli(at), profile) }
        val latin = profile.idolNames
        val system = PromptBuilder.system(
            PromptInput(
                role = Roles.MANAGER, mode = MessageMode.NORMAL, tone = tone, rules = input.rules,
                memory = MemoryBlocks.render(store.memory.items(), today, dayOf), facts = facts,
                companyName = profile.companyName ?: PromptInput.DEFAULT_COMPANY,
                latinNames = (OutputGuard.LATIN_WHITELIST + latin).distinct(),
                // Memory events are applied from T12 on (with 她記得的事 to see and undo them); until then none is asked for.
                memoryWrites = false,
            ),
        )
        // The seed table too: on a fresh install the store has no food yet, and 給力盒子 or 全家 must not read as unknown chains.
        val names = (FoodTable.seed(0) + store.food.all()).flatMap { listOf(it.name) + it.aliases }.distinct()
        val factsText = facts.json.toString()
        val foodItems = parsed?.items.orEmpty().isNotEmpty()
        val judgeContext = { shown: String -> judgeContext(input, foodItems, owner.text, shown) }
        val round = ChatRound(system, PromptBuilder.turns(facts, owner), RoleKey.MANAGER, MessageMode.NORMAL, tone, judgeContext) { outputs ->
            Evidence(listOf(factsText) + outputs, names, latin, ownerText = owner.text, breachToday = input.today.breachToday)
        }
        val result = loop.run(round, tools)
        _lastFail.value = result.fail
        val text = result.utterance.text + (result.fail?.let(::failLine) ?: "")
        return reply(RoleKey.MANAGER, text, if (result.fromModel) WordedBy.MODEL else WordedBy.TEMPLATE, kind)
    }

    /** A command word (code only, no model): its line, then what the links said about its signal. */
    private suspend fun command(route: Route.Command, owner: Message): List<Message> {
        val profile = snapshot.profile()
        val today = snapshot.today(profile)
        val plan = nerve.plan(today)
        val at = owner.at
        var signal: Signal? = null
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
            route.kind == "sign_in" -> sign.signIn(plan, at).also { signal = it.signal }.line
            route.kind == "sign_out" -> sign.signOut(plan, at).also { signal = it.signal }.line
            route.kind == "ride_start" -> sign.rideStart(plan, at).also { signal = it.signal }.line
            route.kind == "ride_end" -> sign.rideEnd(plan, at).also { signal = it.signal }.line
            route.kind.startsWith(Router.GROOMING) -> GroomingItem.valueOf(route.kind.removePrefix(Router.GROOMING)).let { item ->
                signal = Signal.GroomingDone(newId(), at, item)
                "記下：${item.zh}。"
            }
            else -> Router.NOT_YET
        }
        val line = reply(route.role, text, WordedBy.TEMPLATE, route.kind)
        val said = signal?.let { nerve.emit(it).messages }.orEmpty()
        return listOf(line) + said
    }

    /**
     * A record kind the clerk found in words that are no command: the declarations only happen on their exact words (a guess
     * never turns today into a rest day), so the line says them; the other kinds wait for their handlers.
     */
    private fun recordHint(kind: String): String = when (kind) {
        "rest_day" -> "要換成休息日，就打「今天休息」。"
        "hungry_morning" -> "早上很餓就打「早上很餓」，豆漿會挪到早上。"
        "cheat_declare" -> CheatDeclare.FORMAT
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
        private const val CHAT = "chat"

        /** The kind of the manager's 入社建檔 lines. */
        const val BASELINE_KIND = "baseline"

        /** The kind of the manager's 藝名 lines. */
        const val PROFILE_KIND = "profile"

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
        const val KEY_REJECTED = "金鑰被拒或帳戶餘額不足，請到服務商網站確認。"
    }
}
