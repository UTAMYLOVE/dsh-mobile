package app.agency.core.brain

import app.agency.core.engine.Effect
import app.agency.core.engine.Nerve
import app.agency.core.model.FavoritePlace
import app.agency.core.model.MealOrder
import app.agency.core.model.OrderStatus
import app.agency.core.model.PlanSectionKey
import app.agency.core.model.Proposal
import app.agency.core.model.ProposalKind
import app.agency.core.model.ProposalStatus
import app.agency.core.rules.CoachRules
import app.agency.core.schedule.PlanChange
import app.agency.core.schedule.PlanChanges
import app.agency.core.schedule.PlanStore
import app.agency.core.schedule.SlotKey
import app.agency.core.store.AgencyStore
import app.agency.core.time.AgencyClock
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One item of a MEAL_ORDER proposal: what, how much, and its kcal from the food table (code). */
@Serializable
data class OrderItem(val name: String, val portion: Double, val kcal: Int, val proteinG: Double? = null, val estimated: Boolean = false)

/** The diff of a MEAL_ORDER proposal. */
@Serializable
data class OrderDiff(
    val slot: String,
    val items: List<OrderItem>,
    val kcal: Int,
    val proteinG: Double?,
    val placeResultId: String? = null,
    /** The search result's title (T16), for the card. */
    val placeTitle: String? = null,
)

/** The diff of a SAVE_PLACE proposal. */
@Serializable
data class PlaceDiff(val resultId: String, val title: String, val note: String)

/** One line of a 差異卡: [label] [before] → [after] ([before] null: something new). */
data class DiffLine(val label: String, val before: String?, val after: String)

/** What a proposal card shows (computed by code; the screen only draws it). */
data class ProposalView(val id: String, val kind: ProposalKind, val title: String, val lines: List<DiffLine>, val reason: String)

/**
 * The proposals of the one proposal table (arch T12 `ProposalApplier`; the links' proposals live in the same table, decided by
 * the nerve): nothing is applied before his yes; on his yes it is checked again against the plan as it is now and applied by
 * code; 「不要」 changes nothing; one left unanswered lapses when its coach day is over (the links' own rules for theirs).
 * A plan change writes the next version of its section (the history keeps every version) with the proposal's id as
 * `changed_by`.
 */
