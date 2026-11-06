package app.dshmobile.ui

import app.dshmobile.ui.chat.LocalUnlocker
import app.dshmobile.chat.FileKind
import app.dshmobile.ui.shell.SharedSaver
import app.dshmobile.ui.shell.ShareSheet
import app.dshmobile.ui.shell.SharedContent
import app.dshmobile.ui.chat.failureCode
import app.dshmobile.runtime.DshLayout
import app.dshmobile.chat.ProjectStore
import app.dshmobile.ui.shell.AssistantChip
import app.dshmobile.ui.chat.LocalPickFiles
import app.dshmobile.ui.chat.UnlockedChats
import app.dshmobile.ui.chat.Unlocker
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
import androidx.activity.viewModels
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.metrics.performance.JankStats
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.dshmobile.DshMobileApp
import app.dshmobile.R
import app.dshmobile.ui.agents.WarStrip
import app.dshmobile.ui.library.LibraryDsh
import app.dshmobile.config.AgentModels
import app.dshmobile.remote.PromptPart
import app.dshmobile.ui.library.LibraryScreen
import app.dshmobile.ui.library.bookModels
import app.dshmobile.ui.agents.rememberWarAgents
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
import app.dshmobile.ui.chat.LinkOpen
import app.dshmobile.ui.chat.sendFromDock
import app.dshmobile.chat.AttachmentLoader
import app.dshmobile.chat.ConversationStore
import app.dshmobile.chat.AttachmentReason
import app.dshmobile.chat.LoadResult
import app.dshmobile.chat.PendingAttachment
import app.dshmobile.chat.decodeThumbnail
import app.dshmobile.ui.shell.AttachmentChip
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.dshmobile.ui.agents.AgentsTab
import app.dshmobile.ui.fog.FogPanel
import app.dshmobile.ui.fog.JankGovernor
import app.dshmobile.ui.overview.OverviewScreen
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.effectsFor
import app.dshmobile.ui.fog.systemReducedMotion
import app.dshmobile.ui.shell.ComposerUi
import app.dshmobile.ui.shell.FogShell
import app.dshmobile.ui.shell.LocalPagePadding
import app.dshmobile.ui.shell.PromptHost
import app.dshmobile.ui.shell.rememberHasPrompt
import app.dshmobile.ui.shell.ShellNav
import app.dshmobile.ui.shell.ShellSession
import app.dshmobile.ui.shell.ShellTab
import app.dshmobile.ui.shell.buildSettingsEnv
import app.dshmobile.runtime.VerifiedUpdatePolicy
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
import java.util.Locale

/**
 * The main screen: the native FOGCITY shell ([FogShell]) over the in-app dsh, reached through the app-level
 * [app.dshmobile.ui.shell.ShellSessionHolder]. There is no WebView. Decisions live in small classes ([LaunchRequest],
 * [UiHolder], [MainOps], [ShellNav]); this class wires them to dsh's lifecycle. Ready means dsh runs and its remote (the mux
 * connection) has opened. The launch URL carries dsh's one-time token and is never logged.
 */
