package app.dshmobile.wire.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import app.agency.core.agent.CompanyAdmin
import app.agency.core.base.Clock
import app.agency.core.extras.SongBook
import app.agency.core.extras.SongRec
import app.agency.core.singing.Chart
import app.agency.core.singing.DeepSeekAsr
import app.agency.core.singing.Diction
import app.agency.core.singing.Pitch
import app.agency.core.singing.Strictness
import app.agency.core.singing.Wav
import app.agency.core.store.Role
import app.agency.core.store.Store
import app.dshmobile.R
import app.dshmobile.platform.media.AudioDecode
import app.dshmobile.platform.media.AudioRecorder
import app.dshmobile.platform.media.MediaVault
import app.dshmobile.platform.media.RecorderEngine
import app.dshmobile.ui.LyricsOutcome
import app.dshmobile.ui.RefImport
import app.dshmobile.ui.SingingUi
import app.dshmobile.ui.SongRefRow
import app.dshmobile.ui.SongResult
import app.dshmobile.wire.ActivityHost
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import app.agency.core.extras.SongResult as CoreSong

/**
 * 唱歌分析: the microphone question, then [AudioRecorder] (the file lives in the cache folder in the clear only until it is
 * decoded), the take is decoded on the phone ([AudioDecode]), analysed by the company ([CompanyAdmin.recordSong]) and the
 * recording itself is sealed in the vault. The recording never leaves the phone, with one exception: the lyric check, which
 * sends the LAST take to speech-to-text only when he ticked the consent box for that one time ([checkLyrics]).
 *
 * With a [store] and [clock] the take goes through [SongBook] directly, so a chosen reference and the 一般 / 嚴格 switch apply and
 * the pitch-curve picture can be drawn; without them the company's own door is used (its default is 嚴格, no reference).
 */
