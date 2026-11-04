package app.dshmobile.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.chat.ChatItem
import app.dshmobile.chat.NoticeKind
import app.dshmobile.chat.PendingSend
import app.dshmobile.chat.ToolStatus
import app.dshmobile.chat.WebSource
import app.dshmobile.chat.friendlyToolError
import app.dshmobile.ui.fog.FogButton
import app.dshmobile.ui.fog.FogButtonKind
import app.dshmobile.ui.fog.FogDimens
import app.dshmobile.ui.fog.FogFonts
import app.dshmobile.ui.fog.glass
import app.dshmobile.ui.fog.glow
import app.dshmobile.ui.markdown.MarkdownBlock
import app.dshmobile.ui.markdown.MarkdownView
import app.dshmobile.ui.markdown.MdBlock
import app.dshmobile.ui.shell.Tags
import app.dshmobile.ui.theme.Sakura

// ---- citations -------------------------------------------------------------------------------------------------------

/** The 1-based numbers of [sources] whose URL appears in [text], in the sources' order, each once. */
fun citedSources(text: String, sources: List<WebSource>): List<Int> =
    sources.mapIndexedNotNull { i, s -> (i + 1).takeIf { mentions(text, s.url) } }

/** ①…⑳, then (21), (22)… */
internal fun circled(numbers: List<Int>): String =
    numbers.joinToString("") { n -> if (n in 1..20) ('①' + (n - 1)).toString() else "($n)" }

/**
 * True when [url] (a trailing slash ignored) occurs in [text] as a whole URL: what follows may end a sentence or a Markdown
 * link, but may not carry the path on (`…/one` is not `…/one-more`, `…/one/two` or `…/one?x`).
 */
private fun mentions(text: String, url: String): Boolean {
    val bare = url.trim().trimEnd('/')
    if (bare.isEmpty()) return false
    var from = 0
    while (true) {
        val at = text.indexOf(bare, from)
        if (at < 0) return false
        if (endsUrl(text, at + bare.length)) return true
        from = at + 1
    }
}

private fun endsUrl(text: String, end: Int): Boolean {
    if (end >= text.length) return true
    val c = text[end]
    return when {
        c == '/' -> end + 1 >= text.length || !continuesPath(text, end + 1)
        c == '.' -> end + 1 >= text.length || !text[end + 1].isLetterOrDigit()
        else -> !continuesPath(text, end)
    }
}

private fun continuesPath(text: String, i: Int): Boolean {
    val c = text[i]
    return (c.isLetterOrDigit() && c.code < 128) || c in "-_~%/?&=+"
}

/** A source chip's label: its title, else its host, else the URL. */
internal fun sourceLabel(s: WebSource): String =
    s.title.trim().ifEmpty { runCatching { java.net.URI(s.url.trim()).host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: s.url }

// ---- bubbles -----------------------------------------------------------------------------------------------------------

internal enum class BubbleKind(val tag: String, val user: Boolean) {
    USER(Tags.USER_BUBBLE, true), AI(Tags.ASSISTANT_BUBBLE, false), LIVE(Tags.LIVE_BUBBLE, false),
}

/** Where a piece of a long message sits in its bubble: the bubble's background is drawn piece by piece. */
internal enum class SegPos { FIRST, MIDDLE, LAST }

private val Corner = FogDimens.Panel
private val Tail = FogDimens.BubbleTail
private val Round = RoundedCornerShape(50)
private const val BUBBLE_WIDTH = .88f
private const val CARD_WIDTH = .92f

private fun bubbleShape(user: Boolean) =
    if (user) RoundedCornerShape(Corner, Corner, Tail, Corner) else RoundedCornerShape(Corner, Corner, Corner, Tail)

/** The 135° pink of the user's bubbles; [alpha] fades a bubble still waiting for dsh. */
private fun Modifier.userFill(alpha: Float = 1f, glow: Boolean = true): Modifier {
    val shape = bubbleShape(true)
    return this
        .then(if (glow) Modifier.glow(Sakura.Primary.copy(alpha = .55f * alpha), 18.dp, shape, dy = 6.dp) else Modifier)
        .drawWithCache {
            val brush = Brush.linearGradient(
                listOf(Sakura.Background.copy(alpha = alpha), Sakura.Primary.copy(alpha = alpha)),
                start = Offset.Zero, end = Offset(size.width, size.height),
            )
            val outline = shape.createOutline(size, layoutDirection, this)
            onDrawBehind { drawOutline(outline, brush) }
        }
}

/** A message bubble at most 88 % wide, on the right for the user and on the left for the assistant. */
@Composable
private fun BubbleBox(user: Boolean, fixedWidth: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(
            Modifier.fillMaxWidth(BUBBLE_WIDTH).then(
                if (fixedWidth) Modifier else Modifier.wrapContentWidth(if (user) Alignment.End else Alignment.Start),
            ),
        ) { content() }
    }
}

