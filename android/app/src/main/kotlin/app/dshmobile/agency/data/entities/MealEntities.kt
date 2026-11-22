package app.dshmobile.agency.data.entities

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import app.agency.core.model.FavoritePlace
import app.agency.core.model.FoodEntry
import app.agency.core.model.KcalSource
import app.agency.core.model.Meal
import app.agency.core.model.MealItem
import app.agency.core.model.MealOrder
import app.agency.core.model.OrderStatus
import app.agency.core.model.PendingLabel
import app.agency.core.model.SearchKind
import app.agency.core.model.SearchResult

@Entity(tableName = "meal", indices = [Index("day")])
data class MealEntity(
    @PrimaryKey val id: String,
    val day: String,
    @ColumnInfo(name = "slot_key") val slotKey: String?,
    val at: Long,
    val text: String,
    val cheat: Boolean,
    val breaches: String,
    val verified: Boolean,
    @ColumnInfo(name = "photo_media_id") val photoMediaId: String?,
    @ColumnInfo(name = "order_id") val orderId: String?,
    val legacy: Boolean,
) {
    companion object {
        fun of(m: Meal) = MealEntity(
            m.id, m.day, m.slotKey, m.at, m.text, m.cheat, JsonCols.strings(m.breaches), m.verified, m.photoMediaId, m.orderId,
            m.legacy,
        )
    }
}

/** An item belongs to its meal: deleting the meal deletes it. */
@Entity(
    tableName = "meal_item",
    primaryKeys = ["meal_id", "idx"],
    foreignKeys = [ForeignKey(entity = MealEntity::class, parentColumns = ["id"], childColumns = ["meal_id"], onDelete = ForeignKey.CASCADE)],
)
data class MealItemEntity(
    @ColumnInfo(name = "meal_id") val mealId: String,
    val idx: Int,
    val name: String,
    val portion: Double,
    val kcal: Int,
    @ColumnInfo(name = "protein_g") val proteinG: Double?,
    @ColumnInfo(name = "fat_g") val fatG: Double?,
    @ColumnInfo(name = "carbs_g") val carbsG: Double?,
    val fried: Boolean,
    val processed: Boolean,
    @ColumnInfo(name = "zero_cal") val zeroCal: Boolean,
    val source: KcalSource,
    @ColumnInfo(name = "table_estimated") val tableEstimated: Boolean,
    @ColumnInfo(name = "estimate_high") val estimateHigh: Int?,
) {
    fun toModel() = MealItem(name, portion, kcal, proteinG, fatG, carbsG, fried, source, tableEstimated, estimateHigh, zeroCal, processed)

    companion object {
        fun of(mealId: String, idx: Int, i: MealItem) = MealItemEntity(
            mealId, idx, i.name, i.portion, i.kcal, i.proteinG, i.fatG, i.carbsG, i.fried, i.processed, i.zeroCal, i.source,
            i.tableEstimated, i.estimateHigh,
        )
    }
}

data class MealWithItems(
    @Embedded val meal: MealEntity,
    @Relation(parentColumn = "id", entityColumn = "meal_id") val items: List<MealItemEntity>,
) {
    fun toModel() = Meal(
        meal.id, meal.day, meal.at, meal.slotKey, meal.text, items.sortedBy { it.idx }.map { it.toModel() }, meal.cheat,
        JsonCols.strings(meal.breaches), meal.verified, meal.photoMediaId, meal.orderId, meal.legacy,
    )
}

@Entity(tableName = "pending_label", primaryKeys = ["meal_id", "item_idx"])
data class PendingLabelEntity(
    @ColumnInfo(name = "meal_id") val mealId: String,
    @ColumnInfo(name = "item_idx") val itemIdx: Int,
    val name: String,
    @ColumnInfo(name = "counted_kcal") val countedKcal: Int,
    @ColumnInfo(name = "asked_at") val askedAt: Long,
) {
    fun toModel() = PendingLabel(mealId, itemIdx, name, countedKcal, askedAt)

    companion object {
        fun of(p: PendingLabel) = PendingLabelEntity(p.mealId, p.itemIdx, p.name, p.countedKcal, p.askedAt)
    }
}

@Entity(tableName = "food")
data class FoodEntity(
    @PrimaryKey val name: String,
    val aliases: String,
    val kcal: Int,
    @ColumnInfo(name = "protein_g") val proteinG: Double?,
    @ColumnInfo(name = "fat_g") val fatG: Double?,
    @ColumnInfo(name = "carbs_g") val carbsG: Double?,
    val fried: Boolean,
    val processed: Boolean,
    val estimated: Boolean,
    val seeded: Boolean,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    fun toModel() = FoodEntry(name, JsonCols.strings(aliases), kcal, proteinG, fatG, carbsG, fried, estimated, seeded, addedAt, processed, updatedAt)

    companion object {
        fun of(f: FoodEntry) = FoodEntity(
            f.name, JsonCols.strings(f.aliases), f.kcal, f.proteinG, f.fatG, f.carbsG, f.fried, f.processed, f.estimated, f.seeded,
            f.addedAt, f.updatedAt,
        )
    }
}

@Entity(tableName = "meal_order", indices = [Index("day")])
data class MealOrderEntity(
    @PrimaryKey val id: String,
    val day: String,
    @ColumnInfo(name = "slot_key") val slotKey: String,
    val items: String,
    val kcal: Int,
    @ColumnInfo(name = "protein_g") val proteinG: Double?,
    @ColumnInfo(name = "place_result_id") val placeResultId: String?,
    val status: OrderStatus,
    /** The order's [MealOrder.at]. */
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    fun toModel() = MealOrder(id, day, createdAt, slotKey, JsonCols.strings(items), kcal, proteinG, placeResultId, status)

    companion object {
        fun of(o: MealOrder) = MealOrderEntity(o.id, o.day, o.slotKey, JsonCols.strings(o.items), o.kcal, o.proteinG, o.placeResultId, o.status, o.at)
    }
}

@Entity(tableName = "favorite_place")
data class FavoritePlaceEntity(
    @PrimaryKey val id: String,
    val title: String,
    val url: String?,
    val area: String?,
    val note: String?,
    @ColumnInfo(name = "saved_at") val savedAt: Long,
    @ColumnInfo(name = "source_result_id") val sourceResultId: String?,
) {
    fun toModel() = FavoritePlace(id, title, url, area, note, savedAt, sourceResultId)

    companion object {
        fun of(f: FavoritePlace) = FavoritePlaceEntity(f.id, f.title, f.url, f.area, f.note, f.savedAt, f.sourceResultId)
    }
}

@Entity(tableName = "search_result")
data class SearchResultEntity(
    @PrimaryKey val id: String,
    val kind: SearchKind,
    val query: String,
    val url: String,
    val title: String,
    val summary: String?,
    val date: String?,
    val authority: Double?,
    val rerank: Double?,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
) {
    fun toModel() = SearchResult(id, kind, query, url, title, summary, date, authority, rerank, fetchedAt)

    companion object {
        fun of(r: SearchResult) = SearchResultEntity(r.id, r.kind, r.query, r.url, r.title, r.summary, r.date, r.authority, r.rerank, r.fetchedAt)
    }
}
