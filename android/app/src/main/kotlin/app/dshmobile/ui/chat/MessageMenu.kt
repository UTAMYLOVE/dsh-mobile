package app.dshmobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.ui.shell.Tags
import app.dshmobile.ui.theme.Sakura

/** The message a long press picked: its key (`e<seq>`), whether it is the owner's, and its whole text. */
data class MenuTarget(val key: String, val user: Boolean, val text: String)

/** The long-press sheet (0.5.0, mockup 1): 複製・朗讀・編輯並重新送出・重新回答・從這裡開分支, over a scrim. */
@Composable
internal fun MessageMenu(
    target: MenuTarget,
    speaking: Boolean,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
    onEdit: () -> Unit,
    onReanswer: () -> Unit,
    onBranch: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Sakura.Text.copy(alpha = .28f)).clickable(interactionSource = null, indication = null, onClick = onDismiss)
            .testTag(Tags.MSG_MENU),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding()
                .background(Color.White.copy(alpha = .97f), RoundedCornerShape(22.dp)).padding(vertical = 6.dp)
                .clickable(interactionSource = null, indication = null) {},
        ) {
            Text(
                target.text.replace(Regex("\\s+"), " ").take(80), color = Sakura.TextSecondary, fontSize = 12.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            )
            Item(stringResource(R.string.msg_copy), Tags.MSG_COPY) { onCopy(); onDismiss() }
            Item(stringResource(if (speaking) R.string.msg_speak_stop else R.string.msg_speak), Tags.MSG_SPEAK) { onSpeak(); onDismiss() }
            if (target.user) Item(stringResource(R.string.msg_edit), Tags.MSG_EDIT) { onEdit(); onDismiss() }
            else Item(stringResource(R.string.msg_reanswer), Tags.MSG_REANSWER) { onReanswer(); onDismiss() }
            Item(stringResource(R.string.msg_branch), Tags.MSG_BRANCH) { onBranch(); onDismiss() }
            Text(
                stringResource(R.string.msg_branch_note), color = Sakura.TextSecondary, fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Item(label: String, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).testTag(tag).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) { Text(label, color = Sakura.Text, fontSize = 15.sp) }
}
