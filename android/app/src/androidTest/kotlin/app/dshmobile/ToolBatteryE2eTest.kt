package app.dshmobile

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dshmobile.remote.ApprovalText
import app.dshmobile.remote.PendingPrompt
import app.dshmobile.remote.PromptPart
import app.dshmobile.runtime.DshClient
import app.dshmobile.runtime.DshLayout
import app.dshmobile.runtime.PermissionLevel
import app.dshmobile.system.PhantomState
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
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The tools the model really uses, one call each, against the running dsh (the owner, 2026-11-15: 「原生模式很多工具調用不出來」).
 * The fake model makes exactly the call a prompt names (`E2E_TOOL {...}`), dsh runs it, and the result dsh sends back to the
 * model is what is judged: the same text a real model would get. Every case runs in its own conversation, so one stuck call
 * cannot hide the others, and the whole table is reported at the end.
 */
@RunWith(AndroidJUnit4::class)
class ToolBatteryE2eTest {
    @get:Rule val dump = E2e.DumpErrorLogOnFailure()
    private val app = E2e.app
    private val http = OkHttpClient.Builder().followRedirects(false).readTimeout(120, TimeUnit.SECONDS).build()
    private val outcomes = mutableListOf<String>()
    private val failures = mutableListOf<String>()

    @Before fun setUp() {
        E2e.prepareApp()
        FakeModel.instance.reviews.clear()
        app.controller.retain(HOLDER)
        app.controller.stop()
    }

    @After fun tearDown() {
        FakeModel.instance.reviews.clear()
        // An approval card a failed case left waiting would hold its turn open.
        app.shellSessions.current.value?.events?.let { events ->
            runCatching { runBlocking { events.prompts.value.filterIsInstance<PendingPrompt.Approval>().forEach { events.reject(it) } } }
        }
        app.agentSettingsStore.save(app.agentSettingsStore.load(PhantomState.ENABLED, false).copy(autoReview = false))
        app.runtimeMode.setPermission(PermissionLevel.SMART)
        app.controller.stop()
        app.controller.release(HOLDER)
    }

