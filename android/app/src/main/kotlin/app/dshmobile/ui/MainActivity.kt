package app.dshmobile.ui

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.dshmobile.DshMobileApp
import app.dshmobile.R
import app.dshmobile.gateway.Provider
import app.dshmobile.notify.Notifier
import app.dshmobile.remote.MuxState
import app.dshmobile.service.DshService
import app.dshmobile.service.RunningDsh
import app.dshmobile.system.OnboardingState
import app.dshmobile.system.PhantomProcessDetector
import app.dshmobile.ui.chat.ChatPage
import app.dshmobile.ui.chat.DockSend
import app.dshmobile.ui.chat.ExternalLinks
import app.dshmobile.ui.chat.sendFromDock
import app.dshmobile.ui.fog.FogPanel
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.effectsFor
import app.dshmobile.ui.fog.systemReducedMotion
import app.dshmobile.ui.shell.ComposerUi
import app.dshmobile.ui.shell.FogShell
import app.dshmobile.ui.shell.LocalPagePadding
import app.dshmobile.ui.shell.ShellNav
import app.dshmobile.ui.shell.ShellSession
import app.dshmobile.ui.shell.ShellTab
import app.dshmobile.ui.shell.buildSettingsEnv
import app.dshmobile.update.DailyUpdateCheck
import app.dshmobile.update.PendingUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The main screen: the native FOGCITY shell ([FogShell]) over the in-app dsh, reached through the app-level
 * [app.dshmobile.ui.shell.ShellSessionHolder]. There is no WebView. Decisions live in small classes ([LaunchRequest],
 * [UiHolder], [MainOps], [ShellNav]); this class wires them to dsh's lifecycle. Ready means dsh runs and its remote (the mux
 * connection) has opened. The launch URL carries dsh's one-time token and is never logged.
 */
class MainActivity : ComponentActivity() {
    private val app get() = application as DshMobileApp
    private val uiState = MutableStateFlow(MainUiState.Loading)
    private val nav = ShellNav()
    private lateinit var ops: MainOps
    private var ready = false
    private var startJob: Job? = null
    /** The session whose remote has opened since this screen attached it: coming back to it is Ready at once. */
    private var readySession: ShellSession? = null
    private var rollingBack = false
    /** When this screen was created (elapsed realtime); the first Ready after it is logged under `perf`, then cleared. */
    private var openedAt: Long? = null
    private var updating = false
    private val pendingUpdate by lazy { PendingUpdate(getSharedPreferences(DailyUpdateCheck.PREFS, Context.MODE_PRIVATE)) }
    /** Built when the settings tab is first shown. */
    private val settingsEnv by lazy { buildSettingsEnv(this, app) }
    /** Unsaved settings fields live here, outside the tab, so switching tabs keeps them. Built with the tab. */
    private var settingsDraft: SettingsDraft? = null
    private var composerText by mutableStateOf("")
    /** Why the last dock send did not happen (no conversation could be made); cleared by typing. */
    private var dockError by mutableStateOf<String?>(null)

    /** How long a started dsh's remote may take to open before the screen says Error (the remote keeps trying). */
    internal var remoteOpenTimeoutMs: Long = REMOTE_OPEN_TIMEOUT_MS

