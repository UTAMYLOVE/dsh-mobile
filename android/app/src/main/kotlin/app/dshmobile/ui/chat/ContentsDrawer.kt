package app.dshmobile.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.chat.ChatItem
import app.dshmobile.chat.ComposeMode
import app.dshmobile.chat.ReviewRounds
import app.dshmobile.ui.fog.FogFonts
import app.dshmobile.ui.theme.Sakura
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One line of the contents: the owner's [turn]th message (from dsh's `turnOutline`, so also turns paged out of the chat) or a
 * `##` heading of a loaded reply ([heading]). [key] is the message key `e<seq>`.
 */
data class TocEntry(val turn: Int, val key: String, val text: String, val heading: Boolean = false)

object Toc {
    /** The owner's turns from the `turnOutline` projection, then each loaded reply's `##`/`###` headings under its turn. */
    fun entries(outline: JsonElement?, items: List<ChatItem>): List<TocEntry> {
        val turns = (outline as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val seq = (o["seq"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return@mapNotNull null
            val turn = (o["turn"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
            val prompt = (o["prompt"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            Triple(seq, turn, ComposeMode.strip(ReviewRounds.strip(prompt)).lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty())
        }.sortedBy { it.first }
        val seqOfTurn = turns.map { it.first }
        val headings = items.filterIsInstance<ChatItem.Assistant>().flatMap { a ->
            HEADING.findAll(a.text).map { m -> a.seq to m.groupValues[1].trim() }.toList()
        }
        val out = ArrayList<TocEntry>()
        turns.forEachIndexed { i, (seq, turn, text) ->
            out += TocEntry(if (turn > 0) turn else i + 1, "e$seq", text.ifEmpty { "（附件）" })
            val next = seqOfTurn.getOrNull(i + 1) ?: Long.MAX_VALUE
            headings.filter { it.first in (seq + 1) until next }.forEach { (s, h) -> out += TocEntry(out.last().turn, "e$s", h, heading = true) }
        }
        return out
    }

    fun filter(entries: List<TocEntry>, query: String): List<TocEntry> =
        query.trim().takeIf { it.isNotEmpty() }?.let { q -> entries.filter { it.text.contains(q, ignoreCase = true) } } ?: entries

    private val HEADING = Regex("(?m)^#{2,3}\\s+(.+)$")
}

object TocTags {
    const val OPEN = "toc-open"
    const val DRAWER = "toc-drawer"
    const val FIND = "toc-find"
    const val ENTRY = "toc-entry-"
    const val CLOSE = "toc-close"
}

/** Mockup 8: a right-side drawer listing what the owner asked, numbered; tapping one jumps there. */
@Composable
fun ContentsDrawer(entries: List<TocEntry>, current: String?, onPick: (TocEntry) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(entries, query) { Toc.filter(entries, query) }
    val asked = entries.count { !it.heading }
    Box(
        Modifier.fillMaxSize().background(Sakura.Text.copy(alpha = .25f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            Modifier.fillMaxHeight().fillMaxWidth(.8f).widthIn(max = 340.dp)
                .clip(RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)).background(Color.White)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .testTag(TocTags.DRAWER),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.toc_title), color = Sakura.Text, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    Text(stringResource(R.string.toc_count, asked), color = Sakura.TextSecondary, fontSize = 11.sp)
                }
                val closeLabel = stringResource(R.string.toc_close)
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Sakura.Primary, RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClick = onClose).testTag(TocTags.CLOSE).semantics { contentDescription = closeLabel },
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = Sakura.Text, fontSize = 16.sp) }
            }
            BasicTextField(
                query, { query = it }, singleLine = true, textStyle = TextStyle(color = Sakura.Text, fontSize = 14.sp), cursorBrush = SolidColor(Sakura.Text),
                modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth().testTag(TocTags.FIND),
                decorationBox = { inner ->
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 40.dp).background(Sakura.Background.copy(alpha = .7f), RoundedCornerShape(50)).padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (query.isEmpty()) Text(stringResource(R.string.toc_find), color = Sakura.TextSecondary, fontSize = 13.sp)
                        inner()
                    }
                },
            )
            if (shown.isEmpty()) Text(stringResource(R.string.toc_empty), color = Sakura.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            LazyColumn(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 6.dp)) {
                items(shown, key = { it.key + it.heading + it.text }) { e ->
                    val here = e.key == current && !e.heading
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (here) Sakura.Aqua.copy(alpha = .35f) else Color.Transparent)
                            .clickable(role = Role.Button) { onPick(e) }.testTag(TocTags.ENTRY + e.key + if (e.heading) "-h" else "")
                            .padding(start = if (e.heading) 40.dp else 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top,
                    ) {
                        if (!e.heading) {
                            Box(
                                Modifier.size(24.dp).clip(RoundedCornerShape(8.dp)).background(if (here) Sakura.Aqua else Sakura.Primary.copy(alpha = .35f)),
                                contentAlignment = Alignment.Center,
                            ) { Text("${e.turn}", color = Sakura.Text, fontSize = 12.sp, fontFamily = FogFonts.ChakraPetch, fontWeight = FontWeight.SemiBold) }
                        }
                        Text(
                            e.text, color = if (e.heading) Sakura.TextSecondary else Sakura.Text, fontSize = if (e.heading) 13.sp else 14.sp,
                            lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Text(stringResource(R.string.toc_note), color = Sakura.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }
    }
}
