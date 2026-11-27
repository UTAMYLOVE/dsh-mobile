package app.dshmobile.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.dshmobile.ui.agency.collectOnMain
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.StartState
import app.dshmobile.ui.LicensesPage
import app.dshmobile.ui.agency.AgencyTags
import app.dshmobile.ui.kit.Panel
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.SoftButton
import app.dshmobile.ui.kit.Card
import app.dshmobile.ui.kit.IconTile
import app.dshmobile.ui.kit.Icons
import app.dshmobile.ui.kit.LineIcon
import app.dshmobile.ui.kit.Type
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** The pages of 設定 in T06; 公司規章, 作息, 練習生資料, 她記得的事, 入社資料 and 權限檢查 come with their tasks. */
enum class SettingsPage(val title: Int) {
    MODEL(R.string.ag_set_model), SPEND(R.string.ag_set_spend), RULES(R.string.ag_set_rules), SCHEDULE(R.string.ag_set_schedule),
    TRAINEE(R.string.ag_set_trainee), MEMORY(R.string.ag_set_memory), PERMISSIONS(R.string.ag_set_permissions), LEGACY(R.string.ag_set_legacy),
    AREA(R.string.ag_set_area), ROLLOUT(R.string.ag_set_rollout), SAMSUNG(R.string.ag_set_samsung), IMPORT(R.string.ag_set_import),
    BACKUP(R.string.ag_set_backup),
    LICENSES(R.string.ag_set_licenses),
    /** 入社資料: the 入社建檔 page itself (redesign 03, read mode once finished). */
    ONBOARDING(R.string.ag_settings_onboarding),
}

/** 設定's groups (redesign 08): 我的資料, 公司, 小葵姐的大腦, 手機與資料; 舊資料 and 授權 at the bottom with the version. */
private val GROUPS: List<Pair<Int, List<SettingsPage>>> = listOf(
    R.string.ag_set_group_me to listOf(SettingsPage.TRAINEE, SettingsPage.ONBOARDING, SettingsPage.MEMORY),
    R.string.ag_set_group_company to listOf(SettingsPage.RULES, SettingsPage.SCHEDULE, SettingsPage.ROLLOUT),
    R.string.ag_set_group_brain to listOf(SettingsPage.MODEL, SettingsPage.SPEND),
    R.string.ag_set_group_phone to listOf(SettingsPage.PERMISSIONS, SettingsPage.SAMSUNG, SettingsPage.AREA, SettingsPage.BACKUP, SettingsPage.IMPORT),
    R.string.ag_set_group_more to listOf(SettingsPage.LEGACY, SettingsPage.LICENSES),
)

private fun iconOf(p: SettingsPage) = when (p) {
    SettingsPage.TRAINEE -> Icons.User
    SettingsPage.ONBOARDING -> Icons.Ruler
    SettingsPage.MEMORY -> Icons.Heart
    SettingsPage.RULES -> Icons.Book
    SettingsPage.SCHEDULE -> Icons.Clock
    SettingsPage.ROLLOUT -> Icons.Star
    SettingsPage.MODEL -> Icons.Sparkle
    SettingsPage.SPEND -> Icons.Box
    SettingsPage.PERMISSIONS -> Icons.Shield
    SettingsPage.SAMSUNG -> Icons.Phone
    SettingsPage.AREA -> Icons.Pin
    SettingsPage.BACKUP -> Icons.Lock
    SettingsPage.IMPORT -> Icons.Import
    SettingsPage.LEGACY -> Icons.Box
    SettingsPage.LICENSES -> Icons.Pen
}

object SettingsTags {
    const val ENTRY = "ag-set-"
    const val BACK = "ag-set-back"
}

