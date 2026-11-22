package app.agency.core.brain

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

    private suspend fun answer(owner: Message, history: List<Message>): Message {
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
        val judgeContext = judgeContext(input, kind, parsed?.items.orEmpty().isNotEmpty(), owner.text)
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
        private const val DISCUSS = "discuss"

        /** The fixed line after the template when the provider gave no answer (code-written; no model). */
        fun failLine(fail: Fail): String = when (fail) {
            Fail.NO_KEY, Fail.NO_SETUP -> "還沒設定好模型和金鑰，請到設定的模型與金鑰。"
            Fail.KEY_REJECTED -> KEY_REJECTED
            Fail.CAP -> "今天的花費到上限了，要調整請到設定的花費上限。"
            Fail.BAD_RESPONSE, Fail.REFUSED -> "模型這次沒有好好回答，再說一次試試。"
            else -> "現在連不上模型，等一下再試。"
        }

        /** Words that make a 討論 a discussion about food. */
        private val FOOD_TALK = Regex("吃|喝|餐|飯|麵|熱量|大卡|卡路里|食物|零食|飲料|宵夜|點心|甜點|蛋白質|份量|分量|菜單|外送")

        /**
         * The second-pass judge's context line for this turn (arch 1.9b), from code only: today's meals over the quota, a breach
         * today, or a discussion about food (the clerk's kind is discuss and it found food items, or his words name food).
         */
        fun judgeContext(input: FactsInput, kind: String, foodItems: Boolean, ownerText: String): String? {
            val over = input.today.meals.sumOf { it.kcal } - input.rules.quotaKcal
            val foodTalk = kind == DISCUSS && (foodItems || FOOD_TALK.containsMatchIn(ownerText))
            return SecondPassJudge.contextLine(over, input.today.breachToday, foodTalk)
        }

        /** KEY_REJECTED covers DeepSeek's 402 (no balance), so he does not delete a good key (FIX-T04 M2). */
        const val KEY_REJECTED = "金鑰被拒或帳戶餘額不足，請到服務商網站確認。"
    }
}
