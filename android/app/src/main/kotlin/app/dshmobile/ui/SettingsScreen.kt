package app.dshmobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dshmobile.R
import app.dshmobile.config.AgentModels
import app.dshmobile.config.AgentSettings
import app.dshmobile.config.AgentSettingsStore
import app.dshmobile.config.displayName
import app.dshmobile.gateway.CostMeter
import app.dshmobile.gateway.Provider
import app.dshmobile.heartbeat.HeartbeatScheduler
import app.dshmobile.runtime.UpdateOutcome
import app.dshmobile.security.KeyVault
import app.dshmobile.service.DshRuntime
import app.dshmobile.system.PhantomState
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogButton
import app.dshmobile.ui.fog.FogButtonKind
import app.dshmobile.ui.fog.FogDimens
import app.dshmobile.ui.fog.FogPanel
import app.dshmobile.ui.fog.glass
import app.dshmobile.ui.fog.Segmented
import app.dshmobile.ui.shell.Tags
import app.dshmobile.ui.theme.Sakura
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.util.Locale

/** What the version section needs from `DshVersions` and `MainOps`; the production implementation is in `buildSettingsEnv`. */
interface SettingsVersions {
    fun current(): String
    fun previous(): String?
    /** The dsh versions this app release has verified; the last is the newest. */
    fun verified(): List<String>
    /** The verified version an update would install; null when dsh is already on it (an update then stops nothing). */
    fun updateTarget(): String?
    suspend fun checkUpdate(): UpdateOutcome
    suspend fun rollback(): MainOps.RollbackOutcome
}

/** Everything [SettingsScreen] touches, passed in so tests drive it with fakes. */
class SettingsEnv(
    val vault: KeyVault,
    val store: AgentSettingsStore,
    val meter: CostMeter,
    val phantomState: () -> PhantomState,
    val runtime: DshRuntime,
    val isRunning: StateFlow<Boolean>,
    val versions: SettingsVersions,
    /** `AgentConfigWriter.ensureAgentTeamsBundle`: true when the profile already says what was asked, false = after dsh's next start. */
    val applyAgentTeams: (enabled: Boolean) -> Boolean,
    /** `HeartbeatScheduler.schedule(context, minutes)`. */
    val scheduleHeartbeat: (minutes: Int) -> Unit,
    /** The user explicitly (re)starts dsh: ends a service give-up and hands crash supervision to `DshService`. */
    val supervise: () -> Unit,
    val openDeveloperSettings: () -> Unit,
    /** The exports build a zip and open the share sheet; they run on `Dispatchers.IO`. */
    val exportErrorLog: () -> Unit,
    val exportMemory: () -> Unit,
    /** Spec §7.1 "any workspace file can be exported": the whole workspace as one zip (symbolic links left out). */
    val exportWorkspace: () -> Unit,
    /** Owned by the application: the long dsh actions run there and outlive this screen. */
    val jobs: SettingsJobs,
    /** The effect strength chosen in Settings: takes effect at once (the app's `EffectsController.set`). */
    val onEffectLevel: (app.dshmobile.ui.fog.EffectLevel) -> Unit = {},
    /** The user's chosen level (`EffectsController.level`), what the effect control shows; not the governor-capped one. */
    val effectLevel: StateFlow<app.dshmobile.ui.fog.EffectLevel> = kotlinx.coroutines.flow.MutableStateFlow(app.dshmobile.ui.fog.EffectLevel.FULL),
    /** The level really drawn (`EffectsController.effective`): below [effectLevel] when the jank governor lowered it. */
    val effectiveLevel: StateFlow<app.dshmobile.ui.fog.EffectLevel> = effectLevel,
    /** Whether a conversation or subagent runs right now: apply, update and rollback stop dsh and would cut it off, so they ask. */
    val workRunning: () -> Boolean = { false },
    /** dsh's detailed status (stage 2 §A/§G); null hides the status panel. */
    val dshStatus: StateFlow<app.dshmobile.service.DshStatus>? = null,
    /** Opens this app's page in Android's settings (the force-stop button lives there). */
    val openAppSettings: () -> Unit = {},
    /** 「實驗：原生模式」; null hides the section. */
    val nativeMode: app.dshmobile.runtime.NativeModeControl? = null,
    /**
     * The Claude key was stored or removed: dsh only offers Claude models when it starts with the key, so it restarts now.
     * Returns false when work is running (nothing is cut off; Claude arrives with dsh's next start).
     */
    val onClaudeKeyChanged: () -> Boolean = { true },
)

private const val BUDGET_DEFAULT_CAP = 10.0

/**
 * What the user typed or chose in Settings and has not saved yet, and whether one of the screen's own actions (a key, an
 * export) runs. The host keeps it (the shell: one per screen), so switching tabs and back keeps unsaved fields. Memory only,
 * never saved instance state: a half-typed key must not land in a Bundle.
 */
