package app.agency.core.singing

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/** A held note found in his take: from [startMs] to [endMs], its median pitch in cents and how far it sat off the nearest note. */
@Serializable
data class HeldNote(val startMs: Int, val endMs: Int, val cents: Double, val offCents: Int, val jitterCents: Int)

/**
 * What code computed from one take (T19d) — the only numbers a comment may use. [inTunePct]: share of the held notes' frames
 * within ±50 cents of the nearest note; [lowest]/[highest]: the lowest and highest held note (MIDI); [longestPhraseSec]: the
 * longest sung stretch (breath); [volumeSteadyPct]: share of sung frames within ±6 dB of their phrase's median; [jitterCents]:
 * the mean wobble on held notes; [worst]: the three held notes furthest off, with their time.
 */
@Serializable
data class SingingFacts(
    val durationSec: Double,
    val voicedSec: Double,
    val inTunePct: Int,
    val lowest: Int?,
    val highest: Int?,
    val longestPhraseSec: Double,
    val phrases: Int,
    val volumeSteadyPct: Int,
    val jitterCents: Int,
    val worst: List<HeldNote>,
) {
    /** The facts as he (and the teacher) read them; every number here is code's. */
    fun lines(): List<String> = buildList {
        add("音準：長音有 $inTunePct% 落在正確音高的半個半音以內")
        if (lowest != null && highest != null) add("音域：${Pitch.noteName(lowest)} 到 ${Pitch.noteName(highest)}（${highest - lowest} 個半音）")
        add("氣長：最長一句 ${fmt(longestPhraseSec)} 秒，共 $phrases 句")
        add("音量穩定：$volumeSteadyPct%")
        add("長音抖動：平均 $jitterCents 音分")
        worst.forEach { w -> add("偏最多：${fmt(w.startMs / 1000.0)} 秒那個長音${if (w.offCents > 0) "偏高" else "偏低"} ${abs(w.offCents)} 音分") }
    }

    companion object {
        fun fmt(x: Double): String = ((x * 10).roundToInt() / 10.0).let { if (it == it.toInt().toDouble()) it.toInt().toString() else it.toString() }
    }
}

object Analysis {
    /** A gap shorter than this inside singing is not a breath. */
    const val BREATH_GAP_MS = 250
    const val HELD_MIN_MS = 200
    const val HELD_SPREAD_CENTS = 80.0
    const val IN_TUNE_CENTS = 50.0
    const val STEADY_DB = 6.0

    /** Sung stretches (start, end ms), gaps shorter than [BREATH_GAP_MS] joined. */
    fun phrases(frames: List<PitchFrame>): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var s: Int? = null
        var last = 0
        for (f in frames) {
            if (f.hz == null) continue
            if (s == null) s = f.ms
            else if (f.ms - last > BREATH_GAP_MS) { out += s to last + Pitch.HOP_MS; s = f.ms }
            last = f.ms
        }
        s?.let { out += it to last + Pitch.HOP_MS }
        return out.filter { it.second - it.first >= HELD_MIN_MS }
    }

    /** Held notes: runs of voiced frames that stay within [HELD_SPREAD_CENTS] of their running median for ≥ [HELD_MIN_MS]. */
    fun held(frames: List<PitchFrame>): List<Pair<HeldNote, List<Double>>> {
        val out = mutableListOf<Pair<HeldNote, List<Double>>>()
        var run = mutableListOf<PitchFrame>()
        fun close() {
            if (run.size * Pitch.HOP_MS >= HELD_MIN_MS) {
                val cs = run.map { it.cents!! }
                val med = cs.sorted()[cs.size / 2]
                val off = med - Pitch.nearestMidi(med) * 100
                val jit = cs.map { abs(it - med) }.average()
                out += HeldNote(run.first().ms, run.last().ms + Pitch.HOP_MS, med, off.roundToInt(), jit.roundToInt()) to cs
            }
            run = mutableListOf()
        }
        for (f in frames) {
            val c = f.cents
            if (c == null) { close(); continue }
            if (run.isNotEmpty()) {
                val med = run.map { it.cents!! }.sorted()[run.size / 2]
                if (abs(c - med) > HELD_SPREAD_CENTS) close()
            }
            run += f
        }
        close()
        return out
    }

    fun of(frames: List<PitchFrame>): SingingFacts {
        val held = held(frames)
        val phrases = phrases(frames)
        val heldCents = held.flatMap { (h, cs) -> cs.map { it - Pitch.nearestMidi(h.cents) * 100 } }
        val inTune = if (heldCents.isEmpty()) 0 else heldCents.count { abs(it) <= IN_TUNE_CENTS } * 100 / heldCents.size
        val notes = held.map { Pitch.nearestMidi(it.first.cents) }
        var steady = 0
        var voiced = 0
        phrases.forEach { (a, b) ->
            val dbs = frames.filter { it.hz != null && it.ms in a until b }.map { it.db }
            if (dbs.isEmpty()) return@forEach
            val med = dbs.sorted()[dbs.size / 2]
            steady += dbs.count { abs(it - med) <= STEADY_DB }
            voiced += dbs.size
        }
        return SingingFacts(
            durationSec = round1((frames.lastOrNull()?.ms ?: 0) / 1000.0 + Pitch.HOP_MS / 1000.0),
            voicedSec = round1(frames.count { it.hz != null } * Pitch.HOP_MS / 1000.0),
            inTunePct = inTune,
            lowest = notes.minOrNull(),
            highest = notes.maxOrNull(),
            longestPhraseSec = round1((phrases.maxOfOrNull { it.second - it.first } ?: 0) / 1000.0),
            phrases = phrases.size,
            volumeSteadyPct = if (voiced == 0) 0 else steady * 100 / voiced,
            jitterCents = if (held.isEmpty()) 0 else held.map { it.first.jitterCents }.average().roundToInt(),
            worst = held.map { it.first }.sortedByDescending { abs(it.offCents) }.take(3).sortedBy { it.startMs },
        )
    }

    private fun round1(x: Double) = (x * 10).roundToInt() / 10.0

}
