package app.dshmobile.agency.data

import androidx.room.withTransaction
import app.agency.core.model.AuditLog
import app.agency.core.model.Baseline
import app.agency.core.model.CompanyRules
import app.agency.core.model.ConditionLog
import app.agency.core.model.DayOverride
import app.agency.core.model.DayRecord
import app.agency.core.model.FavoritePlace
import app.agency.core.model.FoodEntry
import app.agency.core.model.Meal
import app.agency.core.model.MealItem
import app.agency.core.model.MealOrder
import app.agency.core.model.Media
import app.agency.core.model.MemoryEvent
import app.agency.core.model.MemoryItem
import app.agency.core.model.Message
import app.agency.core.model.MigrationLog
import app.agency.core.model.EffectStatus
import app.agency.core.model.NerveEffect
import app.agency.core.model.Nudge
import app.agency.core.model.StoredSignal
import app.agency.core.model.OrderStatus
import app.agency.core.model.PendingLabel
import app.agency.core.model.PlanSection
import app.agency.core.model.PlanSectionKey
import app.agency.core.model.Profile
import app.agency.core.model.Proposal
import app.agency.core.model.ProposalStatus
import app.agency.core.model.SearchKind
import app.agency.core.model.SearchResult
import app.agency.core.model.SignLog
import app.agency.core.model.TrainingLog
import app.agency.core.model.WaterLog
import app.agency.core.model.WeightLog
import app.agency.core.store.AgencyStore
import app.agency.core.store.BaselineRepo
import app.agency.core.store.ConditionRepo
import app.agency.core.store.DayLogRepo
import app.agency.core.store.DayOverrideRepo
import app.agency.core.store.FoodRepo
import app.agency.core.store.JournalRepo
import app.agency.core.store.MealRepo
import app.agency.core.store.MediaRepo
import app.agency.core.store.MemoryRepo
import app.agency.core.store.MessageRepo
import app.agency.core.store.NerveRepo
import app.agency.core.store.NudgeRepo
import app.agency.core.store.OrderRepo
import app.agency.core.store.PlaceRepo
import app.agency.core.store.ProfileRepo
import app.agency.core.store.ProposalRepo
import app.agency.core.store.RulesRepo
import app.dshmobile.agency.data.dao.DayLogDao
import app.dshmobile.agency.data.entities.AuditLogEntity
import app.dshmobile.agency.data.entities.BaselineEntity
import app.dshmobile.agency.data.entities.CompanyRulesEntity
import app.dshmobile.agency.data.entities.CompanyRulesHistoryEntity
import app.dshmobile.agency.data.entities.ConditionLogEntity
import app.dshmobile.agency.data.entities.DayOverrideEntity
import app.dshmobile.agency.data.entities.FavoritePlaceEntity
import app.dshmobile.agency.data.entities.FoodEntity
import app.dshmobile.agency.data.entities.MealEntity
import app.dshmobile.agency.data.entities.MealItemEntity
import app.dshmobile.agency.data.entities.MealOrderEntity
import app.dshmobile.agency.data.entities.MediaEntity
import app.dshmobile.agency.data.entities.MemoryEventEntity
import app.dshmobile.agency.data.entities.MemoryItemEntity
import app.dshmobile.agency.data.entities.MessageEntity
import app.dshmobile.agency.data.entities.MigrationLogEntity
import app.dshmobile.agency.data.entities.NerveEffectEntity
import app.dshmobile.agency.data.entities.NudgeEntity
import app.dshmobile.agency.data.entities.SignalEntity
import app.dshmobile.agency.data.entities.PendingLabelEntity
import app.dshmobile.agency.data.entities.PlanSectionColumns
import app.dshmobile.agency.data.entities.PlanSectionEntity
import app.dshmobile.agency.data.entities.PlanSectionHistoryEntity
import app.dshmobile.agency.data.entities.ProfileEntity
import app.dshmobile.agency.data.entities.ProposalEntity
import app.dshmobile.agency.data.entities.RulesColumns
import app.dshmobile.agency.data.entities.SearchResultEntity
import app.dshmobile.agency.data.entities.SignLogEntity
import app.dshmobile.agency.data.entities.TrainingLogEntity
import app.dshmobile.agency.data.entities.WaterLogEntity
import app.dshmobile.agency.data.entities.WeightLogEntity

