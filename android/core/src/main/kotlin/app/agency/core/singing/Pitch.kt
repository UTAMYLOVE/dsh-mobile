package app.agency.core.singing

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One analysis frame: its start in ms, the pitch in Hz (null: unvoiced) and the loudness in dBFS. */
data class PitchFrame(val ms: Int, val hz: Double?, val db: Double) {
    /** The pitch in cents on the MIDI scale (MIDI note × 100), or null. */
    val cents: Double? get() = hz?.let { Pitch.cents(it) }
}

/**
 * The phone's own pitch tracker (T19d): YIN (de Cheveigné & Kawahara 2002), written here from the paper — no library, no
 * native code. Mono 16-bit PCM in, one [PitchFrame] every [HOP_MS] ms. A frame is voiced when YIN's dip is below
 * [THRESHOLD] and it is loud enough ([SILENCE_DB]); octave jumps are smoothed by a 5-frame median afterwards.
 */
object Pitch {
    const val HOP_MS = 10
    const val WINDOW_MS = 40
    const val THRESHOLD = 0.15
    const val SILENCE_DB = -45.0
    const val MIN_HZ = 70.0
    const val MAX_HZ = 1000.0

    fun cents(hz: Double): Double = 1200.0 * log2(hz / 440.0) + 6900.0
    fun hz(cents: Double): Double = 440.0 * 2.0.pow((cents - 6900.0) / 1200.0)

    /** Frames of [pcm] at [rate] Hz. */
    fun track(pcm: ShortArray, rate: Int): List<PitchFrame> {
        val hop = rate * HOP_MS / 1000
        val w = rate * WINDOW_MS / 1000
        val minLag = (rate / MAX_HZ).toInt()
        val maxLag = (rate / MIN_HZ).toInt().coerceAtMost(w - 1)
        val out = ArrayList<PitchFrame>()
        val d = DoubleArray(maxLag + 1)
        var start = 0
        while (start + w + maxLag <= pcm.size) {
            var sum = 0.0
            for (i in start until start + w) sum += pcm[i].toDouble() * pcm[i]
            val rms = sqrt(sum / w) / 32768.0
            val db = if (rms <= 0) -120.0 else 20 * log10(rms)
            var hz: Double? = null
            if (db > SILENCE_DB) {
                for (tau in 1..maxLag) {
                    var s = 0.0
                    for (i in start until start + w) {
                        val diff = pcm[i].toDouble() - pcm[i + tau]
                        s += diff * diff
                    }
                    d[tau] = s
                }
                // Cumulative mean normalised difference.
                var running = 0.0
                d[0] = 1.0
                for (tau in 1..maxLag) {
                    running += d[tau]
                    d[tau] = if (running == 0.0) 1.0 else d[tau] * tau / running
                }
                var tau = minLag.coerceAtLeast(2)
                var found = -1
                while (tau <= maxLag) {
                    if (d[tau] < THRESHOLD) {
                        while (tau + 1 <= maxLag && d[tau + 1] < d[tau]) tau++
                        found = tau
                        break
                    }
                    tau++
                }
                if (found > 0) {
                    val a = d[found - 1]
                    val b = d[found]
                    val c = if (found + 1 <= maxLag) d[found + 1] else b
                    val den = a + c - 2 * b
                    val shift = if (abs(den) < 1e-12) 0.0 else (a - c) / (2 * den)
                    hz = rate / (found + shift)
                }
            }
            out += PitchFrame(start * 1000 / rate, hz, db)
            start += hop
        }
        return smooth(out)
    }

    /** A 5-frame median of the voiced cents, a frame more than half an octave off it (an octave jump) takes the median. */
    fun smooth(frames: List<PitchFrame>): List<PitchFrame> = frames.mapIndexed { i, f ->
        val c = f.cents ?: return@mapIndexed f
        val around = (i - 2..i + 2).mapNotNull { frames.getOrNull(it)?.cents }
        if (around.size < 3) return@mapIndexed f.copy(hz = null)
        val med = around.sorted()[around.size / 2]
        if (abs(c - med) > 600) f.copy(hz = hz(med)) else f
    }

    private val DEGREE = listOf("1", "升1", "2", "升2", "3", "4", "升4", "5", "升5", "6", "升6", "7")
    private val OCTAVE = mapOf(2 to "倍低音", 3 to "低音", 4 to "中音", 5 to "高音", 6 to "倍高音")

    /** A MIDI note in 簡譜 words with no Latin letter: 60 → 「中音 1」, 55 → 「低音 5」, 73 → 「高音 升1」. */
    fun noteName(midi: Int): String {
        val oct = midi / 12 - 1
        return "${OCTAVE[oct] ?: "第${oct}組"} ${DEGREE[Math.floorMod(midi, 12)]}"
    }

    fun nearestMidi(cents: Double): Int = (cents / 100.0).roundToInt()
}
