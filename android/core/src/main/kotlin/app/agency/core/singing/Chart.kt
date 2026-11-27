package app.agency.core.singing

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 音高曲線圖 (T19d) as a PNG drawn by our own code (no Android, no library, no metadata chunk at all): time across, semitones up
 * (a line per semitone, every 中音 1 darker), the reference notes as bars under his curve, his pitch as dots (rose when within
 * half a semitone of a note, violet when not), the breaths as light vertical lines and the loudness as a band at the bottom.
 * The same picture he sees is what the image model may read (it carries no location or any other data).
 */
object Chart {
    const val W = 960
    const val H = 480
    private const val BAND = 70
    private const val BG = 0xFFF7F8
    private const val GRID = 0xF6DDE3
    private const val GRID_C = 0xE3B5C1
    private const val REF = 0xF4C0CF
    private const val IN = 0xE0457B
    private const val OFF = 0x7B4FC9
    private const val BREATH = 0xEBD3DB
    private const val VOL = 0xF2A7BE

    fun png(frames: List<PitchFrame>, ref: List<RefNote> = emptyList()): ByteArray {
        val px = IntArray(W * H) { BG }
        fun dot(x: Int, y: Int, c: Int, r: Int = 2) {
            for (dy in -r..r) for (dx in -r..r) if (dx * dx + dy * dy <= r * r) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until W && yy in 0 until H) px[yy * W + xx] = c
            }
        }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
            for (y in maxOf(0, y0) until minOf(H, y1)) for (x in maxOf(0, x0) until minOf(W, x1)) px[y * W + x] = c
        }
        val end = maxOf(frames.lastOrNull()?.ms ?: 0, ref.maxOfOrNull { it.endMs } ?: 0, 1000)
        val notes = frames.mapNotNull { it.cents?.div(100) } + ref.map { it.midi.toDouble() }
        val lo = ((notes.minOrNull() ?: 55.0) - 3).toInt()
        val hi = maxOf(((notes.maxOrNull() ?: 67.0) + 3).toInt(), lo + 12)
        val top = H - BAND
        fun x(ms: Int) = (ms.toLong() * (W - 1) / end).toInt()
        fun y(semi: Double) = (top - 1 - (semi - lo) * (top - 1) / (hi - lo)).roundToInt()
        for (m in lo..hi) rect(0, y(m.toDouble()), W, y(m.toDouble()) + 1, if (Math.floorMod(m, 12) == 0) GRID_C else GRID)
        Analysis.phrases(frames).forEach { (a, b) -> rect(x(a), 0, x(a) + 1, top, BREATH); rect(x(b), 0, x(b) + 1, top, BREATH) }
        ref.forEach { n -> rect(x(n.startMs), y(n.midi + 0.5), x(n.endMs).coerceAtLeast(x(n.startMs) + 2), y(n.midi - 0.5), REF) }
        frames.forEach { f ->
            val c = f.cents ?: return@forEach
            val off = abs(c - Pitch.nearestMidi(c) * 100) > Analysis.IN_TUNE_CENTS
            dot(x(f.ms), y(c / 100), if (off) OFF else IN)
        }
        frames.forEach { f ->
            val h = ((f.db + 60).coerceIn(0.0, 60.0) / 60 * (BAND - 8)).roundToInt()
            if (h > 0) rect(x(f.ms), H - h, x(f.ms) + 2, H, VOL)
        }
        return encode(px)
    }

    /** RGB, 8 bits, no filter, no ancillary chunk. */
    private fun encode(px: IntArray): ByteArray {
        val raw = ByteArray(H * (1 + W * 3))
        var i = 0
        for (y in 0 until H) {
            raw[i++] = 0
            for (x in 0 until W) {
                val c = px[y * W + x]
                raw[i++] = (c shr 16).toByte(); raw[i++] = (c shr 8).toByte(); raw[i++] = c.toByte()
            }
        }
        val z = Deflater(6)
        z.setInput(raw)
        z.finish()
        val zout = ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        while (!z.finished()) zout.write(buf, 0, z.deflate(buf))
        z.end()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            out.write(ByteBuffer.allocate(4).putInt(data.size).array())
            val t = type.toByteArray(Charsets.US_ASCII)
            out.write(t); out.write(data)
            val crc = CRC32().apply { update(t); update(data) }
            out.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
        }
        chunk("IHDR", ByteBuffer.allocate(13).putInt(W).putInt(H).put(8).put(2).put(0).put(0).put(0).array())
        chunk("IDAT", zout.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }
}
