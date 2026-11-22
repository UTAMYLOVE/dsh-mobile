package app.dshmobile.agency.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.agency.core.model.AuditLog
import app.agency.core.model.MemoryEvent
import app.agency.core.model.MemoryItem
import app.agency.core.model.MemoryOp
import app.agency.core.model.Message
import app.agency.core.model.MessageMode
import app.agency.core.model.MigrationLog
import app.agency.core.model.MigrationSource
import app.agency.core.model.Nudge
import app.agency.core.model.Proposal
import app.agency.core.model.ProposalKind
import app.agency.core.model.ProposalStatus
import app.agency.core.model.RoleKey
import app.agency.core.model.Who
import app.agency.core.model.WordedBy

@Entity(tableName = "message", indices = [Index("at")])
data class MessageEntity(
    @PrimaryKey val id: String,
    val at: Long,
    val who: Who,
    val role: RoleKey?,
    val kind: String?,
    val mode: MessageMode,
    val text: String,
    val refs: String,
    @ColumnInfo(name = "worded_by") val wordedBy: WordedBy?,
    val legacy: Boolean,
    /** Schema v3 (T12): his line could not reach the model (「沒送出」). */
    @ColumnInfo(defaultValue = "0") val unsent: Boolean = false,
) {
    fun toModel() = Message(id, at, who, role, kind, mode, text, JsonCols.refs(refs), wordedBy, legacy, unsent)

    companion object {
        fun of(m: Message) = MessageEntity(m.id, m.at, m.who, m.role, m.kind, m.mode, m.text, JsonCols.refs(m.refs), m.wordedBy, m.legacy, m.unsent)
    }
}

/**
 * The manager's thinking per reply (schema v3, T12, owner's choice 乙): its own table, read only to send it back to the
 * provider that produced it; no screen, log, memory or export reads it.
 */
@Entity(tableName = "thinking", indices = [Index("at")])
data class ThinkingEntity(
    @PrimaryKey @ColumnInfo(name = "message_id") val messageId: String,
    val at: Long,
    val provider: String,
    val wire: String,
    val payload: String,
) {
    fun toModel() = app.agency.core.model.StoredThinking(messageId, at, provider, wire, payload)

    companion object {
        fun of(t: app.agency.core.model.StoredThinking) = ThinkingEntity(t.messageId, t.at, t.provider, t.wire, t.payload)
    }
}

@Entity(tableName = "proposal", indices = [Index("status")])
data class ProposalEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val role: RoleKey,
    val kind: ProposalKind,
    val target: String,
    val diff: String,
    val reason: String,
    val status: ProposalStatus,
    @ColumnInfo(name = "decided_at") val decidedAt: Long?,
    @ColumnInfo(name = "applied_version") val appliedVersion: Int?,
) {
    fun toModel() = Proposal(id, createdAt, role, kind, target, diff, reason, status, decidedAt, appliedVersion)

    companion object {
        fun of(p: Proposal) = ProposalEntity(p.id, p.createdAt, p.role, p.kind, p.target, p.diffJson, p.reason, p.status, p.decidedAt, p.appliedVersion)
    }
}

@Entity(tableName = "memory_item")
data class MemoryItemEntity(
    @PrimaryKey val id: String,
    val block: String,
    val text: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "source_message_id") val sourceMessageId: String?,
    val deleted: Boolean,
    @ColumnInfo(name = "due_day") val dueDay: String?,
) {
    fun toModel() = MemoryItem(id, block, text, createdAt, updatedAt, sourceMessageId, deleted, dueDay)

    companion object {
        fun of(i: MemoryItem) = MemoryItemEntity(i.id, i.block, i.text, i.createdAt, i.updatedAt, i.sourceMessageId, i.deleted, i.dueDay)
    }
}

@Entity(tableName = "memory_event")
data class MemoryEventEntity(
    @PrimaryKey val id: String,
    val at: Long,
    val op: MemoryOp,
    val block: String,
    @ColumnInfo(name = "item_id") val itemId: String,
    val before: String?,
    val after: String?,
    @ColumnInfo(name = "source_message_id") val sourceMessageId: String?,
    val undone: Boolean,
    @ColumnInfo(name = "before_due") val beforeDue: String?,
    @ColumnInfo(name = "after_due") val afterDue: String?,
) {
    fun toModel() = MemoryEvent(id, at, op, block, itemId, before, after, sourceMessageId, undone, beforeDue, afterDue)

    companion object {
        fun of(e: MemoryEvent) =
            MemoryEventEntity(e.id, e.at, e.op, e.block, e.itemId, e.before, e.after, e.sourceMessageId, e.undone, e.beforeDue, e.afterDue)
    }
}

@Entity(tableName = "nudge", indices = [Index("at")])
data class NudgeEntity(
    @PrimaryKey val id: String,
    val at: Long,
    @ColumnInfo(name = "gap_keys") val gapKeys: String,
    val channel: String,
    @ColumnInfo(name = "notification_id") val notificationId: Int?,
    val text: String,
    @ColumnInfo(name = "worded_by") val wordedBy: WordedBy,
    @ColumnInfo(name = "answered_at") val answeredAt: Long?,
) {
    fun toModel() = Nudge(id, at, JsonCols.strings(gapKeys), channel, notificationId, text, wordedBy, answeredAt)

    companion object {
        fun of(n: Nudge) = NudgeEntity(n.id, n.at, JsonCols.strings(n.gapKeys), n.channel, n.notificationId, n.text, n.wordedBy, n.answeredAt)
    }
}

@Entity(tableName = "migration_log")
data class MigrationLogEntity(
    @PrimaryKey val id: String,
    val at: Long,
    val source: MigrationSource,
    val counts: String,
    val ok: Boolean,
    val note: String?,
) {
    fun toModel() = MigrationLog(id, at, source, counts, ok, note)

    companion object {
        fun of(m: MigrationLog) = MigrationLogEntity(m.id, m.at, m.source, m.countsJson, m.ok, m.note)
    }
}

@Entity(tableName = "audit_log")
data class AuditLogEntity(
    @PrimaryKey val id: String,
    val at: Long,
    val what: String,
    val detail: String,
) {
    fun toModel() = AuditLog(id, at, what, detail)

    companion object {
        fun of(a: AuditLog) = AuditLogEntity(a.id, a.at, a.what, a.detail)
    }
}