    /**
     * Native mode with commands running without a question (the switch the owner uses): what is judged here is whether each
     * tool works at all on Android's own programs, not who is asked first.
     */
    @Test fun everyToolTheModelUsesWorksNatively() {
        assumeTrue("this APK has no native runtime", app.nativeRunner.available)
        app.runtimeMode.set(true)
        app.runtimeMode.setPermission(PermissionLevel.FULL)
        val running = runBlocking { app.controller.ensureRunning() }
        assertTrue("the layout is not native: ${DshLayout.active}", DshLayout.active.native)
        val client = DshClient(http, running.launch).also { runBlocking { it.login() } }
        val dir = File(app.appPaths.workspace, DIR).apply { deleteRecursively(); mkdirs() }

        bash(client, "echo", "echo native-$((40 + 2))") { it.contains("native-42") }
        // Which everyday programs a model reaches for exist. A missing one is a tool call that "does not come out".
        val probe = "for t in $PROGRAMS; do if command -v \"\$t\" >/dev/null 2>&1; then echo \"HAS \$t\"; else echo \"MISSING \$t\"; fi; done"
        val programs = call(client, "bash", JSONObject().put("description", "List available programs").put("command", probe))
        val missing = programs?.text.orEmpty().lines().filter { it.startsWith("MISSING ") }.map { it.removePrefix("MISSING ") }.toSet()
        record("programs", programs != null && !programs.isError && missing == KNOWN_MISSING, "missing=$missing known=$KNOWN_MISSING ${programs?.text?.take(80) ?: "no result"}")
        bash(client, "mktemp", "f=\$(mktemp) && echo tmp-ok > \"\$f\" && cat \"\$f\" && rm \"\$f\"") { it.contains("tmp-ok") }
        bash(client, "node", "node -e 'console.log(6*7)'") { it.contains("42") }
        bash(client, "pipes", "printf 'b\\na\\nb\\n' | sort | uniq -c | wc -l") { it.trim().lines().first().trim() == "2" }
        bash(client, "git", "cd $DIR && rm -rf repo && git init -q repo && cd repo && git config user.email e2e@example.com && git config user.name e2e && echo x > f && git add f && git commit -qm first && echo commits=$(git log --oneline | wc -l)") { it.contains("commits=1") && !it.contains("templates not found") }
        bash(client, "curl-jq-sqlite", "printf '{\"a\":[1,2,3]}' | jq '.a | length' && sqlite3 :memory: 'select 6*7' && cd $DIR && printf z > z.txt && zip -q z.zip z.txt && unzip -l z.zip | grep -c z.txt && curl --version | head -1") {
            val l = it.trim().lines()
            l.getOrNull(0) == "3" && l.getOrNull(1) == "42" && l.getOrNull(2) == "1" && l.getOrNull(3)?.startsWith("curl ") == true
        }
        bash(client, "script-by-interpreter", "cd $DIR && printf '#!/bin/sh\\necho script-ok\\n' > s.sh && sh s.sh && bash s.sh") { it.lines().count { l -> l.trim() == "script-ok" } == 2 }

        // Python (2026-11-15): the standard library's native modules (each one a library of its own in nativeLibraryDir),
        // subprocess's shell, pip, a user-site install without the network (a wheel made on the spot) and venvs.
        bash(client, "python3", PYTHON_STDLIB) { it.contains("py-ok hi") }
        bash(client, "python-file", "cd $DIR && printf 'import sys\\nprint(\"py-file\", sys.argv[1])\\n' > s.py && python3 s.py ok") { it.contains("py-file ok") }
        bash(client, "pip", "python3 -m pip --version") { Regex("""pip \d+\.\d+.* from .*/native/python/.* \(python 3\.\d+\)""").containsMatchIn(it) }
        bash(client, "pip-install-user", "cd $DIR && rm -f $WHEEL && python3 -c '$MAKE_WHEEL' && python3 -m pip install --no-index --no-deps ./$WHEEL && " +
            "python3 -c 'import dshm_probe, site; print(\"probe\", dshm_probe.VALUE, dshm_probe.__file__.startswith(site.getuserbase()))' && python3 -m pip uninstall -y -q dshm-probe") {
            it.contains("probe 42 True")
        }
        // A venv's bin/python is a symlink to native/bin/python3, itself a symlink into nativeLibraryDir: it runs.
        bash(client, "venv", "rm -rf \"\$TMPDIR/v\" && python3 -m venv --without-pip \"\$TMPDIR/v\" && \"\$TMPDIR/v/bin/python\" -c 'import sys; print(\"venv\", sys.prefix != sys.base_prefix)'") {
            it.contains("venv True")
        }
        // There is no ensurepip wheel: the note tells the model to make a venv that borrows the app's pip, which installs into the venv.
        bash(client, "venv-pip", "cd $DIR && rm -rf \"\$TMPDIR/v2\" && python3 -m venv --without-pip --system-site-packages \"\$TMPDIR/v2\" && \"\$TMPDIR/v2/bin/python\" -m pip install -q --no-index --no-deps ./$WHEEL && " +
            "\"\$TMPDIR/v2/bin/python\" -c 'import dshm_probe, sys; print(\"venv-probe\", dshm_probe.VALUE, dshm_probe.__file__.startswith(sys.prefix))'") {
            it.contains("venv-probe 42 True")
        }

        val newFile = File(dir, "new.txt")
        tool(client, "write", "write", JSONObject().put("file_path", "$DIR/new.txt").put("content", "hello battery\n")) {
            Files.isRegularFile(newFile.toPath(), LinkOption.NOFOLLOW_LINKS) && newFile.readText() == "hello battery\n"
        }
        // dsh lets a conversation edit only a file it has read, so read and edit share one conversation.
        val reading = newSession(client)
        tool(client, "read", "read", JSONObject().put("file_path", "$DIR/new.txt"), reading) { it.contains("hello battery") }
        tool(client, "edit", "edit", JSONObject().put("file_path", "$DIR/new.txt").put("old_string", "hello").put("new_string", "edited"), reading) {
            Files.isRegularFile(newFile.toPath(), LinkOption.NOFOLLOW_LINKS) && newFile.readText() == "edited battery\n"
        }
        tool(client, "glob", "glob", JSONObject().put("pattern", "$DIR/*.txt")) { it.contains("new.txt") }
        tool(client, "grep", "grep", JSONObject().put("pattern", "edited battery").put("path", DIR)) { it.contains("new.txt") }
        tool(client, "skill", "skill", JSONObject().put("name", "dshm-research")) { it.contains("深度研究") }
        tool(client, "web_search", "web_search", JSONObject().put("queries", JSONArray().put("e2e battery"))) { it.contains("E2E Weather") }

        // A background command: the call returns at once, the work happens afterwards.
        val bg = File(dir, "bg.txt")
        val started = call(client, "bash", JSONObject().put("description", "Write a file later").put("command", "sleep 2; echo bg-ok > $DIR/bg.txt").put("run_in_background", true))
        record("background", started != null && !started.isError && waited(30_000) { bg.isFile && bg.readText().trim() == "bg-ok" }, started?.text?.take(200) ?: "no result")

        // A subagent runs a command of its own (a child never gets to ask the owner, so this only works when nothing has to be asked).
        val sub = File(dir, "sub.txt")
        val childCall = JSONObject().put("name", "bash").put("args", JSONObject().put("description", "Child writes a file").put("command", "echo sub-ok > $DIR/sub.txt"))
        val delegated = call(client, "subagent", JSONObject().put("description", "Battery child").put("prompt", "${FakeModel.TOOL} $childCall"), timeoutMs = 180_000)
        record("subagent-bash", delegated != null && waited(60_000) { sub.isFile && sub.readText().trim() == "sub-ok" }, delegated?.text?.take(200) ?: "no result")

        // Known gaps of Android itself, kept visible: when one starts to work, this fails and the note for the model is updated.
        val slashTmp = call(client, "bash", JSONObject().put("description", "Write under /tmp").put("command", "echo x > /tmp/dshm-battery-\$\$ && echo slash-tmp-ok"))
        record("known-gap:/tmp", slashTmp != null && !slashTmp.text.contains("slash-tmp-ok"), "Android has no /tmp (\$TMPDIR works): ${slashTmp?.text?.take(120)}")
        val direct = call(client, "bash", JSONObject().put("description", "Run a script directly").put("command", "cd $DIR && chmod +x s.sh && ./s.sh && echo direct-ok"))
        record("known-gap:./script", direct != null && !direct.text.contains("direct-ok"), "files in the app's data cannot be executed directly (sh s.sh works): ${direct?.text?.take(120)}")

        report("native")
    }