/** A whole short message in one item: glass for the assistant, pink for the user. [cites] closes an answer. */
@Composable
internal fun Bubble(kind: BubbleKind, blocks: List<MdBlock>, cites: List<Int>, onLink: (String) -> Unit, onCopy: () -> Unit = {}) {
    BubbleBox(kind.user, fixedWidth = false) {
        Column(
            Modifier
                .then(if (kind.user) Modifier.userFill() else Modifier.glass(bubbleShape(false)))
                .testTag(kind.tag)
                .semantics(mergeDescendants = true) {}
                .longPressCopy(onCopy)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            MarkdownView(blocks, onLink)
            if (cites.isNotEmpty()) Cites(cites)
        }
    }
}

/** A streamed reply before its first parse: its text as it is. */
@Composable
internal fun RawBubble(kind: BubbleKind, text: String) {
    BubbleBox(kind.user, fixedWidth = false) {
        Box(
            Modifier.glass(bubbleShape(false)).testTag(kind.tag).semantics(mergeDescendants = true) {}.padding(horizontal = 16.dp, vertical = 12.dp),
        ) { Text(text, color = Sakura.Text, fontSize = 15.sp, lineHeight = 25.5.sp) }
    }
}

/**
 * One piece of a long message, in its own lazy item. All pieces of a bubble are the same width (88 %) and draw their part of
 * one continuous background: the first has the top corners, the last the bottom ones and the tail. [gapTop] is the space
 * above this piece inside the bubble (0 when it continues the paragraph before it).
 */
@Composable
internal fun BubbleSegment(
    kind: BubbleKind, block: MdBlock, pos: SegPos, gapTop: Dp, cites: List<Int>, shade: Shade, onLink: (String) -> Unit, onCopy: () -> Unit = {},
) {
    BubbleBox(kind.user, fixedWidth = true) {
        Column(
            Modifier
                .fillMaxWidth()
                .segmentBackground(kind.user, pos, shade)
                .testTag(kind.tag)
                .semantics(mergeDescendants = true) {}
                .longPressCopy(onCopy)
                .padding(start = 16.dp, end = 16.dp, top = if (pos == SegPos.FIRST) 12.dp else gapTop, bottom = if (pos == SegPos.LAST) 12.dp else 0.dp),
        ) {
            MarkdownBlock(block, onLink)
            if (cites.isNotEmpty()) Cites(cites)
        }
    }
}

/**
 * A slice of a bubble's background: the rounded rectangle is drawn as if it went on past the cut edges, clipped to this piece,
 * so the pieces join without seams. A flat fill (a gradient across a 50 000-pixel bubble would be flat anyway) and a rim.
 */
private fun Modifier.segmentBackground(user: Boolean, pos: SegPos, shade: Shade): Modifier = drawWithCache {
    val r = Corner.toPx()
    val tail = Tail.toPx()
    val over = r * 2 + 4f
    val top = if (pos == SegPos.FIRST) 0f else -over
    val bottom = if (pos == SegPos.LAST) size.height else size.height + over
    val rounded = CornerRadius(r)
    val tailed = CornerRadius(tail)
    val path = Path().apply {
        addRoundRect(
            RoundRect(
                0f, top, size.width, bottom,
                topLeftCornerRadius = rounded, topRightCornerRadius = rounded,
                bottomRightCornerRadius = if (user) tailed else rounded, bottomLeftCornerRadius = if (user) rounded else tailed,
            ),
        )
    }
    val fill: Brush = if (user) diagonalShade(shade, size.width, size.height) else SolidColor(Color.White.copy(alpha = .44f))
    val rim = Brush.horizontalGradient(0f to Color.White.copy(alpha = .95f), .5f to Sakura.Primary.copy(alpha = .75f), 1f to Sakura.Aqua.copy(alpha = .9f))
    val stroke = Stroke(1.2.dp.toPx())
    // The glass's lit corner, faded out inside this first piece so the next one joins it.
    val lit = if (pos == SegPos.FIRST && !user && size.height > 0f) {
        Brush.radialGradient(
            0f to Color.White.copy(alpha = .55f), .45f to Color.White.copy(alpha = 0f),
            center = Offset.Zero, radius = minOf(size.width * 1.2f, size.height / .45f),
        )
    } else null
    onDrawBehind {
        clipRect {
            drawPath(path, fill)
            lit?.let { drawPath(path, it) }
            if (!user) drawPath(path, rim, style = stroke)
        }
    }
}

