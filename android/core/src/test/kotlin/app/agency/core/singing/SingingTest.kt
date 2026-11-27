package app.agency.core.singing

import app.agency.core.llm.LlmTestKit
import app.agency.core.metering.PriceTable
import app.agency.core.metering.Provider
import java.time.Instant
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 唱歌分析 (T19d) on synthetic takes (sines and silences, invented): YIN, the facts, the comparison, the chart, the guard, the consent. */
class SingingTest {
    private val rate = 16_000

    /** Notes as (MIDI, ms) with an optional cents detune; a MIDI of 0 is silence. */
    private fun take(vararg notes: Pair<Int, Int>, detune: Double = 0.0, amp: Double = 8000.0): ShortArray {
        val out = ArrayList<Short>()
        var phase = 0.0
        for ((m, ms) in notes) {
            val hz = if (m == 0) 0.0 else Pitch.hz(m * 100 + detune)
            repeat(rate * ms / 1000) {
                out += if (m == 0) 0 else (amp * sin(phase)).toInt().toShort()
                phase += 2 * PI * hz / rate
            }
        }
        return out.toShortArray()
    }

    @Test fun yinFindsTheNoteWithinAFewCents() {
        listOf(48, 57, 60, 69, 76).forEach { m ->
            val frames = Pitch.track(take(m to 600), rate)
            val cs = frames.mapNotNull { it.cents }.drop(5).dropLast(5)
            assertTrue("$m", cs.isNotEmpty())
            assertTrue("$m ${cs.average()}", abs(cs.average() - m * 100) < 15)
        }
        assertTrue(Pitch.track(take(0 to 500), rate).all { it.hz == null })
        assertEquals("中音 1", Pitch.noteName(60))
        assertEquals("低音 5", Pitch.noteName(55))
        assertEquals("高音 升1", Pitch.noteName(73))
    }

    @Test fun theFactsRangeBreathAndTune() {
        val frames = Pitch.track(take(60 to 1500, 0 to 600, 64 to 800, 67 to 700), rate)
        val f = Analysis.of(frames)
        assertEquals(60, f.lowest)
        assertEquals(67, f.highest)
        assertEquals(2, f.phrases)
        assertEquals(1.5, f.longestPhraseSec, 0.15)
        assertTrue(f.inTunePct >= 95)
        // A held note 40 cents sharp is named among the worst.
        assertTrue(Analysis.of(Pitch.track(take(60 to 1500, detune = 40.0), rate)).lines().any { it.contains("偏高 4") })
        assertTrue(f.lines().none { l -> l.any { it in 'A'..'Z' || it in 'a'..'z' } })
    }

    private val ultra = """
        #TITLE:練習曲
        #BPM:150
        #GAP:0
        : 0 10 0 啦
        : 10 10 4 啦
        - 22
        : 24 10 7 啦
        E
    """.trimIndent()

    @Test fun theComparisonIgnoresTheOctaveAndReportsTheKey() {
        val ref = Reference.ultraStar(ultra)!!
        assertEquals(listOf(60, 64, 67), ref.map { it.midi })
        assertEquals(1000, ref[1].startMs) // a beat is 15 / 150 s
        // His take: 1 s each, then the third after a 400 ms gap.
        val good = Pitch.track(take(60 to 1000, 64 to 1000, 0 to 400, 67 to 1000), rate)
        val r = Score.compare(good, ref, Strictness.NORMAL, aCappella = false)
        assertTrue("${r.pitchPct}", r.pitchPct >= 95)
        assertTrue("${r.timingPct}", r.timingPct >= 90)
        // An octave up: the same score, not the same octave.
        val up = Score.compare(Pitch.track(take(72 to 1000, 76 to 1000, 0 to 400, 79 to 1000), rate), ref, Strictness.NORMAL, aCappella = false)
        assertTrue(up.pitchPct >= 95 && !up.sameOctave)
        // Two semitones low a cappella: the key is reported, the tune itself is fine.
        val low = Score.compare(Pitch.track(take(58 to 1000, 62 to 1000, 0 to 400, 65 to 1000), rate), ref, Strictness.NORMAL, aCappella = true)
        assertEquals(-2, low.shiftSemitones)
        assertTrue(low.pitchPct >= 95)
        assertTrue(low.lines().any { it.contains("低 2 個半音") })
        // 60 cents off: half marks under 一般, nothing under 嚴格 (never 入門 by default).
        val off = Pitch.track(take(60 to 1000, 64 to 1000, 0 to 400, 67 to 1000, detune = 60.0), rate)
        assertTrue(Score.compare(off, ref, Strictness.NORMAL, false).pitchPct in 40..70)
        assertEquals(0, Score.compare(off, ref, Strictness.STRICT, false).pitchPct)
        assertEquals(Strictness.NORMAL, Strictness.forLevel(0))
        assertEquals(Strictness.STRICT, Strictness.forLevel(3))
    }

