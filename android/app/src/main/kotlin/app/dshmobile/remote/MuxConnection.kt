package app.dshmobile.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

enum class MuxState { CONNECTING, OPEN, RECONNECTING, CLOSED }

/** What a [MuxConnection.stream] collector sees. */
sealed interface StreamSignal {
    data class Value(val value: JsonElement?) : StreamSignal

    /** The socket was replaced and the stream is being opened again: the next [Value] is a fresh baseline or snapshot. */
    data object Reopened : StreamSignal

    /** dsh ended the stream with an error; the flow completes after this. [code] is a [DshErrors.safeCode]. */
    data class Failed(val code: String, val message: String) : StreamSignal
}

/**
 * One `/api/remote.mux` WebSocket to one dsh instance, shared by any number of streams.
 *
 * - [stream] is cold: collecting sends `open` with a fresh id (`s1`, `s2`, …, never reused here), cancelling the
 *   collector sends `cancel`, dsh's `end` completes the flow and dsh's `error` emits [StreamSignal.Failed] and completes it.
 * - When the socket goes away the state turns [MuxState.RECONNECTING] and a new socket is tried after [backoffMs]
 *   (the last value repeats; the list restarts after every successful open). An upgrade answered 401 calls [relogin]
 *   before the next try; every other try first runs [beforeConnect]. Once open again, every stream still being
 *   collected gets [StreamSignal.Reopened] and is opened again under its original id. A new dsh instance gets a new
 *   [MuxConnection]; [close] the old one.
 * - Every listener callback goes through one queue that a single coroutine on [Dispatchers.IO] drains, so frames are
 *   parsed off the socket's thread and a stream never sees an old socket's frame after its [StreamSignal.Reopened].
 *
 * Nothing here logs, and [lastFailure] only names an exception class or an HTTP status: never a URL, token or frame.
 */
