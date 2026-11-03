package app.dshmobile.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import app.dshmobile.R
import app.dshmobile.config.AgentSettings
import app.dshmobile.config.AgentSettingsStore
import app.dshmobile.gateway.CostMeter
import app.dshmobile.gateway.InMemorySharedPreferences
import app.dshmobile.gateway.MutableClock
import app.dshmobile.gateway.PriceTable
import app.dshmobile.gateway.Provider
import app.dshmobile.gateway.Usage
import app.dshmobile.runtime.LaunchUrl
import app.dshmobile.runtime.UpdateOutcome
import app.dshmobile.security.KeyVault
import app.dshmobile.security.SessionToken
import app.dshmobile.service.DshHolderRuntime
import app.dshmobile.service.DshRuntime
import app.dshmobile.service.RunningDsh
import app.dshmobile.system.PhantomState
import app.dshmobile.ui.shell.Tags
import app.dshmobile.ui.theme.SakuraTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

private class SettingsFakeVault(deepseek: String? = "sk-deepseek-0123456789", claude: String? = null) : KeyVault {
    val keys = mutableMapOf(Provider.DEEPSEEK to deepseek, Provider.ANTHROPIC to claude)
    var failPut = false
    override fun get(p: Provider): String? = keys[p]
    override fun put(p: Provider, key: String) {
        if (failPut) throw IllegalStateException("keystore unavailable: $key")
        keys[p] = key
    }
    override fun clear(p: Provider) { keys[p] = null }
}

private class SettingsFakeRuntime : DshHolderRuntime {
    val events = CopyOnWriteArrayList<String>()
    /** The jobs' holds (kept apart from [events], whose order the tests check). */
    val holds = CopyOnWriteArrayList<String>()
    override fun retain(owner: String) { holds += "retain $owner" }
    override fun release(owner: String) { holds += "release $owner" }
    override fun stopIfUnretained(): Boolean = false
    @Volatile var gate: CompletableDeferred<Unit>? = null
    @Volatile var failStart = false
    @Volatile var completedStarts = 0
    override fun stop() { events += "stop" }
    override suspend fun ensureRunning(): RunningDsh {
        events += "ensureRunning"
        gate?.await()
        if (failStart) throw IllegalStateException("dsh did not start")
        completedStarts++
        return RunningDsh(1, 2, SessionToken.generate(), LaunchUrl("http://127.0.0.1:1/?token=x", 1))
    }
}

private class SettingsFakeVersions : SettingsVersions {
    var current = "0.2.1-rc.5"
    var previous: String? = "0.2.0"
    var failReads = false
    var update: UpdateOutcome = UpdateOutcome.UpToDate
    var rollbackOutcome: MainOps.RollbackOutcome = MainOps.RollbackOutcome.NoPrevious
    /** The verified version an update would install; null when already on it. */
    var target: String? = "0.2.2"
    var verified = listOf("0.2.1-rc.5")
    var updates = 0
    var rollbacks = 0
    @Volatile var gate: CompletableDeferred<Unit>? = null
    @Volatile var completedChecks = 0
    override fun current(): String = if (failReads) throw IllegalStateException("dsh has no current version link") else current
    override fun previous(): String? = if (failReads) throw java.io.IOException("unreadable") else previous
    override fun verified(): List<String> = verified
    override fun updateTarget(): String? = target
    override suspend fun checkUpdate(): UpdateOutcome { updates++; gate?.await(); completedChecks++; return update }
    override suspend fun rollback(): MainOps.RollbackOutcome { rollbacks++; gate?.await(); return rollbackOutcome }
}

