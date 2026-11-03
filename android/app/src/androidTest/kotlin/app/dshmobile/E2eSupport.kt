@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package app.dshmobile

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.AndroidComposeUiTestEnvironment
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.dshmobile.gateway.Provider
import app.dshmobile.system.OnboardingState
import app.dshmobile.ui.MainActivity
import app.dshmobile.ui.MainUiState
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.shell.ShellNav
import app.dshmobile.ui.shell.ShellTab
import app.dshmobile.ui.shell.Tags
import org.junit.rules.TestRule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runners.model.Statement
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Waiting, shell access and diagnostics shared by the end-to-end tests. Nothing here prints a URL (they carry tokens), a key or
 * a request body.
 *
 * Two rules of thumb, both from the CI emulator (software GPU, two cores):
 *  - while a Compose test environment is active ([E2eCompose]) the shell only recomposes when the test waits through it, so
 *    every wait on something the screen shows goes through [await] (Compose's `waitUntil` pumps the frames), never `sleep`;
 *  - nothing here calls `waitForIdleSync` while the shell may animate endlessly (main thread work is posted with
 *    `runOnMainSync`, which does not wait for an idle looper that a slow renderer might never reach).
 */
object E2e {
    val app: DshMobileApp get() = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /**
     * Rulings 2-4: onboarding skipped, the fake key in the real Keystore vault, the bundled rootfs installed from the APK asset.
     * [effects] is a test-setup choice (Task 15): the end-to-end suite runs at SAVER, so no glass backdrop blur is drawn on the
     * emulator's software renderer; t21 and the perf gate ask for FULL themselves. The production default stays FULL.
     */
    fun prepareApp(effects: EffectLevel = EffectLevel.SAVER) {
        val app = app
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        if (!app.rootfsInstaller.isInstalled()) {
            val t0 = System.currentTimeMillis()
            app.rootfsInstaller.install { }
            Log.i(FakeModel.TAG, "rootfs installed in ${(System.currentTimeMillis() - t0) / 1000} s")
        }
        app.keyVault.put(Provider.DEEPSEEK, FakeModel.E2E_KEY)
        val state = OnboardingState(app.getSharedPreferences(OnboardingState.PREFS, Context.MODE_PRIVATE))
        OnboardingState.Step.entries.forEach { state.markDone(it) }
        useEffects(effects)
    }

    /** The user's effect level, as the settings control would set it (also gives the jank governor a fresh start). */
    fun useEffects(level: EffectLevel) {
        if (app.effectsController.level.value != level || app.effectsController.effective.value != level) app.effectsController.set(level)
    }

    /** The last lines of the app's error log (already masked when written: session token, launch token, 64-hex runs). */
    fun errorLogTail(lines: Int = 60): String = try {
        app.errorLog.file().readLines().takeLast(lines).joinToString("\n")
    } catch (e: Exception) {
        "(no error log: ${e.javaClass.simpleName})"
    }

    /** Polls [cond] off the main thread; for state that does not depend on the screen being recomposed (dsh, the app's stores). */
    fun waitFor(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (runCatching(cond).getOrDefault(false)) return
            Thread.sleep(500)
        }
        throw AssertionError("timed out after ${timeoutMs / 1000} s waiting for $what\n--- error log tail ---\n${errorLogTail()}")
    }

    /** Runs [block] on the main thread and returns its result; it never waits for the main looper to go idle. */
    fun <T> onMain(block: () -> T): T {
        var out: Result<T>? = null
        instrumentation.runOnMainSync { out = runCatching(block) }
        return out!!.getOrThrow()
    }

    /** The shell's activity once it is started (resumed or paused), else null. Looked up afresh, so a recreated screen is found too. */
    fun mainActivity(): MainActivity? = onMain {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED).asSequence()
            .flatMap { monitor.getActivitiesInStage(it).asSequence() }
            .filterIsInstance<MainActivity>()
            .firstOrNull { !it.isFinishing }
    }

    fun shellActivity(): MainActivity = mainActivity() ?: throw AssertionError("the shell's activity is not started")

    /** Reads or changes the shell's navigation, on the main thread. */
    fun <T> nav(block: ShellNav.() -> T): T = onMain { shellActivity().navForTest.block() }

    /** Starts the main screen (onboarding is never in the way: [prepareApp] ran first) and waits until the shell is Ready. */
    fun launchShell(intent: Intent? = null): ActivityScenario<MainActivity> {
        val scenario = if (intent == null) ActivityScenario.launch(MainActivity::class.java) else ActivityScenario.launch(intent)
        awaitShellReady()
        return scenario
    }

    /** Waits until the main screen's shell is Ready: dsh runs and the native interface's remote to it is open. */
    fun awaitShellReady() {
        waitFor(300_000, "the native shell to be ready (dsh up, remote open)") {
            mainActivity()?.uiStateForTest?.value == MainUiState.Ready
        }
    }

    /** Class names of every WebView in [activity]'s view tree (the main flow must have none). Main thread. */
    fun webViewsIn(activity: Activity): List<String> {
        val found = mutableListOf<String>()
        fun walk(v: View) {
            if (v is WebView) found += v.javaClass.name
            if (v is ViewGroup) (0 until v.childCount).forEach { walk(v.getChildAt(it)) }
        }
        walk(activity.window.decorView)
        return found
    }

    /** A shell command as the instrumentation's shell user (settings, wm); returns its output. */
    fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    /**
     * Turns the system's animator, transition and window animation scales to 1 for [block] (CI runs the emulator with them at 0,
     * which the shell reads as "remove animations") and puts the previous values back afterwards, also when [block] fails. A
     * screen opened inside [block] reads the setting when it starts.
     */
    fun withAnimationsOn(block: () -> Unit) {
        val before = ANIMATION_SCALES.associateWith { shell("settings get global $it").trim() }
        try {
            ANIMATION_SCALES.forEach { shell("settings put global $it 1") }
            val end = System.currentTimeMillis() + 15_000
            while (!ValueAnimator.areAnimatorsEnabled() && System.currentTimeMillis() < end) Thread.sleep(200)
            if (!ValueAnimator.areAnimatorsEnabled()) Log.w(FakeModel.TAG, "animators still report off after setting the scales to 1")
            block()
        } finally {
            before.forEach { (key, value) ->
                shell(if (value.isEmpty() || value == "null") "settings delete global $key" else "settings put global $key $value")
            }
        }
    }

    private val ANIMATION_SCALES = listOf("animator_duration_scale", "transition_animation_scale", "window_animation_scale")

    /** On failure, puts the masked error log and the kinds of the model's requests (never their bodies) into logcat. */
    class DumpErrorLogOnFailure : TestWatcher() {
        override fun failed(e: Throwable, description: Description) {
            Log.e(FakeModel.TAG, "${description.methodName} failed; model requests: ${FakeModel.instance.seen}")
            errorLogTail(400).lineSequence().forEach { Log.e(FakeModel.TAG, "errorlog| $it") }
        }
    }
}