class MuxConnection(
    private val open: (WebSocketListener) -> WebSocket,
    private val relogin: suspend () -> Unit,
    private val scope: CoroutineScope,
    private val backoffMs: List<Long> = BACKOFF_MS,
    /** Runs before each connection try that is not answering a 401 (DshRemote: log in if nobody has yet). */
    private val beforeConnect: suspend () -> Unit = {},
) {
    private val _state = MutableStateFlow(MuxState.CONNECTING)
    val state: StateFlow<MuxState> = _state.asStateFlow()

    /** Why the last connection try or socket failed, in safe words; for diagnostics and tests. */
    @Volatile internal var lastFailure: String? = null
        private set

    private val lock = Any()
    private val streams = LinkedHashMap<String, Live>()
    private var nextId = 0L
    private var current: Attempt? = null
    private var socket: WebSocket? = null
    private var closed = false

    private val inbox = Channel<Inbound>(Channel.UNLIMITED)
    private val connector: Job

    init {
        require(backoffMs.isNotEmpty() && backoffMs.all { it >= 0 }) { "backoffMs" }
        scope.launch(Dispatchers.IO) { for (event in inbox) handle(event) }
        connector = scope.launch(Dispatchers.IO) { connectLoop() }
    }

    fun stream(endpoint: String, args: JsonObject = JsonObject(emptyMap())): Flow<StreamSignal> = flow {
        val live = register(endpoint, args) ?: return@flow
        try {
            for (signal in live.channel) emit(signal)
        } finally {
            unregister(live)
        }
    }

    /** Drops the socket as if the network had (no close handshake), to exercise the reconnect path. */
    internal fun dropForTest() {
        synchronized(lock) { socket }?.cancel()
    }

    /** Stops for good: no more tries, the socket is closed and every stream's flow completes quietly. */
    fun close() {
        val ending: List<Live>
        val attempt: Attempt?
        val open: WebSocket?
        synchronized(lock) {
            if (closed) return
            closed = true
            _state.value = MuxState.CLOSED
            ending = streams.values.toList()
            streams.clear()
            attempt = current
            open = socket
            current = null
            socket = null
        }
        connector.cancel()
        if (open != null) open.close(NORMAL_CLOSURE, null) else attempt?.socket?.cancel()
        ending.forEach { it.channel.close() }
        inbox.close()
    }

    private fun register(endpoint: String, args: JsonObject): Live? = synchronized(lock) {
        if (closed) return null
        val live = Live("s${++nextId}", endpoint, args)
        streams[live.id] = live
        socket?.let { ws ->
            live.opened = true
            ws.send(MuxFrames.open(live.id, endpoint, args))
        }
        live
    }

    private fun unregister(live: Live) = synchronized(lock) {
        if (streams[live.id] !== live) return // dsh ended it, or we closed
        streams.remove(live.id)
        if (live.opened) socket?.send(MuxFrames.cancel(live.id))
    }

    private suspend fun connectLoop() {
        var failures = 0
        var first = true
        var needRelogin = false
        while (true) {
            if (!first) delay(backoffMs[minOf(failures, backoffMs.lastIndex)])
            first = false
            try {
                if (needRelogin) relogin() else beforeConnect()
                needRelogin = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed("login failed (${e.javaClass.simpleName})")
                failures++
                continue
            }
            val attempt = Attempt()
            synchronized(lock) {
                if (closed) return
                current = attempt
            }
            try {
                attempt.socket = open(attempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed("could not start the upgrade (${e.javaClass.simpleName})")
                failures++
                continue
            }
            val status = attempt.gone.await()
            if (attempt.opened) failures = 0 else failures++
            needRelogin = status == HTTP_UNAUTHORIZED
        }
    }

    private fun failed(why: String) {
        lastFailure = why
        synchronized(lock) { if (!closed) _state.value = MuxState.RECONNECTING }
    }

    private fun handle(event: Inbound) {
        when (event) {
            is Inbound.Opened -> synchronized(lock) {
                if (closed || event.attempt !== current) {
                    event.ws.cancel()
                    return
                }
                event.attempt.opened = true
                socket = event.ws
                _state.value = MuxState.OPEN
                for (live in streams.values) {
                    if (live.opened) live.channel.trySend(StreamSignal.Reopened)
                    live.opened = true
                    event.ws.send(MuxFrames.open(live.id, live.endpoint, live.args))
                }
            }
            is Inbound.Text -> {
                if (event.attempt !== synchronized(lock) { current }) return
                val frame = MuxFrames.parse(event.text) ?: return
                synchronized(lock) {
                    val live = streams[frame.streamId] ?: return
                    when (frame) {
                        is ServerFrame.Item -> live.channel.trySend(StreamSignal.Value(frame.value))
                        is ServerFrame.Error -> {
                            streams.remove(live.id)
                            live.channel.trySend(StreamSignal.Failed(frame.code, "dsh ${live.endpoint}: ${frame.code}"))
                            live.channel.close()
                        }
                        is ServerFrame.End -> {
                            streams.remove(live.id)
                            live.channel.close()
                        }
                    }
                }
            }
            is Inbound.Gone -> {
                synchronized(lock) {
                    if (event.attempt === current) {
                        current = null
                        socket = null
                        if (!closed) _state.value = MuxState.RECONNECTING
                    }
                }
                if (!event.attempt.gone.isCompleted) lastFailure = event.why
                event.attempt.gone.complete(event.status)
            }
        }
    }

    /** One stream being collected. [opened]: an `open` for it went out on some socket. Guarded by [lock]. */
    private class Live(val id: String, val endpoint: String, val args: JsonObject) {
        val channel = Channel<StreamSignal>(Channel.UNLIMITED)
        var opened = false
    }

    /** One connection try; its callbacks are only queued here and handled in order by [handle]. */
    private inner class Attempt : WebSocketListener() {
        val gone = CompletableDeferred<Int?>()
        @Volatile var opened = false
        @Volatile var socket: WebSocket? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            inbox.trySend(Inbound.Opened(this, webSocket))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            inbox.trySend(Inbound.Text(this, text))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
            inbox.trySend(Inbound.Gone(this, null, "dsh closed the socket ($code)"))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            inbox.trySend(Inbound.Gone(this, null, "dsh closed the socket ($code)"))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val why = if (response != null) "upgrade refused: HTTP ${response.code}" else "socket failed (${t.javaClass.simpleName})"
            inbox.trySend(Inbound.Gone(this, response?.code, why))
        }
    }

    private sealed interface Inbound {
        val attempt: Attempt

        class Opened(override val attempt: Attempt, val ws: WebSocket) : Inbound
        class Text(override val attempt: Attempt, val text: String) : Inbound
        class Gone(override val attempt: Attempt, val status: Int?, val why: String) : Inbound
    }

    companion object {
        val BACKOFF_MS = listOf(250L, 500L, 1_000L, 2_000L, 4_000L, 5_000L)
        private const val NORMAL_CLOSURE = 1000
        private const val HTTP_UNAUTHORIZED = 401
    }
}
