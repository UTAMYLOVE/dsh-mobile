package app.dshmobile.coach

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Today's numbers for the strip at the top of the coach page. */
data class Strip(val remainingKcal: Int, val proteinLeftG: Int, val nextGoalKg: Double?, val overBy: Int)

data class CoachUi(val messages: List<CoachMessage>, val busy: Boolean, val strip: Strip)

/** An estimate waits this long for its label; after that it is dropped from 待確認. */
const val PENDING_DAYS = 7

/**
 * One owner message at a time (a [Mutex]), in [scope] so leaving the screen never cancels it. The model only sorts the message
 * ([MealParse]) and, for talk, writes a reply under [CoachPrompt]'s rules; every record and every number comes from here.
 * Records are saved before any reply call, so a failed call never loses one.
 */
class CoachEngine(
    private val store: CoachStore,
    private val settings: CoachSettings,
    private val model: CoachModel,
    private val clock: Clock,
    private val zone: ZoneId,
    private val scope: CoroutineScope,
    /** Gets only an exception's class name (never the owner's words or a key). */
    private val onError: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private val busy = MutableStateFlow(false)
    private val _ui = MutableStateFlow(build())
    val ui: StateFlow<CoachUi> = _ui.asStateFlow()

    init {
        scope.launch { store.messages.collect { refresh() } }
        scope.launch { store.state.collect { refresh() } }
        scope.launch { settings.revision.collect { refresh() } }
        scope.launch { busy.collect { refresh() } }
    }

    /** Recomputes the strip (also on a new day: the page calls this when it shows). */
    fun refresh() { _ui.value = build() }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        scope.launch { handle(t) }
    }

    private fun today(): LocalDate = CoachRules.coachDay(clock.instant(), zone, settings.profile.dayStartHour)

    private fun currentKg(s: CoachState, p: CoachProfile): Double = s.weights.maxByOrNull { it.at }?.kg ?: p.startWeightKg

    private fun build(): CoachUi {
        val s = store.state.value
        val p = settings.profile
        val day = today().toString()
        val meals = s.meals.filter { it.day == day }
        val eaten = meals.sumOf { it.kcal }
        val protein = meals.sumOf { it.proteinG }
        val remaining = CoachRules.remainingKcal(p.quotaKcal, eaten)
        return CoachUi(
            messages = store.messages.value,
            busy = busy.value,
            strip = Strip(maxOf(0, remaining), CoachRules.remainingProtein(p.proteinG, protein), CoachRules.nextGoal(p.goals, currentKg(s, p)), maxOf(0, -remaining)),
        )
    }

    fun summaries(s: CoachState, p: CoachProfile, before: LocalDate, days: Int): List<DailySummary> =
        (1..days).map { before.minusDays(it.toLong()) }.map { d -> summary(s, p, d.toString()) }

    private fun summary(s: CoachState, p: CoachProfile, day: String): DailySummary {
        val meals = s.meals.filter { it.day == day }
        val kcal = meals.sumOf { it.kcal }
        val over = maxOf(0, kcal - p.quotaKcal)
        return DailySummary(day, meals.isNotEmpty(), meals.isNotEmpty() && over == 0 && meals.none { it.offPlan }, kcal, meals.sumOf { it.proteinG }, over)
    }

    fun totals(s: CoachState, p: CoachProfile): Totals {
        val over = s.meals.map { it.day }.distinct().sumOf { summary(s, p, it).overBy }
        return Totals(over, CoachRules.delayDays(over))
    }

    private fun livePending(s: CoachState) = s.pending.filter { it.askedAt >= clock.millis() - PENDING_DAYS * 86_400_000L }

    /** When the next coach day starts (the page refreshes its strip then). */
    fun millisToNextDay(): Long {
        val p = settings.profile
        val next = today().plusDays(1).atStartOfDay(zone).plusHours(p.dayStartHour.toLong()).toInstant()
        return (next.toEpochMilli() - clock.millis()).coerceAtLeast(1_000L)
    }

    /** 食物表's 刪除, off the main thread. */
    fun deleteFood(name: String) {
        scope.launch { store.update { s -> s.copy(food = s.food.filter { it.name != name }) } }
    }

    /** Handles one owner message to the end (tests call it directly). */
    suspend fun handle(text: String) = mutex.withLock {
        val history = store.messages.value
        store.append(CoachMessage(UUID.randomUUID().toString(), clock.millis(), Speaker.OWNER, text, null))
        busy.value = true
        try {
            val (reply, kind) = try {
                respond(text, history)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.javaClass.simpleName)
                CoachReplies.CHAT_DOWN to "error"
            }
            store.append(CoachMessage(UUID.randomUUID().toString(), clock.millis(), Speaker.COACH, reply, kind))
        } finally {
            busy.value = false
        }
    }

    private suspend fun parse(text: String, history: List<CoachMessage>): Pair<Parsed?, Fail?> {
        val turns = CoachPrompt.parseTurns(history, text)
        val first = model.complete(MealParse.SYSTEM, turns)
        if (first is ModelReply.Failed) return null to first.why
        MealParse.parse((first as ModelReply.Ok).text)?.let { return it to null }
        // The retry keeps the 10-message limit: 7 stored turns, the sentence, the bad answer and the reminder.
        val retry = CoachPrompt.parseTurns(history, text, keep = CoachPrompt.HISTORY - 2) + Turn.assistant(first.text.take(400)) + Turn.user(MealParse.RETRY)
        val again = model.complete(MealParse.SYSTEM, retry)
        if (again is ModelReply.Failed) return null to again.why
        return MealParse.parse((again as ModelReply.Ok).text) to null
    }

    private suspend fun respond(text: String, history: List<CoachMessage>): Pair<String, String?> {
        val (parsedOrNull, fail) = parse(text, history)
        var parsed = parsedOrNull
        if (parsed == null && fail != null) {
            // Offline: a message naming exactly one table food (and not asking) is still recorded from the table.
            val table = store.state.value.food
            val named = FoodTable.named(table, text)
            if (named.size == 1 && CoachReplies.plainReport(text)) {
                parsed = Parsed("meal", listOf(ParsedItem(named[0].name, 1.0, false, null, null, null, null)))
            } else {
                return CoachReplies.cannotReach(fail) to "error"
            }
        }
        if (parsed == null) return CoachReplies.NOT_UNDERSTOOD to "error"
        // The kind is not left to the model alone: 「想吃…」 without 「吃了」 is a craving, whatever it says.
        if (parsed.kind == "meal" && CoachReplies.isCraving(text)) parsed = parsed.copy(kind = "craving")
        // A number the owner did not write himself never becomes a weight or a label value (the model must not invent one).
        parsed = parsed.copy(
            weightKg = CoachNumbers.ownersOnly(parsed.weightKg, text),
            waistCm = CoachNumbers.ownersOnly(parsed.waistCm, text),
            foodInfo = parsed.foodInfo?.let { fi ->
                fi.copy(kcal = CoachNumbers.ownersOnly(fi.kcal?.toDouble(), text)?.toInt(), proteinG = CoachNumbers.ownersOnly(fi.proteinG, text))
            },
        )
        val illness = parsed.illness || CoachReplies.ILL_WORDS.any { text.contains(it) }
        val lowIntake = lowIntake()
        val lines = mutableListOf<String>()
        when (parsed.kind) {
            "meal" -> {
                if (parsed.items.isEmpty() && parsed.weightKg == null) return CoachReplies.NOT_UNDERSTOOD to "meal"
                if (parsed.items.isNotEmpty()) lines += meal(text, parsed.items, illness)
                parsed.weightKg?.let { lines += weight(it, parsed.waistCm) }
            }
            "weight" -> {
                val kg = parsed.weightKg ?: return "沒聽到體重。說「今天體重 79.5」。" to "weight"
                lines += weight(kg, parsed.waistCm)
                if (parsed.items.isNotEmpty()) lines += meal(text, parsed.items, illness)
            }
            "craving" -> {
                if (illness) return chat(text, history, illness, lowIntake, null) to "chat"
                lines += craving(parsed.items)
            }
            "food_info" -> lines += foodInfo(parsed.foodInfo, illness)
            else -> return chat(text, history, illness, lowIntake, null) to "chat"
        }
        var reply = lines.joinToString("\n")
        if ((illness || lowIntake) && !reply.contains("看醫生")) reply += "\n" + CoachReplies.DOCTOR
        return reply to parsed.kind
    }

    private fun lowIntake(): Boolean {
        val s = store.state.value
        val p = settings.profile
        val last = summaries(s, p, today(), 2)
        return last.all { it.reported && it.kcal < CoachRules.FLOOR_KCAL }
    }

    private fun dayTotals(s: CoachState, day: String): Pair<Int, Double> {
        val meals = s.meals.filter { it.day == day }
        return meals.sumOf { it.kcal } to meals.sumOf { it.proteinG }
    }

    private fun meal(text: String, items: List<ParsedItem>, illness: Boolean): List<String> {
        val p = settings.profile
        val today = today()
        val cheat = today.dayOfWeek == p.cheatDay
        val mealId = UUID.randomUUID().toString()
        val now = clock.millis()
        val lines = mutableListOf<String>()
        val counted = mutableListOf<MealItem>()
        val pending = mutableListOf<PendingFood>()
        val table = store.state.value.food
        items.forEach { it0 ->
            val entry = FoodTable.lookup(table, it0.name)
            when {
                entry != null -> counted += FoodTable.tableItem(entry, it0.name, it0.portion, it0.fried)
                it0.kcalHigh != null -> {
                    val item = FoodTable.estimateItem(it0.name, it0.portion, it0.fried, it0.kcalHigh, it0.proteinLow, p.marginPct)
                    pending += PendingFood(mealId, counted.size, item.name, item.kcal, now)
                    counted += item
                }
                else -> lines += CoachReplies.noEstimate(it0.name)
            }
        }
        if (counted.isEmpty()) return lines.ifEmpty { listOf(CoachReplies.NOT_UNDERSTOOD) }
        val fried = counted.any { it.fried }
        val m = Meal(mealId, now, today.toString(), text.take(200), counted, cheat = cheat, offPlan = fried && !cheat)
        val cutoff = now - PENDING_DAYS * 86_400_000L
        val s = store.update { it.copy(meals = it.meals + m, pending = it.pending.filter { p0 -> p0.askedAt >= cutoff } + pending) }
            ?: return listOf(CoachReplies.NOT_SAVED)
        counted.forEach { lines += CoachReplies.itemLine(it, p.marginPct) }
        if (fried && !cheat) lines += CoachReplies.OFF_PLAN
        lines += CoachReplies.UNVERIFIED
        pending.forEach { lines += CoachReplies.askLabel(it.name) }
        val (eaten, protein) = dayTotals(s, today.toString())
        lines += CoachReplies.dayStatus(p, eaten, protein, CoachRules.nextGoal(p.goals, currentKg(s, p)), currentKg(s, p), illness)
        return lines
    }

    private fun weight(kg: Double, waist: Double?): String {
        val p = settings.profile
        val now = clock.millis()
        store.update { it.copy(weights = it.weights + WeightEntry(UUID.randomUUID().toString(), now, today().toString(), kg, waist)) }
            ?: return CoachReplies.NOT_SAVED
        return CoachReplies.weight(kg, waist, p, CoachRules.nextGoal(p.goals, kg))
    }

    private fun craving(items: List<ParsedItem>): List<String> {
        val p = settings.profile
        val s = store.state.value
        val today = today()
        val cheat = today.dayOfWeek == p.cheatDay
        val (eaten, _) = dayTotals(s, today.toString())
        val remaining = CoachRules.remainingKcal(p.quotaKcal, eaten)
        if (items.isEmpty()) return listOf(CoachReplies.NOT_UNDERSTOOD)
        val friedNames = items.filter { FoodTable.isFried(it.name, it.fried, FoodTable.lookup(s.food, it.name)) }.map { it.name }
        val deadline = "${p.evidenceMinutes} 分鐘內把訂單截圖傳來。"
        if (friedNames.isNotEmpty() && !cheat) {
            val planned = FoodTable.lookup(s.food, p.plannedMeal)
            val word = if (s.meals.none { it.day == today.toString() }) "晚餐" else "宵夜"
            val eatLine = when {
                remaining < 0 -> "今天已經超過，不准再點。"
                remaining == 0 -> "今天剩 0 大卡，不准再點。"
                planned != null && planned.kcal > remaining -> "今天剩 $remaining 大卡，不准再點炸的。" + floorLine(eaten, word, p)
                else -> "${word}吃${p.plannedMeal.replace(" ", "")}。"
            }
            return listOf("今天不是放縱餐。$eatLine${friedNames.joinToString("、")}排到星期${CoachRules.weekdayZh(p.cheatDay)}。$deadline")
        }
        // Not fried, or the cheat day: allowed only if it fits what is left (ruling Q6).
        val kcal = items.sumOf { i ->
            val e = FoodTable.lookup(s.food, i.name)
            when {
                e != null -> FoodTable.tableItem(e, i.name, i.portion, i.fried).kcal
                i.kcalHigh != null -> FoodTable.estimateKcal(i.kcalHigh, i.portion, p.marginPct)
                else -> 0
            }
        }
        if (items.any { FoodTable.lookup(s.food, it.name) == null && it.kcalHigh == null }) {
            return listOf("估不出這是多少大卡。把包裝標示或份量傳來再說。")
        }
        val word = if (s.meals.none { it.day == today.toString() }) "晚餐" else "宵夜"
        return when {
            remaining < 0 -> listOf("今天已經超過，不准再點。")
            remaining == 0 -> listOf("今天剩 0 大卡，不准再點。")
            kcal > remaining -> listOf("這份大約 $kcal 大卡，今天剩 $remaining 大卡，不准再點。" + floorLine(eaten, word, p))
            else -> listOf("可以。吃了今天會剩 ${remaining - kcal} 大卡。吃完回報，$deadline")
        }
    }

    /** Spec §4: never below 1200. A refusal still says how much he must eat, and what. */
    private fun floorLine(eaten: Int, word: String, p: CoachProfile): String {
        val must = CoachRules.mustStillEat(eaten)
        return if (must > 0) "今天至少還要吃 $must 大卡。${word}吃${p.plannedMeal.replace(" ", "")}。" else ""
    }

    private fun foodInfo(info: FoodInfo?, illness: Boolean): List<String> {
        val p = settings.profile
        val s0 = store.state.value
        val live = s0.pending.filter { it.askedAt >= clock.millis() - PENDING_DAYS * 86_400_000L }
        if (live.isEmpty()) return listOf(CoachReplies.NO_PENDING)
        val target = info?.name?.let { n -> live.lastOrNull { FoodTable.normalize(it.name) == FoodTable.normalize(n) } } ?: live.last()
        val meal = s0.meals.firstOrNull { it.id == target.mealId }
        val item = meal?.items?.getOrNull(target.itemIndex)
        if (meal == null || item == null) {
            store.update { it.copy(pending = it.pending - target) }
            return listOf(CoachReplies.NO_PENDING)
        }
        val label = info?.kcal
        val now = clock.millis()
        val (entry, newItem) = when {
            label != null -> {
                val e = FoodEntry(item.name, emptyList(), label, info.proteinG, null, null, item.fried, estimated = false, seeded = false, addedAt = now)
                e to item.copy(
                    kcal = kotlin.math.ceil(label * item.portion - 1e-9).toInt(), proteinG = info.proteinG?.let { it * item.portion } ?: item.proteinG,
                    source = KcalSource.LABEL, tableEstimated = false,
                )
            }
            info?.confirm == true -> {
                val perPortion = FoodTable.estimateKcal(item.estimateHigh ?: item.kcal, 1.0, p.marginPct)
                val e = FoodEntry(item.name, emptyList(), perPortion, item.proteinG?.let { it / item.portion }, null, null, item.fried, estimated = true, seeded = false, addedAt = now)
                e to item.copy(source = KcalSource.TABLE, tableEstimated = true)
            }
            else -> return listOf(CoachReplies.FOOD_INFO_UNCLEAR)
        }
        val s = store.update { st ->
            st.copy(
                food = st.food.filter { FoodTable.normalize(it.name) != FoodTable.normalize(entry.name) } + entry,
                pending = st.pending - target,
                meals = st.meals.map { m -> if (m.id != meal.id) m else m.copy(items = m.items.mapIndexed { i, it -> if (i == target.itemIndex) newItem else it }) },
            )
        } ?: return listOf(CoachReplies.NOT_SAVED)
        val proteinText = entry.proteinG?.let { "、蛋白質 ${CoachReplies.portion(it)} 克" } ?: ""
        val lines = mutableListOf("存進食物表：${entry.name} ${entry.kcal} 大卡$proteinText（${if (entry.estimated) "估算" else "標示"}）。")
        val (eaten, protein) = dayTotals(s, meal.day)
        if (meal.day == today().toString()) lines += CoachReplies.dayStatus(p, eaten, protein, CoachRules.nextGoal(p.goals, currentKg(s, p)), currentKg(s, p), illness)
        return lines
    }

    private suspend fun chat(text: String, history: List<CoachMessage>, illness: Boolean, lowIntake: Boolean, already: String?): String {
        val s = store.state.value
        val p = settings.profile
        val today = today()
        val meals = s.meals.filter { it.day == today.toString() }
        val latest = s.weights.maxByOrNull { it.at }
        val facts = CoachPrompt.facts(
            CoachPrompt.Facts(
                profile = p, today = today, currentKg = currentKg(s, p), currentAt = latest?.let { LocalDate.parse(it.day) },
                nextGoal = CoachRules.nextGoal(p.goals, currentKg(s, p)), todayMeals = meals,
                eatenKcal = meals.sumOf { it.kcal }, eatenProtein = meals.sumOf { it.proteinG }, pending = livePending(s),
                summaries = summaries(s, p, today, CoachPrompt.SUMMARY_DAYS), totals = totals(s, p),
                illness = illness, lowIntake = lowIntake, already = already,
            ),
        )
        val turns = CoachPrompt.history(history) + CoachPrompt.finalTurn(facts, text)
        var reply = when (val r = model.complete(CoachPrompt.SYSTEM, turns)) {
            is ModelReply.Ok -> NumberGuard.filter(r.text, facts, text).ifBlank { CoachReplies.CHAT_FALLBACK }
            is ModelReply.Failed -> CoachReplies.CHAT_DOWN
        }
        if ((illness || lowIntake) && !reply.contains("看醫生")) reply += "\n" + CoachReplies.DOCTOR
        return reply
    }

    /** What a reply call would send for [text] now (CoachPromptTest measures it). */
    internal fun promptFor(text: String): Pair<String, List<Turn>> {
        val s = store.state.value
        val p = settings.profile
        val today = today()
        val meals = s.meals.filter { it.day == today.toString() }
        val facts = CoachPrompt.facts(
            CoachPrompt.Facts(
                p, today, currentKg(s, p), null, CoachRules.nextGoal(p.goals, currentKg(s, p)), meals, meals.sumOf { it.kcal },
                meals.sumOf { it.proteinG }, livePending(s), summaries(s, p, today, CoachPrompt.SUMMARY_DAYS), totals(s, p), false, false, null,
            ),
        )
        return CoachPrompt.SYSTEM to (CoachPrompt.history(store.messages.value) + CoachPrompt.finalTurn(facts, text))
    }
}
