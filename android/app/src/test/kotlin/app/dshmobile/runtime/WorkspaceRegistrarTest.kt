package app.dshmobile.runtime

import app.dshmobile.log.ErrorLog
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class WorkspaceRegistrarTest {
    private val filesDir = Files.createTempDirectory("wsreg").toFile()
    private val errorLog = ErrorLog(filesDir)
    private val rpcCalls = CopyOnWriteArrayList<Pair<String, String>>() // path, body
    private val servers = mutableListOf<FakeDshServer>()

    @After fun cleanup() {
        servers.forEach { it.close() }
        filesDir.deleteRecursively()
    }

    private fun dsh(tokenValid: Boolean = true, answer: (RecordedRequest) -> MockResponse): FakeDshServer =
        FakeDshServer(tokenValid = { tokenValid }, rpc = { r -> rpcCalls.add(r.path.orEmpty() to r.body.readUtf8()); answer(r) })
            .also { servers.add(it) }

    private fun created(created: Boolean) = MockResponse().setResponseCode(200).setBody(
        """{"type":"server-response","rpcId":"x","result":{"ok":true,"value":{"workspace":{"workspaceId":"49b9645e","path":"/root/workspace",""" +
            """"title":"workspace","sessionIds":[],"createdAt":"2026-11-03T05:20:54.176Z","updatedAt":"2026-11-03T05:20:54.176Z"},"created":$created}}}""",
    )

    private fun registrar(timeoutMs: Long = 10_000) = WorkspaceRegistrar(OkHttpClient(), errorLog, timeoutMs = timeoutMs)
    private fun log(): String = errorLog.file().takeIf { it.isFile }?.readText().orEmpty()

    @Test fun aWorkspaceDshDoesNotListYetIsCreatedThroughTheRpc() = runBlocking {
        val server = dsh { created(true) }

        val outcome = registrar().ensure(server.launch)

        assertEquals(WorkspaceRegistrar.Outcome.REGISTERED, outcome)
        val (path, body) = rpcCalls.single()
        assertEquals("/api/workspace/create", path)
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("client-request", json["type"]!!.jsonPrimitive.content)
        assertEquals("workspace/create", json["method"]!!.jsonPrimitive.content)
        assertTrue("rpcId", json["rpcId"]!!.jsonPrimitive.content.isNotBlank())
        val request = json["payload"]!!.jsonObject["args"]!!.jsonObject["request"] as JsonObject
        assertEquals(mapOf("path" to "/root/workspace"), request.mapValues { it.value.jsonPrimitive.content })
        assertTrue(log(), log().contains("[workspace] registered /root/workspace in dsh"))
    }

    @Test fun aWorkspaceDshAlreadyListsIsNotCreatedAgainAndNotLogged() = runBlocking {
        val server = dsh { created(false) }

        assertEquals(WorkspaceRegistrar.Outcome.ALREADY_LISTED, registrar().ensure(server.launch))
        assertEquals(1, rpcCalls.size)
        assertFalse(log(), log().contains("[workspace]"))
    }

    @Test fun aRefusalIsLoggedWithDshsErrorCodeOnly() = runBlocking {
        val server = dsh {
            MockResponse().setResponseCode(200).setBody(
                """{"type":"server-response","rpcId":"x","result":{"ok":false,"error":{"code":"workspace/invalid-path","message":"secret-ish detail","details":{"path":"/root/workspace"}}}}""",
            )
        }

        assertEquals(WorkspaceRegistrar.Outcome.FAILED, registrar().ensure(server.launch))
        assertTrue(log(), log().contains("[workspace] could not register /root/workspace: workspace/invalid-path"))
        assertFalse(log().contains("secret-ish detail"))
    }

    @Test fun anHttpErrorIsLoggedNotThrown() = runBlocking {
        val server = dsh { MockResponse().setResponseCode(500).setBody("boom") }

        assertEquals(WorkspaceRegistrar.Outcome.FAILED, registrar().ensure(server.launch))
        assertTrue(log(), log().contains("could not register /root/workspace: HTTP 500"))
    }

    @Test fun anAnswerThatIsNotDshsEnvelopeIsAFailure() = runBlocking {
        val server = dsh { MockResponse().setResponseCode(200).setBody("<html>not json</html>") }

        assertEquals(WorkspaceRegistrar.Outcome.FAILED, registrar().ensure(server.launch))
        assertTrue(log(), log().contains("could not register /root/workspace: unexpected answer"))
    }

    @Test fun aFailedLoginIsLoggedNotThrownAndNoRpcIsSent() = runBlocking {
        val server = dsh(tokenValid = false) { created(true) }

        assertEquals(WorkspaceRegistrar.Outcome.FAILED, registrar().ensure(server.launch))
        assertTrue(rpcCalls.isEmpty())
        assertTrue(log(), log().contains("could not register /root/workspace: IOException"))
    }

    @Test fun aDshThatNeverAnswersTimesOut() = runBlocking {
        val server = dsh { created(true).setHeadersDelay(5, TimeUnit.SECONDS) }

        val t0 = System.nanoTime()
        assertEquals(WorkspaceRegistrar.Outcome.FAILED, registrar(timeoutMs = 300).ensure(server.launch))
        assertTrue("took too long", System.nanoTime() - t0 < 3_000_000_000L)
        assertTrue(log(), log().contains("could not register /root/workspace: timed out"))
    }

    @Test fun anUnreachableDshIsLoggedNotThrown() = runBlocking {
        val server = dsh { created(true) }
        val launch = server.launch
        server.close()

        assertEquals(WorkspaceRegistrar.Outcome.FAILED, registrar().ensure(launch))
    }

    @Test fun nothingLoggedCarriesTheLaunchUrlOrItsToken() = runBlocking {
        val server = dsh { MockResponse().setResponseCode(500) }
        registrar().ensure(server.launch)
        val server2 = dsh(tokenValid = false) { created(true) }
        registrar().ensure(server2.launch)

        assertTrue(log().isNotEmpty())
        assertFalse(log(), log().contains("127.0.0.1"))
        assertFalse(log(), log().contains("token"))
    }
}
