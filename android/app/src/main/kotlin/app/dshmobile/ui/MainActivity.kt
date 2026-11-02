package app.dshmobile.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.dshmobile.BuildConfig
import app.dshmobile.DshMobileApp
import app.dshmobile.R
import app.dshmobile.gateway.Provider
import app.dshmobile.notify.Notifier
import app.dshmobile.service.DshService
import app.dshmobile.service.RunningDsh
import app.dshmobile.system.OnboardingState
import app.dshmobile.ui.theme.SakuraTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/**
 * The main screen: a WebView on the in-app dsh (`http://127.0.0.1:<port>`) with a Compose overlay for loading, errors and the
 * settings button. Decisions live in small classes ([DshWebViewClient.decide], [LaunchRequest], [UiHolder], [MainOps]); this
 * class only wires them to the views. The launch URL carries dsh's one-time token and is never logged.
 */
class MainActivity : ComponentActivity() {
    private val app get() = application as DshMobileApp
    private val uiState = MutableStateFlow(MainUiState.Loading)
    private lateinit var webView: WebView
    private lateinit var webClient: DshWebViewClient
    private lateinit var downloads: DownloadHandler
    private lateinit var ops: MainOps
    private var ready = false
    private var startJob: Job? = null
    private var loadedLaunchUrl: String? = null
    private var loadFailed = false
    private var rollingBack = false

