package app.agency.core.extras

import app.agency.core.agent.Card
import app.agency.core.base.Clock
import app.agency.core.base.Days
import app.agency.core.singing.Analysis
import app.agency.core.singing.CompareResult
import app.agency.core.singing.HeldNote
import app.agency.core.singing.Pitch
import app.agency.core.singing.PitchFrame
import app.agency.core.singing.RefNote
import app.agency.core.singing.Reference
import app.agency.core.singing.Score
import app.agency.core.singing.SingingFacts
import app.agency.core.singing.Strictness
import app.agency.core.singing.Wav
import app.agency.core.store.Kind
import app.agency.core.store.Store
import app.agency.core.store.add
import app.agency.core.store.readDoc
import app.agency.core.store.writeDoc
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/**
 * One analysed take ([Kind.SONG] payload). Every number is code's; 小葵 may quote only these (the card carries them as lines).
 * [compare] is set when the take was compared with a stored reference; its `shiftSemitones` is "how many semitones off" for an
 * a cappella take. The recording itself stays in the media vault ([vaultId]) and never leaves the phone.
 */
@Serializable
data class SongRec(
    val title: String,
    val at: String,
    val durationSec: Double,
    val voicedSec: Double,
    val inTunePct: Int,
    val lowestMidi: Int? = null,
    val highestMidi: Int? = null,
    val lowestName: String? = null,
    val highestName: String? = null,
    val rangeSemitones: Int = 0,
    val longestPhraseSec: Double = 0.0,
    val phrases: Int = 0,
    /** Mean loudness of the voiced frames in dB below full scale (a negative number). */
    val loudnessDb: Int = 0,
    val volumeSteadyPct: Int = 0,
    val jitterCents: Int = 0,
    val worst: List<HeldNote> = emptyList(),
    val referenceId: String? = null,
    val compare: CompareResult? = null,
    val vaultId: String? = null,
)

/** An imported reference melody (his demo recording, a melody file, a karaoke file). Kept in one document. */
@Serializable
data class SongRef(val id: String, val title: String, val source: String, val notes: List<RefNote>)

@Serializable
private data class SongRefs(val refs: List<SongRef> = emptyList())

/** What [SongBook.record] hands back: the stored entry, the numbers and the card to show. */
data class SongResult(val entryId: Long, val rec: SongRec, val facts: SingingFacts, val card: Card.Summary)

/**
 * The singing glue: a take's pitch frames → [Analysis] numbers → a [Kind.SONG] entry and a [Card.Summary] whose lines are the
 * facts in Traditional Chinese. Scoring is strict by default; the comparison with a reference ignores the octave
 * (see [Score.compare]). Nothing here sends audio anywhere.
 */
class SongBook(private val store: Store, private val clock: Clock) {

    fun record(
        frames: List<PitchFrame>,
        title: String,
        referenceId: String? = null,
        vaultId: String? = null,
        strictness: Strictness = Strictness.STRICT,
        aCappella: Boolean = true,
    ): SongResult {
        val facts = Analysis.of(frames)
        val ref = referenceId?.let { ref(it) }
        val compare = ref?.let { Score.compare(frames, it.notes, strictness, aCappella) }
        val voiced = frames.filter { it.hz != null }
        val loud = if (voiced.isEmpty()) 0 else voiced.map { it.db }.average().roundToInt()
        val now = clock.now()
        val rec = SongRec(
            title = title.trim(),
            at = now.toString(),
            durationSec = facts.durationSec,
            voicedSec = facts.voicedSec,
            inTunePct = facts.inTunePct,
            lowestMidi = facts.lowest,
            highestMidi = facts.highest,
            lowestName = facts.lowest?.let { Pitch.noteName(it) },
            highestName = facts.highest?.let { Pitch.noteName(it) },
            rangeSemitones = if (facts.lowest != null && facts.highest != null) facts.highest - facts.lowest else 0,
            longestPhraseSec = facts.longestPhraseSec,
            phrases = facts.phrases,
            loudnessDb = loud,
            volumeSteadyPct = facts.volumeSteadyPct,
            jitterCents = facts.jitterCents,
            worst = facts.worst,
            referenceId = ref?.id,
            compare = compare,
            vaultId = vaultId,
        )
        val entry = store.add(Days.of(now, clock.zone), now, Kind.SONG, rec)
        return SongResult(entry.id, rec, facts, card(rec, facts))
    }

