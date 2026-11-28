package app.dshmobile.wire.ui

import android.content.Context
import app.agency.core.agent.CompanyAdmin
import app.agency.core.extras.SongRec
import app.dshmobile.platform.media.AudioDecode
import app.dshmobile.platform.media.AudioRecorder
import app.dshmobile.platform.media.MediaVault
import app.dshmobile.platform.media.RecorderEngine
import app.dshmobile.ui.SingingUi
import app.dshmobile.ui.SongResult
import app.dshmobile.wire.ActivityHost
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import app.agency.core.extras.SongResult as CoreSong

/**
 * 唱歌分析: the microphone question, then [AudioRecorder] (the file lives in the cache folder in the clear only until it is
 * decoded), the take is decoded on the phone ([AudioDecode]), analysed by the company ([CompanyAdmin.recordSong]) and the
 * recording itself is sealed in the vault. The recording never leaves the phone.
 */
class SingingAdapter(
    private val context: Context,
    private val host: ActivityHost,
    private val admin: CompanyAdmin,
    private val vault: () -> MediaVault,
    private val engine: () -> RecorderEngine,
    private val title: String,
) : SingingUi {
    private var recorder: AudioRecorder? = null

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

    /** Stops and analyses; the company's result (stored as a song entry), or null when nothing usable was recorded. */
    suspend fun stopEntry(): CoreSong? = withContext(Dispatchers.IO) {
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
            admin.recordSong(pcm.samples, pcm.rate, title, null, vaultId)
        } finally {
            r.discard()
        }
    }

    override suspend fun stop(): SongResult? = stopEntry()?.let { map(it.rec, it.card.lines) }

    /** One take of [seconds] seconds (the evaluation hands a song in without a stop button). Null when it could not be made. */
    suspend fun recordFor(seconds: Int): CoreSong? {
        if (!start()) return null
        delay(seconds * 1000L)
        return stopEntry()
    }

    override fun history(): List<SongResult> = admin.songs().sortedByDescending { it.entryId }.map { map(it.rec, emptyList()) }

    companion object {
        fun map(rec: SongRec, lines: List<String>): SongResult = SongResult(
            inTunePct = rec.inTunePct,
            range = if (rec.rangeSemitones > 0 && rec.lowestName != null && rec.highestName != null) {
                "${rec.lowestName}到${rec.highestName}，${rec.rangeSemitones} 個半音"
            } else {
                "沒有量到"
            },
            longestPhraseSec = rec.longestPhraseSec.roundToInt(),
            loudness = "${rec.loudnessDb} 分貝",
            comment = lines.joinToString("\n"),
        )
    }
}
