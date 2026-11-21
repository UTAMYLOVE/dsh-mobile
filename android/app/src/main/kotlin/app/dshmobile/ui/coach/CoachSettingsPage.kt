package app.dshmobile.ui.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.coach.CoachProfile
import app.dshmobile.coach.CoachProvider
import app.dshmobile.coach.CoachRules
import app.dshmobile.coach.CoachSettings
import app.dshmobile.coach.CoachStore
import app.dshmobile.gateway.Provider
import app.dshmobile.security.KeyVault
import app.dshmobile.ui.fog.FogPanel
import app.dshmobile.ui.shell.LocalPagePadding
import app.dshmobile.ui.shell.Tags
import app.dshmobile.ui.theme.Sakura
import java.time.DayOfWeek

/** 教練設定: the model, the 放縱餐 day, opening on the coach, and (folded) the body numbers and the food table. The coach never writes here. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoachSettingsPage(settings: CoachSettings, vault: KeyVault, store: CoachStore) {
    val revision by settings.revision.collectAsState()
    val state by store.state.collectAsState()
    val pad = LocalPagePadding.current
    // Read after every change: [revision] makes this recompose.
    val provider = remember(revision) { settings.provider }
    val profile = remember(revision) { settings.profile }
    Column(
        Modifier.fillMaxSize().testTag(Tags.COACH_SETTINGS).verticalScroll(rememberScrollState()).padding(pad),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FogPanel(title = stringResource(R.string.coach_set_model)) {
            Label(stringResource(R.string.coach_set_source))
            Choice(stringResource(R.string.coach_provider_deepseek), provider == CoachProvider.DEEPSEEK) { settings.provider = CoachProvider.DEEPSEEK }
            Choice(stringResource(R.string.coach_provider_claude), provider == CoachProvider.CLAUDE) { settings.provider = CoachProvider.CLAUDE }
            Choice(stringResource(R.string.coach_provider_compat), provider == CoachProvider.COMPAT) { settings.provider = CoachProvider.COMPAT }
            when (provider) {
                CoachProvider.DEEPSEEK -> {
                    Label(stringResource(R.string.coach_set_which))
                    val m = remember(revision) { settings.deepseekModel }
                    CoachSettings.DEEPSEEK_MODELS.forEach { c -> Choice(c.zh, m == c.id) { settings.deepseekModel = c.id } }
                    KeyStatus(vault.has(Provider.DEEPSEEK))
                }
                CoachProvider.CLAUDE -> {
                    Label(stringResource(R.string.coach_set_which))
                    val m = remember(revision) { settings.claudeModel }
                    CoachSettings.CLAUDE_MODELS.forEach { c -> Choice(c.zh, m == c.id) { settings.claudeModel = c.id } }
                    KeyStatus(vault.has(Provider.ANTHROPIC))
                }
                CoachProvider.COMPAT -> CompatSection(settings, vault, revision)
            }
        }
        FogPanel(title = stringResource(R.string.coach_set_cheat)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DayOfWeek.entries.forEach { d ->
                    val on = profile.cheatDay == d
                    Text(
                        stringResource(R.string.coach_weekday, CoachRules.weekdayZh(d)),
                        color = Sakura.Text, fontSize = 14.sp,
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .background(if (on) Sakura.Primary.copy(alpha = .85f) else Sakura.White.copy(alpha = .7f), RoundedCornerShape(50))
                            .clickable(role = Role.RadioButton) { settings.profile = profile.copy(cheatDay = d) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }
            }
        }
        FogPanel(title = stringResource(R.string.coach_set_open)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.coach_set_open_hint), color = Sakura.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                val open = remember(revision) { settings.openOnCoach }
                Switch(checked = open, onCheckedChange = { settings.openOnCoach = it })
            }
        }
        var advanced by rememberSaveable { mutableStateOf(false) }
        FogPanel(title = stringResource(R.string.coach_set_advanced)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.coach_set_advanced_hint), color = Sakura.TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(checked = advanced, onCheckedChange = { advanced = it })
            }
            if (advanced) {
                BodyNumbers(settings, profile)
                Label(stringResource(R.string.coach_set_food))
                state.food.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val flags = buildString {
                            if (f.estimated) append(" ・ 估算")
                            if (f.fried) append(" ・ 炸物")
                        }
                        val protein = f.proteinG?.let { " ・ 蛋白質 ${it.toInt()} 克" } ?: ""
                        Text("${f.name} ${f.kcal} 大卡$protein$flags", color = Sakura.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { store.update { s -> s.copy(food = s.food - f) } }) { Text(stringResource(R.string.coach_delete), color = Sakura.Text) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, color = Sakura.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, color = Sakura.Text, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun KeyStatus(has: Boolean) = Text(
    stringResource(if (has) R.string.coach_key_set else R.string.coach_key_missing),
    color = Sakura.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
)

@Composable
private fun CompatSection(settings: CoachSettings, vault: KeyVault, revision: Long) {
    var url by remember(revision) { mutableStateOf(settings.compatUrl) }
    var model by remember(revision) { mutableStateOf(settings.compatModel) }
    var key by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(vault.hasNamed(CoachSettings.COMPAT_KEY)) }
    var note by remember { mutableStateOf<String?>(null) }
    val badUrl = stringResource(R.string.coach_compat_bad_url)
    val badKey = stringResource(R.string.coach_compat_bad_key)
    val saved = stringResource(R.string.coach_saved)
    Field(stringResource(R.string.coach_compat_url), url) { url = it }
    Field(stringResource(R.string.coach_compat_model), model) { model = it }
    OutlinedTextField(
        value = key, onValueChange = { key = it }, singleLine = true,
        label = { Text(stringResource(if (hasKey) R.string.coach_compat_key_replace else R.string.coach_compat_key)) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            val u = url.trim()
            when {
                u.isNotEmpty() && !u.startsWith("https://") -> note = badUrl
                key.isNotEmpty() && key.trim().length !in 10..300 -> note = badKey
                else -> {
                    settings.compatUrl = u
                    settings.compatModel = model
                    if (key.isNotEmpty()) {
                        vault.putNamed(CoachSettings.COMPAT_KEY, key.trim())
                        key = ""
                        hasKey = true
                    }
                    note = saved
                }
            }
        }) { Text(stringResource(R.string.coach_save), color = Sakura.Text) }
        if (hasKey) TextButton(onClick = { vault.clearNamed(CoachSettings.COMPAT_KEY); hasKey = false }) {
            Text(stringResource(R.string.coach_compat_key_clear), color = Sakura.Text)
        }
    }
    note?.let { Text(it, color = Sakura.TextSecondary, fontSize = 13.sp) }
}

@Composable
private fun Field(label: String, value: String, number: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, label = { Text(label) },
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BodyNumbers(settings: CoachSettings, p: CoachProfile) {
    var quota by remember(p) { mutableStateOf(p.quotaKcal.toString()) }
    var protein by remember(p) { mutableStateOf(p.proteinG.toString()) }
    var fat by remember(p) { mutableStateOf(p.fatG.toString()) }
    var carbs by remember(p) { mutableStateOf(p.carbsG.toString()) }
    var height by remember(p) { mutableStateOf(CoachRules.kg(p.heightCm)) }
    var start by remember(p) { mutableStateOf(CoachRules.kg(p.startWeightKg)) }
    var goals by remember(p) { mutableStateOf(p.goals.joinToString("、") { CoachRules.kg(it) }) }
    var bedtime by remember(p) { mutableStateOf(p.bedtime) }
    var margin by remember(p) { mutableStateOf(p.marginPct.toString()) }
    var planned by remember(p) { mutableStateOf(p.plannedMeal) }
    var minutes by remember(p) { mutableStateOf(p.evidenceMinutes.toString()) }
    var dayStart by remember(p) { mutableStateOf(p.dayStartHour.toString()) }
    var note by remember { mutableStateOf<String?>(null) }
    val saved = stringResource(R.string.coach_saved)
    Field(stringResource(R.string.coach_num_quota), quota, number = true) { quota = it }
    Field(stringResource(R.string.coach_num_protein), protein, number = true) { protein = it }
    Field(stringResource(R.string.coach_num_fat), fat, number = true) { fat = it }
    Field(stringResource(R.string.coach_num_carbs), carbs, number = true) { carbs = it }
    Field(stringResource(R.string.coach_num_height), height, number = true) { height = it }
    Field(stringResource(R.string.coach_num_start), start, number = true) { start = it }
    Field(stringResource(R.string.coach_num_goals), goals) { goals = it }
    Field(stringResource(R.string.coach_num_bedtime), bedtime) { bedtime = it }
    Field(stringResource(R.string.coach_num_margin), margin, number = true) { margin = it }
    Field(stringResource(R.string.coach_num_planned), planned) { planned = it }
    Field(stringResource(R.string.coach_num_minutes), minutes, number = true) { minutes = it }
    Field(stringResource(R.string.coach_num_day_start), dayStart, number = true) { dayStart = it }
    Text(stringResource(R.string.coach_num_floor_note), color = Sakura.TextSecondary, fontSize = 12.sp)
    TextButton(onClick = {
        settings.profile = p.copy(
            quotaKcal = quota.trim().toIntOrNull() ?: p.quotaKcal,
            proteinG = protein.trim().toIntOrNull() ?: p.proteinG,
            fatG = fat.trim().toIntOrNull() ?: p.fatG,
            carbsG = carbs.trim().toIntOrNull() ?: p.carbsG,
            heightCm = height.trim().toDoubleOrNull() ?: p.heightCm,
            startWeightKg = start.trim().toDoubleOrNull() ?: p.startWeightKg,
            goals = CoachSettings.parseGoals(goals) ?: p.goals,
            bedtime = bedtime.trim(),
            marginPct = margin.trim().toIntOrNull() ?: p.marginPct,
            plannedMeal = planned.trim().ifEmpty { p.plannedMeal },
            evidenceMinutes = minutes.trim().toIntOrNull() ?: p.evidenceMinutes,
            dayStartHour = dayStart.trim().toIntOrNull() ?: p.dayStartHour,
        )
        note = saved
    }) { Text(stringResource(R.string.coach_save), color = Sakura.Text) }
    note?.let { Text(it, color = Sakura.TextSecondary, fontSize = 13.sp) }
}
