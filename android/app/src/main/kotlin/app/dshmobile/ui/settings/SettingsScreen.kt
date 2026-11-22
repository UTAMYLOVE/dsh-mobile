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
    MODEL(R.string.ag_set_model), SPEND(R.string.ag_set_spend), LEGACY(R.string.ag_set_legacy), LICENSES(R.string.ag_set_licenses),
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
        } else {
            SoftButton(stringResource(R.string.ag_back), onClick = { page = null }, modifier = Modifier.testTag(SettingsTags.BACK))
            when (open) {
                SettingsPage.MODEL -> ModelKeyPage(agency.settings, agency.keyVault, ready.brain)
                SettingsPage.SPEND -> SpendPage(agency.costMeter)
                SettingsPage.LEGACY -> LegacyDataPage(agency)
                SettingsPage.LICENSES -> Unit
            }
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