class SingingAdapter(
    private val context: Context,
    private val host: ActivityHost,
    private val admin: CompanyAdmin,
    private val vault: () -> MediaVault,
    private val engine: () -> RecorderEngine,
    private val title: String,
    private val store: Store? = null,
    private val clock: (() -> Clock)? = null,
    /** Builds the speech-to-text client; its consent check answers [consent]. Null while no DeepSeek key exists. */
    private val asr: ((consent: Boolean) -> DeepSeekAsr?)? = null,
    private val lyricsKey: () -> Boolean = { false },
) : SingingUi {
    private var recorder: AudioRecorder? = null

    @Volatile private var strict = true
    @Volatile private var chosenRef: String? = null
    @Volatile private var lastWav: ByteArray? = null
    @Volatile private var lastChart: ByteArray? = null

    private fun book(): SongBook? {
        val s = store ?: return null
        val c = clock ?: return null
        return SongBook(s, c())
    }

    override suspend fun start(): Boolean {
        if (!host.askMicrophone()) return false
        return withContext(Dispatchers.IO) {
            synchronized(this@SingingAdapter) {
                recorder?.discard()
                val r = AudioRecorder(AudioRecorder.recordDir(context), engine())
                r.start()
                if (r.state.value is AudioRecorder.State.Failed) {
                    r.discard()
                    recorder = null
                    false
                } else {
                    recorder = r
                    true
                }
            }
        }
    }

    /**
     * Stops and analyses; the company's result (stored as a song entry), or null when nothing usable was recorded. [compare] false
     * leaves the chosen reference out (a take handed in for an evaluation is not a practice against a reference).
     */
    suspend fun stopEntry(compare: Boolean = true): CoreSong? = withContext(Dispatchers.IO) {
        val r = synchronized(this@SingingAdapter) { recorder.also { recorder = null } } ?: return@withContext null
        try {
            r.stop()
            val file = r.take() ?: return@withContext null
            val pcm = AudioDecode.pcm(file)
            if (pcm == null) {
                file.delete()
                return@withContext null
            }
            val id = UUID.randomUUID().toString()
            // Sealing deletes the plaintext file, also when it fails.
            val vaultId = runCatching { vault().putFile(id, file); id }.getOrNull()
            lastWav = runCatching { Wav.write(Wav.resample(pcm.samples, pcm.rate, ASR_RATE), ASR_RATE) }.getOrNull()
            val book = book()
            if (book == null) {
                lastChart = null
                admin.recordSong(pcm.samples, pcm.rate, title, null, vaultId)
            } else {
                val frames = Pitch.track(pcm.samples, pcm.rate)
                val ref = if (compare) chosenRef else null
                val res = book.record(frames, title, ref, vaultId, if (strict) Strictness.STRICT else Strictness.NORMAL, aCappella = true)
                lastChart = runCatching { Chart.png(frames, ref?.let { book.ref(it)?.notes }.orEmpty()) }.getOrNull()
                // The same quiet line the company leaves for a take, with the summary card.
                store?.addMessage(
                    clock!!().now(), Role.NOTE, "✓ 錄好了：${res.rec.title.ifBlank { "一首歌" }}（#${res.entryId}）", listOf(res.card),
                )
                res
            }
        } finally {
            r.discard()
        }
    }

    override suspend fun stop(): SongResult? = stopEntry()?.let { map(it.rec, it.card.lines, lastChart) }

    /** One take of [seconds] seconds (the evaluation hands a song in without a stop button). Null when it could not be made. */
    suspend fun recordFor(seconds: Int): CoreSong? {
        if (!start()) return null
        delay(seconds * 1000L)
        return stopEntry(compare = false)
    }

    override fun history(): List<SongResult> = admin.songs().sortedByDescending { it.entryId }.map { map(it.rec, emptyList()) }

    // ---- references, strictness, lyrics ----------------------------------------------------------------------------------

    override fun references(): List<SongRefRow> = book()?.refs().orEmpty().map { SongRefRow(it.id, it.title, it.source) }

    override fun chooseReference(id: String?) {
        chosenRef = id
    }

    override fun setStrict(strict: Boolean) {
        this.strict = strict
    }

    override suspend fun importReference(): RefImport {
        val uri = host.launch(ActivityResultContracts.OpenDocument(), arrayOf("*/*")) ?: return RefImport(null, null)
        val book = book() ?: return RefImport(null, context.getString(R.string.wire_ref_bad))
        return withContext(Dispatchers.IO) {
            val bytes = readBytes(uri) ?: return@withContext RefImport(null, context.getString(R.string.wire_ref_bad))
            val name = displayName(uri).substringBeforeLast('.').ifBlank { context.getString(R.string.wire_ref_demo) }
            val ref = runCatching {
                when {
                    bytes.size > 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "MThd" -> book.importMidi(name, bytes)
                    looksLikeText(bytes) -> book.importUltraStar(name, String(bytes, Charsets.UTF_8))
                    else -> demo(book, name, bytes)
                }
            }.getOrNull()
            if (ref == null) RefImport(null, context.getString(R.string.wire_ref_bad)) else RefImport(ref.title, null)
        }
    }

    private fun demo(book: SongBook, name: String, bytes: ByteArray): app.agency.core.extras.SongRef? {
        val file = File(context.cacheDir, "ref-${UUID.randomUUID()}.bin")
        return try {
            file.writeBytes(bytes)
            val pcm = AudioDecode.pcm(file) ?: return null
            book.importDemo(name, Pitch.track(pcm.samples, pcm.rate))
        } finally {
            file.delete()
        }
    }

    private fun looksLikeText(b: ByteArray): Boolean = b.take(512).none { it == 0.toByte() }

    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull().orEmpty()

    private fun readBytes(uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_REF_BYTES) return@runCatching null
            }
            out.toByteArray()
        }
    }.getOrNull()

    override fun lyricsAvailable(): Boolean = asr != null && lyricsKey()

    override suspend fun checkLyrics(lyrics: String, consent: Boolean): LyricsOutcome {
        // Without the tick nothing is sent: the speech-to-text client would refuse as well, this is the first of two locks.
        if (!consent) return LyricsOutcome(emptyList(), context.getString(R.string.wire_sing_no_consent))
        val wav = lastWav ?: return LyricsOutcome(emptyList(), context.getString(R.string.wire_sing_no_take))
        val client = asr?.invoke(true) ?: return LyricsOutcome(emptyList(), context.getString(R.string.wire_sing_failed))
        return when (val r = client.transcribe(wav)) {
            is DeepSeekAsr.Result.Ok -> LyricsOutcome(Diction.lines(Diction.check(lyrics, r.text)), null)
            is DeepSeekAsr.Result.Failed -> LyricsOutcome(emptyList(), context.getString(R.string.wire_sing_failed))
        }
    }

    companion object {
        const val ASR_RATE = 16_000
        const val MAX_REF_BYTES = 30 shl 20

        fun map(rec: SongRec, lines: List<String>, chart: ByteArray? = null): SongResult {
            val cmp = rec.compare
            val shift = cmp?.takeIf { it.shiftSemitones != 0 }?.lines()?.firstOrNull { it.startsWith("整首") }.orEmpty()
            return SongResult(
                inTunePct = rec.inTunePct,
                range = if (rec.rangeSemitones > 0 && rec.lowestName != null && rec.highestName != null) {
                    "${rec.lowestName}到${rec.highestName}，${rec.rangeSemitones} 個半音"
                } else {
                    "沒有量到"
                },
                longestPhraseSec = rec.longestPhraseSec.roundToInt(),
                loudness = "${rec.loudnessDb} 分貝",
                comment = lines.joinToString("\n"),
                shift = shift,
                chart = chart,
                compare = cmp?.lines()?.filterNot { it.startsWith("整首") }.orEmpty(),
            )
        }
    }
}
