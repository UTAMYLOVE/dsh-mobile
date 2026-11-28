package app.agency.core.agent

import app.agency.core.base.Clock
import app.agency.core.llm.ToolSpec
import app.agency.core.rules.Rules
import app.agency.core.stage.Feature
import app.agency.core.stage.StageState
import app.agency.core.store.Store
import kotlinx.serialization.json.JsonObject

/** What a tool call produced: [facts] go back to the model (and feed the guard's number check); [cards] go to the screen. */
data class ToolOut(val facts: JsonObject, val cards: List<Card> = emptyList(), val ok: Boolean = true) {
    companion object {
        fun error(message: String): ToolOut =
            ToolOut(JsonObject(mapOf("error" to kotlinx.serialization.json.JsonPrimitive(message))), ok = false)
    }
}

/** What every tool may use. Built fresh for each turn so [rules] and [stage] are current. */
class ToolCtx(
    val store: Store,
    val clock: Clock,
    val rules: Rules,
    val stage: StageState,
    /** Photos attached to the message being answered (vault ids), so a meal tool can attach one. */
    val photoIds: List<String> = emptyList(),
    val ports: Ports = Ports(),
)

/** Outside services a tool may need. Each is optional; a missing one makes its tools answer "not available". */
class Ports(
    val search: SearchPort? = null,
)

data class Hit(val title: String, val url: String, val summary: String = "", val content: String = "")

/** Real web search. A tool may only show what this returned: nothing a model wrote counts as a place or a video. */
interface SearchPort {
    suspend fun videos(query: String): List<Hit>
    suspend fun places(query: String): List<Hit>
}

/**
 * One thing 小葵 can do. [feature] gates it: when that feature is closed the tool is still offered (so a voluntary report is
 * recorded) but [run] must not schedule, count or chase anything, and says so in its facts (`"open": false`).
 * [direct] tools can also be run by a tap without a model call.
 */
interface Tool {
    val spec: ToolSpec
    val feature: Feature?
    val direct: Boolean get() = false
    suspend fun run(args: JsonObject, ctx: ToolCtx): ToolOut
}
