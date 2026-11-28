package app.agency.core.extras

import app.agency.core.base.TestClock
import app.agency.core.singing.Analysis
import app.agency.core.singing.CompareResult
import app.agency.core.singing.Pitch
import app.agency.core.singing.Wav
import app.agency.core.store.Kind
import app.agency.core.store.MemStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongTest {
    private val store = MemStore()
    private val clock = TestClock()
    private val book = SongBook(store, clock)
    private val pcm = Kit.take(60 to 1500, 0 to 600, 64 to 800, 67 to 700)

    @Test fun cardLinesAreTheAnalysisNumbers() {
        val frames = Pitch.track(pcm, Kit.RATE)
        val r = book.record(pcm, Kit.RATE, "測試歌", vaultId = "vault-1")
        val facts = Analysis.of(frames)
        assertEquals(facts, r.facts)
        assertTrue(r.card.lines.containsAll(facts.lines()))
        assertEquals("唱歌分析：測試歌", r.card.title)
        assertEquals(facts.inTunePct, r.rec.inTunePct)
        assertEquals(7, r.rec.rangeSemitones)
        assertEquals("中音 1", r.rec.lowestName)
        assertEquals("中音 5", r.rec.highestName)
        assertEquals(facts.longestPhraseSec, r.rec.longestPhraseSec, 0.0)
        assertEquals("vault-1", r.rec.vaultId)
        assertTrue(r.rec.loudnessDb < 0)
        assertTrue(r.card.lines.any { it.contains("平均 ${r.rec.loudnessDb} 分貝") })
        assertTrue(r.card.lines.none { l -> l.any { it in 'A'..'Z' || it in 'a'..'z' } })
        val e = store.entry(r.entryId)!!
        assertEquals(Kind.SONG, e.kind)
        assertEquals(r.rec, e.read<SongRec>())
        assertEquals(r.rec, book.song(r.entryId))
    }

    @Test fun wavInputGivesTheSameNumbers() {
        val a = book.record(pcm, Kit.RATE, "a")
        val b = book.recordWav(Wav.write(pcm, Kit.RATE), "b")!!
        assertEquals(a.rec.inTunePct, b.rec.inTunePct)
        assertEquals(a.rec.rangeSemitones, b.rec.rangeSemitones)
        assertNull(book.recordWav(byteArrayOf(1, 2, 3), "x"))
    }

    @Test fun comparisonWithAReferenceAThirdLowerIsReportedInSemitones() {
        val demoFrames = Pitch.track(Kit.take(64 to 800, 67 to 800, 69 to 800), Kit.RATE)
        val ref = book.importDemo("示範", demoFrames)!!
        assertEquals("ref1", ref.id)
        assertEquals(listOf(ref), book.refs())
        // The take is sung 3 semitones lower than the demo, a cappella.
        val r = book.record(Pitch.track(Kit.take(61 to 800, 64 to 800, 66 to 800), Kit.RATE), "低三度", referenceId = ref.id)
        val c = r.rec.compare!!
        assertEquals(-3, c.shiftSemitones)
        assertEquals("嚴格", c.strictness)
        assertTrue(r.card.lines.containsAll(c.lines()))
        assertEquals(ref.id, r.rec.referenceId)
    }

    @Test fun unknownReferenceIsIgnoredAndOctaveDoesNotCount() {
        assertNull(book.record(pcm, Kit.RATE, "x", referenceId = "nope").rec.compare)
        val ref = book.importDemo("示範", Pitch.track(Kit.take(64 to 800, 67 to 800), Kit.RATE))!!
        val up = book.record(Pitch.track(Kit.take(76 to 800, 79 to 800), Kit.RATE), "高八度", referenceId = ref.id)
        assertEquals(0, up.rec.compare!!.shiftSemitones)
    }

    @Test fun importsReferencesFromUltraStar() {
        val txt = "#BPM:120\n#GAP:0\n: 0 4 0 la\n: 4 4 2 la\n- 8\n: 8 4 4 la\nE\n"
        val ref = book.importUltraStar("卡拉", txt)!!
        assertEquals(3, ref.notes.size)
        assertEquals("ref1", ref.id)
        assertEquals("ref2", book.importUltraStar("另一首", txt)!!.id)
        assertNull(book.importUltraStar("壞", "nothing"))
        assertNull(book.importMidi("壞", byteArrayOf(0, 1)))
    }

    private fun rec(inTune: Int, pitchPct: Int? = null, voiced: Double = 5.0, lowest: Int? = 60) = SongRec(
        title = "t", at = "2030-01-07T00:00:00Z", durationSec = 5.0, voicedSec = voiced, inTunePct = inTune, lowestMidi = lowest,
        compare = pitchPct?.let { CompareResult("嚴格", it, 0, 0, 0, 0, true, emptyList()) },
    )

    @Test fun vocalScoreBands() {
        listOf(100 to 5, 90 to 5, 89 to 4, 75 to 4, 74 to 3, 60 to 3, 59 to 2, 40 to 2, 39 to 1, 0 to 1).forEach { (pct, score) ->
            assertEquals("$pct", score, book.vocalScore(rec(pct)))
        }
        // With a comparison the two are averaged.
        assertEquals(4, book.vocalScore(rec(100, pitchPct = 50)))
        assertEquals(1, book.vocalScore(rec(100, voiced = 0.0, lowest = null)))
        assertNotNull(SongBook.vocalScore(rec(80)))
    }
}
