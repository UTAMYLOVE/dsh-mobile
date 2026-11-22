package app.agency.core.tools

import app.agency.core.llm.ToolCall
import app.agency.core.llm.ToolSpec
import app.agency.core.llm.Turn
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The three classes of tool error the model sees (arch 1.4, LangChain4j style). */
enum class ToolErrorKind { UNKNOWN_TOOL, BAD_ARGS, FAILED }

/** A tool argument that is missing or out of range: [field] and [reason] go back to the model so it can fix the call. */
class BadToolArgs(val field: String, val reason: String) : Exception("bad tool arguments")

/**
 * One function the model may call inside ChatLoop. Tools are read-only or create pending proposals (arch 1.4); none writes a
 * plan, a rule or a record directly. [run] gets the parsed arguments and returns the JSON the model reads; it throws
 * [BadToolArgs] for arguments it cannot use. Every number in the output is computed by code.
 */
interface AgencyTool {
    val spec: ToolSpec
    suspend fun run(args: JsonObject): JsonElement
}

/**
 * The tools offered in one ChatLoop round. [execute] never throws (except cancellation): an unknown name, arguments that are
 * not one JSON object, a [BadToolArgs] and any other failure all come back as a tool result with `isError`, holding only the
 * error class and a reason, never a stack.
 */
class ToolRegistry(tools: List<AgencyTool>) {
    private val byName: Map<String, AgencyTool> = tools.associateBy { it.spec.name }

    /** The specs in a fixed order, so `tools` stay byte-identical on every step of a round. */
    val specs: List<ToolSpec> = tools.map { it.spec }

    suspend fun execute(call: ToolCall): Turn.ToolResult {
        val tool = byName[call.name] ?: return error(call, ToolErrorKind.UNKNOWN_TOOL, reason = "沒有這個工具：只能用 ${byName.keys.joinToString("、")}")
        val args = runCatching { Json.parseToJsonElement(call.arguments.ifBlank { "{}" }) as? JsonObject }.getOrNull()
            ?: return error(call, ToolErrorKind.BAD_ARGS, field = "整體", reason = "參數要是一個 JSON 物件")
        return try {
            Turn.ToolResult(call.id, tool.run(args).toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: BadToolArgs) {
            error(call, ToolErrorKind.BAD_ARGS, e.field, e.reason)
        } catch (_: Exception) {
            error(call, ToolErrorKind.FAILED, reason = "這次讀不到資料")
        }
    }

    private fun error(call: ToolCall, kind: ToolErrorKind, field: String? = null, reason: String): Turn.ToolResult =
        Turn.ToolResult(
            call.id,
            buildJsonObject {
                put("error", kind.name)
                field?.let { put("field", it) }
                put("reason", reason)
            }.toString(),
            isError = true,
        )

    companion object {
        /** A string argument of [args], or [BadToolArgs]. */
        fun str(args: JsonObject, key: String): String =
            (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw BadToolArgs(key, "要有 $key（字串）")
    }
}
