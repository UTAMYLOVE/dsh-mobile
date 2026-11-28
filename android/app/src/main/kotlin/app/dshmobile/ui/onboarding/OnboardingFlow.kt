package app.dshmobile.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.ui.BackupUi
import app.dshmobile.ui.ImportOutcome
import app.dshmobile.ui.OnboardingUi
import app.dshmobile.ui.theme.Block
import app.dshmobile.ui.theme.InkField
import app.dshmobile.ui.theme.KText
import app.dshmobile.ui.theme.Pal
import app.dshmobile.ui.theme.PillButton
import app.dshmobile.ui.theme.Type
import app.dshmobile.ui.theme.XiaokuiAvatar
import app.dshmobile.ui.theme.paper
import app.dshmobile.ui.theme.sticker
import app.dshmobile.ui.theme.tap
import kotlinx.coroutines.launch

/** The steps, in order. */
enum class OnboardStep { WELCOME, KEYS, PASTE, READING, CONFIRM, NAME, PERMISSIONS }

private val MemberColors = listOf("#FF8FB1", "#88AAFF", "#7EDDB0", "#FFC857", "#B79CFF", "#FF9F6E", "#5FD1D8", "#E57373")

/**
 * Welcome, keys, his document, 小葵 reads it, the summary to confirm, stage name and member colour, permissions and the
 * battery guide, then day 1 starts ([onFinished]).
 */