    internal val webViewForTest: WebView get() = webView
    internal val uiStateForTest: kotlinx.coroutines.flow.StateFlow<MainUiState> get() = uiState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isSetUp()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        ready = true
        enableEdgeToEdge()
        // DshVersions is built on first use (a rollback), not at every launch.
        ops = MainOps(app.controller, { app.dshVersions.previous() }, { app.dshVersions.rollback() }, app.errorLog)
        setContentView(buildContent())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        // Collects only while the activity is started (repeatOnLifecycle restarts it on every start; launch it once).
        // The activity only follows dsh, it never starts it on its own: restarts belong to DshService and its CrashPolicy,
        // and a stop (GIVE_UP included) must stay a stop. Retry on the error screen is the explicit user action.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(app.controller.isRunning, app.serviceGaveUp) { running, gaveUp -> running to gaveUp }.collect { (running, gaveUp) ->
                    if (rollingBack) return@collect // the rollback restarts dsh itself, after switching versions
                    when {
                        gaveUp -> uiState.value = MainUiState.Error
                        // Dsh is up (maybe a new instance after a restart by the service): ensureRunning just returns it.
                        running -> startDsh()
                        uiState.value == MainUiState.Ready -> uiState.value = MainUiState.Loading
                    }
                }
            }
        }
        // An intent that is replayed with a restored instance (process death) was handled before: never roll back twice.
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (ready) handle(intent)
    }

    override fun onStart() {
        super.onStart()
        if (!ready) return
        // The hold comes before anything that can start dsh (Task 10 review note).
        app.uiHolder.onUiStart()
        // After a give-up only an explicit user action restarts dsh (Retry, or the crash notification's rollback): coming back
        // from Settings or another app must neither clear the error nor start the service or dsh. The flag is in memory only,
        // so a new process starts fresh.
        if (app.serviceGaveUp.value) {
            uiState.value = MainUiState.Error
            return
        }
        DshService.start(this)
        startDsh()
    }

    override fun onStop() {
        if (ready) app.uiHolder.onUiStop()
        super.onStop()
    }

    override fun onDestroy() {
        if (ready) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun isSetUp(): Boolean {
        val state = OnboardingState(getSharedPreferences(OnboardingState.PREFS, Context.MODE_PRIVATE))
        return state.isComplete(app.rootfsInstaller.isInstalled(), app.keyVault.get(Provider.DEEPSEEK) != null)
    }

    // ---------- views ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildContent(): FrameLayout {
        webClient = DshWebViewClient(
            openExternal = { startActivity(Intent(Intent.ACTION_VIEW, it).addCategory(Intent.CATEGORY_BROWSABLE)) },
            onLoaded = { if (!loadFailed) uiState.value = MainUiState.Ready },
            onMainFrameError = { loadFailed = true; uiState.value = MainUiState.Error },
            onRendererGone = { recreate() },
        )
        downloads = DownloadHandler(
            exportDir = { File(cacheDir, "export") },
            http = OkHttpClient(),
            dshPort = { webClient.dshPort },
            cookies = { CookieManager.getInstance().getCookie(it) },
        )
        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#FFF5F8"))
            webViewClient = webClient
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                builtInZoomControls = false
            }
            setDownloadListener { url, _, disposition, mime, _ -> download(url, disposition, mime) }
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        CookieManager.getInstance().setAcceptCookie(true)

        val overlay = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                SakuraTheme {
                    MainOverlay(
                        state = uiState.collectAsState().value,
                        onRetry = ::retry,
                        onExportErrorLog = ::exportErrorLog,
                        onOpenSettings = ::openSettings,
                    )
                }
            }
        }
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#FFF5F8"))
        root.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Edge-to-edge is enforced; keep the page clear of the system bars and the keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        return root
    }

    // ---------- dsh ----------

    private fun startDsh() {
        if (startJob?.isActive == true) return
        if (loadedLaunchUrl == null) uiState.value = MainUiState.Loading
        startJob = lifecycleScope.launch {
            try {
                showDsh(ops.ensureRunning())
            } catch (e: CancellationException) {
                // A start cancelled by a forced stop (not by this activity ending) is a failed start.
                if (!isActive) throw e
                uiState.value = MainUiState.Error
            } catch (e: Exception) {
                uiState.value = MainUiState.Error // the controller already logged the reason
            }
        }
    }

    /** Points the view at [running]; a launch URL that is already loaded (same dsh instance) is not loaded again. */
    private fun showDsh(running: RunningDsh) {
        webClient.dshPort = running.dshPort
        val url = running.launch.url
        if (DshWebViewClient.decide(url, running.dshPort) != DshWebViewClient.Decision.ALLOW) {
            uiState.value = MainUiState.Error // the view never loads anything but the local dsh, whatever the launch line said
            return
        }
        if (url == loadedLaunchUrl && !loadFailed) {
            if (uiState.value == MainUiState.Loading) uiState.value = MainUiState.Ready
            return
        }
        loadedLaunchUrl = url
        loadFailed = false
        uiState.value = MainUiState.Loading
        webView.loadUrl(url)
    }

    internal fun retry() {
        app.serviceGaveUp.value = false
        // The service supervises dsh (CrashPolicy, give-up notice, restarts); after a give-up it has stopped itself, so a dsh
        // started here would otherwise run with nobody watching it.
        DshService.start(this)
        loadedLaunchUrl = null
        loadFailed = false
        uiState.value = MainUiState.Loading
        startDsh()
    }

    // ---------- intents ----------

    private fun handle(intent: Intent?) {
        when (val request = LaunchRequest.from(intent)) {
            LaunchRequest.None -> Unit
            LaunchRequest.Rollback -> confirmRollback()
            LaunchRequest.ExportErrorLog -> exportErrorLog()
            // Phase one of the notification deep link: dsh has no conversation URL, so the app only comes to the front.
            is LaunchRequest.OpenSession -> ops.recordDeepLink(request.id)
        }
        // Consumed: a later recreation must not run it again.
        intent?.removeExtra(Notifier.EXTRA_ACTION)
        intent?.removeExtra(Notifier.EXTRA_SESSION_ID)
    }

    /** MainActivity is exported, so another app could send this intent: the rollback needs the user's yes. */
    private fun confirmRollback() {
        AlertDialog.Builder(this)
            .setTitle(R.string.main_rollback_title)
            .setMessage(R.string.main_rollback_message)
            .setPositiveButton(R.string.main_rollback_confirm) { _, _ -> rollback() }
            .setNegativeButton(R.string.main_cancel, null)
            .show()
    }

    private fun rollback() {
        if (rollingBack) return
        rollingBack = true
        lifecycleScope.launch {
            try {
                val outcome = ops.rollback()
                // Dsh runs again (rolled back, or the current version was restarted after a failed switch): that ends a
                // give-up, and the service takes supervision back (it stopped itself on the give-up).
                if (outcome is MainOps.RollbackOutcome.RolledBack || app.controller.isRunning.value) {
                    app.serviceGaveUp.value = false
                    DshService.start(this@MainActivity)
                }
                when (outcome) {
                    is MainOps.RollbackOutcome.RolledBack -> {
                        toast(getString(R.string.main_rollback_done, outcome.version))
                        loadedLaunchUrl = null
                        showDsh(outcome.running)
                    }
                    MainOps.RollbackOutcome.NoPrevious -> toast(getString(R.string.main_rollback_none))
                    is MainOps.RollbackOutcome.Failed -> {
                        toast(getString(R.string.main_rollback_failed))
                        if (!app.controller.isRunning.value) uiState.value = MainUiState.Error
                    }
                }
            } finally {
                rollingBack = false
            }
        }
    }

    // ---------- export, downloads, settings ----------

    private fun exportErrorLog() {
        lifecycleScope.launch {
            try {
                // The zip and the copy into the export directory are file I/O; only starting the chooser is on the main thread.
                val chooser = withContext(Dispatchers.IO) { app.exporter.buildChooser(app.exporter.errorLogZip()) }
                startActivity(chooser)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.errorLog.append("export", "error log export failed: ${e.javaClass.simpleName}")
                toast(getString(R.string.main_export_failed))
            }
        }
    }

    private fun download(url: String, disposition: String?, mime: String?) {
        lifecycleScope.launch {
            when (val result = downloads.download(url, disposition, mime)) {
                is DownloadHandler.Result.Saved -> try {
                    val chooser = withContext(Dispatchers.IO) { app.exporter.buildChooser(result.file) }
                    startActivity(chooser)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    toast(getString(R.string.main_export_failed))
                }
                DownloadHandler.Result.Unsupported -> toast(getString(R.string.main_download_unsupported))
                is DownloadHandler.Result.Failed -> toast(getString(R.string.main_download_failed))
            }
        }
    }

    /** Task 11b creates the settings screen; addressed by name so this module does not depend on it. */
    internal fun openSettings() {
        try {
            startActivity(Intent().setComponent(ComponentName(packageName, SETTINGS_ACTIVITY)))
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.main_settings_missing))
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        const val SETTINGS_ACTIVITY = "app.dshmobile.ui.SettingsActivity"
    }
}
