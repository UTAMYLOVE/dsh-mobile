package app.dshmobile

import android.os.SystemClock
import android.util.Log
import androidx.metrics.performance.JankStats
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
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
 */
@RunWith(AndroidJUnit4::class)
class PerfGateTest {
    @get:Rule val dumpOnFailure = E2e.DumpErrorLogOnFailure()

    private val app get() = E2e.app
    private val device: UiDevice by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }
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
                require(device.wait(Until.hasObject(By.res(Tags.ASSISTANT_BUBBLE)), 180_000)) { "no reply to ${FakeModel.LONG}" }
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
            require(device.wait(Until.hasObject(By.res(Tags.LIVE_BUBBLE)), 120_000)) { "the reply to ${FakeModel.SLOW} never started" }
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
        if (!device.wait(Until.hasObject(By.res(Tags.TAB + ShellTab.OVERVIEW.name)), 60_000)) {
            E2e.finishShell(activity)
            throw AssertionError("the shell's tabs never showed")
        }
        return activity
    }

    private fun find(tag: String): UiObject2 =
        device.wait(Until.findObject(By.res(tag)), 60_000) ?: throw AssertionError("nothing tagged $tag on screen")

    private fun selectTab(tab: ShellTab) {
        find(Tags.TAB + tab.name).click()
        SystemClock.sleep(TAB_PAUSE_MS) // the page switch (560 ms) and its sweep (620 ms) play out
    }

    /** The chat tab with a fresh conversation open (the list's 「＋新對話」). */
    private fun openNewConversation() {
        selectTab(ShellTab.CHAT)
        if (!device.hasObject(By.res(Tags.NEW_SESSION))) find(Tags.CHAT_LIST_BUTTON).click()
        val before = E2e.nav { openSessionId }
        find(Tags.NEW_SESSION).click()
        E2e.waitFor(60_000, "a new conversation") { E2e.nav { openSessionId != null && openSessionId != before && !listOpen } }
        find(Tags.CHAT_HEADER)
    }

    private fun sendFromTheDock(text: String) {
        find(Tags.COMPOSER_INPUT).text = text
        E2e.waitFor(10_000, "the composer to hold the prompt") { device.findObject(By.res(Tags.SEND))?.isEnabled == true }
        find(Tags.SEND).click()
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
