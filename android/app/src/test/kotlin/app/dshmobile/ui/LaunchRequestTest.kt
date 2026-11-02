package app.dshmobile.ui

import android.content.Intent
import app.dshmobile.notify.Notifier
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LaunchRequestTest {
    private fun intent(vararg extras: Pair<String, String>) = Intent().apply { extras.forEach { putExtra(it.first, it.second) } }

    @Test fun noExtrasIsNothing() {
        assertEquals(LaunchRequest.None, LaunchRequest.from(Intent()))
        assertEquals(LaunchRequest.None, LaunchRequest.from(null))
    }

    @Test fun rollbackAndExportActions() {
        assertEquals(LaunchRequest.Rollback, LaunchRequest.from(intent(Notifier.EXTRA_ACTION to Notifier.ACTION_ROLLBACK)))
        assertEquals(LaunchRequest.ExportErrorLog, LaunchRequest.from(intent(Notifier.EXTRA_ACTION to Notifier.ACTION_EXPORT_ERROR_LOG)))
    }

    @Test fun unknownActionIsIgnored() {
        assertEquals(LaunchRequest.None, LaunchRequest.from(intent(Notifier.EXTRA_ACTION to "format_phone")))
        assertEquals(LaunchRequest.None, LaunchRequest.from(intent(Notifier.EXTRA_ACTION to "")))
    }

    @Test fun sessionIdIsAcceptedWhenItLooksLikeOne() {
        val id = "session-d7edacee-0b6b-4c1f-9d3e-123456789abc"
        assertEquals(LaunchRequest.OpenSession(id), LaunchRequest.from(intent(Notifier.EXTRA_SESSION_ID to id)))
    }

    @Test fun oddSessionIdsAreDropped() {
        for (bad in listOf("", " ", "a b", "x\nsecret", "../../etc", "a".repeat(201), "<script>")) {
            assertEquals(bad, LaunchRequest.None, LaunchRequest.from(intent(Notifier.EXTRA_SESSION_ID to bad)))
        }
    }

    @Test fun anActionWinsOverASessionId() {
        assertEquals(
            LaunchRequest.Rollback,
            LaunchRequest.from(intent(Notifier.EXTRA_ACTION to Notifier.ACTION_ROLLBACK, Notifier.EXTRA_SESSION_ID to "session-1")),
        )
    }

    @Test fun nonStringExtrasAreIgnored() {
        val i = Intent().putExtra(Notifier.EXTRA_ACTION, 5).putExtra(Notifier.EXTRA_SESSION_ID, 7)
        assertEquals(LaunchRequest.None, LaunchRequest.from(i))
    }
}
