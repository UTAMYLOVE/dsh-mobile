package app.dshmobile.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import app.dshmobile.update.DailyUpdateCheck
import app.dshmobile.update.PendingUpdate
import app.dshmobile.ui.theme.SakuraTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
    private lateinit var chromeClient: DshChromeClient
    private lateinit var overlay: ComposeView
    /** The system document picker for `<input type=file>` (registered before the activity starts, as the API requires). */
    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        chromeClient.onPickerResult(result.resultCode, result.data)
    }
    private lateinit var downloads: DownloadHandler
    private lateinit var ops: MainOps
    private var ready = false
    private var startJob: Job? = null
    private var loadedLaunchUrl: String? = null
    private var loadFailed = false
    /** The page of [loadedLaunchUrl] has finished loading (onPageFinished); only then may the screen say Ready. */
    private var pageFinished = false
    private var rollingBack = false
    /** When this screen was created (elapsed realtime); the first Ready after it is logged under `perf`, then cleared. */
    private var openedAt: Long? = null
    private var updating = false
    private val pendingUpdate by lazy { PendingUpdate(getSharedPreferences(DailyUpdateCheck.PREFS, Context.MODE_PRIVATE)) }

    internal val webViewForTest: WebView get() = webView
    internal val overlayForTest: android.view.View get() = overlay
    internal val uiStateForTest: kotlinx.coroutines.flow.StateFlow<MainUiState> get() = uiState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isSetUp()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        ready = true
        openedAt = SystemClock.elapsedRealtime()
        enableEdgeToEdge()
        // DshVersions is built on first use (a rollback), not at every launch.
        ops = MainOps(app.controller, { app.dshVersions.previous() }, { app.dshVersions.rollback() }, app.errorLog)
        setContentView(buildContent())
        // Ready: the overlay shrinks to the settings button; Loading/Error: it covers the page again.
        lifecycleScope.launch {
            uiState.collect { state ->
                overlay.layoutParams = overlayLayout(state)
                if (state == MainUiState.Ready) logTimeToReady()
            }
        }
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

    override fun onResume() {
        super.onResume()
        // Back from the "install unknown apps" page: carry on with the update that was waiting for the grant. Without the grant
        // nothing happens and the flag stays. Only the update runs here; dsh is not touched.
        if (ready && pendingUpdate.isPending && app.apkInstaller.canInstall()) {
            // Cleared before the run: a failing attempt must not repeat (and re-download) on every resume.
            pendingUpdate.isPending = false
            installUpdate()
        }
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
            onLoaded = {
                pageFinished = true
                if (!loadFailed) uiState.value = MainUiState.Ready
            },
            onMainFrameError = { loadFailed = true; uiState.value = MainUiState.Error },
            onRendererGone = { recreate() },
        )
        downloads = DownloadHandler(
            exportDir = { File(cacheDir, "export") },
            http = OkHttpClient(),
            dshPort = { webClient.dshPort },
            cookies = { CookieManager.getInstance().getCookie(it) },
        )
        chromeClient = DshChromeClient(openPicker = { intent ->
            try {
                filePicker.launch(intent)
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        })
        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#FEDFE1"))
            webViewClient = webClient
            webChromeClient = chromeClient // file inputs and the default alert/confirm/prompt dialogs
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
            // dsh's renderer is the app: keep it at the foreground app's priority (waived while the app is not visible).
            // Hardware acceleration stays on (the manifest never turns it off; no software layer here).
            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        CookieManager.getInstance().setAcceptCookie(true)

        overlay = ComposeView(this).apply {
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
        root.setBackgroundColor(Color.parseColor("#FEDFE1"))
        root.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(overlay, overlayLayout(uiState.value))
        // Edge-to-edge is enforced; keep the page clear of the system bars and the keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        return root
    }

    /** Full screen for the loading and error screens; only the settings button's corner when dsh is shown. */
    private fun overlayLayout(state: MainUiState): FrameLayout.LayoutParams =
        if (state == MainUiState.Ready) {
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
        } else {
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

    /** Once per screen: how long it took from opening it to dsh's page (and from the process start, for cold starts). No URL. */
    private fun logTimeToReady() {
        val opened = openedAt ?: return
        openedAt = null
        val now = SystemClock.elapsedRealtime()
        val sinceProcess = opened - Process.getStartElapsedRealtime()
        app.errorLog.append("perf", "main screen ready ${now - opened} ms after it opened (process started $sinceProcess ms before that)")
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
            // Same dsh again (back from the background, or a second start racing the first): Ready only once the page has finished.
            if (pageFinished && uiState.value == MainUiState.Loading) uiState.value = MainUiState.Ready
            return
        }
        loadedLaunchUrl = url
        loadFailed = false
        pageFinished = false
        uiState.value = MainUiState.Loading
        webView.loadUrl(url)
    }

    internal fun retry() {
        loadedLaunchUrl = null
        loadFailed = false
        superviseAndShow()
    }

    /**
     * The explicit way back to a supervised dsh: ends a give-up, hands supervision to the service (after a give-up it has
     * stopped itself, so a dsh started here would otherwise run with nobody watching it) and shows dsh through the normal
     * path (Loading, then the launch URL; ensureRunning just returns a dsh that already runs).
     */
    private fun superviseAndShow() {
        app.supervise(this)
        uiState.value = MainUiState.Loading
        startDsh()
    }

    // ---------- intents ----------

    private fun handle(intent: Intent?) {
        when (val request = LaunchRequest.from(intent)) {
            LaunchRequest.None -> Unit
            LaunchRequest.Rollback -> confirmRollback()
            LaunchRequest.ExportErrorLog -> exportErrorLog()
            LaunchRequest.InstallUpdate -> installUpdate()
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

    /** The rollback itself ([MainOps.rollback]); replaced in tests to reach outcomes a real switch rarely produces. */
    internal var rollbackStep: suspend () -> MainOps.RollbackOutcome = { ops.rollback() }

    /**
     * Runs the rollback as an application job ([SettingsJobs], shared with Settings' update and rollback, one at a time): stop,
     * switch and start must not be cut short when this activity goes away, which would leave dsh stopped. The outcome is told by a
     * toast; this screen (if it is still there) then follows dsh's real state ([showRealStateAfterRollback]). A screen opened
     * later follows it through its usual collector.
     */
    private fun rollback() {
        if (rollingBack) return
        val app = app
        val step = rollbackStep
        val started = app.settingsJobs.launch {
            val outcome = withContext(NonCancellable) {
                try {
                    step()
                } catch (e: Exception) {
                    // Also a start that a stop cancelled (CancellationException): the job itself is never cancelled.
                    app.errorLog.append("rollback", "rollback failed: ${e.javaClass.simpleName}")
                    MainOps.RollbackOutcome.Failed("rollback failed")
                }
            }
            // A dsh that is up again goes back under the service (also ends a give-up); a dsh that is down stays as it is.
            if (app.controller.isRunning.value) app.supervise()
            withContext(Dispatchers.Main) {
                val text = when (outcome) {
                    is MainOps.RollbackOutcome.RolledBack -> app.getString(R.string.main_rollback_done, outcome.version)
                    MainOps.RollbackOutcome.NoPrevious -> app.getString(R.string.main_rollback_none)
                    is MainOps.RollbackOutcome.Failed -> app.getString(R.string.main_rollback_failed)
                }
                Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
                if (!isDestroyed) {
                    rollingBack = false
                    showRealStateAfterRollback()
                }
            }
        }
        if (started) rollingBack = true else toast(getString(R.string.main_rollback_busy))
    }

    /**
     * Whatever the rollback's outcome, the screen follows whether dsh actually runs now (the collector is suppressed during
     * the rollback and does not re-emit afterwards). Running: dsh is shown through the normal path (the job has already handed it
     * to the service and ended a give-up). Down: the Error screen; a give-up flag stays set, so Retry is the way out and nothing
     * restarts dsh on its own.
     */
    private fun showRealStateAfterRollback() {
        if (app.controller.isRunning.value) {
            uiState.value = MainUiState.Loading
            startDsh()
        } else {
            uiState.value = MainUiState.Error
        }
    }

    // ---------- shell app update ----------

    /**
     * The update notice's tap. The intent carries no URL or hash (this activity is exported): the release is looked up again
     * from the public releases repo, downloaded, checked against its SHA-256 and only then handed to the system installer.
     * Toasts report each stage; a failed check or hash never reaches the installer. Without the install permission nothing is
     * downloaded: the settings page opens, the update is remembered ([PendingUpdate]) and [onResume] continues it after the grant.
     */
    private fun installUpdate() {
        if (updating) return
        if (!app.apkInstaller.canInstall()) {
            pendingUpdate.isPending = true
            try {
                app.apkInstaller.openInstallSettings()
            } catch (_: ActivityNotFoundException) {
                app.errorLog.append("update", "install settings page not available")
            }
            toast(getString(R.string.main_update_needs_permission))
            return
        }
        updating = true
        lifecycleScope.launch {
            try {
                toast(getString(R.string.main_update_checking))
                val info = app.updateChecker.latest()
                if (info == null) {
                    toast(getString(R.string.main_update_none))
                    return@launch
                }
                toast(getString(R.string.main_update_downloading, info.versionName))
                val apk = app.apkInstaller.download(info)
                if (apk == null) {
                    app.errorLog.append("update", "download or verification of ${info.versionName} failed")
                    toast(getString(R.string.main_update_failed))
                    return@launch
                }
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    // The user left during the download: Android would block the installer's start from the background without a
                    // word, so the verified APK goes to a notice whose tap comes back here (and reuses the download).
                    app.notifier.showUpdateReady(info)
                    return@launch
                }
                try {
                    app.apkInstaller.install(apk)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A verified APK is ready but the installer could not be launched: leave a way to retry.
                    app.errorLog.append("update", "could not open the installer: ${e.javaClass.simpleName}")
                    app.notifier.showUpdateReady(info)
                    toast(getString(R.string.main_update_ready))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.errorLog.append("update", "update failed: ${e.javaClass.simpleName}")
                toast(getString(R.string.main_update_failed))
            } finally {
                updating = false
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