@RunWith(RobolectricTestRunner::class)
// SDK 36, not the default 37: Compose UI tests idle through Espresso, which reflects on InputManager.getInstance(), absent from the Android 17 jar.
@Config(sdk = [36])
class SettingsScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val claudeKey = "sk-ant-api03-abcdefghijklmnopqrstuvwxyz"
    private val vault = SettingsFakeVault()
    private val prefs = InMemorySharedPreferences()
    private val store = AgentSettingsStore(prefs)
    private val meter = CostMeter(InMemorySharedPreferences(), PriceTable(), clock = MutableClock())
    private val runtime = SettingsFakeRuntime()
    private val running = MutableStateFlow(true)
    private val versions = SettingsFakeVersions()
    private var phantom = PhantomState.ENABLED
    private val teamsApplied = CopyOnWriteArrayList<Boolean>()
    private var teamsResult = true
    private val heartbeats = CopyOnWriteArrayList<Int>()
    private var developerOpened = 0
    private var errorLogExports = 0
    private var memoryExports = 0
    private var workspaceExports = 0
    private var exportFails = false
    private var closed = 0
    // The application's scope, outside every composition and Activity: this is what lets a job outlive the screen.
    private val jobsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = SettingsJobs(jobsScope, runtime)
    private val uiPrefs = app.dshmobile.ui.fog.UiPrefs(InMemorySharedPreferences())
    private val effects = app.dshmobile.ui.fog.EffectsController(uiPrefs)
    private val effectCalls = CopyOnWriteArrayList<app.dshmobile.ui.fog.EffectLevel>()
    /** Whether a conversation or subagent runs (the shell's session tree in the app). */
    @Volatile private var working = false

    @After fun cancelJobs() {
        runtime.gate?.complete(Unit)
        versions.gate?.complete(Unit)
        jobsScope.cancel()
    }

    private fun str(id: Int, vararg args: Any) = rule.activity.getString(id, *args)

    private val env by lazy {
        SettingsEnv(
            vault = vault, store = store, meter = meter,
            phantomState = { phantom },
            runtime = runtime, isRunning = running, versions = versions,
            applyAgentTeams = { runtime.events += "teams"; teamsApplied += it; teamsResult },
            scheduleHeartbeat = { heartbeats += it },
            supervise = { runtime.events += "supervise" },
            openDeveloperSettings = { developerOpened++ },
            exportErrorLog = { if (exportFails) error("zip failed") else errorLogExports++ },
            exportMemory = { memoryExports++ },
            exportWorkspace = { workspaceExports++ },
            jobs = jobs,
            effectLevel = effects.level,
            onEffectLevel = { effectCalls += it; effects.set(it) },
            effectiveLevel = effects.effective,
            workRunning = { working },
        )
    }

    private fun show(onClose: (() -> Unit)? = { closed++ }) = rule.setContent {
        SakuraTheme { SettingsScreen(env, onClose = onClose) }
    }

    private fun click(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo().performClick()
    }

    /** Clicks an action that runs in the background and waits until the busy marker is gone again. */
    private fun clickAndWait(tag: String) {
        click(tag)
        waitIdle()
    }

    private fun waitIdle() = rule.waitUntil(5_000) { rule.onAllNodesWithTag("busy").fetchSemanticsNodes().isEmpty() }

    private fun waitForText(text: String) =
        rule.waitUntil(5_000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun waitFor(condition: () -> Boolean) = rule.waitUntil(5_000) { condition() }

    private val isKeyField = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)

    // Text and EditableText are what is displayed and read out; the field's raw InputText semantic exists for tests only.
    private fun showsText(needle: String) = SemanticsMatcher("displays $needle") { node ->
        val shown = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            listOfNotNull(node.config.getOrNull(SemanticsProperties.EditableText)?.text)
        shown.any { it.contains(needle) }
    }

    private fun assertNeverShown(needle: String) =
        assertTrue("shown as text: $needle", rule.onAllNodes(showsText(needle)).fetchSemanticsNodes().isEmpty())

    // ---------- models ----------

    @Test fun withoutAClaudeKeyTheModelListHasNoClaudeOption() {
        show()
        rule.onNodeWithTag("model-deepseek-flash").assertExists()
        rule.onNodeWithTag("model-deepseek-v4-pro").assertExists()
        rule.onNodeWithTag("model-claude-sonnet-5-5").assertDoesNotExist()
        rule.onAllNodesWithText("claude-", substring = true, ignoreCase = true).assertCountEquals(0)
    }

    @Test fun withAClaudeKeyTheClaudeModelsAppear() {
        vault.keys[Provider.ANTHROPIC] = claudeKey
        show()
        listOf("claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-5-5").forEach { rule.onNodeWithTag("model-$it").assertExists() }
    }

    @Test fun theNoteSaysSubagentsFollowTheLeadModel() {
        show()
        rule.onNodeWithText(str(R.string.set_agent_worker_note)).assertExists()
    }

    @Test fun savingAClaudeKeyAddsTheClaudeModelsAndClearingRemovesThem() {
        show()
        click("replace-claude")
        rule.onNode(isKeyField).performTextInput("  $claudeKey  ")
        clickAndWait("save-key")
        assertEquals(claudeKey, vault.keys[Provider.ANTHROPIC]) // trimmed
        rule.onNodeWithTag("model-claude-opus-5-5").assertExists()
        clickAndWait("clear-claude")
        waitFor { vault.keys[Provider.ANTHROPIC] == null }
        rule.onNodeWithTag("model-claude-opus-5-5").assertDoesNotExist()
    }

    @Test fun aSelectedClaudeModelFallsBackWhenTheKeyIsCleared() {
        vault.keys[Provider.ANTHROPIC] = claudeKey
        store.save(AgentSettings(maxConcurrent = 6, leadModel = "claude-opus-5-5"))
        show()
        clickAndWait("clear-claude")
        waitFor { vault.keys[Provider.ANTHROPIC] == null }
        clickAndWait("apply")
        waitFor { "ensureRunning" in runtime.events }
        waitFor { store.load(PhantomState.ENABLED, false).leadModel == "deepseek-flash" }
    }

    // ---------- effect strength, novel model, verified versions ----------

    private fun isSelected(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true

    @Test fun effectStrengthDefaultsToFullAndSavesAtOnce() {
        show()
        assertTrue(isSelected(Tags.EFFECT + 0))
        assertTrue("showing the choice is not a change", effectCalls.isEmpty())
        click(Tags.EFFECT + 2)
        waitFor { uiPrefs.effectLevel == app.dshmobile.ui.fog.EffectLevel.SAVER }
        assertEquals(listOf(app.dshmobile.ui.fog.EffectLevel.SAVER), effectCalls.toList())
        rule.waitForIdle()
        assertTrue(isSelected(Tags.EFFECT + 2))
        assertFalse("no restart", "stop" in runtime.events)
    }

    @Test fun theEffectControlFollowsTheAppsLevelAndNeverReappliesIt() {
        effects.set(app.dshmobile.ui.fog.EffectLevel.BALANCED)
        show()
        assertTrue(isSelected(Tags.EFFECT + 1))
        // A recomposition (and a restored draft) must not call set(): it would reset the jank governor's cap.
        click("inc-concurrent")
        rule.waitForIdle()
        assertTrue(effectCalls.isEmpty())
    }

    @Test fun novelModelListsOnlyAvailableModelsAndSaves() {
        show()
        rule.onNodeWithTag(Tags.NOVEL_MODEL + "deepseek-flash").assertExists()
        rule.onNodeWithTag(Tags.NOVEL_MODEL + "deepseek-v4-pro").assertExists()
        rule.onNodeWithTag(Tags.NOVEL_MODEL + "claude-sonnet-5-5").assertDoesNotExist()
        // the stored default is Sonnet; without a Claude key it shows as what it resolves to
        assertTrue(isSelected(Tags.NOVEL_MODEL + "deepseek-flash"))
        click(Tags.NOVEL_MODEL + "deepseek-v4-pro")
        assertEquals("deepseek-v4-pro", store.novelModel)
        assertTrue("no restart", runtime.events.isEmpty())
    }

    @Test fun withAClaudeKeyTheNovelModelDefaultsToSonnetAndCanBeChanged() {
        vault.keys[Provider.ANTHROPIC] = claudeKey
        show()
        assertTrue(isSelected(Tags.NOVEL_MODEL + "claude-sonnet-5-5"))
        click(Tags.NOVEL_MODEL + "claude-opus-5-5")
        assertEquals("claude-opus-5-5", store.novelModel)
        assertTrue(runtime.events.isEmpty())
    }

    @Test fun theVerifiedListIsShown() {
        versions.verified = listOf("0.2.1-rc.4", "0.2.1-rc.5")
        show()
        rule.onNodeWithTag(Tags.VERIFIED_NOTE).performScrollTo().assertTextContains("0.2.1-rc.4, 0.2.1-rc.5", substring = true)
        rule.onNodeWithTag(Tags.UNVERIFIED_WARNING).assertDoesNotExist()
    }

    @Test fun anUnverifiedCurrentVersionWarns() {
        versions.current = "0.2.2"
        show()
        rule.onNodeWithTag(Tags.UNVERIFIED_WARNING).performScrollTo().assertTextContains("0.2.2", substring = true).assertTextContains("0.2.1-rc.5", substring = true)
    }

    @Test fun rollbackIsDisabledWhenThereIsNoVerifiedPrevious() {
        versions.previous = null // SettingsEnv's previous() is already filtered by VerifiedUpdatePolicy (pinned in MainActivityTest)
        show()
        rule.onNodeWithTag("rollback").performScrollTo().assertIsNotEnabled()
    }

    // ---------- keys ----------

    @Test fun keyStatusShowsConfiguredOrNot() {
        show()
        rule.onAllNodesWithText(str(R.string.set_key_set)).assertCountEquals(1)
        rule.onAllNodesWithText(str(R.string.set_key_unset)).assertCountEquals(1)
        rule.onNodeWithTag("clear-claude").assertIsNotEnabled()
        rule.onNodeWithTag("clear-deepseek").assertIsEnabled()
    }

    @Test fun keyFieldIsAPasswordFieldAndTheKeyIsNeverShown() {
        show()
        click("replace-deepseek")
        rule.onNode(isKeyField).performTextInput(claudeKey)
        assertNeverShown(claudeKey)
    }

    @Test fun replacingAKeyStoresTheTrimmedKeyAndClearsTheField() {
        show()
        click("replace-deepseek")
        rule.onNode(isKeyField).performTextInput("  sk-replacement-key-0123456789 ")
        clickAndWait("save-key")
        waitFor { vault.keys[Provider.DEEPSEEK] == "sk-replacement-key-0123456789" }
        rule.onAllNodesWithText("sk-replacement", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText(str(R.string.set_key_save)).assertCountEquals(0) // the editor closed
    }

    @Test fun aTooShortKeyCannotBeSaved() {
        show()
        click("replace-deepseek")
        rule.onNode(isKeyField).performTextInput("sk-12")
        rule.onNodeWithTag("save-key").assertIsNotEnabled()
        rule.onNode(isKeyField).performTextClearance()
        rule.onNode(isKeyField).performTextInput(" ".repeat(40))
        rule.onNodeWithTag("save-key").assertIsNotEnabled()
    }

    @Test fun aVaultFailureShowsAGenericErrorNeverTheKey() {
        vault.failPut = true
        show()
        click("replace-deepseek")
        rule.onNode(isKeyField).performTextInput(claudeKey)
        clickAndWait("save-key")
        waitForText(str(R.string.set_key_error))
        assertNeverShown("keystore")
        assertNeverShown(claudeKey)
    }

    @Test fun cancelLeavesTheKeyAlone() {
        show()
        click("replace-deepseek")
        rule.onNode(isKeyField).performTextInput(claudeKey)
        click("cancel-key")
        assertEquals("sk-deepseek-0123456789", vault.keys[Provider.DEEPSEEK])
        rule.onAllNodesWithText(str(R.string.set_key_save)).assertCountEquals(0)
    }

    // ---------- agent ----------

    @Test fun steppersChangeTheValuesWithinTheirRanges() {
        show()
        fun label(res: Int, v: Int) = "${str(res)}：$v"
        rule.onNodeWithText(label(R.string.set_agent_concurrent, 6)).assertExists()
        click("inc-concurrent")
        rule.onNodeWithText(label(R.string.set_agent_concurrent, 7)).assertExists()
        repeat(40) { click("inc-concurrent") }
        rule.onNodeWithText(label(R.string.set_agent_concurrent, 32)).assertExists()
        repeat(40) { click("dec-depth") }
        rule.onNodeWithText(label(R.string.set_agent_depth, 0)).assertExists()
        repeat(40) { click("dec-team") }
        rule.onNodeWithText(label(R.string.set_agent_team, 1)).assertExists()
    }

    @Test fun teamSizeAboveConcurrencyIsFlagged() {
        store.save(AgentSettings(maxConcurrent = 3, teamSize = 8, leadModel = "deepseek-flash"))
        show()
        rule.onNodeWithText(str(R.string.set_agent_team_clamped, 3)).assertExists()
    }

    @Test fun phantomStateChoosesTheStartingConcurrency() {
        phantom = PhantomState.DISABLED
        show()
        rule.onNodeWithText("${str(R.string.set_agent_concurrent)}：16").assertExists()
    }

    // ---------- apply ----------

    @Test fun applyStopsThenEnsureRunning() {
        show()
        clickAndWait("apply")
        waitFor { runtime.events.size >= 4 }
        // the profile file (agent teams bundle) is edited only while dsh is stopped
        assertEquals(listOf("stop", "teams", "supervise", "ensureRunning"), runtime.events.toList())
        waitForText(str(R.string.set_apply_done))
    }

    @Test fun applySavesTheChosenSettingsAndTellsTheWriterAboutAgentTeams() {
        show()
        click("inc-concurrent")                 // 6 -> 7
        click("dec-team")                       // 8 -> 7
        click("inc-depth")                      // 2 -> 3
        click("model-deepseek-v4-pro")
        click("switch-teams")                   // on -> off
        clickAndWait("apply")
        waitFor { "ensureRunning" in runtime.events }
        assertEquals(
            AgentSettings(maxConcurrent = 7, maxDepth = 3, teamSize = 7, leadModel = "deepseek-v4-pro", agentTeams = false),
            store.load(PhantomState.ENABLED, false),
        )
        assertEquals(listOf(false), teamsApplied)
    }

    @Test fun nothingIsSavedUntilApply() {
        show()
        click("inc-concurrent")
        assertEquals(AgentSettings.defaults(PhantomState.ENABLED, false), store.load(PhantomState.ENABLED, false))
    }

    @Test fun agentTeamsThatCannotBeAppliedYetSaysSo() {
        teamsResult = false
        show()
        clickAndWait("apply")
        waitForText(str(R.string.set_apply_teams_later))
    }

    @Test fun buttonsAreDisabledWithProgressWhileApplying() {
        runtime.gate = CompletableDeferred()
        show()
        click("apply")
        waitFor { "ensureRunning" in runtime.events }
        rule.onNodeWithTag("apply").assertIsNotEnabled()
        rule.onNodeWithTag("check-update").assertIsNotEnabled()
        rule.onNodeWithTag("export-log").assertIsNotEnabled()
        rule.onNodeWithTag("replace-deepseek").assertIsNotEnabled()
        rule.onNodeWithTag("busy").assertExists()
        runtime.gate!!.complete(Unit)
        waitForText(str(R.string.set_apply_done))
        rule.onNodeWithTag("apply").assertIsEnabled()
        rule.onNodeWithTag("busy").assertDoesNotExist()
    }

    @Test fun aFailedRestartIsReportedAndOffersARestart() {
        runtime.failStart = true
        show()
        running.value = false
        clickAndWait("apply")
        waitForText(str(R.string.set_apply_failed))
        rule.onNodeWithTag("restart").assertExists()
    }

    // ---------- leaving the screen mid-action ----------

    private fun destroyActivity() {
        rule.activityRule.scenario.moveToState(Lifecycle.State.DESTROYED)
    }

    @Test fun closeIsDisabledWhileBusyAndBackDoesNotLeave() {
        runtime.gate = CompletableDeferred()
        show()
        rule.onNodeWithTag("close").assertIsEnabled()
        click("apply")
        waitFor { "ensureRunning" in runtime.events }
        rule.onNodeWithTag("close").assertIsNotEnabled()
        var finishing = true
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed(); finishing = it.isFinishing }
        assertFalse("back must be swallowed while busy", finishing)
        runtime.gate!!.complete(Unit)
        waitIdle()
        rule.onNodeWithTag("close").assertIsEnabled()
    }

    @Test fun anApplyFinishesAndDshRunsAfterTheActivityIsDestroyedMidAction() {
        runtime.gate = CompletableDeferred()
        val done = str(R.string.set_apply_done)
        show()
        click("apply")
        waitFor { "ensureRunning" in runtime.events } // stop() is done, the start is pending
        destroyActivity()
        runtime.gate!!.complete(Unit)
        waitFor { !jobs.busy.value }
        assertEquals(listOf("stop", "teams", "supervise", "ensureRunning"), runtime.events.toList())
        assertEquals(1, runtime.completedStarts)
        assertEquals(done, jobs.applyMessage.value)
    }

    @Test fun anUpdateFinishesAfterTheActivityIsDestroyedMidAction() {
        versions.gate = CompletableDeferred()
        versions.update = UpdateOutcome.Updated("0.3.0")
        val updated = str(R.string.set_version_updated, "0.3.0")
        show()
        click("check-update")
        waitFor { versions.updates == 1 }
        destroyActivity()
        versions.gate!!.complete(Unit)
        waitFor { !jobs.busy.value }
        assertEquals(1, versions.completedChecks)
        assertEquals(updated, jobs.versionMessage.value)
        assertTrue("dsh is running after the update, so it is supervised", "supervise" in runtime.events)
    }

    @Test fun aRollbackFinishesAfterTheActivityIsDestroyedMidAction() {
        versions.gate = CompletableDeferred()
        versions.rollbackOutcome = MainOps.RollbackOutcome.RolledBack("0.2.0", RunningDsh(1, 2, SessionToken.generate(), LaunchUrl("http://127.0.0.1:1/?token=x", 1)))
        val rolled = str(R.string.set_rollback_done, "0.2.0")
        show()
        click("rollback")
        waitFor { versions.rollbacks == 1 }
        destroyActivity()
        versions.gate!!.complete(Unit)
        waitFor { !jobs.busy.value }
        assertEquals(rolled, jobs.versionMessage.value)
    }

    @Test fun aRestartFinishesAfterTheActivityIsDestroyedMidAction() {
        runtime.gate = CompletableDeferred()
        running.value = false
        show()
        click("restart")
        waitFor { "ensureRunning" in runtime.events }
        destroyActivity()
        runtime.gate!!.complete(Unit)
        waitFor { !jobs.busy.value }
        assertEquals(1, runtime.completedStarts)
    }

    @Test fun aReopenedScreenShowsAJobThatIsStillRunning() {
        val gate = CompletableDeferred<Unit>()
        jobs.launch { gate.await() } // started by an earlier screen
        show()
        rule.onNodeWithTag("busy").assertExists()
        rule.onNodeWithTag("apply").assertIsNotEnabled()
        rule.onNodeWithTag("check-update").assertIsNotEnabled()
        rule.onNodeWithTag("close").assertIsNotEnabled()
        gate.complete(Unit)
        waitIdle()
        rule.onNodeWithTag("apply").assertIsEnabled()
    }

    @Test fun theResultOfAnEarlierJobIsShownWhenTheScreenOpens() {
        jobs.applyMessage.value = "earlier result"
        jobs.versionMessage.value = "earlier version result"
        show()
        waitForText("earlier result")
        waitForText("earlier version result")
    }

    @Test fun onlyOneJobRunsAtATime() {
        val gate = CompletableDeferred<Unit>()
        assertTrue(jobs.launch { gate.await() })
        assertFalse(jobs.launch { error("never") })
        gate.complete(Unit)
        waitFor { !jobs.busy.value }
        assertTrue(jobs.launch { })
    }

    // ---------- supervision after update / rollback ----------

    @Test fun updateWithDshRunningHandsItToTheService() {
        running.value = true
        show()
        clickAndWait("check-update")
        assertEquals(listOf("supervise"), runtime.events.toList())
    }

    @Test fun updateWithDshDownDoesNotSuperviseAndOffersARestart() {
        running.value = false
        versions.update = UpdateOutcome.RolledBack("0.3.0", "the new version failed; switching back to 0.2.0 failed: disk full")
        show()
        clickAndWait("check-update")
        assertTrue(runtime.events.isEmpty())
        rule.onNodeWithTag("restart").assertExists()
    }

    @Test fun rollbackWithDshRunningHandsItToTheService() {
        running.value = true
        versions.rollbackOutcome = MainOps.RollbackOutcome.RolledBack("0.2.0", RunningDsh(1, 2, SessionToken.generate(), LaunchUrl("http://127.0.0.1:1/?token=x", 1)))
        show()
        clickAndWait("rollback")
        assertEquals(listOf("supervise"), runtime.events.toList())
    }

    @Test fun rollbackWithDshDownDoesNotSupervise() {
        running.value = false
        versions.rollbackOutcome = MainOps.RollbackOutcome.Failed("could not switch")
        show()
        clickAndWait("rollback")
        assertTrue(runtime.events.isEmpty())
        rule.onNodeWithTag("restart").assertExists()
    }

    // ---------- budget ----------

    @Test fun budgetShowsTodaysSpendAndSavesThresholdAndCap() {
        meter.record(Provider.DEEPSEEK, "deepseek-flash", Usage(inputTokens = 0, outputTokens = 1_000_000))
        show()
        val spent = String.format(Locale.US, "%.4f", meter.todayUsd())
        rule.onNodeWithText(str(R.string.set_budget_today, spent)).assertExists()
        assertNull(meter.hardCapUsd)
        rule.onNodeWithTag("alert-field").performScrollTo().performTextClearance()
        rule.onNodeWithTag("alert-field").performTextInput("2.5")
        click("switch-cap")
        rule.onNodeWithTag("cap-field").performScrollTo().performTextClearance()
        rule.onNodeWithTag("cap-field").performTextInput("12")
        click("save-budget")
        assertEquals(2.5, meter.alertUsd, 0.0)
        assertEquals(12.0, meter.hardCapUsd!!, 0.0)
        waitForText(str(R.string.set_budget_saved))
    }

    @Test fun switchingTheCapOffRemovesIt() {
        meter.hardCapUsd = 8.0
        show()
        click("switch-cap")
        click("save-budget")
        assertNull(meter.hardCapUsd)
    }

    @Test fun invalidBudgetNumbersAreRejectedAndNothingChanges() {
        meter.alertUsd = 5.0
        show()
        for (bad in listOf("abc", "0", "-3", "NaN", "Infinity")) {
            rule.onNodeWithTag("alert-field").performScrollTo().performTextClearance()
            rule.onNodeWithTag("alert-field").performTextInput(bad)
            click("save-budget")
            waitForText(str(R.string.set_budget_invalid))
            assertEquals(bad, 5.0, meter.alertUsd, 0.0)
        }
    }

    // ---------- heartbeat ----------

    @Test fun heartbeatIntervalStepsInFiveAndSchedules() {
        show()
        rule.onNodeWithText(str(R.string.set_heartbeat_value, 45)).assertExists()
        click("inc-heartbeat")
        assertEquals(listOf(50), heartbeats.toList())
        assertEquals(50, store.heartbeatIntervalMin)
        repeat(5) { click("inc-heartbeat") }
        assertEquals(60, store.heartbeatIntervalMin)
        rule.onNodeWithText(str(R.string.set_heartbeat_value, 60)).assertExists()
        repeat(10) { click("dec-heartbeat") }
        assertEquals(30, store.heartbeatIntervalMin)
        assertEquals(30, heartbeats.last())
    }

    // ---------- versions ----------

    @Test fun versionSectionShowsCurrentAndPrevious() {
        show()
        rule.onNodeWithText(str(R.string.set_version_current, "0.2.1-rc.5")).assertExists()
        rule.onNodeWithText(str(R.string.set_version_previous, "0.2.0")).assertExists()
        rule.onNodeWithTag("rollback").assertIsEnabled()
    }

    @Test fun unreadableVersionLinksDoNotStopTheScreenFromOpening() {
        versions.failReads = true
        show()
        rule.onNodeWithText(str(R.string.set_version_current, str(R.string.set_version_unknown))).assertExists()
        rule.onNodeWithTag("rollback").assertIsNotEnabled()
        rule.onNodeWithTag("apply").assertExists()
    }

    @Test fun noPreviousVersionDisablesRollback() {
        versions.previous = null
        show()
        rule.onNodeWithText(str(R.string.set_version_previous, str(R.string.set_version_none))).assertExists()
        rule.onNodeWithTag("rollback").assertIsNotEnabled()
    }

    @Test fun updateOutcomesAreShown() {
        show()
        versions.update = UpdateOutcome.UpToDate
        clickAndWait("check-update")
        waitForText(str(R.string.set_version_uptodate, "0.2.1-rc.5"))
        versions.update = UpdateOutcome.Updated("0.3.0")
        clickAndWait("check-update")
        waitForText(str(R.string.set_version_updated, "0.3.0"))
        versions.update = UpdateOutcome.RolledBack("0.2.1-rc.5", "health check failed")
        clickAndWait("check-update")
        waitForText(str(R.string.set_version_rolled_back, "0.2.1-rc.5", "health check failed"))
        versions.update = UpdateOutcome.Failed("npm exploded")
        clickAndWait("check-update")
        waitForText(str(R.string.set_version_failed, "npm exploded"))
        assertEquals(4, versions.updates)
    }

    @Test fun dshStatusFollowsIsRunningAndAStoppedDshOffersARestart() {
        show()
        rule.onNodeWithText(str(R.string.set_dsh_running)).assertExists()
        rule.onNodeWithTag("restart").assertDoesNotExist()
        // an update result whose switch back failed leaves dsh stopped
        running.value = false
        versions.update = UpdateOutcome.RolledBack("0.2.0", "the new version failed; switching back to 0.2.0 failed: disk full")
        clickAndWait("check-update")
        waitForText("switching back to 0.2.0 failed")
        rule.onNodeWithText(str(R.string.set_dsh_stopped)).assertExists()
        clickAndWait("restart")
        waitFor { runtime.events.toList() == listOf("supervise", "ensureRunning") }
        assertFalse(runtime.events.contains("stop"))
    }

    @Test fun rollbackRunsAndReportsTheVersion() {
        versions.rollbackOutcome = MainOps.RollbackOutcome.RolledBack("0.2.0", RunningDsh(1, 2, SessionToken.generate(), LaunchUrl("http://127.0.0.1:1/?token=x", 1)))
        show()
        clickAndWait("rollback")
        waitForText(str(R.string.set_rollback_done, "0.2.0"))
        assertEquals(1, versions.rollbacks)
    }

    @Test fun rollbackFailureAndNothingToRollBackAreReported() {
        versions.rollbackOutcome = MainOps.RollbackOutcome.Failed("could not switch")
        show()
        clickAndWait("rollback")
        waitForText(str(R.string.set_rollback_failed))
        versions.rollbackOutcome = MainOps.RollbackOutcome.NoPrevious
        clickAndWait("rollback")
        waitForText(str(R.string.set_rollback_none))
    }

    // ---------- phantom, export, close ----------

    @Test fun phantomStateIsShownWithAButtonToDeveloperOptions() {
        phantom = PhantomState.DISABLED
        show()
        rule.onNodeWithText(str(R.string.set_phantom_state, str(R.string.onb_phantom_state_disabled))).assertExists()
        click("open-developer")
        assertEquals(1, developerOpened)
    }

    @Test fun phantomStateIsReReadWhenTheScreenResumes() {
        phantom = PhantomState.ENABLED
        show()
        rule.onNodeWithText(str(R.string.set_phantom_state, str(R.string.onb_phantom_state_enabled))).assertExists()
        phantom = PhantomState.DISABLED
        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.set_phantom_state, str(R.string.onb_phantom_state_disabled))).assertExists()
    }

    @Test fun exportButtonsRunTheExports() {
        show()
        clickAndWait("export-log")
        waitFor { errorLogExports == 1 }
        clickAndWait("export-memory")
        waitFor { memoryExports == 1 }
        rule.onNodeWithText(str(R.string.set_export_workspace)).assertExists()
        clickAndWait("export-workspace")
        waitFor { workspaceExports == 1 }
    }

    @Test fun aFailingExportIsReported() {
        exportFails = true
        show()
        clickAndWait("export-log")
        waitForText(str(R.string.main_export_failed))
        rule.onNodeWithTag("busy").assertDoesNotExist()
    }

    @Test fun withoutOnCloseThereIsNoCloseButton() {
        show(onClose = null) // embedded in the shell's settings tab: the dock is the way out, the header names the page
        rule.onNodeWithTag("close").assertDoesNotExist()
        rule.onNodeWithTag("settings-title").assertDoesNotExist()
        rule.onNodeWithTag("apply").assertExists()
    }

    @Test fun withOnCloseTheCloseButtonCloses() {
        show()
        rule.onNodeWithTag("settings-title").assertExists()
        click("close")
        assertEquals(1, closed)
    }

    /** The shell disposes the settings tab when another tab is chosen: unsaved fields live in the host's draft and come back. */
    @Test fun unsavedFieldsSurviveTheScreenLeavingAndComingBack() {
        val draft = SettingsDraft.from(env)
        var visible by mutableStateOf(true)
        val alertBefore = meter.alertUsd
        val teamsBefore = draft.settings.agentTeams
        rule.setContent { SakuraTheme { if (visible) SettingsScreen(env, draft = draft) } }
        rule.onNodeWithTag("alert-field").performScrollTo().performTextReplacement("7.5")
        click("switch-teams")
        rule.onNodeWithTag("replace-claude").performScrollTo().performClick()
        rule.onNodeWithTag("key-field").performTextReplacement("sk-ant-half-typed")
        visible = false
        rule.waitForIdle()
        rule.onNodeWithTag("alert-field").assertDoesNotExist()
        visible = true
        rule.waitForIdle()
        rule.onNodeWithTag("alert-field").assertTextContains("7.5")
        assertEquals(!teamsBefore, draft.settings.agentTeams)
        rule.onNodeWithTag("switch-teams").assertIsToggleable().apply { if (teamsBefore) assertIsOff() else assertIsOn() }
        rule.onNodeWithTag("key-field").assertExists() // the key editor is still open (its text is never shown)
        assertEquals("sk-ant-half-typed", draft.keyInput)
        assertEquals("nothing was saved", alertBefore, meter.alertUsd, 0.0)
        assertEquals("nothing was saved", teamsBefore, store.load(phantom, false).agentTeams)
    }

    // ---------- final review B2-I4: a running conversation is not cut off without asking ----------

    private fun hasTag(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun applyWhileWorkRunsAsksFirstAndWaitingChangesNothing() {
        working = true
        show()
        click("apply")
        rule.waitForIdle()
        assertTrue(hasTag(Tags.CONFIRM_CARD))
        rule.onNodeWithText(str(R.string.set_confirm_apply)).assertExists()
        rule.onNodeWithText(str(R.string.set_confirm_apply_go)).assertExists()
        assertTrue("nothing stopped yet", runtime.events.isEmpty())
        click(Tags.CONFIRM_WAIT)
        rule.waitForIdle()
        assertFalse(hasTag(Tags.CONFIRM_CARD))
        Thread.sleep(100)
        assertTrue(runtime.events.isEmpty())
    }

    @Test fun applyAnywayStopsAndRestarts() {
        working = true
        show()
        click("apply")
        click(Tags.CONFIRM_GO)
        waitFor { "ensureRunning" in runtime.events }
        assertEquals(listOf("stop", "teams", "supervise", "ensureRunning"), runtime.events.toList())
    }

    @Test fun updateAndRollbackAskWithTheirOwnVerb() {
        working = true
        show()
        click("check-update")
        rule.onNodeWithText(str(R.string.set_confirm_update)).assertExists()
        rule.onNodeWithText(str(R.string.set_confirm_update_go)).assertExists()
        assertEquals(0, versions.updates)
        click(Tags.CONFIRM_GO)
        waitFor { versions.updates == 1 }
        waitIdle()
        click("rollback")
        rule.onNodeWithText(str(R.string.set_confirm_rollback)).assertExists()
        rule.onNodeWithText(str(R.string.set_confirm_rollback_go)).assertExists()
        assertEquals(0, versions.rollbacks)
        click(Tags.CONFIRM_GO)
        waitFor { versions.rollbacks == 1 }
    }

    @Test fun withoutWorkNothingIsAsked() {
        show()
        clickAndWait("check-update")
        assertFalse(hasTag(Tags.CONFIRM_CARD))
        assertEquals(1, versions.updates)
    }

    // ---------- final review B2-M2 / B2-M3 ----------

    @Test fun tappingTheSelectedEffectCellChangesNothing() {
        show()
        click(Tags.EFFECT + 0)
        rule.waitForIdle()
        assertTrue("re-tapping must not reset the governor", effectCalls.isEmpty())
    }

    @Test fun anAutomaticallyLoweredLevelIsShown() {
        show()
        assertFalse(hasTag(Tags.EFFECT_LOWERED))
        rule.runOnIdle {
            effects.governor.onStart()
            while (effects.effective.value == app.dshmobile.ui.fog.EffectLevel.FULL) effects.governor.onFrame(true)
        }
        waitFor { hasTag(Tags.EFFECT_LOWERED) }
        rule.onNodeWithText(str(R.string.set_effect_lowered, str(R.string.set_effect_balanced))).assertExists()
    }

    @Test fun leadModelsShowTheirNamesAndAnUnappliedChoiceSaysSo() {
        show()
        rule.onNodeWithText("deepseek-v4-pro").assertDoesNotExist()
        assertFalse(hasTag(Tags.LEAD_UNAPPLIED))
        click("model-deepseek-v4-pro")
        rule.waitForIdle()
        assertTrue(hasTag(Tags.LEAD_UNAPPLIED))
        clickAndWait("apply")
        waitFor { "ensureRunning" in runtime.events }
        rule.waitForIdle()
        assertFalse(hasTag(Tags.LEAD_UNAPPLIED))
    }

    /** Residual review: up to date, an update stops nothing, so it asks nothing even while work runs. */
    @Test fun anUpdateWithNothingToInstallNeverAsks() {
        working = true
        versions.target = null
        show()
        clickAndWait("check-update")
        assertFalse(hasTag(Tags.CONFIRM_CARD))
        assertEquals(1, versions.updates)
        waitForText(str(R.string.set_version_uptodate, "0.2.1-rc.5"))
        assertFalse("stop" in runtime.events)
    }
}
