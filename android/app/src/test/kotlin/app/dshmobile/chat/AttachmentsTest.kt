package app.dshmobile.chat

import android.net.Uri
import app.dshmobile.remote.DshRpcException
import app.dshmobile.remote.PromptPart
import app.dshmobile.remote.StreamSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.InputStream
import java.util.Base64

private class UploadRemote : ChatRemote {
    val prompts = mutableListOf<List<PromptPart>>()
    val uploads = mutableListOf<Pair<String, Int>>()
    var promptError: Exception? = null
    var uploadError: Throwable? = null
    /** Throws on the n-th upload (1-based). */
    var failUploadAt: Int? = null
    override fun follow(sessionId: String): Flow<StreamSignal> = Channel<StreamSignal>().receiveAsFlow()
    override suspend fun prompt(sessionId: String, requestId: String, parts: List<PromptPart>) {
        promptError?.let { throw it }
        prompts += parts
    }
    override suspend fun cancel(sessionId: String) {}
    override suspend fun page(sessionId: String, beforeSeq: Long, maxMessages: Int): JsonElement = JsonNull
    override suspend fun selectModel(sessionId: String, provider: String, model: String) {}
    override suspend fun upload(sessionId: String, name: String, bytes: ByteArray): String {
        uploadError?.let { throw it }
        uploads += name to bytes.size
        if (failUploadAt == uploads.size) throw IOException("boom")
        return "receipt-${uploads.size}"
    }
}