class SettingsDraft(
    agent: AgentSettings,
    alertText: String,
    capEnabled: Boolean,
    capText: String,
) {
    var localBusy by mutableStateOf(false)
    var editing by mutableStateOf<Provider?>(null)
    var keyInput by mutableStateOf("")
    var keyError by mutableStateOf(false)
    var settings by mutableStateOf(agent)
    var alertText by mutableStateOf(alertText)
    var capEnabled by mutableStateOf(capEnabled)
    var capText by mutableStateOf(capText)
    internal var budgetMessage by mutableStateOf<BudgetMessage?>(null)
    var exportMessage by mutableStateOf<String?>(null)
    /** The lead model saved by the last apply (or found stored): a different choice in [settings] is not in effect yet. */
    var appliedLeadModel by mutableStateOf(agent.leadModel)
    /** The action waiting for the owner's yes because a conversation runs (final review B2-I4); null when nothing asks. */
    internal var confirming by mutableStateOf<StopAction?>(null)

    companion object {
        /** The stored values, as the screen shows them when nothing was edited yet (a presence check for the Claude key, no decrypt). */
        fun from(env: SettingsEnv): SettingsDraft = SettingsDraft(
            agent = env.store.load(env.phantomState(), env.vault.has(Provider.ANTHROPIC)),
            alertText = formatUsd(env.meter.alertUsd),
            capEnabled = env.meter.hardCapUsd != null,
            capText = formatUsd(env.meter.hardCapUsd ?: BUDGET_DEFAULT_CAP),
        )
    }
}
private const val HEARTBEAT_STEP_MIN = 5
private const val MAX_REASON_CHARS = 300

/**
 * Keys, agent settings, budget, heartbeat, dsh versions, the phantom-process limit, exports and "apply and restart". Anything
 * slow (vault, restart, update, export) runs off the main thread; while one runs every action is disabled and a progress bar
 * shows. Agent settings are only saved by [apply]; budget and heartbeat have their own save/step actions. Keys are only ever in
 * a password field and the vault, and never shown.
 *
 * With [onClose] it is a screen of its own (opaque, clear of the system bars, a close button); without it, it is embedded in
 * the shell's settings tab: transparent over the shell's background, no close button, laid out with [contentPadding] (the
 * shell's page padding, which already keeps it clear of the bars, the header and the dock).
 */
