package app.agency.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Project rules that a compile cannot check: one Kotlin version in the catalog (R2), no model names in core's sources. */
class ProjectRulesTest {
    /** A model family named in prose; API model ids (`claude-haiku-5-5`) are lowercase and allowed. */
    private val modelName = Regex("""\b(Haiku|Sonnet|Opus|Fable)\b""")

    @Test fun everyKotlinPluginUsesTheOneKotlinVersion() {
        val catalog = listOf(File("../gradle/libs.versions.toml"), File("gradle/libs.versions.toml"), File("android/gradle/libs.versions.toml"))
            .first { it.isFile }.readLines()
        val kotlinPlugins = catalog.filter { it.contains("id = \"org.jetbrains.kotlin.") }
        assertEquals(3, kotlinPlugins.size) // compose, jvm, serialization
        assertEquals(emptyList<String>(), kotlinPlugins.filterNot { it.contains("version.ref = \"kotlin\"") })
        val versionKeys = catalog.takeWhile { it.trim() != "[libraries]" }.filter { Regex("""^\s*kotlin\w*\s*=""").containsMatchIn(it) }
        assertEquals(listOf("kotlin"), versionKeys.map { it.substringBefore("=").trim() })
    }

    @Test fun noCoreSourceNamesAModel() {
        val root = listOf(File("src"), File("core/src"), File("android/core/src")).first { it.isDirectory }
        val sources = root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "ProjectRulesTest.kt" }.toList()
        assertTrue(sources.size > 10)
        val offenders = sources.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line -> if (modelName.containsMatchIn(line)) "${f.name}:${i + 1}" else null }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test fun theModelScanAllowsApiIds() {
        assertTrue(modelName.containsMatchIn("// Cache reads are 0.10 on Sonnet 5.5"))
        assertTrue(!modelName.containsMatchIn("// Cache reads are 0.10 on `claude-sonnet-5-5`"))
        assertTrue(!modelName.containsMatchIn("\"opus\" to \"audio/opus\""))
    }
}
