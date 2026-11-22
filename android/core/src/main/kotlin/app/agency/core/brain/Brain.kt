package app.agency.core.brain

import app.agency.core.brain.handlers.IdolProfile
import app.agency.core.brain.handlers.Onboarding
import app.agency.core.brain.handlers.OnboardingStatus
import app.agency.core.brain.handlers.OnboardingStep
import app.agency.core.engine.IdolCard
import app.agency.core.engine.IdolCardFacts
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
import app.agency.core.model.MessageMode
import app.agency.core.model.RoleKey
import app.agency.core.model.Who
import app.agency.core.model.WordedBy
import app.agency.core.persona.PromptBuilder
import app.agency.core.persona.PromptInput
import app.agency.core.persona.Roles
import app.agency.core.rules.FoodTable
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
    private val snapshot = SnapshotReader(store, clock)
    private val structured = StructuredCaller(intakeLlm, provider)
    private val loop = ChatLoop(llm, provider, judge = SecondPassJudge(intakeLlm, provider))
    private val tools = ToolRegistry(readTools(store, snapshot))
    private val onboarding = Onboarding(store, clock, newId)
    private val idol = IdolProfile(store)

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
            listOf(owner, answer(owner, history)).also { bump() }
        } finally {
            _busy.value = false
        }
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
        return IdolCard.build(
            profile, snapshot.rules(), snapshot.records(day), day, snapshot.currentWeightKg(day, profile), missing,
            status.baseline?.photoFront,
        )
    }

    private suspend fun baselineLine(text: String): Message =
        reply(RoleKey.MANAGER, text, WordedBy.TEMPLATE, BASELINE_KIND).also { bump() }

    private suspend fun answer(owner: Message, history: List<Message>): Message {
        // An open 入社建檔 question reads his message as its answer first (code, no model); anything else goes on as usual.
        onboarding.answer(owner.text)?.let { return reply(RoleKey.MANAGER, it, WordedBy.TEMPLATE, BASELINE_KIND) }
        idol.answer(owner.text)?.let { return reply(RoleKey.MANAGER, it, WordedBy.TEMPLATE, PROFILE_KIND) }
        when (val route = Router.route(owner.text)) {
            is Route.NotYet -> return reply(route.role, Router.NOT_YET, WordedBy.TEMPLATE, route.kind)
            Route.Model -> Unit
        }
        val input = snapshot.facts(history)
        val facts = FactsBuilder.build(input)
        val intake = structured.call(
            Intake.spec(owner.text), PromptBuilder.intakeSystem(), PromptBuilder.intakeTurns(facts, owner.text), Purpose.INTAKE,
            INTAKE_TOKENS, fallback = { null },
        )
        val parsed = (intake as? Structured.Model)?.value
        val kind = parsed?.kind ?: CHAT
        val tone = ToneDecider.decide(ToneInputs(ownerText = owner.text, intakeKind = kind, breachToday = input.today.breachToday))
        if (intake is Structured.Template && intake.fail != null) {
            _lastFail.value = intake.fail
            return reply(RoleKey.MANAGER, Roles.template(RoleKey.MANAGER, tone) + failLine(intake.fail), WordedBy.TEMPLATE, kind)
        }
        if (Router.isRecordKind(kind)) return reply(Router.roleOf(kind), Router.NOT_YET, WordedBy.TEMPLATE, kind)

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
