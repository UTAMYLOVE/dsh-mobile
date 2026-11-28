package app.dshmobile.platform.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A photo's bytes in memory, never a file. [clean] decodes, turns upright, downsizes to at most [MAX_PX] on the long side and
 * encodes a fresh JPEG, so nothing of the original's metadata (location, device, capture time) survives: this is the only form
 * a photo may reach a model in. [takenAt] reads the capture time first, on the phone, for the meal slot (it never leaves).
 */
object PhotoBytes {
    /** The longest side a photo sent to a model may have. */
    const val MAX_PX = 1568
    const val QUALITY = 85
    const val MIME = "image/jpeg"

    /**
     * The cleaned JPEG, or null when [bytes] cannot be decoded. [rotation] is the camera's own rotation of a frame (CameraX);
     * without it the EXIF orientation of a picked or pasted photo is used.
     */
    fun clean(bytes: ByteArray, rotation: Int? = null, maxPx: Int = MAX_PX): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        if (longSide <= 0) return null
        var sample = 1
        while (longSide / (sample * 2) >= maxPx) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val turn = (rotation ?: exifRotation(bytes)).toFloat()
        val scale = minOf(1f, maxPx.toFloat() / maxOf(decoded.width, decoded.height))
        val m = Matrix().apply {
            postScale(scale, scale)
            postRotate(turn)
        }
        val upright = if (scale < 1f || turn != 0f) Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true) else decoded
        val out = ByteArrayOutputStream()
        check(upright.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) { "not compressed" }
        if (upright !== decoded) upright.recycle()
        decoded.recycle()
        out.toByteArray().takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun exifRotation(bytes: ByteArray): Int = runCatching {
        when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    private val EXIF_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /** The capture time (epoch ms) in [bytes]' EXIF (with its offset when written, else in [zone]), or null when it has none. */
    fun takenAt(bytes: ByteArray, zone: ZoneId): Long? = runCatching {
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME) ?: return null
        val local = LocalDateTime.parse(raw.trim(), EXIF_TIME)
        val offset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_OFFSET_TIME)
        if (offset != null) {
            OffsetDateTime.of(local, ZoneOffset.of(offset.trim())).toInstant().toEpochMilli()
        } else {
            local.atZone(zone).toInstant().toEpochMilli()
        }
    }.getOrNull()
}
