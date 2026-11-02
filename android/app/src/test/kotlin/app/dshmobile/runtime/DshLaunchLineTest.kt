package app.dshmobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DshLaunchLineTest {
    @Test fun parsesPlainLaunchLine() {
        val r = DshLaunchLine.parse("dsh web: http://127.0.0.1:3080/?token=abc123")
        assertEquals(LaunchUrl("http://127.0.0.1:3080/?token=abc123", 3080), r)
    }

    @Test fun toleratesWhitespaceAndAnsiColors() {
        val line = "  \u001B[32mdsh web:\u001B[0m \u001B[1mhttp://127.0.0.1:3080/?token=abc123\u001B[0m \r\n"
        val r = DshLaunchLine.parse(line)
        assertEquals("http://127.0.0.1:3080/?token=abc123", r?.url)
        assertEquals(3080, r?.port)
    }

    @Test fun rejectsNonLoopbackHosts() {
        assertNull(DshLaunchLine.parse("dsh web: http://0.0.0.0:3080/?token=x"))
        assertNull(DshLaunchLine.parse("dsh web: http://localhost:3080/?token=x"))
        assertNull(DshLaunchLine.parse("dsh web: http://192.168.1.5:3080/?token=x"))
        assertNull(DshLaunchLine.parse("dsh web: http://127.0.0.1.evil.com:3080/?token=x"))
    }

    @Test fun rejectsMissingOrEmptyToken() {
        assertNull(DshLaunchLine.parse("dsh web: http://127.0.0.1:3080/"))
        assertNull(DshLaunchLine.parse("dsh web: http://127.0.0.1:3080/?token="))
    }

    @Test fun rejectsOutOfRangePort() {
        assertNull(DshLaunchLine.parse("dsh web: http://127.0.0.1:99999/?token=x"))
    }

    @Test fun rejectsOtherLogLines() {
        assertNull(DshLaunchLine.parse(""))
        assertNull(DshLaunchLine.parse("listening on http://127.0.0.1:3080/?token=abc"))
        assertNull(DshLaunchLine.parse("[info] starting dsh"))
        assertNull(DshLaunchLine.parse("dsh web: starting"))
    }
}
