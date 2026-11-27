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

/** The pages of 設定 in T06; 公司規章, 作息, 練習生資料, 她記得的事, 入社資料 and 權限檢查 come with their tasks. */
enum class SettingsPage(val title: Int) {
    MODEL(R.string.ag_set_model), SPEND(R.string.ag_set_spend), RULES(R.string.ag_set_rules), SCHEDULE(R.string.ag_set_schedule),
    TRAINEE(R.string.ag_set_trainee), MEMORY(R.string.ag_set_memory), PERMISSIONS(R.string.ag_set_permissions), LEGACY(R.string.ag_set_legacy),
    AREA(R.string.ag_set_area), ROLLOUT(R.string.ag_set_rollout), SAMSUNG(R.string.ag_set_samsung), IMPORT(R.string.ag_set_import),
    BACKUP(R.string.ag_set_backup),
    LICENSES(R.string.ag_set_licenses),
}

object SettingsTags {
    const val ENTRY = "ag-set-"
    const val BACK = "ag-set-back"
}

@Composable
fun SettingsScreen(agency: AgencyApp, ready: StartState.Ready) {
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    val open = page
    if (open == SettingsPage.LICENSES) {
        LicensesPage(onClose = { page = null })
        return
    }
    if (open != null) BackHandler { page = null }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(AgencyTags.SETTINGS),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (open == null) {
            Panel(title = stringResource(R.string.ag_tab_settings)) {
                SettingsPage.entries.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider(color = Sakura.MilkTea)
                    Text(
                        stringResource(p.title), color = Sakura.Text, fontSize = 16.sp,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { page = p }.padding(vertical = 12.dp)
                            .testTag(SettingsTags.ENTRY + p.name),
                    )
                }
            }
            // Below the entries, so their places stay.
            VersionPanel(agency)
        } else {
            SoftButton(stringResource(R.string.ag_back), onClick = { page = null }, modifier = Modifier.testTag(SettingsTags.BACK))
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
                SettingsPage.LICENSES -> Unit
            }
        }
    }
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