/** [AgencyStore] on the encrypted [AgencyDatabase]. Writes that touch more than one row run in one transaction. */
class RoomAgencyStore(private val db: AgencyDatabase) : AgencyStore {
    override suspend fun <T> transaction(block: suspend () -> T): T = db.withTransaction { block() }

    private class RoomDayLog<T : DayRecord, E>(
        private val dao: DayLogDao<E>,
        private val toRow: (T) -> E,
        private val toModel: (E) -> T,
    ) : DayLogRepo<T> {
        override suspend fun insert(record: T): Boolean = dao.insert(toRow(record)) != -1L
        override suspend fun upsert(record: T) = dao.upsert(toRow(record))
        override suspend fun get(id: String): T? = dao.get(id)?.let(toModel)
        override suspend fun onDay(day: String): List<T> = dao.onDay(day).map(toModel)
        override suspend fun between(fromDay: String, toDay: String): List<T> = dao.between(fromDay, toDay).map(toModel)
        override suspend fun delete(id: String): Boolean = dao.delete(id) > 0
        override suspend fun count(): Int = dao.count()
    }

    override val profile = object : ProfileRepo {
        override suspend fun get(): Profile? = db.profile().get()?.toModel()
        override suspend fun put(profile: Profile) = db.profile().upsert(ProfileEntity.of(profile))
    }

    override val rules = object : RulesRepo {
        private val dao = db.rules()
        override suspend fun current(): CompanyRules? = dao.current()?.rules?.toModel()
        override suspend fun put(rules: CompanyRules, at: Long): CompanyRules = db.withTransaction {
            val next = rules.copy(version = (dao.current()?.rules?.version ?: 0) + 1, updatedAt = at)
            val columns = RulesColumns.of(next)
            dao.putCurrent(CompanyRulesEntity(rules = columns))
            dao.addHistory(CompanyRulesHistoryEntity(columns))
            next
        }
        override suspend fun history(): List<CompanyRules> = dao.history().map { it.rules.toModel() }
        override suspend fun section(key: PlanSectionKey): PlanSection? = dao.section(key)?.s?.toModel()
        override suspend fun sections(): List<PlanSection> = dao.sections().map { it.s.toModel() }.sortedBy { it.section.ordinal }
        override suspend fun putSection(key: PlanSectionKey, json: String, changedBy: String, at: Long): PlanSection = db.withTransaction {
            val next = PlanSection(key, json, (dao.section(key)?.s?.version ?: 0) + 1, at, changedBy)
            val columns = PlanSectionColumns.of(next)
            dao.putSection(PlanSectionEntity(columns))
            dao.addSectionHistory(PlanSectionHistoryEntity(columns))
            next
        }
        override suspend fun sectionHistory(key: PlanSectionKey): List<PlanSection> = dao.sectionHistory(key).map { it.s.toModel() }
    }

    override val days = object : DayOverrideRepo {
        override suspend fun get(day: String): DayOverride? = db.days().get(day)?.toModel()
        override suspend fun put(override: DayOverride) = db.days().put(DayOverrideEntity.of(override))
        override suspend fun delete(day: String): Boolean = db.days().delete(day) > 0
        override suspend fun between(fromDay: String, toDay: String): List<DayOverride> = db.days().between(fromDay, toDay).map { it.toModel() }
    }

    override val meals = object : MealRepo {
        private val dao = db.meals()
        private fun items(m: Meal) = m.items.mapIndexed { i, item: MealItem -> MealItemEntity.of(m.id, i, item) }

        override suspend fun insert(record: Meal): Boolean = db.withTransaction {
            val inserted = dao.insert(MealEntity.of(record)) != -1L
            if (inserted && record.items.isNotEmpty()) dao.insertItems(items(record))
            inserted
        }
        override suspend fun upsert(record: Meal) = db.withTransaction {
            dao.upsert(MealEntity.of(record))
            dao.deleteItems(record.id)
            if (record.items.isNotEmpty()) dao.insertItems(items(record))
        }
        override suspend fun get(id: String): Meal? = dao.get(id)?.toModel()
        override suspend fun onDay(day: String): List<Meal> = dao.onDay(day).map { it.toModel() }
        override suspend fun between(fromDay: String, toDay: String): List<Meal> = dao.between(fromDay, toDay).map { it.toModel() }
        override suspend fun delete(id: String): Boolean = db.withTransaction {
            dao.deletePendingOf(id)
            dao.delete(id) > 0 // the items go with it (ON DELETE CASCADE)
        }
        override suspend fun count(): Int = dao.count()
        override suspend fun putPending(label: PendingLabel) = dao.putPending(PendingLabelEntity.of(label))
        override suspend fun pending(): List<PendingLabel> = dao.pending().map { it.toModel() }
        override suspend fun deletePending(mealId: String, itemIdx: Int): Boolean = dao.deletePending(mealId, itemIdx) > 0
        override suspend fun countItems(): Int = dao.countItems()
    }

