package app.dshmobile

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dshmobile.runtime.AppPaths
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
 * Native dsh end to end (the default since 0.7.0): dsh runs on Android's own node (no proot) inside the app, a conversation makes
 * the agent call the memory plugin, a conversation with the proot folder name still runs, and a picture is accepted. The other
 * suites run under proot (相容模式), so both ways stay covered; the switch goes back to that afterwards.
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

    /**
     * 0.7.0: a conversation made under proot keeps `/root/workspace` as its folder. Native dsh runs it all the same (the shim
     * maps the proot names onto the same folders of the phone): the agent's tool writes into the shared workspace.
     */
    @Test fun aConversationWithTheProotFolderRunsNatively() {
        val running = runBlocking { app.controller.ensureRunning() }
        assertTrue("the layout is not native: ${DshLayout.active}", DshLayout.active.native)
        val client = DshClient(http, running.launch)
        runBlocking { client.login() }
        val seenBefore = FakeModel.instance.seen.size
        val sizesBefore = notesOfToday().associateWith { if (it.isFile) it.length() else 0L }
        val created = rpc(client, "session/create", JSONObject().put("request", JSONObject().put("cwd", AppPaths.GUEST_WORKSPACE)))
        val sessionId = created.getString("sessionId")
        prompt(client, sessionId, JSONArray().put(JSONObject().put("type", "text").put("text", "Please ${FakeModel.MEMORY_PROMPT}.")))
        E2e.waitFor(240_000, "${FakeModel.MEMORY_MARK} written by a conversation whose folder is /root/workspace") {
            notesOfToday().any { it.isFile && it.length() > sizesBefore.getValue(it) && it.readText().contains(FakeModel.MEMORY_MARK) }
        }
        E2e.waitFor(120_000, "the follow-up request with the tool result") {
            FakeModel.instance.since(seenBefore).any { it.kind == FakeModel.Kind.FOLLOW_UP }
        }
    }

    /**
     * 0.7.0: a picture can be sent natively. dsh prepares pictures with sharp, whose glibc build cannot load on Android's own
     * node; the rootfs carries sharp's WebAssembly build next to it and sharp falls back to that (the owner's 0.5.0 screenshot:
     * 「prompt rejected」).
     */
    @Test fun aPictureIsAcceptedNatively() {
        val running = runBlocking { app.controller.ensureRunning() }
        assertTrue("the layout is not native: ${DshLayout.active}", DshLayout.active.native)
        val client = DshClient(http, running.launch)
        runBlocking { client.login() }
        val seenBefore = FakeModel.instance.seen.size
        val created = rpc(client, "session/create", JSONObject().put("request", JSONObject().put("cwd", DshLayout.active.workspace)))
        val sessionId = created.getString("sessionId")
        val bitmap = android.graphics.Bitmap.createBitmap(1600, 1200, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFDC9FB4.toInt()) }
        val png = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "${FakeModel.HELLO} with a picture"))
            .put(JSONObject().put("type", "image").put("mediaType", "image/png").put("name", "pink.png").put("data", android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP)))
        prompt(client, sessionId, content)
        E2e.waitFor(180_000, "the turn with the picture reaching the model") {
            FakeModel.instance.since(seenBefore).any { it.kind == FakeModel.Kind.HELLO }
        }
    }

    private fun prompt(client: DshClient, sessionId: String, content: JSONArray) {
        rpc(
            client, "session/prompt",
            JSONObject().put(
                "request",
                JSONObject().put("requestId", "e2e-${UUID.randomUUID()}").put("sessionId", sessionId).put("mode", "queue").put("content", content),
            ),
        )
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
