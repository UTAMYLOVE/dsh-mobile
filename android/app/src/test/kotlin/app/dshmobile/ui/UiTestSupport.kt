package app.dshmobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import java.io.File
import kotlinx.coroutines.Dispatchers

/** Plain calls run in place (no thread hop), and the mic button is shown, so a test sees one deterministic screen. */
@Composable
fun TestEnv(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIo provides Dispatchers.Unconfined, LocalMicOverride provides true) { content() }
}

fun ComposeContentTestRule.show(content: @Composable () -> Unit) {
    setContent { TestEnv(content) }
}

/** Waits (real time) until [condition] holds; suspend work in the demo finishes in place, but a gate may hold it. */
fun ComposeContentTestRule.until(timeoutMs: Long = 5000, condition: () -> Boolean) {
    waitUntil(timeoutMs) { condition() }
}

/** The directory for the owner's pictures, or null when the property is not set. */
fun shotsDir(): File? = System.getProperty("dshm.shots")?.takeIf { it.isNotBlank() }?.let { File(it).also(File::mkdirs) }