    fun record(
        pcm: ShortArray,
        rate: Int,
        title: String,
        referenceId: String? = null,
        vaultId: String? = null,
        strictness: Strictness = Strictness.STRICT,
        aCappella: Boolean = true,
    ): SongResult = record(Pitch.track(pcm, rate), title, referenceId, vaultId, strictness, aCappella)

    /** A WAV file's bytes (16-bit PCM), or null when it cannot be read. */
    fun recordWav(
        wav: ByteArray,
        title: String,
        referenceId: String? = null,
        vaultId: String? = null,
        strictness: Strictness = Strictness.STRICT,
        aCappella: Boolean = true,
    ): SongResult? = Wav.read(wav)?.let { record(it.samples, it.rate, title, referenceId, vaultId, strictness, aCappella) }

    /** The card for a stored take: the same facts, in the same words, every time. */
    fun card(rec: SongRec, facts: SingingFacts): Card.Summary {
        val lines = ArrayList<String>()
        lines += facts.lines()
        if (rec.voicedSec > 0) lines += "音量：平均 ${rec.loudnessDb} 分貝"
        rec.compare?.let { lines += it.lines() }
        return Card.Summary("唱歌分析：${rec.title}", lines)
    }

    fun song(entryId: Long): SongRec? =
        store.entry(entryId)?.takeIf { it.kind == Kind.SONG }?.let { runCatching { it.read<SongRec>() }.getOrNull() }

    /**
     * 1..5 from the numbers: the share of held notes in tune (averaged with the comparison's 音準 when a reference was used).
     * 90+ → 5, 75+ → 4, 60+ → 3, 40+ → 2, else 1; a take with no sung note is 1.
     */
    fun vocalScore(rec: SongRec): Int = Companion.vocalScore(rec)

    // ---- references ----

    fun refs(): List<SongRef> = store.readDoc<SongRefs>(REFS_DOC)?.refs.orEmpty()

    fun ref(id: String): SongRef? = refs().firstOrNull { it.id == id }

    /** His own demo recording, tracked by the same pitch tracker. Null when no note could be found in it. */
    fun importDemo(title: String, frames: List<PitchFrame>): SongRef? = save(title, "示範錄音", Reference.demo(frames))

    fun importUltraStar(title: String, text: String): SongRef? = Reference.ultraStar(text)?.let { save(title, "卡拉歌詞檔", it) }

    fun importMidi(title: String, bytes: ByteArray): SongRef? = Reference.midi(bytes)?.let { save(title, "旋律檔", it) }

    private fun save(title: String, source: String, notes: List<RefNote>): SongRef? {
        if (notes.isEmpty()) return null
        val all = refs()
        val next = (all.mapNotNull { it.id.removePrefix("ref").toIntOrNull() }.maxOrNull() ?: 0) + 1
        val ref = SongRef("ref$next", title.trim(), source, notes)
        store.writeDoc(REFS_DOC, SongRefs(all + ref))
        return ref
    }

    companion object {
        const val REFS_DOC = "song_refs"

        fun vocalScore(rec: SongRec): Int {
            if (rec.voicedSec <= 0.0 || rec.lowestMidi == null) return 1
            val pct = rec.compare?.let { (rec.inTunePct + it.pitchPct) / 2 } ?: rec.inTunePct
            return when {
                pct >= 90 -> 5
                pct >= 75 -> 4
                pct >= 60 -> 3
                pct >= 40 -> 2
                else -> 1
            }
        }
    }
}
