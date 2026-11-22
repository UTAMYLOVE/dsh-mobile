package app.agency.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The brain must run on a computer too: nothing under core/src/main may import Android (or AndroidX), and core's build file
 * may declare no Android or AndroidX dependency (a pure-JVM `androidx.*` artifact would compile, so only this catches it).
 */
class CoreHasNoAndroidImportsTest {
    private val forbidden = Regex("""^\s*import\s+(android|androidx|com\.android|dalvik)\.""")
    private val androidDependency = Regex("""libs\.(android|androidx)\b|["'](androidx?\.|com\.android)""")

    @Test fun noSourceUnderCoreMainImportsAndroid() {
        val sources = mainSources()
        assertTrue("found no sources under core/src/main", sources.isNotEmpty())
        val offenders = sources.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line -> if (forbidden.containsMatchIn(line)) "${f.name}:${i + 1}: ${line.trim()}" else null }
        }
        assertEquals("Android imports in :core", emptyList<String>(), offenders)
    }

    @Test fun theScanCatchesAnAndroidImport() {
        assertTrue(forbidden.containsMatchIn("import android.content.Context"))
        assertTrue(forbidden.containsMatchIn("import androidx.room.Entity"))
        assertTrue(!forbidden.containsMatchIn("import app.agency.core.rules.CoachRules"))
    }

    @Test fun theCoreBuildDeclaresNoAndroidDependency() {
        val build = listOf(File("build.gradle.kts"), File("core/build.gradle.kts"), File("android/core/build.gradle.kts")).first { it.isFile }
        assertTrue("not core's build file: $build", build.readText().contains("java-test-fixtures"))
        assertEquals(emptyList<String>(), build.readLines().filter { androidDependency.containsMatchIn(it) })
    }

    @Test fun theBuildScanCatchesAnAndroidDependency() {
        assertTrue(androidDependency.containsMatchIn("    implementation(libs.androidx.annotation)"))
        assertTrue(androidDependency.containsMatchIn("    implementation(libs.android.tools.common)"))
        assertTrue(androidDependency.containsMatchIn("""    implementation("androidx.annotation:annotation:1.9.1")"""))
        assertTrue(androidDependency.containsMatchIn("""    compileOnly("com.android.tools:common:31.0.0")"""))
        assertTrue(!androidDependency.containsMatchIn("    api(libs.kotlinx.serialization.json)"))
    }

    companion object {
        /** Gradle runs unit tests with the module directory as the working directory. */
        fun mainSources(): List<File> {
            val dir = listOf(File("src/main"), File("core/src/main"), File("android/core/src/main")).firstOrNull { it.isDirectory }
                ?: error("core/src/main not found from ${File(".").absolutePath}")
            return dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
        }
    }
}
