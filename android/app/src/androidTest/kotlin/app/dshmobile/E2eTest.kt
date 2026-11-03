package app.dshmobile

import android.app.Notification
import android.app.NotificationManager
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import app.dshmobile.E2e.awaitDshInSakuraPink
import app.dshmobile.E2e.eval
import app.dshmobile.E2e.waitFor
import app.dshmobile.heartbeat.HeartbeatScheduler
import app.dshmobile.heartbeat.HeartbeatWorker
import app.dshmobile.runtime.CommandResult
import app.dshmobile.runtime.DshClient
import app.dshmobile.runtime.DshVersions
import app.dshmobile.runtime.GuestCommand
import app.dshmobile.runtime.HealthCheck
import app.dshmobile.runtime.UpdateOutcome
import app.dshmobile.ui.MainActivity
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The whole stack on a real Android (x86_64 emulator in CI): shell app -> proot -> Debian rootfs with Node 24 -> the official
 * dsh -> our memory/notify/ui plugins -> the app's gateway, WebView, heartbeat and updater. The model is [FakeModel] in this
 * process (the gateway is pointed at it by [E2eRunner]); the key is the fake `sk-e2e-fake`. One test per item of spec §9.
 * The tests run in name order and share the running dsh (held as "e2e"), but each one also works on its own.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class E2eTest {
    @get:Rule val dumpOnFailure = E2e.DumpErrorLogOnFailure()

    private val app get() = E2e.app
    private val rootfs get() = File(app.filesDir, "rootfs")
    /** The workspace lives outside the rootfs (AppPaths) and is bound into the guest at /root/workspace. */
    private val memoryDir get() = File(app.appPaths.workspace, "memory")
    private val http = OkHttpClient.Builder().followRedirects(false).readTimeout(120, TimeUnit.SECONDS).build()

    @Before fun setUp() {
        // A periodic heartbeat must not dream (and consume the notes) behind test 4's back.
        HeartbeatScheduler.cancel(app)
        E2e.prepareApp()
        // Keeps dsh up between tests (the UI's ten-minute grace or a heartbeat would otherwise stop it).
        app.controller.retain(HOLDER)
    }

    /** §9.1: the main screen loads dsh's interface in the WebView, painted by the ui plugin. */
    @Test fun t1_mainScreenShowsDshInSakuraPink() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.awaitDshInSakuraPink()
            assertEquals(E2e.SAKURA, scenario.eval(E2e.BG))
            assertTrue("document.title is empty", scenario.eval("document.title.length") != "0")
        }
    }

    /** §9.2: without dsh's cookie, nothing is served: neither dsh's page nor our plugin routes. */
    @Test fun t2_withoutTheCookieDshAndThePluginRoutesRefuse() {
        val running = runBlocking { app.controller.ensureRunning() }
        val base = "http://127.0.0.1:${running.dshPort}"
        val page = code("$base/")
        assertTrue("GET / without cookie answered $page", page == 401 || page == 403)
        assertEquals(401, code("$base/dshm/health/memory"))
        assertEquals(401, code("$base/dshm/health/notify"))
        val heartbeat = Request.Builder().url("$base/dshm/heartbeat").post("{}".toRequestBody()).build()
        assertEquals(401, http.newCall(heartbeat).execute().use { it.code })
    }

    /** §9.3: a conversation through dsh's own RPC; the fake model calls memory_write, the plugin writes today's note. */
    @Test fun t3_aConversationMakesTheAgentWriteAMemory() {
        val running = runBlocking { app.controller.ensureRunning() }
        val client = DshClient(http, running.launch)
        runBlocking { client.login() }
        val seenBefore = FakeModel.instance.seen.size

        val created = rpc(client, "session/create", JSONObject().put("request", JSONObject().put("cwd", "/root/workspace")))
        val sessionId = created.getString("sessionId")
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", "Please remember the E2E mark."))
        rpc(
            client, "session/prompt",
            JSONObject().put(
                "request",
                JSONObject().put("requestId", "e2e-${UUID.randomUUID()}").put("sessionId", sessionId).put("mode", "queue").put("content", content),
            ),
        )

        // dsh runs with TZ=Asia/Taipei; around midnight the note may land on either side.
        waitFor(240_000, "$MARK in today's memory note") { notesOfToday().any { it.isFile && it.readText().contains(MARK) } }
        waitFor(120_000, "the follow-up request with the tool result") {
            FakeModel.instance.seen.drop(seenBefore).any { it.kind == FakeModel.Kind.FOLLOW_UP }
        }
        val mine = FakeModel.instance.seen.drop(seenBefore)
        assertTrue("no tool_use turn: $mine", mine.any { it.kind == FakeModel.Kind.TOOL_USE })
        assertTrue("dsh reached the model without the vault's key (gateway did not swap it): $mine", mine.all { it.apiKeyIsTheVaultKey })
        assertTrue("requests went past the gateway's /deepseek route: $mine", mine.all { it.path == "/deepseek/v1/messages" })
    }

    /** §9.4: the heartbeat dreams over yesterday's note and posts the reminder the model returned as a notification. */
    @Test fun t4_theHeartbeatPostsTheDreamReminder() {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.cancelAll()
        memoryDir.mkdirs()
        File(memoryDir, ".dream-state.json").delete() // a dream processes only notes after its last run
        val yesterday = LocalDate.now(TAIPEI).minusDays(1)
        File(memoryDir, "$yesterday.md").appendText("- 09:00 E2E: call the dentist tomorrow\n")

        val worker = TestListenableWorkerBuilder<HeartbeatWorker>(app)
            .setWorkerFactory(app.workManagerConfiguration.workerFactory)
            .build()
        val result = runBlocking { worker.doWork() }
        assertEquals(ListenableWorker.Result.success(), result)

        waitFor(30_000, "a notification titled $REMIND") {
            nm.activeNotifications.any { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == REMIND }
        }
        assertTrue("the model never got the dream prompt", FakeModel.instance.seen.any { it.kind == FakeModel.Kind.DREAM })
        assertTrue("dsh stopped although the test holds it", app.controller.isRunning.value)
    }

    /** §9.5: a "new version" that installs but cannot start is rolled back automatically; the old one runs and the UI works. */
    @Test fun t5_aBrokenUpdateRollsBackAndTheAppStillWorks() {
        val optDsh = File(rootfs, "opt/dsh")
        val versions = DshVersions(optDsh, BrokenReleaseNpm(rootfs), app.errorLog, BuildConfig.DSH_PINNED_VERSION)
        val before = versions.current()
        runBlocking { app.controller.ensureRunning() }

        val outcome = runBlocking { versions.updateWithAutoRollback(app.controller, HealthCheck(http)) }

        assertTrue("expected RolledBack, got $outcome", outcome is UpdateOutcome.RolledBack && outcome.version == BROKEN)
        assertFalse("the rollback says the old version failed too: $outcome", (outcome as UpdateOutcome.RolledBack).reason.contains("also failed"))
        assertEquals(before, versions.current())
        assertFalse("the failed version is still on disk", File(optDsh, BROKEN).exists())
        val running = runBlocking { app.controller.ensureRunning() }
        assertTrue("health check after the rollback", runBlocking { HealthCheck(http).check(running.launch) })
        ActivityScenario.launch(MainActivity::class.java).use { it.awaitDshInSakuraPink() }
    }

    // ---------- helpers ----------

    private fun code(url: String): Int = http.newCall(Request.Builder().url(url).build()).execute().use { it.code }

    /** dsh's RPC: `POST /api/<namespace>/<method>` with a client-request envelope (docs/dsh-findings.md §Server). */
    private fun rpc(client: DshClient, method: String, args: JSONObject): JSONObject {
        val envelope = JSONObject().put("type", "client-request").put("rpcId", "e2e-${UUID.randomUUID()}").put("method", method)
            .put("payload", JSONObject().put("args", args))
        val r = runBlocking { client.postJsonForText("/api/$method", envelope.toString()) }
        assertEquals("$method answered ${r.code}: ${r.body.take(500)}", 200, r.code)
        val result = JSONObject(r.body).getJSONObject("result")
        assertTrue("$method failed: ${r.body.take(500)}", result.optBoolean("ok"))
        Log.i(FakeModel.TAG, "$method ok")
        return result.optJSONObject("value") ?: JSONObject()
    }

    private fun notesOfToday(): List<File> {
        val today = LocalDate.now(TAIPEI)
        return listOf(today, today.minusDays(1)).map { File(memoryDir, "$it.md") }
    }

    /**
     * npm for the update test: `npm view` names version [BROKEN]; `npm install` lays down a package of that version whose
     * `bin.js` exits at once (installs fine, never comes up). Paths are the guest's, mapped onto the host rootfs.
     */
    private class BrokenReleaseNpm(private val rootfs: File) : GuestCommand {
        override fun run(args: List<String>, timeoutMs: Long): CommandResult = when {
            args.take(2) == listOf("npm", "view") -> CommandResult(0, "$BROKEN\n", "")
            args.take(2) == listOf("npm", "install") -> {
                val prefix = args[args.indexOf("--prefix") + 1]
                val pkg = File(rootfs, prefix.removePrefix("/") + "/node_modules/@deepseek-ai/dsh")
                File(pkg, "lib").mkdirs()
                File(pkg, "package.json").writeText("""{"name":"@deepseek-ai/dsh","version":"$BROKEN"}""")
                File(pkg, "lib/bin.js").writeText("process.exit(1)\n")
                CommandResult(0, "", "")
            }
            else -> CommandResult(1, "", "unexpected guest command")
        }
    }

    companion object {
        private const val HOLDER = "e2e"
        private const val MARK = FakeModel.MEMORY_MARK
        private const val REMIND = FakeModel.REMIND_TITLE
        private const val BROKEN = "9.9.9"
        private val TAIPEI = ZoneId.of("Asia/Taipei")

        @AfterClass @JvmStatic fun releaseDsh() {
            E2e.app.controller.release(HOLDER)
        }
    }
}
