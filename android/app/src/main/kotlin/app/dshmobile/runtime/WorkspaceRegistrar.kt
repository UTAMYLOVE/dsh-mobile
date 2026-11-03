package app.dshmobile.runtime

import app.dshmobile.log.ErrorLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import java.util.UUID

/**
 * Makes sure dsh lists the app's one workspace ([path], guest `/root/workspace`), so the user can pick it: dsh's own folder
 * picker does not work in the WebView. Runs after every healthy start.
 *
 * dsh 0.2.1-rc.5 has no request/response RPC that lists workspaces (the list only streams through `workspace/follow` on the
 * WebSocket carrier; over HTTP it answers `gateway/signature-invalid`). `workspace/create` is the check itself: it is idempotent
 * by path and answers `created: true` when it added the workspace, `created: false` with the existing registration otherwise
 * (verified on a scratch dsh, see docs/sdd-ledger/followup-1-report.md). So one call per start; dsh never gets a duplicate.
 *
 * Never throws (only cancellation passes through): a failure is one ErrorLog line with a status code, dsh's error code or an
 * exception class, never the launch URL or its token, and does not affect the start.
 */
class WorkspaceRegistrar(
    private val http: OkHttpClient,
    private val errorLog: ErrorLog,
    private val path: String = AppPaths.GUEST_WORKSPACE,
    private val timeoutMs: Long = 15_000,
) {
    enum class Outcome { REGISTERED, ALREADY_LISTED, FAILED }

    suspend fun ensure(launch: LaunchUrl): Outcome {
        val result = try {
            withTimeoutOrNull(timeoutMs) { create(DshClient(http, launch)) } ?: Failure("timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Failure(e.javaClass.simpleName)
        }
        return when (result) {
            is Failure -> {
                errorLog.append(LOG_TAG, "could not register $path: ${result.reason}")
                Outcome.FAILED
            }
            true -> {
                errorLog.append(LOG_TAG, "registered $path in dsh")
                Outcome.REGISTERED
            }
            else -> Outcome.ALREADY_LISTED
        }
    }

    private class Failure(val reason: String)

    /** True: dsh added it now; false: it was already listed; [Failure] otherwise. */
    private suspend fun create(client: DshClient): Any {
        client.login()
        val envelope = buildJsonObject {
            put("type", "client-request")
            put("rpcId", "dshm-${UUID.randomUUID()}")
            put("method", METHOD)
            putJsonObject("payload") { putJsonObject("args") { putJsonObject("request") { put("path", path) } } }
        }
        val response = client.postJsonForText("/api/$METHOD", envelope.toString())
        if (response.code != 200) return Failure("HTTP ${response.code}")
        val result = try {
            (Json.parseToJsonElement(response.body) as? JsonObject)?.get("result") as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: return Failure("unexpected answer")
        if ((result["ok"] as? JsonPrimitive)?.content != "true") {
            val code = ((result["error"] as? JsonObject)?.get("code") as? JsonPrimitive)?.content
            return Failure(code?.takeIf { CODE.matches(it) } ?: "refused")
        }
        val created = ((result["value"] as? JsonObject)?.get("created") as? JsonPrimitive)?.content
            ?: return Failure("unexpected answer")
        return created == "true"
    }

    private companion object {
        const val METHOD = "workspace/create"
        const val LOG_TAG = "workspace"
        /** dsh's error codes look like `workspace/invalid-path`; anything else is not echoed into the log. */
        val CODE = Regex("[A-Za-z0-9/_.-]{1,80}")
    }
}
