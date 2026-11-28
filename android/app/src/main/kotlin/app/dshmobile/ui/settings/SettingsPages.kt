package app.dshmobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.dshmobile.R
import app.agency.core.agent.Attachment
import app.dshmobile.ui.BackupUi
import app.dshmobile.ui.MediaUi
import app.dshmobile.ui.OldDataUi
import app.dshmobile.ui.SettingsUi
import app.dshmobile.ui.rememberIoRunner
import app.dshmobile.ui.rememberLoaded
import app.dshmobile.ui.theme.Block
import app.dshmobile.ui.theme.ChipPill
import app.dshmobile.ui.theme.Glyph
import app.dshmobile.ui.theme.GlyphIcon
import app.dshmobile.ui.theme.InkField
import app.dshmobile.ui.theme.KText
import app.dshmobile.ui.theme.Pal
import app.dshmobile.ui.theme.PageFrame
import app.dshmobile.ui.theme.PillButton
import app.dshmobile.ui.theme.Type
import app.dshmobile.ui.theme.sticker
import app.dshmobile.ui.theme.tap
import kotlinx.coroutines.launch

/** The settings sub-pages (SPEC I). */
enum class SettingsPage(val title: Int) {
    MENU(R.string.set_title),
    RULES(R.string.set_rules),
    MODEL(R.string.set_model),
    SPEND(R.string.set_spend),
    MEMORY(R.string.set_memory),
    REMIND(R.string.set_remind),
    BACKUP(R.string.set_backup),
    IMPORT(R.string.set_import),
    OLD(R.string.set_old),
    VERSION(R.string.set_version),
    LICENSES(R.string.set_licenses),
}

/** Settings: a menu and its pages. [startAt] opens a page directly (tests, the screenshots). */
@Composable
fun SettingsRoot(
    settings: SettingsUi,
    backup: BackupUi,
    oldData: OldDataUi,
    onBack: () -> Unit,
    startAt: SettingsPage = SettingsPage.MENU,
    /** The document picker for 匯入; null = the button is off. */
    media: MediaUi? = null,
    /** He picked a file of old records: the host sends it to 小葵 with 「幫我匯入這個檔案」 and shows the conversation. */
    onImportFile: (Attachment.File) -> Unit = {},
) {
    var page by rememberSaveable(startAt) { mutableIntStateOf(startAt.ordinal) }
    val current = SettingsPage.entries[page]
    val back = { if (current == SettingsPage.MENU) onBack() else page = SettingsPage.MENU.ordinal }
    PageFrame(stringResource(current.title), back) {
        when (current) {
            SettingsPage.MENU -> SettingsPage.entries.drop(1).forEach { p ->
                Row(
                    Modifier.fillMaxWidth().testTag("set_${p.name}").sticker(Pal.White, androidx.compose.foundation.shape.RoundedCornerShape(16.dp), 2.dp)
                        .tap({ page = p.ordinal }).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KText(stringResource(p.title), Modifier.weight(1f), bold = true)
                    GlyphIcon(Glyph.ArrowRight, size = 16.dp)
                }
            }
            SettingsPage.RULES -> RulesPage(settings)
            SettingsPage.MODEL -> ModelPage(settings)
            SettingsPage.SPEND -> SpendPage(settings)
            SettingsPage.MEMORY -> MemoryPage(settings)
            SettingsPage.REMIND -> RemindPage(settings)
            SettingsPage.BACKUP -> {
                BackupPage(backup)
                RestoreSection(backup)
            }
            SettingsPage.IMPORT -> ImportPage(media, onImportFile)
            SettingsPage.OLD -> OldPage(oldData)
            SettingsPage.VERSION -> VersionPage(settings)
            SettingsPage.LICENSES -> LicensesPage(settings)
        }
    }
}

/** 「確定嗎？」 asked twice: [onConfirmed] runs only after the second yes. */
@Composable
private fun ConfirmTwice(first: String, second: String, onCancel: () -> Unit, onConfirmed: () -> Unit) {
    var asked by remember { mutableIntStateOf(1) }
    Block(Modifier.testTag("confirm_$asked"), fill = Pal.Butter) {
        KText(if (asked == 1) first else second, bold = true)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(stringResource(R.string.btn_cancel), onCancel, Modifier.testTag("confirm_cancel"), Pal.White, Type.Small)
            PillButton(stringResource(if (asked == 1) R.string.set_confirm_yes else R.string.set_confirm_yes_again), {
                if (asked == 1) asked = 2 else onConfirmed()
            }, Modifier.testTag("confirm_yes"), Pal.PinkStrong, Type.Small)
        }
    }
}

