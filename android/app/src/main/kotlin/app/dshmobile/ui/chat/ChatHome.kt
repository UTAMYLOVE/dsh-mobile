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
    const val LIST = "home-list"
    const val SETTINGS = "home-settings"
    const val SUGGEST = "home-suggest-"
    const val RECENT = "home-recent-"
}

/**
 * The chat with nothing open (0.6.0): like the assistants' own apps it is a greeting, a few things to try and the latest
 * conversations; typing in the dock starts a new one. The frame with corner brackets and small code-style labels follows
 * 赛博老爸's Harness skin (deepseek-meihuaqi; laid out for a phone, drawn here in the app's six colours, no asset of his).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatHome(
    recent: List<SessionRow>,
    onSuggest: (String) -> Unit,
    onOpen: (String) -> Unit,
    onOpenList: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().testTag(HomeTags.HOME).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("DSH MOBILE // SAKURA", style = FogText.Label, maxLines = 1, overflow = TextOverflow.Clip, modifier = Modifier.weight(1f).clearAndSetSemantics {})
            FogButton(stringResource(R.string.home_list), FogButtonKind.GHOST, onOpenList, Modifier.testTag(HomeTags.LIST))
            FogButton(stringResource(R.string.open_settings), FogButtonKind.GHOST, onSettings, Modifier.testTag(HomeTags.SETTINGS))
        }
        Column(
            Modifier.fillMaxWidth().brackets().padding(horizontal = 18.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.home_title), color = Sakura.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.em,
                textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.home_sub), color = Sakura.TextSecondary, fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
        }
        Panel("01", stringResource(R.string.home_try)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                stringArrayResource(R.array.home_suggestions).forEachIndexed { i, s ->
                    Box(
                        Modifier.heightIn(min = 44.dp).clip(Chip).background(Color.White.copy(alpha = .75f)).border(1.dp, Sakura.Aqua, Chip)
                            .clickable(role = Role.Button) { onSuggest(s) }.testTag(HomeTags.SUGGEST + i).padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(s, color = Sakura.Text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        if (recent.isNotEmpty()) {
            Panel("02", stringResource(R.string.home_recent)) {
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
        Spacer(Modifier.height(4.dp))
    }
}

const val RECENT_MAX = 4
private val Chip = RoundedCornerShape(14.dp)
private val PanelShape = RoundedCornerShape(4.dp)

/** A thin-framed panel with a numbered, code-style title bar. */
@Composable
private fun Panel(index: String, title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(PanelShape).background(Color.White.copy(alpha = .35f)).border(1.dp, Sakura.Primary.copy(alpha = .55f), PanelShape),
    ) {
        Row(
            Modifier.fillMaxWidth().background(Sakura.Primary.copy(alpha = .16f)).padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(index, style = FogText.Label.copy(color = Sakura.RoseBrown), modifier = Modifier.clearAndSetSemantics {})
            Text(title, color = Sakura.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.1.em, modifier = Modifier.semantics { heading() })
        }
        Box(Modifier.fillMaxWidth().padding(12.dp)) { content() }
    }
}

/** Four corner brackets, pink at the top-left and bottom-right, aqua at the other two. */
private fun Modifier.brackets(): Modifier = drawBehind {
    val arm = 18.dp.toPx()
    val w = 2.dp.toPx()
    fun corner(x: Float, y: Float, dx: Float, dy: Float, color: Color) {
        drawLine(color, Offset(x, y), Offset(x + dx * arm, y), w, StrokeCap.Square)
        drawLine(color, Offset(x, y), Offset(x, y + dy * arm), w, StrokeCap.Square)
    }
    corner(0f, 0f, 1f, 1f, Sakura.Primary)
    corner(size.width, 0f, -1f, 1f, Sakura.Aqua)
    corner(0f, size.height, 1f, -1f, Sakura.Aqua)
    corner(size.width, size.height, -1f, -1f, Sakura.Primary)
}
