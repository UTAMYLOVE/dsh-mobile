package app.agency.core.singing

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit PCM WAV, mono (the left channel of a stereo file), read and written by our own code. */
object Wav {
    data class Pcm(val samples: ShortArray, val rate: Int)

    fun read(bytes: ByteArray): Pcm? = runCatching {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")
        var p = 12
        var rate = 0
        var channels = 1
        var bits = 16
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4, Charsets.US_ASCII)
            val len = b.getInt(p + 4)
            if (id == "fmt ") {
                check(b.getShort(p + 8).toInt() == 1) { "PCM only" }
                channels = b.getShort(p + 10).toInt()
                rate = b.getInt(p + 12)
                bits = b.getShort(p + 22).toInt()
            } else if (id == "data") {
                check(bits == 16 && rate > 0)
                val n = minOf(len, bytes.size - p - 8) / 2 / channels
                val out = ShortArray(n) { i -> b.getShort(p + 8 + i * 2 * channels) }
                return Pcm(out, rate)
            }
            p += 8 + len + (len and 1)
        }
        null
    }.getOrNull()

    fun write(pcm: ShortArray, rate: Int): ByteArray {
        val b = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(pcm.size * 2)
        pcm.forEach { b.putShort(it) }
        return b.array()
    }

    /** [pcm] at [rate] down to [to] Hz (averaging, enough for speech and pitch). */
    fun resample(pcm: ShortArray, rate: Int, to: Int): ShortArray {
        if (rate == to) return pcm
        val n = (pcm.size.toLong() * to / rate).toInt()
        return ShortArray(n) { i ->
            val a = (i.toLong() * rate / to).toInt()
            val z = ((i + 1L) * rate / to).toInt().coerceAtMost(pcm.size).coerceAtLeast(a + 1)
            var s = 0L
            for (j in a until z) s += pcm[j]
            (s / (z - a)).toInt().toShort()
        }
    }
}