    override val food = object : FoodRepo {
        private val dao = db.food()
        override suspend fun get(name: String): FoodEntry? = dao.get(name)?.toModel()
        override suspend fun all(): List<FoodEntry> = dao.all().map { it.toModel() }
        override suspend fun insert(entry: FoodEntry): Boolean = dao.insert(FoodEntity.of(entry)) != -1L
        override suspend fun upsert(entry: FoodEntry) = dao.upsert(FoodEntity.of(entry))
        override suspend fun delete(name: String): Boolean = dao.delete(name) > 0
        override suspend fun count(): Int = dao.count()
    }

    override val orders: OrderRepo = object : OrderRepo, DayLogRepo<MealOrder> by RoomDayLog(db.orders(), MealOrderEntity::of, { it.toModel() }) {
        override suspend fun setStatus(id: String, status: OrderStatus): Boolean = db.orders().setStatus(id, status) > 0
    }

    override val places = object : PlaceRepo {
        private val dao = db.places()
        override suspend fun insertFavorite(place: FavoritePlace): Boolean = dao.insertFavorite(FavoritePlaceEntity.of(place)) != -1L
        override suspend fun favorites(): List<FavoritePlace> = dao.favorites().map { it.toModel() }
        override suspend fun deleteFavorite(id: String): Boolean = dao.deleteFavorite(id) > 0
        override suspend fun putResult(result: SearchResult) = dao.putResult(SearchResultEntity.of(result))
        override suspend fun result(id: String): SearchResult? = dao.result(id)?.toModel()
        override suspend fun results(kind: SearchKind): List<SearchResult> = dao.results(kind).map { it.toModel() }
        override suspend fun pruneResults(cutoff: Long, keep: Set<String>): Int = dao.pruneResults(cutoff, keep.toList())
    }

    override val water: DayLogRepo<WaterLog> = RoomDayLog(db.water(), WaterLogEntity::of) { it.toModel() }
    override val training: DayLogRepo<TrainingLog> = RoomDayLog(db.training(), TrainingLogEntity::of) { it.toModel() }
    override val signs: DayLogRepo<SignLog> = RoomDayLog(db.signs(), SignLogEntity::of) { it.toModel() }
    override val weights: DayLogRepo<WeightLog> = RoomDayLog(db.weights(), WeightLogEntity::of) { it.toModel() }

    override val conditions = object : ConditionRepo {
        private val dao = db.conditions()
        override suspend fun put(log: ConditionLog) = dao.put(ConditionLogEntity.of(log))
        override suspend fun get(day: String): ConditionLog? = dao.get(day)?.toModel()
        override suspend fun between(fromDay: String, toDay: String): List<ConditionLog> = dao.between(fromDay, toDay).map { it.toModel() }
        override suspend fun count(): Int = dao.count()
    }

    override val baselines = object : BaselineRepo {
        private val dao = db.baselines()
        override suspend fun get(id: String): Baseline? = dao.get(id)?.toModel()
        override suspend fun insert(baseline: Baseline): Boolean = dao.insert(BaselineEntity.of(baseline)) != -1L
        override suspend fun upsert(baseline: Baseline) = dao.upsert(BaselineEntity.of(baseline))
        override suspend fun all(): List<Baseline> = dao.all().map { it.toModel() }
        override suspend fun count(): Int = dao.count()
    }

