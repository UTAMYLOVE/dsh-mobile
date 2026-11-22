package app.agency.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The brain must run on a computer too: nothing under core/src/main may import Android (or AndroidX). */
class CoreHasNoAndroidImportsTest {
    private val forbidden = Regex("""^\s*import\s+(android|androidx|com\.android|dalvik)\.""")

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

    companion object {
        /** Gradle runs unit tests with the module directory as the working directory. */
        fun mainSources(): List<File> {
            val dir = listOf(File("src/main"), File("core/src/main"), File("android/core/src/main")).firstOrNull { it.isDirectory }
                ?: error("core/src/main not found from ${File(".").absolutePath}")
            return dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
        }
    }
}
