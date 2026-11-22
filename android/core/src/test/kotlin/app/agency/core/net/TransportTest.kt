package app.agency.core.net

import app.agency.core.llm.ChatResult
import app.agency.core.llm.Fail
import app.agency.core.llm.LlmTestKit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportTest {
    private val kit = LlmTestKit()

    @After fun tearDown() = kit.close()

    private fun silent() = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)

    @Test fun aTimeoutIsRetriedOnceAfterTwoSecondsThenOffline() = runBlocking {
        val sleeps = mutableListOf<Long>()
        val transport = Transport(kit.http.newBuilder().readTimeout(200, TimeUnit.MILLISECONDS).build()) { sleeps += it }
        kit.server.enqueue(silent())
        kit.server.enqueue(silent())
        assertEquals(HttpOutcome.Offline, transport.postJson(kit.base.resolve("/x")!!, emptyMap(), "{}"))
        assertEquals(2, kit.server.requestCount)
        assertEquals(listOf(2_000L), sleeps)
    }

    /**
     * A connection dropped with no answer (what the e2e offline test does) is Offline at once, not a bad answer and not retried,
     * on a pooled connection and on a fresh one alike, through the app's own client.
     */
    @Test fun aConnectionDroppedWithNoAnswerIsOfflineAtOnce() = runBlocking {
        val sleeps = mutableListOf<Long>()
        val transport = Transport(HttpFactory.create(kit.gate)) { sleeps += it }
        kit.server.enqueue(MockResponse().setBody("{}"))
        assertEquals(HttpOutcome.Response(200, "{}"), transport.postJson(kit.base.resolve("/x")!!, emptyMap(), "{}"))
        repeat(4) { kit.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
        assertEquals(HttpOutcome.Offline, transport.postJson(kit.base.resolve("/x")!!, emptyMap(), "{}"))
        assertEquals(HttpOutcome.Offline, Transport(HttpFactory.create(kit.gate)) { sleeps += it }.postJson(kit.base.resolve("/x")!!, emptyMap(), "{}"))
        assertEquals(emptyList<Long>(), sleeps)
    }

    @Test fun aBodyOverTheCapIsABadResponse() = runBlocking {
        // A well-formed reply, only too long: without the cap it would be read whole and come back Ok (and metered).
        val text = "好".repeat((Transport.MAX_BODY_BYTES / 3 + 1).toInt())
        kit.enqueue(200, LlmTestKit.completion("""{"role":"assistant","content":"$text"}"""))
        assertEquals(ChatResult.Failed(Fail.BAD_RESPONSE), kit.deepseek().chat(LlmTestKit.user))
        assertEquals(1, kit.server.requestCount)
        assertTrue(kit.recorded.isEmpty())
    }

    @Test fun cancellingTheCallerCancelsTheHttpCall() = runBlocking {
        kit.server.enqueue(silent()) // would hold the call until the 60 s read timeout, then the retry
        kit.server.enqueue(silent())
        val call = async(Dispatchers.Default) { kit.transport.postJson(kit.base.resolve("/x")!!, emptyMap(), "{}") }
        kit.take() // the request reached the server and is now waiting for an answer
        val started = System.nanoTime()
        withTimeout(5_000) { call.cancelAndJoin() }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5_000)
        assertTrue(call.isCancelled)
        assertEquals(1, kit.server.requestCount)
        assertEquals(emptyList<Long>(), kit.sleeps)
        // The OkHttp call itself ended, not only the coroutine: without call.cancel() it stays blocked on the silent socket.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (kit.http.dispatcher.runningCallsCount() > 0 && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(0, kit.http.dispatcher.runningCallsCount())
    }

    /**
     * Runs [block] with [base] on a dispatcher whose pool records every throwable a call lets escape on its worker thread
     * (OkHttp rethrows it there, which would end the process), and returns them. ThreadPoolExecutor runs afterExecute on the
     * worker before the worker exits, so once the pool has terminated every escape is recorded: no wait, no global handler.
     */
    private fun escaped(base: OkHttpClient, block: suspend (OkHttpClient) -> Unit): List<Throwable> {
        val died = CopyOnWriteArrayList<Throwable>()
        val pool = object : ThreadPoolExecutor(0, Int.MAX_VALUE, 60, TimeUnit.SECONDS, SynchronousQueue()) {
            override fun afterExecute(r: Runnable?, t: Throwable?) {
                if (t != null) died += t
            }
        }
        val http = base.newBuilder().dispatcher(Dispatcher(pool)).build()
        try {
            runBlocking { block(http) }
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
        return died
    }

    @Test fun anAllowlistThatThrowsFailsClosedAndKillsNoThread() {
        val died = escaped(HttpFactory.create(NetworkGate({ throw IllegalStateException("settings not ready") }))) { http ->
            assertTrue(Transport(http) { }.postJson(kit.base.resolve("/x")!!, emptyMap(), "{}") is HttpOutcome.Blocked)
        }
        assertEquals(emptyList<Throwable>(), died)
        assertEquals(0, kit.server.requestCount)
    }

    @Test fun aRuntimeExceptionInTheCallChainIsOfflineAndKillsNoThread() {
        // A platform RuntimeException on the way to the socket (as a SecurityException from DNS or TLS would be).
        val base = HttpFactory.create(kit.gate).newBuilder()
            .addNetworkInterceptor { throw SecurityException("network access denied") }.build()
        val died = escaped(base) { http ->
            assertEquals(HttpOutcome.Offline, Transport(http) { }.postJson(kit.base.resolve("/x")!!, emptyMap(), "{}"))
        }
        assertEquals(emptyList<Throwable>(), died)
    }

    /** Review FIX-WAVE3 W4: a provider class that fails to load is Offline too, not a dead thread and a crash loop. */
    @Test fun aLinkageErrorInTheCallChainIsOfflineAndKillsNoThread() {
        val base = HttpFactory.create(kit.gate).newBuilder()
            .addNetworkInterceptor { throw NoClassDefFoundError("a TLS provider class") }.build()
        val died = escaped(base) { http ->
            assertEquals(HttpOutcome.Offline, Transport(http) { }.postJson(kit.base.resolve("/x")!!, emptyMap(), "{}"))
        }
        assertEquals(emptyList<Throwable>(), died)
    }

    /**
     * Review FIX-WAVE3 W3: the capture sees an escape at once (so the empty lists above mean none happened), and an Error that
     * is not a LinkageError is left to end the process, as an out-of-memory one should.
     */
    @Test fun anOutOfMemoryErrorStillEscapesAndTheCaptureSeesIt() {
        val base = HttpFactory.create(kit.gate).newBuilder()
            .addNetworkInterceptor { throw OutOfMemoryError("invented") }.build()
        val died = escaped(base) { http ->
            // OkHttp reports the call failed before it rethrows, so the caller is not left waiting.
            assertEquals(HttpOutcome.Offline, Transport(http) { }.postJson(kit.base.resolve("/x")!!, emptyMap(), "{}"))
        }
        assertEquals(listOf(OutOfMemoryError::class), died.map { it::class })
    }
}
