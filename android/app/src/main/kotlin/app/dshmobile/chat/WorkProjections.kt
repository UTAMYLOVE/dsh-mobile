package app.dshmobile.chat

import app.dshmobile.remote.Reminder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** dsh's reminders, for the always-on helpers (0.5.0). Fakes that have none keep the defaults. */
interface ScheduleRemote {
    suspend fun scheduleCatalog(): List<Reminder> = emptyList()
    suspend fun scheduleDelete(sessionId: String, taskId: String) {}
}

enum class TodoStatus { PENDING, IN_PROGRESS, DONE }

data class Todo(val text: String, val status: TodoStatus)

/** dsh's plan mode for a conversation: [active] while planning, [pending] while the plan waits for the owner's answer. */
data class PlanState(val active: Boolean, val pending: Boolean)

/** The work projections dsh keeps per conversation (`todos` from `todo_write`, `plan` from plan mode). */
object WorkProjections {
    /** The current todo list, or null when the conversation never wrote one. */
    fun todos(p: Map<String, JsonElement>): List<Todo>? {
        val list = p["todos"] as? JsonArray ?: return null
        return list.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val text = (o["content"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val status = when ((o["status"] as? JsonPrimitive)?.content) {
                "completed" -> TodoStatus.DONE
                "in_progress" -> TodoStatus.IN_PROGRESS
                else -> TodoStatus.PENDING
            }
            Todo(text, status)
        }
    }

    fun plan(p: Map<String, JsonElement>): PlanState? {
        val o = p["plan"] as? JsonObject ?: return null
        fun flag(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull ?: false
        return PlanState(flag("active"), flag("pending"))
    }
}
