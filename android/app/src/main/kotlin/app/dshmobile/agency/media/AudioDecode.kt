package app.dshmobile.agency.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import app.agency.core.singing.Wav
import java.io.File
import java.nio.ByteOrder

/**
 * A recording (AAC in MPEG-4, as the app records) decoded to mono 16-bit PCM on the phone with the system's own decoder
 * (MediaExtractor + MediaCodec; no library), for 唱歌分析 (T19d). Null when it cannot be read. Nothing leaves the phone.
 */
object AudioDecode {
    const val MAX_MS = 6 * 60 * 1000L

    fun pcm(file: File): Wav.Pcm? = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.path)
            val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            val out = java.io.ByteArrayOutputStream()
            try {
                codec.configure(fmt, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inDone = false
                var outDone = false
                while (!outDone) {
                    if (!inDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0 || ex.sampleTime > MAX_MS * 1000) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o >= 0) {
                        val buf = codec.getOutputBuffer(o)!!
                        val bytes = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(bytes)
                        out.write(bytes)
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            val bb = java.nio.ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val all = ShortArray(bb.remaining()).also { bb.get(it) }
            val mono = if (channels <= 1) all else ShortArray(all.size / channels) { all[it * channels] }
            Wav.Pcm(mono, rate)
        } finally {
            ex.release()
        }
    }.getOrNull()?.takeIf { it.samples.isNotEmpty() }
}
