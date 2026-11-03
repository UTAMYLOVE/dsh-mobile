package app.dshmobile.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTokenTest {
    @Test fun twoGeneratedTokensDiffer() {
        assertNotEquals(SessionToken.generate().value, SessionToken.generate().value)
    }

    @Test fun valueIs64LowercaseHexChars() {
        val v = SessionToken.generate().value
        assertEquals(64, v.length)
        assertTrue(v.matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun toStringDoesNotLeakValue() {
        val t = SessionToken.generate()
        assertFalse(t.toString().contains(t.value))
        assertEquals("SessionToken(***)", t.toString())
    }
}
