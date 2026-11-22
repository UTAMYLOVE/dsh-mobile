package app.dshmobile

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.agency.core.metering.Provider
import app.agency.core.migrate.AgencyPrefKeys
import app.agency.core.migrate.LegacyPrefs
import app.agency.core.model.Who
import app.agency.core.model.WordedBy
import app.dshmobile.agency.StartState
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
        val edit = prefs(LegacyPrefs.NAME).edit().clear()
        PREF.findAll(asset("v083-prefs.xml").decodeToString()).forEach { m ->
            val (type, name, value, text) = m.destructured
            if (type == "int") edit.putInt(name, value.toInt()) else edit.putString(name, text)
        }
        assertTrue(edit.commit())
    }

    private fun open() {
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
        rule.waitUntil(60_000) { rule.onAllNodesWithTag(AgencyTags.HOME).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ready() = app.agency.start.value as StartState.Ready

    @Before fun setUp() = fresh()

    @After fun tearDown() {
        scenario?.close()
        fresh()
    }

    @Test fun e01_freshInstallOpensTheCompany() {
        open()
        rule.onNodeWithTag(AgencyTags.TRAINEE).assertExists()
        assertTrue(rule.onAllNodesWithTag(AgencyTags.IMPORTING).fetchSemanticsNodes().isEmpty())
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
    }

    private fun contains(hay: ByteArray, needle: ByteArray): Boolean =
        (0..hay.size - needle.size).any { i -> needle.indices.all { hay[i + it] == needle[it] } }

    private companion object {
        const val FAKE_KEY = "sk-e2e-fake-key-0123456789"
        val PREF = Regex("<(int|string) name=\"([^\"]+)\"(?: value=\"([^\"]*)\" />|>([^<]*)</string>)")
    }
}
