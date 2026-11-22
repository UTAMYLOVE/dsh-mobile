package app.dshmobile.agency.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.agency.core.model.OrderStatus
import app.agency.core.model.PlanSectionKey
import app.agency.core.model.ProposalStatus
import app.agency.core.model.SearchKind
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
import app.dshmobile.agency.data.entities.MealWithItems
import app.dshmobile.agency.data.entities.MediaEntity
import app.dshmobile.agency.data.entities.MemoryEventEntity
import app.dshmobile.agency.data.entities.MemoryItemEntity
import app.dshmobile.agency.data.entities.MessageEntity
import app.dshmobile.agency.data.entities.MigrationLogEntity
import app.dshmobile.agency.data.entities.NudgeEntity
import app.dshmobile.agency.data.entities.PendingLabelEntity
import app.dshmobile.agency.data.entities.PlanSectionEntity
import app.dshmobile.agency.data.entities.PlanSectionHistoryEntity
import app.dshmobile.agency.data.entities.ProfileEntity
import app.dshmobile.agency.data.entities.ProposalEntity
import app.dshmobile.agency.data.entities.SearchResultEntity

/** `insert` is INSERT OR IGNORE (returns -1 when the key exists, so a re-run import writes nothing); `upsert` replaces. */
interface WriteDao<E> {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(row: E): Long
    @Upsert suspend fun upsert(row: E)
}

/** The queries every day-keyed log has (Room needs the table name in each query, so each log declares them). */
interface DayLogDao<E> : WriteDao<E> {
    suspend fun get(id: String): E?
    suspend fun onDay(day: String): List<E>
    suspend fun between(fromDay: String, toDay: String): List<E>
    suspend fun delete(id: String): Int
    suspend fun count(): Int
}

@Dao interface ProfileDao : WriteDao<ProfileEntity> {
    @Query("SELECT * FROM profile WHERE id = 1") suspend fun get(): ProfileEntity?
}

@Dao interface RulesDao {
    @Query("SELECT * FROM company_rules WHERE id = 1") suspend fun current(): CompanyRulesEntity?
    @Upsert suspend fun putCurrent(row: CompanyRulesEntity)
    @Insert suspend fun addHistory(row: CompanyRulesHistoryEntity)
    @Query("SELECT * FROM company_rules_history ORDER BY version") suspend fun history(): List<CompanyRulesHistoryEntity>

    @Query("SELECT * FROM plan_section WHERE section = :key") suspend fun section(key: PlanSectionKey): PlanSectionEntity?
    @Query("SELECT * FROM plan_section") suspend fun sections(): List<PlanSectionEntity>
    @Upsert suspend fun putSection(row: PlanSectionEntity)
    @Insert suspend fun addSectionHistory(row: PlanSectionHistoryEntity)
    @Query("SELECT * FROM plan_section_history WHERE section = :key ORDER BY version")
    suspend fun sectionHistory(key: PlanSectionKey): List<PlanSectionHistoryEntity>
}

@Dao interface DayOverrideDao {
    @Query("SELECT * FROM day_override WHERE day = :day") suspend fun get(day: String): DayOverrideEntity?
    @Upsert suspend fun put(row: DayOverrideEntity)
    @Query("DELETE FROM day_override WHERE day = :day") suspend fun delete(day: String): Int
    @Query("SELECT * FROM day_override WHERE day BETWEEN :fromDay AND :toDay ORDER BY day")
    suspend fun between(fromDay: String, toDay: String): List<DayOverrideEntity>
}

@Dao interface MealDao : WriteDao<MealEntity> {
    @Transaction @Query("SELECT * FROM meal WHERE id = :id") suspend fun get(id: String): MealWithItems?
    @Transaction @Query("SELECT * FROM meal WHERE day = :day ORDER BY at, id") suspend fun onDay(day: String): List<MealWithItems>
    @Transaction @Query("SELECT * FROM meal WHERE day BETWEEN :fromDay AND :toDay ORDER BY at, id")
    suspend fun between(fromDay: String, toDay: String): List<MealWithItems>
    @Query("DELETE FROM meal WHERE id = :id") suspend fun delete(id: String): Int
    @Query("SELECT COUNT(*) FROM meal") suspend fun count(): Int

    @Insert suspend fun insertItems(rows: List<MealItemEntity>)
    @Query("DELETE FROM meal_item WHERE meal_id = :mealId") suspend fun deleteItems(mealId: String)
    @Query("SELECT COUNT(*) FROM meal_item") suspend fun countItems(): Int

    @Upsert suspend fun putPending(row: PendingLabelEntity)
    @Query("SELECT * FROM pending_label ORDER BY asked_at, meal_id, item_idx") suspend fun pending(): List<PendingLabelEntity>
    @Query("DELETE FROM pending_label WHERE meal_id = :mealId AND item_idx = :itemIdx") suspend fun deletePending(mealId: String, itemIdx: Int): Int
    @Query("DELETE FROM pending_label WHERE meal_id = :mealId") suspend fun deletePendingOf(mealId: String)
}

@Dao interface FoodDao : WriteDao<FoodEntity> {
    @Query("SELECT * FROM food WHERE name = :name") suspend fun get(name: String): FoodEntity?
    @Query("SELECT * FROM food ORDER BY name") suspend fun all(): List<FoodEntity>
    @Query("DELETE FROM food WHERE name = :name") suspend fun delete(name: String): Int
    @Query("SELECT COUNT(*) FROM food") suspend fun count(): Int
}

