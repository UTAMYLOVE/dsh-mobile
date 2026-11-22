package app.agency.core.net

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only core/net builds HTTP clients or opens connections. Scans every Kotlin and Java source of :core and :app. The two legacy
 * files below predate the agency (the dsh gateway and its app object) and go away with dsh (T06/T09); the list may only shrink.
 */
class HttpClientOnlyInNetTest {
    private val pattern = Regex("""OkHttpClient\s*\(|OkHttpClient\.Builder\s*\(|HttpURLConnection|\bURL\(""")
    private val legacy = setOf("app/dshmobile/DshMobileApp.kt", "app/dshmobile/gateway/LocalGateway.kt")

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

    @Test fun theLegacyFilesStillNeedTheirException() {
        // When a legacy file is deleted or stops building clients, drop it from the list.
        val app = File(root(), "app/src/main/kotlin")
        legacy.forEach { rel ->
            val f = File(app, rel)
            assertTrue("$rel is gone: remove it from the list", f.isFile && f.readLines().any { pattern.containsMatchIn(it) })
        }
    }

    @Test fun theScanCatchesEachForm() {
        listOf("OkHttpClient()", "OkHttpClient.Builder()", "x as HttpURLConnection", "URL(\"https://a\")").forEach {
            assertTrue(it, pattern.containsMatchIn(it))
        }
        assertTrue(!pattern.containsMatchIn("val base: HttpUrl"))
        assertTrue(!pattern.containsMatchIn("toHttpUrl()"))
        assertTrue(!pattern.containsMatchIn("/** the launch URL (with its token) */"))
    }
}
