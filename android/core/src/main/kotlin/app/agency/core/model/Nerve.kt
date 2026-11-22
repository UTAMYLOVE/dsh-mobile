package app.agency.core.model

/**
 * A signal as stored (agency-links §4): [type] is its serial name and [json] the whole signal; [depth] is 0 for one a feature
 * emitted, 1 or 2 for one a link chained; [causeId] the signal that chained it. Signals are kept, so the day state can be
 * recomputed from them at any time.
 */
data class StoredSignal(
    val id: String,
    val day: String,
    val at: Long,
    val type: String,
    val json: String,
    val depth: Int = 0,
    val causeId: String? = null,
)

enum class EffectKind { ADJUST, PROPOSAL, NOTIFY, SAY, RECORD }

/**
 * ACTIVE: a 今天微調 in force (CANCELLED once he cancels it). PROPOSED → ACCEPTED / REJECTED / EXPIRED. PENDING: a
 * notification not posted yet (SENT once it is). DONE: a line said or a note recorded.
 */
enum class EffectStatus { ACTIVE, CANCELLED, PROPOSED, ACCEPTED, REJECTED, EXPIRED, PENDING, SENT, DONE }

/**
 * One effect a link produced (the 「今天的連動」 card reads these). [because] is the 「因為…所以…」 line he sees; [json] the typed
 * effect; [proposalId] the proposal row of a PROPOSAL; [dueAt] when a NOTIFY is to be posted.
 */
data class NerveEffect(
    val id: String,
    val day: String,
    val at: Long,
    val link: String,
    val signalId: String,
    val kind: EffectKind,
    val json: String,
    val because: String,
    val status: EffectStatus,
    val proposalId: String? = null,
    val dueAt: Long? = null,
    val decidedAt: Long? = null,
)
