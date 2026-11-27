package app.dshmobile

import app.dshmobile.ui.kit.MemberColour
import app.agency.core.time.AgencyClock
import app.agency.core.schedule.SlotKey
import app.agency.core.model.TrainingKind
import app.agency.core.brain.handlers.StrengthReport
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.performTextReplacement
import app.dshmobile.ui.agency.meal.ResultTags
import app.agency.core.model.Profile
import app.dshmobile.ui.agency.meal.MealTags
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
import app.dshmobile.ui.agency.sign.SignTags
import app.dshmobile.ui.agency.training.TrainingTags
import kotlinx.coroutines.launch
import org.json.JSONObject
import app.dshmobile.agency.data.AgencyDatabase
import app.dshmobile.agency.migrate.LegacyImporter
import app.dshmobile.ui.agency.AgencyActivity
import app.dshmobile.ui.agency.AgencyTags
import app.dshmobile.ui.agency.lessons.LessonTags
import app.dshmobile.ui.agency.AgencyTab
import app.dshmobile.ui.agency.onboarding.OnboardingTags
import app.agency.core.brain.handlers.OnboardingStep
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import app.dshmobile.ui.agency.ChatTags
import app.dshmobile.ui.agency.ProposalTags
import app.agency.core.model.ProposalStatus
import app.agency.core.model.PlanSectionKey
import androidx.compose.ui.test.performScrollTo
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
    private val clock = E2eClock.instance

    /** What the company had written when a test ended (kept by [tearDown] before the next fresh install; invented data). */
    private var lastLines = ""

    /** A failing test says what the company had written, so one CI run is enough to see why. */
    @get:Rule val lines = org.junit.rules.TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } catch (e: Throwable) {
                    throw AssertionError("lines at the end:\n$lastLines\nrequests: ${fake.seen.map { it.path }}", e)
                }
            }
        }
    }
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
        clock.fixed = null
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

    /**
     * His line through the composer: typed, 送出 once it is enabled (it waits while the company answers), and kept in the store
     * (tried again if the screen was redrawn while typing).
     */
    private fun say(text: String) {
        rule.waitUntil(30_000) { rule.onAllNodesWithTag(AgencyTags.COMPOSER).fetchSemanticsNodes().isNotEmpty() }
        fun mine() = runBlocking { ready().store.messages.recent(80) }.count { it.who == Who.OWNER && it.text == text }
        val before = mine()
        repeat(3) {
            rule.onNodeWithTag(AgencyTags.COMPOSER).performTextReplacement(text)
            rule.waitUntil(30_000) { runCatching { rule.onNodeWithTag(AgencyTags.SEND).assertIsEnabled() }.isSuccess }
            rule.onNodeWithTag(AgencyTags.SEND).performClick()
            if (runCatching { rule.waitUntil(10_000) { mine() > before } }.isSuccess) return
        }
        throw AssertionError("not sent: $text")
    }

    /**
     * A tap on [tag]: scrolled into view (a sideways row; the card area and the panels under the conversation, which scroll
     * once every rollout week is open) and tapped once it is enabled.
     */
    private fun tap(tag: String, timeoutMs: Long = 30_000) {
        reveal(tag, timeoutMs)
        rule.waitUntil(timeoutMs) { runCatching { rule.onNodeWithTag(tag, useUnmergedTree = true).assertIsEnabled() }.isSuccess }
        rule.onNodeWithTag(tag, useUnmergedTree = true).performClick()
    }

    /** Scrolls [tag] into view (see [tap]). */
    private fun reveal(tag: String, timeoutMs: Long = 30_000) {
        rule.waitUntil(timeoutMs) { rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        runCatching { rule.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo() }
        for (area in listOf(AgencyTags.PANELS, AgencyTags.CARD_AREA)) {
            if (rule.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag(area)), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) continue
            val panel = rule.onNodeWithTag(area)
            val box = panel.fetchSemanticsNode().boundsInRoot
            val n = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            val top = n.positionInRoot.y
            val bottom = top + n.size.height
            val dy = when { top < box.top -> top - box.top; bottom > box.bottom -> bottom - box.bottom; else -> 0f }
            if (dy != 0f) panel.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dy) }
            rule.waitForIdle()
        }
    }

    @Before fun setUp() {
        // The notification question (asked once 入社建檔 is done) and the microphone's would cover the company: answered
        // beforehand, as on a phone where he said yes.
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf(android.Manifest.permission.POST_NOTIFICATIONS, android.Manifest.permission.RECORD_AUDIO).forEach { ui.grantRuntimePermission(context.packageName, it) }
        fresh()
    }

    @After fun tearDown() {
        lastLines = runCatching {
            runBlocking { ready().store.messages.recent(40) }.joinToString("\n") { "${it.who} ${it.kind} unsent=${it.unsent} ${it.text.take(160)}" }
        }.getOrDefault("(not open)")
        watch?.cancel()
        scenario?.close()
        fresh()
    }

    @Test fun e01_freshInstallOpensTheCompany() {
        open()
        // The card loads after the screen (its numbers are read off the main thread).
        rule.waitUntil(30_000) { rule.onAllNodesWithTag(AgencyTags.TRAINEE).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(states.toString(), states.none { it == StartState.Importing })
        assertEquals(AgencyPrefKeys.MIGRATED_DONE, prefs(AgencyPrefKeys.NAME).getInt(AgencyPrefKeys.MIGRATED, 0))
        // 入社建檔 starts on the first open (T07): the manager's welcome and first question, written by code.
        runBlocking {
            val all = ready().store.messages.recent(10)
            assertEquals(1, all.size)
            assertEquals(Brain.BASELINE_KIND, all.single().kind)
        }
        rule.onNodeWithTag(OnboardingTags.STEP + OnboardingStep.BODY.name).assertExists()
        assertTrue(fake.seen.isEmpty())
    }

    @Test fun e02_upgradeFromV083KeepsRecordsAndEncryptsThem() {
        seedV083()
        val state = File(context.filesDir, "coach/state.json").readBytes()
        open()
        runBlocking {
            val store = ready().store
            assertEquals(12, store.messages.count()) // 11 of his, then the welcome of 入社建檔 (v0.8.3 had none)
            assertEquals(4, store.meals.count())
            assertEquals(5, store.meals.countItems())
            assertEquals(1, store.meals.pending().size)
            assertEquals(2, store.weights.count())
            assertEquals(8, store.food.count())
            assertTrue(store.journal.migrations().single { it.id == LegacyImporter.LOG_ID }.ok)
            val all = store.messages.recent(20)
            assertTrue(all.dropLast(1).all { it.legacy })
            assertEquals(Brain.BASELINE_KIND, all.last().kind)
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
        say("姐姐在嗎")
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
     * Review M13 and T12: with no network his message is kept and shown, marked 「沒送出」 with 「重送」 beside it, and nothing
     * is answered; once the network is back one tap on 重送 gets a real answer and the mark goes, on the same row (no copy).
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
        say("姐姐在嗎")
        fun mine() = runBlocking { ready().store.messages.recent(5) }.filter { it.kind != Brain.BASELINE_KIND }
        rule.waitUntil(60_000) { mine().singleOrNull()?.unsent == true }
        val stored = mine()
        assertEquals(listOf(Who.OWNER), stored.map { it.who })
        assertEquals("姐姐在嗎", stored[0].text)
        rule.waitUntil(10_000) { ready().brain.lastFail.value == Fail.OFFLINE }
        // The app did try and was cut off; nothing reached the model.
        assertTrue(fake.cutOff.get() > 0)
        assertTrue(fake.seen.isEmpty())
        // On screen: his own bubble with his words, 「沒送出」 and 「重送」.
        rule.onNode(hasTestTag(AgencyTags.BUBBLE + stored[0].id) and hasAnyDescendant(hasText("姐姐在嗎"))).assertExists()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(ChatTags.UNSENT + stored[0].id).fetchSemanticsNodes().isNotEmpty() }
        // The network is back: one tap on 重送 and the company answers; the same row, the mark gone.
        fake.online = true
        tap(ChatTags.RESEND + stored[0].id)
        rule.waitUntil(60_000) { rule.onAllNodes(hasText("現在去喝水。", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(ChatTags.UNSENT + stored[0].id).fetchSemanticsNodes().isEmpty() }
        val after = mine()
        assertEquals(listOf(Who.OWNER, Who.COMPANY), after.map { it.who })
        assertEquals(stored[0].id, after[0].id)
        assertFalse(after[0].unsent)
        assertEquals(WordedBy.MODEL, after[1].wordedBy)
        assertEquals(null, ready().brain.lastFail.value)
    }

    /**
     * Arch T12 e2e: the fake model calls `propose_change`; the plan stays as it is until he taps 好 on the card; then the
     * schedule has a new version written by that proposal.
     */
    @Test fun e10_proposalNeedsConfirm() {
        app.keyVault.put(Provider.DEEPSEEK, FAKE_KEY)
        fake.toolCall = "propose_change" to """{"section":"schedule","ops":[{"path":"class_wake","value":"08:20"}],"reason":"早一點起來吃早餐"}"""
        fake.reply = "我提了一個調整，你看卡片。"
        open()
        say("我想早一點起床")
        rule.waitUntil(60_000) { runBlocking { ready().store.proposals.withStatus(ProposalStatus.PENDING) }.isNotEmpty() }
        val p = runBlocking { ready().store.proposals.withStatus(ProposalStatus.PENDING) }.single()
        assertEquals(1, runBlocking { ready().store.rules.section(PlanSectionKey.SCHEDULE) }!!.version)
        rule.waitUntil(30_000) { rule.onAllNodesWithTag(ProposalTags.YES + p.id).fetchSemanticsNodes().isNotEmpty() }
        tap(ProposalTags.YES + p.id)
        rule.waitUntil(30_000) { runBlocking { ready().store.rules.section(PlanSectionKey.SCHEDULE) }!!.version == 2 }
        val section = runBlocking { ready().store.rules.section(PlanSectionKey.SCHEDULE) }!!
        assertEquals(p.id, section.changedBy)
        assertEquals(ProposalStatus.ACCEPTED, runBlocking { ready().store.proposals.get(p.id) }!!.status)
        rule.waitUntil(30_000) { rule.onAllNodes(hasText("好，改了：上課日起床 08:30 → 08:20。")).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * Arch T07: the three photos (test camera, real downsizing) and a recording (test microphone, real streaming vault) are
     * handed in through the 入社建檔 panel, sealed with the phone's Keystore key, and can be opened again from 入社資料.
     */
    @Test fun e06_baselinePhotosAndRecordingWithTheCameraSeam() {
        open()
        rule.waitUntil(30_000) { rule.onAllNodesWithTag(OnboardingTags.STEP + OnboardingStep.BODY.name).fetchSemanticsNodes().isNotEmpty() }
        say("身高 170 體重 78")
        for (step in OnboardingStep.PHOTOS) {
            rule.waitUntil(30_000) { rule.onAllNodesWithTag(OnboardingTags.STEP + step.name).fetchSemanticsNodes().isNotEmpty() }
            tap(OnboardingTags.TAKE_PHOTO)
        }
        rule.waitUntil(30_000) { rule.onAllNodesWithTag(OnboardingTags.REC_START).fetchSemanticsNodes().isNotEmpty() }
        tap(OnboardingTags.REC_START)
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(OnboardingTags.REC_STOP).fetchSemanticsNodes().isNotEmpty() }
        tap(OnboardingTags.REC_STOP)
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(OnboardingTags.REC_HAND_IN).fetchSemanticsNodes().isNotEmpty() }
        tap(OnboardingTags.REC_HAND_IN)
        rule.waitUntil(30_000) { rule.onAllNodesWithTag(OnboardingTags.STEP + OnboardingStep.PUSHUPS.name).fetchSemanticsNodes().isNotEmpty() }
        val vault = MediaVault.forApp(context)
        val b = runBlocking { ready().store.baselines.get(Baseline.JOIN)!! }
        val photos = listOf(b.photoFront!!, b.photoSide!!, b.photoBack!!)
        photos.forEach { id ->
            val plain = vault.get(id)
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(plain, 0, plain.size, bounds)
            assertEquals(1600, maxOf(bounds.outWidth, bounds.outHeight))
        }
        assertEquals(sha(asset("agency-v2-baseline/recording-1795225200000.m4a")), sha(vault.get(b.recording!!)))
        // Sealed on disk in the segmented format; no plaintext left in the camera or recorder cache.
        MediaVault.mediaDir(context).listFiles { f -> f.name.endsWith(".bin") }!!.forEach { f ->
            val bytes = f.readBytes()
            assertEquals(f.name, 2, bytes[0].toInt())
            assertFalse(f.name, contains(bytes, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
            assertFalse(f.name, contains(bytes, "ftyp".toByteArray()))
        }
        assertTrue(MediaVault.cameraDir(context).list().orEmpty().isEmpty())
        assertTrue(File(context.cacheDir, "record").list().orEmpty().isEmpty())
        // 入社資料 shows the three photos again (decrypted into memory).
        tap(OnboardingTags.FILE)
        photos.forEach { id -> rule.waitUntil(10_000) { rule.onAllNodesWithTag(OnboardingTags.THUMB + id).fetchSemanticsNodes().isNotEmpty() } }
        rule.onNodeWithTag(OnboardingTags.FILE_PLAY).assertExists()
        assertTrue(fake.seen.isEmpty())
    }

    /**
     * Arch T15 e2e: a meal reported in words is 「未證實」; 拍這餐 (the camera seam, real downsizing and sealing with the
     * phone's key) puts the photo on it, the thumbnail opens in memory, and no plaintext is left in the camera cache.
     */
    @Test fun e11_mealWithPhoto() {
        app.keyVault.put(Provider.DEEPSEEK, FAKE_KEY)
        fake.intakeJson = """{"kind":"meal","items":[{"name":"即食雞胸","portion":1,"fried":false,"processed":false,"zero_cal":false,"kcal_low":100,"kcal_high":130,"protein_low":20,"protein_high":25}]}"""
        open()
        runBlocking {
            ready().store.profile.put((ready().store.profile.get() ?: Profile()).copy(onboardingDone = true))
            ready().store.baselines.upsert(Baseline(Baseline.JOIN, heightCm = 170.0, weightKg = 78.0, completedAt = 1))
        }
        say("吃了即食雞胸")
        rule.waitUntil(60_000) { runBlocking { ready().store.meals.count() } == 1 }
        val meal = runBlocking { ready().store.meals.between("0000-01-01", "9999-12-31") }.single()
        assertFalse(meal.verified)
        rule.waitUntil(30_000) { rule.onAllNodesWithTag(MealTags.PHOTO).fetchSemanticsNodes().isNotEmpty() }
        tap(MealTags.PHOTO)
        rule.waitUntil(30_000) { runBlocking { ready().store.meals.get(meal.id) }!!.verified }
        val photo = runBlocking { ready().store.meals.get(meal.id) }!!.photoMediaId!!
        val plain = MediaVault.forApp(context).get(photo)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(plain, 0, plain.size, bounds)
        assertEquals(1600, maxOf(bounds.outWidth, bounds.outHeight))
        assertTrue(MediaVault.cameraDir(context).list().orEmpty().isEmpty())
        rule.waitUntil(30_000) { rule.onAllNodesWithTag(MealTags.THUMB + photo).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * Arch T16 e2e: 「附近有什麼」 runs one search (through the gate, his area and a dish only); the fake search returns two
     * results, the model's reply names an invented third place, and only the real ones reach him (the invented one is guarded
     * out); the two results show as cards.
     */
    @Test fun e12_searchShowsOnlyRealResults() {
        app.keyVault.put(Provider.DEEPSEEK, FAKE_KEY)
        fake.searchResults = org.json.JSONArray()
            .put(org.json.JSONObject().put("url", "https://example.org/a").put("title", "健康雞胸便當大安店").put("summary", "雞胸便當").put("rerank_score", 2.0))
            .put(org.json.JSONObject().put("url", "https://example.org/b").put("title", "好味道自助餐").put("summary", "自助餐").put("rerank_score", 1.5))
        fake.toolCall = "search_places" to """{"dish":"雞胸便當"}"""
        fake.reply = "附近有健康雞胸便當大安店。也可以去阿明牛肉麵店。"
        open()
        runBlocking { ready().store.profile.put((ready().store.profile.get() ?: Profile()).copy(areaText = "大安區")) }
        say("附近有什麼")
        rule.waitUntil(60_000) { runBlocking { ready().store.messages.recent(10) }.any { it.refs.results.size == 2 } }
        val m = runBlocking { ready().store.messages.recent(10) }.last { it.refs.results.isNotEmpty() }
        assertEquals("附近有健康雞胸便當大安店。", m.text)
        val search = fake.seen.single { it.path.endsWith("/v1/tools/search") }
        assertTrue(search.body, search.body.contains("大安區 雞胸便當 推薦"))
        m.refs.results.forEach { id -> rule.waitUntil(30_000) { rule.onAllNodesWithTag(ResultTags.CARD + id).fetchSemanticsNodes().isNotEmpty() } }
    }

    /**
     * T18–T19 on the phone: 簽到 opens the score picker, the core timer guides every move and reports by itself (the day's
     * polaroid), 簽退 opens the bedtime review, whose answers make the 檢討卡 (no key: code lines only).
     */
    @Test fun e13_signInCoreTimerAndBedtimeReview() {
        open()
        runBlocking {
            ready().store.profile.put((ready().store.profile.get() ?: Profile()).copy(onboardingDone = true))
            ready().store.baselines.upsert(Baseline(Baseline.JOIN, heightCm = 170.0, weightKg = 78.0, completedAt = 1))
        }
        // A day without a run (the phone's real clock): the core may be done any time (on a delivery day it waits for 收工).
        say("今天休息")
        rule.waitUntil(60_000) { runBlocking { ready().store.messages.recent(5) }.any { it.text.startsWith("好，今天換成休息日") } }
        say("刮好了")
        rule.waitUntil(60_000) { rule.onAllNodesWithTag(SignTags.SIGN_IN).fetchSemanticsNodes().isNotEmpty() }
        tap(SignTags.SIGN_IN)
        rule.waitUntil(30_000) { rule.onAllNodesWithTag(SignTags.PICKER).fetchSemanticsNodes().isNotEmpty() }
        tap(SignTags.SCORE + "3")
        tap(SignTags.SEND)
        rule.waitUntil(30_000) { runBlocking { ready().store.conditions.count() } == 1 }
        rule.waitUntil(30_000) { rule.onAllNodesWithTag(SignTags.PICKER).fetchSemanticsNodes().isEmpty() }
        tap(TrainingTags.CORE)
        rule.waitUntil(30_000) { rule.onAllNodesWithTag(TrainingTags.START).fetchSemanticsNodes().isNotEmpty() }
        tap(TrainingTags.START)
        repeat(7) { tap(TrainingTags.NEXT) }
        rule.waitUntil(30_000) { runBlocking { ready().store.training.count() } == 1 }
        rule.waitUntil(30_000) { runBlocking { ready().brain.photocards() }.size == 1 }
        rule.waitUntil(30_000) { rule.onAllNodesWithTag(SignTags.SIGN_OUT).fetchSemanticsNodes().isNotEmpty() }
        tap(SignTags.SIGN_OUT)
        rule.waitUntil(30_000) { rule.onAllNodesWithTag(SignTags.REVIEW).fetchSemanticsNodes().isNotEmpty() }
        reveal(SignTags.GOOD)
        rule.onNodeWithTag(SignTags.GOOD).performTextInput("核心照計時做完")
        reveal(SignTags.FIX)
        rule.onNodeWithTag(SignTags.FIX).performTextInput("早點睡")
        tap(SignTags.REVIEW_SEND)
        rule.waitUntil(30_000) { runBlocking { ready().store.messages.recent(5) }.any { it.text.startsWith("今天的檢討卡") } }
    }

    /**
     * T19c on the phone: 找影片 on the 理論課 tab sends code's words to the search only; the real result is a candidate until 好;
     * 看完了 brings three questions (each quote checked by code); 交卷 is graded by the app; the note passes the lesson.
     */
    @Test fun e14_lessonFromSearchToPass() {
        app.keyVault.put(Provider.DEEPSEEK, FAKE_KEY)
        val text = (1..5).joinToString("") { "吐氣的時候用嘴巴發出嘶的聲音，盡量維持十五秒以上。唱高音的時候不要用喉嚨擠，要把氣往下沉。吸氣的時候肩膀不要抬起來。" }
        fake.searchResults = org.json.JSONArray()
            .put(org.json.JSONObject().put("url", "https://www.youtube.com/watch?v=e2e").put("title", "腹式呼吸練習").put("summary", "呼吸").put("content", text).put("rerank_score", 2.0))
        fun q(q: String, o: List<String>, a: Int, quote: String) = org.json.JSONObject().put("q", q).put("options", org.json.JSONArray(o)).put("answer", a).put("quote", quote)
        fake.quizJson = org.json.JSONObject().put(
            "questions",
            org.json.JSONArray()
                .put(q("嘶的聲音要維持多久以上？", listOf("五秒", "十秒", "十五秒", "三十秒"), 2, "盡量維持十五秒以上"))
                .put(q("唱高音時氣要怎樣？", listOf("往上提", "往下沉", "憋住", "用喉嚨擠"), 1, "要把氣往下沉"))
                .put(q("吸氣時肩膀要怎樣？", listOf("抬起來", "不要抬起來", "往前縮", "往後夾"), 1, "吸氣的時候肩膀不要抬起來")),
        ).toString()
        open()
        rule.onNodeWithTag(AgencyTags.TAB + AgencyTab.LESSONS.name).performClick()
        tap(LessonTags.FIND)
        rule.waitUntil(60_000) { runBlocking { ready().store.lessons.all() }.isNotEmpty() }
        val id = runBlocking { ready().store.lessons.all() }.single().id
        val search = fake.seen.single { it.path.endsWith("/v1/tools/search") }
        assertTrue(search.body, search.body.contains("發聲練習 唱歌技巧 教學 入門 初學 影片"))
        rule.waitUntil(30_000) { rule.onAllNodesWithTag(LessonTags.YES + id).fetchSemanticsNodes().isNotEmpty() }
        tap(LessonTags.YES + id)
        rule.waitUntil(30_000) { rule.onAllNodesWithTag(LessonTags.WATCHED + id).fetchSemanticsNodes().isNotEmpty() }
        tap(LessonTags.WATCHED + id)
        rule.waitUntil(60_000) { rule.onAllNodesWithTag(LessonTags.SUBMIT).fetchSemanticsNodes().isNotEmpty() }
        listOf("0-2", "1-1", "2-1").forEach { tap(LessonTags.OPTION + it) }
        tap(LessonTags.SUBMIT)
        rule.waitUntil(30_000) { rule.onAllNodesWithTag(LessonTags.NOTE).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(LessonTags.NOTE).performScrollTo().performTextInput("嘶的聲音要維持十五秒以上，高音時氣往下沉，吸氣肩膀不要抬起來，每天早上練五分鐘。")
        tap(LessonTags.NOTE_SEND)
        rule.waitUntil(30_000) { runBlocking { ready().store.lessons.get(id) }!!.passed }
        assertEquals(3, runBlocking { ready().store.lessons.get(id) }!!.score)
    }

    @Test fun e15_aWholeDayAcrossTheFeatures() = wholeDay()

    /**
     * 整天情境 on the phone, every rollout week open (week 4): an invented Saturday (no class, a delivery day) on the company's
     * test clock. 簽到 with 生病 on the score picker (L2's today-only proposal; he keeps the plan), the first meal reported in
     * words and then photographed, his line kept as 「沒送出」 while offline and answered after 重送, dinner, 出門跑單, 收工,
     * the core on its timer and the rest of the block reported, and a 簽退 after tonight's latest time: one breach (the bedtime review sheet is e13's).
     */
    fun wholeDay() {
        val day = java.time.LocalDate.parse("2026-12-12")
        fun at(hhmm: String) {
            val (h, m) = hhmm.split(":").map { it.toInt() }
            val d = if (h < 5) day.plusDays(1) else day
            clock.fixed = d.atTime(h, m).atZone(AgencyClock.TAIPEI).toInstant()
        }
        fun company() = runBlocking { ready().store.messages.recent(60) }.filter { it.who == Who.COMPANY }.map { it.text }
        fun meal(kcal: Int, protein: Int, name: String) {
            fake.intakeJson = """{"kind":"meal","items":[{"name":"$name","portion":1,"fried":false,"processed":false,"zero_cal":false,"kcal_low":${kcal - 50},"kcal_high":$kcal,"protein_low":$protein,"protein_high":$protein}]}"""
        }
        at("10:50")
        open()
        runBlocking {
            ready().store.profile.put((ready().store.profile.get() ?: Profile()).copy(onboardingDone = true, joinDate = "2026-11-01"))
            ready().store.baselines.upsert(Baseline(Baseline.JOIN, heightCm = 170.0, weightKg = 78.0, pushupsMax = 18, plankMaxSec = 65, completedAt = 1))
            ready().brain.setIdol("小雨", MemberColour.PRESETS[0].second)
        }
        val plan = runBlocking { ready().brain.nerve.plan(day) }
        val signOutBy = plan.slot(SlotKey.SIGN_OUT)!!.time
        // 11:30 簽到, then the score picker: 3 and 生病. L2 proposes today's change; he keeps the plan (不要).
        at("11:30")
        say("簽到")
        rule.waitUntil(30_000) { rule.onAllNodesWithTag(SignTags.PICKER).fetchSemanticsNodes().isNotEmpty() }
        tap(SignTags.SCORE + "2")
        tap(SignTags.BODY + "1")
        tap(SignTags.SEND)
        rule.waitUntil(30_000) { runBlocking { ready().store.conditions.count() } == 1 }
        rule.waitUntil(30_000) { runBlocking { ready().store.proposals.withStatus(ProposalStatus.PENDING) }.isNotEmpty() }
        val sick = runBlocking { ready().store.proposals.withStatus(ProposalStatus.PENDING) }.single()
        tap(ProposalTags.NO + sick.id)
        rule.waitUntil(30_000) { runBlocking { ready().store.proposals.get(sick.id) }!!.status == ProposalStatus.REJECTED }
        // 13:05 the first meal in words (「未證實」), then 拍這餐 puts the photo on it.
        at("13:05")
        meal(690, 44, "烤鮭魚便當")
        say("第一餐吃了烤鮭魚便當")
        rule.waitUntil(60_000) { runBlocking { ready().store.meals.count() } == 1 }
        val first = runBlocking { ready().store.meals.between("0000-01-01", "9999-12-31") }.single()
        assertFalse(first.verified)
        tap(MealTags.PHOTO)
        rule.waitUntil(30_000) { runBlocking { ready().store.meals.get(first.id) }!!.verified }
        // 15:00 no network: his line is kept as 「沒送出」; back online, 重送 gets the answer on the same row.
        at("15:00")
        fake.intakeJson = null
        fake.online = false
        say("姐姐在嗎")
        rule.waitUntil(60_000) { runBlocking { ready().store.messages.recent(60) }.any { it.who == Who.OWNER && it.text == "姐姐在嗎" && it.unsent } }
        val unsent = runBlocking { ready().store.messages.recent(60) }.last { it.who == Who.OWNER && it.text == "姐姐在嗎" }
        rule.waitUntil(10_000) { ready().brain.lastFail.value == Fail.OFFLINE }
        fake.online = true
        tap(ChatTags.RESEND + unsent.id)
        rule.waitUntil(60_000) { runBlocking { ready().store.messages.get(unsent.id) }!!.unsent.not() }
        rule.waitUntil(30_000) { company().any { it.contains("現在去喝水。") } }
        // 17:40 dinner.
        at("17:40")
        meal(545, 35, "雞腿便當")
        say("晚餐吃了雞腿便當")
        rule.waitUntil(60_000) { runBlocking { ready().store.meals.count() } == 2 }
        // 19:10 out on the run; 02:30 收工.
        at("19:10")
        tap(AgencyTags.RIDE_START)
        rule.waitUntil(30_000) { runBlocking { ready().brain.nerve.state(day) }.riding }
        at("02:30")
        tap(AgencyTags.RIDE_END)
        rule.waitUntil(30_000) { !runBlocking { ready().brain.nerve.state(day) }.riding }
        // The block after 收工: the core on its timer, the rounds, the cardio and the stretch reported.
        at("02:38")
        tap(TrainingTags.CORE)
        tap(TrainingTags.START)
        repeat(7) { tap(TrainingTags.NEXT) }
        rule.waitUntil(30_000) { runBlocking { ready().store.training.onDay(day.toString()) }.any { it.kind == TrainingKind.CORE7 } }
        runBlocking {
            at("02:50"); ready().brain.strengthDone(StrengthReport(3, mapOf("push_up" to 15, "squat" to 20, "plank" to 60), extra = 2, seconds = 900))
            at("03:03"); ready().brain.cardioDone(590)
            at("03:15"); ready().brain.stretchDone(600)
        }
        val kinds = runBlocking { ready().store.training.onDay(day.toString()) }.map { it.kind }.toSet()
        assertTrue(kinds.toString(), kinds.containsAll(listOf(TrainingKind.CORE7, TrainingKind.STRENGTH, TrainingKind.STRETCH)))
        // 簽退 ten minutes after tonight's latest time: one breach.
        val (h, m) = signOutBy.split(":").map { it.toInt() }
        val late = java.time.LocalTime.of(h, m).plusMinutes(10)
        at("%02d:%02d".format(late.hour, late.minute))
        tap(SignTags.SIGN_OUT)
        rule.waitUntil(30_000) { company().any { it.contains("記一次違規：超過最晚簽退的時間（$signOutBy）") } }
        assertEquals(1, runBlocking { ready().brain.nerve.state(day) }.breaches)
        assertEquals(2, runBlocking { ready().store.meals.count() })
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
