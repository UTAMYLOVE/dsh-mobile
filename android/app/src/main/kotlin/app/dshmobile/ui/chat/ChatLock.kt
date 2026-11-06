package app.dshmobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.ui.fog.FogButton
import app.dshmobile.ui.fog.FogButtonKind
import app.dshmobile.ui.theme.Sakura
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The system's own lock (fingerprint, face or PIN through `KeyguardManager`); the app never sees how it was passed. */
interface Unlocker {
    /** False when the phone has no screen lock: then nothing can be locked. */
    fun canLock(): Boolean
    /** Asks the system; [onOk] runs only when the owner passed it. */
    fun unlock(onOk: () -> Unit)
}

val LocalUnlocker = staticCompositionLocalOf<Unlocker?> { null }

/** Opens the system file picker; the callback gets what was picked (nothing when cancelled). */
val LocalPickFiles = staticCompositionLocalOf<((List<android.net.Uri>) -> Unit) -> Unit> { { } }

/** Locked conversations opened since the app came to the front; [relock] forgets them (the app stayed in the background). */
object UnlockedChats {
    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    fun add(id: String) { _ids.value = _ids.value + id }
    fun relock() { _ids.value = emptySet() }

    /** Away at least this long relocks (a share sheet or the file picker does not). */
    const val RELOCK_AFTER_MS = 30_000L
}

object LockTags {
    const val PAGE = "lock-page"
    const val UNLOCK = "lock-unlock"
}

/** Mockup 9: the lock, 「這個對話已鎖定」 and 解鎖. */
@Composable
fun LockedPage(onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().background(Brush.radialGradient(listOf(Sakura.White, Sakura.Background))).testTag(LockTags.PAGE),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(84.dp).background(Brush.linearGradient(listOf(Sakura.Background, Sakura.Primary)), RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center,
            ) { Text("🔒", fontSize = 34.sp) }
            Text(stringResource(R.string.lock_title), color = Sakura.Text, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text(stringResource(R.string.lock_body), color = Sakura.TextSecondary, fontSize = 13.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
            FogButton(stringResource(R.string.lock_unlock), FogButtonKind.PINK, onUnlock, Modifier.testTag(LockTags.UNLOCK))
        }
    }
}
