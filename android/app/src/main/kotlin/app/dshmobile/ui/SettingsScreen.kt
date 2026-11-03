package app.dshmobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import app.dshmobile.R
import app.dshmobile.config.AgentModels
import app.dshmobile.config.AgentSettings
import app.dshmobile.config.AgentSettingsStore
import app.dshmobile.gateway.CostMeter
import app.dshmobile.gateway.Provider
import app.dshmobile.heartbeat.HeartbeatScheduler
import app.dshmobile.runtime.UpdateOutcome
import app.dshmobile.security.KeyVault
import app.dshmobile.service.DshRuntime
import app.dshmobile.system.PhantomState
import app.dshmobile.ui.theme.Sakura
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.util.Locale

/** What the version section needs from `DshVersions` and `MainOps`; the production implementation is in SettingsActivity. */
interface SettingsVersions {
    fun current(): String
    fun previous(): String?
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
)

private const val BUDGET_DEFAULT_CAP = 10.0
private const val HEARTBEAT_STEP_MIN = 5
private const val MAX_REASON_CHARS = 300

/**
 * Keys, agent settings, budget, heartbeat, dsh versions, the phantom-process limit, exports and "apply and restart". Anything
 * slow (vault, restart, update, export) runs off the main thread; while one runs every action is disabled and a progress bar
 * shows. Agent settings are only saved by [apply]; budget and heartbeat have their own save/step actions. Keys are only ever in
 * a password field and the vault, and never shown.
 */
