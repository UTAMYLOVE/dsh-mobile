package app.dshmobile.agency.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.agency.core.model.EffectKind
import app.agency.core.model.EffectStatus
import app.agency.core.model.NerveEffect
import app.agency.core.model.StoredSignal

/** The nerve's signals (schema v2, T11b): one row per handled signal id. */
@Entity(tableName = "signal", indices = [Index("day")])
data class SignalEntity(
    @PrimaryKey val id: String,
    val day: String,
    val at: Long,
    val type: String,
    val json: String,
    val depth: Int,
    @ColumnInfo(name = "cause_id") val causeId: String?,
) {
    fun toModel() = StoredSignal(id, day, at, type, json, depth, causeId)

    companion object {
        fun of(s: StoredSignal) = SignalEntity(s.id, s.day, s.at, s.type, s.json, s.depth, s.causeId)
    }
}

/** The effects the links produced (schema v2, T11b): the 「今天的連動」 card and the scheduler read them. */
@Entity(tableName = "nerve_effect", indices = [Index("day"), Index("status")])
data class NerveEffectEntity(
    @PrimaryKey val id: String,
    val day: String,
    val at: Long,
    val link: String,
    @ColumnInfo(name = "signal_id") val signalId: String,
    val kind: EffectKind,
    val json: String,
    val because: String,
    val status: EffectStatus,
    @ColumnInfo(name = "proposal_id") val proposalId: String?,
    @ColumnInfo(name = "due_at") val dueAt: Long?,
    @ColumnInfo(name = "decided_at") val decidedAt: Long?,
) {
    fun toModel() = NerveEffect(id, day, at, link, signalId, kind, json, because, status, proposalId, dueAt, decidedAt)

    companion object {
        fun of(e: NerveEffect) = NerveEffectEntity(e.id, e.day, e.at, e.link, e.signalId, e.kind, e.json, e.because, e.status, e.proposalId, e.dueAt, e.decidedAt)
    }
}
