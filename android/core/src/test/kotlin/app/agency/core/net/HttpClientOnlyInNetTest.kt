package app.agency.core.net

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only core/net builds HTTP clients or opens connections. Scans every Kotlin and Java source of :core and :app. No code
 * outside core/net may even name `OkHttpClient`, so a `.newBuilder()` on someone's client cannot open a second way out.
 */
class HttpClientOnlyInNetTest {
    private val pattern = Regex(
        """OkHttpClient\s*\(|OkHttpClient\.Builder\s*\(|HttpURLConnection|\bURL\(|""" +
            """\bHttpClient\s*\(|\bHttpClient\.new(?:HttpClient|Builder)\b|\bSocket\s*\(|\.toURL\(\)|openConnection\(|newWebSocket\(""",
    )
    private val okHttpName = Regex("""\bOkHttpClient\b""")
    /** Files allowed to build their own client: none since dsh's gateway, client and app wiring were deleted (T09). */
    private val legacy = emptySet<String>()

    private fun root(): File = listOf(File(".."), File("."), File("android")).map { it.canonicalFile }
        .first { File(it, "core/src/main").isDirectory && File(it, "app/src/main").isDirectory }

    @Test fun noHttpClientOutsideCoreNet() {
        val r = root()
        val sources = listOf("core/src/main/kotlin", "app/src/main/kotlin", "app/src/main/java").map { File(r, it) }.filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.map { dir to it } }
        assertTrue(sources.size > 10)
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
            app.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { app to it }
        val list = files.toList()
        assertTrue(list.any { (d, _) -> d == app })
        val offenders = list.mapNotNull { (dir, f) ->
            val rel = f.relativeTo(dir).invariantSeparatorsPath
            if (rel.startsWith("app/agency/core/net/")) return@mapNotNull null
            f.readLines().withIndex().firstOrNull { (_, l) -> okHttpName.containsMatchIn(l) }?.let { "$rel:${it.index + 1}" }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test fun noLegacyFileIsExceptedAnyMore() {
        // dsh's LocalGateway, DshClient and the old app wiring built their own clients; they are gone (T09), and so is the list.
        val app = File(root(), "app/src/main/kotlin")
        listOf("app/dshmobile/gateway/LocalGateway.kt", "app/dshmobile/runtime/DshClient.kt").forEach { assertTrue(it, !File(app, it).exists()) }
        assertTrue(legacy.isEmpty())
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
