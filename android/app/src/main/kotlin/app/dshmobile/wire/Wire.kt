package app.dshmobile.wire

import app.agency.core.agent.Company
import app.agency.core.agent.Providers
import app.agency.core.base.Clock
import app.agency.core.metering.Provider
import app.dshmobile.AppGraph
import app.dshmobile.DshMobileApp
import app.dshmobile.R
import app.dshmobile.platform.media.MediaRecorderEngine
import app.dshmobile.platform.media.RecorderEngine
import app.dshmobile.ui.UiGraph
import app.dshmobile.wire.ui.ActivityMediaUi
import app.dshmobile.wire.ui.BackupAdapter
import app.dshmobile.wire.ui.BodyAdapter
import app.dshmobile.wire.ui.EvalAdapter
import app.dshmobile.wire.ui.LessonsAdapter
import app.dshmobile.wire.ui.MemoAdapter
import app.dshmobile.wire.ui.MoneyAdapter
import app.dshmobile.wire.ui.OldDataAdapter
import app.dshmobile.wire.ui.OnboardingAdapter
import app.dshmobile.wire.ui.SettingsAdapter
import app.dshmobile.wire.ui.SettingsPanelPrefs
import app.dshmobile.wire.ui.SingingAdapter
import app.dshmobile.wire.ui.TimerAdapter
import app.dshmobile.wire.ui.TraineeAdapter
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The wiring layer: connects the pure company ([Company]) and the platform ([AppGraph]) to the screens. Built once by
 * [DshMobileApp]; every member is lazy, so building it opens nothing (the encrypted database opens on first use, off the main
 * thread). What it makes:
 * - [company]: the one company the screens and the alarm use, over the real store, clock, model clients ([ProviderWiring]) and
 *   cost meter ([SpendMeter]);
 * - [wake]: what an alarm, the app coming to the front and every sentence or tap does ([CompanyWake]);
 * - [uiGraph]: a real implementation of every door in `ui/UiInterfaces.kt` (the demo is never used in a real build).
 *
 * First start order: no rules yet → [company]'s `today().ready` is false → the screens show onboarding; the wake handler does
 * nothing until the rules are saved (it checks `callName`); onboarding's last step runs the first rollover (day 1).
 */
class Wire(private val app: DshMobileApp, private val graph: AppGraph) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val screens: Screens get() = app.screens

    /** The graph's clock, looked up at each use (a test may replace `AppGraph.clock` after the company was built). */
    private val liveClock = object : Clock {
        override fun now() = graph.clock.now()
        override val zone get() = graph.clock.zone
    }

    /** The microphone of the singing and evaluation pages; an instrumentation test replaces it with a fake. */
    @Volatile var recorderEngine: () -> RecorderEngine = { MediaRecorderEngine(app) }

    val host: ActivityHost by lazy { ActivityHost(screens, app) }

    val spend: SpendMeter by lazy { SpendMeter(graph.store, graph.settings, { graph.clock }) }

    val providers: ProviderWiring by lazy {
        ProviderWiring(
            transport = graph.transport, keys = graph.keys, settings = graph.settings, meter = spend,
            claudeBase = { graph.claudeBase }, deepseekBase = { graph.deepseekBase }, onRateLimited = ::rateLimited,
        )
    }

    /** The keys 0.9 saved are carried over once, the first time anything asks whether a key exists. */
    private val legacyKeys: Boolean by lazy { LegacyKeys.forApp(app).run(graph.keys); true }

    val company: WiredCompany by lazy {
        WiredCompany(
            build = { _ ->
                Company(
                    store = graph.store, clock = liveClock,
                    providers = providers.providers().let { p ->
                        Providers(p.chat, p.small, p.search) { legacyKeys && providers.keyReady() }
                    },
                    loadPhoto = { id -> runCatching { graph.vault.get(id) }.getOrNull() },
                )
            },
            searchOn = { legacyKeys && graph.keys.has(Provider.DEEPSEEK) },
            onChanged = ::planChanged,
        )
    }

    val wake: CompanyWake by lazy {
        CompanyWake(
            company = { company.quiet }, store = { graph.store }, notifier = graph.notifier, alarms = graph.alarms,
            clock = { graph.clock }, foreground = { screens.inForeground }, noteTitle = { app.getString(R.string.wire_note_title) },
            log = { graph.errorLog.append("wake", it) },
        )
    }

    /** The plan may have changed: let the wake handler look again (and arm the next alarm), off the caller's thread. */
    fun planChanged() {
        scope.launch { runCatching { wake.onWake() } }
    }

    private fun rateLimited() {
        graph.notifier.post("rate", app.getString(R.string.wire_rate_title), app.getString(R.string.wire_rate_text), loud = false)
    }

    /** The app came to the front: the alarm may have been missed, a video may have ended. */
    fun foreground() {
        scope.launch {
            runCatching { lessonsAdapter.cameBack() }
            runCatching { wake.onWake() }
        }
    }

    // ---- the screens' doors ----------------------------------------------------------------------------------------------

    private val media by lazy { ActivityMediaUi(app, host, { graph.attachments }, { graph.vault }) }
    private val lessonsAdapter by lazy { LessonsAdapter(app, company) }
    private val singing by lazy {
        SingingAdapter(app, host, company, { graph.vault }, { recorderEngine() }, app.getString(R.string.wire_song_title))
    }

    val uiGraph: UiGraph by lazy {
        // The settings document is read once, off the main thread, so the first panel look-up on the screen does not wait for it.
        thread(name = "wire-warm", isDaemon = true) { runCatching { graph.settings.get() } }
        UiGraph(
            company = company,
            media = media,
            lessons = lessonsAdapter,
            timer = TimerAdapter(company, graph.store),
            memo = MemoAdapter(company) { graph.clock },
            body = BodyAdapter(app, company, company, graph.store) { graph.clock },
            money = MoneyAdapter(app, company, company),
            trainee = TraineeAdapter(company) { graph.clock },
            singing = singing,
            eval = EvalAdapter(app, host, company, graph.store, media, singing, { graph.vault }) { graph.clock },
            onboarding = OnboardingAdapter(app, company, company, graph.keys, graph.notifier, graph.alarms, host, ::planChanged),
            settings = SettingsAdapter(app, company, graph.store, graph.settings, graph.keys, spend, graph.notifier),
            backup = BackupAdapter(app, host, { graph.backup }, { graph.clock }, ::planChanged),
            oldData = OldDataAdapter({ graph.oldData }) { graph.clock },
            panels = SettingsPanelPrefs(graph.settings),
        )
    }

    companion object {
        /** Registers the company and the wake handler in [AppGraph] and the foreground hook. Cheap: opens nothing. */
        fun install(app: DshMobileApp): Wire {
            val graph = app.graph
            val w = Wire(app, graph)
            app.wire = w
            graph.company = w.company
            graph.wake = w.wake
            app.screens.onForeground = w::foreground
            return w
        }
    }
}