    override val media = object : MediaRepo {
        private val dao = db.media()
        override suspend fun insert(media: Media): Boolean = dao.insert(MediaEntity.of(media)) != -1L
        override suspend fun get(id: String): Media? = dao.get(id)?.toModel()
        override suspend fun delete(id: String): Boolean = dao.delete(id) > 0
        override suspend fun all(): List<Media> = dao.all().map { it.toModel() }
        override suspend fun count(): Int = dao.count()
    }

    override val messages = object : MessageRepo {
        private val dao = db.messages()
        override suspend fun insert(message: Message): Boolean = dao.insert(MessageEntity.of(message)) != -1L
        override suspend fun get(id: String): Message? = dao.get(id)?.toModel()
        override suspend fun recent(limit: Int): List<Message> = dao.recent(limit.coerceAtLeast(0)).map { it.toModel() }
        override suspend fun between(fromAt: Long, toAt: Long): List<Message> = dao.between(fromAt, toAt).map { it.toModel() }
        override suspend fun count(): Int = dao.count()
    }

    override val proposals = object : ProposalRepo {
        private val dao = db.proposals()
        override suspend fun insert(proposal: Proposal): Boolean = dao.insert(ProposalEntity.of(proposal)) != -1L
        override suspend fun upsert(proposal: Proposal) = dao.upsert(ProposalEntity.of(proposal))
        override suspend fun get(id: String): Proposal? = dao.get(id)?.toModel()
        override suspend fun withStatus(status: ProposalStatus): List<Proposal> = dao.withStatus(status).map { it.toModel() }
        override suspend fun count(): Int = dao.count()
    }

    override val memory = object : MemoryRepo {
        private val dao = db.memory()
        override suspend fun putItem(item: MemoryItem) = dao.putItem(MemoryItemEntity.of(item))
        override suspend fun item(id: String): MemoryItem? = dao.item(id)?.toModel()
        override suspend fun items(block: String?): List<MemoryItem> = dao.items(block).map { it.toModel() }
        override suspend fun putEvent(event: MemoryEvent) = dao.putEvent(MemoryEventEntity.of(event))
        override suspend fun event(id: String): MemoryEvent? = dao.event(id)?.toModel()
        override suspend fun events(): List<MemoryEvent> = dao.events().map { it.toModel() }
    }

    override val nudges = object : NudgeRepo {
        private val dao = db.nudges()
        override suspend fun insert(nudge: Nudge): Boolean = dao.insert(NudgeEntity.of(nudge)) != -1L
        override suspend fun get(id: String): Nudge? = dao.get(id)?.toModel()
        override suspend fun between(fromAt: Long, toAt: Long): List<Nudge> = dao.between(fromAt, toAt).map { it.toModel() }
        override suspend fun markAnswered(id: String, at: Long): Boolean = dao.markAnswered(id, at) > 0
    }

    override val journal = object : JournalRepo {
        private val dao = db.journal()
        override suspend fun logMigration(log: MigrationLog) = dao.logMigration(MigrationLogEntity.of(log))
        override suspend fun migrations(): List<MigrationLog> = dao.migrations().map { it.toModel() }
        override suspend fun audit(log: AuditLog) = dao.audit(AuditLogEntity.of(log))
        override suspend fun audits(): List<AuditLog> = dao.audits().map { it.toModel() }
    }

    override val nerve = object : NerveRepo {
        private val dao = db.nerve()
        override suspend fun insertSignal(signal: StoredSignal): Boolean = dao.insertSignal(SignalEntity.of(signal)) != -1L
        override suspend fun signal(id: String): StoredSignal? = dao.signal(id)?.toModel()
        override suspend fun signals(fromDay: String, toDay: String): List<StoredSignal> = dao.signals(fromDay, toDay).map { it.toModel() }
        override suspend fun insertEffect(effect: NerveEffect): Boolean = dao.insertEffect(NerveEffectEntity.of(effect)) != -1L
        override suspend fun effect(id: String): NerveEffect? = dao.effect(id)?.toModel()
        override suspend fun effects(day: String): List<NerveEffect> = dao.effects(day).map { it.toModel() }
        override suspend fun effectsWith(status: EffectStatus): List<NerveEffect> = dao.effectsWith(status).map { it.toModel() }
        override suspend fun setStatus(id: String, status: EffectStatus, decidedAt: Long?): Boolean = dao.setStatus(id, status, decidedAt) > 0
    }
}
