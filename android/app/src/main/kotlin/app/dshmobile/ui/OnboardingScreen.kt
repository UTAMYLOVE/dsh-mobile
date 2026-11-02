package app.dshmobile.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.dshmobile.R
import app.dshmobile.gateway.Provider
import app.dshmobile.security.KeyVault
import app.dshmobile.system.OnboardingState
import app.dshmobile.system.OnboardingState.Step
import app.dshmobile.system.PhantomProcessDetector
import app.dshmobile.system.PhantomState
import app.dshmobile.ui.theme.Sakura
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

internal const val MIN_KEY_LENGTH = 16
internal const val MAX_KEY_LENGTH = 512

/**
 * First-launch flow: notifications, battery, phantom-process limit, DeepSeek key, rootfs. Everything it
 * touches is passed in, so tests drive it with fakes. [installRootfs] is called on `Dispatchers.IO`.
 * [onFinished] runs once every step is done or skipped.
 */
@Composable
fun OnboardingScreen(
    state: OnboardingState,
    platform: OnboardingPlatform,
    vault: KeyVault,
    detector: PhantomProcessDetector,
    isRootfsInstalled: () -> Boolean,
    installRootfs: (onProgress: (Int) -> Unit) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var current by remember { mutableStateOf(state.nextStep()) }
    // Bumped on every ON_RESUME: the user comes back from a settings screen and the facts may have changed.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }

    fun advance() { current = state.nextStep() }
    fun done(step: Step) { state.markDone(step); advance() }
    fun skip(step: Step) { state.markSkipped(step); advance() }

    val step = current
    LaunchedEffect(step) { if (step == null) onFinished() }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (step != null) {
            Column(
                modifier = Modifier
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.onb_step_counter, step.ordinal + 1, Step.values().size),
                    style = MaterialTheme.typography.labelLarge,
                    color = Sakura.TagText,
                )
                when (step) {
                    Step.NOTIFICATIONS -> NotificationsStep(platform, resumes, onDone = { done(step) }, onSkip = { skip(step) })
                    Step.BATTERY -> BatteryStep(platform, resumes, onDone = { done(step) }, onSkip = { skip(step) })
                    Step.PHANTOM -> PhantomStep(platform, detector, resumes, onDone = { done(step) }, onSkip = { skip(step) })
                    Step.DEEPSEEK_KEY -> KeyStep(vault, onDone = { done(step) })
                    Step.ROOTFS -> RootfsStep(isRootfsInstalled, installRootfs, onDone = { done(step) })
                }
            }
        }
    }
}

@Composable
private fun StepCard(title: String, body: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, Sakura.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Sakura.Primary)
            Text(body, style = MaterialTheme.typography.bodyLarge)
            content()
        }
    }
}

@Composable
private fun SkipButton(onSkip: () -> Unit) {
    TextButton(onClick = onSkip) { Text(stringResource(R.string.onb_skip)) }
}

@Composable
private fun NotificationsStep(platform: OnboardingPlatform, resumes: Int, onDone: () -> Unit, onSkip: () -> Unit) {
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onDone() else denied = true
    }
    LaunchedEffect(resumes) { if (platform.notificationsGranted()) onDone() }
    StepCard(stringResource(R.string.onb_notif_title), stringResource(R.string.onb_notif_body)) {
        if (denied) Text(stringResource(R.string.onb_notif_denied), color = MaterialTheme.colorScheme.error)
        Button(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
            Text(stringResource(R.string.onb_notif_button))
        }
        SkipButton(onSkip)
    }
}

@Composable
private fun BatteryStep(platform: OnboardingPlatform, resumes: Int, onDone: () -> Unit, onSkip: () -> Unit) {
    LaunchedEffect(resumes) { if (platform.batteryOptimizationIgnored()) onDone() }
    StepCard(stringResource(R.string.onb_battery_title), stringResource(R.string.onb_battery_body)) {
        Button(onClick = platform::openBatterySettings) { Text(stringResource(R.string.onb_battery_button)) }
        SkipButton(onSkip)
    }
}

