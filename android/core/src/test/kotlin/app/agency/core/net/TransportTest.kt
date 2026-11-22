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
    }
}