    @Test fun aMidiMelodyAndADemoTake() {
        // A format-0 file: 480 ticks a beat at 120 bpm, C4 for a beat, then E4 for a beat.
        fun vlq(v: Int) = if (v < 128) byteArrayOf(v.toByte()) else byteArrayOf(((v shr 7) or 0x80).toByte(), (v and 0x7f).toByte())
        val ev = vlq(0) + byteArrayOf(0x90.toByte(), 60, 100) + vlq(480) + byteArrayOf(0x80.toByte(), 60, 0) +
            vlq(0) + byteArrayOf(0x90.toByte(), 64, 100) + vlq(480) + byteArrayOf(0x80.toByte(), 64, 0) + vlq(0) + byteArrayOf(0xff.toByte(), 0x2f, 0)
        val head = "MThd".toByteArray() + byteArrayOf(0, 0, 0, 6, 0, 0, 0, 1, 0x01, 0xe0.toByte())
        val trk = "MTrk".toByteArray() + java.nio.ByteBuffer.allocate(4).putInt(ev.size).array() + ev
        val notes = Reference.midi(head + trk)!!
        assertEquals(listOf(60, 64), notes.map { it.midi })
        assertEquals(500, notes[1].startMs)
        val demo = Reference.demo(Pitch.track(take(62 to 800, 65 to 800), rate))
        assertEquals(listOf(62, 65), demo.map { it.midi })
    }

    @Test fun theChartIsAPlainPngOfAFixedSize() {
        val frames = Pitch.track(take(60 to 800, 0 to 300, 64 to 800), rate)
        val png = Chart.png(frames, Reference.ultraStar(ultra)!!)
        assertArrayEquals(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), png.copyOf(4))
        val b = java.nio.ByteBuffer.wrap(png)
        assertEquals(Chart.W, b.getInt(16))
        assertEquals(Chart.H, b.getInt(20))
        // Only IHDR, IDAT and IEND: no text, time or location chunk.
        val types = mutableListOf<String>()
        var p = 8
        while (p < png.size) { val len = b.getInt(p); types += String(png, p + 4, 4); p += 12 + len }
        assertEquals(listOf("IHDR", "IDAT", "IEND"), types)
        assertArrayEquals(png, Chart.png(frames, Reference.ultraStar(ultra)!!))
    }

    @Test fun herNumbersMustBeCodesAndNoJudgingTheSound() {
        val facts = listOf("音準：長音有 82% 落在正確音高的半個半音以內", "氣長：最長一句 6.5 秒，共 4 句")
        assertNotNull(SingingCoach.accept("""{"text":"長音有 82% 在音上，最長一句 6.5 秒，下一句前先吸飽氣。"}""", facts))
        assertNull(SingingCoach.accept("""{"text":"長音有 90% 在音上。"}""", facts)) // invented
        assertNull(SingingCoach.accept("""{"text":"你的聲音很好聽。"}""", facts))
        assertTrue(SingingCoach.summary(facts).contains("82%"))
    }

    @Test fun dictionListsWhatWasNotHeard() {
        val r = Diction.check("我們一起唱這首歌", "我們一唱這首")
        assertEquals(listOf("起", "歌"), r.unclear)
        assertEquals(75, r.matchedPct)
    }

    @Test fun aRecordingIsSentOnlyAfterHisConsentAndIsMetered() = LlmTestKit().use { kit ->
        var consent = false
        val asr = DeepSeekAsr(kit.transport, { kit.key }, kit.checks, kit.sink, { consent }, kit.base.newBuilder().addPathSegments("v1/tools/asr").build())
        val wav = Wav.write(take(60 to 500), rate)
        assertTrue(runBlocking { asr.transcribe(wav) } is DeepSeekAsr.Result.Failed)
        assertEquals(0, kit.server.requestCount)
        consent = true
        kit.server.enqueue(MockResponse().setBody("""{"request_id":"r","text":"啦啦啦","duration_ms":500}"""))
        assertEquals(DeepSeekAsr.Result.Ok("啦啦啦"), runBlocking { asr.transcribe(wav) })
        val req = kit.server.takeRequest()
        assertTrue(req.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        assertEquals(500L, kit.recorded.single().third.asrMs)
        val usd = PriceTable().costUsd(Provider.DEEPSEEK, DeepSeekAsr.MODEL, kit.recorded.single().third, Instant.parse("2026-12-01T10:00:00Z"))
        assertEquals(500 / 3_600_000.0 * 0.116, usd, 1e-9)
        // Longer than two minutes is never sent.
        assertTrue(runBlocking { asr.transcribe(Wav.write(ShortArray(rate * 121), rate)) } is DeepSeekAsr.Result.Failed)
        assertEquals(1, kit.server.requestCount)
    }
}
