package app.agency.core.singing

import kotlin.math.roundToInt

/** One note of the reference melody: [startMs], [endMs], its pitch as a MIDI note; [line] the phrase (lyric line) it belongs to. */
data class RefNote(val startMs: Int, val endMs: Int, val midi: Int, val line: Int = 0, val scored: Boolean = true)

/**
 * The reference melody he imports (T19d; refs L §6): an UltraStar song file (the format only, read by our own code), a MIDI
 * melody (a standard MIDI file, the track with the most notes), or his 示範錄音 tracked by the same YIN as his take. Nothing is
 * bundled; nothing is downloaded.
 */
object Reference {
    /**
     * UltraStar `.txt`: `#BPM` and `#GAP` (ms) headers, note lines `<type> <beat> <length> <pitch> <syllable>` (type `:` normal,
     * `*` golden, `F` freestyle and `R`/`G` rap are not pitch-scored), `-` ends a phrase, `E` ends the song. Pitch 0 is MIDI 60;
     * a beat is 15 / BPM seconds. Null when it has no BPM or no note.
     */
    fun ultraStar(text: String): List<RefNote>? {
        var bpm: Double? = null
        var gap = 0.0
        var line = 0
        val notes = mutableListOf<RefNote>()
        for (raw in text.lines()) {
            val l = raw.trim()
            when {
                l.startsWith("#BPM:", ignoreCase = true) -> bpm = l.substringAfter(':').trim().replace(',', '.').toDoubleOrNull()
                l.startsWith("#GAP:", ignoreCase = true) -> gap = l.substringAfter(':').trim().replace(',', '.').toDoubleOrNull() ?: 0.0
                l.startsWith("-") -> line++
                l.startsWith("E") -> break
                l.isNotEmpty() && l[0] in ":*FRG" -> {
                    val b = bpm ?: return null
                    val parts = l.substring(1).trim().split(Regex("\\s+"), limit = 4)
                    val beat = parts.getOrNull(0)?.toIntOrNull() ?: continue
                    val len = parts.getOrNull(1)?.toIntOrNull() ?: continue
                    val pitch = parts.getOrNull(2)?.toIntOrNull() ?: continue
                    val ms = { bt: Int -> (gap + bt * 15000.0 / b).roundToInt() }
                    notes += RefNote(ms(beat), ms(beat + len), 60 + pitch, line, scored = l[0] == ':' || l[0] == '*')
                }
            }
        }
        return notes.takeIf { it.isNotEmpty() && bpm != null }
    }

    /** A standard MIDI file: the track with the most notes, with the tempo map of the file (one channel's melody). */
    fun midi(bytes: ByteArray): List<RefNote>? = runCatching { Smf(bytes).melody() }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** His 示範錄音: the held notes of a take, each a reference note; phrases from the breaths. */
    fun demo(frames: List<PitchFrame>): List<RefNote> {
        val phrases = Analysis.phrases(frames)
        return Analysis.held(frames).map { (h, _) ->
            val line = phrases.indexOfFirst { h.startMs >= it.first && h.startMs < it.second }.coerceAtLeast(0)
            RefNote(h.startMs, h.endMs, Pitch.nearestMidi(h.cents), line)
        }
    }

    /** Lines (phrases) of MIDI notes: a silence over [LINE_GAP_MS] starts a new one. */
    fun lines(notes: List<RefNote>): List<RefNote> {
        var line = 0
        return notes.sortedBy { it.startMs }.mapIndexed { i, n ->
            if (i > 0 && n.startMs - notes.sortedBy { it.startMs }[i - 1].endMs > LINE_GAP_MS) line++
            n.copy(line = line)
        }
    }

    const val LINE_GAP_MS = 500

    /** A small reader of standard MIDI files (format 0 or 1), only what a melody needs. */
    private class Smf(private val b: ByteArray) {
        private var p = 0
        private fun u8() = b[p++].toInt() and 0xff
        private fun u16() = (u8() shl 8) or u8()
        private fun u32() = (u16() shl 16) or u16()
        private fun vlq(): Int { var v = 0; while (true) { val c = u8(); v = (v shl 7) or (c and 0x7f); if (c and 0x80 == 0) return v } }

        fun melody(): List<RefNote> {
            check(String(b, 0, 4, Charsets.US_ASCII) == "MThd")
            p = 8
            u16()
            val tracks = u16()
            val division = u16()
            check(division and 0x8000 == 0) { "SMPTE time not read" }
            p = 8 + 6
            val tempos = sortedMapOf(0L to 500_000)
            val all = mutableListOf<List<Triple<Long, Long, Int>>>()
            repeat(tracks) {
                while (String(b, p, 4, Charsets.US_ASCII) != "MTrk") { p += 4; p += u32() }
                p += 4
                val end = u32() + p
                var tick = 0L
                var status = 0
                val on = HashMap<Int, Long>()
                val notes = mutableListOf<Triple<Long, Long, Int>>()
                while (p < end) {
                    tick += vlq()
                    var s = u8()
                    if (s < 0x80) { p--; s = status } else if (s < 0xf0) status = s
                    when {
                        s == 0xff -> { val type = u8(); val len = vlq(); if (type == 0x51 && len == 3) tempos[tick] = (u8() shl 16) or (u8() shl 8) or u8() else p += len }
                        s == 0xf0 || s == 0xf7 -> p += vlq()
                        s and 0xf0 == 0x90 -> { val k = u8(); val v = u8(); if (v > 0) on[k] = tick else on.remove(k)?.let { notes += Triple(it, tick, k) } }
                        s and 0xf0 == 0x80 -> { val k = u8(); u8(); on.remove(k)?.let { notes += Triple(it, tick, k) } }
                        s and 0xf0 == 0xc0 || s and 0xf0 == 0xd0 -> u8()
                        else -> { u8(); u8() }
                    }
                }
                p = end
                all += notes
            }
            val track = all.maxByOrNull { it.size } ?: return emptyList()
            fun ms(t: Long): Int {
                var us = 0.0
                var lastTick = 0L
                var tempo = 500_000
                for ((at, tp) in tempos) {
                    if (at >= t) break
                    us += (at - lastTick) * tempo.toDouble() / division
                    lastTick = at
                    tempo = tp
                }
                us += (t - lastTick) * tempo.toDouble() / division
                return (us / 1000).roundToInt()
            }
            // A melody: when notes overlap, the higher one.
            val sorted = track.sortedWith(compareBy({ it.first }, { -it.third }))
            val mono = sorted.filterIndexed { i, n -> i == 0 || n.first != sorted[i - 1].first }
            return lines(mono.map { RefNote(ms(it.first), ms(it.second), it.third) })
        }
    }
}
