package app.dshmobile.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ErrorLogTest {
    private val dir: File = Files.createTempDirectory("errorlog").toFile()
    private val log = ErrorLog(dir)

    @After fun cleanup() { dir.deleteRecursively() }

    private fun lines() = log.file().readLines()

    @Test fun writesIsoTimestampTagAndMessage() {
        log.append("dsh", "hello world")
        assertEquals(File(dir, "logs/error.log"), log.file())
        assertTrue(lines().single().matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z \[dsh] hello world""")))
    }

    @Test fun masksTokenQueryValues() {
        log.append("dsh", "open http://127.0.0.1:3080/?token=abc123XYZ&x=1 now")
        val line = lines().single()
        assertFalse(line.contains("abc123XYZ"))
        assertTrue(line.contains("token=***"))
    }

    @Test fun masksSixtyFourHexCharacters() {
        val hex = "0123456789abcdef".repeat(4)
        log.append("gw", "http://127.0.0.1:9/$hex/deepseek and $hex")
        val line = lines().single()
        assertFalse(line.contains("0123456789abcdef0123456789abcdef"))
        assertEquals(2, Regex("""\*\*\*""").findAll(line).count())
    }

    @Test fun keepsShortHexRuns() {
        log.append("x", "commit 0123456789abcdef")
        assertTrue(lines().single().endsWith("commit 0123456789abcdef"))
    }

    @Test fun masksRegisteredSecrets() {
        log.addSecret("s3cret-launch-value")
        log.append("dsh", "leak s3cret-launch-value and again s3cret-launch-value")
        val line = lines().single()
        assertFalse(line.contains("s3cret"))
        assertTrue(line.endsWith("leak *** and again ***"))
    }

    @Test fun blankSecretIsIgnored() {
        log.addSecret("")
        log.append("dsh", "plain text")
        assertTrue(lines().single().endsWith("plain text"))
    }

    @Test fun multiLineMessageBecomesOneEntryPerLine() {
        log.append("dsh", "first\nsecond\r\nthird")
        val l = lines()
        assertEquals(3, l.size)
        assertTrue(l.all { it.contains("[dsh] ") })
        assertTrue(l[2].endsWith("[dsh] third"))
    }

    @Test fun rotatesAboveOneMegabyteKeepingOnlyOneBackup() {
        val chunk = "x".repeat(1000)
        repeat(1100) { log.append("fill", chunk) } // ~1.1 MB: crosses the limit once
        val backup = File(dir, "logs/error.log.1")
        assertTrue(backup.isFile)
        assertTrue(backup.length() > 1_000_000)
        assertTrue(log.file().length() < 200_000)
        log.append("after", "marker")
        assertTrue(log.file().readText().contains("[after] marker"))

        repeat(1100) { log.append("fill2", chunk) } // second rotation overwrites the old backup
        assertEquals(setOf("error.log", "error.log.1"), File(dir, "logs").list()!!.toSet())
        assertTrue("backup is the newer file now", backup.readText().contains("[after] marker"))
    }

    @Test fun concurrentAppendsLoseNoLines() {
        val threads = (1..8).map { t -> Thread { repeat(100) { log.append("t$t", "line $it") } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(800, lines().size)
    }
}
