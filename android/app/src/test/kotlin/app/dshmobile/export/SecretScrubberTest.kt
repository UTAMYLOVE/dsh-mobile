package app.dshmobile.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SecretScrubberTest {
    @Test fun masksSkKeys() {
        val out = SecretScrubber.scrub("auth failed for sk-abcDEF123456 on openai")
        assertEquals("auth failed for *** on openai", out)
        assertEquals("***", SecretScrubber.scrub("sk-ant-api03-AbC_dEf-123"))
    }

    @Test fun masksLongHex() {
        val hex = "a1b2c3d4".repeat(8)
        val out = SecretScrubber.scrub("session $hex ended")
        assertEquals("session *** ended", out)
    }

    @Test fun masksLongAlnumWithDashAndUnderscore() {
        val s = "Abcdefghij_klmnopqrst-uvwxyz012345"
        assertEquals("k=*** x", SecretScrubber.scrub("k=$s x"))
        assertEquals("***", SecretScrubber.scrub("A".repeat(32)))
    }

    @Test fun keepsShortWordsAndPaths() {
        val line = "2026-11-02T10:00:00Z [dsh] listening on 127.0.0.1:8080 at /root/workspace/memory/MEMORY.md"
        assertEquals(line, SecretScrubber.scrub(line))
        assertEquals("A".repeat(31), SecretScrubber.scrub("A".repeat(31)))
        assertEquals("task-force disk-usage", SecretScrubber.scrub("task-force disk-usage"))
    }

    @Test fun masksTokenParam() {
        assertEquals("GET /?token=*** HTTP", SecretScrubber.scrub("GET /?token=xyz HTTP"))
        assertEquals("token=***", SecretScrubber.scrub("token=xyz"))
        assertEquals("a token=*** b", SecretScrubber.scrub("a token=abc b"))
    }

    @Test fun isIdempotentAndMultiline() {
        val text = "one sk-secretsecret\ntwo token=abc\nthree ok"
        val once = SecretScrubber.scrub(text)
        assertEquals("one ***\ntwo token=***\nthree ok", once)
        assertEquals(once, SecretScrubber.scrub(once))
        assertFalse(once.contains("secretsecret"))
    }
}
