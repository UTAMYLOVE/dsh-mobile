package app.dshmobile.runtime

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HealthCheckTest {
    private val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    private val servers = mutableListOf<FakeDshServer>()

    private fun fake(vararg args: Pair<String, Int>, tokenValid: () -> Boolean = { true }) =
        FakeDshServer(healthStatus = mapOf(*args), tokenValid = tokenValid).also { servers.add(it) }

    @After fun cleanup() { servers.forEach { it.close() } }

    @Test fun bothHealthRoutesOkAfterLoginIsTrue() = runBlocking {
        val dsh = fake("/dshm/health/memory" to 200, "/dshm/health/notify" to 200)
        assertTrue(HealthCheck(http, timeoutMs = 5_000).check(dsh.launch))
        val paths = dsh.requests.map { it.path }
        assertEquals("/?token=good", paths.first())
        assertTrue("/dshm/health/memory" in paths && "/dshm/health/notify" in paths)
        assertTrue("health routes must be reached with the login cookie", dsh.unauthenticated.isEmpty())
    }

    @Test fun missingNotifyRouteIsFalseAfterTimeout() = runBlocking {
        val dsh = fake("/dshm/health/memory" to 200, "/dshm/health/notify" to 404)
        assertFalse(HealthCheck(http, timeoutMs = 1_200, retryIntervalMs = 100).check(dsh.launch))
    }

    @Test fun rejectedLoginIsFalseAndNeverReachesTheRoutes() = runBlocking {
        val dsh = fake("/dshm/health/memory" to 200, "/dshm/health/notify" to 200, tokenValid = { false })
        assertFalse(HealthCheck(http, timeoutMs = 800, retryIntervalMs = 100).check(dsh.launch))
        assertTrue(dsh.requests.none { it.path!!.startsWith("/dshm") })
    }

    @Test fun wrongBodyIsNotHealthy() = runBlocking {
        val dsh = FakeDshServer(healthBody = """{"ok":false}""").also { servers.add(it) }
        assertFalse(HealthCheck(http, timeoutMs = 800, retryIntervalMs = 100).check(dsh.launch))
    }

    @Test fun retriesUntilDshIsUp() = runBlocking {
        val attempts = AtomicInteger()
        // The token is refused for the first two attempts, as dsh would while still booting.
        val dsh = fake("/dshm/health/memory" to 200, "/dshm/health/notify" to 200, tokenValid = { attempts.incrementAndGet() > 2 })
        assertTrue(HealthCheck(http, timeoutMs = 5_000, retryIntervalMs = 50).check(dsh.launch))
        assertTrue(attempts.get() >= 3)
    }

    @Test fun nothingListeningIsFalse() = runBlocking {
        val port = FakeDshServer().use { it.server.port } // closed again right away
        assertFalse(HealthCheck(http, timeoutMs = 600, retryIntervalMs = 100).check(LaunchUrl("http://127.0.0.1:$port/?token=good", port)))
    }
}
