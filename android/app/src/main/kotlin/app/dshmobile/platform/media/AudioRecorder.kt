package app.dshmobile.platform.media

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.MediaRecorder
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The microphone side of [AudioRecorder] (the phone's [MediaRecorder]; a fake in tests). */
interface RecorderEngine {
    /** Starts writing to [out]; [onLimit] is called (on any thread) when [maxMs] is reached and the engine stopped itself. */
    fun start(out: File, maxMs: Int, onLimit: () -> Unit)

    /** Stops; true when [out] now holds a recording that can be played. Safe to call when it already stopped itself. */
    fun stop(): Boolean

    fun release()
}

/** Plays a recording: the plaintext file before he hands it in, or the bytes the vault opened when he listens again. */
interface PlayerEngine {
    fun play(file: File, onDone: () -> Unit)
    fun play(bytes: ByteArray, onDone: () -> Unit)
    fun stop()
}

/**
 * 清唱錄音's in-app recorder (arch T07): record up to [MAX_MS] (three minutes), stop, play it back, redo, or take it to hand in.
 * The recording lives only as a plaintext file in `cache/record/` (swept at every start) until [take] hands it to the caller,
 * which seals it into the vault as a stream and deletes it. One instance per screen; not thread-safe beyond [onLimit].
 */
class AudioRecorder(
    private val dir: File,
    private val engine: RecorderEngine,
    private val now: () -> Long = System::currentTimeMillis,
    private val newName: () -> String = { UUID.randomUUID().toString() },
) {
    sealed interface State {
        data object Idle : State
        data class Recording(val startedAt: Long) : State
        data class Recorded(val file: File, val durationMs: Long) : State

        /** The microphone could not be used (busy, no permission given to the engine); nothing is kept. */
        data object Failed : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun start() {
        if (_state.value is State.Recording || _state.value is State.Recorded) return
        dir.mkdirs()
        val file = File(dir, newName() + ".m4a")
        val startedAt = now()
        synchronized(this) {
            try {
                current = file
                _state.value = State.Recording(startedAt)
                engine.start(file, MAX_MS) { synchronized(this) { stopWith(file, startedAt) } }
            } catch (e: Exception) {
                current = null
                file.delete()
                _state.value = State.Failed
            }
        }
    }

    @Volatile private var current: File? = null

    /** Stops recording (also what the three-minute limit does). */
    fun stop() = synchronized(this) {
        val s = _state.value as? State.Recording ?: return
        stopWith(current ?: return, s.startedAt)
    }

    private fun stopWith(file: File, startedAt: Long) {
        if (_state.value !is State.Recording) return
        val ok = runCatching { engine.stop() }.getOrDefault(false)
        current = null
        _state.value = if (ok && file.isFile && file.length() > 0) {
            State.Recorded(file, (now() - startedAt).coerceIn(0, MAX_MS.toLong()))
        } else {
            file.delete()
            State.Failed
        }
    }

    /** Throws the recording away and starts over (from Recorded or Failed). */
    fun redo() = synchronized(this) {
        (_state.value as? State.Recorded)?.file?.delete()
        if (_state.value !is State.Recording) _state.value = State.Idle
    }

    /** The recorded file for the caller to seal (and delete); back to Idle. Null when there is none. */
    fun take(): File? = synchronized(this) {
        val s = _state.value as? State.Recorded ?: return null
        _state.value = State.Idle
        s.file
    }

    /** Leaving the screen: stops, deletes the plaintext, releases the microphone. */
    fun discard() = synchronized(this) {
        if (_state.value is State.Recording) runCatching { engine.stop() }
        current?.delete()
        current = null
        (_state.value as? State.Recorded)?.file?.delete()
        _state.value = State.Idle
        runCatching { engine.release() }
    }

    companion object {
        const val MAX_MS = 3 * 60 * 1000
        const val MIME = "audio/mp4"

        /** Where a recording waits, in the clear, until it is handed in (`cache/record/`; swept at every start). */
        fun recordDir(context: Context) = File(context.cacheDir, "record")
    }
}

/** The phone's microphone: AAC in an MPEG-4 file, mono 44.1 kHz, 128 kbit/s (about 3 MB for three minutes). */
class MediaRecorderEngine(private val context: Context) : RecorderEngine {
    private var recorder: MediaRecorder? = null
    @Volatile private var limited = false

    override fun start(out: File, maxMs: Int, onLimit: () -> Unit) {
        release()
        val r = MediaRecorder(context)
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(128_000)
            r.setMaxDuration(maxMs)
            r.setOutputFile(out)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    limited = true
                    onLimit()
                }
            }
            limited = false
            r.prepare()
            r.start()
            recorder = r
        } catch (e: Exception) {
            r.release()
            throw e
        }
    }

    override fun stop(): Boolean {
        val r = recorder ?: return false
        recorder = null
        // At the limit the recorder already stopped itself and the file is complete; a second stop would throw.
        if (limited) return true.also { r.release() }
        return try {
            r.stop()
            true
        } catch (_: RuntimeException) {
            false // stopped right after start: no valid file
        } finally {
            r.release()
        }
    }

    override fun release() {
        recorder?.release()
        recorder = null
    }
}

/** Plays through [MediaPlayer]: a file path, or bytes from memory (the vault's plaintext never touches the disk). */
class MediaPlayerEngine : PlayerEngine {
    private var player: MediaPlayer? = null

    override fun play(file: File, onDone: () -> Unit) = start(onDone) { it.setDataSource(file.path) }

    override fun play(bytes: ByteArray, onDone: () -> Unit) = start(onDone) { it.setDataSource(BytesSource(bytes)) }

    private fun start(onDone: () -> Unit, source: (MediaPlayer) -> Unit) {
        stop()
        val p = MediaPlayer()
        try {
            source(p)
            p.setOnCompletionListener { stop(); onDone() }
            p.prepare()
            p.start()
            player = p
        } catch (e: Exception) {
            p.release()
            onDone()
        }
    }

    override fun stop() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    private class BytesSource(private val bytes: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= bytes.size) return -1
            val n = minOf(size.toLong(), bytes.size - position).toInt()
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
            return n
        }

        override fun getSize(): Long = bytes.size.toLong()
        override fun close() = Unit
    }
}