/**
 * The user's 135° pink for one piece of a long bubble: t = across·x/w + (1 − across)·(from + (to − from)·y/h), so a piece ends on
 * exactly the colour the next one starts with. A linear gradient whose axis is that gradient of t.
 */
private fun diagonalShade(shade: Shade, w: Float, h: Float): Brush {
    if (w <= 0f || h <= 0f) return SolidColor(Sakura.Primary)
    val dx = shade.across / w
    val dy = (1f - shade.across) * (shade.to - shade.from) / h
    val c = (1f - shade.across) * shade.from
    val len2 = dx * dx + dy * dy
    if (len2 == 0f) return SolidColor(Sakura.Primary)
    val start = Offset(-c * dx / len2, -c * dy / len2)
    val end = Offset(start.x + dx / len2, start.y + dy / len2)
    return Brush.linearGradient(listOf(Sakura.Background, Sakura.Primary), start = start, end = end)
}

@Composable
private fun Cites(numbers: List<Int>) {
    Text(
        stringResource(R.string.chat_cites, circled(numbers)),
        style = TextStyle(fontFamily = FogFonts.ChakraPetch, fontSize = 11.sp, letterSpacing = 0.08.em, color = Sakura.TextSecondary),
        modifier = Modifier.padding(top = 6.dp).testTag(Tags.CITES),
    )
}

/** A long message not parsed yet (a moment, off the main thread). */
@Composable
internal fun PlaceholderBubble(kind: BubbleKind) {
    BubbleBox(kind.user, fixedWidth = false) {
        Box(Modifier.glass(bubbleShape(kind.user)).testTag(kind.tag).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.chat_long_reply_loading), color = Sakura.TextSecondary, fontSize = 13.sp)
        }
    }
}