@Composable
fun OnboardingFlow(
    ob: OnboardingUi,
    onFinished: () -> Unit,
    startAt: OnboardStep = OnboardStep.WELCOME,
    /** Restoring a backup on a new phone (welcome step). Null = no such button. */
    backup: BackupUi? = null,
    /** Called after a restore went through; true = the company is ready (home next), false = only the keys are missing. */
    onRestored: suspend () -> Boolean = { false },
) {
    var step by rememberSaveable { mutableIntStateOf(startAt.ordinal) }
    val scope = rememberCoroutineScope()
    var claude by remember { mutableStateOf("") }
    var deepseek by remember { mutableStateOf("") }
    var document by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<ImportOutcome?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var stageName by remember { mutableStateOf("") }
    var callName by remember { mutableStateOf("") }
    var colorHex by remember { mutableStateOf(MemberColors[0]) }
    var busy by remember { mutableStateOf(false) }
    var keyError by remember { mutableStateOf(false) }
    var perms by remember { mutableStateOf(ob.permissions()) }
    val haveKeys = remember { ob.keysSaved() }
    val haveSecond = remember { ob.deepSeekSaved() }
    var changeKeys by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var restoredMode by remember { mutableStateOf(false) }
    // Back from the system's permission question: show what he decided.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { perms = ob.permissions() }
    val current = OnboardStep.entries[step]

    // step 4 runs by itself
    LaunchedEffect(current) {
        if (current == OnboardStep.READING) {
            failure = null
            val r = ob.readDocument(document)
            if (r.failure == null) {
                outcome = r; step = OnboardStep.CONFIRM.ordinal
            } else {
                failure = r.failure
            }
        }
    }

    Column(Modifier.fillMaxSize().paper().safeDrawingPadding().padding(horizontal = 20.dp).testTag("onboarding_${current.name}")) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            OnboardStep.entries.forEach { s ->
                Box(Modifier.size(10.dp).sticker(if (s.ordinal <= step) Pal.PinkStrong else Pal.White, CircleShape, shadow = 0.dp, outline = 1.5.dp))
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (current) {
                OnboardStep.WELCOME -> {
                    Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) { XiaokuiAvatar(140.dp) }
                    KText(stringResource(R.string.ob_welcome_title), size = 28.sp, bold = true, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Block { KText(stringResource(R.string.ob_welcome_text)) }
                    if (backup != null) {
                        if (!restoring) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                PillButton(stringResource(R.string.ob_restore), { restoring = true }, Modifier.testTag("ob_restore"), Pal.Lavender, Type.Small)
                            }
                        } else {
                            RestoreBlock(backup) {
                                scope.launch {
                                    if (onRestored()) {
                                        onFinished()
                                    } else {
                                        restoredMode = true
                                        step = OnboardStep.KEYS.ordinal
                                    }
                                }
                            }
                        }
                    }
                }
                OnboardStep.KEYS -> {
                    Title(R.string.ob_keys_title)
                    Block { KText(stringResource(R.string.ob_keys_text), size = Type.Small) }
                    if (haveKeys && !changeKeys) {
                        Block(Modifier.testTag("keys_imported"), fill = Pal.Mint) {
                            KText(stringResource(R.string.ob_keys_imported), bold = true)
                            val haveMain = remember { ob.claudeSaved() }
                            val note = when {
                                haveMain && haveSecond -> R.string.ob_keys_imported_both
                                haveMain -> R.string.ob_keys_imported_one
                                else -> R.string.ob_keys_imported_second_only
                            }
                            KText(stringResource(note), size = Type.Small)
                            if (!haveSecond) KText(stringResource(R.string.ob_key2_without), size = Type.Small)
                            PillButton(stringResource(R.string.ob_keys_change), { changeKeys = true }, Modifier.testTag("keys_change"), Pal.White, Type.Small)
                        }
                    } else {
                        Block {
                            KText(stringResource(R.string.ob_key1_title), bold = true)
                            KText(stringResource(R.string.ob_key1_text), size = Type.Small)
                            InkField(claude, { claude = it; keyError = false }, stringResource(R.string.ob_key_claude), visual = PasswordVisualTransformation(), tag = "key_claude")
                        }
                        Block {
                            KText(stringResource(R.string.ob_key2_title), bold = true)
                            KText(stringResource(R.string.ob_key2_text), size = Type.Small)
                            KText(stringResource(R.string.ob_key2_without), size = Type.Small, color = Pal.Warn)
                            InkField(deepseek, { deepseek = it }, stringResource(R.string.ob_key_deepseek), visual = PasswordVisualTransformation(), tag = "key_deepseek")
                        }
                    }
                    if (keyError) KText(stringResource(R.string.ob_key_error), color = Pal.Warn)
                }
                OnboardStep.PASTE -> {
                    Title(R.string.ob_paste_title)
                    Block { KText(stringResource(R.string.ob_paste_text), size = Type.Small) }
                    InkField(document, { document = it }, stringResource(R.string.ob_paste_hint), singleLine = false, minLines = 8, tag = "document")
                }
                OnboardStep.READING -> {
                    Title(R.string.ob_reading_title)
                    if (failure == null) {
                        Block { KText(stringResource(R.string.ob_reading_text)) }
                    } else {
                        Block(fill = Pal.Pink) { KText(failure.orEmpty(), color = Pal.Ink) }
                    }
                }
                OnboardStep.CONFIRM -> {
                    Title(R.string.ob_confirm_title)
                    outcome?.groups?.forEach { g ->
                        Block {
                            KText(g.title, bold = true)
                            g.lines.forEach { KText(it, size = Type.Small) }
                        }
                    }
                    outcome?.missing?.takeIf { it.isNotEmpty() }?.let {
                        Block(fill = Pal.Butter) { KText(stringResource(R.string.ob_missing, it.joinToString("、")), size = Type.Small) }
                    }
                    KText(stringResource(R.string.ob_confirm_later), size = Type.Tiny, alpha = 0.7f)
                }
                OnboardStep.NAME -> {
                    Title(R.string.ob_name_title)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        app.dshmobile.ui.theme.TraineeAvatar(88.dp, Color(android.graphics.Color.parseColor(colorHex)))
                    }
                    InkField(callName, { callName = it }, stringResource(R.string.ob_call_name), tag = "call_name")
                    InkField(stageName, { stageName = it }, stringResource(R.string.ob_stage_name), tag = "stage_name")
                    KText(stringResource(R.string.ob_member_color), bold = true)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MemberColors.forEach { hex ->
                            val c = Color(android.graphics.Color.parseColor(hex))
                            Box(
                                Modifier.size(34.dp).sticker(c, CircleShape, shadow = if (hex == colorHex) 3.dp else 0.dp, outline = if (hex == colorHex) 3.dp else 1.5.dp)
                                    .tap({ colorHex = hex }).testTag("color_$hex"),
                            )
                        }
                    }
                }
                OnboardStep.PERMISSIONS -> {
                    Title(R.string.ob_perm_title)
                    Block {
                        KText(stringResource(R.string.ob_perm_notify), bold = true)
                        if (perms.notifications) KText(stringResource(R.string.ob_perm_ok), size = Type.Small)
                        else PillButton(stringResource(R.string.ob_perm_notify_btn), { ob.requestNotifications(); perms = ob.permissions() }, Modifier.testTag("perm_notify"), Pal.Mint, Type.Small)
                    }
                    Block {
                        KText(stringResource(R.string.ob_perm_alarm), bold = true)
                        if (perms.exactAlarms) KText(stringResource(R.string.ob_perm_ok), size = Type.Small)
                        else PillButton(stringResource(R.string.ob_perm_alarm_btn), { ob.openAlarmSettings(); perms = ob.permissions() }, Modifier.testTag("perm_alarm"), Pal.Mint, Type.Small)
                    }
                    Block(fill = Pal.Butter) {
                        KText(stringResource(R.string.ob_battery_title), bold = true)
                        KText(stringResource(R.string.ob_battery_text), size = Type.Small)
                        PillButton(stringResource(R.string.ob_battery_btn), { ob.openBatteryGuide() }, Modifier.testTag("perm_battery"), Pal.White, Type.Small)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            if (current != OnboardStep.WELCOME && current != OnboardStep.READING) {
                PillButton(stringResource(R.string.ob_back), { step = (step - 1).coerceAtLeast(0) }, Modifier.testTag("ob_back"), Pal.White)
            }
            if (current == OnboardStep.READING && failure != null) {
                PillButton(stringResource(R.string.ob_back), { step = OnboardStep.PASTE.ordinal }, Modifier.testTag("ob_back"), Pal.White)
            }
            val label = when (current) {
                OnboardStep.WELCOME -> R.string.ob_start
                OnboardStep.PASTE -> R.string.ob_read
                OnboardStep.CONFIRM -> R.string.ob_confirm_ok
                OnboardStep.NAME -> R.string.ob_name_ok
                OnboardStep.PERMISSIONS -> R.string.ob_day_one
                else -> R.string.ob_next
            }
            if (current != OnboardStep.READING) {
                val enabled = when (current) {
                    OnboardStep.KEYS -> (claude.isNotBlank() || deepseek.isNotBlank() || haveKeys) && !busy
                    OnboardStep.PASTE -> document.isNotBlank()
                    OnboardStep.NAME -> callName.isNotBlank() && !busy
                    else -> !busy
                }
                PillButton(stringResource(label), {
                    when (current) {
                        OnboardStep.KEYS -> scope.launch {
                            busy = true
                            if (ob.saveKeys(claude.trim(), deepseek.trim())) {
                                // A restored company already has its rules: the keys were the only thing missing.
                                if (restoredMode) { ob.finish(); onFinished() } else step = OnboardStep.PASTE.ordinal
                            } else {
                                keyError = true
                            }
                            busy = false
                        }
                        OnboardStep.NAME -> scope.launch {
                            busy = true
                            ob.confirm(stageName.trim(), colorHex, callName.trim())
                            busy = false
                            step = OnboardStep.PERMISSIONS.ordinal
                        }
                        OnboardStep.PERMISSIONS -> { ob.finish(); onFinished() }
                        else -> step = (step + 1).coerceAtMost(OnboardStep.PERMISSIONS.ordinal)
                    }
                }, Modifier.testTag("ob_next"), enabled = enabled)
            }
        }
    }
}

