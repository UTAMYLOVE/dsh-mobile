package app.agency.core.model

import kotlinx.serialization.Serializable

enum class Who { OWNER, COMPANY, SYSTEM }

/** The company role that spoke (persona cards live in `persona/`). */
enum class RoleKey { MANAGER, BODY }

enum class MessageMode { NORMAL, DISCUSS }

/** Whether a company sentence was written by the model (and passed the guard) or by a code template. */
enum class WordedBy { MODEL, TEMPLATE }

/** What a message points at: record ids, search result ids, a proposal. */
@Serializable
data class MessageRefs(
    val records: List<String> = emptyList(),
    val results: List<String> = emptyList(),
    val proposal: String? = null,
)

/** One line of the one conversation. [legacy] marks lines imported from v0.8.3. */
data class Message(
    val id: String,
    val at: Long,
    val who: Who,
    val role: RoleKey? = null,
    val kind: String? = null,
    val mode: MessageMode = MessageMode.NORMAL,
    val text: String,
    val refs: MessageRefs = MessageRefs(),
    val wordedBy: WordedBy? = null,
    val legacy: Boolean = false,
)

/** [TODAY_CHANGE] and [RULES_SUGGESTION] come from the links (T11b): a today-only illness change, a hint to go to 設定. */
enum class ProposalKind { PLAN_CHANGE, MEAL_ORDER, SAVE_PLACE, PROGRESSION, WAKE_EARLIER, TODAY_CHANGE, RULES_SUGGESTION }
enum class ProposalStatus { PENDING, ACCEPTED, REJECTED, EXPIRED }

/** A change the company suggests; code applies it only after his tap or exact 「好」. [diffJson] is the typed diff. */
data class Proposal(
    val id: String,
    val createdAt: Long,
    val role: RoleKey,
    val kind: ProposalKind,
    val target: String,
    val diffJson: String,
    val reason: String,
    val status: ProposalStatus = ProposalStatus.PENDING,
    val decidedAt: Long? = null,
    val appliedVersion: Int? = null,
)

/**
 * One remembered fact in a fixed memory block (≤ 80 characters). [dueDay] (`yyyy-MM-dd`) is set for an open loop: the day
 * code worked out from its words when they were written.
 */
data class MemoryItem(
    val id: String,
    val block: String,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sourceMessageId: String?,
    val deleted: Boolean = false,
    val dueDay: String? = null,
)

enum class MemoryOp { ADD, UPDATE, DELETE }

/** A change to memory, shown in 設定 › 她記得的事 and undoable; [beforeDue] and [afterDue] are the item's due day around it. */
data class MemoryEvent(
    val id: String,
    val at: Long,
    val op: MemoryOp,
    val block: String,
    val itemId: String,
    val before: String?,
    val after: String?,
    val sourceMessageId: String?,
    val undone: Boolean = false,
    val beforeDue: String? = null,
    val afterDue: String? = null,
)

/** A proactive message the company sent (the caps and the war room read this log). */
data class Nudge(
    val id: String,
    val at: Long,
    val gapKeys: List<String>,
    val channel: String,
    val notificationId: Int? = null,
    val text: String,
    val wordedBy: WordedBy,
    val answeredAt: Long? = null,
)

enum class MigrationSource { V083_JSON, AGENCY_V2_JSON }

/** One import attempt; [countsJson] holds counts per kind (never content). */
data class MigrationLog(
    val id: String,
    val at: Long,
    val source: MigrationSource,
    val countsJson: String,
    val ok: Boolean,
    val note: String? = null,
)

/** Rule edits from settings, seeded-food corrections, legacy cleanup. */
data class AuditLog(val id: String, val at: Long, val what: String, val detail: String)
