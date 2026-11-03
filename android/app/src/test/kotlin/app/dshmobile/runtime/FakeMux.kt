package app.dshmobile.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * dsh's `/api/remote.mux` for [FakeDshServer]: records every client frame, checks the exact key sets and the
 * no-duplicate-live-id rule the way dsh does (a violation closes that socket), and lets a test push server frames.
 */
class FakeMux {
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val live = ConcurrentHashMap<WebSocket, MutableSet<String>>()
    private val frames = CopyOnWriteArrayList<JsonObject>()

    /** Every client frame, in arrival order, across sockets. */
    val received: List<JsonObject> get() = frames.toList()

    /** Frames dsh would have refused (wrong key set, duplicate live id); each closed its socket. */
    val violations = CopyOnWriteArrayList<String>()

    /** Upgrade requests that reached the mux (rejected ones included). */
    val upgrades = AtomicInteger()

    /** Answer the next upgrade with this status instead of 101. */
    @Volatile var rejectNextUpgradeWith: Int? = null

    /**
     * When set, an upgrade request is not answered until this completes (the client stays CONNECTING); bounded by
     * [UPGRADE_GATE_MAX_MS] so a test that forgets to complete it cannot hang the server's shutdown.
     */
    @Volatile var upgradeGate: CompletableDeferred<Unit>? = null

    /** Called for every client `open`, on the server's reader thread. */
    @Volatile var onOpen: (streamId: String, endpoint: String, args: JsonObject) -> Unit = { _, _, _ -> }

    val openSockets: Int get() = sockets.size

    /** Sends a raw server frame on every open socket. */
    fun send(json: String) {
        val frame = try { Json.parseToJsonElement(json) as? JsonObject } catch (_: IllegalArgumentException) { null }
        val type = (frame?.get("type") as? JsonPrimitive)?.content
        val id = (frame?.get("streamId") as? JsonPrimitive)?.content
        sockets.forEach { ws ->
            if (id != null && (type == "end" || type == "error")) live[ws]?.remove(id)
            ws.send(json)
        }
    }

    /** `{"type":"item","streamId":…,"value":<value>}`; [value] is JSON text. */
    fun sendItem(streamId: String, value: String) =
        send("""{"type":"item","streamId":${JsonPrimitive(streamId)},"value":$value}""")

    fun sendEnd(streamId: String) = send(buildJsonObject { put("type", "end"); put("streamId", streamId) }.toString())

    fun sendError(streamId: String, code: String, message: String) = send(buildJsonObject {
        put("type", "error")
        put("streamId", streamId)
        put("error", buildJsonObject { put("code", code); put("message", message); put("details", buildJsonObject {}) })
    }.toString())

    /**
     * Closes every socket from the server side (1001 going away). MockWebServer's server sockets cannot be cut without
     * a close frame; [app.dshmobile.remote.MuxConnection.dropForTest] covers the abrupt client-side drop.
     */
    fun closeAll() {
        val all = sockets.toList()
        sockets.clear()
        all.forEach { live.remove(it); it.close(1001, "going away") }
    }

    /** Stream ids of client `open` frames for [endpoint], in order (a reopen repeats its id). */
    fun opened(endpoint: String): List<String> =
        received.filter { it.str("type") == "open" && it.str("endpoint") == endpoint }.mapNotNull { it.str("streamId") }

    internal fun upgrade(): MockResponse {
        upgrades.incrementAndGet()
        upgradeGate?.let { gate -> runBlocking { withTimeoutOrNull(UPGRADE_GATE_MAX_MS) { gate.await() } } }
        rejectNextUpgradeWith?.let {
            rejectNextUpgradeWith = null
            return MockResponse().setResponseCode(it)
        }
        return MockResponse().withWebSocketUpgrade(ServerSide())
    }

    private inner class ServerSide : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            live[webSocket] = Collections.synchronizedSet(HashSet())
            sockets.add(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = try { Json.parseToJsonElement(text) as? JsonObject } catch (_: IllegalArgumentException) { null }
            if (frame == null) return refuse(webSocket, "not a JSON object: $text")
            frames.add(frame)
            val type = frame.str("type")
            val id = frame.str("streamId")
            val expected = when (type) {
                "open" -> setOf("type", "streamId", "endpoint", "payload")
                "item" -> if ("value" in frame) setOf("type", "streamId", "value") else setOf("type", "streamId")
                "end", "cancel" -> setOf("type", "streamId")
                else -> return refuse(webSocket, "unknown frame type $type")
            }
            if (frame.keys != expected || id.isNullOrEmpty()) return refuse(webSocket, "bad key set ${frame.keys} for $type")
            val ids = live[webSocket] ?: return
            when (type) {
                "open" -> {
                    val payload = frame["payload"] as? JsonObject
                    val args = payload?.get("args") as? JsonObject
                    if (payload?.keys != setOf("args") || args == null) return refuse(webSocket, "bad payload ${frame["payload"]}")
                    if (!ids.add(id)) return refuse(webSocket, "duplicate live stream $id")
                    onOpen(id, frame.str("endpoint").orEmpty(), args)
                }
                "cancel" -> ids.remove(id)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            forget(webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = forget(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = forget(webSocket)

        private fun refuse(webSocket: WebSocket, why: String) {
            violations.add(why)
            forget(webSocket)
            webSocket.close(1008, "protocol violation")
        }

        private fun forget(webSocket: WebSocket) {
            sockets.remove(webSocket)
            live.remove(webSocket)
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        const val UPGRADE_GATE_MAX_MS = 30_000L
    }
}

/** Polls [cond] every 10 ms until it holds; fails with [what] after [timeoutMs]. */
suspend fun eventually(what: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (!cond()) {
        if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for $what")
        delay(10)
    }
}