class ProposalApplier(
    private val store: AgencyStore,
    private val clock: AgencyClock,
    private val newId: () -> String,
) {
    private val plans = PlanStore(store)

    private suspend fun today(): LocalDate =
        CoachRules.coachDay(clock.now(), clock.zone, store.profile.get()?.dayStartHour ?: 5)

    private suspend fun dayOf(at: Long): LocalDate =
        CoachRules.coachDay(Instant.ofEpochMilli(at), clock.zone, store.profile.get()?.dayStartHour ?: 5)

    /** Pending proposals of an earlier coach day lapse (only the kinds this class owns; the nerve expires its own). */
    suspend fun expire() {
        val today = today()
        store.proposals.withStatus(ProposalStatus.PENDING).filter { it.kind !in NERVE_KINDS && dayOf(it.createdAt) < today }
            .forEach { store.proposals.upsert(it.copy(status = ProposalStatus.EXPIRED, decidedAt = clock.millis())) }
    }

    /** His answer to [p] (one of this class's kinds); the line the manager says, by code. */
    suspend fun decide(p: Proposal, accept: Boolean): String = store.transaction {
        val now = clock.millis()
        if (!accept) {
            store.proposals.upsert(p.copy(status = ProposalStatus.REJECTED, decidedAt = now))
            return@transaction "好，不改，照原本的。"
        }
        when (p.kind) {
            ProposalKind.PLAN_CHANGE -> planChange(p, now)
            ProposalKind.MEAL_ORDER -> {
                val d = JSON.decodeFromString(OrderDiff.serializer(), p.diffJson)
                store.orders.insert(
                    MealOrder(p.id, today().toString(), now, d.slot, d.items.map { it.name }, d.kcal, d.proteinG, d.placeResultId, OrderStatus.CONFIRMED),
                )
                store.proposals.upsert(p.copy(status = ProposalStatus.ACCEPTED, decidedAt = now))
                "好，這餐就點這個：${d.items.joinToString("、") { it.name }}，${d.kcal} 大卡。吃完拍照回報。"
            }
            ProposalKind.SAVE_PLACE -> {
                val d = JSON.decodeFromString(PlaceDiff.serializer(), p.diffJson)
                val r = store.places.result(d.resultId)
                if (r == null) {
                    store.proposals.upsert(p.copy(status = ProposalStatus.EXPIRED, decidedAt = now))
                    return@transaction "這家店的搜尋結果已經不在了，沒有存。"
                }
                store.places.insertFavorite(FavoritePlace(newId(), r.title, r.url, store.profile.get()?.areaText, d.note, now, r.id))
                store.proposals.upsert(p.copy(status = ProposalStatus.ACCEPTED, decidedAt = now))
                "好，${r.title}存成常去店家了。"
            }
            else -> {
                store.proposals.upsert(p.copy(status = ProposalStatus.ACCEPTED, decidedAt = now))
                "好。"
            }
        }
    }

    private suspend fun planChange(p: Proposal, now: Long): String {
        val change = JSON.decodeFromString(PlanChange.serializer(), p.diffJson)
        val section = PlanSectionKey.valueOf(change.section)
        // Checked again against the plan as it is now: a section changed since (in 設定, or by another proposal) may no
        // longer take it.
        val checked = PlanChanges.check(plans.specs(), section, change.ops.map { it.path to it.after }, today())
        if (checked !is PlanChanges.Checked.Ok || checked.change.ops.map { it.before } != change.ops.map { it.before }) {
            store.proposals.upsert(p.copy(status = ProposalStatus.EXPIRED, decidedAt = now))
            return "計畫在這之後改過了，這個提案對不上，沒有改。要的話再討論一次。"
        }
        val written = store.rules.putSection(section, checked.specs.json(section), p.id, now)
        store.proposals.upsert(p.copy(status = ProposalStatus.ACCEPTED, decidedAt = now, appliedVersion = written.version))
        return "好，改了：" + change.ops.joinToString("；") { "${it.label} ${it.before} → ${it.after}" } + "。"
    }

    companion object {
        /** The kinds the nerve owns (their answer goes through [Nerve.decide]). */
        val NERVE_KINDS = setOf(ProposalKind.TODAY_CHANGE, ProposalKind.RULES_SUGGESTION, ProposalKind.PROGRESSION, ProposalKind.TOMORROW_FIX)

        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        private val SECTION_ZH = mapOf(
            PlanSectionKey.SCHEDULE.name to "作息", PlanSectionKey.MEALS.name to "吃飯時間", PlanSectionKey.TRAINING.name to "訓練時間",
            PlanSectionKey.WATER.name to "喝水",
        )

        /** The 差異卡 of [p], from its typed diff (code-written; an unreadable diff shows its reason only). */
        fun view(p: Proposal): ProposalView = runCatching {
            when (p.kind) {
                ProposalKind.PLAN_CHANGE -> {
                    val c = JSON.decodeFromString(PlanChange.serializer(), p.diffJson)
                    ProposalView(p.id, p.kind, "改${SECTION_ZH[c.section] ?: "計畫"}", c.ops.map { DiffLine(it.label, it.before, it.after) }, p.reason)
                }
                ProposalKind.MEAL_ORDER -> {
                    val d = JSON.decodeFromString(OrderDiff.serializer(), p.diffJson)
                    val slot = SlotKey.parse(d.slot)?.zh ?: "這一餐"
                    ProposalView(
                        p.id, p.kind, "${slot}點這個",
                        listOfNotNull(d.placeTitle?.let { DiffLine("店家", null, it) }) +
                            d.items.map { DiffLine(it.name, null, "${CoachRules.kg(it.portion)} 份，${it.kcal} 大卡" + if (it.estimated) "（估算）" else "") } +
                            DiffLine("一共", null, "${d.kcal} 大卡" + if (d.items.any { it.estimated }) "（估算，拍菜單或標示才算數）" else ""),
                        p.reason,
                    )
                }
                ProposalKind.SAVE_PLACE -> {
                    val d = JSON.decodeFromString(PlaceDiff.serializer(), p.diffJson)
                    ProposalView(p.id, p.kind, "存成常去店家", listOf(DiffLine(d.title, null, d.note.ifBlank { "常去店家" })), p.reason)
                }
                ProposalKind.PROGRESSION -> {
                    val e = Nerve.decodeEffect(p.diffJson) as Effect.Proposal
                    ProposalView(p.id, p.kind, "肌力升級（一次加一樣）", listOf(DiffLine("肌力", "照現在的", e.change)), e.because)
                }
                ProposalKind.TOMORROW_FIX -> {
                    val e = Nerve.decodeEffect(p.diffJson) as Effect.Proposal
                    ProposalView(p.id, p.kind, "明天要改", listOf(DiffLine("明天起床簽到時提醒你", null, e.fixTomorrow ?: e.change)), e.because)
                }
                ProposalKind.TODAY_CHANGE, ProposalKind.RULES_SUGGESTION -> {
                    val e = Nerve.decodeEffect(p.diffJson) as Effect.Proposal
                    val title = if (p.kind == ProposalKind.TODAY_CHANGE) "只改今天" else "建議（要你自己到設定改）"
                    val label = if (p.kind == ProposalKind.TODAY_CHANGE) "今天的訓練" else "每天的熱量"
                    ProposalView(p.id, p.kind, title, listOf(DiffLine(label, "照原本的", e.change)), e.because)
                }
                else -> ProposalView(p.id, p.kind, "提案", emptyList(), p.reason)
            }
        }.getOrElse { ProposalView(p.id, p.kind, "提案", emptyList(), p.reason) }
    }
}