@Composable
fun SettingsScreen(
    env: SettingsEnv,
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    draft: SettingsDraft = remember(env) { SettingsDraft.from(env) },
) {
    val embedded = onClose == null
    val scope = rememberCoroutineScope() // keys and exports only: cancelling those half way is harmless
    var localBusy by draft::localBusy
    val jobBusy by env.jobs.busy.collectAsStateWithLifecycle()
    val busy = localBusy || jobBusy
    // Leaving the screen is not possible while something runs (the jobs would survive it, but the user should see the result).
    BackHandler(enabled = busy) {}

    // Presence checks only (no decrypt); everything that can block runs on IO.
    var hasDeepseek by remember { mutableStateOf(env.vault.has(Provider.DEEPSEEK)) }
    var hasClaude by remember { mutableStateOf(env.vault.has(Provider.ANTHROPIC)) }
    // The Claude key changed while work was running: dsh restarts with it later (shown under the key rows).
    var claudePending by remember { mutableStateOf(false) }
    var editing by draft::editing
    var keyInput by draft::keyInput
    var keyError by draft::keyError

    var settings by draft::settings
    val applyMessage by env.jobs.applyMessage.collectAsStateWithLifecycle()

    var todayUsd by remember { mutableStateOf(env.meter.todayUsd()) }
    var alertText by draft::alertText
    var capEnabled by draft::capEnabled
    var capText by draft::capText
    var budgetMessage by draft::budgetMessage

    var heartbeat by remember { mutableStateOf(env.store.heartbeatIntervalMin) }

    // DshVersions.current() throws when the `current` link is missing or malformed: the screen must still open.
    val unknownVersion = stringResource(R.string.set_version_unknown)
    fun readCurrent(): String = try { env.versions.current() } catch (_: Exception) { unknownVersion }
    fun readPrevious(): String? = try { env.versions.previous() } catch (_: Exception) { null }
    var currentVersion by remember { mutableStateOf(readCurrent()) }
    var previousVersion by remember { mutableStateOf(readPrevious()) }
    val versionMessage by env.jobs.versionMessage.collectAsStateWithLifecycle()
    val dshRunning by env.isRunning.collectAsStateWithLifecycle()
    val effectLevel by env.effectLevel.collectAsStateWithLifecycle()
    val effectiveLevel by env.effectiveLevel.collectAsStateWithLifecycle()
    val verified = remember { runCatching { env.versions.verified() }.getOrDefault(emptyList()) }
    var novelModel by remember { mutableStateOf(env.store.novelModel) }

    var phantom by remember { mutableStateOf(env.phantomState()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { phantom = env.phantomState() } // back from the system settings
    var exportMessage by draft::exportMessage
    var confirming by draft::confirming

    val context = LocalContext.current.applicationContext // (the jobs outlive the activity) for the messages built inside callbacks (stringResource is composable-only)

    fun launchBusy(block: suspend () -> Unit) {
        if (busy) return
        localBusy = true
        scope.launch {
            try {
                block()
            } finally {
                localBusy = false
            }
        }
    }

    // Versions on disk change when a job ends (update, rollback): read them again then.
    LaunchedEffect(jobBusy) {
        if (!jobBusy) {
            currentVersion = readCurrent()
            previousVersion = readPrevious()
        }
    }

    fun saveKey(provider: Provider) = launchBusy {
        val key = keyInput.trim()
        val ok = try {
            withContext(Dispatchers.IO) { env.vault.put(provider, key) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false // the message is generic on purpose: an exception text could carry the key
        }
        if (ok) {
            keyInput = ""
            keyError = false
            editing = null
            if (provider == Provider.DEEPSEEK) hasDeepseek = true else hasClaude = true
            if (provider == Provider.ANTHROPIC) claudePending = !env.onClaudeKeyChanged()
        } else {
            keyError = true
        }
    }

    fun clearKey(provider: Provider) = launchBusy {
        try {
            withContext(Dispatchers.IO) { env.vault.clear(provider) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            keyError = true
            return@launchBusy
        }
        if (editing == provider) { editing = null; keyInput = "" }
        if (provider == Provider.DEEPSEEK) hasDeepseek = false else hasClaude = false
        if (provider == Provider.ANTHROPIC) claudePending = !env.onClaudeKeyChanged()
    }

    /** After an update or rollback: dsh that is up again goes back under DshService (clears a give-up); a dsh that is down stays as it is. */
    fun superviseIfRunning() {
        if (env.isRunning.value) runCatching { env.supervise() }
    }

    /** Runs [action] now, or asks first while a conversation runs (final review B2-I4). */
    fun askOrRun(action: StopAction, run: () -> Unit) {
        if (busy) return
        if (runCatching { env.workRunning() }.getOrDefault(false)) confirming = action else run()
    }

    fun apply() {
        if (busy) return
        val toSave = settings.copy(leadModel = AgentModels.resolve(settings.leadModel, hasClaude).id)
        settings = toSave
        env.jobs.launch {
            env.jobs.applyMessage.value = null
            // Not cancellable: stop() followed by a cancelled start would leave dsh stopped.
            env.jobs.applyMessage.value = withContext(NonCancellable) {
                val failure = context.getString(R.string.set_apply_failed)
                val teamsNow = try {
                    env.store.save(toSave)
                    draft.appliedLeadModel = toSave.leadModel
                    env.runtime.stop()
                    // dsh's own profile file is only touched while dsh is stopped.
                    env.applyAgentTeams(toSave.agentTeams)
                } catch (_: Exception) {
                    return@withContext failure
                }
                try {
                    runCatching { env.supervise() }
                    env.runtime.ensureRunning()
                    val done = context.getString(R.string.set_apply_done)
                    if (toSave.agentTeams && !teamsNow) done + "\n" + context.getString(R.string.set_apply_teams_later) else done
                } catch (_: Exception) {
                    failure
                }
            }
        }
    }

    fun forceRestart() {
        env.jobs.launch {
            env.jobs.versionMessage.value = withContext(NonCancellable) {
                try {
                    if (!env.runtime.forceStop()) return@withContext context.getString(R.string.set_apply_failed)
                    env.runtime.ensureRunning()
                    null
                } catch (_: Exception) {
                    context.getString(R.string.set_apply_failed)
                }
            }
        }
    }

    fun restart() {
        if (busy) return
        env.jobs.launch {
            env.jobs.versionMessage.value = withContext(NonCancellable) {
                try {
                    runCatching { env.supervise() }
                    // A real restart (stage 2 §A): stop the whole tree first, then start; ensureRunning alone stopped nothing.
                    env.runtime.stop()
                    env.runtime.ensureRunning()
                    null
                } catch (_: Exception) {
                    context.getString(R.string.set_apply_failed)
                }
            }
        }
    }

    fun checkUpdate() {
        if (busy) return
        env.jobs.launch {
            env.jobs.versionMessage.value = null
            env.jobs.versionMessage.value = withContext(NonCancellable) {
                val outcome = try {
                    env.versions.checkUpdate()
                } catch (e: Exception) {
                    UpdateOutcome.Failed(e.message ?: e.javaClass.simpleName)
                }
                superviseIfRunning()
                when (outcome) {
                    UpdateOutcome.UpToDate -> context.getString(R.string.set_version_uptodate, verified.lastOrNull().orEmpty())
                    is UpdateOutcome.Updated -> context.getString(R.string.set_version_updated, outcome.version)
                    is UpdateOutcome.RolledBack -> context.getString(R.string.set_version_rolled_back, outcome.version, outcome.reason.take(MAX_REASON_CHARS))
                    is UpdateOutcome.Failed -> context.getString(R.string.set_version_failed, outcome.reason.take(MAX_REASON_CHARS))
                }
            }
        }
    }

    fun rollback() {
        if (busy) return
        env.jobs.rollback(step = env.versions::rollback, isRunning = { env.isRunning.value }, supervise = env.supervise) { outcome ->
            env.jobs.versionMessage.value = when (outcome) {
                is MainOps.RollbackOutcome.RolledBack -> context.getString(R.string.set_rollback_done, outcome.version)
                MainOps.RollbackOutcome.NoPrevious -> context.getString(R.string.set_rollback_none)
                is MainOps.RollbackOutcome.Failed -> context.getString(R.string.set_rollback_failed)
            }
        }
    }

    /**
     * An update stops dsh only when there is a verified version to install: up to date, it stops nothing and asks nothing (it
     * just says so). Only with a target and a running conversation does it ask first.
     */
    fun askToUpdate() {
        if (busy) return
        val target = runCatching { env.versions.updateTarget() }.getOrNull()
        if (target == null) checkUpdate() else askOrRun(StopAction.UPDATE, ::checkUpdate)
    }

    fun confirmed(action: StopAction) {
        confirming = null
        when (action) {
            StopAction.APPLY -> apply()
            StopAction.UPDATE -> checkUpdate()
            StopAction.ROLLBACK -> rollback()
        }
    }

    fun export(action: () -> Unit) = launchBusy {
        exportMessage = try {
            withContext(Dispatchers.IO) { action() }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            context.getString(R.string.main_export_failed)
        }
    }

    fun saveBudget() {
        val alert = parseUsd(alertText)
        val cap = if (capEnabled) parseUsd(capText) else null
        if (alert == null || (capEnabled && cap == null)) {
            budgetMessage = BudgetMessage.INVALID
            return
        }
        env.meter.alertUsd = alert
        env.meter.hardCapUsd = cap
        todayUsd = env.meter.todayUsd()
        budgetMessage = BudgetMessage.SAVED
    }

    fun stepHeartbeat(delta: Int) {
        val next = (heartbeat + delta).coerceIn(HeartbeatScheduler.MIN_INTERVAL_MIN, HeartbeatScheduler.MAX_INTERVAL_MIN)
        if (next == heartbeat) return
        heartbeat = next
        env.store.heartbeatIntervalMin = next
        env.scheduleHeartbeat(next)
    }

    Surface(modifier = modifier.fillMaxSize(), color = if (embedded) Color.Transparent else MaterialTheme.colorScheme.background, contentColor = Sakura.Text) {
        Column(
            modifier = Modifier
                .then(if (embedded) Modifier else Modifier.safeDrawingPadding())
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Embedded, the shell's header already names the page.
            if (onClose != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.set_title), style = MaterialTheme.typography.headlineSmall, color = Sakura.Text,
                        modifier = Modifier.weight(1f).testTag("settings-title"),
                    )
                    TextButton(onClick = onClose, enabled = !busy, modifier = Modifier.testTag("close")) { Text(stringResource(R.string.set_close)) }
                }
            }
            if (busy) {
                Column(Modifier.testTag("busy"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.set_busy), style = MaterialTheme.typography.labelMedium)
                }
            }
            env.dshStatus?.let { flow ->
                val dshStatus by flow.collectAsStateWithLifecycle()
                DshStatusView(dshStatus, onForceRestart = { forceRestart() }, onOpenAppSettings = env.openAppSettings)
            }

            Section(R.string.set_section_keys, R.string.set_en_keys, 0) {
                Text(stringResource(R.string.set_key_hint), style = MaterialTheme.typography.bodySmall)
                KeyRow(R.string.set_key_deepseek, "deepseek", hasDeepseek, !busy, { editing = Provider.DEEPSEEK; keyInput = ""; keyError = false }, { clearKey(Provider.DEEPSEEK) })
                KeyRow(R.string.set_key_claude, "claude", hasClaude, !busy, { editing = Provider.ANTHROPIC; keyInput = ""; keyError = false }, { clearKey(Provider.ANTHROPIC) })
                editing?.let { provider ->
                    val valid = keyInput.trim().length in MIN_KEY_LENGTH..MAX_KEY_LENGTH
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        label = { Text(stringResource(if (provider == Provider.DEEPSEEK) R.string.set_key_deepseek else R.string.set_key_claude) + " · " + stringResource(R.string.set_key_field)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("key-field"),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { saveKey(provider) }, enabled = valid && !busy, modifier = Modifier.testTag("save-key")) { Text(stringResource(R.string.set_key_save)) }
                        TextButton(onClick = { editing = null; keyInput = ""; keyError = false }, enabled = !busy, modifier = Modifier.testTag("cancel-key")) { Text(stringResource(R.string.set_key_cancel)) }
                    }
                }
                if (keyError) Text(stringResource(R.string.set_key_error), color = MaterialTheme.colorScheme.error)
                if (claudePending) Text(stringResource(R.string.set_key_claude_pending), style = MaterialTheme.typography.bodySmall)
                // 0.5.0: the CWA open-data key for the weather analysis; only the app's own weather request uses it, never dsh.
                var hasCwa by remember { mutableStateOf(env.vault.hasNamed(app.dshmobile.security.KeyVault.CWA)) }
                var cwaEditing by remember { mutableStateOf(false) }
                var cwaInput by remember { mutableStateOf("") }
                KeyRow(R.string.set_key_cwa, "cwa", hasCwa, !busy, { cwaEditing = true; cwaInput = "" }, {
                    env.vault.clearNamed(app.dshmobile.security.KeyVault.CWA); hasCwa = false
                })
                if (cwaEditing) {
                    OutlinedTextField(
                        value = cwaInput, onValueChange = { cwaInput = it }, singleLine = true,
                        label = { Text(stringResource(R.string.set_key_cwa) + " · " + stringResource(R.string.set_key_field)) },
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().testTag("cwa-field"),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            env.vault.putNamed(app.dshmobile.security.KeyVault.CWA, cwaInput.trim()); hasCwa = true; cwaEditing = false; cwaInput = ""
                        }, enabled = cwaInput.trim().length in 10..200) { Text(stringResource(R.string.set_key_save)) }
                        TextButton(onClick = { cwaEditing = false; cwaInput = "" }) { Text(stringResource(R.string.set_key_cancel)) }
                    }
                }
                Text(stringResource(R.string.set_key_cwa_note), style = MaterialTheme.typography.bodySmall)
            }

            Section(R.string.set_section_agent, R.string.set_en_agents, 1) {
                val concurrent = stringResource(R.string.set_agent_concurrent)
                val depth = stringResource(R.string.set_agent_depth)
                val team = stringResource(R.string.set_agent_team)
                Stepper(concurrent, settings.maxConcurrent, AgentSettings.MAX_CONCURRENT_RANGE, "concurrent", !busy) { settings = settings.copy(maxConcurrent = it) }
                Stepper(depth, settings.maxDepth, AgentSettings.MAX_DEPTH_RANGE, "depth", !busy) { settings = settings.copy(maxDepth = it) }
                Stepper(team, settings.teamSize, AgentSettings.TEAM_SIZE_RANGE, "team", !busy) { settings = settings.copy(teamSize = it) }
                if (settings.teamSize > settings.maxConcurrent) {
                    Text(stringResource(R.string.set_agent_team_clamped, settings.maxConcurrent), style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.set_claude_leads), Modifier.weight(1f))
                    Switch(
                        checked = settings.claudeLeads && hasClaude, onCheckedChange = { settings = settings.copy(claudeLeads = it) },
                        enabled = !busy && hasClaude, modifier = Modifier.testTag("switch-claude-leads"),
                    )
                }
                Text(stringResource(if (hasClaude) R.string.set_claude_leads_note else R.string.set_claude_leads_need_key), style = MaterialTheme.typography.bodySmall)
                if (settings.claudeLeads && hasClaude) {
                    Segmented(
                        options = listOf(stringResource(R.string.set_claude_lead_opus), stringResource(R.string.set_claude_lead_fable)),
                        selected = app.dshmobile.config.AgentModels.CLAUDE_LEADS.indexOf(settings.leadClaude).coerceAtLeast(0),
                        onSelect = { settings = settings.copy(leadClaude = app.dshmobile.config.AgentModels.CLAUDE_LEADS[it]) },
                        tagPrefix = "claude-lead-",
                    )
                    Segmented(
                        options = listOf(stringResource(R.string.set_worker_pro), stringResource(R.string.set_worker_flash)),
                        selected = if (settings.workerModel == "deepseek-flash") 1 else 0,
                        onSelect = { settings = settings.copy(workerModel = if (it == 1) "deepseek-flash" else "deepseek-v4-pro") },
                        tagPrefix = "worker-",
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.set_agent_teams), Modifier.weight(1f))
                    Switch(checked = settings.agentTeams, onCheckedChange = { settings = settings.copy(agentTeams = it) }, enabled = !busy, modifier = Modifier.testTag("switch-teams"))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.set_auto_review), Modifier.weight(1f))
                    Switch(checked = settings.autoReview, onCheckedChange = { settings = settings.copy(autoReview = it) }, enabled = !busy, modifier = Modifier.testTag("switch-auto-review"))
                }
                Text(stringResource(R.string.set_auto_review_note), style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.OutlinedTextField(
                    settings.mcpServers, { settings = settings.copy(mcpServers = it) }, enabled = !busy,
                    label = { Text(stringResource(R.string.set_mcp)) }, minLines = 2,
                    modifier = Modifier.fillMaxWidth().testTag("field-mcp"),
                )
                val badMcp = app.dshmobile.config.McpServers.invalid(settings.mcpServers)
                Text(
                    if (badMcp.isEmpty()) stringResource(R.string.set_mcp_note) else stringResource(R.string.set_mcp_bad, badMcp.joinToString("、")),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(stringResource(R.string.set_agent_worker_note), style = MaterialTheme.typography.bodySmall)
            }

            Section(R.string.set_section_models, R.string.set_en_models, 2) {
                Text(stringResource(R.string.set_agent_model), style = MaterialTheme.typography.titleSmall)
                val selected = AgentModels.resolve(settings.leadModel, hasClaude).id
                AgentModels.available(hasClaude).forEach { model ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .testTag("model-${model.id}")
                            .selectable(selected = model.id == selected, enabled = !busy, role = Role.RadioButton) { settings = settings.copy(leadModel = model.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = model.id == selected, onClick = null, enabled = !busy)
                        Text(AgentModels.displayName(model.id) ?: model.id, Modifier.padding(start = 8.dp).weight(1f))
                        // Chosen but not in effect: the lead model changes with "apply and restart" (final review B2-M3).
                        if (model.id == selected && selected != AgentModels.resolve(draft.appliedLeadModel, hasClaude).id) {
                            Text(
                                stringResource(R.string.set_model_unapplied), color = Sakura.TextSecondary,
                                style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag(Tags.LEAD_UNAPPLIED),
                            )
                        }
                    }
                }
                Text(stringResource(R.string.set_novel_model), style = MaterialTheme.typography.titleSmall)
                val novelSelected = AgentModels.resolve(novelModel, hasClaude).id
                AgentModels.available(hasClaude).forEach { model ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .testTag(Tags.NOVEL_MODEL + model.id)
                            // Saved at once; dsh is not restarted (the bookshelf that uses it comes in the next release).
                            .selectable(selected = model.id == novelSelected, enabled = !busy, role = Role.RadioButton) {
                                novelModel = model.id
                                env.store.novelModel = model.id
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = model.id == novelSelected, onClick = null, enabled = !busy)
                        Text(AgentModels.displayName(model.id) ?: model.id, Modifier.padding(start = 8.dp))
                    }
                }
                Text(stringResource(R.string.set_novel_hint), style = MaterialTheme.typography.bodySmall)
            }

            Section(R.string.set_section_effects, R.string.set_en_effects, 3) {
                // The control shows the app's level and calls onEffectLevel only when a cell is tapped (never on recomposition).
                val names = listOf(stringResource(R.string.set_effect_full), stringResource(R.string.set_effect_balanced), stringResource(R.string.set_effect_saver))
                Segmented(
                    options = names,
                    selected = effectLevel.ordinal,
                    // Re-tapping the chosen cell is not a choice: it would reset the jank governor's cap (final review B2-M2).
                    onSelect = { if (it != effectLevel.ordinal) env.onEffectLevel(EffectLevel.entries[it]) },
                    tagPrefix = Tags.EFFECT,
                )
                if (effectiveLevel.ordinal > effectLevel.ordinal) {
                    Text(
                        stringResource(R.string.set_effect_lowered, names[effectiveLevel.ordinal]),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(Tags.EFFECT_LOWERED),
                    )
                }
                Text(stringResource(R.string.set_effect_hint), style = MaterialTheme.typography.bodySmall)
            }
            (androidx.compose.ui.platform.LocalContext.current.applicationContext as? app.dshmobile.DshMobileApp)?.let { dshApp ->
                Section(R.string.set_section_status, R.string.set_en_status, 3) { StatusLineSettings(dshApp) }
            }

            Section(R.string.set_section_budget, R.string.set_en_budget, 4) {
                Text(stringResource(R.string.set_budget_today, String.format(Locale.US, "%.4f", todayUsd)))
                OutlinedTextField(
                    value = alertText, onValueChange = { alertText = it; budgetMessage = null },
                    label = { Text(stringResource(R.string.set_budget_alert)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("alert-field"),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.set_budget_cap_switch), Modifier.weight(1f))
                    Switch(checked = capEnabled, onCheckedChange = { capEnabled = it; budgetMessage = null }, modifier = Modifier.testTag("switch-cap"))
                }
                if (capEnabled) {
                    OutlinedTextField(
                        value = capText, onValueChange = { capText = it; budgetMessage = null },
                        label = { Text(stringResource(R.string.set_budget_cap)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().testTag("cap-field"),
                    )
                }
                Button(onClick = ::saveBudget, modifier = Modifier.testTag("save-budget")) { Text(stringResource(R.string.set_budget_save)) }
                when (budgetMessage) {
                    BudgetMessage.SAVED -> Text(stringResource(R.string.set_budget_saved))
                    BudgetMessage.INVALID -> Text(stringResource(R.string.set_budget_invalid), color = MaterialTheme.colorScheme.error)
                    null -> Unit
                }
            }

            Section(R.string.set_section_heartbeat, R.string.set_en_heartbeat, 5) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.set_heartbeat_value, heartbeat), Modifier.weight(1f))
                    StepButtons("heartbeat", stringResource(R.string.set_section_heartbeat), !busy && heartbeat > HeartbeatScheduler.MIN_INTERVAL_MIN, !busy && heartbeat < HeartbeatScheduler.MAX_INTERVAL_MIN,
                        { stepHeartbeat(-HEARTBEAT_STEP_MIN) }, { stepHeartbeat(HEARTBEAT_STEP_MIN) })
                }
                Text(stringResource(R.string.set_heartbeat_hint), style = MaterialTheme.typography.bodySmall)
            }

            Section(R.string.set_section_version, R.string.set_en_dsh, 6) {
                Text(stringResource(R.string.set_verified_note, verified.joinToString(", ")), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(Tags.VERIFIED_NOTE))
                if (currentVersion != unknownVersion && currentVersion !in verified && verified.isNotEmpty()) {
                    Text(
                        stringResource(R.string.set_unverified_warning, currentVersion, verified.last()),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(Tags.UNVERIFIED_WARNING),
                    )
                }
                Text(stringResource(R.string.set_version_current, currentVersion))
                Text(stringResource(R.string.set_version_previous, previousVersion ?: stringResource(R.string.set_version_none)))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { askToUpdate() }, enabled = !busy, modifier = Modifier.testTag("check-update")) { Text(stringResource(R.string.set_version_check)) }
                    OutlinedButton(onClick = { askOrRun(StopAction.ROLLBACK, ::rollback) }, enabled = !busy && previousVersion != null, modifier = Modifier.testTag("rollback")) { Text(stringResource(R.string.set_version_rollback)) }
                }
                confirming?.takeIf { it != StopAction.APPLY }?.let { action -> StopConfirmCard(action, { confirming = null }) { confirmed(action) } }
                versionMessage?.let { Text(it) }
                // Whatever the result was, say whether dsh is up: a failed switch back leaves it stopped.
                Text(stringResource(if (dshRunning) R.string.set_dsh_running else R.string.set_dsh_stopped), color = if (dshRunning) Sakura.Text else MaterialTheme.colorScheme.error)
                if (!dshRunning) {
                    Button(onClick = { restart() }, enabled = !busy, modifier = Modifier.testTag("restart")) { Text(stringResource(R.string.set_dsh_restart)) }
                }
            }

            Section(R.string.set_section_phantom, R.string.set_en_system, 7) {
                val label = stringResource(
                    when (phantom) {
                        PhantomState.DISABLED -> R.string.onb_phantom_state_disabled
                        PhantomState.ENABLED -> R.string.onb_phantom_state_enabled
                        PhantomState.UNKNOWN -> R.string.onb_phantom_state_unknown
                    },
                )
                Text(stringResource(R.string.set_phantom_state, label))
                OutlinedButton(onClick = env.openDeveloperSettings, modifier = Modifier.testTag("open-developer")) { Text(stringResource(R.string.set_phantom_open)) }
            }

            Section(R.string.set_section_export, R.string.set_en_export, 8) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { export(env.exportErrorLog) }, enabled = !busy, modifier = Modifier.testTag("export-log")) { Text(stringResource(R.string.main_export_log)) }
                    OutlinedButton(onClick = { export(env.exportMemory) }, enabled = !busy, modifier = Modifier.testTag("export-memory")) { Text(stringResource(R.string.set_export_memory)) }
                }
                OutlinedButton(onClick = { export(env.exportWorkspace) }, enabled = !busy, modifier = Modifier.testTag("export-workspace")) { Text(stringResource(R.string.set_export_workspace)) }
                exportMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }

            env.nativeMode?.let { native ->
                Section(R.string.set_section_native, R.string.set_en_native, 9) {
                    val on by native.enabled.collectAsState()
                    Text(stringResource(R.string.set_native_note), style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.set_native_switch), modifier = Modifier.weight(1f))
                        Switch(
                            checked = on && native.available,
                            onCheckedChange = { native.set(it) },
                            enabled = !busy && native.available,
                            modifier = Modifier.testTag("switch-native"),
                        )
                    }
                    Text(
                        stringResource(
                            when {
                                !native.available -> R.string.set_native_unavailable
                                on -> R.string.set_native_on
                                else -> R.string.set_native_off
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("native-state"),
                    )
                    val noApproval by native.noApproval.collectAsState()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.set_native_no_approval), modifier = Modifier.weight(1f))
                        Switch(
                            checked = noApproval,
                            onCheckedChange = { native.setNoApproval(it) },
                            enabled = !busy && native.available,
                            modifier = Modifier.testTag("switch-native-no-approval"),
                        )
                    }
                    Text(stringResource(R.string.set_native_no_approval_note), style = MaterialTheme.typography.bodySmall)
                }
            }

            Section(R.string.set_apply, R.string.set_en_apply, 10) {
                Text(stringResource(R.string.set_apply_note), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { askOrRun(StopAction.APPLY, ::apply) }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("apply")) { Text(stringResource(R.string.set_apply)) }
                if (confirming == StopAction.APPLY) StopConfirmCard(StopAction.APPLY, { confirming = null }) { confirmed(StopAction.APPLY) }
                applyMessage?.let { Text(it) }
            }
        }
    }
}

