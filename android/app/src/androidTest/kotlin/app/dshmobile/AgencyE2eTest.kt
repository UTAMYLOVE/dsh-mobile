package app.dshmobile

import android.content.Context
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.agency.core.brain.Brain
import app.agency.core.llm.Fail
import app.agency.core.metering.Provider
import app.agency.core.migrate.AgencyPrefKeys
import app.agency.core.migrate.LegacyPrefs
import app.agency.core.model.Who
import app.agency.core.model.WordedBy
import app.dshmobile.agency.StartState
import app.dshmobile.agency.media.MediaVault
import app.agency.core.migrate.ImportNote
import app.agency.core.migrate.LegacyMapper
import app.agency.core.model.Baseline
import kotlinx.coroutines.launch
import org.json.JSONObject
import app.dshmobile.agency.data.AgencyDatabase
import app.dshmobile.agency.migrate.LegacyImporter
import app.dshmobile.ui.agency.AgencyActivity
import app.dshmobile.ui.agency.AgencyTags
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * 小雨娛樂 end to end on a real Android (arch T06): the real encrypted database (SQLCipher with a Keystore-wrapped key), the
 * real media vault, and the [FakeModel] behind the network gate. The suite shares one process, so each test closes the company,
 * puts the phone in the state it needs (a fresh install, or his v0.8.3 files) and opens it again.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AgencyE2eTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as DshMobileApp
    private val fake = FakeModel.instance
    private var scenario: ActivityScenario<AgencyActivity>? = null

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** A phone with no agency data and no coach files (the Keystore keys and the secrets file stay, as after an update). */
    private fun fresh() {
        runBlocking { app.agency.stopForTest() }
        File(context.noBackupFilesDir, "agency").deleteRecursively()
        listOf(AgencyPrefKeys.NAME, AgencyPrefKeys.IMPORT_NAME, LegacyPrefs.NAME).forEach { assertTrue(prefs(it).edit().clear().commit()) }
        context.filesDir.listFiles { f -> f.name == LegacyImporter.LEGACY_DIR || f.name.startsWith(LegacyImporter.LEGACY_DIR + ".migrated-") }
            ?.forEach { it.deleteRecursively() }
        app.keyVault.clear(Provider.DEEPSEEK)
        fake.reset()
    }

    private fun asset(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open("legacy/$name").use { it.readBytes() }

    /** His v0.8.3 install: `filesDir/coach` and prefs `dshm_coach` exactly as the coach left them (invented records). */
    private fun seedV083() {
        val coach = File(context.filesDir, LegacyImporter.LEGACY_DIR).apply { mkdirs() }
        File(coach, "state.json").writeBytes(asset("v083-state.json"))
        File(coach, "messages.jsonl").writeBytes(asset("v083-messages.jsonl"))
        seedPrefs("v083-prefs.xml")
    }

    /** `dshm_coach` written through the prefs API from a saved prefs file (the file itself may be cached in this process). */
    private fun seedPrefs(xml: String) {
        val edit = prefs(LegacyPrefs.NAME).edit().clear()
        PREF.findAll(asset(xml).decodeToString()).forEach { m ->
            val (type, name, value, text) = m.destructured
            if (type == "int") edit.putInt(name, value.toInt()) else edit.putString(name, text)
        }
        assertTrue(edit.commit())
    }

    /** The start states seen from before the company opens (review M13: an absence is checked from the start, not after). */
    private val states = java.util.Collections.synchronizedList(mutableListOf<StartState>())
    private var watch: kotlinx.coroutines.Job? = null

    private fun open() {
        watch = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            app.agency.start.collect { states += it }
        }
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
        rule.waitUntil(60_000) { rule.onAllNodesWithTag(AgencyTags.HOME).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ready() = app.agency.start.value as StartState.Ready

    @Before fun setUp() = fresh()

    @After fun tearDown() {
        watch?.cancel()
        scenario?.close()
        fresh()
    }

    @Test fun e01_freshInstallOpensTheCompany() {
        open()
        rule.onNodeWithTag(AgencyTags.TRAINEE).assertExists()
        assertTrue(states.toString(), states.none { it == StartState.Importing })
        assertEquals(AgencyPrefKeys.MIGRATED_DONE, prefs(AgencyPrefKeys.NAME).getInt(AgencyPrefKeys.MIGRATED, 0))
        runBlocking { assertEquals(0, ready().store.messages.count()) }
        assertTrue(fake.seen.isEmpty())
    }

    @Test fun e02_upgradeFromV083KeepsRecordsAndEncryptsThem() {
        seedV083()
        val state = File(context.filesDir, "coach/state.json").readBytes()
        open()
        runBlocking {
            val store = ready().store
            assertEquals(11, store.messages.count())
            assertEquals(4, store.meals.count())
            assertEquals(5, store.meals.countItems())
            assertEquals(1, store.meals.pending().size)
            assertEquals(2, store.weights.count())
            assertEquals(8, store.food.count())
            assertTrue(store.journal.migrations().single { it.id == LegacyImporter.LOG_ID }.ok)
            assertTrue(store.messages.recent(20).all { it.legacy })
        }
        assertEquals(AgencyPrefKeys.MIGRATED_DONE, prefs(AgencyPrefKeys.NAME).getInt(AgencyPrefKeys.MIGRATED, 0))
        assertEquals("DEEPSEEK", prefs(AgencyPrefKeys.NAME).getString(AgencyPrefKeys.PROVIDER, null))
        // The legacy folder is kept aside, byte for byte.
        val kept = context.filesDir.listFiles { f -> f.name.startsWith("coach.migrated-") }!!.single()
        assertTrue(state.contentEquals(File(kept, "state.json").readBytes()))
        assertFalse(File(context.filesDir, "coach").exists())
        // The database on disk is encrypted: no SQLite header, none of his words.
        val db = AgencyDatabase.file(context)
        val header = db.inputStream().use { s -> ByteArray(16).also { s.read(it) } }
        assertFalse(header.decodeToString().startsWith("SQLite format 3"))
        val words = "晚餐吃了炸臭豆腐一盤".toByteArray()
        listOf(db, File(db.path + "-wal")).filter { it.isFile }.forEach { f -> assertFalse(f.name, contains(f.readBytes(), words)) }
        assertTrue(fake.seen.isEmpty())
    }

    @Test fun e03_aReplyIsGuarded() {
        app.keyVault.put(Provider.DEEPSEEK, FAKE_KEY)
        fake.toolFirst = true
        fake.reply = "你今天還能吃 1234 大卡。現在去喝水。"
        open()
        rule.onNodeWithTag(AgencyTags.COMPOSER).performTextInput("姐姐在嗎")
        rule.onNodeWithTag(AgencyTags.SEND).performClick()
        rule.waitUntil(60_000) { rule.onAllNodes(hasText("現在去喝水。", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(rule.onAllNodes(hasText("1234", substring = true)).fetchSemanticsNodes().isEmpty())
        val reply = runBlocking { ready().store.messages.recent(5) }.last()
        assertEquals(Who.COMPANY, reply.who)
        assertEquals(WordedBy.MODEL, reply.wordedBy)
        assertEquals("現在去喝水。", reply.text)
        // The clerk, then the loop's two steps (a get_today call, then the reply); the key only in the header.
        assertEquals(3, fake.seen.size)
        assertTrue(fake.seen.all { it.auth == "Bearer $FAKE_KEY" && !it.body.contains(FAKE_KEY) })
        assertTrue(fake.seen.none { it.body.contains("/data/") || it.body.contains("http") && it.body.contains(context.packageName) })
        assertTrue(fake.seen[2].body.contains("\"role\":\"tool\""))
        // Arch §3's e2e note, for every request: at most 10 conversation messages, no picture, no path, no content address.
        fake.seen.forEach { s ->
            val msgs = JSONObject(s.body).getJSONArray("messages")
            val talk = (0 until msgs.length()).map { msgs.getJSONObject(it) }
                .count { it.optString("role") in setOf("user", "assistant") && !it.has("tool_calls") }
            assertTrue("messages: $talk", talk <= 10)
            listOf("image", "/data/", "content://").forEach { bad -> assertFalse(bad, s.body.contains(bad)) }
        }
    }

    @Test fun e04_upgradeFromTheAgencyBuildSealsTheBaselineFiles() {
        val coach = File(context.filesDir, LegacyImporter.LEGACY_DIR).apply { mkdirs() }
        File(coach, "state.json").writeBytes(asset("agency-v2-state.json"))
        File(coach, "messages.jsonl").writeBytes(asset("agency-v2-messages.jsonl"))
        File(coach, "baseline").mkdirs()
        BASELINE.forEach { File(coach, "baseline/$it").writeBytes(asset("agency-v2-baseline/$it")) }
        seedPrefs("agency-v2-prefs.xml")
        open()
        val vault = MediaVault.forApp(context)
        runBlocking {
            val store = ready().store
            assertEquals(9, store.messages.count())
            assertEquals(3, store.media.count())
            BASELINE.forEach { name ->
                val id = LegacyMapper.mediaId(name)
                assertEquals(name, sha(asset("agency-v2-baseline/$name")), store.media.get(id)!!.sha256)
                assertEquals(name, sha(asset("agency-v2-baseline/$name")), sha(vault.get(id)))
            }
            val b = store.baselines.get(Baseline.JOIN)!!
            assertEquals(LegacyMapper.mediaId("photo-1795224600000.jpg"), b.photoFront)
            assertEquals(LegacyMapper.mediaId("recording-1795225200000.m4a"), b.recording)
            assertTrue(ImportNote.fromJson(store.journal.migrations().single { it.id == LegacyImporter.LOG_ID }.note!!).mediaPending.isEmpty())
        }
        // Sealed on disk: no JPEG start, no MP4 box, nothing equal to a fixture.
        val sealed = MediaVault.mediaDir(context).listFiles { f -> f.name.endsWith(".bin") }!!
        assertEquals(3, sealed.size)
        sealed.forEach { f ->
            val bytes = f.readBytes()
            assertFalse(f.name, contains(bytes, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
            assertFalse(f.name, contains(bytes, "ftyp".toByteArray()))
        }
        val kept = context.filesDir.listFiles { f -> f.name.startsWith("coach.migrated-") }!!.single()
        BASELINE.forEach { assertTrue(it, asset("agency-v2-baseline/$it").contentEquals(File(kept, "baseline/$it").readBytes())) }
    }

    /**
     * Review M13: with no network his message is kept and shown, the reply is the template and says the company could not
     * reach the model, and once the network is back he can send again and gets a real answer.
     */
    @Test fun e05_offlineStillKeepsHisMessage() {
        // The screen's Compose state changes on the main thread only (the first CI run of b7b2703 drew while an IO thread
        // observed layout reads): a write or snapshot apply on one of the app's own threads (IO, Default, the HTTP client)
        // is kept and fails the test. The instrumentation thread is left out: it runs the Compose test framework's own code
        // (waitUntil, semantics fetches, text input) and this test's set-up, which may send apply notifications there by
        // design or after a framework upgrade without any app bug. The Robolectric AgencyMainThreadTest checks every thread.
        val testThread = Thread.currentThread()
        val offMain = java.util.Collections.synchronizedList(mutableListOf<String>())
        fun check(what: String) {
            val t = Thread.currentThread()
            if (android.os.Looper.myLooper() !== android.os.Looper.getMainLooper() && t !== testThread) offMain += "$what on ${t.name}"
        }
        val handles = listOf(
            Snapshot.registerGlobalWriteObserver { check("state write") },
            Snapshot.registerApplyObserver { _, _ -> check("snapshot apply") },
        )
        try {
            // The flow three times in a row (a fresh install each time), so one CI run samples the old flake three times.
            repeat(ROUNDS) { round ->
                if (round > 0) {
                    watch?.cancel()
                    scenario?.close()
                    fresh()
                }
                app.keyVault.put(Provider.DEEPSEEK, FAKE_KEY)
                fake.online = false
                offlineThenOnline()
            }
        } finally {
            handles.forEach { it.dispose() }
        }
        assertEquals(emptyList<String>(), offMain.toList().distinct())
    }

    private fun offlineThenOnline() {
        open()
        rule.onNodeWithTag(AgencyTags.COMPOSER).performTextInput("姐姐在嗎")
        rule.onNodeWithTag(AgencyTags.SEND).performClick()
        val offline = Brain.failLine(Fail.OFFLINE)
        rule.waitUntil(60_000) { rule.onAllNodes(hasText(offline, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        val stored = runBlocking { ready().store.messages.recent(5) }
        assertEquals(listOf(Who.OWNER, Who.COMPANY), stored.map { it.who })
        assertEquals("姐姐在嗎", stored[0].text)
        assertEquals(WordedBy.TEMPLATE, stored[1].wordedBy)
        assertTrue(stored[1].text, stored[1].text.endsWith(offline))
        assertEquals(Fail.OFFLINE, ready().brain.lastFail.value)
        // The app did try and was cut off; nothing reached the model.
        assertTrue(fake.cutOff.get() > 0)
        assertTrue(fake.seen.isEmpty())
        // On screen: his own bubble with his words, and the company's line saying it could not reach the model.
        rule.onNode(hasTestTag(AgencyTags.BUBBLE + stored[0].id) and hasAnyDescendant(hasText("姐姐在嗎"))).assertExists()
        rule.onNode(hasTestTag(AgencyTags.BUBBLE + stored[1].id) and hasAnyDescendant(hasText(offline, substring = true))).assertExists()
        // The network is back: he sends again and the company answers; his first message is still there.
        fake.online = true
        rule.onNodeWithTag(AgencyTags.COMPOSER).performTextInput("姐姐在嗎")
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag(AgencyTags.SEND).assertIsEnabled() }.isSuccess }
        rule.onNodeWithTag(AgencyTags.SEND).performClick()
        rule.waitUntil(60_000) { rule.onAllNodes(hasText("現在去喝水。", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        val after = runBlocking { ready().store.messages.recent(5) }
        assertEquals(listOf(Who.OWNER, Who.COMPANY, Who.OWNER, Who.COMPANY), after.map { it.who })
        assertEquals(stored[0].id, after[0].id)
        assertEquals(WordedBy.MODEL, after[3].wordedBy)
        assertEquals(null, ready().brain.lastFail.value)
    }

    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun contains(hay: ByteArray, needle: ByteArray): Boolean =
        (0..hay.size - needle.size).any { i -> needle.indices.all { hay[i + it] == needle[it] } }

    private companion object {
        const val FAKE_KEY = "sk-e2e-fake-key-0123456789"
        const val ROUNDS = 3
        val BASELINE = listOf("photo-1795224000000.jpg", "photo-1795224600000.jpg", "recording-1795225200000.m4a")
        val PREF = Regex("<(int|string) name=\"([^\"]+)\"(?: value=\"([^\"]*)\" />|>([^<]*)</string>)")
    }
}
