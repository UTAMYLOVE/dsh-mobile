package app.dshmobile

import android.app.Notification
import android.app.NotificationManager
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import app.dshmobile.E2e.awaitDshInFogcity
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
    @Test fun t1_mainScreenShowsDshInFogcity() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.awaitDshInFogcity()
            assertEquals(E2e.FOGCITY, scenario.eval(E2e.BG))
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
        // Only what this conversation adds counts: an earlier run (or test) may already have left the mark in today's note.
        val sizesBefore = notesOfToday().associateWith { if (it.isFile) it.length() else 0L }

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
        waitFor(240_000, "$MARK newly written to today's memory note") {
            notesOfToday().any { it.isFile && addedSince(it, sizesBefore.getValue(it)).contains(MARK) }
        }
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
        // The heartbeat's first run of the day also asks GitHub for a newer app: the test stays off the network.
        app.dailyUpdateCheck.markCheckedToday()
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
        ActivityScenario.launch(MainActivity::class.java).use { it.awaitDshInFogcity() }
    }

    /**
     * Follow-up 1: dsh lists the app's workspace after a start (its own folder picker does not work in the WebView), and
     * registering it again on a later start (t5 restarted dsh twice) never adds a duplicate.
     */
    @Test fun t6_dshListsTheAppWorkspaceOnceAfterEveryStart() {
        val running = runBlocking { app.controller.ensureRunning() }
        val store = File(app.appPaths.dshHome, "storages/workspace.json")
        waitFor(30_000, "/root/workspace in dsh's workspace list") { workspacePaths(store).contains("/root/workspace") }
        assertEquals(listOf("/root/workspace"), workspacePaths(store).filter { it == "/root/workspace" })
        // dsh itself says it already has it: workspace/create is idempotent by path.
        val client = DshClient(http, running.launch)
        runBlocking { client.login() }
        val again = rpc(client, "workspace/create", JSONObject().put("request", JSONObject().put("path", "/root/workspace")))
        assertFalse("dsh created the workspace a second time", again.getBoolean("created"))
        assertEquals(1, workspacePaths(store).count { it == "/root/workspace" })
    }

    /**
     * Follow-up 1: dsh's own folder picker works in the WebView. Its browser half (`dsh-client-ui-directory-picker-browse`)
     * is loaded by the page (missing in v0.1.0: "choose workspace" did nothing) and its host half lists /root with the
     * workspace in it.
     */
    @Test fun t8_dshsFolderPickerIsLoadedAndSeesTheWorkspace() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.awaitDshInFogcity()
            waitFor(30_000, "the picker's browser half among the page's plugin bundles") { scenario.eval(PICKER_LOADED) == "true" }
        }
        val running = runBlocking { app.controller.ensureRunning() }
        val client = DshClient(http, running.launch)
        runBlocking { client.login() }
        val listing = rpc(client, "directoryPicker/list", JSONObject().put("path", "/root"))
        val entries = listing.getJSONArray("entries")
        val names = (0 until entries.length()).map { entries.getJSONObject(it).optString("name") }
        assertTrue("/root lists $names", "workspace" in names)
    }

    /**
     * Follow-up 1: the settings DSH Mobile manages are hidden in dsh's interface. Opens Plugins > 子智能体 in the real WebView
     * (by dsh's own hooks: the sidebar button's label in either language, the card's `data-plugin-item`) and checks that the
     * 最大递归深度 field is rendered but invisible, with the app's note in its place.
     */
    @Test fun t7_theSubagentDepthFieldIsHiddenInDshsSettings() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.awaitDshInFogcity()
            waitFor(30_000, "dsh's Plugins button") { scenario.eval(OPEN_PLUGINS) == "true" }
            waitFor(30_000, "the 子智能体 card") { scenario.eval(OPEN_SUBAGENT) == "true" }
            waitFor(30_000, "the 最大递归深度 field, rendered and hidden") { scenario.eval(DEPTH_FIELD) == "\"hidden\"" }
            assertTrue("no DSH Mobile note where the limits were", scenario.eval(NOTE).contains("DSH Mobile"))
        }
    }

    /** The paths of every workspace in dsh's own registry file (`$DSH_HOME/storages/workspace.json`). */
    private fun workspacePaths(store: File): List<String> {
        if (!store.isFile) return emptyList()
        val tables = JSONObject(store.readText()).optJSONObject("tables")?.optJSONObject("workspaces") ?: return emptyList()
        return tables.keys().asSequence().map { tables.getJSONObject(it).optString("path") }.toList()
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

    /** The text appended to [note] after it had [sizeBefore] bytes (a note is only ever appended to). */
    private fun addedSince(note: File, sizeBefore: Long): String {
        val bytes = note.readBytes()
        return if (bytes.size.toLong() <= sizeBefore) "" else String(bytes, sizeBefore.toInt(), bytes.size - sizeBefore.toInt(), Charsets.UTF_8)
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

        private const val PICKER_LOADED = """(function () {
  var name = 'dsh-client-ui-directory-picker-browse';
  var urls = performance.getEntriesByType('resource').map(function (e) { return e.name; });
  document.querySelectorAll('script[src]').forEach(function (s) { urls.push(s.src); });
  return urls.some(function (u) { return decodeURIComponent(u).indexOf(name) >= 0; });
})()"""
        private const val OPEN_PLUGINS = """(function () {
  var b = Array.prototype.find.call(document.querySelectorAll('button[aria-label]'), function (x) {
    var l = x.getAttribute('aria-label'); return l === '插件' || l === 'Plugins';
  });
  if (b) b.click();
  return !!b;
})()"""
        private const val OPEN_SUBAGENT = """(function () {
  var li = document.querySelector('li[data-plugin-item="subagent"]');
  if (li) (li.querySelector('button') || li).click();
  return !!li;
})()"""
        private const val DEPTH_FIELD = """(function () {
  var d = document.getElementById('plugin-config-subagent-depth');
  if (!d) return 'absent';
  return d.offsetParent === null && d.getClientRects().length === 0 ? 'hidden' : 'visible';
})()"""
        private const val NOTE = """(function () {
  var s = document.querySelector('[data-dshm-managed="note"]');
  return s ? getComputedStyle(s, '::before').content : 'none';
})()"""

        @AfterClass @JvmStatic fun releaseDsh() {
            E2e.app.controller.release(HOLDER)
        }
    }
}
