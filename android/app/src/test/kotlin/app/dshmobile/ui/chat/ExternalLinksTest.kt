package app.dshmobile.ui.chat

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ExternalLinksTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun webLinksOpenInABrowsableViewIntent() {
        assertEquals(LinkOpen.OPENED, ExternalLinks.open(app, "https://example.com/e2e-weather"))
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://example.com/e2e-weather", intent.dataString)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertTrue("started from the application: a new task", intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(LinkOpen.OPENED, ExternalLinks.open(app, "HTTP://example.com/"))
    }

    @Test fun otherSchemesNeverOpen() {
        listOf("file:///data/data/app.dshmobile/x", "content://x/y", "intent://x#Intent;end", "javascript:alert(1)", "mailto:a@b.c", "https://", "not a url", "")
            .forEach { assertEquals(it, LinkOpen.REFUSED, ExternalLinks.open(app, it)) }
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun withoutABrowserItSaysFalse() {
        val noBrowser = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent?) = throw android.content.ActivityNotFoundException()
        }
        assertEquals(LinkOpen.NO_BROWSER, ExternalLinks.open(noBrowser as Context, "https://example.com/"))
    }

    /** Final review B2-M9: the phone's own loopback (dsh, the gateway) is never handed to a browser. */
    @Test fun loopbackLinksAreRefused() {
        listOf(
            "http://127.0.0.1:8080/?token=x", "https://127.0.0.5/", "http://localhost:3000/", "http://LOCALHOST/", "http://[::1]:80/",
            "http://0.0.0.0:9000/", "http://app.localhost/",
        ).forEach { assertEquals(it, LinkOpen.REFUSED, ExternalLinks.open(app, it)) }
        assertNull(shadowOf(app).nextStartedActivity)
    }
}