    /**
     * 權限自動審查 on (the owner's phone, 2026-11-15: every bash card 失敗 with "Auto review of tool "bash" failed; its body was not
     * executed: Unexpected token 'T', "The pendin"... is not valid JSON"). The reviewer here answers the way Claude did, a sentence
     * and then the verdict; the review must run, and the command must run after it.
     */
    @Test fun withAutoReviewOnACommandRunsWhenTheReviewerAddsASentence() {
        assumeTrue("this APK has no native runtime", app.nativeRunner.available)
        app.runtimeMode.set(true)
        app.runtimeMode.setPermission(PermissionLevel.SMART)
        app.agentSettingsStore.save(app.agentSettingsStore.load(PhantomState.ENABLED, false).copy(autoReview = true))
        val running = runBlocking { app.controller.ensureRunning() }
        val client = DshClient(http, running.launch).also { runBlocking { it.login() } }
        val dir = File(app.appPaths.workspace, DIR).apply { deleteRecursively(); mkdirs() }
        val proof = File(dir, "reviewed.txt")
        val session = newSession(client)
        // What the app does before a conversation's first message when Auto review is on (ShellSession.ensurePreset).
        rpc(client, "commands/execute", JSONObject().put("agentId", session).put("line", "/permission auto").put("submittedAttachments", JSONArray()))
        val from = FakeModel.instance.seen.size
        val result = call(client, "bash", JSONObject().put("description", "Reviewed write").put("command", "echo reviewed-ok > $DIR/reviewed.txt && cat $DIR/reviewed.txt"), session = session)
        val reviews = FakeModel.instance.since(from).count { it.kind == FakeModel.Kind.REVIEW }
        record("auto-review-ran", reviews >= 1, "reviewer requests: $reviews")
        record("auto-review-bash", result != null && !result.isError && result.text.contains("reviewed-ok") && proof.isFile, result?.let { (if (it.isError) "[error] " else "") + it.text.take(300) } ?: "no result within the time")
        report("auto-review")
    }