/** A reply that has started but has no text yet: what it is doing. */
@Composable
internal fun TypingBubble(tools: List<String>) {
    BubbleBox(user = false, fixedWidth = false) {
        Row(
            Modifier.glass(bubbleShape(false)).testTag(Tags.LIVE_BUBBLE).semantics(mergeDescendants = true) {}.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(3) { Box(Modifier.size(6.dp).background(Sakura.RoseBrown.copy(alpha = .6f), CircleShape)) }
            val label = if (tools.isEmpty()) stringResource(R.string.chat_typing) else stringResource(R.string.chat_using_tools, tools.joinToString("、"))
            Text(label, color = Sakura.TextSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---- thinking ----------------------------------------------------------------------------------------------------------

/** 「思考過程」, folded by default; tapping shows or hides the reasoning below it. */
@Composable
internal fun ThinkingToggle(expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = 40.dp)
            .clip50()
            .clickable(role = Role.Button, onClick = onToggle)
            .testTag(Tags.THINKING)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(7.dp).glow(Sakura.Primary, 6.dp, RoundedCornerShape(2.dp)).background(Sakura.Primary, RoundedCornerShape(2.dp)))
        Text(stringResource(R.string.chat_thinking), color = Sakura.TextSecondary, fontSize = 13.sp, letterSpacing = 0.08.em)
        Text(if (expanded) "▾" else "▸", color = Sakura.TextSecondary, fontSize = 13.sp)
    }
}

private fun Modifier.clip50() = clip(Round)

/** One piece of the shown reasoning (long reasoning is several lazy items). */
@Composable
internal fun ReasoningPiece(text: String, first: Boolean, last: Boolean) {
    val r = 16.dp
    val shape = RoundedCornerShape(if (first) r else 0.dp, if (first) r else 0.dp, if (last) r else 0.dp, if (last) r else 0.dp)
    Box(Modifier.fillMaxWidth(CARD_WIDTH).background(Color.White.copy(alpha = .35f), shape)
        .padding(start = 14.dp, end = 14.dp, top = if (first) 10.dp else 0.dp, bottom = if (last) 10.dp else 0.dp)) {
        BasicText(text, style = TextStyle(color = Sakura.TextSecondary, fontSize = 13.sp, lineHeight = 20.sp))
    }
}

// ---- tools -------------------------------------------------------------------------------------------------------------

@Composable
private fun statusText(status: ToolStatus): String = stringResource(
    when (status) {
        ToolStatus.RUNNING -> R.string.chat_tool_running
        ToolStatus.WAITING_APPROVAL -> R.string.chat_tool_waiting
        ToolStatus.DONE -> R.string.chat_tool_done
        ToolStatus.FAILED -> R.string.chat_tool_failed
    },
)

private fun statusColor(status: ToolStatus): Color = when (status) {
    ToolStatus.RUNNING -> Sakura.Aqua
    ToolStatus.WAITING_APPROVAL -> Sakura.Primary
    ToolStatus.DONE -> Sakura.MilkTea
    ToolStatus.FAILED -> Sakura.RoseBrown
}

private val CardShape = RoundedCornerShape(FogDimens.Thumb)

/** A generic tool card: name, argument summary and status; the error when it failed; 「分享」 for a file it wrote in the workspace. */
@Composable
internal fun ToolCard(item: ChatItem.Tool, onShare: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth(CARD_WIDTH)
            .background(Color.White.copy(alpha = .45f), CardShape)
            .border(1.dp, Color.White.copy(alpha = .9f), CardShape)
            .testTag(Tags.TOOL_CARD + item.key)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val dot = statusColor(item.status)
            Box(Modifier.size(8.dp).glow(dot, 6.dp, CircleShape).background(dot, CircleShape))
            Text(
                item.name.ifEmpty { "tool" }, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                style = TextStyle(fontFamily = FogFonts.ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.1.em, color = Sakura.Text),
            )
            Text(statusText(item.status), color = Sakura.TextSecondary, fontSize = 12.sp, maxLines = 1, modifier = Modifier.testTag(Tags.TOOL_STATUS + item.key))
        }
        if (item.summary.isNotBlank()) {
            Text(item.summary, color = Sakura.Text, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        item.error?.takeIf { item.status == ToolStatus.FAILED && it.isNotBlank() }?.let { e -> friendlyToolError(e) }?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 7, overflow = TextOverflow.Ellipsis)
        }
        item.sharePath?.let { path ->
            val label = stringResource(R.string.chat_share_file, path)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                FogButton(stringResource(R.string.chat_share), FogButtonKind.GHOST, { onShare(path) },
                    Modifier.testTag(Tags.SHARE + item.key).semantics { contentDescription = label })
            }
        }
    }
}

