package app.agency.core.vision

import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * A photo fit to leave the phone for the image-capable model (T15b). Only [of] makes one, and it accepts nothing but a JPEG
 * whose long side is at most [MAX_SIDE] px; every metadata segment (EXIF with its location and device, XMP, ICC, IPTC,
 * comments, any APPn but a plain JFIF header) is cut out of the bytes it keeps. A recording, a PNG, or anything else is
 * refused, so a recording can never reach a model through here.
 *
 * The app downsizes and re-encodes first (a fresh JPEG from the decoded pixels carries no EXIF); this is the second check,
 * in code that runs in tests.
 */
class VisionImage private constructor(private val data: ByteArray, val width: Int, val height: Int) {
    val bytes: ByteArray get() = data.copyOf()
    val size: Int get() = data.size

    fun base64(): String = Base64.getEncoder().encodeToString(data)

    fun dataUrl(): String = "data:$MIME;base64," + base64()

    override fun toString() = "VisionImage(${width}x$height, $size bytes)"

    companion object {
        const val MAX_SIDE = 1600
        const val MIME = "image/jpeg"
        const val MAX_BYTES = 4 * 1024 * 1024

        /** The cleaned photo, or null when [jpeg] is not a JPEG, cannot be read, is larger than [MAX_SIDE] or [MAX_BYTES]. */
        fun of(jpeg: ByteArray): VisionImage? {
            val clean = Jpeg.strip(jpeg) ?: return null
            val (w, h) = Jpeg.size(clean) ?: return null
            if (w <= 0 || h <= 0 || maxOf(w, h) > MAX_SIDE || clean.size > MAX_BYTES) return null
            if (Jpeg.hasMetadata(clean)) return null
            return VisionImage(clean, w, h)
        }
    }
}

/** The JPEG marker walk behind [VisionImage]: pure bytes, no image library. */
object Jpeg {
    private const val SOI = 0xD8
    private const val EOI = 0xD9
    private const val SOS = 0xDA
    private const val APP0 = 0xE0
    private const val COM = 0xFE

    private class Segment(val marker: Int, val start: Int, val end: Int)

    /** The segments before the scan, or null when [b] is not a well-formed JPEG header. The last one is SOS (its data follows). */
    private fun segments(b: ByteArray): List<Segment>? {
        if (b.size < 4 || (b[0].toInt() and 0xFF) != 0xFF || (b[1].toInt() and 0xFF) != SOI) return null
        val out = mutableListOf<Segment>()
        var i = 2
        while (i + 4 <= b.size) {
            if ((b[i].toInt() and 0xFF) != 0xFF) return null
            var m = b[i + 1].toInt() and 0xFF
            // Fill bytes.
            while (m == 0xFF && i + 2 < b.size) { i++; m = b[i + 1].toInt() and 0xFF }
            if (m == SOI || m == EOI || m in 0xD0..0xD7 || m == 0x01) return null
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (len < 2 || i + 2 + len > b.size) return null
            out += Segment(m, i, i + 2 + len)
            if (m == SOS) return out
            i += 2 + len
        }
        return null
    }

    /** A plain JFIF header is the one APPn kept. */
    private fun jfif(b: ByteArray, s: Segment): Boolean =
        s.marker == APP0 && s.end - s.start >= 9 && String(b, s.start + 4, 5, Charsets.ISO_8859_1) == "JFIF\u0000"

    private fun metadata(b: ByteArray, s: Segment): Boolean = s.marker == COM || (s.marker in APP0..0xEF && !jfif(b, s))

    /** [b] without its metadata segments (the scan and everything after it unchanged), or null when it is not a JPEG. */
    fun strip(b: ByteArray): ByteArray? {
        val segs = segments(b) ?: return null
        val out = ByteArrayOutputStream(b.size)
        out.write(b, 0, 2)
        segs.forEach { s -> if (!metadata(b, s)) out.write(b, s.start, s.end - s.start) }
        val scan = segs.last().end
        out.write(b, scan, b.size - scan)
        return out.toByteArray()
    }

    /** Whether any metadata segment is left before the scan. */
    fun hasMetadata(b: ByteArray): Boolean = segments(b)?.any { metadata(b, it) } ?: true

    /** Width and height from the frame header, or null. */
    fun size(b: ByteArray): Pair<Int, Int>? {
        val sof = segments(b)?.firstOrNull { it.marker in 0xC0..0xCF && it.marker != 0xC4 && it.marker != 0xC8 && it.marker != 0xCC } ?: return null
        if (sof.end - sof.start < 9) return null
        val h = ((b[sof.start + 5].toInt() and 0xFF) shl 8) or (b[sof.start + 6].toInt() and 0xFF)
        val w = ((b[sof.start + 7].toInt() and 0xFF) shl 8) or (b[sof.start + 8].toInt() and 0xFF)
        return w to h
    }
}