internal enum class BudgetMessage { SAVED, INVALID }

/** The settings actions that stop dsh: each asks first while a conversation runs, with its own words. */
internal enum class StopAction(val question: Int, val go: Int) {
    APPLY(R.string.set_confirm_apply, R.string.set_confirm_apply_go),
    UPDATE(R.string.set_confirm_update, R.string.set_confirm_update_go),
    ROLLBACK(R.string.set_confirm_rollback, R.string.set_confirm_rollback_go),
}

/**
 * 「有對話正在進行，套用會中斷它」: a glass card in the section of the action that asks (it pushes the content down and covers
 * nothing). 「等做完」 drops the action; the other button runs it.
 */
@Composable
private fun StopConfirmCard(action: StopAction, onWait: () -> Unit, onGo: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(FogDimens.Sheet))
            .testTag(Tags.CONFIRM_CARD)
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(action.question), color = Sakura.Text, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FogButton(stringResource(R.string.set_confirm_wait), FogButtonKind.GHOST, onWait, Modifier.testTag(Tags.CONFIRM_WAIT))
            FogButton(stringResource(action.go), FogButtonKind.PINK, onGo, Modifier.testTag(Tags.CONFIRM_GO))
        }
    }
}

@Composable
private fun Section(title: Int, en: Int, index: Int, content: @Composable () -> Unit) {
    FogPanel(title = stringResource(title), en = stringResource(en), index = index, modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun KeyRow(label: Int, tag: String, configured: Boolean, enabled: Boolean, onReplace: () -> Unit, onClear: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(label))
            Text(
                stringResource(if (configured) R.string.set_key_set else R.string.set_key_unset),
                style = MaterialTheme.typography.bodySmall,
                color = if (configured) Sakura.Text else Sakura.TextSecondary,
            )
        }
        OutlinedButton(onClick = onReplace, enabled = enabled, modifier = Modifier.testTag("replace-$tag")) {
            Text(stringResource(if (configured) R.string.set_key_replace else R.string.set_key_add))
        }
        TextButton(onClick = onClear, enabled = enabled && configured, modifier = Modifier.testTag("clear-$tag")) { Text(stringResource(R.string.set_key_clear)) }
    }
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, tag: String, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label：$value", Modifier.weight(1f))
        StepButtons(tag, label, enabled && value > range.first, enabled && value < range.last, { onChange(value - 1) }, { onChange(value + 1) })
    }
}