    internal val navForTest: ShellNav get() = nav
    internal val uiStateForTest: StateFlow<MainUiState> get() = uiState

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
        // Leaving settings while one of its actions runs would cancel it (a key save, an export) or hide its result.
        nav.canLeave = { from -> from != ShellTab.SETTINGS || !settingsBusy() }
        nav.onLeaveRefused = { toast(getString(R.string.shell_settings_busy)) }
        // The chat opens on the conversation that was open last time (saved in onStop); a deep link (below) replaces it.
        if (savedInstanceState == null) nav.openSessionId = app.uiPrefs.lastSessionId
        // Static for now; Task 14 switches levels live and degrades on jank.
        val effects = effectsFor(app.uiPrefs.effectLevel, systemReducedMotion())
        setContent { FogTheme(effects) { Shell() } }
        lifecycleScope.launch {
            uiState.collect { state -> if (state == MainUiState.Ready) logTimeToReady() }
        }
        // Back first walks the shell back (list, conversation, tab); only then does the system's back apply.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (nav.back()) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
        // Collects only while the activity is started (repeatOnLifecycle restarts it on every start; launch it once).
        // The activity only follows dsh, it never starts it on its own: restarts belong to DshService and its CrashPolicy,
        // and a stop (GIVE_UP included) must stay a stop. Retry on the error panel is the explicit user action.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(app.controller.isRunning, app.serviceGaveUp) { running, gaveUp -> running to gaveUp }.collect { (running, gaveUp) ->
                    // A stopped dsh takes its session with it; a new instance gets a new one.
                    if (!running) app.shellSessions.detach()
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
        // from another app must neither clear the error nor start the service or dsh. The flag is in memory only, so a new
        // process starts fresh.
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
        if (ready) {
            app.uiPrefs.lastSessionId = nav.openSessionId
            app.uiHolder.onUiStop()
        }
        super.onStop()
    }

    private fun isSetUp(): Boolean {
        val state = OnboardingState(getSharedPreferences(OnboardingState.PREFS, Context.MODE_PRIVATE))
        return state.isComplete(app.rootfsInstaller.isInstalled(), app.keyVault.get(Provider.DEEPSEEK) != null)
    }

    // ---------- the shell ----------

    @Composable
    private fun Shell() {
        val state by uiState.collectAsState()
        val session by app.shellSessions.current.collectAsState()
        val connectionFlow: StateFlow<MuxState?> = remember(session) { session?.remote?.muxState ?: MutableStateFlow(null) }
        val connection by connectionFlow.collectAsState()
        // Only whether the open conversation runs reaches the shell (not every streamed token).
        val openId = nav.openSessionId
        val runningFlow = remember(session, openId) {
            val s = session
            if (s == null || openId == null) flowOf(false) else s.conversation(openId).state.map { it.running }.distinctUntilChanged()
        }
        val running by runningFlow.collectAsState(false)
        FogShell(
            status = state,
            connection = connection,
            nav = nav,
            composer = ComposerUi(
                text = composerText,
                onText = { composerText = it; dockError = null },
                enabled = state == MainUiState.Ready,
                canSend = composerText.isNotBlank(),
                running = running,
                onSend = ::onDockSend,
                onStop = ::stopReply,
                error = dockError,
            ),
            onRetry = ::retry,
            onExportErrorLog = ::exportErrorLog,
        ) { tab ->
            when (tab) {
                ShellTab.SETTINGS -> {
                    val draft = settingsDraft ?: SettingsDraft.from(settingsEnv).also { settingsDraft = it }
                    SettingsScreen(settingsEnv, onClose = null, contentPadding = LocalPagePadding.current, draft = draft)
                }
                ShellTab.CHAT -> session?.let { ChatTab(it) }
                else -> EmptyPage(tab)
            }
        }
    }

    /** The chat tab (Task 8). A Claude key added in settings shows its models once the tab is shown again. */
    @Composable
    private fun ChatTab(session: ShellSession) {
        val tab = nav.tab
        val hasClaude = remember(tab) { app.keyVault.get(Provider.ANTHROPIC) != null }
        val lead = remember(tab, hasClaude) {
            app.agentSettingsStore.load(PhantomProcessDetector(contentResolver).state(), hasClaude).leadModel
        }
        ChatPage(session, nav, hasClaude, lead, onLink = ::openLink, onShare = ::shareWorkspaceFile, onMessage = ::toast)
    }

    /** The dock's send: to the open conversation (a new one when none is open), and the chat tab shows it. */
    private fun onDockSend() {
        val text = composerText
        val session = app.shellSessions.current.value
        if (text.isBlank() || session == null) return
        nav.select(ShellTab.CHAT)
        if (nav.tab != ShellTab.CHAT) return // settings is busy and said so; the text stays
        composerText = ""
        dockError = null
        session.scope.launch(Dispatchers.Main.immediate) {
            val outcome = sendFromDock(session, nav, text)
            if (outcome != DockSend.Sent && composerText.isEmpty()) composerText = text // nothing typed meanwhile: put it back
            if (outcome is DockSend.NotCreated) dockError = getString(R.string.chat_create_failed, outcome.code)
        }
    }

    private fun stopReply() {
        val session = app.shellSessions.current.value ?: return
        val id = nav.openSessionId ?: return
        session.scope.launch { session.conversation(id).stop() }
    }

    private fun openLink(url: String) {
        if (!ExternalLinks.open(this, url)) toast(getString(R.string.chat_no_browser))
    }

    private fun shareWorkspaceFile(rel: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { app.exporter.shareWorkspaceFile(rel) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.errorLog.append("export", "sharing a workspace file failed: ${e.javaClass.simpleName}")
                toast(getString(R.string.chat_share_failed))
            }
        }
    }

    private fun settingsBusy(): Boolean = settingsDraft?.localBusy == true || app.settingsJobs.busy.value

    /** Once per screen: how long it took from opening it to Ready (and from the process start, for cold starts). No URL. */
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
        if (app.shellSessions.current.value == null) uiState.value = MainUiState.Loading
        startJob = lifecycleScope.launch {
            try {
                showShell(ops.ensureRunning())
            } catch (e: CancellationException) {
                // A start cancelled by a forced stop (not by this activity ending) is a failed start.
                if (!isActive) throw e
                uiState.value = MainUiState.Error
            } catch (e: Exception) {
                uiState.value = MainUiState.Error // the controller already logged the reason
            }
        }
    }

    /**
     * Attaches the shell to [running] (the same dsh keeps its session) and says Ready once its remote has opened. A remote that
     * does not open within [remoteOpenTimeoutMs] is an Error (logged by exception class only); one that closes meanwhile (dsh
     * stopped) leaves the state to the collector.
     */
    private suspend fun showShell(running: RunningDsh) {
        val session = app.shellSessions.attach(running)
        val mux = session.remote.muxState
        if (session === readySession || mux.value == MuxState.OPEN) {
            readySession = session
            uiState.value = MainUiState.Ready
            return
        }
        uiState.value = MainUiState.Loading
        val reached = try {
            withTimeout(remoteOpenTimeoutMs) { mux.first { it == MuxState.OPEN || it == MuxState.CLOSED } }
        } catch (e: TimeoutCancellationException) {
            app.errorLog.append("remote", "no connection (${e.javaClass.simpleName})")
            uiState.value = MainUiState.Error
            return
        }
        if (reached == MuxState.OPEN) {
            readySession = session
            uiState.value = MainUiState.Ready
        }
    }

    internal fun retry() {
        superviseAndShow()
    }

    /**
     * The explicit way back to a supervised dsh: ends a give-up, hands supervision to the service (after a give-up it has
     * stopped itself, so a dsh started here would otherwise run with nobody watching it) and shows dsh through the normal
     * path (Loading, then the shell's remote; ensureRunning just returns a dsh that already runs).
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
            is LaunchRequest.OpenSession -> nav.openSession(request.id)
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

    // ---------- export ----------

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

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private companion object {
        /** Ruling of Task 4: a dsh whose remote does not open within a minute of ensureRunning returning is an Error. */
        const val REMOTE_OPEN_TIMEOUT_MS = 60_000L
    }
}

/** The overview and agents pages until their tasks fill them: an empty FOGCITY panel with the page's names. */
@Composable
private fun EmptyPage(tab: ShellTab) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LocalPagePadding.current)) {
        FogPanel(title = tab.zh, en = tab.en) {}
    }
}