class MainActivity : ComponentActivity() {
    private val app get() = application as DshMobileApp
    private val uiState = MutableStateFlow(MainUiState.Loading)
    /** Navigation, the composer and the settings draft: they outlive a recreated activity (and partly a process death). */
    private val vm: MainViewModel by viewModels()
    private val nav get() = vm.nav
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
    private val settingsEnv by lazy { buildSettingsEnv(this, app, onEffectLevel = app.effectsController::set) }
    // The system pickers need no permission; both are registered here, before the activity starts.
    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { vm.addPicked(it) }
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addPicked(it) }

    /** 拍照問 (0.5.0): the camera app writes into cache/camera/ through the FileProvider; no CAMERA permission is declared. */
    private var cameraUri: Uri? = null
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok -> cameraUri?.let { if (ok) vm.addPicked(listOf(it)) }; cameraUri = null }

    private fun takePicture() {
        val dir = java.io.File(cacheDir, "camera").apply { mkdirs() }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.export", java.io.File(dir, "photo-${System.currentTimeMillis()}.jpg"))
        cameraUri = uri
        try { camera.launch(uri) } catch (_: android.content.ActivityNotFoundException) { cameraUri = null; toast(getString(R.string.camera_unavailable)) }
    }

    /** 語音輸入 (0.5.0): the system recognizer (zh-TW) types into the field; no RECORD_AUDIO permission in this app. */
    // Locked conversations (0.5.0): the system's own credential screen; the app only learns whether it was passed.
    private var onUnlocked: (() -> Unit)? = null
    private val confirmCredential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val then = onUnlocked
        onUnlocked = null
        if (r.resultCode == RESULT_OK) then?.invoke()
    }
    private var stoppedAt = 0L
    /** Something another app shared, waiting for the owner's choice in the [ShareSheet]. */
    private var shared by mutableStateOf<SharedContent?>(null)
    // Reference files for a project (0.5.0).
    private var onPicked: ((List<Uri>) -> Unit)? = null
    private val projectFilePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val then = onPicked
        onPicked = null
        then?.invoke(uris)
    }
    private fun pickFiles(then: (List<Uri>) -> Unit) {
        onPicked = then
        projectFilePicker.launch(arrayOf("*/*"))
    }
    private val unlocker = object : Unlocker {
        private val keyguard get() = getSystemService(android.app.KeyguardManager::class.java)
        override fun canLock(): Boolean = keyguard?.isDeviceSecure == true
        override fun unlock(onOk: () -> Unit) {
            @Suppress("DEPRECATION")
            val intent = keyguard?.createConfirmDeviceCredentialIntent(getString(R.string.lock_prompt), null)
            if (intent == null) { toast(getString(R.string.lock_no_screen_lock)); return }
            onUnlocked = onOk
            confirmCredential.launch(intent)
        }
    }
    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
        if (said.isNotEmpty()) vm.onText(if (vm.composerText.isBlank()) said else vm.composerText.trimEnd() + " " + said)
    }

    private fun listen() {
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, getString(R.string.shell_voice))
        try { voice.launch(intent) } catch (_: android.content.ActivityNotFoundException) { toast(getString(R.string.voice_unavailable)) }
    }
    /** The system "remove animations" setting; read again at every resume, since it is changed in the system settings. */
    private var reducedMotion by mutableStateOf(false)
    private var jankStats: JankStats? = null
    /** The governor's frame and janky counts when this screen was last started: its stop logs the difference. */
    private var framesAtStart = 0L
    private var jankyAtStart = 0L

    /** How long a started dsh's remote may take to open before the screen says Error (the remote keeps trying). */
    internal var remoteOpenTimeoutMs: Long = REMOTE_OPEN_TIMEOUT_MS

    internal val navForTest: ShellNav get() = nav
    internal val uiStateForTest: StateFlow<MainUiState> get() = uiState
    internal val governorForTest: JankGovernor get() = app.effectsController.governor

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
        ops = MainOps(app.controller, { VerifiedUpdatePolicy.previous(app.dshVersions.previous()) }, { app.dshVersions.rollback() }, app.errorLog)
        // The view model ([vm], made on first use) restores the shell (tab, conversation, list, draft) after a process death, or
        // opens the conversation that was open last time (saved in onStop) on a fresh start; a deep link (below) replaces it.
        reducedMotion = systemReducedMotion()
        setContent {
            // Live: the user's level held down by the jank governor, and the system's animation setting.
            val level by app.effectsController.effective.collectAsState()
            val effects = remember(level, reducedMotion) { effectsFor(level, reducedMotion) }
            FogTheme(effects) {
                androidx.compose.runtime.CompositionLocalProvider(LocalUnlocker provides unlocker, LocalPickFiles provides ::pickFiles) { Shell() }
            }
        }
        trackJank()
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
        // Away long enough: locked conversations ask again (a share sheet or picker does not count).
        if (stoppedAt > 0 && SystemClock.elapsedRealtime() - stoppedAt >= UnlockedChats.RELOCK_AFTER_MS) UnlockedChats.relock()
        if (!ready) return
        startJankTracking()
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

    /**
     * Frame timing feeds the governor (which steps the effects down when the screen drops frames); it is on only while this
     * screen is started. Every step down is one `perf` line; the numbers carry no conversation content.
     */
    private fun trackJank() {
        val governor = app.effectsController.governor
        val log = app.errorLog
        governor.onDegrade = { level, janky, frames -> log.append("perf", "effects capped at $level after $janky janky of $frames frames") }
        jankStats = try {
            JankStats.createAndTrack(window) { frame -> governor.onFrame(frame.isJank) }.also { it.isTrackingEnabled = false }
        } catch (e: Exception) {
            log.append("perf", "frame tracking unavailable (${e.javaClass.simpleName})")
            null
        }
    }

    private fun startJankTracking() {
        val governor = app.effectsController.governor
        governor.onStart()
        framesAtStart = governor.frames
        jankyAtStart = governor.janky
        jankStats?.isTrackingEnabled = true
    }

    /** Stops the tracking and logs this stretch's frames: the way to read real numbers off a device is exporting the error log. */
    private fun stopJankTracking() {
        jankStats?.isTrackingEnabled = false
        val governor = app.effectsController.governor
        val frames = governor.frames - framesAtStart
        if (frames <= 0) return
        val janky = governor.janky - jankyAtStart
        val percent = janky * 100.0 / frames
        app.errorLog.append("perf", "frames=$frames janky=$janky (${String.format(Locale.ROOT, "%.1f", percent)}%) level=${app.effectsController.effective.value}")
    }

    override fun onResume() {
        super.onResume()
        reducedMotion = systemReducedMotion()
        // Back from the "install unknown apps" page: carry on with the update that was waiting for the grant. Without the grant
        // nothing happens and the flag stays. Only the update runs here; dsh is not touched.
        if (ready && pendingUpdate.isPending && app.apkInstaller.canInstall()) {
            // Cleared before the run: a failing attempt must not repeat (and re-download) on every resume.
            pendingUpdate.isPending = false
            installUpdate()
        }
    }

    override fun onStop() {
        stoppedAt = SystemClock.elapsedRealtime()
        if (ready) {
            app.uiPrefs.lastSessionId = nav.openSessionId
            stopJankTracking()
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
        val projects by app.projectStore.projects.collectAsState()
        val assistants = remember(projects) { projects.filter { it.assistant }.map { AssistantChip(it.slug, it.name) } }
        shared?.let { content ->
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { shared = null },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) {
                ShareSheet(
                    content,
                    onAsk = { q -> shared = null; vm.takeShared(content.message(q), content.uris, toNew = true) },
                    onAddToExisting = { q -> shared = null; vm.takeShared(content.message(q), content.uris, toNew = false) },
                    onSaveToShelf = { shared = null; saveSharedToShelf(content) },
                    onDismiss = { shared = null },
                )
            }
        }
        vm.otherMode?.let { madeNative -> OtherModeDialog(madeNative, onSwitch = vm::switchModeForConversation, onCancel = { vm.otherMode = null }) }
        FogShell(
            status = state,
            connection = connection,
            nav = nav,
            composer = ComposerUi(
                text = vm.composerText,
                onText = vm::onText,
                enabled = state == MainUiState.Ready,
                canSend = vm.canSend,
                running = running,
                onSend = vm::onDockSend,
                onStop = vm::stopReply,
                onAttachImage = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onAttachFile = { filePicker.launch(arrayOf("*/*")) },
                chips = vm.chips,
                onRemoveChip = vm::removeChip,
                error = vm.dockError,
                notice = if (vm.editTarget != null) getString(R.string.edit_banner) else null,
                mode = vm.mode,
                onMode = { vm.mode = it },
                onVoice = ::listen,
                onCamera = ::takePicture,
                onCancelNotice = vm::cancelEdit,
                assistants = assistants,
                onAssistant = { slug -> openAssistant(session, slug) },
            ),
            onRetry = ::retry,
            onExportErrorLog = ::exportErrorLog,
            // dsh waits on these: the cards are global and never dismissed (a subagent's card names it through the agents' originOf)
            overlay = { PromptHost(session?.events, session?.tree, originOf = { id -> session?.agents?.originOf(id) }) },
            modalOverlay = rememberHasPrompt(session?.events),
        ) { tab ->
            when (tab) {
                ShellTab.SETTINGS -> {
                    val draft = vm.settingsDraft ?: SettingsDraft.from(settingsEnv).also { vm.settingsDraft = it }
                    SettingsScreen(settingsEnv, onClose = null, contentPadding = LocalPagePadding.current, draft = draft)
                }
                ShellTab.CHAT -> session?.let { ChatTab(it) } ?: EmptyPage(tab)
                ShellTab.AGENTS -> session?.let { sess ->
                    // The limit is the configured subagent cap; read when the tab is shown (changing it needs "apply and restart" anyway).
                    val settings = remember {
                        app.agentSettingsStore.load(PhantomProcessDetector(contentResolver).state(), app.keyVault.has(Provider.ANTHROPIC))
                    }
                    val limit = settings.maxConcurrent
                    val openId = nav.openSessionId
                    val lead = remember(sess, openId) { sess.existing(openId)?.state ?: kotlinx.coroutines.flow.flowOf(null) }
                    val spend by app.costMeter.revision.collectAsStateWithLifecycle()
                    val today = remember(spend) { app.costMeter.todayUsd() }
                    AgentsTab(
                        sess.agents, sess.tree, sess.scope, limit, nav::openSession,
                        recorder = sess.warRoom, openId = openId, lead = lead, todayUsd = today,
                        limits = getString(R.string.war_limits, settings.maxConcurrent, settings.teamSize, settings.maxDepth),
                        alertUsd = remember(spend) { app.costMeter.alertUsd }, team = settings.teamSize,
                    )
                } ?: EmptyPage(tab)
                ShellTab.OVERVIEW -> {
                    // Collected only while the overview is the current page (the page lambda is composed for the current tab only); the
                    // app keeps the last state, so coming back shows it at once instead of an empty page (final review B2-M12).
                    val overview by app.overviewState.collectAsStateWithLifecycle()
                    overview?.let {
                        OverviewScreen(it, nav::openSession, { nav.select(ShellTab.AGENTS) }) {
                            session?.let { sess ->
                                val lead = remember(sess, nav.openSessionId) { sess.existing(nav.openSessionId)?.state ?: kotlinx.coroutines.flow.flowOf(null) }
                                val war = rememberWarAgents(sess.tree, sess.warRoom, nav.openSessionId, lead)
                                val log by sess.warRoom.log.collectAsStateWithLifecycle()
                                val dsh by app.controller.status.collectAsStateWithLifecycle()
                                WarStrip(war, log, dsh) { nav.select(ShellTab.AGENTS) }
                            }
                        }
                    } ?: EmptyPage(tab)
                }
                ShellTab.LIBRARY -> {
                    val hasClaude = remember { app.keyVault.has(Provider.ANTHROPIC) }
                    val dsh = remember(session) {
                        session?.let { sess ->
                            LibraryDsh(
                                startBook = { model, opening ->
                                    val id = sess.index.create()
                                    val store = sess.conversation(id)
                                    AgentModels.find(model)?.let { store.selectModel(it) }
                                    store.send(listOf(PromptPart.Text(opening)), opening, emptyList())
                                    id
                                },
                                conversation = { id -> sess.conversation(id).state },
                                send = { id, text -> sess.conversation(id).send(listOf(PromptPart.Text(text)), text, emptyList()) },
                                openChat = nav::openSession,
                            )
                        }
                    }
                    LibraryScreen(app.libraryStore, dsh, bookModels(hasClaude), remember { app.agentSettingsStore.novelModel }, ::openWorkspaceFile, ::shareWorkspaceFile)
                }
                else -> EmptyPage(tab)
            }
        }
    }

    /** The chat tab (Task 8). A Claude key added in settings shows its models once the tab is shown again. */
    @Composable
    private fun ChatTab(session: ShellSession) {
        val tab = nav.tab
        val hasClaude = remember(tab) { app.keyVault.has(Provider.ANTHROPIC) }
        val lead = remember(tab, hasClaude) {
            app.agentSettingsStore.load(PhantomProcessDetector(contentResolver).state(), hasClaude).leadModel
        }
        ChatPage(
            session, nav, hasClaude, lead, onLink = ::openLink, onShare = ::shareWorkspaceFile, onMessage = ::toast, onRestartDsh = ::restartDsh, onOpenFile = ::openWorkspaceFile,
            onEditMessage = vm::startEdit,
        )
    }

    internal fun attachForTest(uris: List<Uri>) = vm.addPicked(uris)

    /** The chat's 「重新啟動 dsh」 (stage 2 §A): a real stop of the whole tree, then a start, on the app-scope settings jobs. */
    private fun restartDsh() {
        val app = application as DshMobileApp
        app.settingsJobs.launch {
            runCatching {
                app.controller.stop()
                app.controller.ensureRunning()
            }.onFailure { app.errorLog.append("restart", "chat restart failed: ${it.javaClass.simpleName}") }
        }
    }

    private fun openLink(url: String) {
        when (ExternalLinks.open(this, url)) {
            LinkOpen.OPENED -> Unit
            LinkOpen.REFUSED -> toast(getString(R.string.chat_link_refused))
            LinkOpen.NO_BROWSER -> toast(getString(R.string.chat_no_browser))
        }
    }

    /** A 小助理 from the ＋ sheet: a new conversation in its project folder; "" (新增小助理) opens the list's 小助理 tab. */
    private fun openAssistant(session: ShellSession?, slug: String) {
        if (slug.isEmpty() || session == null) {
            nav.listTab = "ASSISTANTS"
            nav.projectSlug = null
            nav.select(ShellTab.CHAT)
            nav.listOpen = true
            return
        }
        lifecycleScope.launch {
            try {
                val id = session.index.createAt(ProjectStore.cwdOf(DshLayout.active.workspace, slug))
                app.conversationPrefs.setProject(id, slug)
                nav.openSession(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(getString(R.string.chat_list_failed, failureCode(e)))
            }
        }
    }

    /** 存到書架: shared text becomes `shared/<time>-<start>.md`, shared files are copied into `shared/`; each lands on the shelf. */
    private fun saveSharedToShelf(content: SharedContent) {
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { SharedSaver(app.appPaths.workspace, contentResolver).save(content) }
            saved.forEach { (rel, title) -> app.libraryStore.addFile(rel, title, FileKind.of(rel).name, null) }
            toast(getString(if (saved.isEmpty()) R.string.share_save_failed else R.string.share_saved, saved.size))
        }
    }

    private fun openWorkspaceFile(rel: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { app.exporter.openWorkspaceFile(rel) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.errorLog.append("export", "opening a workspace file failed: ${e.javaClass.simpleName}")
                toast(getString(R.string.result_open_failed))
            }
        }
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
        SharedContent.from(intent)?.let { content ->
            shared = content
            intent?.action = Intent.ACTION_MAIN // consumed: a recreation must not offer it again
            return
        }
        when (val request = LaunchRequest.from(intent)) {
            LaunchRequest.None -> Unit
            LaunchRequest.Rollback -> confirmRollback()
            LaunchRequest.ExportErrorLog -> exportErrorLog()
            LaunchRequest.InstallUpdate -> installUpdate()
            // Like a tab tap: while settings is busy the tab stays (and says why); the conversation opens once settings is free.
            is LaunchRequest.OpenSession -> vm.openFromLink(request.id)
        }
        // Consumed: a later recreation must not run it again.
        intent?.removeExtra(Notifier.EXTRA_ACTION)
        intent?.removeExtra(Notifier.EXTRA_SESSION_ID)
    }

    /**
     * MainActivity is exported, so another app could send this intent: the rollback needs the user's yes. While a conversation
     * runs the question says the rollback will cut it off (final review B2-I4).
     */
    private fun confirmRollback() {
        val working = settingsEnv.workRunning()
        val message = getString(R.string.main_rollback_message) + if (working) "\n\n" + getString(R.string.set_confirm_rollback) else ""
        AlertDialog.Builder(this)
            .setTitle(R.string.main_rollback_title)
            .setMessage(message)
            .setPositiveButton(if (working) R.string.set_confirm_rollback_go else R.string.main_rollback_confirm) { _, _ -> rollback() }
            .setNegativeButton(if (working) R.string.set_confirm_wait else R.string.main_cancel, null)
            .show()
    }

    /** The rollback itself ([MainOps.rollback]); replaced in tests to reach outcomes a real switch rarely produces. */
    internal var rollbackStep: suspend () -> MainOps.RollbackOutcome = { ops.rollback() }

    /**
     * Runs the shared rollback ([SettingsJobs.rollback], the same one as Settings', one job at a time): stop, switch and start must
     * not be cut short when this activity goes away, which would leave dsh stopped. The outcome is told by a toast; this screen
     * (if it is still there) then follows dsh's real state ([showRealStateAfterRollback]). A screen opened later follows it
     * through its usual collector.
     */
    private fun rollback() {
        if (rollingBack) return
        val app = app
        val started = app.settingsJobs.rollback(
            step = rollbackStep,
            isRunning = { app.controller.isRunning.value },
            supervise = { app.supervise() },
            onFailure = { e -> app.errorLog.append("rollback", "rollback failed: ${e.javaClass.simpleName}") },
        ) { outcome ->
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
        /** Most pictures one photo-picker session returns. */
        const val MAX_PICK = 9
    }
}

/** The fallback for a page that has nothing to show yet (no session, no data): an empty FOGCITY panel with the page's names. */
@Composable
private fun EmptyPage(tab: ShellTab) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LocalPagePadding.current)) {
        FogPanel(title = tab.zh, en = tab.en) {}
    }
}

/** 「這個對話是在一般模式建立的」: sending there would never get an answer; switching restarts dsh in that mode (owner, 2026-11-05). */
@androidx.compose.runtime.Composable
private fun OtherModeDialog(madeNative: Boolean, onSwitch: () -> Unit, onCancel: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(if (madeNative) R.string.other_mode_title_native else R.string.other_mode_title_proot)) },
        text = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.other_mode_body)) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onSwitch, modifier = OTHER_MODE_SWITCH) {
                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(if (madeNative) R.string.other_mode_to_native else R.string.other_mode_to_proot))
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onCancel) { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.main_cancel)) } },
    )
}
private val OTHER_MODE_SWITCH = androidx.compose.ui.Modifier.testTag("other-mode-switch")
