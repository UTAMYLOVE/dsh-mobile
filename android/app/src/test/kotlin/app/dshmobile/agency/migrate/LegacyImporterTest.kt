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
import app.agency.core.model.Message
import app.agency.core.model.MigrationSource
import app.agency.core.model.RoleKey
import app.agency.core.model.Who
import app.agency.core.store.AgencyStore
import app.agency.core.store.MessageRepo
import app.dshmobile.agency.data.AgencyDatabase
import app.dshmobile.agency.data.RoomAgencyStore
import app.dshmobile.agency.media.MediaVault
import app.dshmobile.coach.CoachJson
import app.dshmobile.coach.CoachMessage
import app.dshmobile.coach.Speaker
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
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

    private fun importer(legacy: SharedPreferences, into: AgencyStore = store, agency: SharedPreferences = agencyPrefs) =
        LegacyImporter(filesDir, legacy, agency, into, vault) { NOW }

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
        agencyPrefs.edit().putInt(AgencyPrefKeys.MIGRATED, 1).commit()
        val r = importer(prefs("legacy", "v083-prefs.xml")).run()
        assertTrue(r.skipped)
        assertEquals(0, store.messages.count())
        assertTrue(coach.isDirectory)
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

    @Test fun anUnreadableStateStillBringsTheMessagesAndIsKept() = runBlocking {
        seedV083()
        File(coach, "state.json").writeBytes(fixture("damaged-state.json"))
        val r = importer(prefs("legacy")).run()
        assertTrue(r.ok)
        assertEquals(StateOrigin.UNREADABLE.name, r.note!!.stateOrigin)
        assertEquals(0, store.meals.count())
        assertEquals(11, store.messages.count())
        assertArrayEquals(fixture("damaged-state.json"), File(filesDir, "coach.migrated-$NOW/state.json").readBytes())
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
