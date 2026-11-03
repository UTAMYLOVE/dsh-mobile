package app.dshmobile.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class ApkInstallerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer().apply { start() }
    private val updateDir = File(context.cacheDir, "update")
    private val bytes = ByteArray(300_000) { (it * 31).toByte() }
    private val goodHash = sha(bytes)
    private val installer = ApkInstaller(context, OkHttpClient(), listOf(server.url("/").toString()))

    @Before fun clearFileProviderCache() {
        // FileProvider caches its path strategy statically; every Robolectric test has a fresh cache dir.
        FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
            .get(null).let { @Suppress("UNCHECKED_CAST") (it as MutableMap<String, Any>).clear() }
    }

    @After fun tearDown() {
        server.shutdown()
        updateDir.deleteRecursively()
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun info(code: Int = 3, hash: String = goodHash, url: String = server.url("/dsh-mobile.apk").toString()) =
        ReleaseInfo(code, "0.3.0", url, hash)

    private fun serve(body: ByteArray = bytes) = server.enqueue(MockResponse().setBody(Buffer().write(body)))

    private fun download(i: ReleaseInfo = info()): File? = runBlocking { installer.download(i) }

    @Test fun aMatchingHashGivesTheApkAndNoLeftoverPart() {
        serve()
        val apk = download()!!
        assertEquals(File(updateDir, "dsh-mobile-3.apk"), apk)
        assertTrue(bytes.contentEquals(apk.readBytes()))
        assertEquals(listOf("dsh-mobile-3.apk"), updateDir.list()!!.toList())
    }

    @Test fun aMismatchingHashDeletesEverythingAndReturnsNull() {
        serve()
        assertNull(download(info(hash = "0".repeat(64))))
        assertEquals(emptyList<String>(), updateDir.list()?.toList().orEmpty())
    }

    @Test fun aTamperedDownloadIsNeverKept() {
        serve(bytes.copyOf().also { it[1000] = (it[1000] + 1).toByte() })
        assertNull(download())
        assertFalse(File(updateDir, "dsh-mobile-3.apk").exists())
        assertFalse(File(updateDir, "dsh-mobile-3.apk.part").exists())
    }

    @Test fun anUpperCaseExpectedHashStillMatches() {
        serve()
        assertNotNull(download(info(hash = goodHash.uppercase())))
    }

    @Test fun nonSuccessIsNullAndLeavesNothing() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(download())
        assertEquals(emptyList<String>(), updateDir.list()?.toList().orEmpty())
    }

    @Test fun aTruncatedBodyIsNull() {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)).setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        assertNull(download())
        assertEquals(emptyList<String>(), updateDir.list()?.toList().orEmpty())
    }

    @Test fun anUrlOutsideTheAllowListIsNeverFetched() {
        assertNull(download(info(url = "https://evil.example/dsh-mobile.apk")))
        assertEquals(0, server.requestCount)
    }

    @Test fun staleFilesFromOtherVersionsAreRemoved() {
        updateDir.mkdirs()
        File(updateDir, "dsh-mobile-2.apk").writeText("old")
        File(updateDir, "dsh-mobile-2.apk.part").writeText("old")
        serve()
        download()
        assertEquals(listOf("dsh-mobile-3.apk"), updateDir.list()!!.toList())
    }

    @Test fun anAlreadyVerifiedApkIsReusedWithoutDownloadingAgain() {
        serve()
        download()
        assertEquals(1, server.requestCount)
        assertNotNull(download())
        assertEquals(1, server.requestCount)
    }

    @Test fun aCorruptedLeftoverApkIsReplacedByAFreshDownload() {
        updateDir.mkdirs()
        File(updateDir, "dsh-mobile-3.apk").writeText("corrupt")
        serve()
        val apk = download()!!
        assertTrue(bytes.contentEquals(apk.readBytes()))
        assertEquals(1, server.requestCount)
    }

    // ---- install ----

    @Test fun installOpensTheSystemInstallerThroughTheFileProvider() {
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        serve()
        val apk = download()!!
        val intent = installer.buildInstallIntent(apk)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals("content", intent.data!!.scheme)
        assertEquals("${context.packageName}.export", intent.data!!.authority)
        assertTrue(intent.data!!.path!!.contains("update/dsh-mobile-3.apk"))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        installer.install(apk)
        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun withoutTheUnknownSourcesGrantTheSettingsPageOpensInstead() {
        shadowOf(context.packageManager).setCanRequestPackageInstalls(false)
        serve()
        val apk = download()!!
        val intent = installer.buildInstallIntent(apk)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals(Uri.parse("package:${context.packageName}"), intent.data)
        installer.install(apk)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, shadowOf(context as android.app.Application).nextStartedActivity.action)
    }

    @Test fun onlyFilesInTheUpdateDirectoryCanBeInstalled() {
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        val other = File(context.cacheDir, "elsewhere.apk").also { it.writeText("x") }
        try {
            installer.install(other)
            assertTrue("must have thrown", false)
        } catch (_: IllegalArgumentException) {
            // refused: the FileProvider does not even expose that path
        } finally {
            other.delete()
        }
    }
}
