package app.dshmobile.export

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.dshmobile.log.ErrorLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
class ExporterTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var base: File
    private lateinit var workspace: File
    private lateinit var outside: File
    private lateinit var errorLog: ErrorLog
    private var now = System.currentTimeMillis()
    private lateinit var exporter: Exporter

    private val exportDir get() = File(app.cacheDir, "export")

    @Before fun setUp() {
        // FileProvider caches its path strategy statically; every Robolectric test has a fresh cache dir.
        androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
            .get(null).let { @Suppress("UNCHECKED_CAST") (it as MutableMap<String, Any>).clear() }
        base = Files.createTempDirectory("exp").toFile()
        workspace = File(base, "workspace").apply { mkdirs() }
        outside = File(base, "outside").apply { mkdirs() }
        File(outside, "secret.txt").writeText("secret")
        errorLog = ErrorLog(File(base, "files"))
        exporter = Exporter(app, workspace, errorLog) { now }
    }

    @After fun tearDown() {
        base.deleteRecursively()
        exportDir.deleteRecursively()
    }

    private fun write(rel: String, text: String = rel) {
        File(workspace, rel).apply { parentFile!!.mkdirs() }.writeText(text)
    }

    private fun entries(zip: File): Map<String, String> = ZipFile(zip).use { z ->
        z.entries().asSequence().associate { it.name to z.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
    }

    // --- workspaceZip (spec §7.1: any workspace file can be exported from the app) ---

    @Test fun workspaceZipHasEveryRegularFileWithRelativeNames() {
        write("MEMORY.md", "m")
        write("memory/2026-11-01.md", "d1")
        write("project/src/main.py", "print(1)")
        write(".hidden/config", "h")
        File(workspace, "empty-dir").mkdirs()

        val zip = exporter.workspaceZip()

        assertEquals(exportDir.canonicalFile, zip.parentFile!!.canonicalFile)
        assertTrue(zip.name, zip.name.matches(Regex("""dsh-workspace-\d{8}-\d{4}\.zip""")))
        assertEquals(
            mapOf(
                "MEMORY.md" to "m", "memory/2026-11-01.md" to "d1", "project/src/main.py" to "print(1)", ".hidden/config" to "h",
            ),
            entries(zip),
        )
    }

    @Test fun workspaceZipNeverFollowsASymlinkOutOfTheWorkspace() {
        write("kept.txt", "k")
        Files.createSymbolicLink(File(workspace, "leak.txt").toPath(), File(outside, "secret.txt").toPath())
        Files.createSymbolicLink(File(workspace, "leakdir").toPath(), outside.toPath())
        File(workspace, "sub").mkdirs()
        Files.createSymbolicLink(File(workspace, "sub/up").toPath(), base.toPath())
        Files.createSymbolicLink(File(workspace, "inside-link.txt").toPath(), File(workspace, "kept.txt").toPath())

        val zip = exporter.workspaceZip()

        assertEquals(mapOf("kept.txt" to "k"), entries(zip))
        ZipFile(zip).use { z -> assertTrue(z.entries().asSequence().none { it.name.contains("secret") || it.name.contains("..") }) }
    }

    @Test fun workspaceZipOfAMissingWorkspaceIsAValidEmptyZip() {
        workspace.deleteRecursively()
        assertTrue(entries(exporter.workspaceZip()).isEmpty())
    }

    // --- memoryBackupZip ---

    @Test fun memoryBackupHasMemoryUserAndMemoryDirMarkdown() {
        write("MEMORY.md", "m")
        write("USER.md", "u")
        write("memory/2026-11-01.md", "d1")
        write("memory/sub/topic.md", "d2")
        write("memory/.lock", "lock")
        write("memory/.dream-state.json", "{}")
        write("memory/notes.txt", "txt")
        write("other.md", "not memory")
        write("notes/x.md", "not memory")

        val zip = exporter.memoryBackupZip()

        assertTrue(zip.parentFile!!.canonicalFile == exportDir.canonicalFile)
        assertTrue(zip.name.matches(Regex("""dsh-memory-\d{8}-\d{4}\.zip""")))
        assertEquals(
            mapOf(
                "MEMORY.md" to "m", "USER.md" to "u",
                "memory/2026-11-01.md" to "d1", "memory/sub/topic.md" to "d2",
            ),
            entries(zip),
        )
    }

    @Test fun memoryBackupNameUsesClockTime() {
        val a = exporter.memoryBackupZip().name
        now += 3 * 24 * 3_600_000L
        val b = exporter.memoryBackupZip().name
        assertTrue(a != b)
    }

    @Test fun memoryBackupSkipsSymlinksAndMissingFiles() {
        write("USER.md", "u")
        write("memory/real.md", "r")
        Files.createSymbolicLink(File(workspace, "MEMORY.md").toPath(), File(outside, "secret.txt").toPath())
        Files.createSymbolicLink(File(workspace, "memory/link.md").toPath(), File(outside, "secret.txt").toPath())
        Files.createSymbolicLink(File(workspace, "memory/dir").toPath(), outside.toPath())
        File(outside, "leak.md").writeText("leak")

        val e = entries(exporter.memoryBackupZip())

        assertEquals(setOf("USER.md", "memory/real.md"), e.keys)
        assertFalse(e.values.any { it.contains("secret") || it.contains("leak") })
    }

    @Test fun memoryBackupWithEmptyWorkspaceIsAValidEmptyZip() {
        assertTrue(entries(exporter.memoryBackupZip()).isEmpty())
    }

    @Test fun memoryBackupEntriesAreRelative() {
        write("MEMORY.md")
        write("memory/a/b.md")
        for (name in entries(exporter.memoryBackupZip()).keys) {
            assertFalse(name.startsWith("/"))
            assertFalse(name.split("/").contains(".."))
        }
    }

    // --- errorLogZip ---

    @Test fun errorLogZipContainsScrubbedLogAndRotatedLog() {
        val hex = "0123456789abcdef".repeat(4)
        val log = errorLog.file()
        log.parentFile!!.mkdirs()
        log.writeText("a sk-abcdef0123456789 b\nhex $hex\ntoken=xyz\nkeep me\n")
        File(log.parentFile, "error.log.1").writeText("old sk-oldoldoldold token=abc\n")

        val zip = exporter.errorLogZip()

        assertTrue(zip.name.matches(Regex("""dsh-error-log-\d{8}-\d{4}\.zip""")))
        val e = entries(zip)
        assertEquals(setOf("error.log", "error.log.1"), e.keys)
        assertEquals("a *** b\nhex ***\ntoken=***\nkeep me\n", e["error.log"])
        assertEquals("old *** token=***\n", e["error.log.1"])
    }

    @Test fun errorLogZipOmitsMissingRotatedLog() {
        errorLog.file().apply { parentFile!!.mkdirs() }.writeText("only\n")
        assertEquals(setOf("error.log"), entries(exporter.errorLogZip()).keys)
    }

    @Test fun errorLogZipWithoutAnyLogIsEmptyButValid() {
        assertTrue(entries(exporter.errorLogZip()).isEmpty())
    }

    // --- share ---

    @Test fun shareRefusesFilesOutsideWorkspace() {
        try {
            exporter.share(File(outside, "secret.txt"))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertNull(shadowOf(app).nextStartedActivity)
        assertFalse(File(exportDir, "secret.txt").exists())
    }

    @Test fun shareRefusesSymlinkInWorkspacePointingOutside() {
        Files.createSymbolicLink(File(workspace, "evil.txt").toPath(), File(outside, "secret.txt").toPath())
        try {
            exporter.share(File(workspace, "evil.txt"))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertFalse(File(exportDir, "evil.txt").exists())
    }

    @Test fun shareRefusesDotDotPathThatLeavesWorkspace() {
        try {
            exporter.share(File(workspace, "../outside/secret.txt"))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun shareRefusesDirectories() {
        File(workspace, "dir").mkdirs()
        try {
            exporter.share(File(workspace, "dir"))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun shareCopiesToCacheExportAndStartsChooser() {
        write("report.txt", "hello")

        exporter.share(File(workspace, "report.txt"))

        assertEquals("hello", File(exportDir, "report.txt").readText())
        val chooser = shadowOf(app).nextStartedActivity
        assertNotNull(chooser)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
    }

    @Test fun sendIntentGrantsReadAndUsesTheExportAuthority() {
        write("report.txt", "hello")
        write("pic.png", "png")

        val chooser = exporter.buildChooser(File(workspace, "report.txt"), "text/plain")
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!

        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("text/plain", send.type)
        val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)!!
        assertEquals("content", uri.scheme)
        assertEquals("app.dshmobile.export", uri.authority)
        assertTrue(uri.path!!.endsWith("report.txt"))

        // Default mime guessed from the extension.
        val png = exporter.buildChooser(File(workspace, "pic.png"))
            .getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals("image/png", png.type)
    }

    @Test fun shareOverwritesSameNameAndAcceptsFilesAlreadyInExportDir() {
        write("report.txt", "v1")
        exporter.share(File(workspace, "report.txt"))
        write("report.txt", "v2")
        exporter.share(File(workspace, "report.txt"))
        assertEquals("v2", File(exportDir, "report.txt").readText())

        val zip = exporter.errorLogZip()
        zip.setLastModified(now - 7_200_000L) // stale, but being shared right now: must survive the cleanup
        exporter.share(zip)
        assertTrue(zip.exists())
    }

    @Test fun shareWorkspaceFileResolvesInsideWorkspaceOnly() {
        write("notes/a.txt", "a")
        exporter.shareWorkspaceFile("notes/a.txt")
        assertEquals("a", File(exportDir, "a.txt").readText())

        for (bad in listOf("../outside/secret.txt", "/etc/passwd", "", "missing.txt")) {
            try {
                exporter.shareWorkspaceFile(bad)
                fail("expected IllegalArgumentException for '$bad'")
            } catch (_: IllegalArgumentException) {
            }
        }
        Files.createSymbolicLink(File(workspace, "dir").toPath(), outside.toPath())
        try {
            exporter.shareWorkspaceFile("dir/secret.txt")
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    // --- clearExports ---

    @Test fun clearExportsDeletesOnlyFilesOlderThanOneHour() {
        exportDir.mkdirs()
        val old = File(exportDir, "old.zip").apply { writeText("o"); setLastModified(now - 3_700_000L) }
        val fresh = File(exportDir, "fresh.zip").apply { writeText("f"); setLastModified(now - 600_000L) }

        exporter.clearExports()

        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test fun clearExportsWithoutDirectoryIsANoOp() {
        exportDir.deleteRecursively()
        exporter.clearExports()
    }

    @Test fun shareClearsStaleExportsFirst() {
        exportDir.mkdirs()
        val old = File(exportDir, "old.zip").apply { writeText("o"); setLastModified(now - 7_200_000L) }
        write("r.txt", "r")
        exporter.share(File(workspace, "r.txt"))
        assertFalse(old.exists())
    }
}
