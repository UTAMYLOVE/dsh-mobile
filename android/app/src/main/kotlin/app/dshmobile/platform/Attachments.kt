package app.dshmobile.platform

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import app.agency.core.agent.Attachment
import app.dshmobile.R
import app.dshmobile.platform.media.CameraShot
import app.dshmobile.platform.media.MediaVault
import app.dshmobile.platform.media.PhotoBytes
import app.dshmobile.secure.DataUnavailableException
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.UUID

/** What turning a picked or pasted thing into an [Attachment] gave: the attachment, or a reason in his language. */
sealed interface AttachResult {
    class Ok(val attachment: Attachment) : AttachResult

    /** [reason] is Traditional Chinese, ready to show. */
    class Refused(val reason: String) : AttachResult
}

/**
 * Turns content URIs (photo picker, document picker, clipboard, keyboard image insertion) and camera frames into
 * [Attachment]s. No composable and no Activity: the UI agent registers [AttachPickers]' contracts and passes the URIs here.
 * All functions read files and encrypt: call them off the main thread.
 *
 * - A photo: the original is sealed in the [vault] (encrypted, addressed by id); the bytes for a model are a freshly encoded
 *   JPEG, at most [PhotoBytes.MAX_PX] on the long side, with no metadata ([PhotoBytes.clean]).
 * - A file: plain text, CSV, JSON or Markdown only, at most [MAX_FILE_BYTES], decoded as UTF-8 (a bad byte becomes U+FFFD).
 *   Anything else is refused.
 */
class Attachments(
    private val context: Context,
    private val vault: MediaVault,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** A picked photo ([fromGallery] true: it may be an old shot, 「補交」) or a pasted image ([fromGallery] false). */
    fun photo(uri: Uri, fromGallery: Boolean = true): AttachResult {
        val original = try {
            context.contentResolver.openInputStream(uri)?.use { readAtMost(it, MAX_PHOTO_BYTES) }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } ?: return refused(R.string.attach_refuse_unreadable)
        if (original.size > MAX_PHOTO_BYTES) return refused(R.string.attach_refuse_photo_size)
        return photoBytes(original, rotation = null, fromGallery = fromGallery)
    }

    /** A frame from the in-app camera or the system camera ([rotation] null for the latter: its EXIF is used). */
    fun fromCamera(shot: CameraShot): AttachResult = photoBytes(shot.bytes, shot.rotation, fromGallery = false)

    fun fromCamera(bytes: ByteArray): AttachResult = photoBytes(bytes, rotation = null, fromGallery = false)

    /** Seals [original] in the vault and cleans a copy for the model. */
    fun photoBytes(original: ByteArray, rotation: Int?, fromGallery: Boolean): AttachResult {
        val jpeg = PhotoBytes.clean(original, rotation) ?: return refused(R.string.attach_refuse_photo)
        val id = newId()
        try {
            vault.put(id, original)
        } catch (_: DataUnavailableException) {
            return refused(R.string.attach_refuse_vault)
        } catch (_: IOException) {
            return refused(R.string.attach_refuse_vault)
        }
        return AttachResult.Ok(Attachment.Photo(id, jpeg, fromGallery))
    }

    /** A picked document or a pasted file. */
    fun file(uri: Uri): AttachResult {
        val resolver = context.contentResolver
        val (name, size) = describe(uri)
        val mime = runCatching { resolver.getType(uri) }.getOrNull()?.lowercase(Locale.ROOT)
        if (!typeAllowed(mime, name)) return refused(R.string.attach_refuse_type)
        if (size != null && size > MAX_FILE_BYTES) return refused(R.string.attach_refuse_size)
        val bytes = try {
            resolver.openInputStream(uri)?.use { readAtMost(it, MAX_FILE_BYTES) }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } ?: return refused(R.string.attach_refuse_unreadable)
        if (bytes.size > MAX_FILE_BYTES) return refused(R.string.attach_refuse_size)
        if (bytes.isEmpty()) return refused(R.string.attach_refuse_empty)
        if (bytes.any { it == 0.toByte() }) return refused(R.string.attach_refuse_type) // binary content under a text name
        val text = String(bytes, Charsets.UTF_8).removePrefix("﻿")
        return AttachResult.Ok(Attachment.File(name ?: context.getString(R.string.attach_file_default_name), text))
    }

    /** Something pasted or inserted by a keyboard: an image becomes a photo (not from the gallery), anything else a file. */
    fun pasted(uri: Uri): AttachResult {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()?.lowercase(Locale.ROOT)
        return if (mime != null && mime.startsWith("image/")) photo(uri, fromGallery = false) else file(uri)
    }

    private fun describe(uri: Uri): Pair<String?, Long?> = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null to null
            val name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }
            val size = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
            name to size
        }
    }.getOrNull() ?: (null to null)

    private fun refused(res: Int) = AttachResult.Refused(context.getString(res))

    companion object {
        /** A file that goes with a message is read whole into the prompt, so it stays small. */
        const val MAX_FILE_BYTES = 200 * 1024

        /** The original of a photo is kept whole, but a runaway file must not fill memory. */
        const val MAX_PHOTO_BYTES = 40 * 1024 * 1024

        val FILE_MIME_TYPES = setOf(
            "text/plain", "text/csv", "text/comma-separated-values", "application/csv", "application/json", "text/json",
            "text/markdown", "text/x-markdown",
        )
        private val FILE_EXTENSIONS = setOf("txt", "csv", "json", "md", "markdown")
        private val UNSURE_TYPES = setOf("application/octet-stream", "binary/octet-stream", "application/x-unknown")

        /**
         * Allowed when the system says one of the text types, or says nothing useful (many providers report Markdown as
         * "octet-stream") and the file name ends in a text extension.
         */
        fun typeAllowed(mime: String?, name: String?): Boolean {
            if (mime != null && mime in FILE_MIME_TYPES) return true
            if (mime != null && mime !in UNSURE_TYPES) return false
            val ext = name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
            return ext in FILE_EXTENSIONS
        }

        /** Reads at most [limit] + 1 bytes: a result longer than [limit] means "too big", without reading the rest. */
        private fun readAtMost(input: InputStream, limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (total <= limit) {
                val n = input.read(buf, 0, minOf(buf.size, limit + 1 - total))
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
            }
            return out.toByteArray()
        }
    }
}

/**
 * The `ActivityResultContract`s the UI registers (`rememberLauncherForActivityResult`), and what to launch them with. The result
 * URIs go to [Attachments.photo] / [Attachments.file].
 */
object AttachPickers {
    /** 相簿: the system photo picker, several at once (needs no storage permission). */
    fun photos(maxItems: Int = 9): ActivityResultContract<PickVisualMediaRequest, List<@JvmSuppressWildcards Uri>> =
        ActivityResultContracts.PickMultipleVisualMedia(maxItems)

    /** Launch [photos] with this. */
    val photosRequest: PickVisualMediaRequest = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    /** 檔案: the document picker. Launch it with [documentTypes]; the result is the chosen URI or null. */
    fun document(): ActivityResultContract<Array<String>, Uri?> = ActivityResultContracts.OpenDocument()

    /** The MIME types to offer: text, CSV, JSON, Markdown (and the generic type, so a provider that mislabels Markdown shows it). */
    val documentTypes: Array<String> = (Attachments.FILE_MIME_TYPES + "application/octet-stream").toTypedArray()
}
