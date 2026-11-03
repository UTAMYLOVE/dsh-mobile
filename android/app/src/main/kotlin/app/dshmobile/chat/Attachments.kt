package app.dshmobile.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import app.dshmobile.remote.DshErrors
import app.dshmobile.remote.DshRpcException
import app.dshmobile.remote.PromptPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.UUID

/**
 * One picked attachment. It holds the picker's [uri] and what the picker said, never the bytes: those are read only when the
 * message is sent ([AttachmentLoader.read]). [size] was counted from the stream at pick time. An image of an accepted type up
 * to [AttachmentLoader.IMAGE_LIMIT] is sent inline ([isImage]); everything else goes through `fileUploads/upload`.
 */
class PendingAttachment(val id: String, val uri: Uri, val name: String, val mime: String, val size: Long) {
    val isImage: Boolean get() = mime in AttachmentLoader.IMAGE_TYPES && size <= AttachmentLoader.IMAGE_LIMIT
}

sealed interface LoadResult {
    data class Ok(val attachment: PendingAttachment) : LoadResult
    data class TooLarge(val name: String, val limitMb: Int) : LoadResult
    data class Unreadable(val name: String) : LoadResult
}

/** The file grew past [AttachmentLoader.FILE_LIMIT] between picking and sending. */
class AttachmentTooLarge : IOException("attachment too large")

/** The attachment could not be opened or read. */
class AttachmentUnreadable : IOException("attachment unreadable")

/**
 * Looks at and reads picked content. [open] opens a content URI (null when it cannot), [describe] gives the provider's display
 * name and size (the size is only a hint: it is never trusted), [mimeOf] the provider's type. No name, URI or content is logged.
 */
