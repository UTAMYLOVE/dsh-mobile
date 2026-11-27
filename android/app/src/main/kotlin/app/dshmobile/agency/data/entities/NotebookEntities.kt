package app.dshmobile.agency.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.agency.core.model.ImportBatch
import app.agency.core.model.ImportTarget
import app.agency.core.model.LedgerBucket
import app.agency.core.model.LedgerEntry
import app.agency.core.model.Memo
import app.agency.core.model.PhotoRead
import app.agency.core.model.PhotoReadKind
import app.agency.core.model.PhotoReadStatus

/** 備忘錄 (v6, T12b). */
@Entity(tableName = "memo")
data class MemoEntity(
    @PrimaryKey val id: String,
    val at: Long,
    val text: String,
    @ColumnInfo(name = "due_at") val dueAt: Long?,
    @ColumnInfo(name = "reminded_at") val remindedAt: Long?,
    @ColumnInfo(name = "done_at") val doneAt: Long?,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    @ColumnInfo(name = "batch_id") val batchId: String?,
) {
    fun toModel() = Memo(id, at, text, dueAt, remindedAt, doneAt, deletedAt, batchId)

    companion object {
        fun of(m: Memo) = MemoEntity(m.id, m.at, m.text, m.dueAt, m.remindedAt, m.doneAt, m.deletedAt, m.batchId)
    }
}

/** 記帳 (v6, T15c). */
@Entity(tableName = "ledger_entry", indices = [Index("day")])
data class LedgerEntryEntity(
    @PrimaryKey val id: String,
    val day: String,
    val at: Long,
    val amount: Int,
    val item: String,
    val category: String?,
    val bucket: LedgerBucket,
    val source: String,
    @ColumnInfo(name = "batch_id") val batchId: String?,
) {
    fun toModel() = LedgerEntry(id, day, at, amount, item, category, bucket, source, batchId)

    companion object {
        fun of(e: LedgerEntry) = LedgerEntryEntity(e.id, e.day, e.at, e.amount, e.item, e.category, e.bucket, e.source, e.batchId)
    }
}

/** A confirmed file import (v6, T15c). */
@Entity(tableName = "import_batch")
data class ImportBatchEntity(
    @PrimaryKey val id: String,
    val at: Long,
    val target: ImportTarget,
    @ColumnInfo(name = "source_key") val sourceKey: String,
    val mapping: String,
    val ids: String,
    val skipped: Int,
    @ColumnInfo(name = "undone_at") val undoneAt: Long?,
) {
    fun toModel() = ImportBatch(id, at, target, sourceKey, mapping, JsonCols.strings(ids), skipped, undoneAt)

    companion object {
        fun of(b: ImportBatch) = ImportBatchEntity(b.id, b.at, b.target, b.sourceKey, b.mappingJson, JsonCols.strings(b.ids), b.skipped, b.undoneAt)
    }
}

/** What the image model read from a photo (v6, T15b). */
@Entity(tableName = "photo_read")
data class PhotoReadEntity(
    @PrimaryKey val id: String,
    val at: Long,
    @ColumnInfo(name = "media_id") val mediaId: String,
    val kind: PhotoReadKind,
    val json: String,
    val status: PhotoReadStatus,
    @ColumnInfo(name = "decided_at") val decidedAt: Long?,
) {
    fun toModel() = PhotoRead(id, at, mediaId, kind, json, status, decidedAt)

    companion object {
        fun of(r: PhotoRead) = PhotoReadEntity(r.id, r.at, r.mediaId, r.kind, r.json, r.status, r.decidedAt)
    }
}