    /**
     * The reviewer's other slips (2026-11-15 audit): an allow with a "reason" (the plugin refused that shape), and HTTP 500s. A
     * slip the reviewer recovers from runs the command; a review that does not work at all becomes the owner's approval card
     * (自動審查這次沒有成功…), never a 失敗 card, and the command runs once he allows it.
     */
    @Test fun withAutoReviewOnTheReviewersSlipsNeverFailTheCommand() {
        assumeTrue("this APK has no native runtime", app.nativeRunner.available)
        app.runtimeMode.set(true)
        app.runtimeMode.setPermission(PermissionLevel.SMART)
        app.agentSettingsStore.save(app.agentSettingsStore.load(PhantomState.ENABLED, false).copy(autoReview = true))
        val running = runBlocking { app.controller.ensureRunning() }
        val client = DshClient(http, running.launch).also { runBlocking { it.login() } }
        // The app's own connection to dsh: approval cards reach the owner through it.
        val events = app.shellSessions.attach(running).events
        assertTrue("no event stream", events != null && waited(30_000) { events.clientId.value != null })
        File(app.appPaths.workspace, DIR).apply { deleteRecursively(); mkdirs() }
        fun autoSession() = newSession(client).also { rpc(client, "commands/execute", JSONObject().put("agentId", it).put("line", "/permission auto").put("submittedAttachments", JSONArray())) }
        fun write(name: String) = JSONObject().put("description", "Reviewed write").put("command", "echo $name-ok > $DIR/$name.txt && cat $DIR/$name.txt")
        fun ran(name: String, result: FakeModel.ToolResult?) = result != null && !result.isError && result.text.contains("$name-ok") && File(app.appPaths.workspace, "$DIR/$name.txt").isFile
        fun shown(result: FakeModel.ToolResult?) = result?.let { (if (it.isError) "[error] " else "") + it.text.take(300) } ?: "no result within the time"

        // An allow with a reason: the plugin's protocol has no such shape and failed the review.
        FakeModel.instance.reviews += FakeModel.Review.Says("""{"risk":"low","decision":"allow","reason":"writes one small file in the workspace"}""")
        val reasoned = call(client, "bash", write("reasoned"), session = autoSession())
        record("allow-with-reason", ran("reasoned", reasoned), shown(reasoned))

        // One 500, then a verdict: the review is tried again and the command runs.
        FakeModel.instance.reviews += FakeModel.Review.Fails(500)
        val from = FakeModel.instance.seen.size
        val once = call(client, "bash", write("retried"), session = autoSession())
        val tries = FakeModel.instance.since(from).count { it.kind == FakeModel.Kind.REVIEW }
        record("500-once", ran("retried", once) && tries >= 2, "reviews=$tries ${shown(once)}")

        // 500 on every try: the owner is asked instead of the tool failing.
        repeat(3) { FakeModel.instance.reviews += FakeModel.Review.Fails(500) }
        val session = autoSession()
        val start = ask(client, session, "bash", write("asked"))
        var card: PendingPrompt.Approval? = null
        waited(60_000) { events!!.prompts.value.filterIsInstance<PendingPrompt.Approval>().firstOrNull { it.agentId == session || it.toolName == "bash" }?.also { card = it } != null }
        val early = FakeModel.instance.toolResults(start).lastOrNull()
        record(
            "500-always-asks", card != null && card!!.toolName == "bash" && card!!.reason == ApprovalText.AUTO_REVIEW_FAILED && early == null,
            "card=${card?.let { "${it.toolName}: ${it.reason}" }} result before the card=${shown(early)}",
        )
        card?.let { runBlocking { events!!.allowOnce(it) } }
        var asked: FakeModel.ToolResult? = null
        waited(90_000) { FakeModel.instance.toolResults(start).lastOrNull()?.also { asked = it } != null }
        record("500-always-runs-once-allowed", ran("asked", asked), shown(asked))
        report("auto-review-slips")
    }

