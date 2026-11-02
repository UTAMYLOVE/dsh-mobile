package app.dshmobile

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import app.dshmobile.heartbeat.HeartbeatWorkerFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * WorkManager must be initialised on demand from [DshMobileApp.workManagerConfiguration]. If the default startup
 * initializer stays in the manifest it runs first with a default Configuration, our WorkerFactory is never used and
 * WorkManager fails to reflect a `HeartbeatWorker(Context, WorkerParameters)` that does not exist.
 */
@RunWith(RobolectricTestRunner::class)
class WorkManagerInitTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun theDefaultWorkManagerInitializerIsNotInTheMergedManifest() {
        val provider = context.packageManager.getProviderInfo(
            ComponentName(context, "androidx.startup.InitializationProvider"),
            PackageManager.GET_META_DATA,
        )
        val keys = provider.metaData?.keySet().orEmpty()
        assertFalse("WorkManagerInitializer must be removed: $keys", "androidx.work.WorkManagerInitializer" in keys)
    }

    @Test fun theAppsConfigurationCarriesTheHeartbeatWorkerFactory() {
        val factory = (context as DshMobileApp).workManagerConfiguration.workerFactory
        assertTrue("got ${factory.javaClass}", factory is HeartbeatWorkerFactory)
    }
}
