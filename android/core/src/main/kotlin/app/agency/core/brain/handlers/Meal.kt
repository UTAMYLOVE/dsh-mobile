package app.agency.core.brain.handlers

import app.agency.core.brain.SnapshotReader
import app.agency.core.engine.IdolCard
import app.agency.core.engine.Links
import app.agency.core.engine.Signal
import app.agency.core.model.FoodEntry
import app.agency.core.model.KcalSource
import app.agency.core.model.Meal
import app.agency.core.model.MealItem
import app.agency.core.model.Media
import app.agency.core.model.OrderStatus
import app.agency.core.model.PendingLabel
import app.agency.core.rules.Breaches
import app.agency.core.rules.ProteinRotation
import app.agency.core.rules.FoodTable
import app.agency.core.rules.OverQuota
import app.agency.core.rules.RulesBook
import app.agency.core.schedule.SlotKey
import app.agency.core.store.AgencyStore
import app.agency.core.structured.IntakeItem
import app.agency.core.time.AgencyClock
import kotlin.math.ceil

/** What a meal handler did: the record (if one was written), the lines he reads (code's), the signals for the nerve. */
data class MealOutcome(val meal: Meal?, val lines: List<String>, val signals: List<Signal> = emptyList())

/**
 * Meal reports (arch 1.5 meal rules, T14), code only: the food table first (his stored entries, then plan v3's), else the
 * strict estimate (the clerk's high end × portion + the margin, rounded up, 估算, and a label asked for), breaches by
 * [Breaches], the slot by [MealSlots], 「未證實」 until a photo (T15), and the day's numbers ([OverQuota.dayStatus]). A photo
 * may come first (「拍這餐」): it waits as a meal without items, and the words he sends next fill it.
 */
