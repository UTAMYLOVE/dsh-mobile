package app.dshmobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agency.core.brain.Brain
import app.agency.core.llm.CallChecks
import app.agency.core.llm.Fail
import app.agency.core.llm.ProviderChoice
import app.agency.core.metering.Provider
import app.dshmobile.R
import app.dshmobile.agency.AgencySettings
import app.dshmobile.security.KeyVault
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.SoftButton
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ModelKeyTags {
    const val PROVIDER = "ag-model-provider-"
    const val MODEL = "ag-model-"
    const val KEY = "ag-model-key"
    const val KEY_SAVE = "ag-model-key-save"
    const val KEY_CLEAR = "ag-model-key-clear"
    const val KEY_STATUS = "ag-model-key-status"
    const val REJECTED = "ag-model-rejected"
    const val COMPAT_URL = "ag-model-compat-url"
    const val COMPAT_MODEL = "ag-model-compat-model"
    const val COMPAT_SAVE = "ag-model-compat-save"
    const val NOTE = "ag-model-note"
}

/**
 * 設定 › 模型與金鑰 (ported from the coach's settings page): which provider, which model, the key (KeyVault only; the field is
 * never filled back and the key never shown), and his compatible service. When the last call came back KEY_REJECTED (DeepSeek's
 * 402 included) it says so in words that do not make him delete a good key.
 */
@Composable
fun ModelKeyPage(settings: AgencySettings, vault: KeyVault, brain: Brain) {
    val revision by settings.revision.collectAsState()
    val lastFail by brain.lastFail.collectAsState()
    val provider = remember(revision) { settings.provider }
    var note by remember { mutableStateOf<String?>(null) }
    Section(stringResource(R.string.ag_set_model)) {
        if (lastFail == Fail.KEY_REJECTED) {
            Text(stringResource(R.string.ag_key_rejected), color = Sakura.Text, fontSize = 14.sp, modifier = Modifier.testTag(ModelKeyTags.REJECTED))
        }
        Label(stringResource(R.string.ag_model_source))
        listOf(
            ProviderChoice.DEEPSEEK to R.string.ag_provider_deepseek,
            ProviderChoice.CLAUDE to R.string.ag_provider_claude,
            ProviderChoice.COMPAT to R.string.ag_provider_compat,
        ).forEach { (p, label) ->
            Choice(stringResource(label), provider == p, ModelKeyTags.PROVIDER + p.name) { settings.provider = p; note = null }
        }
        when (provider) {
            ProviderChoice.DEEPSEEK -> {
                Label(stringResource(R.string.ag_model_which))
                val m = remember(revision) { settings.deepseekModel }
                AgencySettings.DEEPSEEK_MODELS.forEach { c -> Choice(c.zh, m == c.id, ModelKeyTags.MODEL + c.id) { settings.deepseekModel = c.id } }
                KeyEditor(has = { vault.has(Provider.DEEPSEEK) }, put = { vault.put(Provider.DEEPSEEK, it) }, clear = { vault.clear(Provider.DEEPSEEK) }, onNote = { note = it })
            }
            ProviderChoice.CLAUDE -> {
                Label(stringResource(R.string.ag_model_which))
                val m = remember(revision) { settings.claudeModel }
                AgencySettings.CLAUDE_MODELS.forEach { c -> Choice(c.zh, m == c.id, ModelKeyTags.MODEL + c.id) { settings.claudeModel = c.id } }
                KeyEditor(has = { vault.has(Provider.ANTHROPIC) }, put = { vault.put(Provider.ANTHROPIC, it) }, clear = { vault.clear(Provider.ANTHROPIC) }, onNote = { note = it })
            }
            ProviderChoice.COMPAT -> CompatSection(settings, vault, revision) { note = it }
        }
        note?.let { Note(it, Modifier.testTag(ModelKeyTags.NOTE)) }
    }
}

/** A key field: status, a new key (shown as dots), 儲存金鑰 and 清除金鑰. */
@Composable
private fun KeyEditor(has: () -> Boolean, put: (String) -> Unit, clear: () -> Unit, onNote: (String?) -> Unit) {
    var stored by remember { mutableStateOf(has()) }
    var key by remember { mutableStateOf("") }
    val bad = stringResource(R.string.ag_key_bad)
    val saved = stringResource(R.string.ag_saved)
    Note(stringResource(if (stored) R.string.ag_key_set else R.string.ag_key_missing), Modifier.testTag(ModelKeyTags.KEY_STATUS))
    Field(stringResource(R.string.ag_key_new), key, ModelKeyTags.KEY, secret = true) { key = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        PinkButton(stringResource(R.string.ag_key_save), onClick = {
            val k = goodKey(key)
            if (k == null) {
                onNote(bad)
            } else {
                put(k)
                key = ""
                stored = true
                onNote(saved)
            }
        }, enabled = key.isNotBlank(), modifier = Modifier.testTag(ModelKeyTags.KEY_SAVE))
        if (stored) SoftButton(stringResource(R.string.ag_key_clear), onClick = { clear(); stored = false; onNote(null) }, modifier = Modifier.testTag(ModelKeyTags.KEY_CLEAR))
    }
}

/** A pasted key: trimmed, 10 to 300 printable characters, nothing else. */
internal fun goodKey(input: String): String? = CallChecks.usableKey(input)?.takeIf { it.length in 10..300 }

@Composable
private fun CompatSection(settings: AgencySettings, vault: KeyVault, revision: Long, onNote: (String?) -> Unit) {
    var url by remember(revision) { mutableStateOf(settings.compatUrl) }
    var model by remember(revision) { mutableStateOf(settings.compatModel) }
    val json = remember(revision) { settings.compatJson }
    val tools = remember(revision) { settings.compatTools }
    val badUrl = stringResource(R.string.ag_compat_bad_url)
    val saved = stringResource(R.string.ag_saved)
    Field(stringResource(R.string.ag_compat_url), url, ModelKeyTags.COMPAT_URL) { url = it }
    Field(stringResource(R.string.ag_compat_model), model, ModelKeyTags.COMPAT_MODEL) { model = it }
    PinkButton(stringResource(R.string.ag_save), onClick = {
        val u = url.trim()
        if (u.isNotEmpty() && (!u.startsWith("https://", ignoreCase = true) || u.toHttpUrlOrNull() == null)) {
            onNote(badUrl)
        } else {
            // A key belongs to the service it was entered for: a new address never gets the old key.
            if (u.toHttpUrlOrNull()?.host != settings.compatUrl.toHttpUrlOrNull()?.host) vault.clearNamed(AgencySettings.COMPAT_KEY)
            settings.compatUrl = u
            settings.compatModel = model
            onNote(saved)
        }
    }, modifier = Modifier.testTag(ModelKeyTags.COMPAT_SAVE))
    Note(stringResource(R.string.ag_compat_key_note))
    Toggle(stringResource(R.string.ag_compat_json), json) { settings.compatJson = it }
    Toggle(stringResource(R.string.ag_compat_tools), tools) { settings.compatTools = it }
    KeyEditor(
        has = { vault.hasNamed(AgencySettings.COMPAT_KEY) }, put = { vault.putNamed(AgencySettings.COMPAT_KEY, it) },
        clear = { vault.clearNamed(AgencySettings.COMPAT_KEY) }, onNote = onNote,
    )
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Sakura.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange)
    }
}
