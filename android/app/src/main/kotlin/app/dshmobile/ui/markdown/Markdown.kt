package app.dshmobile.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.ui.theme.Sakura
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code as CmCode
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading as CmHeading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph as CmParagraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as CmText
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/** The annotation tag under which every clickable URL is also recorded as a string annotation (the `Clickable` link's own tag is the URL too). */
const val LINK_TAG = "url"

sealed interface MdBlock {
    data class Paragraph(val text: AnnotatedString) : MdBlock
    data class Heading(val level: Int, val text: AnnotatedString) : MdBlock
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<List<MdBlock>>) : MdBlock
    data class Code(val language: String?, val code: String) : MdBlock
    data class Table(val header: List<AnnotatedString>, val rows: List<List<AnnotatedString>>) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data object Rule : MdBlock
}

/**
 * Markdown to a small block model (commonmark-java with GFM tables and strikethrough), drawn by [MarkdownView].
 * Raw HTML is kept as literal text and images are never loaded: an image becomes a "〔圖片〕alt" link, so nothing here touches the network.
 */
object MarkdownParser {
    const val MAX_CHARS = 200_000
    private const val TRUNCATED = "（內容過長，只顯示前 20 萬字）"
    private const val MAX_BLOCK_DEPTH = 24
    private const val MAX_INLINE_DEPTH = 24
    const val MAX_ROWS = 500
    const val MAX_ITEMS = 500

    private val parser: Parser = Parser.builder()
        .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
        .build()

    private val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace, background = Sakura.White.copy(alpha = 0.6f))
    private val linkStyles = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))

    fun parse(text: String): List<MdBlock> {
        val capped = text.length > MAX_CHARS
        // Never end on the first half of a surrogate pair (emoji would turn into a replacement glyph).
        val end = if (capped && Character.isHighSurrogate(text[MAX_CHARS - 1])) MAX_CHARS - 1 else MAX_CHARS
        val source = if (capped) text.substring(0, end) else text
        val blocks = convertChildren(parser.parse(source), 0)
        return if (capped) blocks + MdBlock.Paragraph(AnnotatedString(TRUNCATED)) else blocks
    }

    private fun convertChildren(parent: Node, depth: Int): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        var child = parent.firstChild
        while (child != null) {
            convert(child, depth)?.let(out::add)
            child = child.next
        }
        return out
    }

    private fun convert(node: Node, depth: Int): MdBlock? = when (node) {
        is CmParagraph -> MdBlock.Paragraph(inline(node))
        is CmHeading -> MdBlock.Heading(node.level.coerceIn(1, 6), inline(node))
        is BulletList -> MdBlock.ListBlock(false, 1, listItems(node, depth))
        is OrderedList -> MdBlock.ListBlock(true, node.markerStartNumber ?: 1, listItems(node, depth))
        is FencedCodeBlock -> MdBlock.Code(
            node.info?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() },
            node.literal.orEmpty().trimEnd('\n'),
        )
        is IndentedCodeBlock -> MdBlock.Code(null, node.literal.orEmpty().trimEnd('\n'))
        is BlockQuote ->
            if (depth >= MAX_BLOCK_DEPTH) MdBlock.Paragraph(AnnotatedString(plainText(node)))
            else MdBlock.Quote(convertChildren(node, depth + 1))
        is ThematicBreak -> MdBlock.Rule
        is HtmlBlock -> MdBlock.Paragraph(AnnotatedString(node.literal.orEmpty().trimEnd('\n')))
        is TableBlock -> table(node)
        else -> null
    }

    private fun listItems(list: Node, depth: Int): List<List<MdBlock>> {
        val items = ArrayList<List<MdBlock>>()
        var item = list.firstChild
        while (item != null) {
            if (item is ListItem) {
                if (items.size == MAX_ITEMS) {
                    var more = 0
                    while (item != null) { if (item is ListItem) more++; item = item.next }
                    items += listOf(MdBlock.Paragraph(AnnotatedString("…還有 $more 項")))
                    break
                }
                items += if (depth >= MAX_BLOCK_DEPTH) listOf(MdBlock.Paragraph(AnnotatedString(plainText(item))))
                else convertChildren(item, depth + 1)
            }
            item = item.next
        }
        return items
    }

    private fun table(table: TableBlock): MdBlock {
        var header: List<AnnotatedString> = emptyList()
        val rows = ArrayList<List<AnnotatedString>>()
        var hidden = 0
        var section = table.firstChild
        while (section != null) {
            var row = section.firstChild
            while (row != null) {
                if (row is TableRow) {
                    val cells = ArrayList<AnnotatedString>()
                    var cell = row.firstChild
                    while (cell != null) {
                        if (cell is TableCell) cells += inline(cell)
                        cell = cell.next
                    }
                    if (section is TableHead) header = cells
                    else if (section is TableBody) {
                        if (rows.size < MAX_ROWS) rows += cells else hidden++
                    }
                }
                row = row.next
            }
            section = section.next
        }
        if (hidden > 0) rows += listOf(listOf(AnnotatedString("…還有 $hidden 列")))
        return MdBlock.Table(header, rows)
    }

    private fun inline(parent: Node): AnnotatedString = buildAnnotatedString { appendChildren(parent, 0) }

    private fun AnnotatedString.Builder.appendChildren(parent: Node, depth: Int) {
        var child = parent.firstChild
        while (child != null) {
            appendInline(child, depth)
            child = child.next
        }
    }

    private fun AnnotatedString.Builder.appendInline(node: Node, depth: Int) {
        if (depth >= MAX_INLINE_DEPTH) {
            append(plainText(node))
            return
        }
        when (node) {
            is CmText -> append(node.literal.orEmpty())
            is SoftLineBreak, is HardLineBreak -> append('\n')
            is CmCode -> withStyle(codeStyle) { append(node.literal.orEmpty()) }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendChildren(node, depth + 1) }
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendChildren(node, depth + 1) }
            is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendChildren(node, depth + 1) }
            is Link -> {
                val url = node.destination.orEmpty().trim()
                if (isWebUrl(url)) link(url) { appendChildren(node, depth + 1) } else appendChildren(node, depth + 1)
            }
            is Image -> {
                val url = node.destination.orEmpty().trim()
                val label = "〔圖片〕" + plainText(node)
                if (isWebUrl(url)) link(url) { append(label) } else append(label)
            }
            is HtmlInline -> append(node.literal.orEmpty())
            else -> appendChildren(node, depth + 1)
        }
    }

    private fun AnnotatedString.Builder.link(url: String, body: AnnotatedString.Builder.() -> Unit) {
        val start = length
        withLink(LinkAnnotation.Clickable(tag = url, styles = linkStyles, linkInteractionListener = null)) { body() }
        addStringAnnotation(LINK_TAG, url, start, length)
    }

    private fun isWebUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    /** The literal text under [root], without recursion (nesting can be arbitrarily deep in hostile input). */
    private fun plainText(root: Node): String {
        val sb = StringBuilder()
        val stack = ArrayDeque<Node>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            when (n) {
                is CmText -> sb.append(n.literal)
                is CmCode -> sb.append(n.literal)
                is HtmlInline -> sb.append(n.literal)
                is SoftLineBreak, is HardLineBreak -> sb.append('\n')
                else -> {
                    var c = n.lastChild
                    while (c != null) {
                        stack.addLast(c)
                        c = c.previous
                    }
                }
            }
        }
        return sb.toString()
    }
}

