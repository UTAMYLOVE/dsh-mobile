package app.dshmobile

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dshmobile.runtime.DshClient
import app.dshmobile.runtime.DshLayout
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 「實驗：原生模式」 end to end: the switch on, dsh restarts on Android's own node (no proot) inside the app, and a conversation
 * makes the agent call the memory plugin, which writes today's note into the shared workspace. Then the switch goes back off.
 */
@RunWith(AndroidJUnit4::class)
class NativeModeE2eTest {
    @get:Rule val dump = E2e.DumpErrorLogOnFailure()
    private val app = E2e.app
    private val http = OkHttpClient.Builder().followRedirects(false).readTimeout(120, TimeUnit.SECONDS).build()
    private val memoryDir get() = File(app.appPaths.workspace, "memory")

    @Before fun setUp() {
        assumeTrue("this APK has no native runtime", app.nativeRunner.available)
        E2e.prepareApp()
        app.controller.retain(HOLDER)
        app.controller.stop()
        app.runtimeMode.set(true)
    }

    @After fun tearDown() {
        if (!app.nativeRunner.available) return
        app.runtimeMode.set(false)
        app.controller.stop()
        app.controller.release(HOLDER)
    }

    @Test fun aConversationRunsOnAndroidsOwnNode() {
        val running = runBlocking { app.controller.ensureRunning() }
        assertTrue("the layout is not native: ${DshLayout.active}", DshLayout.active.native)
        assertEquals(app.appPaths.workspace.path, DshLayout.active.workspace)
        val cmdlines = ownProcesses()
        assertTrue("no native node runs dsh: $cmdlines", cmdlines.any { "libdshn_bin_node.so" in it && "@deepseek-ai/dsh/lib/bin.js" in it })
        assertTrue("proot is running in native mode: $cmdlines", cmdlines.none { "libproot.so" in it })

        val client = DshClient(http, running.launch)
        runBlocking { client.login() }
        val seenBefore = FakeModel.instance.seen.size
        val sizesBefore = notesOfToday().associateWith { if (it.isFile) it.length() else 0L }
        val created = rpc(client, "session/create", JSONObject().put("request", JSONObject().put("cwd", DshLayout.active.workspace)))
        val sessionId = created.getString("sessionId")
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", "Please ${FakeModel.MEMORY_PROMPT}."))
        rpc(
            client, "session/prompt",
            JSONObject().put(
                "request",
                JSONObject().put("requestId", "e2e-${UUID.randomUUID()}").put("sessionId", sessionId).put("mode", "queue").put("content", content),
            ),
        )
        E2e.waitFor(240_000, "${FakeModel.MEMORY_MARK} written to today's memory note by native dsh") {
            notesOfToday().any { it.isFile && it.length() > sizesBefore.getValue(it) && it.readText().contains(FakeModel.MEMORY_MARK) }
        }
        E2e.waitFor(120_000, "the follow-up request with the tool result") {
            FakeModel.instance.since(seenBefore).any { it.kind == FakeModel.Kind.FOLLOW_UP }
        }
        // dsh keeps the conversation in the shared dsh home, under the native workspace.
        val sessions = File(app.appPaths.dshHome, "sessions").walkTopDown().filter { it.name.startsWith("session.v4") }.map { it.path }.toList()
        assertTrue("no session log for $sessionId: $sessions", sessions.any { sessionId in it })
    }

    private fun ownProcesses(): List<String> = File("/proc").listFiles().orEmpty().filter { it.name.all(Char::isDigit) }.mapNotNull {
        try { File(it, "cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ').trim().takeIf { c -> c.isNotEmpty() } } catch (_: Exception) { null }
    }

    private fun rpc(client: DshClient, method: String, args: JSONObject): JSONObject {
        val envelope = JSONObject().put("type", "client-request").put("rpcId", "e2e-${UUID.randomUUID()}").put("method", method)
            .put("payload", JSONObject().put("args", args))
        val r = runBlocking { client.postJsonForText("/api/$method", envelope.toString()) }
        assertEquals("$method answered ${r.code}: ${r.body.take(500)}", 200, r.code)
        val result = JSONObject(r.body).getJSONObject("result")
        assertTrue("$method failed: ${r.body.take(500)}", result.optBoolean("ok"))
        Log.i(FakeModel.TAG, "native $method ok")
        return result.optJSONObject("value") ?: JSONObject()
    }

    private fun notesOfToday(): List<File> {
        val today = LocalDate.now(ZoneId.of("Asia/Taipei"))
        return listOf(today, today.minusDays(1)).map { File(memoryDir, "$it.md") }
    }

    private companion object {
        const val HOLDER = "e2e-native"
    }
}
