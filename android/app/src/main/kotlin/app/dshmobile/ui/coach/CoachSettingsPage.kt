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
import app.agency.core.rules.CoachRules
import app.dshmobile.coach.CoachSettings
import app.dshmobile.coach.CoachStore
import app.agency.core.metering.Provider
import app.dshmobile.security.KeyVault
import app.dshmobile.ui.fog.FogPanel
import app.dshmobile.ui.shell.LocalPagePadding
import app.dshmobile.ui.shell.Tags
import app.dshmobile.ui.theme.Sakura
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import app.dshmobile.coach.AgencyRules
import app.dshmobile.coach.AgencySchedule
import app.dshmobile.coach.SlotTimes

/** 設定 of the company: the model, the 公司規章 (read only), the company's name, join day and schedule, opening on the 練習生證, and (folded) the body numbers and the food table. No role ever writes here. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoachSettingsPage(
    settings: CoachSettings,
    vault: KeyVault,
    store: CoachStore,
    /** Runs off the main thread (the engine's scope). */
    onDeleteFood: (String) -> Unit,
    /** Saves 「打開應用程式先進教練」 and applies it to the shell at once. */
    onOpenOnCoach: (Boolean) -> Unit = { settings.openOnCoach = it },
) {
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
        // 公司規章: fixed in code; shown, never edited (agency spec).
        FogPanel(title = stringResource(R.string.agency_rules_title)) {
            Column(Modifier.testTag(Tags.AGENCY_RULES), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    stringResource(R.string.agency_rule_quota, AgencyRules.QUOTA_KCAL, AgencyRules.PROTEIN_G),
                    stringResource(R.string.agency_rule_floor),
                    stringResource(R.string.agency_rule_cheat, CoachRules.weekdayZh(AgencyRules.CHEAT_DAY)),
                    stringResource(R.string.agency_rule_zero, AgencyRules.ZERO_CAL_PER_WEEK),
                    stringResource(R.string.agency_rule_meal, AgencyRules.MEAL_MAX_KCAL),
                    stringResource(R.string.agency_rule_over),
                    stringResource(R.string.agency_rule_person),
                ).forEach { Text("・$it", color = Sakura.Text, fontSize = 14.sp, lineHeight = 20.sp) }
                Text(stringResource(R.string.agency_rules_note), color = Sakura.TextSecondary, fontSize = 12.sp)
            }
        }
        CompanyPanel(settings, revision)
        FogPanel(title = stringResource(R.string.coach_set_open)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.coach_set_open_hint), color = Sakura.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                val open = remember(revision) { settings.openOnCoach }
                Switch(checked = open, onCheckedChange = onOpenOnCoach, modifier = Modifier.testTag(Tags.COACH_OPEN_SWITCH))
            }
        }
        var advanced by rememberSaveable { mutableStateOf(false) }
        FogPanel(title = stringResource(R.string.coach_set_advanced)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.coach_set_advanced_hint), color = Sakura.TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(checked = advanced, onCheckedChange = { advanced = it }, modifier = Modifier.testTag(Tags.COACH_ADVANCED))
            }
            if (advanced) {
                BodyNumbers(settings, profile, state.food.map { it.name })
                Label(stringResource(R.string.coach_set_food))
                state.food.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val flags = buildString {
                            if (f.estimated) append(" ・ 估算")
                            if (f.fried) append(" ・ 炸物")
                        }
                        val protein = f.proteinG?.let { " ・ 蛋白質 ${it.toInt()} 克" } ?: ""
                        Text("${f.name} ${f.kcal} 大卡$protein$flags", color = Sakura.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onDeleteFood(f.name) }, modifier = Modifier.testTag(Tags.COACH_FOOD_DELETE + f.name)) { Text(stringResource(R.string.coach_delete), color = Sakura.Text) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, color = Sakura.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun Choice(label: String, selected: Boolean, tag: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.RadioButton, onClick = onClick)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
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
    val savedHost = remember(revision) { settings.compatUrl.toHttpUrlOrNull()?.host }
    var note by remember { mutableStateOf<String?>(null) }
    val badUrl = stringResource(R.string.coach_compat_bad_url)
    val badKey = stringResource(R.string.coach_compat_bad_key)
    val saved = stringResource(R.string.coach_saved)
    Field(stringResource(R.string.coach_compat_url), url, tag = Tags.COACH_COMPAT_URL) { url = it }
    Field(stringResource(R.string.coach_compat_model), model) { model = it }
    OutlinedTextField(
        value = key, onValueChange = { key = it }, singleLine = true,
        label = { Text(stringResource(if (hasKey) R.string.coach_compat_key_replace else R.string.coach_compat_key)) },
        supportingText = if (hasKey && savedHost != null) ({ Text(stringResource(R.string.coach_compat_key_for)) }) else null,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(modifier = Modifier.testTag(Tags.COACH_COMPAT_SAVE), onClick = {
            val u = url.trim()
            when {
                u.isNotEmpty() && !u.startsWith("https://") -> note = badUrl
                key.isNotEmpty() && key.trim().length !in 10..300 -> note = badKey
                else -> {
                    // A key belongs to the service it was entered for: a new address never gets the old key.
                    val newHost = u.toHttpUrlOrNull()?.host
                    if (hasKey && newHost != savedHost) {
                        vault.clearNamed(CoachSettings.COMPAT_KEY)
                        hasKey = false
                    }
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
private fun Field(label: String, value: String, number: Boolean = false, tag: String? = null, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, label = { Text(label) },
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier),
    )
}

@Composable
private fun BodyNumbers(settings: CoachSettings, p: CoachProfile, foods: List<String>) {
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
    Field(stringResource(R.string.coach_num_fat), fat, number = true) { fat = it }
    Field(stringResource(R.string.coach_num_carbs), carbs, number = true) { carbs = it }
    Field(stringResource(R.string.coach_num_height), height, number = true) { height = it }
    Field(stringResource(R.string.coach_num_start), start, number = true) { start = it }
    Field(stringResource(R.string.coach_num_goals), goals) { goals = it }
    Field(stringResource(R.string.coach_num_bedtime), bedtime) { bedtime = it }
    Field(stringResource(R.string.coach_num_margin), margin, number = true) { margin = it }
    // The planned meal is picked from the food table, so its kcal is always known.
    Label(stringResource(R.string.coach_num_planned))
    foods.forEach { name -> Choice(name, planned == name, Tags.COACH_PLANNED + name) { planned = name } }
    Field(stringResource(R.string.coach_num_minutes), minutes, number = true) { minutes = it }
    Field(stringResource(R.string.coach_num_day_start), dayStart, number = true) { dayStart = it }
    Text(stringResource(R.string.coach_num_floor_note), color = Sakura.TextSecondary, fontSize = 12.sp)
    TextButton(onClick = {
        settings.profile = p.copy(
            fatG = fat.trim().toIntOrNull() ?: p.fatG,
            carbsG = carbs.trim().toIntOrNull() ?: p.carbsG,
            heightCm = height.trim().toDoubleOrNull() ?: p.heightCm,
            startWeightKg = start.trim().toDoubleOrNull() ?: p.startWeightKg,
            goals = CoachSettings.parseGoals(goals) ?: p.goals,
            bedtime = bedtime.trim(),
            marginPct = margin.trim().toIntOrNull() ?: p.marginPct,
            plannedMeal = planned.takeIf { it in foods } ?: p.plannedMeal,
            evidenceMinutes = minutes.trim().toIntOrNull() ?: p.evidenceMinutes,
            dayStartHour = dayStart.trim().toIntOrNull() ?: p.dayStartHour,
        )
        note = saved
    }) { Text(stringResource(R.string.coach_save), color = Sakura.Text) }
    note?.let { Text(it, color = Sakura.TextSecondary, fontSize = 13.sp) }
}

/** The company's name, 入社日 and the 經紀人's schedule times. */
@Composable
private fun CompanyPanel(settings: CoachSettings, revision: Long) {
    var name by remember(revision) { mutableStateOf(settings.companyName) }
    var join by remember(revision) { mutableStateOf(settings.joinDate.toString()) }
    val t = remember(revision) { settings.slotTimes }
    var lunch by remember(revision) { mutableStateOf(AgencySchedule.clock(t.lunch)) }
    var dinner by remember(revision) { mutableStateOf(AgencySchedule.clock(t.dinner)) }
    var chicken by remember(revision) { mutableStateOf(AgencySchedule.clock(t.chicken)) }
    var night by remember(revision) { mutableStateOf(AgencySchedule.clock(t.night)) }
    var note by remember { mutableStateOf<String?>(null) }
    val saved = stringResource(R.string.coach_saved)
    val bad = stringResource(R.string.agency_bad_time)
    FogPanel(title = stringResource(R.string.agency_company_title)) {
        Field(stringResource(R.string.agency_company_name), name, tag = Tags.AGENCY_COMPANY_NAME) { name = it }
        Field(stringResource(R.string.agency_join_date), join) { join = it }
        Label(stringResource(R.string.agency_schedule_title))
        Field(stringResource(R.string.agency_t_lunch), lunch) { lunch = it }
        Field(stringResource(R.string.agency_t_dinner), dinner) { dinner = it }
        Field(stringResource(R.string.agency_t_chicken), chicken) { chicken = it }
        Field(stringResource(R.string.agency_t_night), night) { night = it }
        TextButton(modifier = Modifier.testTag(Tags.AGENCY_COMPANY_SAVE), onClick = {
            val times = listOf(lunch, dinner, chicken, night).map(::minuteOf)
            val day = runCatching { java.time.LocalDate.parse(join.trim()) }.getOrNull()
            if (times.any { it == null } || day == null) {
                note = bad
            } else {
                settings.companyName = name
                settings.joinDate = day
                settings.slotTimes = SlotTimes(times[0]!!, times[1]!!, times[2]!!, times[3]!!)
                note = saved
            }
        }) { Text(stringResource(R.string.coach_save), color = Sakura.Text) }
        note?.let { Text(it, color = Sakura.TextSecondary, fontSize = 13.sp) }
    }
}

/** 「17:30」 → minutes after midnight; null when not a time. */
internal fun minuteOf(s: String): Int? {
    val m = Regex("^\\s*(\\d{1,2})[:：](\\d{2})\\s*$").find(s) ?: return null
    val h = m.groupValues[1].toInt()
    val min = m.groupValues[2].toInt()
    return if (h in 0..23 && min in 0..59) h * 60 + min else null
}