    /**
     * The release blocker (audit A1): the owner's conversations were put on "auto" while the Auto review switch was on, also at
     * 全部放行. After an update (dsh killed, no clean stop) dsh must still open such a conversation: its next message is sent,
     * the conversation is moved off "auto" (nothing reviews at 全部放行), and bash runs.
     */
    @Test fun aConversationLeftOnAutoStillRunsAtTheFullLevelAfterDshWasKilled() {
        assumeTrue("this APK has no native runtime", app.nativeRunner.available)
        app.runtimeMode.set(true)
        app.runtimeMode.setPermission(PermissionLevel.SMART)
        app.agentSettingsStore.save(app.agentSettingsStore.load(PhantomState.ENABLED, false).copy(autoReview = true))
        val first = runBlocking { app.controller.ensureRunning() }
        val client = DshClient(http, first.launch).also { runBlocking { it.login() } }
        File(app.appPaths.workspace, DIR).apply { deleteRecursively(); mkdirs() }
        val session = newSession(client)
        rpc(client, "commands/execute", JSONObject().put("agentId", session).put("line", "/permission auto").put("submittedAttachments", JSONArray()))
        val before = FakeModel.instance.seen.size
        val onAuto = call(client, "bash", JSONObject().put("description", "On auto").put("command", "echo on-auto-ok"), session = session)
        val reviewed = FakeModel.instance.since(before).count { it.kind == FakeModel.Kind.REVIEW }
        record("on-auto", onAuto != null && !onAuto.isError && onAuto.text.contains("on-auto-ok") && reviewed >= 1, "reviews=$reviewed ${onAuto?.text?.take(200)}")

        // The owner's settings: 全部放行 with the switch still on. Then dsh dies the way an APK update ends it: no clean stop,
        // so dsh's own Auto review never moves its conversations off "auto".
        app.runtimeMode.setPermission(PermissionLevel.FULL)
        val pids = dshPids()
        assertTrue("no dsh process found", pids.isNotEmpty())
        pids.forEach { android.os.Process.sendSignal(it, android.os.Process.SIGNAL_KILL) }
        assertTrue("dsh survived SIGKILL", waited(30_000) { pids.none { File("/proc/$it").exists() } })
        app.controller.stop()
        val second = runBlocking { app.controller.ensureRunning() }
        val bundles = File(app.appPaths.dshHome, "profiles/web/package.json").readText()
        record("bundle-kept", bundles.contains("@deepseek-ai/dsh-experimental-auto-review"), "profile keeps the Auto review bundle")

        // The app's own send (ShellSession: the preset is set first, then the prompt).
        val shell = app.shellSessions.attach(second)
        val from = FakeModel.instance.seen.size
        val text = "${FakeModel.TOOL} " + JSONObject().put("name", "bash").put("args", JSONObject().put("description", "After the kill").put("command", "echo after-kill-ok > $DIR/after.txt && cat $DIR/after.txt"))
        val sent = runBlocking { shell.conversation(session).send(listOf(PromptPart.Text(text)), text, emptyList()) }
        var result: FakeModel.ToolResult? = null
        waited(120_000) { FakeModel.instance.toolResults(from).lastOrNull()?.also { result = it } != null }
        val reviews = FakeModel.instance.since(from).count { it.kind == FakeModel.Kind.REVIEW }
        record(
            "send-after-kill",
            sent && result != null && !result!!.isError && result!!.text.contains("after-kill-ok") && reviews == 0,
            "sent=$sent reviews=$reviews ${result?.text?.take(200) ?: "no result; send error: ${shell.conversation(session).sendError.value}"}",
        )
        report("auto-after-kill")
    }

