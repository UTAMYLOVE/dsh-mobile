package app.dshmobile.ui.agency

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agency.core.model.Message
import app.agency.core.model.RoleKey
import app.agency.core.model.Who
import app.dshmobile.R
import app.dshmobile.ui.kit.Sakura

/**
 * The one conversation, oldest at the top and kept scrolled to the newest. Every company line shows its role above it
 * (【經紀人】 or 【體態管理老師】), imported ones too; his lines sit on the right.
 */
@Composable
fun ChatList(messages: List<Message>, hasOlder: Boolean, onOlder: () -> Unit, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) state.scrollToItem(messages.size - 1 + if (hasOlder) 1 else 0)
    }
    if (messages.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp).testTag(AgencyTags.CHAT), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.ag_empty_chat), color = Sakura.TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
        }
        return
    }
    LazyColumn(
        modifier.fillMaxWidth().testTag(AgencyTags.CHAT),
        state = state,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hasOlder) {
            item(key = "older") {
                TextButton(onClick = onOlder, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ag_older), color = Sakura.TextSecondary) }
            }
        }
        items(messages, key = { it.id }) { Bubble(it) }
    }
}

@Composable
private fun Bubble(m: Message) {
    val mine = m.who == Who.OWNER
    Column(
        Modifier.fillMaxWidth().testTag(AgencyTags.BUBBLE + m.id),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        if (m.who == Who.COMPANY) {
            Text(roleLabel(m.role), color = Sakura.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
        }
        val shape = RoundedCornerShape(18.dp)
        val color = when (m.who) {
            Who.OWNER -> Sakura.Primary.copy(alpha = .55f)
            Who.COMPANY -> Sakura.White
            Who.SYSTEM -> Sakura.MilkTea.copy(alpha = .5f)
        }
        Text(
            m.text, color = Sakura.Text, fontSize = 15.sp, lineHeight = 21.sp,
            modifier = Modifier.widthIn(max = 300.dp).background(color, shape).padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun roleLabel(role: RoleKey?): String = stringResource(
    when (role) {
        RoleKey.MANAGER -> R.string.ag_role_manager
        RoleKey.BODY -> R.string.ag_role_body
        null -> R.string.ag_role_company
    },
)