/** `web_search`: 「搜尋網路 · N 個來源」 and a chip per source (its title, else its host) that opens it in the browser. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchCard(item: ChatItem.Tool, onLink: (String) -> Unit) {
    val shape = RoundedCornerShape(FogDimens.SearchCard)
    Column(
        Modifier
            .fillMaxWidth(CARD_WIDTH)
            .background(Sakura.Aqua.copy(alpha = .22f), shape)
            .border(1.dp, Sakura.Aqua.copy(alpha = .85f), shape)
            .testTag(Tags.SEARCH_CARD)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).glow(Sakura.Aqua, 6.dp, CircleShape).background(Sakura.Aqua, CircleShape))
            val title = when {
                item.sources.isNotEmpty() -> stringResource(R.string.chat_search_sources, item.sources.size)
                item.status == ToolStatus.RUNNING || item.status == ToolStatus.WAITING_APPROVAL -> stringResource(R.string.chat_search_running)
                else -> stringResource(R.string.chat_search_none)
            }
            Text(
                title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = FogFonts.ChakraPetch, fontSize = 12.sp, letterSpacing = 0.14.em, color = Sakura.Text),
            )
        }
        if (item.summary.isNotBlank()) Text(item.summary, color = Sakura.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (item.sources.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item.sources.forEachIndexed { i, s ->
                    Text(
                        sourceLabel(s), color = Sakura.Text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .heightIn(min = 32.dp)
                            .background(Color.White.copy(alpha = .7f), Round)
                            .clip50()
                            .clickable(role = Role.Button) { onLink(s.url) }
                            .testTag(Tags.SOURCE_CHIP + (i + 1))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            }
        }
        item.error?.takeIf { item.status == ToolStatus.FAILED && it.isNotBlank() }?.let { e -> friendlyToolError(e) }?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---- notices and pending sends ------------------------------------------------------------------------------------------

/** 「已停止」 and dsh's error notices, small and centred between the messages. */
@Composable
internal fun NoticeLine(item: ChatItem.Notice) {
    val stopped = stringResource(R.string.chat_stopped)
    val text = if (item.kind == NoticeKind.STOPPED) item.text.ifBlank { stopped } else friendlyToolError(item.text)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = if (item.kind == NoticeKind.ERROR) MaterialTheme.colorScheme.error else Sakura.TextSecondary,
            fontSize = 12.sp, letterSpacing = 0.08.em, maxLines = 4, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .background(Color.White.copy(alpha = .5f), Round)
                .testTag(Tags.NOTICE)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

/** At most this much of a waiting bubble's text is drawn (the whole text is in the conversation once dsh records it). */
private const val PENDING_PREVIEW_CHARS = 2_000

/**
 * A message dsh has not recorded yet: the user's bubble, faded. Queued behind a running reply it says 「排隊中」; never recorded
 * within a minute it offers 重送 and 移除.
 */
@Composable
internal fun PendingBubble(p: PendingSend, onRetry: (String) -> Unit, onDismiss: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BubbleBox(user = true, fixedWidth = false) {
            Box(
                Modifier.userFill(alpha = .5f, glow = false).testTag(Tags.PENDING_BUBBLE + p.requestId).semantics(mergeDescendants = true) {}
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                val text = if (p.text.length > PENDING_PREVIEW_CHARS) p.text.take(PENDING_PREVIEW_CHARS) + "…" else p.text
                Text(text, color = Sakura.TextSecondary, fontSize = 15.sp, lineHeight = 25.5.sp)
            }
        }
        val label = TextStyle(fontFamily = FogFonts.ChakraPetch, fontSize = 11.sp, letterSpacing = 0.08.em, color = Sakura.TextSecondary)
        when {
            p.failed -> {
                Text(stringResource(R.string.chat_send_failed), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FogButton(stringResource(R.string.chat_resend), FogButtonKind.PINK, { onRetry(p.requestId) }, Modifier.testTag(Tags.SEND_RETRY + p.requestId))
                    FogButton(stringResource(R.string.chat_remove), FogButtonKind.GHOST, { onDismiss(p.requestId) }, Modifier.testTag(Tags.SEND_DISMISS + p.requestId))
                }
            }
            p.queued -> Text(stringResource(R.string.chat_queued), style = label, modifier = Modifier.testTag(Tags.QUEUED))
            else -> Text(stringResource(R.string.chat_sending), style = label)
        }
    }
}

/** The small stats line under a finished reply: speed, first token, tokens, estimated cost, duration (stage 2 §C.1). */
@Composable
internal fun ReplyStatsLine(text: String, key: String = "", onCopy: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            color = Sakura.TextSecondary,
            fontSize = 11.sp,
            letterSpacing = 0.04.em,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag(Tags.REPLY_STATS),
        )
        Box(
            Modifier.heightIn(min = 36.dp).clip(Round).clickable(role = Role.Button, onClick = onCopy).testTag(Tags.COPY_REPLY + key)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.chat_copy), color = Sakura.RoseBrown, fontSize = 12.sp) }
    }
}

/** Long-press a message to copy it (owner, 2026-11-04: 「對話可以複製」); links inside still open on a tap. */
private fun Modifier.longPressCopy(onCopy: () -> Unit): Modifier =
    semantics { onLongClick("複製") { onCopy(); true } }.pointerInput(onCopy) { detectTapGestures(onLongPress = { onCopy() }) }

/** Puts [text] on the clipboard; Android 13 and later show their own notice, older ones get [toast]. */
internal fun copyText(context: Context, text: String, toast: String) {
    if (text.isBlank()) return
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("dsh", text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

/** How full the model's context is (a thin bar, rose past 80 %) and the conversation's totals beside it (stage 2 §C.2). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContextBar(fill: Float?, text: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).semantics(mergeDescendants = true) {}.testTag(Tags.CHAT_STATS), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (fill != null) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = .6f))) {
                Box(
                    Modifier.fillMaxWidth(fill.coerceIn(0.02f, 1f)).height(4.dp).clip(CircleShape)
                        .background(if (fill >= 0.8f) Sakura.RoseBrown else Sakura.Aqua),
                )
            }
        }
        // Each item stays whole (「190 tok/s」 never splits across lines); the items wrap as units.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            text.split(" · ").forEachIndexed { i, part ->
                Text((if (i > 0) "· " else "") + part, color = Sakura.TextSecondary, fontSize = 11.sp, maxLines = 1, softWrap = false)
            }
        }
    }
}