@Composable
private fun StepButtons(tag: String, label: String, canDecrease: Boolean, canIncrease: Boolean, onDecrease: () -> Unit, onIncrease: () -> Unit) {
    val dec = stringResource(R.string.set_agent_decrease, label)
    val inc = stringResource(R.string.set_agent_increase, label)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onDecrease, enabled = canDecrease, modifier = Modifier.testTag("dec-$tag").semantics { contentDescription = dec }) { Text("－") }
        OutlinedButton(onClick = onIncrease, enabled = canIncrease, modifier = Modifier.testTag("inc-$tag").semantics { contentDescription = inc }) { Text("＋") }
    }
}

/** "5" for 5.0, "2.5" for 2.5, never scientific notation. */
internal fun formatUsd(v: Double): String = BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()

/** A positive, finite number; anything else (empty, text, zero, negative, NaN, Infinity) is null. */
internal fun parseUsd(text: String): Double? = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }

/** 聊天框狀態列: which segments show (switch) and their order (↑ ↓); applies at once. */
@Composable
private fun StatusLineSettings(dshApp: app.dshmobile.DshMobileApp) {
    val config by dshApp.statusLineConfig.collectAsStateWithLifecycle()
    val all = config.order + app.dshmobile.ui.shell.StatusLineConfig.ALL.filter { it !in config.order }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        all.forEach { id ->
            val on = id in config.order
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(on, { dshApp.setStatusLine(config.toggled(id)) }, modifier = Modifier.testTag("status-cfg-$id"))
                Text(app.dshmobile.ui.shell.StatusLineConfig.NAMES[id] ?: id, modifier = Modifier.weight(1f))
                if (on) {
                    androidx.compose.material3.TextButton(onClick = { dshApp.setStatusLine(config.moved(id, -1)) }) { Text("↑") }
                    androidx.compose.material3.TextButton(onClick = { dshApp.setStatusLine(config.moved(id, 1)) }) { Text("↓") }
                }
            }
        }
        Text(stringResource(R.string.set_status_hint), style = MaterialTheme.typography.bodySmall)
    }
}
