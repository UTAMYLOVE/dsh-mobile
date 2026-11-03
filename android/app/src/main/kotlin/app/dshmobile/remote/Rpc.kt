package app.dshmobile.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * dsh 0.2.1-rc.5's unary RPC envelope (`POST /api/<namespace>/<method>`, docs/dsh-ui-protocol.md §1.2):
 * `{"type":"client-request","rpcId","method","payload":{"args":{…named args…}}}` →
 * `{"type":"server-response","rpcId","result":{"ok":true,"value"}|{"ok":false,"error":{"code","message","details"}}}`.
 */
object RpcEnvelope {
    fun request(method: String, args: JsonObject, rpcId: String): String = buildJsonObject {
        put("type", "client-request")
        put("rpcId", rpcId)
        put("method", method)
        put("payload", buildJsonObject { put("args", args) })
    }.toString()

    /**
     * Never throws: anything that is not a result object is [RpcResult.Err] `unexpected-answer`. A dsh error code is passed through
     * [DshErrors.safeCode]; its free-text message is kept as dsh sent it and must not be shown or logged as is.
     */
    fun parse(body: String): RpcResult {
        val root = try {
            Json.parseToJsonElement(body)
        } catch (_: IllegalArgumentException) { // SerializationException
            return UNEXPECTED
        }
        val result = (root as? JsonObject)?.get("result") as? JsonObject ?: return UNEXPECTED
        val ok = (result["ok"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return UNEXPECTED
        if (ok) return RpcResult.Ok(result["value"] ?: JsonNull)
        val error = result["error"] as? JsonObject
        return RpcResult.Err(DshErrors.safeCode(error.string("code")), error.string("message").orEmpty())
    }

    private fun JsonObject?.string(key: String): String? = (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private val UNEXPECTED = RpcResult.Err(DshErrors.UNEXPECTED_ANSWER, "dsh answered something that is not an RPC result")
}

sealed interface RpcResult {
    data class Ok(val value: JsonElement) : RpcResult
    data class Err(val code: String, val message: String) : RpcResult
}

/** dsh answered `ok:false` (or something unreadable: [code] `unexpected-answer`). The message names only the method and the safe code. */
class DshRpcException(val code: String, message: String) : java.io.IOException(message)

object DshErrors {
    const val REFUSED = "refused"
    const val UNEXPECTED_ANSWER = "unexpected-answer"

    /** dsh's codes look like `workspace/invalid-path`; anything else (a URL, a sentence, nothing) is not echoed. */
    private val CODE = Regex("[A-Za-z0-9/_.-]{1,80}")

    fun safeCode(code: String?): String = code?.takeIf { CODE.matches(it) } ?: REFUSED

    /**
     * Codes that will not change by asking again (the session is gone, the request itself is wrong): a stream that ends with one
     * is not reopened on its own. Everything else (dsh internal errors, cancellations, busy) is treated as transient.
     */
    private val PERMANENT = setOf(
        "session/not-found", "subagent/not-found", "subagent/unauthorized", "subagent/catalog-diagnostic", "workspace/not-found",
        "gateway/bad-request", "gateway/protocol", "session/migration-required", "remote-access/denied",
    )

    fun isPermanent(code: String?): Boolean = code in PERMANENT
}
