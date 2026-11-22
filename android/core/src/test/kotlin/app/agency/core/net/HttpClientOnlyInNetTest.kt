package app.agency.core.net

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only core/net builds HTTP clients or opens connections. Scans every Kotlin and Java source of :core and :app. The legacy
 * files below predate the agency (the dsh gateway, its client and its app object) and go away with dsh (T06/T09); the list may
 * only shrink. Agency code (all of :core, and every `agency` package of :app) may not even name `OkHttpClient` outside core/net,
 * so a `.newBuilder()` on someone's client cannot open a second way out.
 */
class HttpClientOnlyInNetTest {
    private val pattern = Regex(
        """OkHttpClient\s*\(|OkHttpClient\.Builder\s*\(|HttpURLConnection|\bURL\(|""" +
            """\bHttpClient\s*\(|\bHttpClient\.new(?:HttpClient|Builder)\b|\bSocket\s*\(|\.toURL\(\)|openConnection\(|newWebSocket\(""",
    )
    private val okHttpName = Regex("""\bOkHttpClient\b""")
    private val legacy = setOf("app/dshmobile/DshMobileApp.kt", "app/dshmobile/gateway/LocalGateway.kt", "app/dshmobile/runtime/DshClient.kt")

    private fun root(): File = listOf(File(".."), File("."), File("android")).map { it.canonicalFile }
        .first { File(it, "core/src/main").isDirectory && File(it, "app/src/main").isDirectory }

    @Test fun noHttpClientOutsideCoreNet() {
        val r = root()
        val sources = listOf("core/src/main/kotlin", "app/src/main/kotlin", "app/src/main/java").map { File(r, it) }.filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.map { dir to it } }
        assertTrue(sources.size > 50)
        val offenders = sources.mapNotNull { (dir, f) ->
            val rel = f.relativeTo(dir).invariantSeparatorsPath
            if (rel.startsWith("app/agency/core/net/") || rel in legacy) return@mapNotNull null
            f.readLines().withIndex().firstOrNull { (_, l) -> pattern.containsMatchIn(l) }?.let { "$rel:${it.index + 1}" }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test fun agencyCodeNeverNamesAnOkHttpClientOutsideCoreNet() {
        val r = root()
        val core = File(r, "core/src/main/kotlin")
        val app = File(r, "app/src/main/kotlin")
        val files = core.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { core to it } +
            app.walkTopDown().filter { it.isFile && it.extension == "kt" && "/agency/" in it.relativeTo(app).invariantSeparatorsPath }
                .map { app to it }
        val list = files.toList()
        assertTrue(list.any { (d, f) -> d == app && f.name == "CostMeter.kt" })
        val offenders = list.mapNotNull { (dir, f) ->
            val rel = f.relativeTo(dir).invariantSeparatorsPath
            if (rel.startsWith("app/agency/core/net/")) return@mapNotNull null
            f.readLines().withIndex().firstOrNull { (_, l) -> okHttpName.containsMatchIn(l) }?.let { "$rel:${it.index + 1}" }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test fun theLegacyFilesStillNeedTheirException() {
        // When a legacy file is deleted or stops building clients, drop it from the list.
        val app = File(root(), "app/src/main/kotlin")
        legacy.forEach { rel ->
            val f = File(app, rel)
            assertTrue("$rel is gone: remove it from the list", f.isFile && f.readLines().any { pattern.containsMatchIn(it) })
        }
    }

    @Test fun theScanCatchesEachForm() {
        listOf(
            "OkHttpClient()", "OkHttpClient.Builder()", "x as HttpURLConnection", "URL(\"https://a\")", "HttpClient(CIO)",
            "HttpClient.newHttpClient()", "HttpClient.newBuilder()", "Socket(\"a\", 443)", "uri.toURL()", "u.openConnection()",
            "client.newWebSocket(req, l)",
        ).forEach { assertTrue(it, pattern.containsMatchIn(it)) }
        assertTrue(okHttpName.containsMatchIn("val c: OkHttpClient = other.newBuilder().build()"))
        assertTrue(!pattern.containsMatchIn("val s: ServerSocketFactory"))
        assertTrue(!pattern.containsMatchIn("openWebSocket(path)"))
        assertTrue(!pattern.containsMatchIn("val base: HttpUrl"))
        assertTrue(!pattern.containsMatchIn("toHttpUrl()"))
        assertTrue(!pattern.containsMatchIn("/** the launch URL (with its token) */"))
    }
}
