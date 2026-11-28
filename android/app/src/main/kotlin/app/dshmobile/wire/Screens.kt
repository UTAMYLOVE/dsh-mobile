package app.dshmobile.wire

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import java.lang.ref.WeakReference

/**
 * Knows whether one of our screens is in front and which one (pickers and permission questions need an activity). Registered
 * once by the application. [onForeground] runs each time the app comes to the front (the first screen starts).
 */
class Screens : Application.ActivityLifecycleCallbacks {
    @Volatile private var started = 0
    @Volatile private var top: WeakReference<ComponentActivity>? = null

    /** Set by [Wire]; called on the main thread. */
    @Volatile var onForeground: () -> Unit = {}

    /** True while at least one of our screens is started. */
    val inForeground: Boolean get() = started > 0

    /** The screen in front, or null when none (the app is in the background). */
    fun current(): ComponentActivity? = top?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    override fun onActivityStarted(activity: Activity) {
        val was = started
        started = was + 1
        if (activity is ComponentActivity) top = WeakReference(activity)
        if (was == 0) runCatching(onForeground)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is ComponentActivity) top = WeakReference(activity)
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
