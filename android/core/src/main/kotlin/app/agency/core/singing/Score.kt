package app.agency.core.singing

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/**
 * How strict the comparison is (refs L §8; owner: 女團 standard, never 入門 by default): 一般 full marks within 35 cents, zero
 * at 100; 嚴格 20 / 50. Timing: within 100 ms full, 250 ms zero (嚴格 50 / 150).
 */
enum class Strictness(val zh: String, val fullCents: Double, val zeroCents: Double, val fullMs: Int, val zeroMs: Int) {
    NORMAL("一般", 35.0, 100.0, 100, 250),
    STRICT("嚴格", 20.0, 50.0, 50, 150),
    ;

    companion object {
        /** By his vocal level (T19e): 一般 until 進階, then 嚴格. */
        fun forLevel(vocalLevel: Int): Strictness = if (vocalLevel >= 3) STRICT else NORMAL
    }
}

/** A phrase's result: its number (1-based), its 音準 share and the mean signed error (cents; + is high). */
@Serializable
data class PhraseScore(val line: Int, val pitchPct: Int, val meanCents: Int)

/**
 * His take against the reference (T19d), all by code: [pitchPct] 音準, [timingPct] 節奏, [completePct] the share of reference
 * notes he sang at all; [shiftSemitones] how many semitones higher (+) or lower he sang on the whole (a cappella; 0 with the
 * backing track); [offsetMs] the time shift found; [sameOctave] whether he sang in the reference's octave (never scored);
 * [worst] the three weakest phrases.
 */
@Serializable
data class CompareResult(
    val strictness: String,
    val pitchPct: Int,
    val timingPct: Int,
    val completePct: Int,
    val shiftSemitones: Int,
    val offsetMs: Int,
    val sameOctave: Boolean,
    val worst: List<PhraseScore>,
) {
    fun lines(): List<String> = buildList {
        add("比對（$strictness）：音準 $pitchPct%、節奏 $timingPct%、唱到的音 $completePct%")
        if (shiftSemitones != 0) add("整首比原調${if (shiftSemitones > 0) "高" else "低"} ${abs(shiftSemitones)} 個半音，可以把調${if (shiftSemitones > 0) "升" else "降"} ${abs(shiftSemitones)} 個半音再唱")
        worst.forEach { add("第 ${it.line} 句：音準 ${it.pitchPct}%，平均${if (it.meanCents >= 0) "偏高" else "偏低"} ${abs(it.meanCents)} 音分") }
    }
}

object Score {
    const val DROP_HEAD_MS = 80
    const val DROP_TAIL_MS = 50
    const val SEARCH_MS = 300

    /** [x] folded to [−600, 600) cents: the octave does not count. */
    fun fold(x: Double): Double = ((x + 600) % 1200 + 1200) % 1200 - 600

    private fun lin(err: Double, full: Double, zero: Double) = ((zero - err) / (zero - full)).coerceIn(0.0, 1.0)

    /** The frames' median error against [n] at time shift [off] and key shift [k] (cents), or null when he sang none of it. */
    private fun noteError(frames: List<PitchFrame>, n: RefNote, off: Int, k: Double): Double? {
        val a = n.startMs + DROP_HEAD_MS + off
        val b = n.endMs - DROP_TAIL_MS + off
        val es = frames.filter { it.ms in a until b }.mapNotNull { f -> f.cents?.let { fold(it - n.midi * 100 - k) } }
        return if (es.isEmpty()) null else es.sorted()[es.size / 2]
    }

    /**
     * [frames] of his take against [ref]. [aCappella]: the key shift is the median difference (rounded to whole semitones) and
     * reported; with the backing track it is 0. The time shift is searched within ±[SEARCH_MS] ms in 20 ms steps.
     */
    fun compare(frames: List<PitchFrame>, ref: List<RefNote>, strictness: Strictness, aCappella: Boolean): CompareResult {
        val notes = ref.filter { it.scored && it.endMs - it.startMs > DROP_HEAD_MS + DROP_TAIL_MS }
        fun keyShift(off: Int): Double {
            if (!aCappella) return 0.0
            val d = notes.flatMap { n -> frames.filter { it.ms in n.startMs + off until n.endMs + off }.mapNotNull { f -> f.cents?.let { it - n.midi * 100 } } }
            if (d.isEmpty()) return 0.0
            return (fold(d.sorted()[d.size / 2]) / 100.0).roundToInt() * 100.0
        }
        fun onset(n: RefNote, off: Int, k: Double): Int? = frames.firstOrNull { f ->
            f.ms in (n.startMs + off - strictness.zeroMs) until (n.endMs + off) && f.cents?.let { abs(fold(it - n.midi * 100 - k)) <= 100 } == true
        }?.let { it.ms - (n.startMs + off) }
        // The pitch error plus how far the onsets are off (a 10 ms miss weighs as a cent); the smallest shift wins a tie.
        fun cost(off: Int): Double {
            val k = keyShift(off)
            return notes.sumOf { n -> (noteError(frames, n, off, k)?.let { abs(it) } ?: 600.0) + (onset(n, off, k)?.let { abs(it) / 10.0 } ?: 60.0) }
        }
        val off = (-SEARCH_MS..SEARCH_MS step 20).sortedBy { abs(it) }.minByOrNull { cost(it) } ?: 0
        val k = keyShift(off)
        var pitchSum = 0.0
        var lenSum = 0.0
        var timing = 0.0
        var sung = 0
        val perLine = HashMap<Int, MutableList<Pair<Double, Double>>>()
        for (n in notes) {
            val len = (n.endMs - n.startMs).toDouble()
            lenSum += len
            val e = noteError(frames, n, off, k)
            val s = e?.let { lin(abs(it), strictness.fullCents, strictness.zeroCents) } ?: 0.0
            pitchSum += s * len
            if (e != null) sung++
            perLine.getOrPut(n.line) { mutableListOf() } += s to (e ?: 0.0)
            // Onset: the first frame from 250 ms before to the note's end that is voiced within 100 cents of the target.
            timing += onset(n, off, k)?.let { lin(abs(it).toDouble(), strictness.fullMs.toDouble(), strictness.zeroMs.toDouble()) } ?: 0.0
        }
        val octave = run {
            val d = notes.flatMap { n -> frames.filter { it.ms in n.startMs + off until n.endMs + off }.mapNotNull { f -> f.cents?.let { it - n.midi * 100 - k } } }
            d.isEmpty() || abs(d.sorted()[d.size / 2]) < 600
        }
        val worst = perLine.map { (line, xs) -> PhraseScore(line + 1, (xs.map { it.first }.average() * 100).roundToInt(), xs.map { it.second }.average().roundToInt()) }
            .sortedBy { it.pitchPct }.take(3).sortedBy { it.line }
        val count = notes.size.coerceAtLeast(1)
        return CompareResult(
            strictness.zh,
            pitchPct = if (lenSum == 0.0) 0 else (pitchSum / lenSum * 100).roundToInt(),
            timingPct = (timing / count * 100).roundToInt(),
            completePct = sung * 100 / count,
            shiftSemitones = (k / 100).roundToInt(),
            offsetMs = off,
            sameOctave = octave,
            worst = worst,
        )
    }
}
