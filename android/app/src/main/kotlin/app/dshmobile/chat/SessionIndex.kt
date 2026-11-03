package app.dshmobile.chat

import app.dshmobile.remote.obj
import app.dshmobile.remote.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One line of the conversation list. [title] is empty when dsh has no title yet (the UI says 「新對話」). */
data class SessionRow(
    val id: String,
    val title: String,
    val lastLine: String?,
    val model: String?,
    /** The model chosen for the next turn; Task 8 shows `nextModel ?: model`. */
    val nextModel: String?,
    val updatedAt: Long,
    val running: Boolean,
)

object SessionRows {
    private fun isTopLevel(s: app.dshmobile.remote.SessionSummary) = s.parentId == null && s.origin != "subagent"

    /** Top-level, unarchived conversations, newest first. */
    fun rows(s: SessionTreeState): List<SessionRow> = s.summaries.values
        .filter { isTopLevel(it) && it.id !in s.archived }
        .map { row(s, it) }
        .sortedByDescending { it.updatedAt }

    /** The title of a top-level session; null for subagents, unknown ids and untitled sessions. */
    fun titleOf(s: SessionTreeState, id: String): String? =
        s.summaries[id]?.takeIf(::isTopLevel)?.let { row(s, it).title.takeIf { t -> t.isNotBlank() } }

    private fun row(s: SessionTreeState, m: app.dshmobile.remote.SessionSummary): SessionRow {
        val p = s.projections[m.id].orEmpty()
        val lastTurn = (p["turnOutline"] as? JsonArray)?.lastOrNull() as? JsonObject
        val selection = p["modelSelection"] as? JsonObject
        return SessionRow(
            id = m.id,
            title = text(p["title"]) ?: m.title.orEmpty(),
            lastLine = lastTurn?.string("response")?.takeIf { it.isNotEmpty() } ?: lastTurn?.string("prompt") ?: m.lastLine,
            model = selection?.obj("lastUsed")?.string("model") ?: m.model,
            nextModel = selection?.obj("next")?.string("model") ?: m.nextModel,
            updatedAt = m.updatedAt ?: 0L,
            running = m.running ?: false,
        )
    }

    private fun text(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
}

enum class DayGroup(val zh: String) { TODAY("今天"), YESTERDAY("昨天"), EARLIER("更早") }

/** Groups [rows] (kept in their order) by their local calendar day; empty groups are left out. */
fun groupByDay(rows: List<SessionRow>, today: LocalDate, zone: ZoneId): List<Pair<DayGroup, List<SessionRow>>> {
    val by = rows.groupBy { r ->
        val day = Instant.ofEpochMilli(r.updatedAt).atZone(zone).toLocalDate()
        when {
            r.updatedAt <= 0L -> DayGroup.EARLIER
            !day.isBefore(today) -> DayGroup.TODAY
            day == today.minusDays(1) -> DayGroup.YESTERDAY
            else -> DayGroup.EARLIER
        }
    }
    return DayGroup.entries.mapNotNull { g -> by[g]?.let { g to it } }
}

/** The conversation list: rows derived from [SessionTree], plus search, create, rename and delete (archive). */
class SessionIndex(private val tree: SessionTree, private val remote: IndexRemote, scope: CoroutineScope) {
    val rows: StateFlow<List<SessionRow>> = tree.state.map(SessionRows::rows)
        .stateIn(scope, SharingStarted.Eagerly, SessionRows.rows(tree.state.value))

    fun titleOf(id: String): String? = SessionRows.titleOf(tree.state.value, id)

    /** dsh's hits in dsh's order, as rows of top-level, unarchived conversations; [SessionRow.lastLine] is the snippet. */
    suspend fun search(query: String): List<SessionRow> {
        val byId = rows.value.associateBy { it.id }
        return remote.searchSessions(query).mapNotNull { hit -> byId[hit.sessionId]?.copy(lastLine = hit.snippet) }
    }

    /** A new conversation in the app workspace (its id when known, else its path); returns the session id. */
    suspend fun create(): String {
        val id = remote.createSession(tree.state.value.workspaceId)
        tree.refreshNow()
        return id
    }

    suspend fun rename(id: String, title: String) {
        val stored = remote.rename(id, title)
        tree.setTitle(id, stored)
        tree.refreshNow()
    }

    /** Delete means archive, and stop whatever the conversation is doing. The row leaves the list at once. */
    suspend fun delete(id: String) {
        remote.archive(id)
        tree.markArchived(id)
        tree.refreshNow()
    }
}
