package app.agency.core.net

import app.agency.core.llm.ChatResult
import app.agency.core.llm.Fail
import app.agency.core.llm.LlmTestKit
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    /** Runs [block] on [http]'s dispatcher and returns every exception that reached a thread's uncaught handler. */
    private fun uncaught(http: okhttp3.OkHttpClient, block: suspend () -> Unit): List<Throwable> {
        val died = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> died += e }
        try {
            runBlocking { block() }
            http.dispatcher.executorService.shutdown()
            http.dispatcher.executorService.awaitTermination(2, TimeUnit.SECONDS)
            Thread.sleep(500) // the pool reports termination before a dying worker reaches its uncaught handler
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
        return died
    }

    @Test fun anAllowlistThatThrowsFailsClosedAndKillsNoThread() {
        val http = HttpFactory.create(NetworkGate({ throw IllegalStateException("settings not ready") }))
        val died = uncaught(http) {
            assertTrue(Transport(http) { }.postJson(kit.base.resolve("/x")!!, emptyMap(), "{}") is HttpOutcome.Blocked)
        }
        assertEquals(emptyList<Throwable>(), died)
        assertEquals(0, kit.server.requestCount)
    }

    @Test fun aRuntimeExceptionInTheCallChainIsOfflineAndKillsNoThread() {
        // A platform RuntimeException on the way to the socket (as a SecurityException from DNS or TLS would be).
        val http = HttpFactory.create(kit.gate).newBuilder()
            .addNetworkInterceptor { throw SecurityException("network access denied") }.build()
        val died = uncaught(http) {
            assertEquals(HttpOutcome.Offline, Transport(http) { }.postJson(kit.base.resolve("/x")!!, emptyMap(), "{}"))
        }
        assertEquals(emptyList<Throwable>(), died)
    }
}
