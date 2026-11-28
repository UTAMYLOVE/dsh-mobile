package app.dshmobile.wire

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import app.agency.core.base.Days
import app.agency.core.base.TestClock
import app.agency.core.rules.Fixtures
import app.agency.core.stage.StageState
import app.agency.core.store.Doc
import app.agency.core.store.writeDoc
import app.dshmobile.AppGraph
import app.dshmobile.DshMobileApp
import app.dshmobile.data.TestDb
import app.dshmobile.secure.SecretKeyWrap
import app.dshmobile.secure.SecretsFile
import app.dshmobile.security.WrappedKeyVault
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.KeyGenerator
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.robolectric.Shadows.shadowOf

/** One request a fake model server saw: [body] is for assertions only (never printed). */
class Seen(val path: String, val body: String, val keyHeader: String?) {
    override fun toString() = "Seen($path, ${body.length} bytes)"
}

/**
 * The whole wiring on the JVM: the real [AppGraph] over a plain SQLite database, a key vault wrapped by a JVM key, two fake model
 * servers on 127.0.0.1 (Claude shape and DeepSeek shape, set as the graph's test bases, so the network gate lets nothing else
 * out), a moving clock, and [Wire] installed in the application. No real key, no real network.
 */
class WireRig {
    val app: DshMobileApp = ApplicationProvider.getApplicationContext()
    val clock = TestClock()
    private val jvmKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    val claudeSeen = CopyOnWriteArrayList<Seen>()
    val deepseekSeen = CopyOnWriteArrayList<Seen>()

    /** What each fake server says for a request body (its plain text answer); the rules import answer is chosen by the caller. */
    @Volatile var claudeSays: (String) -> String = { "好。" }
    @Volatile var deepseekSays: (String) -> String = { "好。" }

    val claude = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                claudeSeen += Seen(request.path.orEmpty(), body, request.getHeader("x-api-key"))
                val text = claudeSays(body)
                return json(
                    """{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":${q(text)}}],
                       "stop_reason":"end_turn","usage":{"input_tokens":50,"output_tokens":10}}""",
                )
            }
        }
        start()
    }

    val deepseek = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                deepseekSeen += Seen(request.path.orEmpty(), body, request.getHeader("Authorization"))
                if (request.path.orEmpty().endsWith("/v1/tools/search")) return json("""{"results":[]}""")
                val text = deepseekSays(body)
                return json(
                    """{"id":"c1","choices":[{"index":0,"message":{"role":"assistant","content":${q(text)}},"finish_reason":"stop"}],
                       "usage":{"prompt_tokens":100,"completion_tokens":20}}""",
                )
            }
        }
        start()
    }

    val graph = AppGraph(
        app,
        openDb = { TestDb.open(app) },
        keyVaultProvider = { WrappedKeyVault(SecretsFile(File(app.noBackupFilesDir, SecretsFile.NAME)), SecretKeyWrap { jvmKey }) },
    ).also {
        it.clock = clock
        it.claudeBase = claude.url("/")
        it.deepseekBase = deepseek.url("/")
        app.graph = it
    }

    val wire: Wire = Wire.install(app)

    val notifications get() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
    val alarmManager: AlarmManager get() = app.getSystemService(AlarmManager::class.java)

    init {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Invented rules saved as if onboarding had finished (joined on the clock's day by the first rollover), at [stage] (2 = check-in
     * and meals are open, so plan items have times). Goes through the quiet company: no wake handler runs behind the test's back.
     */
    fun onboarded(stage: Int = 2) {
        val c = wire.company.quiet
        c.saveRules(Fixtures.rules(), "他喜歡簡單直接的說法。", "（測試用的規章原文）")
        c.rollover()
        val day = Days.today(clock).toString()
        graph.store.writeDoc(Doc.STAGE, StageState(joinedOn = day, stage = stage, startedOn = day))
    }

    fun close() {
        claude.shutdown()
        deepseek.shutdown()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun q(s: String): String = kotlinx.serialization.json.JsonPrimitive(s).toString()

    companion object {
        const val CLAUDE_KEY = "sk-ant-invented-0123456789"
        const val DEEPSEEK_KEY = "sk-invented-deepseek-9876543210"
    }
}
