package app.dshmobile.agency.migrate

import android.content.Context
import android.content.SharedPreferences
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.agency.core.migrate.AgencyPrefKeys
import app.agency.core.migrate.ImportKind
import app.agency.core.migrate.ImportNote
import app.agency.core.migrate.LegacyMapper
import app.agency.core.migrate.StateOrigin
import app.agency.core.model.Baseline
import app.agency.core.model.CompanyRules
import app.agency.core.model.Message
import app.agency.core.model.MigrationLog
import app.agency.core.model.MigrationSource
import app.agency.core.model.Profile
import app.agency.core.model.RoleKey
import app.agency.core.model.Who
import app.agency.core.rules.FoodTable
import app.agency.core.store.AgencyStore
import app.agency.core.store.JournalRepo
import app.agency.core.store.MessageRepo
import app.dshmobile.agency.data.AgencyDatabase
import app.dshmobile.agency.data.RoomAgencyStore
import app.dshmobile.agency.media.MediaVault
import app.dshmobile.coach.CoachJson
import app.dshmobile.coach.CoachMessage
import app.dshmobile.coach.CoachState
import app.dshmobile.coach.Speaker
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.KeyGenerator
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [LegacyImporter] on Room (in memory, plain SQLite) with the coach's own files and prefs (fixtures in
 * android/core/src/test/resources/legacy, invented records) and a vault on a JVM key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LegacyImporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val filesDir by lazy { tmp.newFolder("files") }
    private val coach get() = File(filesDir, "coach")
    private val db = AgencyDatabase.build(context, null, FrameworkSQLiteOpenHelperFactory())
    private val store = RoomAgencyStore(db)
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val mediaDir by lazy { tmp.newFolder("media") }
    private val vault by lazy { MediaVault(mediaDir) { key } }
    private val agencyPrefs = prefs("agency")

    @After fun close() = db.close()

    private fun fixture(name: String): ByteArray = requireNotNull(javaClass.getResource("/legacy/$name")) { name }.readBytes()

    /** A fresh prefs file; with [xml], his saved `dshm_coach` file exactly as SharedPreferences wrote it. */
    private fun prefs(tag: String, xml: String? = null): SharedPreferences {
        val name = "$tag-${UUID.randomUUID()}"
        xml?.let { File(context.dataDir, "shared_prefs/$name.xml").apply { parentFile!!.mkdirs() }.writeBytes(fixture(it)) }
        return context.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    private fun seedV083() {
        coach.mkdirs()
        File(coach, "state.json").writeBytes(fixture("v083-state.json"))
        File(coach, "messages.jsonl").writeBytes(fixture("v083-messages.jsonl"))
    }

    private val baselineFiles = listOf("photo-1795224000000.jpg", "photo-1795224600000.jpg", "recording-1795225200000.m4a")

    private fun seedV2() {
        coach.mkdirs()
        File(coach, "state.json").writeBytes(fixture("agency-v2-state.json"))
        File(coach, "messages.jsonl").writeBytes(fixture("agency-v2-messages.jsonl"))
        File(coach, "baseline").mkdirs()
        baselineFiles.forEach { File(coach, "baseline/$it").writeBytes(fixture("agency-v2-baseline/$it")) }
    }

    private fun importer(
        legacy: SharedPreferences,
        into: AgencyStore = store,
        agency: SharedPreferences = agencyPrefs,
        media: MediaVault = vault,
        now: Long = NOW,
    ) = LegacyImporter(filesDir, legacy, agency, into, media) { now }

    private val kept get() = File(filesDir, "coach.migrated-$NOW")

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun snapshot(dir: File): Map<String, String> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to sha(it.readBytes()) }

    /** What the database holds, per kind. */
    private fun dbCounts(): Map<ImportKind, Int> = runBlocking {
        linkedMapOf(
            ImportKind.PROFILE to if (store.profile.get() == null) 0 else 1,
            ImportKind.RULES to store.rules.history().size,
            ImportKind.MEALS to store.meals.count(),
            ImportKind.MEAL_ITEMS to store.meals.countItems(),
            ImportKind.PENDING to store.meals.pending().size,
            ImportKind.WEIGHTS to store.weights.count(),
            ImportKind.TRAININGS to store.training.count(),
            ImportKind.FOOD to store.food.count(),
            ImportKind.AUDIT to store.journal.audits().size,
            ImportKind.BASELINES to store.baselines.count(),
            ImportKind.MEDIA to store.media.count(),
            ImportKind.MESSAGES to store.messages.count(),
        )
    }

    @Test fun v083RecordsAllArriveAndTheFolderIsKeptAside() = runBlocking {
        seedV083()
        val before = snapshot(coach)
        val r = importer(prefs("legacy", "v083-prefs.xml")).run()
        assertTrue(r.ok)
        assertEquals(
            mapOf(
                ImportKind.PROFILE to 1, ImportKind.RULES to 1, ImportKind.MEALS to 4, ImportKind.MEAL_ITEMS to 5, ImportKind.PENDING to 1,
                ImportKind.WEIGHTS to 2, ImportKind.TRAININGS to 0, ImportKind.FOOD to 8, ImportKind.AUDIT to 1, ImportKind.BASELINES to 0,
                ImportKind.MEDIA to 0, ImportKind.MESSAGES to 11,
            ),
            r.counts,
        )
        assertEquals(r.counts, dbCounts())
        assertEquals(r.counts, r.inserted)

        val fried = store.meals.get("0b6f3c2e-1a4d-4c55-9a51-3f7d2e8a9c01")!!
        assertEquals(listOf("FRIED"), fried.breaches)
        assertTrue(fried.legacy)
        assertEquals(2, store.meals.get("9a3c7e21-4f6b-4d08-b2a5-1e9f8c3d6a23")!!.items.size)
        val geli = store.food.get("給力盒子 南加州烤雞腿")!!
        assertEquals(714, geli.kcal)
        assertEquals(48.0, geli.proteinG!!, 0.0)
        assertEquals(LegacyMapper.AUDIT_SEED_CORRECTED, store.journal.audits().single().what)
        assertNotNull(store.food.get("牛肉麵"))
        assertNull(store.food.get("蚵仔麵線一碗")) // he had deleted that seed

        val rules = store.rules.current()!!
        assertEquals(listOf(75.0, 68.0), rules.goalsKg)
        assertEquals(1700, rules.quotaKcal)
        assertEquals(800, rules.mealCapKcal)
        val profile = store.profile.get()!!
        assertEquals(172.0, profile.heightCm!!, 0.0)
        assertEquals(81.5, profile.startWeightKg!!, 0.0)
        assertEquals("小雨娛樂", profile.companyName)
        assertEquals("2026-11-21", profile.joinDate)

        val msgs = store.messages.recent(100)
        assertEquals(11, msgs.size)
        assertTrue(msgs.all { it.legacy })
        assertEquals(listOf(null, RoleKey.BODY, null, RoleKey.BODY), msgs.take(4).map { it.role })
        assertEquals(RoleKey.MANAGER, msgs.first { it.kind == "chat" }.role)
        assertEquals(Who.OWNER, msgs[0].who)

        val log = store.journal.migrations().single()
        assertTrue(log.ok)
        assertEquals(MigrationSource.V083_JSON, log.source)
        assertTrue(log.countsJson, log.countsJson.contains("\"MESSAGES\":11"))
        val note = ImportNote.fromJson(log.note!!)
        assertEquals(StateOrigin.STATE.name, note.stateOrigin)
        assertEquals(1, note.unreadableMessageLines)
        assertEquals(mapOf("bedtime" to "00:30"), note.oldSettings)

        assertEquals(1, agencyPrefs.getInt(AgencyPrefKeys.MIGRATED, 0))
        assertEquals("DEEPSEEK", agencyPrefs.getString(AgencyPrefKeys.PROVIDER, null))
        assertEquals("deepseek-flash", agencyPrefs.getString(AgencyPrefKeys.MODEL_DEEPSEEK, null))
        assertEquals("2026-11-18", agencyPrefs.getString(AgencyPrefKeys.COMPAT_DAY, null))

        // The folder is renamed, never changed.
        assertFalse(coach.exists())
        val kept = File(filesDir, "coach.migrated-$NOW")
        assertEquals(kept, r.keptAt)
        assertEquals(before, snapshot(kept))
    }

    @Test fun agencyRecordsAndTheBaselineFilesArrive() = runBlocking {
        seedV2()
        val r = importer(prefs("legacy", "agency-v2-prefs.xml")).run()
        assertTrue(r.ok)
        assertEquals(r.counts, dbCounts())
        assertEquals(3, r.counts[ImportKind.MEALS])
        assertEquals(2, r.counts[ImportKind.TRAININGS])
        assertEquals(3, r.counts[ImportKind.MEDIA])
        assertEquals(9, r.counts[ImportKind.MESSAGES])

        assertEquals(listOf("FRIED", "MEAL_CAP_700_LEGACY"), store.meals.get("d2be1a96-cdef-4a4f-8acd-37b2f0e3de13")!!.breaches)
        assertTrue(store.meals.get("d2be1a96-cdef-4a4f-8acd-37b2f0e3de13")!!.items[1].zeroCal)
        assertEquals(listOf(3, null), store.training.between("2026-11-21", "2026-11-22").map { it.rounds })

        // Each baseline file is in the vault and decrypts to its exact bytes; the baseline points at them.
        for (name in baselineFiles) {
            assertArrayEquals(name, fixture("agency-v2-baseline/$name"), vault.get(LegacyMapper.mediaId(name)))
            assertEquals(sha(fixture("agency-v2-baseline/$name")), store.media.get(LegacyMapper.mediaId(name))!!.sha256)
        }
        val b = store.baselines.get(Baseline.JOIN)!!
        assertEquals(LegacyMapper.mediaId("photo-1795224600000.jpg"), b.photoFront)
        assertEquals(LegacyMapper.mediaId("recording-1795225200000.m4a"), b.recording)
        assertEquals(18, b.pushupsMax)
        assertEquals(listOf("PLANK"), b.skipped)
        // Nothing in the vault is plaintext.
        assertTrue(mediaDir.listFiles()!!.none { f -> baselineFiles.any { sha(fixture("agency-v2-baseline/$it")) == sha(f.readBytes()) } })

        val rules = store.rules.current()!!
        assertEquals(listOf(78.0, 72.0), rules.goalsKg)
        assertEquals(55, rules.fatG)
        assertEquals(160, rules.carbsG)
        assertEquals(15, rules.marginPct)
        val log = store.journal.migrations().single()
        assertEquals(MigrationSource.AGENCY_V2_JSON, log.source)
        val note = ImportNote.fromJson(log.note!!)
        assertEquals(mapOf("t_lunch" to "12:30"), note.customisedSlots)
        assertEquals(listOf(50.0), note.goalsDropped)
        // The two v0.8.3 lines the agency build kept: the company one takes its role from its kind.
        assertEquals(listOf(null, RoleKey.BODY, RoleKey.MANAGER, null, RoleKey.MANAGER, null, RoleKey.BODY, null, RoleKey.BODY), store.messages.recent(50).map { it.role })
        assertEquals("CLAUDE", agencyPrefs.getString(AgencyPrefKeys.PROVIDER, null))
        assertEquals("claude-haiku-5-5", agencyPrefs.getString(AgencyPrefKeys.MODEL_CLAUDE, null))
        assertEquals("https://llm.example.invalid/v1", agencyPrefs.getString(AgencyPrefKeys.COMPAT_URL, null))
    }

    @Test fun aSecondRunInsertsNothing() = runBlocking {
        seedV2()
        val legacy = prefs("legacy", "agency-v2-prefs.xml")
        assertTrue(importer(legacy).run().ok)
        val counts = dbCounts()
        val sealed = snapshot(mediaDir)
        // As if the app died after the commit but before the flag and the rename: same files, no flag.
        File(filesDir, "coach.migrated-$NOW").renameTo(coach)
        val again = importer(legacy, agency = prefs("agency")).run()
        assertTrue(again.ok)
        assertEquals(ImportKind.entries.associateWith { 0 }, again.inserted)
        assertEquals(counts, dbCounts())
        assertEquals(sealed, snapshot(mediaDir))
        assertEquals(1, store.journal.migrations().size)
        assertEquals(1, store.rules.history().size)
    }

    @Test fun aFinishedImportDoesNotRunAgain() = runBlocking {
        seedV083()
        val before = snapshot(coach)
        agencyPrefs.edit().putInt(AgencyPrefKeys.MIGRATED, 1).commit()
        val r = importer(prefs("legacy", "v083-prefs.xml")).run()
        assertTrue(r.skipped)
        assertEquals(0, store.messages.count())
        // The folder of a finished import is only moved aside (its rename had failed), every byte unchanged.
        assertFalse(coach.exists())
        assertEquals(kept, r.keptAt)
        assertEquals(before, snapshot(kept))
    }

    @Test fun aDamagedStateWithAGoodTempFileImportsTheTempFile() = runBlocking {
        coach.mkdirs()
        File(coach, "state.json").writeBytes(fixture("damaged-state.json"))
        File(coach, "state.json.tmp").writeBytes(fixture("agency-v2-state.json"))
        val r = importer(prefs("legacy")).run()
        assertTrue(r.ok)
        assertEquals(StateOrigin.TMP.name, r.note!!.stateOrigin)
        assertEquals(3, store.meals.count())
        assertEquals(1, store.weights.count())
        assertArrayEquals(fixture("damaged-state.json"), File(filesDir, "coach.migrated-$NOW/state.json").readBytes())
    }

    @Test fun onlyTheTempFileOfACutSaveIsLeft() = runBlocking {
        coach.mkdirs()
        File(coach, "state.json.tmp").writeBytes(fixture("tmp-only/state.json.tmp"))
        val r = importer(prefs("legacy")).run()
        assertTrue(r.ok)
        assertEquals(StateOrigin.TMP.name, r.note!!.stateOrigin)
        assertEquals("午餐吃了雞胸便當", store.meals.get("a58b1763-9abc-4176-979a-048f0d5a4b26")!!.text)
        assertEquals(78.9, store.weights.get("b69c2874-abcd-4287-8a8b-159a1e6b5c27")!!.kg, 0.0)
    }

    @Test fun anUnreadableStateFailsKeepsTheFolderAndStillBringsWhatCanBeRead() = runBlocking {
        seedV083()
        // Cut inside the first meal: no complete record is left.
        val cut = fixture("v083-state.json").decodeToString().let { it.substring(0, it.indexOf("\"at\"")) }
        File(coach, "state.json").writeText(cut)
        val before = snapshot(coach)
        val legacy = prefs("legacy", "v083-prefs.xml")
        val r = importer(legacy).run()
        assertFalse(r.ok)
        assertEquals(StateOrigin.UNREADABLE.name, r.note!!.stateOrigin)
        // Not marked done, not renamed: the next start tries again, the folder stays for a later recovery.
        assertEquals(0, agencyPrefs.getInt(AgencyPrefKeys.MIGRATED, 0))
        assertTrue(coach.isDirectory)
        assertEquals(before, snapshot(coach))
        assertTrue(filesDir.list()!!.none { it.startsWith("coach.migrated-") })
        // No seed comes back into a table nobody could read; the messages and his settings are in.
        assertEquals(0, store.food.count())
        assertEquals(0, store.meals.count())
        assertEquals(11, store.messages.count())
        assertEquals(172.0, store.profile.get()!!.heightCm!!, 0.0)
        assertEquals("DEEPSEEK", agencyPrefs.getString(AgencyPrefKeys.PROVIDER, null))
        val log = store.journal.migrations().single()
        assertFalse(log.ok)
        assertEquals("STATE UNREADABLE", log.note)
        // The next start: nothing new, still one log row, still not done.
        val again = importer(legacy).run()
        assertFalse(again.ok)
        assertEquals(ImportKind.entries.associateWith { 0 }, again.inserted)
        assertEquals(1, store.journal.migrations().size)
        assertEquals(0, agencyPrefs.getInt(AgencyPrefKeys.MIGRATED, 0))
        assertTrue(coach.isDirectory)
    }

    @Test fun theCompleteRecordsOfACutStateAreSalvagedButTheImportStillFails() = runBlocking {
        seedV083()
        File(coach, "state.json").writeBytes(fixture("damaged-state.json"))
        val r = importer(prefs("legacy")).run()
        assertFalse(r.ok)
        val note = r.note!!
        assertEquals(StateOrigin.SALVAGED.name, note.stateOrigin)
        assertEquals("state.json", note.salvagedFrom)
        assertEquals(6, note.salvagedRecords)
        assertEquals(3, store.meals.count())
        assertEquals(1, store.weights.count())
        assertEquals(listOf("全家 紐奧良風味烤雞鮮蔬三明治", "給力盒子 南加州烤雞腿"), store.food.all().map { it.name }.sorted())
        assertEquals(714, store.food.get("給力盒子 南加州烤雞腿")!!.kcal)
        assertEquals(11, store.messages.count())
        assertEquals(0, agencyPrefs.getInt(AgencyPrefKeys.MIGRATED, 0))
        assertTrue(coach.isDirectory)
        assertEquals("STATE UNREADABLE, SALVAGED 6", store.journal.migrations().single().note)
    }

    @Test fun theRecordsTheCoachSetAsideAreImportedAndListed() = runBlocking {
        // A read error made the coach move his file aside and seed a fresh one.
        seedV083()
        File(coach, "state.json").renameTo(File(coach, "state.json.bad-1795300000000"))
        File(coach, "state.json").writeText(CoachJson.encode(CoachState(seeded = true, food = FoodTable.seed(1_795_300_000_100L))).toString())
        File(coach, "state.json.bad-1795300000500-copy").writeText("not a state file")
        val r = importer(prefs("legacy", "v083-prefs.xml")).run()
        assertTrue(r.ok)
        assertEquals(4, store.meals.count())
        assertEquals("宵夜鹽酥雞", store.meals.get("c47d2a90-3e1f-4b6c-8d72-5f0a9e1b3c34")!!.text)
        assertEquals(2, store.weights.count())
        assertEquals(1, store.meals.pending().size)
        assertNotNull(store.food.get("牛肉麵"))
        assertEquals(r.counts, dbCounts())
        val expected = mapOf("state.json.bad-1795300000000" to 9, "state.json.bad-1795300000500-copy" to -1)
        assertEquals(expected, r.note!!.setAsideFiles)
        assertEquals(expected, ImportNote.fromJson(store.journal.migrations().single().note!!).setAsideFiles)
        assertTrue(File(kept, "state.json.bad-1795300000000").isFile)
    }

    @Test fun aGoodStateNextToACutTempFileImportsTheStateAndSaysSo() = runBlocking {
        // The coach writes the temp file, then renames it: a temp file still there is a save that never landed.
        coach.mkdirs()
        File(coach, "state.json").writeBytes(fixture("agency-v2-state.json"))
        File(coach, "state.json.tmp").writeBytes(fixture("damaged-state.json"))
        val r = importer(prefs("legacy")).run()
        assertTrue(r.ok)
        assertEquals(StateOrigin.STATE.name, r.note!!.stateOrigin)
        assertTrue(r.note!!.tmpIgnored)
        assertEquals(2, store.training.count())
        assertTrue(ImportNote.fromJson(store.journal.migrations().single().note!!).tmpIgnored)
        assertArrayEquals(fixture("damaged-state.json"), File(kept, "state.json.tmp").readBytes())
    }

    @Test fun aV083FileTheAgencyBuildSavedAgainImportsWhole() = runBlocking {
        coach.mkdirs()
        File(coach, "state.json").writeBytes(fixture("agency-v1-upgraded-state.json"))
        File(coach, "messages.jsonl").writeBytes(fixture("agency-v2-messages.jsonl"))
        val r = importer(prefs("legacy", "v083-prefs.xml")).run()
        assertTrue(r.ok)
        assertEquals(
            mapOf(
                ImportKind.PROFILE to 1, ImportKind.RULES to 1, ImportKind.MEALS to 4, ImportKind.MEAL_ITEMS to 5, ImportKind.PENDING to 1,
                ImportKind.WEIGHTS to 2, ImportKind.TRAININGS to 1, ImportKind.FOOD to 8, ImportKind.AUDIT to 1, ImportKind.BASELINES to 1,
                ImportKind.MEDIA to 0, ImportKind.MESSAGES to 9,
            ),
            r.counts,
        )
        assertEquals(r.counts, dbCounts())
        assertEquals(MigrationSource.AGENCY_V2_JSON, store.journal.migrations().single().source)
        assertEquals(listOf("FRIED"), store.meals.get("0b6f3c2e-1a4d-4c55-9a51-3f7d2e8a9c01")!!.breaches)
        assertEquals(2, store.training.get("7c2e9d41-5a3b-4f86-9e17-2b8d6c4a0f39")!!.rounds)
        val b = store.baselines.get(Baseline.JOIN)!!
        assertEquals(15, b.pushupsMax)
        assertEquals(listOf("PHOTO", "RECORDING"), b.skipped)
        assertEquals(NOW, b.completedAt)
    }

    @Test fun aBaselineFileOverTheLimitIsNotedNotImportedAndKept() = runBlocking {
        seedV2()
        val big = File(coach, "baseline/recording-1795225300000.wav")
        RandomAccessFile(big, "rw").use { it.setLength(LegacyMapper.MAX_IMPORT_MEDIA_BYTES + 1) }
        val r = importer(prefs("legacy", "agency-v2-prefs.xml")).run()
        assertTrue(r.ok)
        assertEquals(listOf("recording-1795225300000.wav"), r.note!!.mediaTooLarge)
        assertEquals(3, store.media.count())
        assertFalse(vault.has(LegacyMapper.mediaId("recording-1795225300000.wav")))
        assertEquals(LegacyMapper.MAX_IMPORT_MEDIA_BYTES + 1, File(kept, "baseline/recording-1795225300000.wav").length())
    }

    @Test fun runningOutOfMemoryWhileSealingIsAnOrdinaryFailure() = runBlocking {
        seedV2()
        val before = snapshot(coach)
        val r = importer(prefs("legacy", "agency-v2-prefs.xml"), media = MediaVault(mediaDir) { throw OutOfMemoryError("invented") }).run()
        assertFalse(r.ok)
        assertEquals(0, agencyPrefs.getInt(AgencyPrefKeys.MIGRATED, 0))
        assertEquals(before, snapshot(coach))
        val log = store.journal.migrations().single()
        assertFalse(log.ok)
        assertEquals("IOException", log.note)
    }

    @Test fun eachBaselineFileIsDecryptedOnceToCheckIt() = runBlocking {
        seedV2()
        var opened = 0
        val counting = MediaVault(mediaDir) { opened++; key }
        val legacy = prefs("legacy", "agency-v2-prefs.xml")
        assertTrue(importer(legacy, media = counting).run().ok)
        // Per file: one key use to seal, one to read it back; none again for the count check.
        assertEquals(6, opened)
        kept.renameTo(coach)
        opened = 0
        assertTrue(importer(legacy, agency = prefs("agency"), media = counting).run().ok)
        // Already sealed with the same bytes: one read each, nothing sealed again.
        assertEquals(3, opened)
    }

    @Test fun aFolderAFinishedImportMovedAsideIsNotPlannedAgain() = runBlocking {
        File(filesDir, "coach.migrated-1795300000000").mkdirs()
        val r = importer(prefs("legacy", "v083-prefs.xml")).run()
        assertTrue(r.ok)
        assertTrue(r.skipped)
        assertEquals(1, agencyPrefs.getInt(AgencyPrefKeys.MIGRATED, 0))
        // No seed (one he deleted could come back) and nothing else is written.
        assertEquals(dbCounts().mapValues { 0 }, dbCounts())
        assertTrue(store.journal.migrations().isEmpty())
    }

    @Test fun aSecondRunAtTheSameTimeWaitsAndFindsTheImportDone() = runBlocking {
        seedV083()
        val legacy = prefs("legacy", "v083-prefs.xml")
        val scope = CoroutineScope(Dispatchers.IO)
        var second: Deferred<ImportReport>? = null
        val first = object : AgencyStore by store {
            private var started = false
            override suspend fun <T> transaction(block: suspend () -> T): T {
                if (!started) {
                    started = true
                    second = scope.async { importer(legacy).run() }
                    // Without the lock the second run imports the whole folder now, while this one waits.
                    withTimeoutOrNull(3_000) { second!!.await() }
                }
                return store.transaction(block)
            }
        }
        val a = importer(legacy, into = first).run()
        val b = second!!.await()
        assertTrue(a.ok)
        assertEquals(a.counts, a.inserted)
        assertEquals(kept, a.keptAt)
        assertTrue(b.ok)
        assertTrue(b.skipped)
        assertEquals(1, store.journal.migrations().size)
    }

    @Test fun aRenameThatFailedIsReportedAndTriedAgainAtTheNextStart() = runBlocking {
        seedV083()
        val before = snapshot(coach)
        File(kept, "occupied").apply { parentFile!!.mkdirs() }.writeText("x")
        val legacy = prefs("legacy", "v083-prefs.xml")
        val r = importer(legacy).run()
        assertTrue(r.ok)
        assertTrue(r.renameFailed)
        assertNull(r.keptAt)
        assertEquals(1, agencyPrefs.getInt(AgencyPrefKeys.MIGRATED, 0))
        assertTrue(coach.isDirectory)
        val counts = dbCounts()
        val next = importer(legacy, now = NOW + 1).run()
        assertTrue(next.skipped)
        assertFalse(next.renameFailed)
        assertEquals(File(filesDir, "coach.migrated-${NOW + 1}"), next.keptAt)
        assertFalse(coach.exists())
        assertEquals(before, snapshot(next.keptAt!!))
        assertEquals(counts, dbCounts())
    }

    @Test fun savedSettingsAnExistingProfileOrRulesRowKeptOutAreListed() = runBlocking {
        store.profile.put(Profile(heightCm = 170.0, startWeightKg = 81.5, companyName = "小雨娛樂", joinDate = "2026-11-21", dayStartHour = 5))
        store.rules.put(CompanyRules(fatG = 45), NOW - 1)
        seedV083()
        val r = importer(prefs("legacy", "v083-prefs.xml")).run()
        assertTrue(r.ok)
        val expected = mapOf("profile.heightCm" to "172.0", "rules.fatG" to "50")
        assertEquals(expected, r.note!!.notApplied)
        assertEquals(expected, ImportNote.fromJson(store.journal.migrations().single().note!!).notApplied)
        assertEquals(170.0, store.profile.get()!!.heightCm!!, 0.0)
        assertEquals(45, store.rules.current()!!.fatG)
    }

    @Test fun aFailureAfterTheCommitLogsNoFailedRow() = runBlocking {
        seedV083()
        val legacy = prefs("legacy", "v083-prefs.xml")
        val r = importer(legacy, agency = CommitFails(agencyPrefs)).run()
        assertFalse(r.ok)
        // The records and their ok row are in; only the flag is missing.
        assertTrue(store.journal.migrations().single().ok)
        assertEquals(r.counts, dbCounts())
        assertTrue(coach.isDirectory)
        val retry = importer(legacy).run()
        assertTrue(retry.ok)
        assertEquals(ImportKind.entries.associateWith { 0 }, retry.inserted)
        assertEquals(1, store.journal.migrations().size)
    }

    @Test fun aCancellationWhileLoggingAFailureIsNotSwallowed() = runBlocking {
        seedV2()
        val thrown = runCatching { importer(prefs("legacy", "agency-v2-prefs.xml"), into = CancelsTheFailureLog(store)).run() }.exceptionOrNull()
        assertTrue(thrown.toString(), thrown is CancellationException)
        assertTrue(mediaDir.listFiles().orEmpty().isEmpty())
    }

    @Test fun aFailedRunRemovesFilesAnEarlierKilledRunSealed() = runBlocking {
        seedV2()
        // An earlier run sealed this file and was killed before its commit.
        vault.put(LegacyMapper.mediaId(baselineFiles[0]), fixture("agency-v2-baseline/${baselineFiles[0]}"))
        val r = importer(prefs("legacy", "agency-v2-prefs.xml"), into = DropsOneMessage(store)).run()
        assertFalse(r.ok)
        assertTrue(mediaDir.listFiles().orEmpty().isEmpty())
    }

    @Test fun aMismatchRollsEverythingBackAndLeavesTheLegacyFilesAsTheyWere() = runBlocking {
        seedV2()
        val legacy = prefs("legacy", "agency-v2-prefs.xml")
        val before = snapshot(coach)
        val r = importer(legacy, into = DropsOneMessage(store)).run()
        assertFalse(r.ok)
        // Nothing stays: no record, no sealed file, no flag; the folder is where it was, every byte unchanged.
        val empty = dbCounts().filterKeys { it != ImportKind.AUDIT }
        assertEquals(empty.mapValues { 0 }, empty)
        assertTrue(store.journal.audits().isEmpty())
        assertTrue(mediaDir.listFiles().orEmpty().isEmpty())
        assertEquals(0, agencyPrefs.getInt(AgencyPrefKeys.MIGRATED, 0))
        assertNull(agencyPrefs.getString(AgencyPrefKeys.PROVIDER, null))
        assertTrue(coach.isDirectory)
        assertEquals(before, snapshot(coach))
        // The failure is logged with counts only.
        val log = store.journal.migrations().single()
        assertFalse(log.ok)
        assertTrue(log.note!!, log.note!!.contains("MESSAGES 8/9"))
        assertFalse(log.note!!.contains("排骨"))
        // The next start tries again and succeeds.
        val retry = importer(legacy).run()
        assertTrue(retry.ok)
        assertEquals(retry.counts, dbCounts())
    }

    @Test fun everyLineOfALongConversationArrives() = runBlocking {
        seedV083()
        val more = (1..400).joinToString("") { i ->
            CoachJson.encodeMessage(CoachMessage("long-$i", NOW - 1_000_000 + i, if (i % 2 == 0) Speaker.COACH else Speaker.OWNER, "第 $i 句", if (i % 2 == 0) "chat" else null)) + "\n"
        }
        File(coach, "messages.jsonl").appendText(more)
        val r = importer(prefs("legacy")).run()
        assertTrue(r.ok)
        assertEquals(411, store.messages.count())
        assertEquals("long-1", store.messages.get("long-1")!!.id)
    }

    @Test fun aChoiceAlreadyMadeInTheAgencyIsNotOverwritten() = runBlocking {
        seedV2()
        agencyPrefs.edit().putString(AgencyPrefKeys.PROVIDER, "DEEPSEEK").commit()
        assertTrue(importer(prefs("legacy", "agency-v2-prefs.xml")).run().ok)
        assertEquals("DEEPSEEK", agencyPrefs.getString(AgencyPrefKeys.PROVIDER, null))
        assertEquals("claude-haiku-5-5", agencyPrefs.getString(AgencyPrefKeys.MODEL_CLAUDE, null))
    }

    @Test fun aFreshInstallHasNothingToImport() = runBlocking {
        val r = importer(prefs("legacy")).run()
        assertTrue(r.ok)
        assertEquals(dbCounts().mapValues { 0 }, dbCounts())
        assertTrue(store.journal.migrations().isEmpty())
        assertEquals(1, agencyPrefs.getInt(AgencyPrefKeys.MIGRATED, 0))
    }

    /** Prefs whose saves never land. */
    private class CommitFails(private val inner: SharedPreferences) : SharedPreferences by inner {
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by inner.edit() {
            override fun putString(key: String?, value: String?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun commit() = false
        }
    }

    /** A store that loses one message, and whose failure log is cancelled. */
    private class CancelsTheFailureLog(private val inner: AgencyStore) : AgencyStore by DropsOneMessage(inner) {
        override val journal: JournalRepo = object : JournalRepo by inner.journal {
            override suspend fun logMigration(log: MigrationLog) {
                if (!log.ok) throw CancellationException("invented")
                inner.journal.logMigration(log)
            }
        }
    }

    /** A store that loses one message while saying it wrote it. */
    private class DropsOneMessage(private val inner: AgencyStore) : AgencyStore by inner {
        override val messages: MessageRepo = object : MessageRepo by inner.messages {
            private var dropped = false
            override suspend fun insert(message: Message): Boolean {
                if (!dropped) {
                    dropped = true
                    return true
                }
                return inner.messages.insert(message)
            }
        }
    }

    companion object {
        private const val NOW = 1_795_400_000_000L
    }
}
