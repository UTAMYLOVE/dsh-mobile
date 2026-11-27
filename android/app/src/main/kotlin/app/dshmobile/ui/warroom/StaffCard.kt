package app.dshmobile.ui.warroom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agency.core.persona.Roles
import app.dshmobile.R
import app.dshmobile.ui.kit.Panel
import app.dshmobile.ui.kit.Sakura

object StaffTags {
    const val CARD = "ag-staff"
    const val ROLE = "ag-staff-"
}

/**
 * 指揮室's role cards (owner 2026-11-26): the company's people from the role cards of the core, the manager by her name
 * (小葵, 夏葵, 해나; he calls her 小葵姐), the roles still to come as 「還沒到任」.
 */
@Composable
fun StaffCard() {
    Panel(title = stringResource(R.string.ag_staff_title), modifier = Modifier.testTag(StaffTags.CARD)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.ag_staff_manager), color = Sakura.Text, fontSize = 15.sp, modifier = Modifier.testTag(StaffTags.ROLE + Roles.MANAGER.id))
            Roles.ALL.filter { it.id != Roles.MANAGER.id }.forEach { c ->
                val state = stringResource(if (c.enabledIn(Roles.STAGE)) R.string.ag_staff_on_duty else R.string.ag_staff_later)
                Text("${c.title}　$state", color = Sakura.TextSecondary, fontSize = 14.sp, modifier = Modifier.testTag(StaffTags.ROLE + c.id))
            }
        }
    }
}