/** 從備份還原: the passphrase, the file picker with a preview, then the restore asked twice (it replaces what is on the phone). */
@Composable
private fun RestoreBlock(backup: BackupUi, onRestored: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    Block(Modifier.testTag("ob_restore_block"), fill = Pal.Lavender) {
        KText(stringResource(R.string.ob_restore_hint), size = Type.Small)
        InkField(pass, { pass = it; failed = false }, stringResource(R.string.backup_pass), visual = PasswordVisualTransformation(), tag = "ob_restore_pass")
        PillButton(stringResource(R.string.import_pick), {
            scope.launch { val p = backup.preview(pass); preview = p; failed = p == null }
        }, Modifier.testTag("ob_restore_pick"), Pal.White, Type.Small, enabled = pass.isNotBlank())
        if (failed) KText(stringResource(R.string.import_failed), color = Pal.Warn, size = Type.Small)
        preview?.let { p ->
            KText(stringResource(R.string.import_preview, p), size = Type.Small, bold = true, modifier = Modifier.testTag("ob_restore_preview"))
            PillButton(stringResource(R.string.import_apply), {
                scope.launch {
                    result = backup.apply()
                    preview = null
                    onRestored()
                }
            }, Modifier.testTag("ob_restore_apply"), Pal.PinkStrong, Type.Small)
        }
        result?.let { KText(it, size = Type.Small, bold = true, modifier = Modifier.testTag("ob_restore_result")) }
    }
}

@Composable
private fun Title(res: Int) {
    KText(stringResource(res), size = Type.Title, bold = true, modifier = Modifier.padding(top = 10.dp))
}