/**
 * The Compose test environment for the end-to-end tests, in place of `createEmptyComposeRule()`: that rule runs every test
 * body under a 60-second wall-clock limit (kotlinx-coroutines-test's runTest), and a blocking test body that takes longer fails
 * after it completed. Starting dsh alone takes longer than that on the CI emulator, so this one allows [timeout].
 */
class E2eCompose(timeout: Duration = 20.minutes) : TestRule {
    private val environment = AndroidComposeUiTestEnvironment<ComponentActivity>(EmptyCoroutineContext, EmptyCoroutineContext, timeout) {
        error("the end-to-end tests launch their activities themselves")
    }

    /** Finds and drives what any Compose screen started during the test shows. */
    val ui: ComposeUiTest get() = environment.test

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            environment.runTest { base.evaluate() }
        }
    }
}

// ---------- driving the shell through Compose's semantics (test tags only, never Chinese text) ----------

fun ComposeUiTest.nodes(matcher: SemanticsMatcher, unmerged: Boolean = false): List<SemanticsNode> =
    onAllNodes(matcher, useUnmergedTree = unmerged).fetchSemanticsNodes(atLeastOneRootRequired = false)

fun ComposeUiTest.has(tag: String): Boolean = nodes(hasTestTag(tag), unmerged = true).isNotEmpty()

