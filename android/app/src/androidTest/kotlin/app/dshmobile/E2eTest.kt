@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package app.dshmobile

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.TextUnit
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import app.dshmobile.E2e.launchShell
import app.dshmobile.E2e.waitFor
import app.dshmobile.heartbeat.HeartbeatScheduler
import app.dshmobile.heartbeat.HeartbeatWorker
import app.dshmobile.notify.Notifier
import app.dshmobile.remote.DshRemote
import app.dshmobile.remote.MuxState
import app.dshmobile.remote.PendingPrompt
import app.dshmobile.runtime.CommandResult
import app.dshmobile.runtime.DshClient
import app.dshmobile.runtime.DshVersions
import app.dshmobile.runtime.GuestCommand
import app.dshmobile.runtime.HealthCheck
import app.dshmobile.runtime.UpdateOutcome
import app.dshmobile.runtime.VerifiedDsh
import app.dshmobile.chat.ChatItem
import app.dshmobile.ui.MainActivity
import app.dshmobile.ui.chat.circled
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogSemantics
import app.dshmobile.ui.shell.ShellTab
import app.dshmobile.ui.shell.Tags
import app.dshmobile.ui.shell.StatusTags
import app.dshmobile.ui.chat.HelperTags
import app.dshmobile.ui.chat.ProjectTags
import app.dshmobile.chat.ComposeMode
import app.dshmobile.ui.chat.ResultTags
import app.dshmobile.ui.chat.WorkTags
import app.dshmobile.ui.shell.buildSettingsEnv
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The whole stack on a real Android (x86_64 emulator in CI): shell app -> proot -> Debian rootfs with Node 24 -> the official
 * dsh -> our memory/notify/ui plugins -> the app's gateway, native FOGCITY shell, heartbeat and updater. The model is
 * [FakeModel] in this process (the gateway is pointed at it by [E2eRunner]); the key is the fake `sk-e2e-fake`.
 *
 * The tests run in name order (two-digit numbers, Ruling 23) and share only the running dsh (held as "e2e"): every chat test
 * opens its own conversation and makes the data it checks, so each one also works on its own. t05 stops and restarts dsh and
 * runs before the chat tests. The screen is driven by test tags ([Tags]) through Compose's semantics, at the SAVER effect level
 * (a test-setup choice, see [E2e.prepareApp]); t21 alone asks for FULL.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class E2eTest {
    private val compose = E2eCompose()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(E2e.DumpErrorLogOnFailure()).around(compose)
    private val ui: ComposeUiTest get() = compose.ui

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

    /**
     * Whatever a test left behind must not take the next one down: a card still waiting (the next shell would open under it),
     * a reply or a subagent still running, a remembered conversation. Best effort, bounded, and never fails the test itself.
     */
    @After fun cleanUp() {
        val session = app.shellSessions.current.value ?: return
        runCatching {
            runBlocking {
                withTimeout(20_000) {
                    session.events?.prompts?.value.orEmpty().forEach { p ->
                        when (p) {
                            is PendingPrompt.Approval -> session.events?.reject(p)
                            is PendingPrompt.Question -> session.events?.decline(p)
                        }
                    }
                    session.index.rows.value.filter { it.running }.forEach { session.conversation(it.id).stop() }
                    session.agents.rows.value.filter { it.running }.forEach { session.agents.stop(it) }
                }
            }
        }.onFailure { Log.w(FakeModel.TAG, "cleanup after the test: ${it.javaClass.simpleName}") }
        app.uiPrefs.lastSessionId = null
    }

    /** §9.1 (stage 1): the main screen is the native shell, Ready on the real dsh, with no WebView anywhere in it. */
    @Test fun t01_theNativeShellIsReadyWithoutAWebView() {
        launchShell().use {
            assertEquals(emptyList<String>(), E2e.onMain { E2e.webViewsIn(E2e.shellActivity()) })
            ShellTab.entries.forEach { ui.awaitTag(Tags.TAB + it.name) }
            ui.awaitTag(Tags.SHELL_BACKGROUND)
        }
    }

    /** §9.2: without dsh's cookie, nothing is served: neither dsh's page nor our plugin routes. */
    @Test fun t02_withoutTheCookieDshAndThePluginRoutesRefuse() {
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
    @Test fun t03_aConversationMakesTheAgentWriteAMemory() {
        val running = runBlocking { app.controller.ensureRunning() }
        val client = DshClient(http, running.launch)
        runBlocking { client.login() }
        val seenBefore = FakeModel.instance.seen.size
        // Only what this conversation adds counts: an earlier run (or test) may already have left the mark in today's note.
        val sizesBefore = notesOfToday().associateWith { if (it.isFile) it.length() else 0L }

        val created = rpc(client, "session/create", JSONObject().put("request", JSONObject().put("cwd", "/root/workspace")))
        val sessionId = created.getString("sessionId")
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", "Please ${FakeModel.MEMORY_PROMPT}."))
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
            FakeModel.instance.since(seenBefore).any { it.kind == FakeModel.Kind.FOLLOW_UP }
        }
        val mine = FakeModel.instance.since(seenBefore)
        assertTrue("no tool_use turn: $mine", mine.any { it.kind == FakeModel.Kind.TOOL_USE })
        assertTrue("dsh reached the model without the vault's key (gateway did not swap it): $mine", mine.all { it.apiKeyIsTheVaultKey })
        assertTrue("requests went past the gateway's /deepseek route: $mine", mine.all { it.path == "/deepseek/v1/messages" })
    }

    /** §9.4: the heartbeat dreams over yesterday's note and posts the reminder the model returned; the overview lists it. */
    @Test fun t04_theHeartbeatPostsTheDreamReminder() {
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

        launchShell().use {
            ui.tap(Tags.TAB + ShellTab.OVERVIEW.name)
            ui.await("$REMIND on the overview's reminders") { ui.textsOf(Tags.OVERVIEW_REMINDERS).any { it.contains(REMIND) } }
        }
    }

    /**
     * §9.5: a "new version" that installs but cannot start is rolled back automatically; the old one runs and the UI works.
     * Then settings' update check on a verified current version: UpToDate, without asking npm anything. It runs the real
     * settings environment ([buildSettingsEnv]'s `checkUpdate`) on a [DshVersions] whose npm only records.
     */
    @Test fun t05_aBrokenUpdateRollsBackAndTheAppStillWorks() {
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
        val npm = RecordingNpm()
        val checked = DshVersions(optDsh, npm, app.errorLog, BuildConfig.DSH_PINNED_VERSION)
        assertTrue("the current dsh ${checked.current()} is not a verified version", VerifiedDsh.isVerified(checked.current()))
        launchShell().use {
            val env = E2e.onMain { buildSettingsEnv(E2e.shellActivity(), app, dshVersions = { checked }) }
            assertEquals(UpdateOutcome.UpToDate, runBlocking { env.versions.checkUpdate() })
        }
        assertEquals("npm was asked: ${npm.calls}", emptyList<String>(), npm.calls)
    }

    /**
     * Follow-up 1: dsh lists the app's workspace after a start (so a new conversation has it without dsh's picker), and
     * registering it again on a later start (t5 restarted dsh twice) never adds a duplicate.
     */
    @Test fun t06_dshListsTheAppWorkspaceOnceAfterEveryStart() {
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

    /** Follow-up 1: the host half of dsh's folder picker lists /root with the app's workspace in it. */
    @Test fun t07_dshsFolderPickerHostListsTheWorkspace() {
        val running = runBlocking { app.controller.ensureRunning() }
        val client = DshClient(http, running.launch)
        runBlocking { client.login() }
        val listing = rpc(client, "directoryPicker/list", JSONObject().put("path", "/root"))
        val entries = listing.getJSONArray("entries")
        val names = (0 until entries.length()).map { entries.getJSONObject(it).optString("name") }
        assertTrue("/root lists $names", "workspace" in names)
    }

    // ---------- the native chat ----------

    /** A reply streams in as Markdown (bold, list, table); its thinking is folded until tapped. */
    @Test fun t10_aReplyStreamsAsMarkdownWithCollapsibleThinking() = shell {
        val id = ui.newConversation()
        ui.send(FakeModel.HELLO)
        awaitBubble("reply")
        val texts = bubbleTexts()
        assertTrue("the list items: $texts", texts.any { it.contains("one") } && texts.any { it.contains("two") })
        assertTrue("the table's cell: $texts", texts.any { it.trim() == "2" })
        ui.awaitInList(Tags.CHAT_MESSAGES, Tags.THINKING, 30_000) { chatState(id) }
        assertFalse("the thinking shows before it is opened", ui.nodes(hasText("E2E thinking", substring = true)).isNotEmpty())
        ui.tap(Tags.THINKING)
        ui.awaitText("E2E thinking", 30_000)
    }

    /** web_search shows its source card (two sources) and the answer's citation line; the answer's link is its own accessible node. */
    @Test fun t11_webSearchShowsTheSourceCardAndCitations() = shell {
        val id = ui.newConversation()
        ui.send(FakeModel.SEARCH)
        awaitBubble("Sunny today")
        val cites = app.getString(R.string.chat_cites, circled(listOf(1)))
        ui.await("the citation line $cites", 30_000, { chatState(id) }) { ui.textsOf(Tags.CITES).any { it.contains(cites) } }
        // The card sits above the answer: on the 640 dp CI screen it may have to be scrolled to.
        ui.awaitInList(Tags.CHAT_MESSAGES, Tags.SEARCH_CARD, 30_000) { chatState(id) }
        val sources = app.getString(R.string.chat_search_sources, 2)
        ui.await("the search card with two sources", 30_000, { "card: ${ui.textsOf(Tags.SEARCH_CARD)}\n${chatState(id)}" }) {
            ui.textsOf(Tags.SEARCH_CARD).any { it.contains(sources) }
        }
        assertEquals(listOf("E2E Weather"), ui.textsOf(Tags.SOURCE_CHIP + 1))
        awaitBubble("Sunny today")
        // TalkBack: the link inside the answer is a separate node with its own click action (Compose's link annotation), not
        // just a styled span of the bubble's text.
        val answer = ui.nodes(hasTestTag(Tags.ASSISTANT_BUBBLE)).first { n -> n.texts().any { it.contains("Sunny today") } }.id
        val links = ui.nodes(hasClickAction() and hasAnyAncestor(SemanticsMatcher("the answer") { it.id == answer }), unmerged = true)
        assertTrue("the answer's link is not exposed as its own accessible node", links.isNotEmpty())
    }

    /** An approval card stays up while another tab shows (it is global) and must be answered; then the tool runs. */
    @Test fun t12_anApprovalMustBeAnsweredAndThenTheToolRuns() = shell {
        ui.newConversation()
        ui.send(FakeModel.APPROVE)
        ui.awaitTag(Tags.PROMPT_SHEET)
        // The dock is hidden from accessibility under the modal card, so the tab is switched the way the dock would.
        E2e.nav { select(ShellTab.SETTINGS) }
        ui.await("the settings tab") { E2e.nav { tab } == ShellTab.SETTINGS }
        ui.onEachFrame { assertTrue("the card went away with the tab change", ui.has(Tags.PROMPT_SHEET)) }
        ui.tap(Tags.APPROVE_ONCE)
        ui.awaitNoTag(Tags.PROMPT_SHEET, 30_000)
        ui.tap(Tags.TAB + ShellTab.CHAT.name)
        awaitBubble(FakeModel.APPROVED_REPLY)
        val done = app.getString(R.string.chat_tool_done)
        ui.await("the write tool's card says $done") {
            ui.nodes(hasTagPrefix(Tags.TOOL_STATUS), unmerged = true).any { n -> n.texts().any { it == done } }
        }
    }

    /** ask_user_question: the card's option and submit answer it; the model gets the answer. */
    @Test fun t13_aQuestionIsAnsweredFromTheCard() = shell {
        ui.newConversation()
        ui.send(FakeModel.ASK)
        ui.awaitTag(Tags.PROMPT_SHEET)
        ui.tap(Tags.OPTION + "q1-0")
        ui.tap(Tags.QUESTION_SUBMIT)
        awaitBubble(FakeModel.PICKED_REPLY)
    }

    /** STOP ends a streaming reply: 「已停止」 shows within 15 s and the stop button goes. */
    @Test fun t14_stopEndsAStreamingReply() = shell {
        ui.newConversation()
        ui.send(FakeModel.SLOW)
        ui.awaitTag(Tags.LIVE_BUBBLE)
        ui.tap(Tags.STOP)
        val stopped = app.getString(R.string.chat_stopped)
        ui.await("「$stopped」 and no stop button", 15_000) {
            ui.nodes(hasTestTag(Tags.NOTICE), unmerged = true).any { n -> n.texts().any { it.contains(stopped) } } && !ui.has(Tags.STOP)
        }
    }

    /** A prompt sent while a reply runs waits as 「排隊中」 and is answered after it. */
    @Test fun t15_aPromptSentWhileRunningIsQueuedThenAnswered() = shell {
        ui.newConversation()
        ui.send(FakeModel.SLOW)
        ui.awaitTag(Tags.LIVE_BUBBLE)
        ui.send("${FakeModel.HELLO} again")
        ui.awaitTag(Tags.QUEUED, 30_000)
        awaitBubble("reply", timeoutMs = 180_000)
        ui.awaitNoTag(Tags.QUEUED, 30_000)
    }

    /** A file picked through the system picker (here: a FileProvider URI) reaches the model with the prompt. */
    @Test fun t16_aFileAttachmentReachesTheModel() = shell {
        val uri = exportFile("e2e-note.txt", FILE_CONTENT)
        ui.newConversation()
        E2e.onMain { E2e.shellActivity().attachForTest(listOf(uri)) }
        ui.await("the attachment's chip") { ui.nodes(hasTagPrefix(Tags.ATTACHMENT_CHIP), unmerged = true).isNotEmpty() }
        val before = FakeModel.instance.seen.size
        ui.send("${FakeModel.FILE} read it")
        awaitBubble(FakeModel.FILE_REPLY)
        val mine = FakeModel.instance.since(before).filter { it.kind == FakeModel.Kind.FILE }
        assertTrue("the model never got the prompt: ${FakeModel.instance.since(before)}", mine.isNotEmpty())
        assertTrue("the file did not reach the model: $mine", mine.any { it.body.contains("e2e-note") || it.body.contains(FILE_CONTENT) })
    }

    /** The model chip switches this conversation's model at once; the next turn goes to it. */
    @Test fun t17_theModelSwitchSendsTheNextTurnToThePickedModel() = shell {
        ui.newConversation()
        ui.tap(Tags.MODEL_CHIP)
        ui.tap(Tags.MODEL_OPTION + PRO)
        ui.await("the chip shows DEEPSEEK PRO", 10_000) { ui.textsOf(Tags.MODEL_CHIP).any { it == "DEEPSEEK PRO" } }
        val before = FakeModel.instance.seen.size
        ui.send(FakeModel.HELLO)
        awaitBubble("reply")
        val turns = FakeModel.instance.since(before).filter { it.kind == FakeModel.Kind.HELLO }
        assertTrue("the turn did not go to $PRO: $turns", turns.isNotEmpty() && turns.all { it.model == PRO })
    }

    /**
     * Rename and delete from the conversation list; dsh no longer lists the conversation (or lists it only as archived), and it
     * stays gone after the app starts again. Everything it looks at is made here.
     */
    @Test fun t18_renameAndDeleteAConversation() {
        var id = ""
        launchShell().use {
            id = ui.newConversation()
            ui.send(FakeModel.HELLO)
            awaitBubble("reply")
            // dsh names a new conversation itself (the fake model's "E2E title"); renaming before that lands would race it.
            ui.await("dsh's own title for $id", 60_000, { listState() }) {
                !app.shellSessions.current.value?.index?.rows?.value?.firstOrNull { it.id == id }?.title.isNullOrBlank()
            }
            ui.tap(Tags.CHAT_LIST_BUTTON)
            ui.awaitInList(Tags.SESSION_LIST, Tags.SESSION_MENU + id, 60_000) { listState() }
            ui.tap(Tags.SESSION_MENU + id)
            ui.tap(Tags.RENAME)
            ui.type(Tags.RENAME_FIELD, RENAMED)
            // The keyboard first: on the 640 dp CI screen it leaves the card's buttons no room, and a tap aimed at 儲存
            // lands on the scrim, which cancels the card (runs 41666809430 and 41673409488).
            ui.hideKeyboard()
            ui.tap(Tags.RENAME_SAVE)
            ui.awaitNoTag(Tags.RENAME_FIELD, 30_000)
            ui.await("the row titled $RENAMED", 60_000, { listState() + "\nrow: ${ui.textsOf(Tags.SESSION_ROW + id)}\ndsh's title: ${dshTitle(id)}" }) {
                ui.textsOf(Tags.SESSION_ROW + id).any { it.contains(RENAMED) }
            }
            ui.awaitInList(Tags.SESSION_LIST, Tags.SESSION_MENU + id, 30_000) { listState() }
            ui.tap(Tags.SESSION_MENU + id)
            ui.tap(Tags.DELETE)
            ui.tap(Tags.DELETE_CONFIRM)
            ui.awaitNoTag(Tags.SESSION_ROW + id, 60_000)
        }
        val running = runBlocking { app.controller.ensureRunning() }
        val client = DshClient(http, running.launch)
        runBlocking { client.login() }
        waitFor(60_000, "dsh to stop listing $id (or to list it as archived)") {
            id !in listedSessionIds(client) || id in archivedSessionIds()
        }
        launchShell().use {
            // A conversation of this test's own: once its row is listed, dsh's list has arrived.
            val witness = ui.newConversation()
            ui.tap(Tags.CHAT_LIST_BUTTON)
            ui.awaitInList(Tags.SESSION_LIST, Tags.SESSION_ROW + witness, 60_000) { listState() }
            ui.onEachFrame { assertFalse("the deleted conversation is listed again", ui.has(Tags.SESSION_ROW + id)) }
            assertFalse("the app's index still lists the deleted conversation", app.shellSessions.current.value!!.index.rows.value.any { it.id == id })
        }
    }

    /**
     * A running subagent shows on the agents tab and its stop ends it. dsh 0.2.1-rc.5 facts (CI runs 41659088488, 41666809430
     * and its source): the `subagent` tool returns to the parent at once while the child runs on; `session/list` does not list
     * child sessions; and a child whose run ends is disposed (`session/disposed` → `api-session/removed`), so its row leaves the
     * tab instead of staying as 「已結束」. Stopped therefore means: no stop button, and the row says 已結束 or is gone. The child's
     * reply would take over a minute on its own, so ending within 30 s of the tap is the stop's doing.
     */
    @Test fun t19_theAgentsTabListsARunningSubagentAndStopsIt() = shell {
        val before = FakeModel.instance.seen.size
        ui.newConversation()
        ui.send(FakeModel.SUB)
        ui.tap(Tags.TAB + ShellTab.AGENTS.name)
        // The war room is the default; the plain list (TalkBack, small screens) carries the row tags this test drives.
        ui.tap(Tags.WAR_LIST_TOGGLE)
        var child = ""
        ui.await("a running agent row labelled E2E child", 180_000, { agentsState(child) }) {
            val row = ui.nodes(hasTagPrefix(Tags.AGENT_ROW)).firstOrNull { n -> n.texts().any { it.contains("E2E child") } }
            val id = row?.tag()?.removePrefix(Tags.AGENT_ROW)
            if (id != null && ui.has(Tags.AGENT_STOP + id)) child = id
            child.isNotEmpty()
        }
        // The row (and its stop button) shows as soon as dsh spawns the child, which can be before the child's first model
        // request: stop it once it is really running, and time its run from that request.
        ui.await("the child's model request", 60_000, { agentsState(child) }) {
            FakeModel.instance.since(before).any { it.kind == FakeModel.Kind.CHILD }
        }
        val childStarted = FakeModel.instance.since(before).first { it.kind == FakeModel.Kind.CHILD }.at
        ui.tap(Tags.AGENT_STOP + child)
        val finished = app.getString(R.string.agents_finished)
        ui.await("the child $child to stop (row 「$finished」 or gone)", 30_000, { agentsState(child) }) {
            val agents = app.shellSessions.current.value!!.agents
            !ui.has(Tags.AGENT_STOP + child) &&
                agents.rows.value.none { it.childId == child && it.running } &&
                (!ui.has(Tags.AGENT_ROW + child) || agentStatus(child) == finished)
        }
        val ranMs = System.currentTimeMillis() - childStarted
        assertTrue("the child ended ${ranMs / 1000} s after it started: on its own, not by the stop", ranMs < CHILD_ALONE_MS)
    }

    /** The overview shows today's spend (the fake model reports usage on every reply) and the conversation just used. */
    @Test fun t20_overviewShowsTodaysSpendAndRecentConversations() = shell {
        val id = ui.newConversation()
        // The meter adds up the whole day (earlier tests spent too): what counts is that this reply adds to it.
        val before = app.costMeter.todayUsd()
        ui.send(FakeModel.HELLO)
        awaitBubble("reply")
        ui.await("today's spend to grow past US$$before", 30_000) { app.costMeter.todayUsd() > before }
        ui.tap(Tags.TAB + ShellTab.OVERVIEW.name)
        ui.await("the ring to show more than US$$before") { ringUsd()?.let { it > before } == true }
        ui.awaitTag(Tags.OVERVIEW_RECENT + id)
    }

    /**
     * Ruling 3: at FULL the background really animates; SAVER (from settings) is saved and calms it; FULL brings it back. The
     * jank governor may not step the level down meanwhile, and the shell is started without ActivityScenario (see
     * [E2e.startShell]): at FULL it animates on every frame.
     *
     * What this reads is `FogSemantics.Animating`, the shell's *intent* flag (the effects it asked for at this level), not frames
     * actually drawn: a background that asked to animate but never redrew would still pass here. Real animation and its cost are
     * measured by [PerfGateTest] (final review A, minor).
     */
    @Test fun t21_effectStrengthPersistsAndCalmsTheShell() {
        val governor = app.effectsController.governor
        val autoDegrade = governor.autoDegrade
        try {
            E2e.withAnimationsOn {
                E2e.useEffects(EffectLevel.FULL)
                governor.autoDegrade = false
                val activity = E2e.startShell()
                try {
                    ui.await("the background animating at FULL", 30_000) { backgroundAnimating() == true }
                    ui.tap(Tags.TAB + ShellTab.SETTINGS.name)
                    ui.tap(Tags.EFFECT + 2)
                    ui.await("SAVER saved and the background still", 30_000) {
                        app.uiPrefs.effectLevel == EffectLevel.SAVER && backgroundAnimating() == false
                    }
                    ui.tap(Tags.EFFECT + 0)
                    ui.await("the background animating again at FULL", 30_000) {
                        app.uiPrefs.effectLevel == EffectLevel.FULL && backgroundAnimating() == true
                    }
                } finally {
                    E2e.finishShell(activity)
                }
            }
        } finally {
            governor.autoDegrade = autoDegrade
            E2e.useEffects(EffectLevel.SAVER)
        }
    }

    /** A notification's deep link (EXTRA_SESSION_ID) opens that conversation in the chat tab. */
    @Test fun t22_aNotificationDeepLinkOpensTheConversation() {
        var id = ""
        launchShell().use {
            id = ui.newConversation()
            ui.send(FakeModel.HELLO)
            awaitBubble("reply")
        }
        val title = conversationTitle(id)
        app.uiPrefs.lastSessionId = null // the link, not the remembered conversation, must open it
        val intent = Intent(app, MainActivity::class.java).putExtra(Notifier.EXTRA_SESSION_ID, id)
        launchShell(intent).use {
            ui.await("the chat tab on $id") { E2e.nav { tab == ShellTab.CHAT && openSessionId == id } }
            ui.await("the title $title") { ui.textsOf(Tags.CHAT_TITLE) == listOf(title) }
        }
    }

    /** Review Focus 1: an approval asked before the mux drops is still one card after the reconnect, and answering it works. */
    @Test fun t23_anApprovalPendingAcrossAReconnectIsStillAnswerable() = shell {
        ui.newConversation()
        ui.send(FakeModel.APPROVE)
        ui.awaitTag(Tags.PROMPT_SHEET)
        val remote = app.shellSessions.current.value!!.remote as DshRemote
        remote.mux.dropForTest()
        ui.await("the mux to notice the drop", 30_000) { remote.muxState.value != MuxState.OPEN }
        // The card is watched on every frame of the reconnect: it must neither vanish nor double.
        val counts = mutableSetOf<Int>()
        ui.await("the mux to reconnect", 120_000) {
            counts += ui.nodes(hasTestTag(Tags.PROMPT_SHEET), unmerged = true).size
            remote.muxState.value == MuxState.OPEN
        }
        assertEquals("cards seen during the reconnect", setOf(1), counts)
        ui.onEachFrame { assertEquals("cards after the reconnect", 1, ui.nodes(hasTestTag(Tags.PROMPT_SHEET), unmerged = true).size) }
        ui.tap(Tags.APPROVE_ONCE)
        awaitBubble(FakeModel.APPROVED_REPLY)
    }

    /**
     * The phone layout on the emulator (plan: "T15 的模擬器測試各檢查一次"): every tab, the attach menu, two attachment chips and
     * an approval card; nothing floating overlaps, each page's last control scrolled to the end sits above the dock, and no text
     * is squeezed into a vertical column. The screen is first set to about 412 dp wide (`wm density`, put back afterwards);
     * should that not give 360..480 dp, the measurements are only logged.
     */
    @Test fun t24_atPhoneWidthNoFloatingControlOverlapsAnother() {
        val density = phoneDensity()
        try {
            if (density != null) E2e.shell("wm density $density")
            launchShell().use {
                val widthDp = E2e.onMain { E2e.shellActivity().resources.displayMetrics.let { it.widthPixels / it.density } }
                Log.i(FakeModel.TAG, "t24: activity width $widthDp dp (density override $density)")
                assertTrue("the screen is $widthDp dp wide after the density override $density, not a phone's 360..480", widthDp in 360f..480f)
                fun everyTab(where: String) = ShellTab.entries.forEach { t ->
                    ui.tap(Tags.TAB + t.name)
                    ui.await("the ${t.name} tab") { E2e.nav { tab } == t }
                    if (t == ShellTab.CHAT) {
                        E2e.nav { listOpen = true } // the conversation list: a page that scrolls to an end like the others
                        ui.awaitTag(Tags.NEW_SESSION)
                    }
                    ui.awaitStable("the ${t.name} page's layout") { ui.nodes(hasClickAction(), unmerged = true).map { it.boundsInRoot } }
                    ui.assertNoFloatingOverlap("$where/${t.name}")
                    assertNoVerticalText("$where/${t.name}")
                    assertLastPageControlClearsTheDock("$where/${t.name}")
                }

                everyTab("ready")
                ui.tap(Tags.ATTACH)
                ui.awaitTag(Tags.ATTACH_IMAGE)
                ui.assertNoFloatingOverlap("attach menu", minControls = DOCK_CONTROLS + 2)
                ui.tap(Tags.ATTACH)
                ui.awaitNoTag(Tags.ATTACH_IMAGE)

                val uris = listOf(exportFile("e2e-layout-one.txt", "one"), exportFile("e2e-layout-two-with-a-long-name.txt", "two"))
                E2e.onMain { E2e.shellActivity().attachForTest(uris) }
                ui.await("two attachment chips") { ui.nodes(hasTagPrefix(Tags.ATTACHMENT_CHIP), unmerged = true).size >= 2 }
                everyTab("chips")
                // Remove them again: the approval below must not carry them along.
                while (true) {
                    val remove = ui.nodes(hasTagPrefix(Tags.ATTACHMENT_REMOVE), unmerged = true).firstOrNull()?.tag() ?: break
                    ui.tap(remove)
                    ui.awaitNoTag(remove)
                }

                ui.newConversation()
                ui.send(FakeModel.APPROVE)
                ui.awaitTag(Tags.PROMPT_SHEET)
                // Under the modal card the dock is hidden from semantics: the card's own two buttons are what floats.
                ui.assertNoFloatingOverlap("approval card", minControls = 2)
                assertNoVerticalText("approval card")
                ui.tap(Tags.APPROVE_ONCE)
                awaitBubble(FakeModel.APPROVED_REPLY)
            }
        } finally {
            if (density != null) E2e.shell("wm density reset")
        }
    }

    // ---------- helpers ----------

    /** Runs [block] with the shell started and Ready. */
    private fun shell(block: () -> Unit) {
        launchShell().use { block() }
    }

    private fun bubbleTexts(): List<String> =
        ui.nodes(hasTestTag(Tags.ASSISTANT_BUBBLE)).flatMap { it.texts() }

    /** Waits until a finished assistant bubble contains [text]. */
    /** Stage 2 §C: a finished reply gets its stats line, the header its context/total line, and 數據 opens the data panel. */
    @Test fun t25_aReplyShowsItsStatsAndTheDataPanelOpens() = shell {
        val id = ui.newConversation()
        ui.send(FakeModel.HELLO)
        awaitBubble("reply")
        ui.await("the reply's stats line", 60_000, { chatState(id) }) { ui.textsOf(Tags.REPLY_STATS).any { it.contains("出 ") } }
        // 0.5.0: the totals are the dock's status line; 數據 is behind the header's ⋮, and the 上下文/速度 segments open it too.
        ui.awaitTag(StatusTags.SEG + "model", 60_000)
        ui.tap(Tags.CHAT_MORE)
        ui.tap(Tags.CHAT_DATA)
        ui.awaitTag(Tags.CHAT_DATA_PANEL, 30_000)
        ui.tap(Tags.CHAT_MORE)
        ui.tap(Tags.CHAT_DATA)
        ui.awaitNoTag(Tags.CHAT_DATA_PANEL, 30_000)
    }

    /** Stage 2 §E/H: the war room shows the LIVE FLOW and a card for the subagent; tapping a card opens its detailed status. */
    @Test fun t26_theWarRoomShowsTheSpiritsAndAgentCards() = shell {
        ui.newConversation()
        ui.send(FakeModel.SUB)
        ui.tap(Tags.TAB + ShellTab.AGENTS.name)
        if (!ui.has(Tags.WAR_FLOW)) ui.tap(Tags.WAR_LIST_TOGGLE) // a list mode left on by an earlier test
        ui.awaitTag(Tags.WAR_FLOW, 60_000)
        ui.awaitTag(Tags.WAR_TILES, 60_000)
        var card = ""
        ui.await("a war-room card for E2E child", 180_000) {
            card = ui.nodes(hasTagPrefix(Tags.WAR_CARD)).firstOrNull { n -> n.texts().any { it.contains("E2E child") } }?.tag().orEmpty()
            card.isNotEmpty()
        }
        val childId = card.removePrefix(Tags.WAR_CARD)
        // The card is tall: on the 640 dp CI screen its centre can sit under the floating dock, so it is clicked the way
        // TalkBack does (its click action), not by a touch at its centre.
        ui.onAllNodes(hasTestTag(card), useUnmergedTree = true).onFirst().performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        ui.awaitTag(Tags.WAR_DETAIL + childId, 30_000)
    }

    /** Stage 2 §D: a new book opens its own conversation with the opening; its long reply becomes a chapter that reads. */
    @Test fun t27_aNewBookWritesItsFirstChapterAndItReads() = shell {
        val before = FakeModel.instance.seen.size
        ui.tap(Tags.TAB + ShellTab.LIBRARY.name)
        ui.tap(Tags.LIB_NEW)
        ui.type(Tags.LIB_TITLE, FakeModel.LONG)
        ui.tap(Tags.LIB_CREATE)
        ui.await("the book's conversation to reach the model", 120_000) {
            FakeModel.instance.since(before).any { it.kind == FakeModel.Kind.LONG }
        }
        ui.awaitTag(Tags.LIB_BOOK_PAGE, 60_000)
        ui.tap(Tags.LIB_READ, 180_000)
        ui.awaitTag(Tags.LIB_READER, 30_000)
        ui.await("the chapter text") { ui.textsOf(Tags.LIB_TEXT).any { it.length > 100 } }
        assertTrue("the book is stored in the workspace", app.libraryStore.state.value.books.any { it.title == FakeModel.LONG })
    }

    /** Stage 2 batch 2: the app's skills are in dsh's skill root after a start. */
    @Test fun t28_theAppSkillsAreInstalled() = shell {
        for (name in listOf("dshm-novel", "dshm-daily-brief", "dshm-bookkeeping", "dshm-phone", "dshm-pages", "dshm-followups", "dshm-contemplate", "dshm-research", "dshm-deliverable", "dshm-draw", "dshm-weather")) {
            val f = java.io.File(app.appPaths.dshHome, "skills/$name/SKILL.md")
            assertTrue("$name is installed", f.isFile && f.readText().startsWith("---\nname: $name"))
        }
        // The superpowers port is gone (the owner, 2026-11-10): none of its folders is left.
        assertTrue(java.io.File(app.appPaths.dshHome, "skills").list().orEmpty().none { it.startsWith("dshm-sp-") })
    }

    /** 0.5.0: a reply's hidden follow-up block becomes chips, and the block itself is not in the bubble. */
    @Test fun t29_followUpSuggestionsBecomeChips() = shell {
        ui.newConversation()
        ui.send(FakeModel.FOLLOWUPS)
        awaitBubble("E2E answer with suggestions")
        ui.awaitTag(Tags.FOLLOWUP_CHIP + "0", 60_000)
        assertTrue("the marker block is hidden", bubbleTexts().none { it.contains("dshm-followups") })
    }

    /** 0.5.0: dsh's todo_write list shows as the progress bar under the header. */
    @Test fun t30_todosShowAsTheProgressBar() = shell {
        ui.newConversation()
        ui.send(FakeModel.TODOS)
        awaitBubble(FakeModel.TODOS_REPLY)
        ui.awaitTag(WorkTags.TODO_BAR, 60_000)
        ui.await("the bar counts 1 of 3") { ui.nodes(hasText("1 / 3", substring = true), unmerged = true).isNotEmpty() }
    }

    /** 0.5.0: a presented file is a card in the chat and lands on the bookshelf once. */
    @Test fun t31_aPresentedFileIsACardAndOnTheShelf() = shell {
        ui.newConversation()
        ui.send(FakeModel.PRESENT)
        awaitBubble(FakeModel.PRESENT_REPLY, 180_000)
        ui.await("a present card", 60_000) { ui.nodes(hasTagPrefix(ResultTags.PRESENT), unmerged = true).isNotEmpty() }
        ui.await("the file on the shelf", 30_000) { app.libraryStore.state.value.files.count { it.path == FakeModel.PRESENT_PATH } == 1 }
    }

    /** 0.5.0: 計畫 runs dsh's /plan; the plan review is the plan card, and 同意 lets it go on. */
    @Test fun t32_planModeShowsThePlanCardAndApprovalGoesOn() = shell {
        ui.newConversation()
        ui.tap(Tags.ATTACH)
        ui.tap(Tags.MODE + ComposeMode.PLAN.name)
        ui.send(FakeModel.PLAN)
        ui.awaitTag(WorkTags.PLAN_CARD, 180_000)
        ui.tap(WorkTags.PLAN_APPROVE)
        awaitBubble(FakeModel.PLAN_REPLY, 120_000)
    }

    /** 0.5.0: a helper made from the 小幫手 tab gets its dsh schedule and shows as active. */
    @Test fun t33_aHelperGetsItsSchedule() = shell {
        ui.tap(Tags.TAB + ShellTab.CHAT.name)
        ui.await("the chat tab") { ui.has(Tags.NEW_SESSION) || ui.has(Tags.CHAT_LIST_BUTTON) }
        if (!ui.has(Tags.NEW_SESSION)) ui.tap(Tags.CHAT_LIST_BUTTON)
        ui.tap(ProjectTags.TAB + "HELPERS")
        ui.tap(HelperTags.EXAMPLE + "2")
        ui.tap(HelperTags.CREATE)
        ui.await("the helper's schedule in dsh", 180_000) {
            val h = app.helperStore.helpers.value.firstOrNull() ?: return@await false
            runCatching { runBlocking { app.shellSessions.current.value!!.remote.scheduleCatalog() } }.getOrNull().orEmpty().any { it.sessionId == h.sessionId }
        }
    }

    private fun awaitBubble(text: String, timeoutMs: Long = 120_000) =
        ui.await("an answer containing '$text'", timeoutMs) { bubbleTexts().any { it.contains(text) } }

    private fun backgroundAnimating(): Boolean? =
        ui.nodes(hasTestTag(Tags.SHELL_BACKGROUND), unmerged = true).firstOrNull()?.config?.getOrNull(FogSemantics.Animating)

    /** For a failure message: the title dsh itself holds for [id] (its `session/list` projection hints). */
    private fun dshTitle(id: String): String? = runCatching {
        val running = runBlocking { app.controller.ensureRunning() }
        val client = DshClient(http, running.launch).also { runBlocking { it.login() } }
        val items = rpc(client, "session/list", JSONObject().put("_request", JSONObject())).optJSONArray("items") ?: return@runCatching null
        (0 until items.length()).mapNotNull { items.optJSONObject(it) }.firstOrNull { it.optString("sessionId") == id }
            ?.optJSONObject("projections")?.optJSONObject("values")?.optString("title")
    }.getOrElse { "(${it.javaClass.simpleName})" }

    /** For a failure message: the agents the app knows (no prompts or replies, only ids, labels and flags). */
    private fun agentsState(child: String): String {
        val agents = app.shellSessions.current.value?.agents ?: return "no session"
        return "rows=${agents.rows.value.map { "${it.childId}:${it.label}:running=${it.running}" }} stopping=${agents.stopping.value} " +
            "failed=${agents.failed.value} child=$child status=${runCatching { agentStatus(child) }.getOrNull()}"
    }

    /** For a failure message: the kinds of items the app holds for conversation [id] (no text). */
    private fun chatState(id: String): String {
        val state = app.shellSessions.current.value?.conversation(id)?.state?.value ?: return "no session"
        val items = state.items.map { item ->
            when (item) {
                is ChatItem.Assistant -> "Assistant(reasoning=${item.reasoning?.length ?: "none"}, text=${item.text.length})"
                is ChatItem.Tool -> "Tool(${item.name}, ${item.status}, sources=${item.sources.size})"
                else -> item.javaClass.simpleName
            }
        }
        return "running=${state.running} live=${state.live != null} items=$items tags=${ui.nodes(hasTagPrefix(""), unmerged = true).mapNotNull { it.tag() }.distinct()}"
    }

    /** For a failure message: the conversation list's rows as the app holds them, and the tags on screen. */
    private fun listState(): String {
        val rows = app.shellSessions.current.value?.index?.rows?.value ?: return "no session"
        return "rows=${rows.take(12).map { "${it.id}@${it.updatedAt}:'${it.title.take(20)}'" }} tags=${ui.nodes(hasTagPrefix(Tags.SESSION_ROW), unmerged = true).mapNotNull { it.tag() }}"
    }

    /** The status the agents page gives the row of [child] (the dot beside the row's text says 執行中 or 已結束). */
    private fun agentStatus(child: String): String? {
        val row = ui.nodes(hasTestTag(Tags.AGENT_ROW + child), unmerged = true).firstOrNull() ?: return null
        val labels = setOf(app.getString(R.string.agents_running), app.getString(R.string.agents_finished))
        // The dot sits beside the row's text (not inside it): the label on the same line as the row is the row's.
        val line = row.boundsInRoot.top..row.boundsInRoot.bottom
        return ui.nodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription), unmerged = true)
            .filter { it.boundsInRoot.center.y in line }
            .flatMap { it.texts() }
            .firstOrNull { it in labels }
    }

    /** Today's spend as the overview's ring reads it out (its content description carries the US$ amount). */
    private fun ringUsd(): Double? {
        val prefix = app.getString(R.string.overview_ring_desc, "")
        return ui.textsOf(Tags.OVERVIEW_RING).firstNotNullOfOrNull { it.removePrefix(prefix).trim().toDoubleOrNull() }
    }

    /** The ids dsh's `session/list` returns (top level and children). */
    private fun listedSessionIds(client: DshClient): Set<String> {
        val items = rpc(client, "session/list", JSONObject().put("_request", JSONObject())).optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { items.optJSONObject(it)?.optString("sessionId")?.takeIf { id -> id.isNotEmpty() } }.toSet()
    }

    /** The sessions dsh's workspace registry marks archived (`workspace/archiveSession` is how the app deletes one). */
    private fun archivedSessionIds(): Set<String> {
        val store = File(app.appPaths.dshHome, "storages/workspace.json")
        if (!store.isFile) return emptySet()
        val out = mutableSetOf<String>()
        fun walk(v: Any?, underArchived: Boolean) {
            when (v) {
                is JSONObject -> v.keys().forEach { k -> walk(v.get(k), underArchived || k.contains("archived", ignoreCase = true)) }
                is JSONArray -> (0 until v.length()).forEach { walk(v.get(it), underArchived) }
                is String -> if (underArchived) out += v
            }
        }
        walk(JSONObject(store.readText()), false)
        return out
    }

    /** The title the chat header shows for [id] (dsh's title once it has one, else the untitled label), as the chat page picks it. */
    private fun conversationTitle(id: String): String {
        val rows = app.shellSessions.current.value?.index?.rows
        val end = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < end) {
            val title = rows?.value?.firstOrNull { it.id == id }?.title
            if (!title.isNullOrBlank()) return title
            Thread.sleep(500)
        }
        return app.getString(R.string.chat_untitled)
    }

    /** A file under cache/export/ (the FileProvider's shared directory), as a content URI the app reads like a picked one. */
    private fun exportFile(name: String, content: String): Uri {
        val file = File(app.cacheDir, "export/$name")
        file.parentFile!!.mkdirs()
        file.writeText(content)
        return FileProvider.getUriForFile(app, "${app.packageName}.export", file)
    }

    /** The density that makes the emulator's screen about 412 dp wide, or null when it already is (or the size is unknown). */
    private fun phoneDensity(): Int? {
        val size = Regex("""Physical size: (\d+)x(\d+)""").find(E2e.shell("wm size"))?.groupValues ?: return null
        val widthPx = size[1].toInt()
        val metrics = app.resources.displayMetrics
        val widthDp = metrics.widthPixels / metrics.density
        if (widthDp in 360f..480f) return null
        return (widthPx * 160.0 / 412.0).toInt().coerceAtLeast(60)
    }

    private val inDock = hasAnyAncestor(hasTestTag(Tags.DOCK))
    private val inSheet = hasAnyAncestor(hasTestTag(Tags.PROMPT_SHEET))

    /** Scrolled to the end, the page's last control ends above the floating dock. */
    private fun assertLastPageControlClearsTheDock(where: String) {
        val scrollers = ui.nodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy) and !inDock and !inSheet)
        scrollers.forEach { s ->
            runCatching {
                ui.onNode(SemanticsMatcher("node ${s.id}") { it.id == s.id }).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
            }
        }
        fun pageControls() = ui.nodes(hasClickAction() and !inDock and !inSheet and !hasTestTag(Tags.DOCK), unmerged = true)
            .filter { it.boundsInRoot.height > 0f && it.boundsInRoot.width > 0f }
        // The scroll (and anything it brings into view) has settled when the lowest control stops moving.
        ui.awaitStable("$where: the scroll to the end") { pageControls().maxOfOrNull { it.boundsInRoot.bottom } }
        val dockTop = ui.nodes(hasTestTag(Tags.DOCK), unmerged = true).first().boundsInRoot.top
        val lowest = pageControls().maxByOrNull { it.boundsInRoot.bottom } ?: return
        assertTrue("$where: ${lowest.describe()} ends at ${lowest.boundsInRoot.bottom}, under the dock's top $dockTop", lowest.boundsInRoot.bottom <= dockTop + 0.5f)
    }

    /** No text of two or more characters wraps into a column narrower than two of its characters (vertical text). */
    private fun assertNoVerticalText(where: String) {
        val texts = ui.nodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), unmerged = true)
            .filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
        for (n in texts) {
            val text = n.texts().joinToString("").filterNot { it.isWhitespace() }
            if (text.length < 2) continue
            val layouts = Collections.synchronizedList(mutableListOf<TextLayoutResult>())
            ui.runOnUiThread { n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) }
            val layout = layouts.firstOrNull() ?: continue
            val fontSize: TextUnit = layout.layoutInput.style.fontSize
            if (!fontSize.isSp) continue
            val em = with(ui.density) { fontSize.toPx() }
            val width = n.boundsInRoot.width
            assertFalse(
                "$where: '${text.take(20)}' is ${layout.lineCount} lines in $width px (< two characters of ${em}px): vertical text",
                layout.lineCount >= 2 && width < 2 * em,
            )
        }
    }

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

    /** The paths of every workspace in dsh's own registry file (`$DSH_HOME/storages/workspace.json`). */
    private fun workspacePaths(store: File): List<String> {
        if (!store.isFile) return emptyList()
        val tables = JSONObject(store.readText()).optJSONObject("tables")?.optJSONObject("workspaces") ?: return emptyList()
        return tables.keys().asSequence().map { tables.getJSONObject(it).optString("path") }.toList()
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

    /** Records every guest command and runs none of them (settings' update check must not need npm on a verified version). */
    private class RecordingNpm : GuestCommand {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun run(args: List<String>, timeoutMs: Long): CommandResult {
            calls += args.take(2).joinToString(" ")
            return CommandResult(1, "", "not run in this test")
        }
    }

    companion object {
        private const val HOLDER = "e2e"
        private const val MARK = FakeModel.MEMORY_MARK
        private const val REMIND = FakeModel.REMIND_TITLE
        private const val BROKEN = "9.9.9"
        private const val PRO = "deepseek-v4-pro"
        private const val RENAMED = "E2E 改名"
        private const val FILE_CONTENT = "E2E-FILE-CONTENT"
        /** E2E_CHILD's reply alone takes over a minute (FakeModel): a child gone sooner was stopped. */
        private const val CHILD_ALONE_MS = 60_000L
        private val TAIPEI = ZoneId.of("Asia/Taipei")

        @AfterClass @JvmStatic fun releaseDsh() {
            E2e.app.controller.release(HOLDER)
        }
    }
}
