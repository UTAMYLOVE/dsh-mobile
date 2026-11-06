package app.dshmobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.dshmobile.R
import app.dshmobile.chat.FileKind
import app.dshmobile.chat.PresentedFile
import app.dshmobile.ui.common.SvgImage
import app.dshmobile.ui.fog.FogFonts
import app.dshmobile.ui.theme.Sakura
import java.io.File

object ResultTags {
    const val PRESENT = "present-card-"
    const val RESEARCH = "research-card-"
    const val FILE_ROW = "present-file-"
    const val OPEN = "present-open-"
    const val SHARE = "present-share-"
    const val ZOOM = "svg-zoom"
}

/** What a deep-research turn did before it presented its report. */
data class ResearchCounts(val searches: Int, val sources: Int)

/** Badge background and text for a file kind: palette colors only (the owner, 2026-11-05). */
fun badgeColors(kind: FileKind): Pair<Color, Color> = when (kind) {
    FileKind.MD -> Sakura.RoseBrown to Color.White
    FileKind.DOC -> Sakura.Text to Color.White
    FileKind.XLS -> Sakura.Aqua to Sakura.Text
    FileKind.PPT -> Sakura.Primary to Color.White
    FileKind.WEB -> Sakura.TextSecondary to Color.White
    FileKind.SVG -> Sakura.Background to Sakura.Text
    FileKind.PDF -> Sakura.Primary to Color.White
    FileKind.CSV -> Sakura.MilkTea to Sakura.Text
    FileKind.IMG -> Sakura.MilkTea to Sakura.Text
    FileKind.OTHER -> Sakura.MilkTea to Sakura.Text
}

private val Card = RoundedCornerShape(18.dp)
private val RowShape = RoundedCornerShape(12.dp)

/**
 * The files a `present` call handed over: one row each (badge, name, what it is) with 打開 and 分享; a drawing is shown right in
 * the card (tap to zoom). With [research], the card is the deep-research result: 「深度研究報告・N 次搜尋・M 個來源」.
 * Everything here is already on the bookshelf (the chat page adds it), so the card says 已存書架.
 */
@Composable
fun PresentCard(
    key: String,
    files: List<PresentedFile>,
    research: ResearchCounts?,
    workspace: File?,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    var zoom by remember { mutableStateOf<File?>(null) }
    Column(
        Modifier
            .fillMaxWidth(.92f)
            .clip(Card)
            .background(Color.White.copy(alpha = .78f))
            .border(1.dp, if (research != null) Sakura.Aqua else Sakura.Primary.copy(alpha = .5f), Card)
            .testTag((if (research != null) ResultTags.RESEARCH else ResultTags.PRESENT) + key)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(if (research != null) R.string.result_research_title else R.string.result_files_title),
                color = Sakura.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.result_on_shelf), color = Sakura.RoseBrown, fontSize = 11.sp)
        }
        if (research != null) {
            Text(
                stringResource(R.string.result_research_counts, research.searches, research.sources),
                color = Sakura.TextSecondary, fontSize = 12.sp, fontFamily = FogFonts.ChakraPetch,
            )
        }
        files.forEachIndexed { i, f ->
            val local = workspace?.let { File(it, f.path) }?.takeIf { !f.path.startsWith("/") }
            if (f.kind == FileKind.SVG && local != null) {
                Box(
                    Modifier.fillMaxWidth().clip(RowShape).background(Color.White).border(1.dp, Sakura.Background, RowShape)
                        .clickable { zoom = local }.padding(6.dp),
                ) { SvgImage(local) }
            }
            Row(
                Modifier.fillMaxWidth().clip(RowShape).background(Sakura.Background.copy(alpha = .6f)).heightIn(min = 48.dp)
                    .testTag("${ResultTags.FILE_ROW}$key-$i").padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val (bg, fg) = badgeColors(f.kind)
                Text(
                    f.kind.badge, color = fg, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, fontFamily = FogFonts.ChakraPetch,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 5.dp, vertical = 2.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(f.name, color = Sakura.Text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    f.description?.let { Text(it, color = Sakura.TextSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
                if (!f.path.startsWith("/")) {
                    TextButton(onClick = { onOpen(f.path) }, modifier = Modifier.testTag("${ResultTags.OPEN}$key-$i")) {
                        Text(stringResource(R.string.result_open), fontSize = 12.sp, color = Sakura.Text)
                    }
                    TextButton(onClick = { onShare(f.path) }, modifier = Modifier.testTag("${ResultTags.SHARE}$key-$i")) {
                        Text(stringResource(R.string.chat_share), fontSize = 12.sp, color = Sakura.Text)
                    }
                }
            }
        }
    }
    zoom?.let { file -> SvgZoom(file) { zoom = null } }
}

/** A drawing full screen: pinch to zoom, drag to move, tap 關閉 to leave. */
@Composable
private fun SvgZoom(file: File, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val state = rememberTransformableState { z, pan, _ ->
            scale = (scale * z).coerceIn(1f, 6f)
            offset += pan
        }
        Box(Modifier.fillMaxSize().background(Color.White).testTag(ResultTags.ZOOM)) {
            Box(
                Modifier.fillMaxSize().transformable(state)
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
                contentAlignment = Alignment.Center,
            ) { SvgImage(file) }
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                Text(stringResource(R.string.result_close), color = Sakura.Text, fontSize = 15.sp)
            }
        }
    }
}

/** The workspace folder, for drawings shown inline (null in previews and tests without the app). */
@Composable
internal fun rememberWorkspace(): File? {
    val app = LocalContext.current.applicationContext as? app.dshmobile.DshMobileApp
    return remember(app) { runCatching { app?.appPaths?.workspace }.getOrNull() }
}
