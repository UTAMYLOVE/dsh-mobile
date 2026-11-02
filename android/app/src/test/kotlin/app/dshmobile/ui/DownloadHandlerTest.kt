package app.dshmobile.ui

import app.dshmobile.ui.DownloadHandler.Result
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class DownloadHandlerTest {
    private lateinit var server: MockWebServer
    private lateinit var exportDir: File
    private var cookie: String? = "dsh-auth-x=1"
    private var requestedCookieFor: String? = null

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        exportDir = File(Files.createTempDirectory("dl").toFile(), "export")
    }

    @After fun tearDown() {
        server.shutdown()
        exportDir.parentFile!!.deleteRecursively()
    }

    private fun handler(port: Int = server.port) = DownloadHandler(
        exportDir = { exportDir },
        http = OkHttpClient(),
        dshPort = { port },
        cookies = { requestedCookieFor = it; cookie },
    )

    private fun url(path: String) = "http://127.0.0.1:${server.port}$path"

    // --- sanitizeFileName ---

    @Test fun sanitizeKeepsOrdinaryNames() {
        assertEquals("report.pdf", DownloadHandler.sanitizeFileName("report.pdf"))
        assertEquals("日報 2026.md", DownloadHandler.sanitizeFileName("日報 2026.md"))
    }

    @Test fun sanitizeRemovesPathSeparatorsAndControlCharacters() {
        assertEquals("..evil", DownloadHandler.sanitizeFileName("../evil"))
        assertEquals("abc", DownloadHandler.sanitizeFileName("a/b\\c"))
        assertEquals("ab", DownloadHandler.sanitizeFileName("a\u0000b"))
        assertEquals("ab", DownloadHandler.sanitizeFileName("a\r\nb"))
        assertEquals("ab", DownloadHandler.sanitizeFileName("a\u007fb\u009f"))
    }

    @Test fun sanitizeFallsBackToDownload() {
        for (bad in listOf(null, "", "   ", "/", "\\\\", ".", "..", "\u0000", " . ")) {
            assertEquals("[$bad]", "download", DownloadHandler.sanitizeFileName(bad))
        }
    }

    @Test fun sanitizeLimitsLengthKeepingTheExtension() {
        val name = DownloadHandler.sanitizeFileName("a".repeat(500) + ".tar.gz")
        assertTrue(name.length <= 120)
        assertTrue(name.endsWith(".gz"))
    }

    // --- source check ---

    @Test fun onlyTheDshOriginIsDownloadable() = runBlocking {
        val h = handler()
        for (u in listOf(
            "https://example.com/a.zip", "http://example.com/a.zip", "blob:http://127.0.0.1:${server.port}/x", "file:///etc/passwd",
            "http://127.0.0.1:${server.port + 1}/a", "http://localhost:${server.port}/a", "data:text/plain,hi", "javascript:1",
            "http://127.0.0.1.evil.com:${server.port}/a",
        )) {
            assertEquals(u, Result.Unsupported, h.download(u, null, null))
        }
        assertEquals(0, server.requestCount)
        assertFalse(exportDir.exists() && exportDir.list()!!.isNotEmpty())
    }

    // --- download ---

    @Test fun downloadsWithTheWebViewCookieIntoTheExportDirectory() = runBlocking {
        server.enqueue(MockResponse().setBody("hello"))
        val r = handler().download(url("/files/a.txt"), null, "text/plain")
        r as Result.Saved
        assertEquals("a.txt", r.file.name)
        assertEquals(exportDir.canonicalFile, r.file.parentFile!!.canonicalFile)
        assertEquals("hello", r.file.readText())
        val req = server.takeRequest()
        assertEquals("dsh-auth-x=1", req.getHeader("Cookie"))
        assertEquals("/files/a.txt", req.path)
        assertEquals(url("/files/a.txt"), requestedCookieFor)
    }

    @Test fun noCookieMeansNoCookieHeader() = runBlocking {
        cookie = null
        server.enqueue(MockResponse().setBody("x"))
        handler().download(url("/a.bin"), null, null)
        assertNull(server.takeRequest().getHeader("Cookie"))
    }

    @Test fun contentDispositionNamesTheFile() = runBlocking {
        server.enqueue(MockResponse().setBody("x"))
        val r = handler().download(url("/dl?id=1"), "attachment; filename=\"notes.md\"", "text/markdown") as Result.Saved
        assertEquals("notes.md", r.file.name)
    }

    @Test fun aTraversalNameStaysInsideTheExportDirectory() = runBlocking {
        server.enqueue(MockResponse().setBody("x"))
        val r = handler().download(url("/dl"), "attachment; filename=\"../../evil.sh\"", null) as Result.Saved
        assertEquals(exportDir.canonicalFile, r.file.parentFile!!.canonicalFile)
        assertFalse(r.file.name.contains("/"))
    }

    @Test fun anErrorStatusIsAFailureAndLeavesNoFile() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val r = handler().download(url("/a.txt"), null, null)
        assertTrue(r is Result.Failed)
        assertTrue(exportDir.list().orEmpty().isEmpty())
    }

    @Test fun redirectsAreNotFollowed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://example.com/steal"))
        assertTrue(handler().download(url("/a.txt"), null, null) is Result.Failed)
        assertEquals(1, server.requestCount)
    }

    @Test fun anUnreachableServerIsAFailure() = runBlocking {
        val port = server.port
        server.shutdown()
        assertTrue(handler(port).download("http://127.0.0.1:$port/a.txt", null, null) is Result.Failed)
    }

    @Test fun aFailureDoesNotLeakTheUrl() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val r = handler().download(url("/secret-path?token=abc"), null, null) as Result.Failed
        assertFalse(r.reason.contains("secret-path"))
        assertFalse(r.reason.contains("token"))
    }

    @Test fun aSecondDownloadWithTheSameNameReplacesTheFirst() = runBlocking {
        server.enqueue(MockResponse().setBody("one"))
        server.enqueue(MockResponse().setBody("two"))
        handler().download(url("/a.txt"), null, null)
        val r = handler().download(url("/a.txt"), null, null) as Result.Saved
        assertEquals("two", r.file.readText())
        assertEquals(1, exportDir.list()!!.size)
    }

    @Test fun anOversizedDownloadIsRefusedAndRemoved() = runBlocking {
        server.enqueue(MockResponse().setBody("0123456789"))
        val small = DownloadHandler(exportDir = { exportDir }, http = OkHttpClient(), dshPort = { server.port }, cookies = { null }, maxBytes = 5)
        assertTrue(small.download(url("/big.bin"), null, null) is Result.Failed)
        assertTrue(exportDir.list().orEmpty().isEmpty())
    }
}