/** A stream of [n] zero bytes that never holds them. */
private fun zeros(n: Long): InputStream = object : InputStream() {
    var left = n
    override fun read(): Int = if (left-- > 0) 0 else -1
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val k = minOf(len.toLong(), left).toInt()
        left -= k
        return k
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentsTest {
    private val mb = 1024L * 1024
    private val sizes = mutableMapOf<Uri, Long>()
    private val names = mutableMapOf<Uri, String>()
    private val mimes = mutableMapOf<Uri, String>()
    /** What describe() claims, when it should lie. */
    private val claimed = mutableMapOf<Uri, Long>()
    private val unreadable = mutableSetOf<Uri>()
    private val throwing = mutableSetOf<Uri>()

    private fun uri(name: String, mime: String, size: Long): Uri =
        Uri.parse("content://test/$name").also { sizes[it] = size; names[it] = name; mimes[it] = mime }

    private val loader = AttachmentLoader(
        open = { u -> if (u in throwing) throw SecurityException("no") else if (u in unreadable) null else zeros(sizes.getValue(u)) },
        describe = { u -> names[u] to (claimed[u] ?: sizes[u]) },
        mimeOf = { u -> mimes[u] },
    )

    private fun TestScope.store(remote: UploadRemote): ConversationStore {
        var n = 0
        return ConversationStore(remote, "s1", CoroutineScope(StandardTestDispatcher(testScheduler)), { "r${++n}" }, StandardTestDispatcher(testScheduler))
    }

    private fun attach(u: Uri) = (loader.inspect(u) as LoadResult.Ok).attachment

    @Test fun aSmallPngBecomesAnImagePart() = runTest {
        val remote = UploadRemote()
        val a = attach(uri("a.png", "image/png", 1024))
        assertTrue(a.isImage)
        val parts = AttachmentSender(remote, loader).toParts("s1", "hi", listOf(a))
        assertEquals(PromptPart.Text("hi"), parts[0])
        val image = parts[1] as PromptPart.Image
        assertEquals("image/png", image.mediaType)
        assertEquals(1024, Base64.getDecoder().decode(image.base64).size)
        assertTrue(remote.uploads.isEmpty())
    }

    @Test fun aPdfIsUploadedAndBecomesAFilePart() = runTest {
        val remote = UploadRemote()
        val a = attach(uri("r.pdf", "application/pdf", 2048))
        assertFalse(a.isImage)
        val parts = AttachmentSender(remote, loader).toParts("s1", "see", listOf(a))
        assertEquals(1, remote.uploads.size)
        assertEquals("r.pdf" to 2048, remote.uploads.single())
        assertEquals(PromptPart.File("receipt-1", "r.pdf"), parts[1])
    }

    @Test fun aSevenMegabyteJpegIsSentAsAFile() = runTest {
        val remote = UploadRemote()
        val a = attach(uri("big.jpg", "image/jpeg", 7 * mb))
        assertFalse(a.isImage)
        val parts = AttachmentSender(remote, loader).toParts("s1", "", listOf(a))
        assertEquals(listOf<PromptPart>(PromptPart.File("receipt-1", "big.jpg")), parts)
    }

    @Test fun overTwentyMegabytesIsTooLargeEvenIfTheProviderLies() {
        val u = uri("huge.zip", "application/zip", 21 * mb)
        claimed[u] = 1024
        assertEquals(LoadResult.TooLarge("huge.zip", 20), loader.inspect(u))
    }

    @Test fun anUnreadableUriIsReportedNotThrown() {
        val a = uri("a.png", "image/png", 10).also { unreadable += it }
        val b = uri("b.png", "image/png", 10).also { throwing += it }
        assertEquals(LoadResult.Unreadable("a.png"), loader.inspect(a))
        assertEquals(LoadResult.Unreadable("b.png"), loader.inspect(b))
    }

    @Test fun aFileThatGrewAfterPickingIsRefusedAtSend() = runTest {
        val remote = UploadRemote(); val store = store(remote)
        val u = uri("g.pdf", "application/pdf", mb)
        val a = attach(u)
        sizes[u] = 21 * mb
        val out = sendWithAttachments(store, AttachmentSender(remote, loader), "x", listOf(a))
        assertEquals(SendOutcome.AttachmentFailed("g.pdf", AttachmentReason.TOO_LARGE), out)
        assertTrue(remote.prompts.isEmpty()); assertTrue(remote.uploads.isEmpty())
        store.close()
    }

    @Test fun aFailedUploadNeverSendsThePrompt() = runTest {
        val remote = UploadRemote().apply { failUploadAt = 2 }; val store = store(remote)
        val list = listOf(attach(uri("a.pdf", "application/pdf", 10)), attach(uri("b.pdf", "application/pdf", 10)))
        val out = sendWithAttachments(store, AttachmentSender(remote, loader), "x", list)
        assertEquals(SendOutcome.AttachmentFailed("b.pdf", AttachmentReason.CONNECTION), out)
        assertEquals(0, remote.prompts.size)
        assertTrue(store.pending.value.isEmpty())
        store.close()
    }

    @Test fun aRefusedImageShowsTheDshCode() = runTest {
        val remote = UploadRemote().apply { promptError = DshRpcException("session/model-unavailable", "m") }; val store = store(remote)
        val a = attach(uri("a.png", "image/png", 10))
        val out = sendWithAttachments(store, AttachmentSender(remote, loader), "x", listOf(a))
        assertEquals(SendOutcome.Refused("session/model-unavailable"), out)
        assertTrue(store.pending.value.isEmpty())
        store.close()
    }

    @Test fun textComesFirstThenAttachmentsInPickOrder() = runTest {
        val remote = UploadRemote(); val store = store(remote)
        val list = listOf(attach(uri("1.png", "image/png", 5)), attach(uri("2.pdf", "application/pdf", 5)), attach(uri("3.gif", "image/gif", 5)))
        assertEquals(SendOutcome.Sent, sendWithAttachments(store, AttachmentSender(remote, loader), "hello", list))
        val parts = remote.prompts.single()
        assertEquals(listOf("text", "image", "file", "image"), parts.map { when (it) { is PromptPart.Text -> "text"; is PromptPart.Image -> "image"; is PromptPart.File -> "file" } })
        assertEquals(listOf("1.png"), listOf((parts[1] as PromptPart.Image).name))
        assertEquals(listOf("1.png", "2.pdf", "3.gif"), store.pending.value.single().attachments)
        store.close()
    }

    @Test fun aDshUploadErrorShowsItsCodeAndAnythingElseIsUnknown() = runTest {
        val remote = UploadRemote(); val store = store(remote)
        val a = attach(uri("a.pdf", "application/pdf", 10))
        remote.uploadError = DshRpcException("upload/denied", "m")
        assertEquals(SendOutcome.AttachmentFailed("a.pdf", "upload/denied"), sendWithAttachments(store, AttachmentSender(remote, loader), "x", listOf(a)))
        remote.uploadError = IllegalStateException("x")
        assertEquals(SendOutcome.AttachmentFailed("a.pdf", AttachmentReason.UNKNOWN), sendWithAttachments(store, AttachmentSender(remote, loader), "x", listOf(a)))
        store.close()
    }

    @Test fun runningOutOfMemoryInAnUploadSendsNothing() = runTest {
        val remote = UploadRemote().apply { uploadError = OutOfMemoryError() }; val store = store(remote)
        val a = attach(uri("a.pdf", "application/pdf", 10))
        assertEquals(SendOutcome.AttachmentFailed("a.pdf", AttachmentReason.OUT_OF_MEMORY), sendWithAttachments(store, AttachmentSender(remote, loader), "x", listOf(a)))
        assertTrue(remote.prompts.isEmpty()); assertTrue(store.pending.value.isEmpty())
        store.close()
    }

    @Test fun runningOutOfMemoryWhileReadingSendsNothing() = runTest {
        val remote = UploadRemote(); val store = store(remote)
        val u = uri("a.png", "image/png", 10)
        val a = attach(u)
        val oom = AttachmentLoader(open = { throw OutOfMemoryError() }, describe = { names[it] to null }, mimeOf = { mimes[it] })
        assertEquals(SendOutcome.AttachmentFailed("a.png", AttachmentReason.OUT_OF_MEMORY), sendWithAttachments(store, AttachmentSender(remote, oom), "x", listOf(a)))
        assertTrue(remote.prompts.isEmpty())
        store.close()
    }

    @Test fun readReturnsExactlyTheBytesThereAre() {
        val u = uri("a.bin", "application/octet-stream", 1000)
        val a = attach(u)
        assertEquals(1000, loader.read(a).size)
        sizes[u] = 400 // shrank
        assertEquals(400, loader.read(a).size)
        sizes[u] = 5000 // grew, still within the limit
        assertEquals(5000, loader.read(a).size)
        sizes[u] = 20 * mb // exactly the limit is fine
        assertEquals(20 * mb.toInt(), loader.read(attach(u)).size)
        sizes[u] = 20 * mb + 1
        try { loader.read(a); org.junit.Assert.fail() } catch (_: AttachmentTooLarge) {}
    }

    @Test fun pendingAttachmentHoldsNoBytes() {
        val fields = PendingAttachment::class.java.declaredFields.filterNot { it.isSynthetic || it.name == "\$stable" }
        assertTrue(fields.none { it.type == ByteArray::class.java || InputStream::class.java.isAssignableFrom(it.type) || it.type == String::class.java && it.name == "base64" })
        assertEquals(setOf("id", "uri", "name", "mime", "size"), fields.map { it.name }.toSet())
    }
}