@Composable
fun SettingsScreen(agency: AgencyApp, ready: StartState.Ready, onOnboarding: () -> Unit = {}) {
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    val open = page
    if (open == SettingsPage.LICENSES) {
        LicensesPage(onClose = { page = null })
        return
    }
    if (open != null) BackHandler { page = null }
    val revision by ready.brain.revision.collectOnMain()
    val values by androidx.compose.runtime.produceState(emptyMap<SettingsPage, Triple<Int, Int?, Int?>>(), revision) {
        app.dshmobile.ui.agency.onMain {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val st = ready.brain.onboardingStatus()
                    mapOf(
                        SettingsPage.ROLLOUT to Triple(R.string.ag_settings_rollout_value, ready.brain.rollout().week, null),
                        SettingsPage.ONBOARDING to if (st.missing.isEmpty()) Triple(R.string.ag_settings_onboarding_all, null, null) else Triple(R.string.ag_settings_onboarding_left, st.missing.size, null),
                    )
                }.getOrDefault(emptyMap())
            }
        }
    }
    Column(
        Modifier.fillMaxSize().background(Sakura.Page).verticalScroll(rememberScrollState()).padding(16.dp).testTag(AgencyTags.SETTINGS),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (open == null) {
            Text(stringResource(R.string.ag_tab_settings), style = Type.Title)
            if (android.os.Build.MANUFACTURER.equals(SAMSUNG, ignoreCase = true)) {
                Row(
                    Modifier.fillMaxWidth().background(Sakura.Warn, RoundedCornerShape(16.dp)).border(1.dp, Sakura.WarnLine, RoundedCornerShape(16.dp))
                        .clickable { page = SettingsPage.SAMSUNG }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LineIcon(Icons.Phone)
                    Text(stringResource(R.string.ag_set_samsung_warn), style = Type.Support.copy(color = Sakura.Text), modifier = Modifier.weight(1f))
                    LineIcon(Icons.Right, tint = Sakura.TextSecondary, size = 18.dp)
                }
            }
            GROUPS.forEach { (title, pages) ->
                Text(stringResource(title), style = Type.Caption, modifier = Modifier.padding(start = 4.dp))
                Card(padding = 4.dp) {
                    pages.forEachIndexed { i, p ->
                        if (i > 0) HorizontalDivider(color = Sakura.Divider, modifier = Modifier.padding(start = 56.dp))
                        val v = when (p) {
                            SettingsPage.SPEND -> spendValue(agency)
                            else -> values[p]?.let { (res, a, b) -> if (a == null) stringResource(res) else if (b == null) stringResource(res, a) else stringResource(res, a, b) }
                        }
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                .clickable { if (p == SettingsPage.ONBOARDING) onOnboarding() else page = p }
                                .padding(horizontal = 10.dp, vertical = 6.dp).testTag(SettingsTags.ENTRY + p.name),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            IconTile(iconOf(p), size = 34.dp)
                            Text(stringResource(p.title), style = Type.Body, modifier = Modifier.weight(1f))
                            v?.let { Text(it, style = Type.Support) }
                            LineIcon(Icons.Right, tint = Sakura.TextSecondary, size = 18.dp)
                        }
                    }
                }
            }
            // Below the entries, so their places stay.
            VersionPanel(agency)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val back = stringResource(R.string.ag_back)
                androidx.compose.material3.IconButton(onClick = { page = null }, modifier = Modifier.semantics { contentDescription = back }.testTag(SettingsTags.BACK)) {
                    LineIcon(Icons.Back)
                }
                Text(stringResource(open.title), style = Type.Title)
            }
            when (open) {
                SettingsPage.MODEL -> ModelKeyPage(agency.settings, agency.keyVault, ready.brain)
                SettingsPage.SPEND -> SpendPage(agency.costMeter)
                SettingsPage.RULES -> RulesPage(ready)
                SettingsPage.SCHEDULE -> SchedulePage(ready)
                SettingsPage.TRAINEE -> TraineePage(agency, ready)
                SettingsPage.AREA -> AreaPage(ready)
                SettingsPage.MEMORY -> MemoryPage(ready)
                SettingsPage.PERMISSIONS -> PermissionCheckPage(agency)
                SettingsPage.LEGACY -> LegacyDataPage(agency)
                SettingsPage.ROLLOUT -> RolloutPage(ready)
                SettingsPage.SAMSUNG -> SamsungPage()
                SettingsPage.IMPORT -> ImportPage(ready)
                SettingsPage.BACKUP -> BackupPage(agency)
                SettingsPage.LICENSES, SettingsPage.ONBOARDING -> Unit
            }
        }
    }
}

/** 花費上限's value line (moved here from the war room, redesign 07/08): today's spend against his cap. */
@Composable
private fun spendValue(agency: AgencyApp): String {
    val today = remember { runCatching { agency.costMeter.todayUsd() }.getOrDefault(0.0) }
    val cap = remember { runCatching { agency.costMeter.hardCapUsd }.getOrNull() }
    fun usd(v: Double) = String.format(java.util.Locale.ROOT, "%.2f", v)
    return if (cap != null) stringResource(R.string.ag_settings_spend_value, usd(today), usd(cap)) else stringResource(R.string.ag_settings_spend_no_cap, usd(today))
}

object VersionTags {
    const val NOW = "ag-version-now"
    const val NEW = "ag-version-new"
}

/** The installed version, and a newer release when the daily check found one (新版本通知): tapping opens its page. */
@Composable
private fun VersionPanel(agency: AgencyApp) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val rev by agency.settings.revision.collectOnMain()
    val newer = remember(rev) { agency.settings.newRelease }
    Panel(title = stringResource(R.string.ag_version_title)) {
        Text(stringResource(R.string.ag_version_now, app.dshmobile.BuildConfig.VERSION_NAME, app.dshmobile.BuildConfig.VERSION_CODE), color = Sakura.Text, fontSize = 15.sp, modifier = Modifier.testTag(VersionTags.NOW))
        newer?.let { n ->
            val name = n.substringBefore('|')
            val page = n.substringAfter('|')
            SoftButton(stringResource(R.string.ag_version_new, name), onClick = {
                if (page.startsWith(app.agency.core.net.ReleaseCheck.PAGE_PREFIX) || page == app.agency.core.net.ReleaseCheck.RELEASES_PAGE) {
                    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(page)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }, modifier = Modifier.testTag(VersionTags.NEW))
        }
    }
}

@Composable
internal fun Label(text: String) = Text(text, color = Sakura.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))

@Composable
internal fun Note(text: String, modifier: Modifier = Modifier) = Text(text, color = Sakura.TextSecondary, fontSize = 13.sp, modifier = modifier)

@Composable
internal fun Choice(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.RadioButton, onClick = onClick).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, color = Sakura.Text, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
internal fun Field(label: String, value: String, tag: String, secret: Boolean = false, number: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, label = { Text(label) },
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                secret -> KeyboardType.Password
                number -> KeyboardType.Decimal
                else -> KeyboardType.Text
            },
        ),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/** A panel of one settings page. */
@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) = Panel(title = title, content = content)
private const val SAMSUNG = "samsung"
