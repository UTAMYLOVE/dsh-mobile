package app.dshmobile.wire

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Starts system pickers, permission questions and other activities for a suspended caller, through the registry of the screen in
 * front (no registration in `onCreate` needed: each launch registers a one-shot launcher and drops it when the answer arrives).
 * Every function switches to the main thread by itself. With no screen in front they answer "nothing" (null / false).
 */
class ActivityHost(private val screens: Screens, private val context: Context) {
    private val seq = AtomicInteger()

    suspend fun <I, O> launch(contract: ActivityResultContract<I, O>, input: I): O? = withContext(Dispatchers.Main.immediate) {
        val activity = screens.current() ?: return@withContext null
        suspendCancellableCoroutine<O?> { cont ->
            val holder = java.util.concurrent.atomic.AtomicReference<ActivityResultLauncher<I>?>()
            val l = activity.activityResultRegistry.register("wire-${seq.incrementAndGet()}", contract) { result ->
                holder.get()?.unregister()
                if (cont.isActive) cont.resume(result)
            }
            holder.set(l)
            cont.invokeOnCancellation { l.unregister() }
            try {
                l.launch(input)
            } catch (_: Exception) {
                l.unregister()
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    fun granted(permission: String): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** True when [permission] is (now) granted; asks once when it is not. */
    suspend fun ask(permission: String): Boolean = granted(permission) || launch(ActivityResultContracts.RequestPermission(), permission) == true

    suspend fun askCamera(): Boolean = ask(Manifest.permission.CAMERA)

    suspend fun askMicrophone(): Boolean = ask(Manifest.permission.RECORD_AUDIO)
}
