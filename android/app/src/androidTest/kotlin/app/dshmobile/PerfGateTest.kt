package app.dshmobile

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.metrics.performance.JankStats
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import app.dshmobile.heartbeat.HeartbeatScheduler
import app.dshmobile.ui.MainActivity
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.shell.ShellTab
import app.dshmobile.ui.shell.Tags
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * The jank measurement (Rulings 1 and 4): the FOGCITY shell at FULL, with the system's animations on, measured with JankStats
 * while UiAutomator drives it. No Compose test rule (it cancels the shell's endless animations, which are what is measured) and
 * no ActivityScenario (its launch waits for an idle main looper, which a slow software renderer animating every frame may never
 * give). The jank governor's auto-degrade is off for the run, so a regression cannot hide behind a quieter level; each scenario
 * ends with the level still FULL. Every scenario runs once to warm up and once measured.
 *
 * ADVISORY on the CI emulator (controller's ruling, final review A-I5): a software GPU on two shared vCPUs says little about a
 * phone's frame times, so the janky fraction is measured and reported (logged as `perf-gate <scenario>: frames=… janky=…
 * fraction=… max=… verdict=…`, tag DSHM-E2E, and copied into the CI step summary) but a fraction over [MAX_JANK_TABS],
 * [MAX_JANK_SCROLL] or [MAX_JANK_STREAM] (0.10, the brief's) does not fail the build; too few frames for a verdict does not
 * either. Numbers measured on a real device decide the gate later; then these thresholds become clamp(measured x 1.5, 0.02,
 * 0.10) and this test fails over them again. What still fails here: a crash or exception while driving the shell, a shell that
 * never shows, and the effect level stepping down although auto-degrade is off. The end-to-end functional tests (E2eTest)
 * stay release-gating.
 *
 * Every lookup reads the screen afresh ([freshScreen]): UiAutomator otherwise reads a copy of the accessibility tree that the
 * app's window refreshes only through accessibility events, and on the CI emulator that copy kept showing the dock's
 * microphone for 1.5 to more than 10 seconds after the typed prompt had turned it into send (the test then failed with
 * "waiting for the composer to hold the prompt", on commits before and after 外觀主題 alike).
 */
@RunWith(AndroidJUnit4::class)
class PerfGateTest {
    @get:Rule val dumpOnFailure = E2e.DumpErrorLogOnFailure()

    private val app get() = E2e.app
    private val device: UiDevice by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }
    /** The UiAutomation the [device] reads through (the same flags give the same instance, so the same accessibility cache). */
    private val automation by lazy { InstrumentationRegistry.getInstrumentation().getUiAutomation(Configurator.getInstance().uiAutomationFlags) }
    private var levelBefore = EffectLevel.SAVER
    private var autoDegradeBefore = true

    @Before fun setUp() {
        HeartbeatScheduler.cancel(app)
        levelBefore = app.effectsController.level.value
        autoDegradeBefore = app.effectsController.governor.autoDegrade
        E2e.prepareApp(effects = EffectLevel.FULL)
        app.controller.retain(HOLDER)
    }

    @After fun tearDown() {
        app.effectsController.governor.autoDegrade = autoDegradeBefore
        E2e.useEffects(levelBefore)
    }

    /** (1) The tabs: overview → chat → agents → settings → overview, three times. */
    @Test fun tabCyclingStaysUnderTheGate() {
        val cycle = { repeat(3) { listOf(ShellTab.CHAT, ShellTab.AGENTS, ShellTab.SETTINGS, ShellTab.OVERVIEW).forEach(::selectTab) } }
        gate("tabs", MAX_JANK_TABS, warmUp = cycle, measure = cycle)
    }

    /** (2) Up and down through a long Markdown reply (E2E_LONG: 60 sections, each with a small table and a code block), five times. */
    @Test fun scrollingALongReplyStaysUnderTheGate() {
        val upAndDown = {
            val list = find(Tags.CHAT_MESSAGES)
            repeat(5) {
                list.scroll(Direction.UP, 0.9f, SCROLL_SPEED)
                list.scroll(Direction.DOWN, 0.9f, SCROLL_SPEED)
            }
        }
        gate(
            "scroll", MAX_JANK_SCROLL,
            prepare = {
                openNewConversation()
                sendFromTheDock(FakeModel.LONG)
                require(waitForNode(Tags.ASSISTANT_BUBBLE, 180_000) != null) { "no reply to ${FakeModel.LONG}" }
            },
            warmUp = upAndDown, measure = upAndDown,
        )
    }

    /** (3) Ten seconds on the chat page while a reply streams in (E2E_SLOW, about 25 s); its first four seconds are the warm-up. */
    @Test fun streamingStaysUnderTheGate() = gate(
        "streaming", MAX_JANK_STREAM,
        prepare = {
            openNewConversation()
            sendFromTheDock(FakeModel.SLOW)
            require(waitForNode(Tags.LIVE_BUBBLE, 120_000) != null) { "the reply to ${FakeModel.SLOW} never started" }
        },
        warmUp = { SystemClock.sleep(4_000) },
        measure = { SystemClock.sleep(10_000) },
    )

    // ---------- the gate ----------

    /**
     * Starts the shell at FULL with animations on, lets [prepare] set the scene, runs [warmUp] (not counted) and [measure], and
     * checks the janky share of the measured frames against [max].
     */
    private fun gate(name: String, max: Float, prepare: () -> Unit = {}, warmUp: () -> Unit, measure: () -> Unit) {
        E2e.withAnimationsOn {
            E2e.useEffects(EffectLevel.FULL)
            app.effectsController.governor.autoDegrade = false
            val activity = startShell()
            try {
                prepare()
                val counter = FrameCounter(activity)
                warmUp()
                counter.reset()
                measure()
                val (frames, janky) = counter.stop()
                val fraction = if (frames == 0) 1f else janky.toFloat() / frames
                val verdict = advisoryVerdict(frames, fraction, max)
                Log.i(FakeModel.TAG, "perf-gate $name: frames=$frames janky=$janky fraction=${String.format(Locale.ROOT, "%.4f", fraction)} max=$max verdict=$verdict")
                // A setup failure, not a measurement: auto-degrade is off, so the level must not have moved.
                assertEquals("the effects stepped down during $name", EffectLevel.FULL, app.effectsController.effective.value)
                // ADVISORY (see the class doc): over the threshold, or too few frames, is reported above and never fails the run.
            } finally {
                E2e.finishShell(activity)
            }
        }
    }

    /** `within` / `over` the threshold, or `no-verdict` with fewer than [MIN_FRAMES] frames: reported only (advisory). */
    private fun advisoryVerdict(frames: Int, fraction: Float, max: Float): String = when {
        frames < MIN_FRAMES -> "no-verdict"
        fraction <= max -> "within"
        else -> "over"
    }

    /** The shell, started like a launcher would ([E2e.startShell], which finishes it again if the start fails), with its tabs on screen. */
    private fun startShell(): MainActivity {
        val activity = E2e.startShell()
        if (waitForNode(Tags.TAB + ShellTab.OVERVIEW.name, 60_000) == null) {
            E2e.finishShell(activity)
            throw AssertionError("the shell's tabs never showed")
        }
        return activity
    }

    private fun find(tag: String): UiObject2 = waitForNode(tag, 60_000) ?: throw AssertionError("nothing tagged $tag on screen")

    /**
     * Throws away UiAutomator's copy of the screen's accessibility tree, so the next lookup asks the app for the screen as it
     * is now. That copy is refreshed only by the accessibility events the window sends, and here they did not come: the copy
     * kept a parent whose children were gone ("Node returned null child") and the dock's microphone after the prompt had turned
     * it into send, until some other window's event happened to clear it, 1.5 to more than 10 seconds later. Compose sends
     * those events only while Android lists an enabled accessibility service (UiAutomation is not one), and the CI emulator has
     * none turned on; [dockReport] prints what Android says, so a failure shows whether that still holds. Reading afresh is
     * what a lookup costs anyway on a cache miss; no wait is longer than before.
     */
    private fun freshScreen() {
        automation.clearCache()
    }

    /** The node tagged [tag], looked up afresh about every [POLL_MS] until it shows or [timeoutMs] has passed (then null). */
    private fun waitForNode(tag: String, timeoutMs: Long): UiObject2? {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            freshScreen()
            device.findObject(By.res(tag))?.let { return it }
            if (SystemClock.uptimeMillis() >= end) return null
            SystemClock.sleep(POLL_MS)
        }
    }

    private fun showsNow(tag: String): Boolean {
        freshScreen()
        return device.hasObject(By.res(tag))
    }

    private fun selectTab(tab: ShellTab) {
        find(Tags.TAB + tab.name).click()
        SystemClock.sleep(TAB_PAUSE_MS) // the page switch (560 ms) and its sweep (620 ms) play out
    }

    /** The chat tab with a fresh conversation open (the list's 「＋新對話」). */
    private fun openNewConversation() {
        selectTab(ShellTab.CHAT)
        if (!showsNow(Tags.NEW_SESSION)) find(Tags.CHAT_LIST_BUTTON).click()
        val before = E2e.nav { openSessionId }
        find(Tags.NEW_SESSION).click()
        E2e.waitFor(60_000, "a new conversation") { E2e.nav { openSessionId != null && openSessionId != before && !listOpen } }
        find(Tags.CHAT_HEADER)
    }

    /** Types [text] into the dock (as an accessibility service does: no focus, no keyboard) and taps send once it shows. */
    private fun sendFromTheDock(text: String) {
        find(Tags.COMPOSER_INPUT).text = text
        val typed = SystemClock.uptimeMillis()
        val end = typed + SEND_WAIT_MS
        while (true) {
            freshScreen()
            if (device.findObject(By.res(Tags.SEND))?.isEnabled == true) break
            if (SystemClock.uptimeMillis() >= end) {
                throw AssertionError(
                    "timed out after ${SEND_WAIT_MS / 1000} s waiting for the composer to hold the prompt\n" +
                        "--- the dock ---\n${dockReport()}\n--- error log tail ---\n${E2e.errorLogTail()}",
                )
            }
            SystemClock.sleep(POLL_MS)
        }
        Log.i(FakeModel.TAG, "perf-gate: send showed ${SystemClock.uptimeMillis() - typed} ms after the prompt was typed")
        find(Tags.SEND).click()
    }

    /**
     * What the dock holds when send did not show, three ways: what UiAutomator sees (read afresh), what the app's own semantics
     * tree holds (read on the main thread, no accessibility in between), and the screen's state; plus whether Android reports an
     * accessibility service, which is what makes Compose send the events that keep UiAutomator's copy current.
     */
    private fun dockReport(): String = buildString {
        appendLine("seen by UiAutomator: " + runCatching { seenDock() }.getOrElse { "(could not read: ${it.javaClass.simpleName})" })
        appendLine("in the app: " + runCatching { appDock() }.getOrElse { "(could not read: ${it.javaClass.simpleName})" })
        val screen = runCatching {
            val activity = E2e.mainActivity()
            val nav = activity?.let { E2e.onMain { it.navForTest.let { n -> "tab=${n.tab} open=${n.openSessionId} list=${n.listOpen}" } } }
            "uiState=${activity?.uiStateForTest?.value ?: "(no shell)"} $nav"
        }.getOrElse { "(could not read: ${it.javaClass.simpleName})" }
        appendLine("screen: $screen")
        val am = app.getSystemService(AccessibilityManager::class.java)
        append("accessibility: enabled=${am?.isEnabled} services=${am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)?.size}")
    }

    private fun seenDock(): String {
        freshScreen()
        val input = device.findObject(By.res(Tags.COMPOSER_INPUT))
        val field = if (input == null) "input absent" else "input=\"${input.text.orEmpty()}\" enabled=${input.isEnabled}"
        return field + "; " + DOCK_BUTTONS.joinToString("; ") { tag ->
            device.findObject(By.res(tag))?.let { "$tag enabled=${it.isEnabled}" } ?: "$tag absent"
        }
    }

    private fun appDock(): String = E2e.onMain {
        val root = E2e.mainActivity()?.window?.decorView?.let(::composeRoot) ?: return@onMain "(no Compose root)"
        val nodes = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
        fun tagged(tag: String) = nodes.firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        val input = tagged(Tags.COMPOSER_INPUT)
        val field = if (input == null) "input absent" else
            "input=\"${input.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()}\" enabled=${input.config.getOrNull(SemanticsProperties.Disabled) == null}"
        field + "; " + DOCK_BUTTONS.joinToString("; ") { tag ->
            tagged(tag)?.let { "$tag enabled=${it.config.getOrNull(SemanticsProperties.Disabled) == null}" } ?: "$tag absent"
        }
    }

    private fun composeRoot(view: View): RootForTest? {
        if (view is RootForTest) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) composeRoot(view.getChildAt(i))?.let { return it }
        return null
    }

    /** Counts frames and janky frames of [activity]'s window with a JankStats of its own (beside the app's governor). */
    private class FrameCounter(activity: MainActivity) {
        private val frames = AtomicInteger()
        private val janky = AtomicInteger()
        private val stats: JankStats = E2e.onMain {
            JankStats.createAndTrack(activity.window) { frame ->
                frames.incrementAndGet()
                if (frame.isJank) janky.incrementAndGet()
            }
        }

        fun reset() {
            frames.set(0)
            janky.set(0)
        }

        fun stop(): Pair<Int, Int> {
            E2e.onMain { stats.isTrackingEnabled = false }
            return frames.get() to janky.get()
        }
    }

    companion object {
        private const val HOLDER = "e2e-perf"
        /** Fewer measured frames than this means nothing was drawn: no verdict (reported, advisory). */
        private const val MIN_FRAMES = 30
        private const val TAB_PAUSE_MS = 900L
        private const val SCROLL_SPEED = 1_500
        /** How long the typed prompt may take to turn the dock's microphone into send. */
        private const val SEND_WAIT_MS = 10_000L
        /** Between two fresh lookups of the screen. */
        private const val POLL_MS = 250L
        /** The button right of the input: send with text, the microphone when empty and idle, stop while a reply runs. */
        private val DOCK_BUTTONS = listOf(Tags.SEND, Tags.VOICE, Tags.STOP)

        /** Advisory threshold (brief, Step 3) until on-device numbers set the gate. */
        const val MAX_JANK_TABS = 0.10f
        /** Advisory threshold (brief, Step 3) until on-device numbers set the gate. */
        const val MAX_JANK_SCROLL = 0.10f
        /** Advisory threshold (brief, Step 3) until on-device numbers set the gate. */
        const val MAX_JANK_STREAM = 0.10f

        @AfterClass @JvmStatic fun releaseDsh() {
            E2e.app.controller.release(HOLDER)
        }
    }
}