private const val STREAM_DEBOUNCE_MS = 100L
private const val EAGER_PARSE_CHARS = 2_000

/**
 * Parses each latest value of [texts] off the main thread and hands it to [publish]; while [streaming], one parse at most per
 * [debounceMs]. A parse is never cancelled by newer text: it finishes and is published, then only the newest text is parsed next
 * (conflated), so a long reply keeps showing progress even when one parse is slower than the token gap.
 */
internal suspend fun conflatedParse(
    texts: Flow<String>,
    streaming: Boolean,
    debounceMs: Long = STREAM_DEBOUNCE_MS,
    parse: (String) -> List<MdBlock> = MarkdownParser::parse,
    publish: (List<MdBlock>) -> Unit,
) {
    if (streaming) delay(debounceMs)
    texts.distinctUntilChanged().conflate().collect { t ->
        publish(withContext(Dispatchers.Default) { parse(t) })
        if (streaming) delay(debounceMs)
    }
}

/** Parses [text] off the main thread (see [conflatedParse]); never blocks the UI and never starves while [streaming]. */
@Composable
fun rememberMarkdown(text: String, streaming: Boolean): List<MdBlock> {
    var blocks by remember { mutableStateOf(if (!streaming && text.length <= EAGER_PARSE_CHARS) MarkdownParser.parse(text) else emptyList()) }
    val latest by rememberUpdatedState(text)
    LaunchedEffect(streaming) {
        conflatedParse(snapshotFlow { latest }, streaming) { blocks = it }
    }
    return blocks
}

