package app.dshmobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.chat.SessionRow
import app.dshmobile.ui.fog.FogButton
import app.dshmobile.ui.fog.FogButtonKind
import app.dshmobile.ui.fog.FogText
import app.dshmobile.ui.theme.Sakura

object HomeTags {
    const val HOME = "chat-home"
    const val SETTINGS = "home-settings"
    const val SUGGEST = "home-suggest-"
    const val RECENT = "home-recent-"
}

/**
 * The chat with nothing open (0.6.0): like the assistants' own apps it is a greeting, a few things to try and the latest
 * conversations; typing in the dock starts a new one (對話紀錄 is in the shell's top bar). Soft white cards on the pink
 * ground, no frames or code-style labels (0.8.0, the owner: 「不要科技感了，來一個舒服的感覺」).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatHome(
    recent: List<SessionRow>,
    onSuggest: (String) -> Unit,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().testTag(HomeTags.HOME).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.home_title), color = Sakura.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.home_sub), color = Sakura.TextSecondary, fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center)
        }
        Panel(stringResource(R.string.home_try)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                stringArrayResource(R.array.home_suggestions).forEachIndexed { i, s ->
                    Box(
                        Modifier.heightIn(min = 44.dp).clip(Chip).background(Sakura.Background.copy(alpha = .9f))
                            .clickable(role = Role.Button) { onSuggest(s) }.testTag(HomeTags.SUGGEST + i).padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(s, color = Sakura.Text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        if (recent.isNotEmpty()) {
            Panel(stringResource(R.string.home_recent)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    recent.take(RECENT_MAX).forEach { row ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(Chip).background(Color.White.copy(alpha = .55f))
                                .clickable(role = Role.Button) { onOpen(row.id) }.testTag(HomeTags.RECENT + row.id).padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(Modifier.size(width = 3.dp, height = 18.dp).background(if (row.running) Sakura.Aqua else Sakura.Primary, RoundedCornerShape(2.dp)))
                            Text(
                                row.title.ifBlank { stringResource(R.string.chat_untitled) }, color = Sakura.Text, fontSize = 15.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            FogButton(stringResource(R.string.open_settings), FogButtonKind.GHOST, onSettings, Modifier.testTag(HomeTags.SETTINGS))
        }
        Spacer(Modifier.height(4.dp))
    }
}

const val RECENT_MAX = 4
private val Chip = RoundedCornerShape(18.dp)
private val PanelShape = RoundedCornerShape(24.dp)

/** A soft white card with a small title. */
@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(PanelShape).background(Color.White.copy(alpha = .6f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, color = Sakura.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.semantics { heading() })
        content()
    }
}