@Composable
private fun RulesPage(settings: SettingsUi) {
    val groups = rememberLoaded { settings.rulesGroups() }.value ?: return
    val edits = remember { mutableStateMapOf<String, String>() }
    var asking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val io = app.dshmobile.ui.LocalIo.current
    val scope = rememberCoroutineScope()
    KText(stringResource(R.string.rules_hint), size = Type.Small, alpha = 0.75f)
    groups.forEach { g ->
        Block {
            KText(g.title, bold = true)
            g.fields.forEach { f ->
                KText(f.label, size = Type.Small, alpha = 0.8f)
                InkField(edits[f.key] ?: f.value, { edits[f.key] = it }, f.hint.ifBlank { f.label }, tag = "rule_${f.key}")
            }
        }
    }
    val changes = groups.flatMap { it.fields }.filter { f -> edits[f.key]?.let { it != f.value } == true }.associate { it.key to edits[it.key]!! }
    message?.let { KText(it, bold = true, modifier = Modifier.testTag("rules_message")) }
    if (asking) {
        ConfirmTwice(stringResource(R.string.rules_confirm_1), stringResource(R.string.rules_confirm_2), { asking = false }) {
            asking = false
            scope.launch {
                val failure = kotlinx.coroutines.withContext(io) { settings.saveRules(changes) }
                message = failure ?: "已存好。"
                if (failure == null) edits.clear()
            }
        }
    } else {
        PillButton(stringResource(R.string.btn_save), { asking = true }, Modifier.testTag("rules_save"), enabled = changes.isNotEmpty())
    }
}

@Composable
private fun ModelPage(settings: SettingsUi) {
    val model = rememberLoaded { settings.model() }
    val run = rememberIoRunner()
    val scope = rememberCoroutineScope()
    val m = model.value ?: return
    var claudeKey by remember { mutableStateOf("") }
    var deepKey by remember { mutableStateOf("") }
    var keyMessage by remember { mutableStateOf<String?>(null) }
    Block {
        KText(stringResource(R.string.model_chat), bold = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipPill(stringResource(R.string.model_all_claude), { run { settings.setModel(false, m.tier) }; model.reload() }, Modifier.testTag("model_claude"), if (!m.useDeepSeekForChat) Pal.PinkStrong else Pal.Butter)
            ChipPill(
                stringResource(R.string.model_chat_cheap), { run { settings.setModel(true, m.tier) }; model.reload() }, Modifier.testTag("model_cheap"),
                if (m.useDeepSeekForChat) Pal.PinkStrong else Pal.Butter, enabled = m.deepSeekKeySet,
            )
        }
        if (!m.deepSeekKeySet) KText(stringResource(R.string.model_cheap_needs_key), size = Type.Small, alpha = 0.75f, modifier = Modifier.testTag("model_cheap_reason"))
    }
    Block {
        KText(stringResource(R.string.model_tier), bold = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("smart" to R.string.tier_smart, "balanced" to R.string.tier_balanced, "cheap" to R.string.tier_cheap).forEach { (k, label) ->
                ChipPill(stringResource(label), { run { settings.setModel(m.useDeepSeekForChat, k) }; model.reload() }, Modifier.testTag("tier_$k"), if (m.tier == k) Pal.PinkStrong else Pal.Butter)
            }
        }
    }
    Block {
        KText(stringResource(R.string.model_keys), bold = true)
        KText(stringResource(R.string.ob_key1_title), bold = true, size = Type.Small)
        KText(stringResource(R.string.ob_key1_text), size = Type.Small, alpha = 0.8f)
        KText(stringResource(if (m.claudeKeySet) R.string.key_set else R.string.key_not_set), size = Type.Small, alpha = 0.75f)
        InkField(claudeKey, { claudeKey = it }, stringResource(R.string.ob_key_claude), visual = PasswordVisualTransformation(), tag = "set_key_claude")
        PillButton(stringResource(R.string.btn_save), {
            scope.launch {
                keyMessage = if (settings.saveKey("claude", claudeKey.trim())) "已存好。" else "存不起來。"
                claudeKey = ""; model.reload()
            }
        }, Modifier.testTag("save_key_claude"), size = Type.Small, enabled = claudeKey.isNotBlank())
        KText(stringResource(R.string.ob_key2_title), bold = true, size = Type.Small, modifier = Modifier.padding(top = 6.dp))
        KText(stringResource(R.string.ob_key2_text), size = Type.Small, alpha = 0.8f)
        KText(stringResource(R.string.ob_key2_without), size = Type.Small, alpha = 0.8f)
        KText(stringResource(if (m.deepSeekKeySet) R.string.key_set else R.string.key_not_set), size = Type.Small, alpha = 0.75f)
        InkField(deepKey, { deepKey = it }, stringResource(R.string.ob_key_deepseek), visual = PasswordVisualTransformation(), tag = "set_key_deepseek")
        PillButton(stringResource(R.string.btn_save), {
            scope.launch {
                keyMessage = if (settings.saveKey("deepseek", deepKey.trim())) "已存好。" else "存不起來。"
                deepKey = ""; model.reload()
            }
        }, Modifier.testTag("save_key_deepseek"), size = Type.Small, enabled = deepKey.isNotBlank())
        keyMessage?.let { KText(it, size = Type.Small, bold = true) }
    }
}

