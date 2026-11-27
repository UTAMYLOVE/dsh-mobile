package app.agency.core.vision

import app.agency.core.brain.Brain
import app.agency.core.llm.ChatResult
import app.agency.core.llm.CompatEndpoint
import app.agency.core.llm.Fail
import app.agency.core.llm.LlmTestKit
import app.agency.core.llm.ProviderChoice
import app.agency.core.llm.Turn
import app.agency.core.llm.Wire
import app.agency.core.metering.MutableClock
import app.agency.core.metering.Provider
import app.agency.core.model.LedgerBucket
import app.agency.core.model.Media
import app.agency.core.model.MediaKind
import app.agency.core.model.MediaPurpose
import app.agency.core.model.PhotoReadKind
import app.agency.core.model.PhotoReadStatus
import app.agency.core.model.Profile
import app.agency.core.model.RoleKey
import app.agency.core.schedule.DayClock
import app.agency.core.store.InMemoryAgencyStore
import app.agency.core.structured.ScriptedLlm
import app.agency.core.time.AgencyClock
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** T15b: photos leave only cleaned (no metadata, ≤ 1600 px), only as photos, through the gate and the meter; confirm first. */
class VisionT15bTest {
    companion object {
        /** A tiny JPEG header (not decodable pixels, enough for the marker walk) with the metadata a phone writes. */
        fun jpeg(w: Int, h: Int, exif: Boolean = true): ByteArray {
            val out = ByteArrayOutputStream()
            fun seg(marker: Int, body: ByteArray) {
                out.write(0xFF); out.write(marker); out.write((body.size + 2) shr 8); out.write((body.size + 2) and 0xFF); out.write(body)
            }
            out.write(0xFF); out.write(0xD8)
            seg(0xE0, "JFIF\u0000".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(1, 1, 0, 0, 1, 0, 1, 0, 0))
            if (exif) {
                seg(0xE1, "Exif\u0000\u0000GPS 25.03N 121.56E Pixel".toByteArray(Charsets.ISO_8859_1))
                seg(0xE2, "ICC_PROFILE\u0000xx".toByteArray(Charsets.ISO_8859_1))
                seg(0xFE, "made on a phone".toByteArray(Charsets.ISO_8859_1))
            }
            seg(0xC0, byteArrayOf(8, (h shr 8).toByte(), h.toByte(), (w shr 8).toByte(), w.toByte(), 1, 1, 0x11, 0))
            seg(0xDA, byteArrayOf(1, 1, 0, 0, 0x3F, 0))
            out.write(byteArrayOf(0x12, 0x34, 0x56))
            out.write(0xFF); out.write(0xD9)
            return out.toByteArray()
        }

        fun ok(text: String): VisionResult = VisionResult.Ok(text)
    }

    private class FakeVision(vararg replies: VisionResult) : VisionClient {
        private val script = ArrayDeque(replies.toList())
        val seen = mutableListOf<VisionRequest>()
        override suspend fun see(request: VisionRequest): VisionResult {
            seen += request
            return script.removeFirstOrNull() ?: error("unexpected photo call")
        }
    }