private val BodyStyle = TextStyle(color = Sakura.Text, fontSize = 15.sp, lineHeight = 25.5.sp)
private val CodeStyle = TextStyle(color = Sakura.Text, fontSize = 13.sp, lineHeight = 19.sp, fontFamily = FontFamily.Monospace)
/** Wide enough for the largest number ("100." and "1000." must not wrap); bullets stay narrow. */
internal fun listMarkerWidth(block: MdBlock.ListBlock): androidx.compose.ui.unit.Dp {
    if (!block.ordered) return 18.dp
    val digits = (block.start + block.items.size - 1).coerceAtLeast(1).toString().length
    return maxOf(28, (digits + 1) * 10 + 4).dp
}

private val TableCellMax = 240.dp

@Composable
fun MarkdownView(blocks: List<MdBlock>, onLink: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { Block(it, onLink) }
    }
}

/**
 * One block as [MarkdownView] draws it, for hosts that lay the blocks out themselves: the chat page puts every block of a long
 * reply in a lazy item of its own, so a 100k-character chapter is never one item.
 */
@Composable
fun MarkdownBlock(block: MdBlock, onLink: (String) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier) { Block(block, onLink) }
}

@Composable
private fun Block(block: MdBlock, onLink: (String) -> Unit) {
    when (block) {
        is MdBlock.Paragraph -> MdText(block.text, BodyStyle, onLink)
        is MdBlock.Heading -> {
            val size = when (block.level) { 1 -> 22.sp; 2 -> 19.sp; 3 -> 17.sp; else -> 15.sp }
            MdText(block.text, BodyStyle.copy(fontSize = size, lineHeight = size * 1.5f, fontWeight = FontWeight.Bold), onLink)
        }
        is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val markerWidth = listMarkerWidth(block)
            block.items.forEachIndexed { i, item ->
                Row {
                    BasicText(
                        if (block.ordered) "${block.start + i}." else "•",
                        Modifier.width(markerWidth),
                        style = BodyStyle,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { item.forEach { Block(it, onLink) } }
                }
            }
        }
        is MdBlock.Code -> Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Sakura.White.copy(alpha = 0.6f))
                .horizontalScroll(rememberScrollState()).padding(12.dp),
        ) { BasicText(block.code, style = CodeStyle, softWrap = false) }
        is MdBlock.Table -> Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Sakura.White.copy(alpha = 0.45f))
                .horizontalScroll(rememberScrollState()),
        ) { TableGrid(block, onLink) }
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(Sakura.Primary, RoundedCornerShape(2.dp)))
            Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { block.blocks.forEach { Block(it, onLink) } }
        }
        MdBlock.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(Sakura.RoseBrown.copy(alpha = 0.45f)))
    }
}

/** A paragraph of styled text whose link spans open through [onLink]. */
@Composable
private fun MdText(text: AnnotatedString, style: TextStyle, onLink: (String) -> Unit) {
    val current by rememberUpdatedState(onLink)
    val wired = remember(text) {
        text.mapAnnotations { range ->
            val item = range.item
            if (item is LinkAnnotation.Clickable) {
                val link = LinkAnnotation.Clickable(item.tag, item.styles) { current(item.tag) }
                AnnotatedString.Range(link, range.start, range.end)
            } else range
        }
    }
    BasicText(wired, style = style)
}

/** Columns are as wide as their widest cell (at most 240 dp), so a wide table scrolls sideways instead of squeezing. */
@Composable
private fun TableGrid(table: MdBlock.Table, onLink: (String) -> Unit) {
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (columns == 0) return
    val all = listOf(table.header) + table.rows
    val cellMax = with(LocalDensity.current) { TableCellMax.roundToPx() }
    Layout(
        content = {
            all.forEachIndexed { r, row ->
                for (c in 0 until columns) {
                    val cell = row.getOrNull(c) ?: AnnotatedString("")
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
                        MdText(cell, BodyStyle.copy(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal), onLink)
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val widths = IntArray(columns)
        measurables.forEachIndexed { i, m -> widths[i % columns] = maxOf(widths[i % columns], m.maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(cellMax)) }
        val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints(minWidth = widths[i % columns], maxWidth = widths[i % columns])) }
        val rowCount = all.size
        val heights = IntArray(rowCount) { r -> (0 until columns).maxOf { c -> placeables[r * columns + c].height } }
        val xs = IntArray(columns) { c -> (0 until c).sumOf { widths[it] } }
        layout(widths.sum(), heights.sum()) {
            var y = 0
            for (r in 0 until rowCount) {
                for (c in 0 until columns) placeables[r * columns + c].placeRelative(xs[c], y)
                y += heights[r]
            }
        }
    }
}