class MealDesk(
    private val store: AgencyStore,
    private val snapshot: SnapshotReader,
    private val clock: AgencyClock,
    private val newId: () -> String,
) {
    /** The table a lookup reads: his stored entries, then plan v3's for what he has not stored. */
    suspend fun table(): List<FoodEntry> = FoodTable.withCurrent(store.food.all())

    /** A meal he reported in words ([said] are the clerk's items); [ill] today (the four lines give way then). */
    suspend fun record(text: String, said: List<IntakeItem>, ill: Boolean): MealOutcome {
        val profile = snapshot.profile()
        val rules = snapshot.rules()
        val now = clock.millis()
        val day = snapshot.today(profile)
        val d = day.toString()
        val plan = snapshot.plan(day)
        val table = table()
        val today = store.meals.onDay(d)
        // A photo he took first waits for these words (at most [PHOTO_WAIT_MS]).
        val waiting = today.filter { it.items.isEmpty() && it.photoMediaId != null && now - it.at in 0..PHOTO_WAIT_MS }.maxByOrNull { it.at }
        val others = today.filter { it.id != waiting?.id }
        val id = waiting?.id ?: newId()
        val at = waiting?.at ?: now
        val (named, namedSlot) = MealSlots.named(plan, text)
        val slot = when {
            named -> namedSlot
            waiting != null -> SlotKey.parse(waiting.slotKey)
            else -> MealSlots.pick(plan, text, Links.minuteOf(day, now, clock.zone), others, now)
        }
        val lines = mutableListOf<String>()
        val counted = mutableListOf<MealItem>()
        val pending = mutableListOf<PendingLabel>()
        said.forEach { s ->
            val entry = FoodTable.lookup(table, s.name)
            val zero = Breaches.isZeroCal(s.name, s.zeroCal)
            val item = when {
                entry != null -> FoodTable.tableItem(entry, s.name, s.portion, s.fried).copy(zeroCal = zero)
                // A zero-calorie drink needs no estimate: 0 kcal, and it counts toward the weekly limit.
                zero && (s.kcalHigh ?: 0) <= ZERO_KCAL_MAX -> MealItem(s.name, s.portion, 0, 0.0, 0.0, 0.0, fried = false, source = KcalSource.ESTIMATE, zeroCal = true)
                s.kcalHigh != null -> FoodTable.estimateItem(s.name, s.portion, s.fried, s.kcalHigh, s.proteinLow, rules.marginPct).copy(zeroCal = zero)
                    .also { pending += PendingLabel(id, counted.size, it.name, it.kcal, now) }
                else -> null.also { lines += "${s.name}估不出來，沒有算進去。把包裝標示或菜單上的數字打給我。" }
            } ?: return@forEach
            counted += item.copy(processed = Breaches.categories(item, rules, cheat = false).isNotEmpty())
        }
        if (counted.isEmpty()) return MealOutcome(null, lines.ifEmpty { listOf(NOT_UNDERSTOOD) })
        val cheat = slot != null && slot == plan.cheatSlot && RulesBook.cheatOpen(day)
        val week = store.meals.between(Breaches.weekStart(day).toString(), d).filter { it.id != waiting?.id }
        val check = Breaches.check(counted, rules, at, others, Breaches.zeroCalInWeek(week, day), cheat, onRun = slot == SlotKey.RIDE_MEAL)
        val order = slot?.let { k -> store.orders.onDay(d).lastOrNull { it.slotKey == k.name && it.status == OrderStatus.CONFIRMED } }
        val meal = Meal(
            id, d, at, slot?.name, text.take(MAX_TEXT), counted, cheat = cheat, breaches = check.breaches.map { it.name },
            verified = waiting?.verified ?: false, photoMediaId = waiting?.photoMediaId, orderId = order?.id,
        )
        store.transaction {
            store.meals.upsert(meal)
            pending.forEach { store.meals.putPending(it) }
            order?.let { store.orders.setStatus(it.id, OrderStatus.EATEN) }
        }
        val label = slot?.let { k -> plan.slot(k)?.label ?: k.zh }
        lines.add(0, if (label != null) "記在$label。" else "記成加餐。")
        counted.forEachIndexed { i, it -> lines.add(1 + i, MealWords.itemLine(it, rules.marginPct)) }
        if (cheat) lines += "這是你宣告的休假餐，禁止的東西這餐不算違規；熱量照算。"
        check.breaches.forEach { lines += Breaches.line(it, check, rules) }
        // 雞胸 one pack a day, the 21:00 protein rotated, 福樂 a few times a week (notes, not breaches).
        val back = store.meals.between(day.minusDays(ProteinRotation.ROTATE_AFTER_DAYS.toLong()).toString(), day.minusDays(1).toString())
            .groupBy { java.time.LocalDate.parse(it.day) }
        lines += ProteinRotation.notes(meal, others + meal, week + meal, back, day)
        // 20:30 之後不喝咖啡和茶 (owner 2026-11-26; its rollout week): a note, not a breach.
        if (snapshot.plans.specs().open(app.agency.core.schedule.Feature.CAFFEINE) && Links.minuteOf(day, at, clock.zone) >= app.agency.core.engine.Links.L29Caffeine.CUTOFF &&
            counted.any { CAFFEINE.containsMatchIn(it.name) }
        ) {
            lines += app.agency.core.engine.Links.L29Caffeine.LINE
        }
        lines += if (meal.verified) "照片有了，這餐已證實。" else UNVERIFIED
        pending.forEach { lines += askLabel(it.name) }
        lines += status(meal.day, ill)
        val protein = counted.sumOf { it.proteinG ?: 0.0 }
        val signals = buildList {
            add(Signal.MealLogged("meal:$id", now, slot, meal.kcal, protein, meal.breaches, cheat))
            MealWords.price(text)?.let { add(Signal.SpendLogged("spend:$id", now, it, image = false)) }
        }
        return MealOutcome(meal, lines, signals)
    }

    /** The day's numbers (and the doctor line after two reported days under the floor), for [day] when it is today. */
    suspend fun status(day: String, ill: Boolean): List<String> {
        val profile = snapshot.profile()
        val today = snapshot.today(profile)
        if (day != today.toString()) return emptyList()
        val rules = snapshot.rules()
        val meals = store.meals.onDay(day)
        val eaten = meals.sumOf { it.kcal }
        val protein = meals.sumOf { m -> m.items.sumOf { it.proteinG ?: 0.0 } }
        val kg = snapshot.currentWeightKg(today, profile)
        val height = profile.heightCm ?: store.baselines.get(app.agency.core.model.Baseline.JOIN)?.heightCm
        val out = OverQuota.dayStatus(rules, eaten, protein, IdolCard.nextGoal(rules, kg), kg, height, ill).toMutableList()
        val last = snapshot.summaries(today, LOW_DAYS)
        if (ill || last.all { it.reported && it.kcal < rules.floorKcal }) out += OverQuota.DOCTOR
        return out
    }

    /**
     * 拍這餐 (T15): the sealed photo goes on today's latest meal without one (reported in the last [PHOTO_ATTACH_MS]); with none,
     * it waits as a meal of the slot it was taken for, until he says what it was. [media] is written here, with the link.
     */
    suspend fun attachPhoto(media: Media): MealOutcome {
        val profile = snapshot.profile()
        val now = clock.millis()
        val day = snapshot.today(profile)
        val d = day.toString()
        val plan = snapshot.plan(day)
        val today = store.meals.onDay(d)
        val target = today.filter { it.photoMediaId == null && now - it.at in 0..PHOTO_ATTACH_MS }.maxByOrNull { it.at }
        if (target != null) {
            val m = target.copy(verified = true, photoMediaId = media.id)
            store.transaction {
                store.media.insert(media)
                store.meals.upsert(m)
            }
            val label = SlotKey.parse(m.slotKey)?.let { plan.slot(it)?.label ?: it.zh } ?: "這份加餐"
            return MealOutcome(m, listOf("照片收到，${label}已證實。"))
        }
        val slot = MealSlots.pick(plan, "", Links.minuteOf(day, now, clock.zone), today, now)
        val m = Meal(newId(), d, now, slot?.name, PHOTO_ONLY_TEXT, verified = true, photoMediaId = media.id)
        store.transaction {
            store.media.insert(media)
            store.meals.upsert(m)
        }
        val label = slot?.let { plan.slot(it)?.label ?: it.zh } ?: "加餐"
        return MealOutcome(m, listOf("照片收到，記在$label。吃了什麼、幾份打給我，熱量才算得出來。"))
    }

    /**
     * 刪掉照片 (after his confirm): the meal goes back to 「未證實」 (a meal that was only the photo goes with it). Returns the
     * media id whose file the app deletes, or null when there was none.
     */
    suspend fun deletePhoto(mealId: String): String? {
        val m = store.meals.get(mealId) ?: return null
        val media = m.photoMediaId ?: return null
        store.transaction {
            if (m.items.isEmpty()) store.meals.delete(m.id) else store.meals.upsert(m.copy(verified = false, photoMediaId = null))
            store.media.delete(media)
        }
        return media
    }

    /**
     * Numbers from a package label or a menu (typed, or from the label form, T15): they replace the estimate of the item
     * waiting for them (the one named, else the latest; [target] is the form's meal and item), the food table keeps them
     * (標示), and today's numbers are said again. With nothing waiting, a named food goes into the table as it is.
     */
    suspend fun label(name: String?, kcal: Int?, proteinG: Double?, target: Pair<String, Int>? = null): MealOutcome {
        if (kcal == null || kcal !in LABEL_KCAL) return MealOutcome(null, listOf(LABEL_UNCLEAR))
        if (proteinG != null && proteinG !in LABEL_PROTEIN) return MealOutcome(null, listOf(LABEL_UNCLEAR))
        val now = clock.millis()
        val live = store.meals.pending().filter { it.askedAt >= now - PENDING_KEEP_MS }
        val p = when {
            target != null -> live.firstOrNull { it.mealId == target.first && it.itemIdx == target.second }
            name != null -> live.lastOrNull { FoodTable.normalize(it.name) == FoodTable.normalize(name) }
            else -> live.maxByOrNull { it.askedAt }
        }
        val meal = p?.let { store.meals.get(it.mealId) }
        val item = p?.let { meal?.items?.getOrNull(it.itemIdx) }?.takeIf { FoodTable.normalize(it.name) == FoodTable.normalize(p.name) }
        val entryName = item?.name ?: name?.trim()?.takeIf { it.isNotEmpty() } ?: return MealOutcome(null, listOf(NO_PENDING))
        val old = store.food.all().firstOrNull { FoodTable.normalize(it.name) == FoodTable.normalize(entryName) }
        val entry = FoodEntry(
            entryName, old?.aliases.orEmpty(), kcal, proteinG, null, null, fried = item?.fried ?: old?.fried ?: false, estimated = false, seeded = false,
            addedAt = old?.addedAt ?: now, processed = item?.processed ?: old?.processed ?: false, updatedAt = now,
        )
        val proteinText = proteinG?.let { "、蛋白質 ${MealWords.portion(it)} 克" }.orEmpty()
        val lines = mutableListOf("存進食物表：$entryName $kcal 大卡$proteinText（標示）。")
        val signals = mutableListOf<Signal>()
        store.transaction {
            old?.takeIf { it.name != entryName }?.let { store.food.delete(it.name) }
            store.food.upsert(entry)
            if (p != null) store.meals.deletePending(p.mealId, p.itemIdx)
            if (meal != null && item != null) {
                val fixed = item.copy(
                    kcal = ceil(kcal * item.portion - 1e-9).toInt(), proteinG = proteinG?.let { it * item.portion } ?: item.proteinG,
                    source = KcalSource.LABEL, tableEstimated = false, estimateHigh = null,
                )
                store.meals.upsert(meal.copy(items = meal.items.mapIndexed { i, it -> if (i == p.itemIdx) fixed else it }))
                // The day state counts meals from their signals: the difference goes in as one more.
                signals += Signal.MealLogged("label:${meal.id}:${p.itemIdx}:$now", now, null, fixed.kcal - item.kcal, (fixed.proteinG ?: 0.0) - (item.proteinG ?: 0.0))
                lines += "${MealWords.amount(item.name, item.portion)}改成 ${fixed.kcal} 大卡（原本估算 ${item.kcal}）。"
            }
        }
        if (meal != null) lines += status(meal.day, ill = false)
        return MealOutcome(meal, lines, signals)
    }

    /** The items of today still waiting for their label (the card and the label form list them). */
    suspend fun pendingToday(): List<PendingLabel> {
        val d = snapshot.today(snapshot.profile()).toString()
        val now = clock.millis()
        val ids = store.meals.onDay(d).map { it.id }.toSet()
        return store.meals.pending().filter { it.mealId in ids && it.askedAt >= now - PENDING_KEEP_MS }
    }

    companion object {
        const val MAX_TEXT = 200
        const val ZERO_KCAL_MAX = 20
        const val PHOTO_WAIT_MS = 3L * 3_600_000
        const val PHOTO_ATTACH_MS = 3L * 3_600_000
        const val PENDING_KEEP_MS = 7L * 86_400_000
        const val LOW_DAYS = 2
        val LABEL_KCAL = 1..5000
        val LABEL_PROTEIN = 0.0..500.0
        const val PHOTO_ONLY_TEXT = "（照片）"
        const val NOT_UNDERSTOOD = "沒聽懂。說清楚吃了什麼、幾份。"
        const val UNVERIFIED = "這餐先記「未證實」，按「拍這餐」補照片。"
        const val NO_PENDING = "沒有等標示的食物。說清楚是哪一樣、標示寫幾大卡。"
        const val LABEL_UNCLEAR = "說清楚標示寫幾大卡，例如「雞胸 標示 120 大卡 蛋白質 24 克」。"

        /** Coffee and tea (茶葉蛋 is an egg; 無咖啡因 has none). */
        val CAFFEINE = Regex("咖啡(?!因)|拿鐵|美式|(?<!葉)茶(?!葉蛋)")

        fun askLabel(name: String) = "${name}不在食物表，先照估算算。有包裝標示或菜單上的數字就打給我，確認了才存進表。"
    }
}
