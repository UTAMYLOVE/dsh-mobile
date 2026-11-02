package app.dshmobile.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class ApkUpdateCheckerTest {
    private val hash = "a".repeat(32) + "0123456789abcdef".repeat(2)
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var releaseResponse: () -> MockResponse = { MockResponse().setResponseCode(404) }
    private var shaResponse: () -> MockResponse = { MockResponse().setBody("$hash  dsh-mobile.apk\n") }

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                return when {
                    request.path == "/repos/owner/repo/releases/latest" -> releaseResponse()
                    request.path!!.endsWith(".sha256") -> shaResponse()
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        start()
    }
    private val base = server.url("/")
    private val allowed = listOf(base.toString())

    @After fun tearDown() = server.shutdown()

    private fun checker(current: Int = 1, allowedPrefixes: List<String> = allowed) =
        ApkUpdateChecker(OkHttpClient(), "owner/repo", current, base, allowedPrefixes)

    private fun asset(name: String, url: String = "${base}download/$name") = """{"name":"$name","browser_download_url":"$url"}"""

    private fun release(tag: String, vararg assets: String) =
        """{"tag_name":"$tag","assets":[${assets.joinToString(",")}]}"""

    private fun serve(tag: String, vararg assets: String = arrayOf(asset("dsh-mobile.apk"), asset("dsh-mobile.apk.sha256"))) {
        releaseResponse = { MockResponse().setBody(release(tag, *assets)) }
    }

    private fun latest(current: Int = 1, allowedPrefixes: List<String> = allowed) = runBlocking { checker(current, allowedPrefixes).latest() }

    @Test fun aNewerReleaseIsReturnedWithUrlAndHash() {
        serve("v0.2.0+2")
        assertEquals(ReleaseInfo(2, "0.2.0", "${base}download/dsh-mobile.apk", hash), latest(current = 1))
    }

    @Test fun theSha256FileMayBeUpperCaseAndTheHashIsLowerCased() {
        serve("v0.2.0+2")
        shaResponse = { MockResponse().setBody(hash.uppercase() + "  dsh-mobile.apk") }
        assertEquals(hash, latest()!!.sha256)
    }

    @Test fun theSameVersionCodeIsNotAnUpdate() {
        serve("v0.2.0+2")
        assertNull(latest(current = 2))
    }

    @Test fun anOlderVersionCodeIsNotAnUpdate() {
        serve("v0.1.0+1")
        assertNull(latest(current = 5))
    }

    @Test fun theVersionCodeDecidesNotTheVersionName() {
        serve("v0.0.1+9")
        assertEquals(9, latest(current = 8)!!.versionCode)
    }

    @Test fun badTagsAreRejected() {
        for (tag in listOf("v1.2", "1.2.3+4", "v1.2.3+x", "v1.2.3", "v1.2.3+4-rc1", "v1.2.3+4 ", " v1.2.3+4", "vv1.2.3+4", "v1.2.3+99999999999")) {
            serve(tag)
            assertNull(tag, latest(current = 0))
        }
    }

    @Test fun aMissingSha256AssetIsRejected() {
        serve("v0.2.0+2", asset("dsh-mobile.apk"))
        assertNull(latest())
    }

    @Test fun aSha256ThatBelongsToAnotherApkIsRejected() {
        serve("v0.2.0+2", asset("dsh-mobile.apk"), asset("other.apk.sha256"))
        assertNull(latest())
    }

    @Test fun aMissingApkIsRejected() {
        serve("v0.2.0+2", asset("dsh-mobile.apk.sha256"))
        assertNull(latest())
    }

    @Test fun twoApksAreAmbiguousAndRejected() {
        serve("v0.2.0+2", asset("a.apk"), asset("a.apk.sha256"), asset("b.apk"), asset("b.apk.sha256"))
        assertNull(latest())
    }

    @Test fun aSha256FileThatIsNot64HexIsRejected() {
        serve("v0.2.0+2")
        for (body in listOf("", "zz", "a".repeat(63), "a".repeat(65), "g".repeat(64), "<html>" + "a".repeat(64), "$hash".replaceRange(0, 1, "-"))) {
            shaResponse = { MockResponse().setBody(body) }
            assertNull(body, latest())
        }
    }

    @Test fun aBareHashWithoutAFileNameIsAccepted() {
        serve("v0.2.0+2")
        shaResponse = { MockResponse().setBody(hash) }
        assertEquals(hash, latest()!!.sha256)
    }

    @Test fun downloadUrlsOutsideTheAllowListAreRejectedAndNeverFetched() {
        serve("v0.2.0+2", asset("dsh-mobile.apk", "https://evil.example/dsh-mobile.apk"), asset("dsh-mobile.apk.sha256"))
        assertNull(latest())
        serve("v0.2.0+2", asset("dsh-mobile.apk"), asset("dsh-mobile.apk.sha256", "https://evil.example/x.sha256"))
        assertNull(latest())
        serve("v0.2.0+2", asset("dsh-mobile.apk", "http://github.com/o/r/dsh-mobile.apk"), asset("dsh-mobile.apk.sha256"))
        assertNull("plain http github is not on the list", latest(allowedPrefixes = ApkUpdateChecker.DEFAULT_ALLOWED_PREFIXES))
        assertTrue(requests.none { it.path!!.endsWith(".sha256") && it.requestUrl!!.host == "evil.example" })
    }

    @Test fun theDefaultAllowListIsGithubAndItsObjectHost() {
        assertEquals(
            listOf("https://github.com/", "https://objects.githubusercontent.com/"),
            ApkUpdateChecker.DEFAULT_ALLOWED_PREFIXES,
        )
        // A look-alike host must not pass a plain prefix check.
        assertTrue(!ApkUpdateChecker.isAllowed("https://github.com.evil.example/x.apk", ApkUpdateChecker.DEFAULT_ALLOWED_PREFIXES))
        assertTrue(ApkUpdateChecker.isAllowed("https://github.com/o/r/releases/download/v1.0.0+1/a.apk", ApkUpdateChecker.DEFAULT_ALLOWED_PREFIXES))
        assertTrue(ApkUpdateChecker.isAllowed("https://objects.githubusercontent.com/x", ApkUpdateChecker.DEFAULT_ALLOWED_PREFIXES))
    }

    @Test fun nonSuccessStatusesAreNull() {
        for (code in listOf(403, 404, 429, 500)) {
            releaseResponse = { MockResponse().setResponseCode(code) }
            assertNull("release $code", latest())
        }
        serve("v0.2.0+2")
        shaResponse = { MockResponse().setResponseCode(404) }
        assertNull(latest())
    }

    @Test fun malformedJsonIsNull() {
        for (body in listOf("", "not json", "[]", """{"tag_name":5}""", """{"tag_name":"v1.0.0+2"}""", """{"tag_name":"v1.0.0+2","assets":"x"}""")) {
            releaseResponse = { MockResponse().setBody(body) }
            assertNull(body, latest())
        }
    }

    @Test fun theRequestAsksForTheGithubJsonTypeAndSendsNoCredentials() {
        serve("v0.2.0+2")
        latest()
        val release = requests.first { it.path == "/repos/owner/repo/releases/latest" }
        assertEquals("GET", release.method)
        assertEquals("application/vnd.github+json", release.getHeader("Accept"))
        for (r in requests) {
            assertEquals(null, r.getHeader("Authorization"))
            assertEquals(null, r.getHeader("Cookie"))
        }
    }

    @Test fun anOlderReleaseDoesNotFetchTheHashFile() {
        serve("v0.1.0+1")
        latest(current = 1)
        assertTrue(requests.none { it.path!!.endsWith(".sha256") })
    }

    @Test fun anOversizedHashFileIsRejected() {
        serve("v0.2.0+2")
        shaResponse = { MockResponse().setBody("$hash  " + "x".repeat(100_000)) }
        assertNull(latest())
    }

    @Test fun aNetworkErrorSurfacesAsAnException() {
        server.shutdown()
        var thrown: Throwable? = null
        try {
            latest()
        } catch (e: IOException) {
            thrown = e
        }
        assertTrue(thrown != null)
    }
}