/** A test tag starting with [prefix] (for the tags that end in an id). */
fun hasTagPrefix(prefix: String): SemanticsMatcher =
    SemanticsMatcher("test tag starts with $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true }

fun SemanticsNode.tag(): String? = config.getOrNull(SemanticsProperties.TestTag)

/** Every text of this node (its descendants' too when it merges them), its editable text and its content descriptions. */
fun SemanticsNode.texts(): List<String> =
    config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
        listOfNotNull(config.getOrNull(SemanticsProperties.EditableText)?.text) +
        config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

/**
 * The texts shown under [tag]: from the merged tree when the tagged node is there (a merging node: its whole content), else
 * from the node itself (a node inside a merging parent).
 */
fun ComposeUiTest.textsOf(tag: String): List<String> {
    val merged = nodes(hasTestTag(tag))
    return (merged.ifEmpty { nodes(hasTestTag(tag), unmerged = true) }).flatMap { it.texts() }
}

/** Waits (pumping the shell's frames) until [cond] holds; fails with [what] and the app's masked error log. */
fun ComposeUiTest.await(what: String, timeoutMs: Long = 120_000, cond: () -> Boolean) {
    try {
        waitUntil(what, timeoutMs) { runCatching(cond).getOrDefault(false) }
    } catch (e: ComposeTimeoutException) {
        throw AssertionError("timed out after ${timeoutMs / 1000} s waiting for $what\n--- error log tail ---\n${E2e.errorLogTail()}")
    }
}

fun ComposeUiTest.awaitTag(tag: String, timeoutMs: Long = 120_000) = await("a node tagged $tag", timeoutMs) { has(tag) }

fun ComposeUiTest.awaitNoTag(tag: String, timeoutMs: Long = 120_000) = await("no node tagged $tag", timeoutMs) { !has(tag) }

fun ComposeUiTest.awaitText(text: String, timeoutMs: Long = 120_000) =
    await("the text '$text' on screen", timeoutMs) { nodes(hasText(text, substring = true)).isNotEmpty() }

/** Taps the node tagged [tag] once it is there and enabled, scrolling it into view first when it sits in a scrolling page. */
fun ComposeUiTest.tap(tag: String, timeoutMs: Long = 120_000) {
    awaitTag(tag, timeoutMs)
    await("$tag to be enabled", timeoutMs) { nodes(hasTestTag(tag), unmerged = true).first().config.getOrNull(SemanticsProperties.Disabled) == null }
    val node = onAllNodes(hasTestTag(tag), useUnmergedTree = true).onFirst()
    runCatching { node.performScrollTo() } // only pages scroll; the dock and the cards do not
    node.performClick()
}

/** Replaces the text of the field tagged [tag]. */
fun ComposeUiTest.type(tag: String, text: String) {
    awaitTag(tag)
    await("$tag to be enabled") { nodes(hasTestTag(tag), unmerged = true).first().config.getOrNull(SemanticsProperties.Disabled) == null }
    onAllNodes(hasTestTag(tag), useUnmergedTree = true).onFirst().performTextReplacement(text)
}

/** The chat tab, its conversation list (over an open conversation) and 「＋新對話」; returns the new conversation's id, open in the tab. */
fun ComposeUiTest.newConversation(): String {
    tap(Tags.TAB + ShellTab.CHAT.name)
    await("the chat tab") { has(Tags.NEW_SESSION) || has(Tags.CHAT_LIST_BUTTON) }
    if (!has(Tags.NEW_SESSION)) tap(Tags.CHAT_LIST_BUTTON)
    val before = E2e.nav { openSessionId }
    tap(Tags.NEW_SESSION)
    var id: String? = null
    await("a new conversation open in the chat tab") {
        val (open, listOpen) = E2e.nav { openSessionId to listOpen }
        val ready = open != null && open != before && !listOpen && has(Tags.CHAT_HEADER)
        if (ready) id = open
        ready
    }
    return id!!
}

/** Types [text] into the dock and sends it (to the conversation open in the chat tab). */
fun ComposeUiTest.send(text: String) {
    type(Tags.COMPOSER_INPUT, text)
    tap(Tags.SEND)
}

private fun clickable(n: SemanticsNode) =
    n.config.getOrNull(SemanticsActions.OnClick) != null || n.config.getOrNull(SemanticsActions.SetText) != null

private fun Rect.overlaps(o: Rect) = left < o.right && o.left < right && top < o.bottom && o.top < bottom

private fun SemanticsNode.isAncestorOf(other: SemanticsNode): Boolean {
    var p = other.parent
    while (p != null) {
        if (p.id == id) return true
        p = p.parent
    }
    return false
}

fun SemanticsNode.describe(): String = tag() ?: config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "node $id"

/**
 * Nothing floating covers anything else floating: the controls of the dock (and the attach menu, the attachment chips and the
 * reconnecting chip above it) and of a permission or question card, compared by their bounds on screen.
 */
fun ComposeUiTest.assertNoFloatingOverlap(where: String = "") {
    val floating = hasAnyAncestor(hasTestTag(Tags.DOCK)) or hasAnyAncestor(hasTestTag(Tags.PROMPT_SHEET))
    val controls = nodes(floating, unmerged = true).filter { n ->
        val tag = n.tag().orEmpty()
        (clickable(n) || tag == Tags.RECONNECTING || tag.startsWith(Tags.ATTACHMENT_CHIP)) && n.boundsInRoot.width > 0f && n.boundsInRoot.height > 0f
    }
    for (i in controls.indices) for (j in i + 1 until controls.size) {
        val a = controls[i]
        val b = controls[j]
        if (a.isAncestorOf(b) || b.isAncestorOf(a)) continue // a chip and its own remove button
        if (a.boundsInRoot.overlaps(b.boundsInRoot)) {
            throw AssertionError("$where: ${a.describe()} ${a.boundsInRoot} overlaps ${b.describe()} ${b.boundsInRoot}")
        }
    }
}
