package app.agency.core.model

/**
 * 備忘錄 (T12b): [text] is exactly what he said after the command word (code-stamped, never model text). [dueAt] is the time
 * code parsed from his words (null: just listed); [remindedAt] is set once 小葵's reminder went out. [doneAt] is his check-off,
 * [deletedAt] a delete that 復原 can take back. [batchId] marks a memo that came in with a file import (undone with it).
 */
data class Memo(
    val id: String,
    val at: Long,
    val text: String,
    val dueAt: Long? = null,
    val remindedAt: Long? = null,
    val doneAt: Long? = null,
    val deletedAt: Long? = null,
    val batchId: String? = null,
) {
    val open: Boolean get() = doneAt == null && deletedAt == null
}

/** 生活費 90% / 娛樂費 10% (形象用品 from 娛樂費). */
enum class LedgerBucket(val zh: String) { LIFE("生活費"), FUN("娛樂費") }

/**
 * One 記帳 entry (T15c): [amount] in whole NT dollars, [item] and [category] as he wrote or confirmed them. [source] says where
 * it came from (`chat`, `import`, `receipt`); [batchId] ties an imported row to its import (undone together).
 */
data class LedgerEntry(
    override val id: String,
    override val day: String,
    override val at: Long,
    val amount: Int,
    val item: String,
    val category: String? = null,
    val bucket: LedgerBucket = LedgerBucket.LIFE,
    val source: String = SOURCE_CHAT,
    val batchId: String? = null,
) : DayRecord {
    companion object {
        const val SOURCE_CHAT = "chat"
        const val SOURCE_IMPORT = "import"
        const val SOURCE_RECEIPT = "receipt"
    }
}

/** What an import writes. */
enum class ImportTarget(val zh: String) { LEDGER("記帳"), MEALS("餐點"), WEIGHTS("體重"), MEMOS("備忘錄") }

/**
 * One file import he confirmed (T15c): what it wrote ([ids] by kind, so 復原 takes back exactly those rows), the source it
 * came from ([sourceKey]: file name or 「貼上」, for the remembered column mapping [mappingJson]) and when it was undone.
 */
data class ImportBatch(
    val id: String,
    val at: Long,
    val target: ImportTarget,
    val sourceKey: String,
    val mappingJson: String,
    val ids: List<String>,
    val skipped: Int,
    val undoneAt: Long? = null,
)

enum class PhotoReadKind { MENU, MEAL, RECEIPT, BODY }

enum class PhotoReadStatus { WAITING, CONFIRMED, DROPPED }

/**
 * What the image-capable model read from one photo (T15b): a suggestion only until he confirms. [json] is the checked list
 * (rows with names and numbers); nothing in it is a fact before [status] is CONFIRMED, and only the rows he kept are.
 */
data class PhotoRead(
    val id: String,
    val at: Long,
    val mediaId: String,
    val kind: PhotoReadKind,
    val json: String,
    val status: PhotoReadStatus = PhotoReadStatus.WAITING,
    val decidedAt: Long? = null,
)
