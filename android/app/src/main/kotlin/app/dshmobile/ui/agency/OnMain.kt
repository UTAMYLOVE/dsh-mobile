package app.dshmobile.ui.agency

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/*
 * The company's screens change Compose state, and drive layout, on the main thread only, whatever dispatcher runs the
 * composition's effects. On the phone the effects already run on the main thread and these cost nothing (Main.immediate does
 * not dispatch there). Under the Compose test rule the effects run unconfined: without these, a flow the brain sets on an IO
 * thread, or the rest of an effect after `withContext(Dispatchers.IO)`, went on on that IO thread, wrote the screen's state and
 * sent the snapshot's apply notifications from there, and Compose could then observe layout reads off the main thread while the
 * main thread drew (the e2e e05 flake).
 */

/** [collectAsState] with the collector on the main thread: the state changes there, whichever thread sets the flow. */
@Composable
fun <T> StateFlow<T>.collectOnMain(): State<T> = collectAsState(Dispatchers.Main.immediate)

/** Runs [block] on the main thread: for an effect that writes the screen's state or scrolls after work elsewhere. */
suspend fun <T> onMain(block: suspend CoroutineScope.() -> T): T = withContext(Dispatchers.Main.immediate, block)