@Dao interface OrderDao : DayLogDao<MealOrderEntity> {
    @Query("SELECT * FROM meal_order WHERE id = :id") override suspend fun get(id: String): MealOrderEntity?
    @Query("SELECT * FROM meal_order WHERE day = :day ORDER BY created_at, id") override suspend fun onDay(day: String): List<MealOrderEntity>
    @Query("SELECT * FROM meal_order WHERE day BETWEEN :fromDay AND :toDay ORDER BY created_at, id")
    override suspend fun between(fromDay: String, toDay: String): List<MealOrderEntity>
    @Query("DELETE FROM meal_order WHERE id = :id") override suspend fun delete(id: String): Int
    @Query("SELECT COUNT(*) FROM meal_order") override suspend fun count(): Int
    @Query("UPDATE meal_order SET status = :status WHERE id = :id") suspend fun setStatus(id: String, status: OrderStatus): Int
}

@Dao interface PlaceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertFavorite(row: FavoritePlaceEntity): Long
    @Query("SELECT * FROM favorite_place ORDER BY saved_at, id") suspend fun favorites(): List<FavoritePlaceEntity>
    @Query("DELETE FROM favorite_place WHERE id = :id") suspend fun deleteFavorite(id: String): Int
    @Upsert suspend fun putResult(row: SearchResultEntity)
    @Query("SELECT * FROM search_result WHERE id = :id") suspend fun result(id: String): SearchResultEntity?
    @Query("SELECT * FROM search_result WHERE kind = :kind ORDER BY fetched_at, id") suspend fun results(kind: SearchKind): List<SearchResultEntity>
    @Query("DELETE FROM search_result WHERE fetched_at < :cutoff AND id NOT IN (:keep)")
    suspend fun pruneResults(cutoff: Long, keep: List<String>): Int
}

@Dao interface ConditionDao {
    @Upsert suspend fun put(row: ConditionLogEntity)
    @Query("SELECT * FROM condition_log WHERE day = :day") suspend fun get(day: String): ConditionLogEntity?
    @Query("SELECT * FROM condition_log WHERE day BETWEEN :fromDay AND :toDay ORDER BY day")
    suspend fun between(fromDay: String, toDay: String): List<ConditionLogEntity>
    @Query("SELECT COUNT(*) FROM condition_log") suspend fun count(): Int
}

@Dao interface BaselineDao : WriteDao<BaselineEntity> {
    @Query("SELECT * FROM baseline WHERE id = :id") suspend fun get(id: String): BaselineEntity?
    @Query("SELECT * FROM baseline ORDER BY id") suspend fun all(): List<BaselineEntity>
    @Query("SELECT COUNT(*) FROM baseline") suspend fun count(): Int
}

@Dao interface MediaDao : WriteDao<MediaEntity> {
    @Query("SELECT * FROM media WHERE id = :id") suspend fun get(id: String): MediaEntity?
    @Query("DELETE FROM media WHERE id = :id") suspend fun delete(id: String): Int
    @Query("SELECT * FROM media ORDER BY created_at, id") suspend fun all(): List<MediaEntity>
    @Query("SELECT COUNT(*) FROM media") suspend fun count(): Int
}

@Dao interface MessageDao : WriteDao<MessageEntity> {
    @Query("SELECT * FROM message WHERE id = :id") suspend fun get(id: String): MessageEntity?
    @Query("SELECT * FROM (SELECT * FROM message ORDER BY at DESC, id DESC LIMIT :limit) ORDER BY at, id")
    suspend fun recent(limit: Int): List<MessageEntity>
    @Query("SELECT * FROM message WHERE at >= :fromAt AND at < :toAt ORDER BY at, id")
    suspend fun between(fromAt: Long, toAt: Long): List<MessageEntity>
    @Query("SELECT COUNT(*) FROM message") suspend fun count(): Int
}

@Dao interface ProposalDao : WriteDao<ProposalEntity> {
    @Query("SELECT * FROM proposal WHERE id = :id") suspend fun get(id: String): ProposalEntity?
    @Query("SELECT * FROM proposal WHERE status = :status ORDER BY created_at, id") suspend fun withStatus(status: ProposalStatus): List<ProposalEntity>
    @Query("SELECT COUNT(*) FROM proposal") suspend fun count(): Int
}

@Dao interface MemoryDao {
    @Upsert suspend fun putItem(row: MemoryItemEntity)
    @Query("SELECT * FROM memory_item WHERE id = :id") suspend fun item(id: String): MemoryItemEntity?
    @Query("SELECT * FROM memory_item WHERE deleted = 0 AND (:block IS NULL OR block = :block) ORDER BY created_at, id")
    suspend fun items(block: String?): List<MemoryItemEntity>
    @Upsert suspend fun putEvent(row: MemoryEventEntity)
    @Query("SELECT * FROM memory_event WHERE id = :id") suspend fun event(id: String): MemoryEventEntity?
    @Query("SELECT * FROM memory_event ORDER BY at DESC, id DESC") suspend fun events(): List<MemoryEventEntity>
}

@Dao interface NudgeDao : WriteDao<NudgeEntity> {
    @Query("SELECT * FROM nudge WHERE id = :id") suspend fun get(id: String): NudgeEntity?
    @Query("SELECT * FROM nudge WHERE at >= :fromAt AND at < :toAt ORDER BY at, id") suspend fun between(fromAt: Long, toAt: Long): List<NudgeEntity>
    @Query("UPDATE nudge SET answered_at = :at WHERE id = :id") suspend fun markAnswered(id: String, at: Long): Int
}

@Dao interface JournalDao {
    @Upsert suspend fun logMigration(row: MigrationLogEntity)
    @Query("SELECT * FROM migration_log ORDER BY at, id") suspend fun migrations(): List<MigrationLogEntity>
    @Upsert suspend fun audit(row: AuditLogEntity)
    @Query("SELECT * FROM audit_log ORDER BY at, id") suspend fun audits(): List<AuditLogEntity>
}