    /** dsh's node processes (native mode), for a kill that skips dsh's clean shutdown. */
    private fun dshPids(): List<Int> = File("/proc").listFiles().orEmpty().filter { it.name.all(Char::isDigit) }.mapNotNull { dir ->
        val cmd = try { File(dir, "cmdline").readBytes().toString(Charsets.UTF_8) } catch (_: Exception) { return@mapNotNull null }
        dir.name.toInt().takeIf { "libdshn_bin_node.so" in cmd && "@deepseek-ai/dsh/lib/bin.js" in cmd }
    }

    /**
     * 相容模式 (proot): a file the write tool creates must be an ordinary file. dsh publishes a new file with link(temp, final) and
     * then removes the temp folder; proot turns hard links into symlinks, which left the new file pointing into the folder that
     * was just removed (DSH on the owner's phone: the write tool breaks on new files, it used a shell heredoc instead).
     * Every other suite runs natively ([E2e.prepareApp]): this case switches to proot itself and back, and goes when proot does.
     */
    @Test fun aNewFileFromTheWriteToolIsAnOrdinaryFileUnderProot() {
        app.runtimeMode.set(false)
        try {
            val running = runBlocking { app.controller.ensureRunning() }
            assertTrue("the layout is native: ${DshLayout.active}", !DshLayout.active.native)
            val client = DshClient(http, running.launch).also { runBlocking { it.login() } }
            val dir = File(app.appPaths.workspace, DIR).apply { deleteRecursively(); mkdirs() }
            val file = File(dir, "proot-new.txt")
            tool(client, "proot-write", "write", JSONObject().put("file_path", "$DIR/proot-new.txt").put("content", "hello proot\n")) {
                Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.readText() == "hello proot\n"
            }
            tool(client, "proot-read", "read", JSONObject().put("file_path", "$DIR/proot-new.txt")) { it.contains("hello proot") }
            if (failures.isNotEmpty()) {
                val listing = dir.listFiles().orEmpty().joinToString { f -> f.name + if (Files.isSymbolicLink(f.toPath())) " -> " + Files.readSymbolicLink(f.toPath()) else "" }
                outcomes += "folder: $listing"
            }
            report("proot")
        } finally {
            // No dsh is left running under proot, and the next start is native again.
            app.controller.stop()
            app.runtimeMode.set(true)
        }
    }

    private fun bash(client: DshClient, id: String, command: String, ok: (String) -> Boolean) =
        tool(client, id, "bash", JSONObject().put("description", "Battery: $id").put("command", command), ok = ok)

    /** One call of [tool]; passes when dsh did not mark the result an error and [ok] accepts its text. */
    private fun tool(client: DshClient, id: String, tool: String, args: JSONObject, session: String? = null, ok: (String) -> Boolean) {
        val result = call(client, tool, args, session = session)
        record(id, result != null && !result.isError && ok(result.text), result?.let { (if (it.isError) "[error] " else "") + it.text.take(300) } ?: "no result within the time")
    }

    private fun record(id: String, passed: Boolean, detail: String) {
        val line = "${if (passed) "OK  " else "FAIL"} $id: ${detail.replace('\n', '␤')}"
        Log.i(FakeModel.TAG, "battery $line")
        outcomes += line
        if (!passed) failures += id
    }

    private fun report(mode: String) {
        assertTrue("$mode tool battery: ${failures.size} of ${outcomes.size} failed $failures\n" + outcomes.joinToString("\n"), failures.isEmpty())
    }

    private fun newSession(client: DshClient): String =
        rpc(client, "session/create", JSONObject().put("request", JSONObject().put("cwd", DshLayout.active.workspace))).getString("sessionId")

