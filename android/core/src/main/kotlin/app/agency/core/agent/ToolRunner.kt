package app.agency.core.agent

import app.agency.core.llm.ToolCall
import app.agency.core.llm.Turn
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Runs the tool calls of one model step. Nothing a call does can break the loop: an unknown tool, arguments that are not a JSON
 * object, a tool that throws, all come back to the model as an error result it can read and recover from.
 */
internal object ToolRunner {
    data class Ran(val result: Turn.ToolResult, val out: ToolOut?)

    suspend fun run(calls: List<ToolCall>, tools: List<Tool>, ctx: ToolCtx): List<Ran> = calls.map { c -> one(c, tools, ctx) }

    private suspend fun one(c: ToolCall, tools: List<Tool>, ctx: ToolCtx): Ran {
        val tool = tools.firstOrNull { it.spec.name == c.name }
            ?: return error(c, "unknown tool: ${c.name}")
        val args = runCatching { Json.parseToJsonElement(c.arguments.ifBlank { "{}" }) as? JsonObject }.getOrNull()
            ?: return error(c, "arguments must be a JSON object")
        val out = try {
            tool.run(args, ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolOut.error("tool failed: ${e.message ?: e::class.simpleName}")
        }
        return Ran(Turn.ToolResult(c.id, out.facts.toString(), isError = !out.ok), out)
    }

    private fun error(c: ToolCall, message: String): Ran {
        val out = ToolOut.error(message)
        return Ran(Turn.ToolResult(c.id, out.facts.toString(), isError = true), out)
    }
}
