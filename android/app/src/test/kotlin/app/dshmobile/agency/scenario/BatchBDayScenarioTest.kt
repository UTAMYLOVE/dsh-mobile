package app.dshmobile.agency.scenario

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.agency.core.brain.Brain
import app.agency.core.importing.Tables
import app.agency.core.llm.LlmClient
import app.agency.core.llm.ProviderChoice
import app.agency.core.metering.MutableClock
import app.agency.core.model.ImportTarget
import app.agency.core.model.LedgerBucket
import app.agency.core.model.Media
import app.agency.core.model.MediaKind
import app.agency.core.model.MediaPurpose
import app.agency.core.model.PhotoReadKind
import app.agency.core.model.PhotoReadStatus
import app.agency.core.model.Profile
import app.agency.core.model.Who
import app.agency.core.schedule.DayClock
import app.agency.core.time.AgencyClock
import app.agency.core.vision.ReadDraft
import app.agency.core.vision.VisionClient
import app.agency.core.vision.VisionImage
import app.agency.core.vision.VisionRequest
import app.agency.core.vision.VisionResult
import app.agency.core.vision.VisionRouter
import app.dshmobile.agency.backup.Backup
import app.dshmobile.agency.data.AgencyDatabase
import app.dshmobile.agency.data.RoomAgencyStore
import app.dshmobile.agency.media.MediaVault
import app.dshmobile.agency.media.PhotoBytes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 整天情境, batch B: one invented Monday on the real schema (Room on plain SQLite) and the vault. 08:30 「提醒我中午十二點去領包裹」
 * (code saves his words, the reminder comes at 12:00, once); 12:20 the lunch photo is read (two items, a suggestion) and his
 * confirm makes the meal, counted by code; 17:30 a menu photo, one number fixed by him, confirmed into the food table; 20:00 an
 * invented 記帳 table pasted (one row already in is skipped; 形象用品 goes to 娛樂費); 23:00 the encrypted backup, more
 * changes, and the restore: everything as it was at the backup. No photo, key or record of his is used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BatchBDayScenarioTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir: File = Files.createTempDirectory("batch-b-day").toFile()
    private val db = AgencyDatabase.build(context, File(dir, "agency.db").path, FrameworkSQLiteOpenHelperFactory())
    private val store = RoomAgencyStore(db)
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val vault = MediaVault(File(dir, "media")) { key }
    private val clock = MutableClock()
    private val day = LocalDate.parse("2026-11-23") // a Monday (class day)
    private var n = 0

    /** No chat model call happens today: every line is code's; the photos go to the image model only. */
    private val noModel = LlmClient { error("no model call expected") }

    private class ScriptedVision(vararg replies: String) : VisionClient {
        private val script = ArrayDeque(replies.toList())
        val seen = mutableListOf<VisionRequest>()
        override suspend fun see(request: VisionRequest): VisionResult {
            seen += request
            return VisionResult.Ok(script.removeFirst())
        }
    }

    @After fun clean() {
        db.close()
        dir.deleteRecursively()
    }

    private fun at(hhmm: String) {
        clock.now = day.atStartOfDay(AgencyClock.TAIPEI).plusMinutes(DayClock.minute(hhmm).toLong()).toInstant()
    }

    private fun drawn(rgb: Int): ByteArray {
        val bmp = Bitmap.createBitmap(2400, 1800, Bitmap.Config.ARGB_8888).apply { eraseColor(rgb) }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun seal(id: String, purpose: MediaPurpose, rgb: Int): Pair<Media, VisionImage> {
        val clean = PhotoBytes.clean(drawn(rgb))!!
        val s = vault.put(id, clean)
        return Media(id, MediaKind.PHOTO, purpose, "image/jpeg", s.bytes, s.sha256, clock.millis()) to PhotoBytes.forModel(vault.get(id))!!
    }

    @Test fun aDayWithAMemoALunchPhotoAMenuAnImportAndABackup() = runBlocking {
        store.profile.put(Profile(joinDate = "2026-11-21", onboardingDone = true))
        val vision = ScriptedVision(
            """{"rows":[{"name":"即食雞胸","kcal":300,"protein_g":20,"printed":false,"portion":1,"fried":false,"processed":false,"amount":null,"food":false},{"name":"燙青菜","kcal":80,"protein_g":2,"printed":false,"portion":1,"fried":false,"processed":false,"amount":null,"food":false}]}""",
            """{"rows":[{"name":"鹽烤鯖魚定食","kcal":650,"protein_g":35,"printed":true,"portion":1,"fried":false,"processed":false,"amount":null,"food":false},{"name":"炸豬排定食","kcal":980,"protein_g":30,"printed":true,"portion":1,"fried":true,"processed":false,"amount":null,"food":false}]}""",
        )
        val brain = Brain(
            store, noModel, { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock), intakeLlm = noModel,
            vision = VisionRouter({ ProviderChoice.DEEPSEEK }, vision, vision), newId = { "id-%05d".format(++n) },
        )

        // 08:30 a memo with a time, by code.
        at("08:30")
        val memoLine = brain.onText("提醒我中午十二點去領包裹").last()
        assertEquals("記下了：「中午十二點去領包裹」。11/23（一）12:00 提醒你。", memoLine.text)
        at("12:00")
        assertEquals(listOf("小雨，你要我提醒的：「中午十二點去領包裹」。"), brain.tick().posts.filter { it.key.startsWith("memo:") }.map { it.text })
        at("12:05")
        assertTrue(brain.tick().posts.none { it.key.startsWith("memo:") })

        // 12:20 lunch: the photo first, read as a suggestion, confirmed by him; the calories are code's.
        at("12:20")
        val (lunch, lunchImg) = seal("lunch-photo", MediaPurpose.MEAL, Color.rgb(230, 190, 160))
        brain.attachMealPhoto(lunch)
        val read = brain.readPhoto(PhotoReadKind.MEAL, lunch, listOf(lunchImg)).single()
        val r = store.photoReads.get(read.refs.photoRead!!)!!
        assertTrue(store.meals.onDay(day.toString()).single().items.isEmpty())
        brain.confirmPhoto(r.id, ReadDraft.of(r.json)!!)
        val meal = store.meals.onDay(day.toString()).single()
        assertEquals("lunch-photo", meal.photoMediaId)
        assertEquals(117, meal.items.first { it.name == "即食雞胸" }.kcal) // the food table, not the model's 300
        assertEquals(PhotoReadStatus.CONFIRMED, store.photoReads.get(r.id)!!.status)
        // What the model saw: a clean JPEG, at most 1600 px.
        assertTrue(vision.seen.first().images.single().let { maxOf(it.width, it.height) <= 1600 })

        // 17:30 a menu: he fixes one number and drops the fried set, then confirms.
        at("17:30")
        val (menu, menuImg) = seal("menu-photo", MediaPurpose.MENU, Color.rgb(250, 250, 240))
        val mr = store.photoReads.get(brain.readPhoto(PhotoReadKind.MENU, menu, listOf(menuImg)).single().refs.photoRead!!)!!
        val d = ReadDraft.of(mr.json)!!
        brain.confirmPhoto(mr.id, d.copy(rows = listOf(d.rows[0].copy(kcal = 620, printed = false))))
        assertEquals(620, store.food.get("鹽烤鯖魚定食")!!.kcal)
        assertTrue(store.food.get("鹽烤鯖魚定食")!!.estimated)
        assertEquals(null, store.food.get("炸豬排定食"))

        // 20:00 an invented bookkeeping table, pasted.
        at("20:00")
        store.ledger.insert(app.agency.core.model.LedgerEntry("before", "2026-11-20", 1, 85, "早餐"))
        val table = Tables.text("日期,金額,項目,分類\n2026/11/20,85,早餐,餐費\n2026/11/21,350,髮蠟,形象用品\n2026/11/22,1200,房租分攤,生活")
        val mapping = brain.importMapping(ImportTarget.LEDGER, table, "貼上")
        val plan = brain.planImport(ImportTarget.LEDGER, table, mapping, "貼上")
        assertEquals(2, plan.fresh.size)
        assertEquals(1, plan.duplicates)
        assertEquals(1, store.ledger.count()) // nothing before 匯入
        brain.commitImport(plan)
        assertEquals(listOf(85 to LedgerBucket.LIFE, 350 to LedgerBucket.FUN, 1200 to LedgerBucket.LIFE), store.ledger.between("2026-11-01", "2026-11-30").map { it.amount to it.bucket })

        // 23:00 the backup, then more happens, then the restore gives back the day as it was.
        at("23:00")
        val before = snapshot()
        val out = ByteArrayOutputStream()
        val backup = Backup({ db.openHelper.writableDatabase }, vault)
        val made = backup.export(out, "invented-pass-77".toCharArray(), clock.millis(), app.agency.core.backup.BackupCipher.MIN_ITERATIONS)
        assertEquals(2, made.media)
        brain.onText("幫我記買洗衣精")
        store.ledger.delete(store.ledger.between("2026-11-01", "2026-11-30").last().id)
        vault.delete("menu-photo")
        assertFalse(snapshot() == before)
        backup.restore(ByteArrayInputStream(out.toByteArray()), "invented-pass-77".toCharArray())
        assertEquals(before, snapshot())
        assertTrue(vault.has("menu-photo") && vault.has("lunch-photo"))
        assertTrue(store.messages.recent(200).none { it.who == Who.OWNER && it.text == "幫我記買洗衣精" })
    }

    private suspend fun snapshot(): List<Any?> = listOf(
        store.messages.recent(500), store.memos.all(deleted = true), store.meals.between("0000-01-01", "9999-12-31"),
        store.food.all(), store.ledger.between("0000-01-01", "9999-12-31"), store.imports.all(), store.photoReads.all(),
        store.media.all(), store.nudges.between(0, Long.MAX_VALUE), vault.ids().sorted(),
    )
}
