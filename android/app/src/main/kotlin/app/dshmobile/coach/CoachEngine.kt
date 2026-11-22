package app.dshmobile.coach

import app.agency.core.guard.CoachNumbers
import app.agency.core.guard.OutputGuard
import app.agency.core.model.FoodEntry
import app.agency.core.model.KcalSource
import app.agency.core.model.MealItem
import app.agency.core.rules.CoachRules
import app.agency.core.rules.FoodTable
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

/** The 練習生證 at the top of the home page. */
data class TraineeCard(
    val company: String,
    val number: String,
    val level: String,
    val dayNumber: Long,
    val slots: List<Slot>,
    val violations: Int,
    val baselineMissing: List<BaselineStep>,
    /** The 入社建檔 step the 經紀人 is asking now (null when none is open). */
    val asking: BaselineStep?,
)

data class CoachUi(val messages: List<CoachMessage>, val busy: Boolean, val strip: Strip, val card: TraineeCard)

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
        val td = today()
        return CoachUi(
            messages = store.messages.value,
            busy = busy.value,
            strip = Strip(maxOf(0, remaining), CoachRules.remainingProtein(p.proteinG, protein), CoachRules.nextGoal(p.goals, currentKg(s, p)), maxOf(0, -remaining)),
            card = TraineeCard(
                company = settings.companyName,
                number = AgencyRules.TRAINEE_NO,
                level = AgencyRules.LEVEL,
                dayNumber = AgencyRules.dayNumber(settings.joinDate, td).coerceAtLeast(1),
                slots = AgencySchedule.ticked(td, settings.slotTimes, s.meals, s.trainings, zone, p.dayStartHour),
                violations = AgencyRules.violationsOn(s.meals, day),
                baselineMissing = (s.baseline ?: Baseline()).missing,
                asking = s.baseline?.step,
            ),
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
        scope.launch {
            val s = store.update { st -> st.copy(food = st.food.filter { it.name != name }) } ?: return@launch
            // The planned meal must stay a table food (its kcal is checked before it is named).
            val p = settings.profile
            if (p.plannedMeal == name || FoodTable.lookup(s.food, p.plannedMeal) == null) {
                s.food.firstOrNull()?.let { settings.profile = p.copy(plannedMeal = it.name) }
            }
        }
    }

    /** How long the page waits before it refreshes the strip: at most a minute, so a phone waking from sleep catches up soon. */
    fun nextRefreshDelay(): Long = minOf(millisToNextDay(), 60_000L)

    /** Handles one owner message to the end (tests call it directly). */
    suspend fun handle(text: String) = mutex.withLock {
        val history = store.messages.value
        store.append(CoachMessage(UUID.randomUUID().toString(), clock.millis(), Speaker.OWNER, text, null))
        busy.value = true
        try {
            val (reply, kind) = try {
                baselineAnswer(text) ?: respond(text, history)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.javaClass.simpleName)
                CoachReplies.CHAT_DOWN to "error"
            }
            store.append(CoachMessage(UUID.randomUUID().toString(), clock.millis(), Speaker.COACH, reply, kind, roleFor(kind)))
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
            if (named.size == 1 && CoachReplies.plainReport(text, table)) {
                parsed = Parsed("meal", listOf(ParsedItem(named[0].name, 1.0, false, null, null, null, null)))
            } else {
                return CoachReplies.cannotReach(fail) to "error"
            }
        }
        if (parsed == null) return CoachReplies.NOT_UNDERSTOOD to "error"
        // The kind is not left to the model alone: 「想吃…」 without 「吃了」 is a craving, whatever it says.
        if (parsed.kind == "meal" && CoachReplies.isCraving(text)) parsed = parsed.copy(kind = "craving")
        // A training report is not left to the model either: 「肌力三輪做完了」 is training whatever it says.
        // Only a message the model calls talk becomes a training report; a meal or weight that also reports training runs both.
        val alsoTraining = isTrainingReport(text)
        if (parsed.kind == "chat" && alsoTraining) parsed = parsed.copy(kind = "training")
        // 「訓練還沒做完」「等等要去訓練」: not a report of training done, whatever the model says.
        if (parsed.kind == "training" && !alsoTraining) parsed = parsed.copy(kind = "chat")
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
                if (parsed.items.isEmpty() && parsed.weightKg == null && !alsoTraining) return CoachReplies.NOT_UNDERSTOOD to "meal"
                if (parsed.items.isNotEmpty()) lines += meal(text, parsed.items, illness)
                parsed.weightKg?.let { lines += weight(it, parsed.waistCm) }
                if (alsoTraining) lines += training(CoachNumbers.ownersOnly(parsed.rounds?.toDouble(), text)?.toInt() ?: roundsIn(text))
            }
            "weight" -> {
                val kg = parsed.weightKg ?: return "沒聽到體重。說「今天體重 79.5」。" to "weight"
                lines += weight(kg, parsed.waistCm)
                if (parsed.items.isNotEmpty()) lines += meal(text, parsed.items, illness)
                if (alsoTraining) lines += training(CoachNumbers.ownersOnly(parsed.rounds?.toDouble(), text)?.toInt() ?: roundsIn(text))
            }
            "craving" -> {
                if (illness) return chat(text, history, illness, lowIntake, null, AgencyRole.BODY) to "craving"
                lines += craving(parsed.items)
            }
            "food_info" -> lines += foodInfo(parsed.foodInfo, illness)
            "training" -> lines += training(CoachNumbers.ownersOnly(parsed.rounds?.toDouble(), text)?.toInt() ?: roundsIn(text))
            else -> return chat(text, history, illness, lowIntake, null, AgencyRole.AGENT) to "chat"
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
            val zero = AgencyRules.isZeroCal(it0.name, it0.zeroCal)
            when {
                entry != null -> counted += FoodTable.tableItem(entry, it0.name, it0.portion, it0.fried).copy(zeroCal = zero)
                it0.kcalHigh != null -> {
                    val item = FoodTable.estimateItem(it0.name, it0.portion, it0.fried, it0.kcalHigh, it0.proteinLow, p.marginPct).copy(zeroCal = zero)
                    pending += PendingFood(mealId, counted.size, item.name, item.kcal, now)
                    counted += item
                }
                // A zero-calorie drink needs no estimate: it is 0 kcal and still counts toward the weekly three.
                zero -> counted += MealItem(it0.name, it0.portion, 0, 0.0, 0.0, 0.0, fried = false, source = KcalSource.ESTIMATE, zeroCal = true)
                else -> lines += CoachReplies.noEstimate(it0.name)
            }
        }
        if (counted.isEmpty()) return lines.ifEmpty { listOf(CoachReplies.NOT_UNDERSTOOD) }
        val fried = counted.any { it.fried }
        // 公司規章: fried only at the 休假餐, a single meal at most 700 kcal, zero-calorie drinks at most three a week.
        val zeroBefore = AgencyRules.zeroCalThisWeek(store.state.value.meals, today)
        val zeroAfter = zeroBefore + counted.filter { it.zeroCal }.sumOf { kotlin.math.ceil(it.portion - 1e-9).toInt() }
        val breaches = buildList {
            if (fried && !cheat) add(Breach.FRIED)
            // One sitting may be reported in parts: meals within [AgencyRules.SITTING_MINUTES] count together.
            val sitting = store.state.value.meals.filter { it.day == today.toString() && now - it.at in 0..AgencyRules.SITTING_MINUTES * 60_000L }
            val alreadyFlagged = sitting.any { Breach.OVER700.name in it.breaches }
            if (!alreadyFlagged && sitting.sumOf { it.kcal } + counted.sumOf { it.kcal } > AgencyRules.MEAL_MAX_KCAL) add(Breach.OVER700)
            if (zeroAfter > AgencyRules.ZERO_CAL_PER_WEEK && zeroAfter > zeroBefore) add(Breach.ZEROCAL)
        }
        val m = Meal(mealId, now, today.toString(), text.take(200), counted, cheat = cheat, offPlan = breaches.isNotEmpty(), breaches = breaches.map { it.name })
        val cutoff = now - PENDING_DAYS * 86_400_000L
        val s = store.update { it.copy(meals = it.meals + m, pending = it.pending.filter { p0 -> p0.askedAt >= cutoff } + pending) }
            ?: return listOf(CoachReplies.NOT_SAVED)
        counted.forEach { lines += CoachReplies.itemLine(it, p.marginPct) }
        breaches.forEach { lines += AgencyRules.breachLine(it, zeroAfter) }
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
        val zeroWeek = AgencyRules.zeroCalThisWeek(s.meals, today)
        if (items.any { AgencyRules.isZeroCal(it.name, it.zeroCal) } && zeroWeek >= AgencyRules.ZERO_CAL_PER_WEEK) {
            return listOf("零卡飲料這週已經喝了 $zeroWeek 瓶，上限 ${AgencyRules.ZERO_CAL_PER_WEEK} 瓶，不准再喝。")
        }
        val friedNames = items.filter { FoodTable.isFried(it.name, it.fried, FoodTable.lookup(s.food, it.name)) }.map { it.name }
        val deadline = "${p.evidenceMinutes} 分鐘內把訂單截圖傳來。"
        if (friedNames.isNotEmpty() && !cheat) {
            val planned = FoodTable.lookup(s.food, p.plannedMeal)
            val word = if (s.meals.none { it.day == today.toString() }) "晚餐" else "宵夜"
            val eatLine = when {
                remaining < 0 -> "今天已經超過，不准再點。"
                remaining == 0 -> "今天剩 0 大卡，不准再點。"
                // A planned meal missing from the table is never named: its kcal cannot be checked.
                planned == null -> noPlanLine(eaten, remaining)
                planned.kcal > remaining -> "今天剩 $remaining 大卡，不准再點炸的。" + floorLine(eaten, remaining, word, p, planned)
                else -> "${word}吃${p.plannedMeal.replace(" ", "")}。"
            }
            return listOf("今天不是休假餐。$eatLine${friedNames.joinToString("、")}排到星期${CoachRules.weekdayZh(p.cheatDay)}。$deadline")
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
            kcal > remaining -> listOf("這份大約 $kcal 大卡，今天剩 $remaining 大卡，不准再點。" + floorLine(eaten, remaining, word, p, FoodTable.lookup(s.food, p.plannedMeal)))
            // 一次不要吃到撐: a single meal above 700 kcal is refused even when it fits what is left (休假餐 included).
            kcal > AgencyRules.MEAL_MAX_KCAL -> listOf(
                "這份大約 $kcal 大卡，單餐不准超過 ${AgencyRules.MEAL_MAX_KCAL} 大卡。" +
                    (floorLine(eaten, minOf(remaining, AgencyRules.MEAL_MAX_KCAL), word, p, FoodTable.lookup(s.food, p.plannedMeal)).ifEmpty { menuLine(remaining) }),
            )
            else -> listOf("可以。吃了今天會剩 ${remaining - kcal} 大卡。吃完回報，$deadline")
        }
    }

    /** Spec §4: never below 1200. A refusal still says how much he must eat, and what. */
    private fun floorLine(eaten: Int, remaining: Int, word: String, p: CoachProfile, planned: FoodEntry?): String {
        val must = CoachRules.mustStillEat(eaten)
        if (must <= 0) return ""
        // Never name a meal that would push him over: the planned meal only if it fits what is left.
        val what = if (planned != null && planned.kcal <= remaining) "${word}吃${p.plannedMeal.replace(" ", "")}。" else menuLine(remaining)
        return "今天至少還要吃 $must 大卡。$what"
    }

    /** No options (spec §5): when no known meal fits, the owner sends the menu and the coach picks. */
    private fun menuLine(remaining: Int) = "今天剩 $remaining 大卡。點之前先把菜單傳來，我來選。"

    private fun noPlanLine(eaten: Int, remaining: Int): String {
        val must = CoachRules.mustStillEat(eaten)
        return (if (must > 0) "今天至少還要吃 $must 大卡。" else "") + menuLine(remaining)
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

    private suspend fun chat(text: String, history: List<CoachMessage>, illness: Boolean, lowIntake: Boolean, already: String?, role: AgencyRole): String {
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
                zeroCalWeek = AgencyRules.zeroCalThisWeek(s.meals, today), violations = AgencyRules.violationsOn(s.meals, today.toString()),
                schedule = scheduleText(s, today),
            ),
        )
        val turns = CoachPrompt.history(history) + CoachPrompt.finalTurn(facts, text)
        var reply = when (val r = model.complete(CoachPrompt.system(role, settings.companyName), turns)) {
            is ModelReply.Ok -> OutputGuard.legacyFilter(r.text, facts, text).ifBlank { CoachReplies.CHAT_FALLBACK }
            is ModelReply.Failed -> CoachReplies.CHAT_DOWN
        }
        if ((illness || lowIntake) && !reply.contains("看醫生")) reply += "\n" + CoachReplies.DOCTOR
        return reply
    }

    // ---- agency: roles, training, schedule, 入社建檔 ----

    private fun roleFor(kind: String?): AgencyRole = AgencyRole.forKind(kind)

    private fun isTrainingReport(text: String): Boolean =
        TRAINING_WORDS.any { text.contains(it) } && DONE_WORDS.any { text.contains(it) } && !NOT_DONE.containsMatchIn(text) &&
            !CoachReplies.isCraving(text)

    /** 「肌力三輪做完了」: recorded; [rounds] only when the owner wrote the number. No exercise is ever suggested. */
    private fun training(rounds: Int?): List<String> {
        val now = clock.millis()
        store.update { it.copy(trainings = it.trainings + TrainingEntry(UUID.randomUUID().toString(), now, today().toString(), rounds)) }
            ?: return listOf(CoachReplies.NOT_SAVED)
        val lines = mutableListOf(if (rounds != null) "記下：肌力 $rounds 輪。" else "記下：肌力訓練做完了。")
        if (rounds != null && rounds < AgencyRules.PLANNED_ROUNDS) lines += "計畫是 ${AgencyRules.PLANNED_ROUNDS} 輪，今天沒做完。"
        else lines += "今天的肌力訓練完成。"
        lines += "先記「未證實」。"
        return lines
    }

    /** 「三輪」「3 輪」 in the owner's own words. */
    private fun roundsIn(text: String): Int? =
        Regex("([0-9零一二兩三四五六七八九十]+)\\s*輪").find(text)?.let { CoachNumbers.all(it.groupValues[1]).firstOrNull()?.toInt() }?.takeIf { it in 1..20 }

    private fun scheduleText(s: CoachState, day: LocalDate): String =
        AgencySchedule.ticked(day, settings.slotTimes, s.meals, s.trainings, zone, settings.profile.dayStartHour).joinToString("、") { sl ->
            (sl.minute?.let { AgencySchedule.clock(it) + " " } ?: "") + sl.label + if (sl.done) " 已完成" else " 未完成"
        }

    /** Where 入社建檔's photo and recording are kept (the app's own files; never sent to the model). */
    val baselineDir: java.io.File get() = store.baselineDir

    /** On the first visit with no 入社建檔 yet, the 經紀人 welcomes him and asks the first question. */
    fun startOnboarding() {
        if (store.state.value.baseline != null) return
        scope.launch {
            mutex.withLock {
                if (store.state.value.baseline != null) return@withLock
                val step = BaselineStep.BODY
                store.update { it.copy(baseline = Baseline(step = step)) } ?: return@withLock
                agentSays(welcome() + "\n" + question(step))
            }
        }
    }

    /** 補交入社資料: reopens the first missing step. */
    fun reopenBaseline() {
        scope.launch {
            mutex.withLock {
                val b = store.state.value.baseline ?: Baseline()
                val step = b.missing.firstOrNull() ?: return@withLock
                // Every missing item is asked again, the ones skipped before included.
                store.update { it.copy(baseline = b.copy(step = step, skipped = emptySet())) } ?: return@withLock
                agentSays(question(step))
            }
        }
    }

    /** A photo or recording already copied into [baselineDir] as [fileName]. */
    fun onBaselineFile(step: BaselineStep, fileName: String) {
        if (step != BaselineStep.PHOTO && step != BaselineStep.RECORDING) return
        scope.launch {
            mutex.withLock {
                val b = store.state.value.baseline ?: Baseline()
                val got = if (step == BaselineStep.PHOTO) b.copy(photo = fileName) else b.copy(recording = fileName)
                val saved = if (step == BaselineStep.PHOTO) "記下：全身照收到了。" else "記下：清唱錄音收到了。"
                if (b.step == step) {
                    agentSays(advance(got, saved))
                } else {
                    // Handed in while another question is open (or none): saved, and the open question stays as it is.
                    if (store.update { it.copy(baseline = got) } == null) agentSays(CoachReplies.NOT_SAVED) else agentSays(saved)
                }
            }
        }
    }

    private fun agentSays(text: String) {
        store.append(CoachMessage(UUID.randomUUID().toString(), clock.millis(), Speaker.COACH, text, "baseline", AgencyRole.AGENT))
    }

    private fun welcome(): String {
        val days = AgencyRules.dayNumber(settings.joinDate, today()).coerceAtLeast(1)
        return "公司成立了：\n歡迎加入${settings.companyName}。你是公司唯一的練習生，編號 ${AgencyRules.TRAINEE_NO}，等級${AgencyRules.LEVEL}，今天是入社第 $days 天。" +
            "入社第一件事：建檔。這是以後每次評鑑的比較基準。"
    }

    private fun question(step: BaselineStep): String {
        val p = settings.profile
        return when (step) {
            BaselineStep.BODY -> "先確認身高 ${CoachRules.kg(p.heightCm)} 公分、體重 ${CoachRules.kg(store.state.value.weights.maxByOrNull { it.at }?.kg ?: p.startWeightKg)} 公斤。對就回「對」，不對就打「身高 168 體重 79.5」。現在不做就回「跳過」。"
            BaselineStep.PHOTO -> "交一張全身照：站直、正面，手機放在固定的位置，以後每個月都在同一個位置拍。按練習生證上的「交全身照」。現在不做就回「跳過」。"
            BaselineStep.RECORDING -> "交一段清唱錄音。按練習生證上的「交清唱錄音」。現在不做就回「跳過」。"
            BaselineStep.PUSHUPS -> "體能測驗第一項：伏地挺身，做到做不動為止。做完打「伏地挺身 幾下」。現在不做就回「跳過」。"
            BaselineStep.PLANK -> "體能測驗第二項：棒式，撐到撐不住為止。做完打「棒式 幾秒」。現在不做就回「跳過」。"
        }
    }

    /** Saves [b], moves to the next step still missing and not skipped, and returns the 經紀人's reply. */
    private fun advance(b: Baseline, saved: String?): String {
        val after = b.step?.let { cur -> BaselineStep.entries.dropWhile { it != cur }.drop(1) } ?: BaselineStep.entries
        val next = after.firstOrNull { !b.has(it) && it !in b.skipped }
        store.update { it.copy(baseline = b.copy(step = next)) } ?: return CoachReplies.NOT_SAVED
        val lines = mutableListOf<String>()
        saved?.let { lines += it }
        if (next != null) {
            lines += question(next)
        } else {
            val missing = b.missing
            lines += if (missing.isEmpty()) "入社建檔完成。從今天開始照公司規章走。"
            else "入社建檔先到這裡，還缺：${missing.joinToString("、") { BASELINE_ZH.getValue(it) }}。之後按練習生證上的「補交入社資料」。"
        }
        return lines.joinToString("\n")
    }

    /**
     * While a 入社建檔 question is open, the owner's message is first read as its answer (confirm, numbers, or 跳過). Null when it
     * is not an answer: the message then goes through the normal flow and the question stays open.
     */
    private fun baselineAnswer(text: String): Pair<String, String>? {
        val b = store.state.value.baseline ?: return null
        val step = b.step ?: return null
        // Strict, per step: anything that is not plainly an answer goes through the normal flow and the question stays open.
        val t = text.trim().trimEnd('。', '！', '!', '.', '～', '~').trim()
        if (t in SKIP_PHRASES) {
            return advance(b.copy(skipped = b.skipped + step), "好，${BASELINE_ZH.getValue(step)}之後補交。") to "baseline"
        }
        return when (step) {
            BaselineStep.BODY -> {
                val p = settings.profile
                val latest = store.state.value.weights.maxByOrNull { it.at }?.kg
                val (h, w) = if (t in CONFIRM_PHRASES) p.heightCm to (latest ?: p.startWeightKg) else BaselineAnswers.body(t) ?: return null
                val height = h ?: p.heightCm
                val weight = w ?: latest ?: p.startWeightKg
                settings.profile = p.copy(heightCm = height)
                // A weight he typed is a new reading; a confirm (or the same number again) adds nothing.
                if (w != null && (latest == null || kotlin.math.abs(latest - w) > 1e-9)) {
                    val now = clock.millis()
                    store.update { it.copy(weights = it.weights + WeightEntry(UUID.randomUUID().toString(), now, today().toString(), w, null)) }
                        ?: return CoachReplies.NOT_SAVED to "baseline"
                }
                advance(b.copy(heightCm = height, weightKg = weight), "記下：身高 ${CoachRules.kg(height)} 公分、體重 ${CoachRules.kg(weight)} 公斤。") to "baseline"
            }
            BaselineStep.PUSHUPS -> {
                val n = BaselineAnswers.pushups(t) ?: return null
                advance(b.copy(pushups = n), "記下：伏地挺身 $n 下。") to "baseline"
            }
            BaselineStep.PLANK -> {
                val sec = BaselineAnswers.plank(t) ?: return null
                advance(b.copy(plankSec = sec), "記下：棒式 $sec 秒。") to "baseline"
            }
            // Photo and recording come through the card's buttons; text can only skip them.
            BaselineStep.PHOTO, BaselineStep.RECORDING -> null
        }
    }

    companion object {
        private val TRAINING_WORDS = listOf("肌力", "訓練", "伏地挺身", "棒式", "深蹲")
        private val DONE_WORDS = listOf("做完", "練完", "做了", "練了", "完成")
        private val SKIP_PHRASES = setOf("跳過", "先跳過", "之後補", "之後再補", "晚點補", "下次補", "跳過這個", "先不要")
        private val CONFIRM_PHRASES = setOf("對", "對的", "沒錯", "確認", "是", "是的", "正確")
        /** Negations of the action itself only (「累到不行」「沒問題」 are not). */
        private val NOT_DONE = Regex("還沒(做|練)|沒(有)?(做|練)|不(做|練)|做(了)?一半|練(了)?一半")
        val BASELINE_ZH = mapOf(
            BaselineStep.BODY to "身高體重", BaselineStep.PHOTO to "全身照", BaselineStep.RECORDING to "清唱錄音",
            BaselineStep.PUSHUPS to "伏地挺身", BaselineStep.PLANK to "棒式",
        )
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