    /** Asks for the call in [sessionId] without waiting for it; returns where its requests start in [FakeModel.seen]. */
    private fun ask(client: DshClient, sessionId: String, tool: String, args: JSONObject): Int {
        val from = FakeModel.instance.seen.size
        val text = "${FakeModel.TOOL} " + JSONObject().put("name", tool).put("args", args)
        rpc(
            client, "session/prompt",
            JSONObject().put(
                "request",
                JSONObject().put("requestId", "e2e-${UUID.randomUUID()}").put("sessionId", sessionId).put("mode", "queue")
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))),
            ),
        )
        return from
    }

    /** Asks for the call (in a new conversation unless [session]) and returns the result dsh sent back to the model, or null. */
    private fun call(client: DshClient, tool: String, args: JSONObject, timeoutMs: Long = 90_000, session: String? = null): FakeModel.ToolResult? {
        val from = ask(client, session ?: newSession(client), tool, args)
        var result: FakeModel.ToolResult? = null
        waited(timeoutMs) { FakeModel.instance.toolResults(from).lastOrNull()?.also { result = it } != null && (tool != "subagent" || FakeModel.instance.toolResults(from).size >= 2) }
        return result ?: FakeModel.instance.toolResults(from).lastOrNull()
    }

    private fun waited(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (runCatching(cond).getOrDefault(false)) return true
            Thread.sleep(400)
        }
        return false
    }

    private fun rpc(client: DshClient, method: String, args: JSONObject): JSONObject {
        val envelope = JSONObject().put("type", "client-request").put("rpcId", "e2e-${UUID.randomUUID()}").put("method", method)
            .put("payload", JSONObject().put("args", args))
        val r = runBlocking { client.postJsonForText("/api/$method", envelope.toString()) }
        assertEquals("$method answered ${r.code}: ${r.body.take(500)}", 200, r.code)
        val result = JSONObject(r.body).getJSONObject("result")
        assertTrue("$method failed: ${r.body.take(500)}", result.optBoolean("ok"))
        return result.optJSONObject("value") ?: JSONObject()
    }

    private companion object {
        const val HOLDER = "e2e-battery"
        const val DIR = "e2e-battery"
        /** What a model reaches for in a shell, most used first. */
        const val PROGRAMS = "sh bash cat ls cp mv rm mkdir touch grep sed awk find sort uniq head tail wc cut tr xargs tee date sleep basename dirname mktemp " +
            "tar gzip unzip zip diff patch which file curl wget python3 node npm npx git rg jq sqlite3 make"
        /** Programs the native runtime does not carry yet. Exactly this set may be missing: more is a regression, fewer means this list is stale. */
        val KNOWN_MISSING: Set<String> = setOf("wget", "npm", "npx", "make")

        /** Imports what a model's script reaches for, uses the modules that need a library of their own, and runs a shell command. */
        const val PYTHON_STDLIB = """python3 -c 'import ssl, sqlite3, ctypes, lzma, bz2, zlib, hashlib, decimal, json, os, subprocess
assert sqlite3.connect(":memory:").execute("select 6*7").fetchone()[0] == 42
assert lzma.decompress(lzma.compress(b"x")) == bz2.decompress(bz2.compress(b"x")) == zlib.decompress(zlib.compress(b"x")) == b"x"
assert ctypes.CDLL("libc.so").getpid() == os.getpid()
assert str(decimal.Decimal("1.1") + decimal.Decimal("2.2")) == "3.3"
assert hashlib.sha256(b"").hexdigest().startswith("e3b0c442") and json.loads(json.dumps({"a": [1]})) == {"a": [1]}
ssl.create_default_context()
print("py-ok", subprocess.run("echo hi", shell=True, capture_output=True, text=True).stdout.strip())'"""

        const val WHEEL = "dshm_probe-1.0-py3-none-any.whl"
        /** A pure-Python wheel, so pip can be tried without the network (python3 -c '...', no single quotes inside). */
        const val MAKE_WHEEL = "import zipfile; z = zipfile.ZipFile(\"$WHEEL\", \"w\"); " +
            "z.writestr(\"dshm_probe/__init__.py\", \"VALUE = 42\\n\"); " +
            "z.writestr(\"dshm_probe-1.0.dist-info/METADATA\", \"Metadata-Version: 2.1\\nName: dshm-probe\\nVersion: 1.0\\n\"); " +
            "z.writestr(\"dshm_probe-1.0.dist-info/WHEEL\", \"Wheel-Version: 1.0\\nGenerator: e2e\\nRoot-Is-Purelib: true\\nTag: py3-none-any\\n\"); " +
            "z.writestr(\"dshm_probe-1.0.dist-info/RECORD\", \"\"); z.close()"
    }
}
