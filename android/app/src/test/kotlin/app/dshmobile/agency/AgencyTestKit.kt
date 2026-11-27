package app.dshmobile.agency

import android.content.Context
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.agency.core.metering.Provider
import app.agency.core.model.Message
import app.agency.core.store.AgencyStore
import app.agency.core.store.MessageRepo
import app.dshmobile.DshMobileApp
import app.dshmobile.agency.data.AgencyDatabase
import app.dshmobile.agency.media.MediaVault
import app.dshmobile.agency.secure.DataLockedException
import app.dshmobile.security.KeyVault
import java.io.File
import javax.crypto.KeyGenerator
import okhttp3.HttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject

/**
 * The company on Robolectric: the schema on plain SQLite in a file under [dir] (SQLCipher cannot load on the JVM, and a file
 * survives a reopen), a vault on a JVM key, keys in memory, and optionally a fake DeepSeek on 127.0.0.1.
 */
class AgencyTestKit(private val dir: File) {
    init {
        // FileProvider caches its path strategy statically; every Robolectric test has a fresh cache dir (the camera's files).
        (androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null) as MutableMap<*, *>).clear()
    }

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    var opens = 0
        private set

    /** Throws [fail] (once per entry) before opening; null entries open. */
    val failures = ArrayDeque<Throwable?>()

    /** While true, the vault's data key is lost: every seal fails (an import with baseline files then rolls back). */
    @Volatile var vaultLocked = false

    /** Everything open by default (the tests of the features); a rollout test passes its own week. */
    fun overrides(
        modelBase: HttpUrl? = null,
        rollout: app.agency.core.schedule.RolloutSpec = app.agency.core.schedule.RolloutSpec.ALL_OPEN,
        systemCamera: Boolean = true,
        autoOnboarding: Boolean = false,
    ) = TestOverrides(
        autoOnboarding = autoOnboarding,
        modelBase = modelBase,
        rollout = rollout,
        systemCamera = systemCamera,
        openDatabase = { ctx ->
            opens++
            failures.removeFirstOrNull()?.let { throw it }
            AgencyDatabase.build(ctx, File(dir, "agency.db").path, FrameworkSQLiteOpenHelperFactory())
        },
        mediaVault = { MediaVault(File(dir, "media")) { if (vaultLocked) throw DataLockedException("lost") else key } },
        store = { inner -> DropsMessagesWhile(inner) { dropMessages } },
    )

    /** While true, the store says it wrote a message it did not keep: the import's count check fails and rolls back. */
    @Volatile var dropMessages = false

    private class DropsMessagesWhile(private val inner: AgencyStore, private val on: () -> Boolean) : AgencyStore by inner {
        override val messages: MessageRepo = object : MessageRepo by inner.messages {
            override suspend fun insert(message: Message): Boolean = if (on()) true else inner.messages.insert(message)
        }
    }

    class MemoryKeys(vararg keys: Pair<Provider, String>) : KeyVault {
        private val map = mutableMapOf<String, String>().apply { keys.forEach { (p, k) -> put(p.name, k) } }
        override fun get(p: Provider): String? = map[p.name]
        override fun put(p: Provider, key: String) { map[p.name] = key }
        override fun clear(p: Provider) { map.remove(p.name) }
        override fun getNamed(name: String): String? = map[name]
        override fun putNamed(name: String, key: String) { map[name] = key }
        override fun clearNamed(name: String) { map.remove(name) }
    }

    companion object {
        /** Replaces a lazy singleton of [app] before anything reads it. */
        fun swap(app: DshMobileApp, field: String, value: Any) {
            DshMobileApp::class.java.getDeclaredField("$field\$delegate").apply { isAccessible = true }.set(app, lazyOf(value))
        }

        fun fixture(name: String): ByteArray = requireNotNull(AgencyTestKit::class.java.getResource("/legacy/$name")) { name }.readBytes()

        /** His v0.8.3 files: state, every message and the saved `dshm_coach` prefs, as the coach wrote them. */
        fun seedV083(context: Context, state: ByteArray = fixture("v083-state.json")) {
            val coach = File(context.filesDir, "coach").apply { mkdirs() }
            File(coach, "state.json").writeBytes(state)
            File(coach, "messages.jsonl").writeBytes(fixture("v083-messages.jsonl"))
            File(context.dataDir, "shared_prefs/dshm_coach.xml").apply { parentFile!!.mkdirs() }.writeBytes(fixture("v083-prefs.xml"))
        }

        /** The 5a94d3c build's files: state version 2, messages, the three 入社建檔 files and its prefs. */
        fun seedV2(context: Context) {
            val coach = File(context.filesDir, "coach").apply { mkdirs() }
            File(coach, "state.json").writeBytes(fixture("agency-v2-state.json"))
            File(coach, "messages.jsonl").writeBytes(fixture("agency-v2-messages.jsonl"))
            File(coach, "baseline").mkdirs()
            listOf("photo-1795224000000.jpg", "photo-1795224600000.jpg", "recording-1795225200000.m4a").forEach {
                File(coach, "baseline/$it").writeBytes(fixture("agency-v2-baseline/$it"))
            }
            File(context.dataDir, "shared_prefs/dshm_coach.xml").apply { parentFile!!.mkdirs() }.writeBytes(fixture("agency-v2-prefs.xml"))
        }

        /** A fake DeepSeek: the data clerk gets {"kind": [kind]}, a chat call gets an Utterance with [reply]. Bodies are kept. */
        fun fakeDeepSeek(reply: String, kind: String = "chat", bodies: MutableList<String> = mutableListOf()): MockWebServer {
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = request.body.readUtf8()
                    synchronized(bodies) { bodies += body }
                    val content = if (body.contains("資料整理員")) {
                        JSONObject().put("kind", kind).toString()
                    } else if (body.contains("你是審稿員")) {
                        JSONObject().put("violation", false).put("class", "none").put("reason", "").toString()
                    } else {
                        JSONObject().put("text", reply).put("ask", JSONObject.NULL).put("options", org.json.JSONArray())
                            .put("recommended", JSONObject.NULL).put("memory", org.json.JSONArray()).toString()
                    }
                    val message = JSONObject().put("role", "assistant").put("content", content)
                    val json = JSONObject().put("id", "fake").put("choices", org.json.JSONArray().put(JSONObject().put("index", 0).put("message", message).put("finish_reason", "stop")))
                        .put("usage", JSONObject().put("prompt_tokens", 10).put("completion_tokens", 5).put("prompt_cache_hit_tokens", 0).put("prompt_cache_miss_tokens", 10))
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(json.toString())
                }
            }
            server.start()
            return server
        }

        fun ComposeTestRule.waitForTag(tag: String, timeoutMs: Long = 10_000) =
            waitUntil(timeoutMs) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

        /**
         * Scrolls [tag] into view (a sideways row, and the panels under the conversation, which scroll once everything is open)
         * and taps it once it is enabled.
         */
        fun ComposeTestRule.tapTag(tag: String, timeoutMs: Long = 10_000) {
            waitUntil(timeoutMs) { onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            runCatching { onNodeWithTag(tag, useUnmergedTree = true).performScrollTo() }
            for (area in listOf(app.dshmobile.ui.agency.AgencyTags.PANELS, app.dshmobile.ui.agency.AgencyTags.CARD_AREA)) {
                if (onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag(area)), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) continue
                val panel = onNodeWithTag(area)
                val box = panel.fetchSemanticsNode().boundsInRoot
                val n = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
                val top = n.positionInRoot.y
                val bottom = top + n.size.height
                val dy = when { top < box.top -> top - box.top; bottom > box.bottom -> bottom - box.bottom; else -> 0f }
                if (dy != 0f) panel.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, dy) }
                waitForIdle()
            }
            waitUntil(timeoutMs) { runCatching { onNodeWithTag(tag, useUnmergedTree = true).assertIsEnabled() }.isSuccess }
            onNodeWithTag(tag, useUnmergedTree = true).performClick()
        }

        /** [text] on screen; a line further up the conversation is scrolled to (the chat shows only what fits). */
        fun ComposeTestRule.waitForText(text: String, timeoutMs: Long = 10_000) =
            waitUntil(timeoutMs) {
                if (onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()) return@waitUntil true
                runCatching {
                    onNodeWithTag(app.dshmobile.ui.agency.AgencyTags.CHAT)
                        .performScrollToNode(androidx.compose.ui.test.hasText(text, substring = true))
                }
                onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
    }
}
