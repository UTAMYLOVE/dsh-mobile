package app.agency.core.tools

import app.agency.core.llm.ToolSpec
import app.agency.core.store.AgencyStore
import app.agency.core.structured.JsonSchema
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `read_memos` (T12b): his memos as stored (his words, the time code read, done or not), so 小葵 can answer 「我要記得什麼」.
 * Read only: there is no tool that writes or edits a memo, and she may name only the rows this returns.
 */
class ReadMemosTool(private val store: AgencyStore, private val zone: ZoneId) : AgencyTool {
    override val spec = ToolSpec(
        NAME, "讀他的備忘錄（他的原話、提醒時間、做完沒）。只能照這裡的內容說，不能改他的字，也不能說沒有存的備忘錄。", JsonSchema.obj(),
    )

    override suspend fun run(args: JsonObject): JsonElement {
        val rows = store.memos.all()
        val open = rows.filter { it.doneAt == null }
        val done = rows.filter { it.doneAt != null }.takeLast(MAX_DONE)
        return buildJsonObject {
            put("memos", buildJsonArray {
                (open + done).take(MAX_ROWS).forEach { m ->
                    add(buildJsonObject {
                        put("id", m.id)
                        put("text", m.text)
                        val due = m.dueAt
                        if (due != null) put("remind_at", Instant.ofEpochMilli(due).atZone(zone).toLocalDateTime().toString()) else put("remind_at", JsonNull)
                        put("done", m.doneAt != null)
                    })
                }
            })
            put("count_open", open.size)
        }
    }

    companion object {
        const val NAME = "read_memos"
        const val MAX_ROWS = 40
        const val MAX_DONE = 10
    }
}