@Composable
fun SettingsScreen(env: SettingsEnv, onClose: () -> Unit = {}, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope() // keys and exports only: cancelling those half way is harmless
    var localBusy by remember { mutableStateOf(false) }
    val jobBusy by env.jobs.busy.collectAsState()
    val busy = localBusy || jobBusy
    // Leaving the screen is not possible while something runs (the jobs would survive it, but the user should see the result).
    BackHandler(enabled = busy) {}

    // The vault reads below are one AES-GCM decrypt each (as at app start); everything that can block runs on IO.
    var hasDeepseek by remember { mutableStateOf(env.vault.get(Provider.DEEPSEEK) != null) }
    var hasClaude by remember { mutableStateOf(env.vault.get(Provider.ANTHROPIC) != null) }
    var editing by remember { mutableStateOf<Provider?>(null) }
    var keyInput by remember { mutableStateOf("") }
    var keyError by remember { mutableStateOf(false) }

    var settings by remember { mutableStateOf(env.store.load(env.phantomState(), hasClaude)) }
    val applyMessage by env.jobs.applyMessage.collectAsState()

    var todayUsd by remember { mutableStateOf(env.meter.todayUsd()) }
    var alertText by remember { mutableStateOf(formatUsd(env.meter.alertUsd)) }
    var capEnabled by remember { mutableStateOf(env.meter.hardCapUsd != null) }
    var capText by remember { mutableStateOf(formatUsd(env.meter.hardCapUsd ?: BUDGET_DEFAULT_CAP)) }
    var budgetMessage by remember { mutableStateOf<BudgetMessage?>(null) }

    var heartbeat by remember { mutableStateOf(env.store.heartbeatIntervalMin) }

    // DshVersions.current() throws when the `current` link is missing or malformed: the screen must still open.
    val unknownVersion = stringResource(R.string.set_version_unknown)
    fun readCurrent(): String = try { env.versions.current() } catch (_: Exception) { unknownVersion }
    fun readPrevious(): String? = try { env.versions.previous() } catch (_: Exception) { null }
    var currentVersion by remember { mutableStateOf(readCurrent()) }
    var previousVersion by remember { mutableStateOf(readPrevious()) }
    val versionMessage by env.jobs.versionMessage.collectAsState()
    val dshRunning by env.isRunning.collectAsState()

    var phantom by remember { mutableStateOf(env.phantomState()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { phantom = env.phantomState() } // back from the system settings
    var exportMessage by remember { mutableStateOf<String?>(null) }

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
    }

    /** After an update or rollback: dsh that is up again goes back under DshService (clears a give-up); a dsh that is down stays as it is. */
    fun superviseIfRunning() {
        if (env.isRunning.value) runCatching { env.supervise() }
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

    fun restart() {
        if (busy) return
        env.jobs.launch {
            env.jobs.versionMessage.value = withContext(NonCancellable) {
                try {
                    runCatching { env.supervise() }
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
                    UpdateOutcome.UpToDate -> context.getString(R.string.set_version_uptodate)
                    is UpdateOutcome.Updated -> context.getString(R.string.set_version_updated, outcome.version)
                    is UpdateOutcome.RolledBack -> context.getString(R.string.set_version_rolled_back, outcome.version, outcome.reason.take(MAX_REASON_CHARS))
                    is UpdateOutcome.Failed -> context.getString(R.string.set_version_failed, outcome.reason.take(MAX_REASON_CHARS))
                }
            }
        }
    }

    fun rollback() {
        if (busy) return
        env.jobs.launch {
            env.jobs.versionMessage.value = null
            env.jobs.versionMessage.value = withContext(NonCancellable) {
                val outcome = try {
                    env.versions.rollback()
                } catch (_: Exception) {
                    MainOps.RollbackOutcome.Failed("rollback failed")
                }
                superviseIfRunning()
                when (outcome) {
                    is MainOps.RollbackOutcome.RolledBack -> context.getString(R.string.set_rollback_done, outcome.version)
                    MainOps.RollbackOutcome.NoPrevious -> context.getString(R.string.set_rollback_none)
                    is MainOps.RollbackOutcome.Failed -> context.getString(R.string.set_rollback_failed)
                }
            }
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

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.set_title), style = MaterialTheme.typography.headlineSmall, color = Sakura.Text, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose, enabled = !busy, modifier = Modifier.testTag("close")) { Text(stringResource(R.string.set_close)) }
            }
            if (busy) {
                Column(Modifier.testTag("busy"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.set_busy), style = MaterialTheme.typography.labelMedium)
                }
            }

            Section(R.string.set_section_keys) {
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
            }

            Section(R.string.set_section_agent) {
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
                    Text(stringResource(R.string.set_agent_teams), Modifier.weight(1f))
                    Switch(checked = settings.agentTeams, onCheckedChange = { settings = settings.copy(agentTeams = it) }, enabled = !busy, modifier = Modifier.testTag("switch-teams"))
                }
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
                        Text(model.id, Modifier.padding(start = 8.dp))
                    }
                }
                Text(stringResource(R.string.set_agent_worker_note), style = MaterialTheme.typography.bodySmall)
            }

            Section(R.string.set_section_budget) {
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

            Section(R.string.set_section_heartbeat) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.set_heartbeat_value, heartbeat), Modifier.weight(1f))
                    StepButtons("heartbeat", stringResource(R.string.set_section_heartbeat), !busy && heartbeat > HeartbeatScheduler.MIN_INTERVAL_MIN, !busy && heartbeat < HeartbeatScheduler.MAX_INTERVAL_MIN,
                        { stepHeartbeat(-HEARTBEAT_STEP_MIN) }, { stepHeartbeat(HEARTBEAT_STEP_MIN) })
                }
                Text(stringResource(R.string.set_heartbeat_hint), style = MaterialTheme.typography.bodySmall)
            }

            Section(R.string.set_section_version) {
                Text(stringResource(R.string.set_version_current, currentVersion))
                Text(stringResource(R.string.set_version_previous, previousVersion ?: stringResource(R.string.set_version_none)))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { checkUpdate() }, enabled = !busy, modifier = Modifier.testTag("check-update")) { Text(stringResource(R.string.set_version_check)) }
                    OutlinedButton(onClick = { rollback() }, enabled = !busy && previousVersion != null, modifier = Modifier.testTag("rollback")) { Text(stringResource(R.string.set_version_rollback)) }
                }
                versionMessage?.let { Text(it) }
                // Whatever the result was, say whether dsh is up: a failed switch back leaves it stopped.
                Text(stringResource(if (dshRunning) R.string.set_dsh_running else R.string.set_dsh_stopped), color = if (dshRunning) Sakura.Text else MaterialTheme.colorScheme.error)
                if (!dshRunning) {
                    Button(onClick = { restart() }, enabled = !busy, modifier = Modifier.testTag("restart")) { Text(stringResource(R.string.set_dsh_restart)) }
                }
            }

            Section(R.string.set_section_phantom) {
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

            Section(R.string.set_section_export) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { export(env.exportErrorLog) }, enabled = !busy, modifier = Modifier.testTag("export-log")) { Text(stringResource(R.string.main_export_log)) }
                    OutlinedButton(onClick = { export(env.exportMemory) }, enabled = !busy, modifier = Modifier.testTag("export-memory")) { Text(stringResource(R.string.set_export_memory)) }
                }
                OutlinedButton(onClick = { export(env.exportWorkspace) }, enabled = !busy, modifier = Modifier.testTag("export-workspace")) { Text(stringResource(R.string.set_export_workspace)) }
                exportMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }

            Section(R.string.set_apply) {
                Text(stringResource(R.string.set_apply_note), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { apply() }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("apply")) { Text(stringResource(R.string.set_apply)) }
                applyMessage?.let { Text(it) }
            }
        }
    }
}

private enum class BudgetMessage { SAVED, INVALID }

@Composable
private fun Section(title: Int, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, Sakura.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = Sakura.Text)
            content()
        }
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
