package app.dshmobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where plain (non-suspend) UI-side calls run. Tests replace it with an immediate dispatcher. */
val LocalIo = compositionLocalOf<CoroutineDispatcher> { Dispatchers.IO }

/** Tests and screenshots set this to force the mic button on or off; null asks the system. */
val LocalMicOverride = compositionLocalOf<Boolean?> { null }

/** A value loaded off the main thread, with [reload] to load it again (after the page changed something). */
class Loaded<T>(private val state: State<T?>, private val bump: () -> Unit) {
    val value: T? get() = state.value
    fun reload() = bump()
}

@Composable
fun <T> rememberLoaded(vararg keys: Any?, block: suspend () -> T): Loaded<T> {
    val io = LocalIo.current
    var version by remember { mutableIntStateOf(0) }
    val state = produceState<T?>(null, *keys, version) { value = withContext(io) { block() } }
    return remember(state) { Loaded(state) { version++ } }
}

/** Runs [block] on the IO dispatcher from a button tap, then [after] on the main thread. */
@Composable
fun rememberIoRunner(): (suspend () -> Unit) -> Unit {
    val io = LocalIo.current
    val scope: CoroutineScope = rememberCoroutineScope()
    return remember(io, scope) { { block -> scope.launch { withContext(io) { block() } } } }
}