@Composable
private fun PhantomStep(
    platform: OnboardingPlatform,
    detector: PhantomProcessDetector,
    resumes: Int,
    onDone: () -> Unit,
    onSkip: () -> Unit,
) {
    // Re-read after returning from the settings screens.
    val phantom = remember(resumes) { detector.state() }
    val developerOptions = remember(resumes) { platform.developerOptionsEnabled() }
    StepCard(stringResource(R.string.onb_phantom_title), stringResource(R.string.onb_phantom_body)) {
        if (!developerOptions) {
            Text(stringResource(R.string.onb_phantom_tutorial), color = Sakura.TagText)
            Button(onClick = platform::openDeviceInfoSettings) { Text(stringResource(R.string.onb_phantom_open_about)) }
        } else {
            Button(onClick = platform::openDeveloperSettings) { Text(stringResource(R.string.onb_phantom_open_dev)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.onb_phantom_state_label), style = MaterialTheme.typography.labelLarge)
            Text(
                stringResource(
                    when (phantom) {
                        PhantomState.DISABLED -> R.string.onb_phantom_state_disabled
                        PhantomState.ENABLED -> R.string.onb_phantom_state_enabled
                        PhantomState.UNKNOWN -> R.string.onb_phantom_state_unknown
                    },
                ),
                color = Sakura.TagText,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (phantom == PhantomState.DISABLED) {
            Button(onClick = onDone) { Text(stringResource(R.string.onb_phantom_continue)) }
        }
        SkipButton(onSkip)
    }
}

@Composable
private fun KeyStep(vault: KeyVault, onDone: () -> Unit) {
    // The only place the key lives besides the vault: the password field's saveable state.
    var key by rememberSaveable { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    val trimmed = key.trim()
    val valid = trimmed.length in MIN_KEY_LENGTH..MAX_KEY_LENGTH
    StepCard(stringResource(R.string.onb_key_title), stringResource(R.string.onb_key_body)) {
        OutlinedTextField(
            value = key,
            onValueChange = { key = it; failed = false },
            label = { Text(stringResource(R.string.onb_key_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            isError = failed,
            modifier = Modifier.fillMaxWidth(),
        )
        if (failed) Text(stringResource(R.string.onb_key_error), color = MaterialTheme.colorScheme.error)
        Button(
            enabled = valid,
            onClick = {
                try {
                    vault.put(Provider.DEEPSEEK, trimmed)
                } catch (_: Exception) {
                    failed = true // never surface the exception: its message could carry vault internals
                    return@Button
                }
                key = ""
                onDone()
            },
        ) { Text(stringResource(R.string.onb_key_save)) }
    }
}

@Composable
private fun RootfsStep(
    isRootfsInstalled: () -> Boolean,
    installRootfs: (onProgress: (Int) -> Unit) -> Unit,
    onDone: () -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<ErrorKind?>(null) }
    LaunchedEffect(attempt) {
        error = null
        progress = 0
        val failure = withContext(Dispatchers.IO) {
            try {
                if (!isRootfsInstalled()) installRootfs { progress = it.coerceIn(0, 100) }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileNotFoundException) {
                ErrorKind.Missing
            } catch (t: Throwable) {
                ErrorKind.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
        if (failure == null) onDone() else error = failure
    }
    StepCard(stringResource(R.string.onb_rootfs_title), stringResource(R.string.onb_rootfs_body)) {
        when (val e = error) {
            null -> {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.onb_rootfs_progress, progress))
            }
            else -> {
                Text(
                    when (e) {
                        ErrorKind.Missing -> stringResource(R.string.onb_rootfs_missing)
                        is ErrorKind.Failed -> stringResource(R.string.onb_rootfs_failed, e.detail)
                    },
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = { attempt++ }) { Text(stringResource(R.string.onb_rootfs_retry)) }
            }
        }
    }
}

private sealed interface ErrorKind {
    data object Missing : ErrorKind
    data class Failed(val detail: String) : ErrorKind
}