    @Test fun onlyACleanSmallJpegCanLeave() {
        val raw = jpeg(1600, 1200)
        val img = VisionImage.of(raw)!!
        assertEquals(1600, img.width)
        assertEquals(1200, img.height)
        val s = String(img.bytes, Charsets.ISO_8859_1)
        listOf("Exif", "GPS", "Pixel", "ICC_PROFILE", "made on a phone").forEach { assertFalse(it, s.contains(it)) }
        assertTrue(s.contains("JFIF"))
        assertFalse(Jpeg.hasMetadata(img.bytes))
        assertTrue(Jpeg.hasMetadata(raw))
        assertTrue(img.dataUrl().startsWith("data:image/jpeg;base64,"))
        // Too large: the app must downsize first.
        assertNull(VisionImage.of(jpeg(1601, 900)))
        assertNull(VisionImage.of(jpeg(900, 2000)))
        // A recording (a WAV or an AAC file) or a PNG is never a photo that can leave.
        assertNull(VisionImage.of("RIFF\u0000\u0000\u0000\u0000WAVEfmt ".toByteArray(Charsets.ISO_8859_1)))
        assertNull(VisionImage.of(byteArrayOf(0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70, 0x4D, 0x34, 0x41)))
        assertNull(VisionImage.of(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
    }

    @Test fun deepSeekGetsADataUrlThroughTheGateAndTheMeterBooksIt() = runBlocking {
        LlmTestKit().use { kit ->
            kit.enqueue(200, LlmTestKit.completion("""{"role":"assistant","content":"{\"rows\":[]}"}"""))
            val client = DeepSeekVisionClient(kit.transport, { kit.key }, kit.checks, kit.sink, kit.base)
            val r = client.see(PhotoReads.request(PhotoReadKind.MENU, listOf(VisionImage.of(jpeg(800, 600))!!)))
            assertTrue(r is VisionResult.Ok)
            val body = LlmTestKit.json(kit.take())
            assertEquals(CompatEndpoint.DEEPSEEK_FLASH, body["model"]!!.jsonPrimitive.content)
            val parts = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
            assertEquals("image_url", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
            val url = parts[0].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
            assertTrue(url.startsWith("data:image/jpeg;base64,"))
            assertFalse(String(java.util.Base64.getDecoder().decode(url.substringAfter(",")), Charsets.ISO_8859_1).contains("Exif"))
            assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals(Triple(Provider.DEEPSEEK, CompatEndpoint.DEEPSEEK_FLASH, kit.recorded.single().third), kit.recorded.single())
            // Over the spend cap, nothing goes out.
            kit.capBlocked = true
            assertEquals(VisionResult.Failed(Fail.CAP), client.see(PhotoReads.request(PhotoReadKind.MENU, listOf(VisionImage.of(jpeg(8, 8))!!))))
            assertEquals(1, kit.server.requestCount)
            // A host the gate does not allow is never reached.
            kit.capBlocked = false
            kit.allowed = emptySet()
            assertEquals(VisionResult.Failed(Fail.BLOCKED_HOST), client.see(PhotoReads.request(PhotoReadKind.MENU, listOf(VisionImage.of(jpeg(8, 8))!!))))
            assertEquals(1, kit.server.requestCount)
        }
    }

    @Test fun claudeGetsAnImageBlockAndTheCompatibleServiceSaysNo() = runBlocking {
        LlmTestKit().use { kit ->
            kit.enqueue(200, """{"model":"claude-sonnet-5-5","content":[{"type":"text","text":"{\"rows\":[]}"}],"stop_reason":"end_turn","usage":{"input_tokens":900,"output_tokens":10}}""")
            val client = ClaudeVisionClient(kit.transport, { "claude-sonnet-5-5" }, { kit.key }, kit.checks, kit.sink, kit.base)
            assertTrue(client.see(PhotoReads.request(PhotoReadKind.MEAL, listOf(VisionImage.of(jpeg(100, 100))!!))) is VisionResult.Ok)
            val body = LlmTestKit.json(kit.take())
            val block = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
            assertEquals("image", block["type"]!!.jsonPrimitive.content)
            assertEquals("image/jpeg", block["source"]!!.jsonObject["media_type"]!!.jsonPrimitive.content)
            assertEquals("json_schema", body["output_config"]!!.jsonObject["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals(Provider.ANTHROPIC, kit.recorded.single().first)
        }
        val router = VisionRouter({ ProviderChoice.COMPAT }, FakeVision(), FakeVision())
        assertFalse(router.supported())
        assertEquals(VisionResult.Failed(Fail.NO_SETUP), router.see(PhotoReads.request(PhotoReadKind.MENU, listOf(VisionImage.of(jpeg(8, 8))!!))))
    }

    private val store = InMemoryAgencyStore()
    private val clock = MutableClock()
    private var n = 0
    private val day = LocalDate.parse("2026-11-23") // a Monday

    init {
        runBlocking { store.profile.put(Profile(joinDate = "2026-11-21", onboardingDone = true)) }
    }

    private fun at(hhmm: String) {
        clock.now = day.atStartOfDay(AgencyClock.TAIPEI).plusMinutes(DayClock.minute(hhmm).toLong()).toInstant()
    }

    private fun brain(vision: FakeVision, judge: ScriptedLlm = ScriptedLlm(), choice: ProviderChoice = ProviderChoice.DEEPSEEK) =
        Brain(store, judge, { choice }, AgencyClock.of(clock), intakeLlm = judge, newId = { "id-%05d".format(++n) }, vision = VisionRouter({ choice }, vision, vision))

    private fun photo(id: String, purpose: MediaPurpose) = Media(id, MediaKind.PHOTO, purpose, "image/jpeg", 100, "ab".repeat(32), clock.millis())

    private val img = VisionImage.of(jpeg(1200, 900))!!

    @Test fun aMenuIsOnlyAListUntilHeConfirms() = runBlocking {
        at("12:15")
        val v = FakeVision(ok("""{"rows":[{"name":"滷雞腿飯","kcal":720,"protein_g":35,"printed":true,"portion":1,"fried":false,"processed":false,"amount":null,"food":false},
            {"name":"炸排骨飯","kcal":900,"protein_g":null,"printed":false,"portion":1,"fried":true,"processed":false,"amount":null,"food":false},
            {"name":"燙青菜","kcal":null,"protein_g":null,"printed":false,"portion":1,"fried":false,"processed":false,"amount":null,"food":false}]}"""))
        val b = brain(v)
        val line = b.readPhoto(PhotoReadKind.MENU, photo("menu-1", MediaPurpose.MENU), listOf(img)).single()
        assertEquals(RoleKey.BODY, line.role)
        assertTrue(line.text, line.text.startsWith("菜單讀到 3 樣（只是讀到的，改好按「確認」才算數）"))
        val id = line.refs.photoRead!!
        assertEquals(PhotoReadStatus.WAITING, store.photoReads.get(id)!!.status)
        assertNull(store.food.get("滷雞腿飯")) // not a fact yet
        // He fixes one name and drops the fried one.
        val draft = ReadDraft.of(store.photoReads.get(id)!!.json)!!
        val edited = draft.copy(rows = listOf(draft.rows[0].copy(name = "滷雞腿便當"), draft.rows[2]))
        val said = b.confirmPhoto(id, edited)
        assertTrue(said.first().text, said.first().text.contains("滷雞腿便當 720 大卡（菜單）"))
        val e = store.food.get("滷雞腿便當")!!
        assertFalse(e.estimated)
        assertNull(store.food.get("炸排骨飯"))
        assertEquals(PhotoReadStatus.CONFIRMED, store.photoReads.get(id)!!.status)
        assertTrue(b.confirmPhoto(id, edited).isEmpty()) // once
        assertEquals("image/jpeg", VisionImage.MIME)
        assertEquals(1, v.seen.single().images.size)
    }

    @Test fun aMealPhotoIsASuggestionAndCodeCountsTheCalories() = runBlocking {
        at("12:20")
        val v = FakeVision(ok("""{"rows":[{"name":"即食雞胸","kcal":300,"protein_g":10,"printed":false,"portion":1,"fried":false,"processed":false,"amount":null,"food":false},
            {"name":"炸雞塊","kcal":400,"protein_g":12,"printed":false,"portion":1,"fried":true,"processed":true,"amount":null,"food":false}]}"""))
        val b = brain(v)
        val media = photo("meal-1", MediaPurpose.MEAL)
        b.attachMealPhoto(media)
        val id = b.readPhoto(PhotoReadKind.MEAL, media, listOf(img)).single().refs.photoRead!!
        val waiting = store.meals.onDay(day.toString()).single()
        assertTrue(waiting.items.isEmpty()) // no items, no breach before his yes
        assertTrue(waiting.breaches.isEmpty())
        val draft = ReadDraft.of(store.photoReads.get(id)!!.json)!!
        b.confirmPhoto(id, draft)
        val meal = store.meals.onDay(day.toString()).single()
        assertEquals("meal-1", meal.photoMediaId)
        // The table's 117 for 即食雞胸, not the model's 300; the fried flag became a breach only now.
        assertEquals(117, meal.items.first { it.name == "即食雞胸" }.kcal)
        assertTrue(meal.breaches.isNotEmpty())
    }

    @Test fun aReceiptBecomesBookkeepingAfterHisYes() = runBlocking {
        at("13:00")
        val v = FakeVision(ok("""{"date":"2026-11-23","total":185,"rows":[{"name":"雞胸便當","kcal":650,"protein_g":null,"printed":false,"portion":1,"fried":false,"processed":false,"amount":120,"food":true},
            {"name":"髮蠟","kcal":null,"protein_g":null,"printed":false,"portion":1,"fried":false,"processed":false,"amount":65,"food":false}]}"""))
        val b = brain(v)
        val id = b.readPhoto(PhotoReadKind.RECEIPT, photo("r-1", MediaPurpose.RECEIPT), listOf(img)).single().refs.photoRead!!
        assertEquals(0, store.ledger.count())
        val d = ReadDraft.of(store.photoReads.get(id)!!.json)!!
        b.confirmPhoto(id, d.copy(rows = listOf(d.rows[0], d.rows[1].copy(entertainment = true))))
        val rows = store.ledger.onDay(day.toString())
        assertEquals(listOf(120 to LedgerBucket.LIFE, 65 to LedgerBucket.FUN), rows.map { it.amount to it.bucket })
        // Its food row is the meal, counted by code's strict estimate (the model's figure is the high end, plus the margin).
        val meal = store.meals.onDay(day.toString()).single()
        assertEquals(listOf("雞胸便當"), meal.items.map { it.name })
        assertTrue(meal.kcal >= 650)
    }

    @Test fun bodyFeedbackPassesTwoChecksAndNeverTalksFoodOrNumbers() = runBlocking {
        at("21:00")
        val v = FakeVision(
            ok("""{"text":"肩膀有點往前，站的時候把肩胛往後收。腰腹的脂肪比較集中。晚餐少吃一點。看起來大概 96 公斤。褲子太長，褲管堆在鞋上。"}"""),
            ok("""{"text":"你這樣很醜。"}"""),
            ok("""{"text":"腰腹比較集中。"}"""),
        )
        val judge = ScriptedLlm(ScriptedLlm.ok("""{"violation":false,"class":"none","reason":""}"""), ScriptedLlm.ok("""{"violation":true,"class":"insult","reason":"貶低他"}"""))
        val b = brain(v, judge)
        val said = b.readPhoto(PhotoReadKind.BODY, photo("body-1", MediaPurpose.BODY), listOf(img, img)).single().text
        assertEquals("肩膀有點往前，站的時候把肩胛往後收。腰腹的脂肪比較集中。褲子太長，褲管堆在鞋上。", said)
        assertEquals(2, v.seen.single().images.size) // with the baseline, compared in words only
        // An insult is cut by the rule guard (nothing left: the template); the judge's breach also gives the template.
        assertEquals(app.agency.core.brain.handlers.PhotoDesk.BODY_HELD, b.readPhoto(PhotoReadKind.BODY, photo("body-2", MediaPurpose.BODY), listOf(img)).single().text)
        assertEquals(app.agency.core.brain.handlers.PhotoDesk.BODY_HELD, b.readPhoto(PhotoReadKind.BODY, photo("body-3", MediaPurpose.BODY), listOf(img)).single().text)
        assertEquals(2, judge.requests.size)
        // Her words about his body are never memory.
        assertTrue(store.memory.items().isEmpty())
    }

    @Test fun aRecordingCanNeverBeRead() = runBlocking {
        val b = brain(FakeVision())
        try {
            b.readPhoto(PhotoReadKind.MEAL, Media("rec", MediaKind.AUDIO, MediaPurpose.LESSON, "audio/mp4", 10, "ab".repeat(32), 1), listOf(img))
            fail("a recording was read")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }

    @Test fun withoutImageSupportThePhotoStaysEvidenceAndHeTypes() = runBlocking {
        at("12:30")
        val v = FakeVision()
        val b = brain(v, choice = ProviderChoice.COMPAT)
        val line = b.readPhoto(PhotoReadKind.MENU, photo("menu-2", MediaPurpose.MENU), listOf(img)).single()
        assertEquals(PhotoReads.NO_VISION, line.text)
        assertTrue(v.seen.isEmpty())
        assertNotNull(store.media.get("menu-2"))
    }

    @Test fun aPickedPhotoGoesOnTheMealOfItsCaptureTimeAndIsMarkedLate() = runBlocking {
        at("12:20")
        val taken = clock.millis()
        at("15:10")
        val b = brain(FakeVision())
        val line = b.attachPickedMealPhoto(photo("pick-1", MediaPurpose.MEAL), taken)
        val meal = store.meals.onDay(day.toString()).single()
        assertEquals("LUNCH", meal.slotKey)
        assertEquals(taken, meal.lateAt)
        assertEquals(taken, meal.at)
        assertTrue(line.text, line.text.startsWith("照片收到（補交，11/23 12:20 拍的），記在"))
        // Handed in right after it was taken: on time.
        at("17:40")
        val now = clock.millis()
        b.attachPickedMealPhoto(photo("pick-2", MediaPurpose.MEAL), now - 60_000)
        assertNull(store.meals.onDay(day.toString()).last().lateAt)
    }
}
