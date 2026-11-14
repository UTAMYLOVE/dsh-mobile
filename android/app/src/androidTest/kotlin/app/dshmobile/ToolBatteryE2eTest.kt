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
        app.controller.retain(HOLDER)
        app.controller.stop()
    }

    @After fun tearDown() {
        app.runtimeMode.setNoApproval(false)
        app.runtimeMode.set(false)
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
        app.runtimeMode.setNoApproval(true)
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
        bash(client, "git", "cd $DIR && rm -rf repo && git init -q repo && cd repo && git config user.email e2e@example.com && git config user.name e2e && echo x > f && git add f && git commit -qm first && git log --oneline | wc -l") { it.trim().lines().last().trim() == "1" }
        bash(client, "script-by-interpreter", "cd $DIR && printf '#!/bin/sh\\necho script-ok\\n' > s.sh && sh s.sh && bash s.sh") { it.lines().count { l -> l.trim() == "script-ok" } == 2 }

        val newFile = File(dir, "new.txt")
        tool(client, "write", "write", JSONObject().put("file_path", "$DIR/new.txt").put("content", "hello battery\n")) {
            Files.isRegularFile(newFile.toPath(), LinkOption.NOFOLLOW_LINKS) && newFile.readText() == "hello battery\n"
        }
        tool(client, "read", "read", JSONObject().put("file_path", "$DIR/new.txt")) { it.contains("hello battery") }
        tool(client, "edit", "edit", JSONObject().put("file_path", "$DIR/new.txt").put("old_string", "hello").put("new_string", "edited")) {
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
     * 相容模式 (proot): a file the write tool creates must be an ordinary file. dsh publishes a new file with link(temp, final) and
     * then removes the temp folder; proot turns hard links into symlinks, which left the new file pointing into the folder that
     * was just removed (DSH on the owner's phone: the write tool breaks on new files, it used a shell heredoc instead).
     */
    @Test fun aNewFileFromTheWriteToolIsAnOrdinaryFileUnderProot() {
        app.runtimeMode.set(false)
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
    }

    private fun bash(client: DshClient, id: String, command: String, ok: (String) -> Boolean) =
        tool(client, id, "bash", JSONObject().put("description", "Battery: $id").put("command", command), ok)

    /** One call of [tool]; passes when dsh did not mark the result an error and [ok] accepts its text. */
    private fun tool(client: DshClient, id: String, tool: String, args: JSONObject, ok: (String) -> Boolean) {
        val result = call(client, tool, args)
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

    /** Asks for the call in a new conversation and returns the result dsh sent back to the model, or null when none came. */
    private fun call(client: DshClient, tool: String, args: JSONObject, timeoutMs: Long = 90_000): FakeModel.ToolResult? {
        val from = FakeModel.instance.seen.size
        val sessionId = rpc(client, "session/create", JSONObject().put("request", JSONObject().put("cwd", DshLayout.active.workspace))).getString("sessionId")
        val text = "${FakeModel.TOOL} " + JSONObject().put("name", tool).put("args", args)
        rpc(
            client, "session/prompt",
            JSONObject().put(
                "request",
                JSONObject().put("requestId", "e2e-${UUID.randomUUID()}").put("sessionId", sessionId).put("mode", "queue")
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))),
            ),
        )
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
        val KNOWN_MISSING: Set<String> = emptySet()
    }
}