@Composable
private fun SpendPage(settings: SettingsUi) {
    val spend = rememberLoaded { settings.spend() }
    val run = rememberIoRunner()
    val s = spend.value ?: return
    var cap by remember(s.capYuan) { mutableStateOf(s.capYuan?.toString() ?: "") }
    Block {
        KText(stringResource(R.string.spend_today, s.today))
        KText(stringResource(R.string.spend_month, s.month))
    }
    Block {
        KText(stringResource(R.string.spend_cap), bold = true)
        InkField(cap, { cap = it.filter(Char::isDigit) }, stringResource(R.string.spend_cap_hint), keyboard = KeyboardType.Number, tag = "spend_cap")
        PillButton(stringResource(R.string.btn_save), { run { settings.setCap(cap.toIntOrNull()) }; spend.reload() }, Modifier.testTag("spend_save"), size = Type.Small)
    }
}

@Composable
private fun MemoryPage(settings: SettingsUi) {
    val lines = rememberLoaded { settings.memory() }
    val run = rememberIoRunner()
    KText(stringResource(R.string.memory_hint), size = Type.Small, alpha = 0.75f)
    val l = lines.value ?: return
    if (l.isEmpty()) KText(stringResource(R.string.memory_empty), alpha = 0.7f)
    l.forEachIndexed { i, line ->
        Row(Modifier.fillMaxWidth().sticker(Pal.White, androidx.compose.foundation.shape.RoundedCornerShape(14.dp), 2.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            KText(line, Modifier.weight(1f))
            Box(Modifier.size(32.dp).tap({ run { settings.forget(line) }; lines.reload() }).testTag("forget_$i"), contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.Close, size = 16.dp, color = Pal.Ink.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun RemindPage(settings: SettingsUi) {
    val run = rememberIoRunner()
    var sent by remember { mutableStateOf(false) }
    Block(fill = Pal.Butter) {
        KText(stringResource(R.string.ob_battery_title), bold = true)
        KText(stringResource(R.string.ob_battery_text), size = Type.Small)
        PillButton(stringResource(R.string.ob_battery_btn), { settings.openBatteryGuide() }, Modifier.testTag("remind_battery"), Pal.White, Type.Small)
    }
    Block {
        KText(stringResource(R.string.remind_test_text), size = Type.Small)
        PillButton(stringResource(R.string.remind_test), { run { settings.testNotification() }; sent = true }, Modifier.testTag("remind_test"), Pal.Mint, Type.Small)
        if (sent) KText(stringResource(R.string.remind_sent), size = Type.Small, bold = true)
    }
}

@Composable
private fun BackupPage(backup: BackupUi) {
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    KText(stringResource(R.string.backup_hint), size = Type.Small, alpha = 0.75f)
    InkField(pass, { pass = it }, stringResource(R.string.backup_pass), visual = PasswordVisualTransformation(), tag = "backup_pass")
    PillButton(stringResource(R.string.backup_export), { scope.launch { result = backup.export(pass) } }, Modifier.testTag("backup_export"), enabled = pass.length >= 6)
    if (pass.isNotEmpty() && pass.length < 6) KText(stringResource(R.string.backup_pass_short), size = Type.Small, color = Pal.Warn)
    result?.let { KText(it, bold = true, modifier = Modifier.testTag("backup_result")) }
}

/** 匯入: old records (記帳、吃的、體重、備忘錄) go in by giving the file to 小葵, who shows what she read before anything is written. */
@Composable
private fun ImportPage(media: MediaUi?, onImportFile: (Attachment.File) -> Unit) {
    val scope = rememberCoroutineScope()
    var nothing by remember { mutableStateOf(false) }
    Block {
        KText(stringResource(R.string.import_old_hint), size = Type.Small)
        KText(stringResource(R.string.import_old_kinds), size = Type.Small, alpha = 0.8f)
        KText(stringResource(R.string.import_old_safe), size = Type.Small, alpha = 0.8f)
    }
    PillButton(stringResource(R.string.import_old_pick), {
        scope.launch {
            val f = media?.pickFile()
            if (f == null) nothing = true else onImportFile(f)
        }
    }, Modifier.testTag("import_file"), enabled = media != null)
    if (nothing) KText(stringResource(R.string.import_old_none), size = Type.Small, color = Pal.Warn, modifier = Modifier.testTag("import_file_none"))
    KText(stringResource(R.string.import_old_backup_note), size = Type.Tiny, alpha = 0.65f)
}

@Composable
private fun RestoreSection(backup: BackupUi) {
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    KText(stringResource(R.string.restore_title), bold = true, modifier = Modifier.padding(top = 10.dp))
    KText(stringResource(R.string.import_hint), size = Type.Small, alpha = 0.75f)
    InkField(pass, { pass = it; failed = false }, stringResource(R.string.backup_pass), visual = PasswordVisualTransformation(), tag = "import_pass")
    PillButton(stringResource(R.string.import_pick), {
        scope.launch { val p = backup.preview(pass); preview = p; failed = p == null }
    }, Modifier.testTag("import_pick"), enabled = pass.isNotBlank())
    if (failed) KText(stringResource(R.string.import_failed), color = Pal.Warn)
    preview?.let { p ->
        Block(Modifier.testTag("import_preview"), fill = Pal.Mint) { KText(stringResource(R.string.import_preview, p)) }
        if (asking) {
            ConfirmTwice(stringResource(R.string.import_confirm_1), stringResource(R.string.import_confirm_2), { asking = false }) {
                asking = false
                scope.launch { result = backup.apply(); preview = null }
            }
        } else {
            PillButton(stringResource(R.string.import_apply), { asking = true }, Modifier.testTag("import_apply"), Pal.PinkStrong)
        }
    }
    result?.let { KText(it, bold = true, modifier = Modifier.testTag("import_result")) }
}

@Composable
private fun OldPage(old: OldDataUi) {
    val msgs = rememberLoaded { if (old.available()) old.messages() else null }
    val run = rememberIoRunner()
    var asking by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }
    val list = msgs.value
    if (deleted || list == null) {
        KText(stringResource(if (deleted) R.string.old_deleted else R.string.old_none), alpha = 0.75f)
        return
    }
    KText(stringResource(R.string.old_hint), size = Type.Small, alpha = 0.75f)
    list.forEach { m ->
        Block(fill = if (m.who == "你") Pal.Lavender else Pal.White) {
            KText("${m.who}　${m.time}", size = Type.Tiny, alpha = 0.7f)
            KText(m.text, size = Type.Small)
        }
    }
    if (asking) {
        ConfirmTwice(stringResource(R.string.old_confirm_1), stringResource(R.string.old_confirm_2), { asking = false }) {
            asking = false
            run { old.delete() }
            deleted = true
        }
    } else {
        PillButton(stringResource(R.string.old_delete), { asking = true }, Modifier.testTag("old_delete"), Pal.Pink)
    }
}

@Composable
private fun VersionPage(settings: SettingsUi) {
    val u = rememberLoaded { settings.update() }.value ?: return
    Block {
        KText(stringResource(R.string.version_now, u.version), bold = true)
        if (u.newer != null) {
            KText(stringResource(R.string.version_newer, u.newer), size = Type.Small)
            PillButton(stringResource(R.string.version_open), { settings.openUpdate() }, Modifier.testTag("version_open"), Pal.Mint, Type.Small)
        } else {
            KText(stringResource(R.string.version_latest), size = Type.Small, alpha = 0.75f)
        }
    }
}

@Composable
private fun LicensesPage(settings: SettingsUi) {
    val list = rememberLoaded { settings.licenses() }.value ?: return
    var open by remember { mutableIntStateOf(-1) }
    list.forEachIndexed { i, l ->
        Block(Modifier.tap({ open = if (open == i) -1 else i }).testTag("license_$i")) {
            KText(l.name, bold = true)
            if (open == i) KText(l.text, size = Type.Tiny)
        }
    }
}