class AttachmentLoader(
    private val open: (Uri) -> InputStream?,
    private val describe: (Uri) -> Pair<String?, Long?>,
    private val mimeOf: (Uri) -> String?,
) {
    /**
     * At pick time: name and type, and the real size by counting the stream (it stops one byte past [FILE_LIMIT] and keeps
     * nothing). Never throws for a bad URI: that is [LoadResult.Unreadable].
     */
    fun inspect(uri: Uri): LoadResult {
        val name = safeName(uri)
        val size = try {
            open(uri)?.use { count(it) } ?: return LoadResult.Unreadable(name)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { // SecurityException, IOException, IllegalArgumentException, ...: the picker's grant is gone or the file is
            return LoadResult.Unreadable(name)
        }
        if (size > FILE_LIMIT) return LoadResult.TooLarge(name, FILE_LIMIT / MB)
        val mime = mimeOf(uri)?.lowercase()?.takeIf { it.isNotEmpty() } ?: OCTET
        return LoadResult.Ok(PendingAttachment(UUID.randomUUID().toString(), uri, name, mime, size))
    }

    /** At send time only: the bytes of [a] (blocking IO; call it off the main thread). */
    fun read(a: PendingAttachment): ByteArray {
        val stream = try {
            open(a.uri)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw AttachmentUnreadable()
        } ?: throw AttachmentUnreadable()
        try {
            stream.use {
                // One array of the size seen at pick time; it only grows if the file grew, and never past FILE_LIMIT.
                var buf = ByteArray(a.size.coerceIn(0, FILE_LIMIT.toLong()).toInt())
                var n = 0
                while (true) {
                    if (n == buf.size) {
                        val extra = it.read() // full: is there more?
                        if (extra < 0) break
                        if (n >= FILE_LIMIT) throw AttachmentTooLarge()
                        buf = buf.copyOf(minOf(FILE_LIMIT.toLong(), maxOf(n * 2L, BUFFER.toLong())).toInt())
                        buf[n++] = extra.toByte()
                        continue
                    }
                    val r = it.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                return if (n == buf.size) buf else buf.copyOf(n) // copied only when the file shrank
            }
        } catch (e: AttachmentTooLarge) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw AttachmentUnreadable()
        }
    }

    private fun safeName(uri: Uri): String {
        val provided = try { describe(uri).first } catch (_: Exception) { null }
        return provided?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME
    }

    /** Reads to the end or to [FILE_LIMIT] + 1 bytes, keeping nothing. */
    private fun count(stream: InputStream): Long {
        val buf = ByteArray(BUFFER)
        var total = 0L
        while (total <= FILE_LIMIT) {
            val n = stream.read(buf)
            if (n < 0) break
            total += n
        }
        return total
    }

    companion object {
        const val IMAGE_LIMIT = 5 * 1024 * 1024
        const val FILE_LIMIT = 20 * 1024 * 1024
        private const val MB = 1024 * 1024
        private const val BUFFER = 64 * 1024
        private const val OCTET = "application/octet-stream"
        private const val DEFAULT_NAME = "file"
        val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
    }
}

/** Why an attachment stopped a send (besides an upload's own error name). */
object AttachmentReason {
    const val TOO_LARGE = "too-large"
    const val UNREADABLE = "unreadable"
    /** The phone ran out of memory reading or encoding the file. */
    const val OUT_OF_MEMORY = "out-of-memory"
    /** An upload failed on the connection (an IOException). */
    const val CONNECTION = "connection"
    const val UNKNOWN = "unknown"
}

/** Thrown by [AttachmentSender.toParts]: [reason] is an [AttachmentReason] or a [DshErrors.safeCode] / exception class name. */
class AttachmentFailure(val name: String, val reason: String) : Exception("attachment failed: $reason")

/** Turns picked attachments into the content of one `session/prompt`. */
class AttachmentSender(private val remote: ChatRemote, private val loader: AttachmentLoader) {
    /**
     * [text] first (when it is not blank), then the attachments in pick order. Every attachment is read and, when it is not an
     * inline image, uploaded before this returns; the first failure throws [AttachmentFailure] and nothing is sent. Reads on
     * [Dispatchers.IO], one attachment at a time, so at most one file is in memory beside the finished parts.
     */
    suspend fun toParts(sessionId: String, text: String, attachments: List<PendingAttachment>): List<PromptPart> {
        val parts = ArrayList<PromptPart>(attachments.size + 1)
        if (text.isNotBlank()) parts += PromptPart.Text(text)
        for (a in attachments) {
            try {
                val bytes = withContext(Dispatchers.IO) { loader.read(a) }
                if (a.isImage && bytes.size <= AttachmentLoader.IMAGE_LIMIT) {
                    val encoded = withContext(Dispatchers.IO) { Base64.getEncoder().encodeToString(bytes) }
                    parts += PromptPart.Image(a.mime, encoded, a.name)
                } else {
                    parts += PromptPart.File(remote.upload(sessionId, a.name, bytes), a.name)
                }
            } catch (_: AttachmentTooLarge) {
                throw AttachmentFailure(a.name, AttachmentReason.TOO_LARGE)
            } catch (_: AttachmentUnreadable) {
                throw AttachmentFailure(a.name, AttachmentReason.UNREADABLE)
            } catch (_: OutOfMemoryError) {
                throw AttachmentFailure(a.name, AttachmentReason.OUT_OF_MEMORY)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw AttachmentFailure(a.name, uploadReason(e))
            }
        }
        return parts
    }

    /** A dsh code as is, [AttachmentReason.CONNECTION] for an IOException, else [AttachmentReason.UNKNOWN]; never a class name. */
    private fun uploadReason(e: Exception): String = when (e) {
        is DshRpcException -> DshErrors.safeCode(e.code)
        is IOException -> AttachmentReason.CONNECTION
        else -> AttachmentReason.UNKNOWN
    }
}

sealed interface SendOutcome {
    data object Sent : SendOutcome
    /** Reading or uploading [name] failed; nothing was sent. */
    data class AttachmentFailed(val name: String, val reason: String) : SendOutcome
    /** dsh refused `session/prompt` ([code] is [ConversationStore.sendError]). */
    data class Refused(val code: String) : SendOutcome
}

/**
 * The dock's only way to send: reads and uploads every attachment first, then one prompt. The caller clears the text and the
 * attachments only on [SendOutcome.Sent].
 */
suspend fun sendWithAttachments(
    store: ConversationStore,
    sender: AttachmentSender,
    text: String,
    attachments: List<PendingAttachment>,
): SendOutcome {
    val parts = try {
        sender.toParts(store.sessionId, text, attachments)
    } catch (e: AttachmentFailure) {
        return SendOutcome.AttachmentFailed(e.name, e.reason)
    }
    return if (store.send(parts, text, attachments.map { it.name })) SendOutcome.Sent
    else SendOutcome.Refused(store.sendError.value ?: DshErrors.REFUSED)
}

/**
 * A thumbnail of at most about [targetPx] on its long side: the bounds are read first and the bitmap is decoded subsampled, so a
 * full-size picture is never in memory. Null when it cannot be opened or is not a picture. Blocking IO.
 */
fun decodeThumbnail(open: () -> InputStream?, targetPx: Int): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    val longSide = maxOf(bounds.outWidth, bounds.outHeight)
    while (longSide / (sample * 2) >= targetPx) sample *= 2
    if (bounds.outWidth <= 0) null
    else open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
} catch (_: OutOfMemoryError) {
    null
}
