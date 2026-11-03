package app.dshmobile.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.chat.ConversationState
import app.dshmobile.config.AgentModels
import app.dshmobile.config.displayName
import app.dshmobile.library.Book
import app.dshmobile.library.Chapter
import app.dshmobile.library.Chapters
import app.dshmobile.library.CharacterCard
import app.dshmobile.library.LibraryData
import app.dshmobile.library.LibraryStore
import app.dshmobile.ui.fog.FogButton
import app.dshmobile.ui.fog.FogButtonKind
import app.dshmobile.ui.fog.FogPanel
import app.dshmobile.ui.fog.FogText
import app.dshmobile.ui.shell.LocalPagePadding
import app.dshmobile.ui.shell.Tags
import app.dshmobile.ui.theme.Sakura
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** What the bookshelf needs from dsh; null members mean dsh is not running (the shelf and stored chapters still work). */
class LibraryDsh(
    /** Creates a conversation, picks [model] and sends [opening]; returns its id. */
    val startBook: suspend (model: String?, opening: String) -> String,
    /** The live state of a book's conversation. */
    val conversation: (sessionId: String) -> Flow<ConversationState>,
    /** Sends a message in a book's conversation (「下一章」). */
    val send: suspend (sessionId: String, text: String) -> Unit,
    val openChat: (sessionId: String) -> Unit,
)

private sealed interface Page {
    data object Shelf : Page
    data class BookPage(val id: String) : Page
    data class Reader(val id: String, val key: String) : Page
    data object Cards : Page
}

/**
 * 書架 (stage 2 §D): covers with chapter count and reading progress, 新書 (title, setting card, model; one book is one
 * conversation, opened with the card), the chapters of a book (prose replies, 「不是章節」 to drop one), the reader (font size,
 * night mode, %, remembered position, 下一章) and the setting cards. Everything lives in the workspace file of [store].
 */
@Composable
fun LibraryScreen(store: LibraryStore, dsh: LibraryDsh?, models: List<Pair<String, String>>, defaultModel: String?) {
    val data by store.state.collectAsState()
    var pageKey by rememberSaveable { mutableStateOf("shelf") }
    val page: Page = when {
        pageKey == "cards" -> Page.Cards
        pageKey.startsWith("book:") -> Page.BookPage(pageKey.removePrefix("book:"))
        pageKey.startsWith("read:") -> pageKey.removePrefix("read:").split('|', limit = 2).let { Page.Reader(it[0], it.getOrElse(1) { "" }) }
        else -> Page.Shelf
    }
    BackHandler(enabled = page != Page.Shelf) {
        pageKey = when (page) { is Page.Reader -> "book:${page.id}"; else -> "shelf" }
    }
    when (page) {
        Page.Shelf -> Shelf(data, dsh, models, defaultModel, store, onOpen = { pageKey = "book:$it" }, onCards = { pageKey = "cards" })
        Page.Cards -> Cards(data.cards, store) { pageKey = "shelf" }
        is Page.BookPage -> data.books.firstOrNull { it.id == page.id }?.let { b ->
            BookView(b, store, dsh, onRead = { key -> pageKey = "read:${b.id}|$key" }) { pageKey = "shelf" }
        } ?: LaunchedEffect(Unit) { pageKey = "shelf" }
        is Page.Reader -> data.books.firstOrNull { it.id == page.id }?.let { b ->
            Reader(b, page.key, data, store, dsh, onKey = { k -> pageKey = "read:${b.id}|$k" }) { pageKey = "book:${b.id}" }
        } ?: LaunchedEffect(Unit) { pageKey = "shelf" }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Shelf(
    data: LibraryData, dsh: LibraryDsh?, models: List<Pair<String, String>>, defaultModel: String?, store: LibraryStore,
    onOpen: (String) -> Unit, onCards: () -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(LocalPagePadding.current),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FogButton("新書", FogButtonKind.AQUA, { creating = true }, Modifier.testTag(Tags.LIB_NEW), enabled = dsh != null)
            FogButton("設定卡（${data.cards.size}）", FogButtonKind.GHOST, onCards, Modifier.testTag(Tags.LIB_CARDS))
        }
        if (dsh == null) Text("dsh 啟動後才能開新書或寫下一章；已存的章節照樣能讀。", color = Sakura.TextSecondary, fontSize = 12.sp)
        if (creating && dsh != null) NewBook(data.cards, models, defaultModel, store, dsh, onDone = { id -> creating = false; id?.let(onOpen) })
        FogPanel(title = "書架", en = "LIBRARY", meta = "${data.books.size} 本", index = 0) {
            if (data.books.isEmpty()) {
                Text("還沒有書。按「新書」開一本，或在任何對話的「數據」旁按「加入書架」。", color = Sakura.TextSecondary, fontSize = 13.sp, modifier = Modifier.testTag(Tags.LIB_EMPTY))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                data.books.forEach { b -> Cover(b) { onOpen(b.id) } }
            }
        }
    }
}

/** A cover: a FOGCITY gradient, the title, the chapter count and how far it is read. */
@Composable
private fun Cover(b: Book, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val hue = (b.id.hashCode() and 0x7fffffff) % 3
    val colors = listOf(listOf(Sakura.Primary, Sakura.Aqua), listOf(Sakura.MilkTea, Sakura.Primary), listOf(Sakura.Aqua, Sakura.Background))[hue]
    val total = b.chapters.size
    val pct = if (total == 0) 0 else (b.readCount * 100 / total).coerceIn(0, 100)
    Column(
        Modifier.width(110.dp).clickable(role = Role.Button, onClick = onClick).testTag(Tags.LIB_BOOK + b.id),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.7f).background(Brush.linearGradient(colors), shape).border(1.dp, Sakura.White.copy(alpha = .8f), shape).padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(b.title, color = Sakura.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        Text("$total 章 · 讀到 $pct%", style = FogText.Label.copy(fontSize = 10.sp), maxLines = 1)
    }
}

@Composable
private fun NewBook(
    cards: List<CharacterCard>, models: List<Pair<String, String>>, defaultModel: String?, store: LibraryStore, dsh: LibraryDsh,
    onDone: (String?) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var cardId by rememberSaveable { mutableStateOf<String?>(cards.firstOrNull()?.id) }
    var model by rememberSaveable { mutableStateOf(defaultModel) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    FogPanel(title = "新書", en = "NEW BOOK", index = 1, modifier = Modifier.testTag(Tags.LIB_NEW_PANEL)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("書名") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(Tags.LIB_TITLE))
            Text("設定卡", style = FogText.Label)
            Choices(listOf<Pair<String?, String>>(null to "不用") + cards.map { it.id to it.name }, cardId) { cardId = it }
            Text("寫作模型", style = FogText.Label)
            Choices(models.map { it.first to it.second }, model) { model = it }
            error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FogButton(if (busy) "開書中…" else "開始寫", FogButtonKind.AQUA, {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val card = cards.firstOrNull { it.id == cardId }
                            val name = title.ifBlank { "未命名的書" }
                            val sid = dsh.startBook(model, Chapters.opening(name, card))
                            onDone(store.addBook(name, sid, card?.id, model).id)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = "開書失敗：${e.javaClass.simpleName}"
                        } finally {
                            busy = false
                        }
                    }
                }, Modifier.testTag(Tags.LIB_CREATE), enabled = !busy)
                FogButton("取消", FogButtonKind.GHOST, { onDone(null) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (v, label) ->
            val on = v == selected
            Text(
                label, fontSize = 13.sp, color = Sakura.Text, maxLines = 1,
                modifier = Modifier.heightIn(min = 36.dp)
                    .background(if (on) Sakura.Aqua.copy(alpha = .6f) else Sakura.White.copy(alpha = .5f), RoundedCornerShape(50))
                    .border(1.dp, if (on) Sakura.Aqua else Sakura.Primary.copy(alpha = .4f), RoundedCornerShape(50))
                    .clickable(role = Role.RadioButton) { onPick(v) }.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** Keeps a book's stored chapters in step with its conversation while it is shown. */
@Composable
private fun rememberChapters(b: Book, store: LibraryStore, dsh: LibraryDsh?): List<Chapter> {
    val flow = remember(b.sessionId, dsh) { dsh?.conversation(b.sessionId) ?: flowOf(null) }
    val conv by flow.collectAsState(null)
    val items = conv?.items
    val chapters = remember(b, items) { if (items == null) b.chapters else Chapters.of(b, items) }
    LaunchedEffect(chapters) { if (chapters != b.chapters && items != null) store.syncChapters(b.id, chapters) }
    return chapters
}

@Composable
private fun BookView(b: Book, store: LibraryStore, dsh: LibraryDsh?, onRead: (String) -> Unit, onBack: () -> Unit) {
    val chapters = rememberChapters(b, store, dsh)
    var confirmDelete by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(LocalPagePadding.current),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("‹ 書架") }
        FogPanel(title = b.title, en = "BOOK", meta = "${chapters.size} 章", index = 0, modifier = Modifier.testTag(Tags.LIB_BOOK_PAGE)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val resume = b.readKey?.takeIf { k -> chapters.any { it.key == k } } ?: chapters.firstOrNull()?.key
                if (resume != null) FogButton(if (b.readKey != null) "繼續讀" else "開始讀", FogButtonKind.AQUA, { onRead(resume) }, Modifier.testTag(Tags.LIB_READ))
                if (dsh != null) FogButton("打開對話", FogButtonKind.GHOST, { dsh.openChat(b.sessionId) })
            }
            if (chapters.isEmpty()) Text("還沒有章節。模型的長篇回覆（${Chapters.MIN_CHARS} 字以上）會自動變成章節。", color = Sakura.TextSecondary, fontSize = 13.sp)
            chapters.forEachIndexed { i, c ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable(role = Role.Button) { onRead(c.key) }.padding(vertical = 8.dp)) {
                        Text("第 ${i + 1} 章　" + c.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim('#', ' ').take(30), color = Sakura.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${c.text.length} 字", style = FogText.Label.copy(fontSize = 10.sp))
                    }
                    TextButton(onClick = { store.setChapter(b.id, c.key, false) }, modifier = Modifier.testTag(Tags.LIB_NOT_CHAPTER + c.key)) { Text("不是章節", fontSize = 12.sp) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                if (!confirmDelete) TextButton(onClick = { confirmDelete = true }) { Text("從書架移除", color = Sakura.RoseBrown) }
                else {
                    Text("對話會留著，只是不在書架上。", fontSize = 12.sp, color = Sakura.TextSecondary, modifier = Modifier.weight(1f))
                    TextButton(onClick = { store.removeBook(b.id); onBack() }) { Text("確定移除", color = Sakura.RoseBrown) }
                }
            }
        }
    }
}

/** The reader: one chapter full width, font size, night mode, read %, remembered position, previous / next, 下一章 (asks the model). */
@Composable
private fun Reader(b: Book, key: String, data: LibraryData, store: LibraryStore, dsh: LibraryDsh?, onKey: (String) -> Unit, onBack: () -> Unit) {
    val chapters = rememberChapters(b, store, dsh)
    val index = chapters.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: 0
    val chapter = chapters.getOrNull(index)
    val scroll = rememberScrollState(if (b.readKey == key) b.readScroll else 0)
    val night = data.night
    val bg = if (night) Sakura.Text else Color(0xFFFFF6F6)
    val ink = if (night) Sakura.Background else Sakura.Text
    var asked by remember(chapters.size) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(b.id, key) {
        snapshotFlow { scroll.value }.debounce(400).collect { v ->
            val done = if (scroll.maxValue > 0 && v >= scroll.maxValue - 40) index + 1 else index
            store.savePosition(b.id, key, v, done)
        }
    }
    Column(Modifier.fillMaxSize().background(bg).padding(LocalPagePadding.current).testTag(Tags.LIB_READER)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹", color = ink, fontSize = 20.sp) }
            Text("${b.title}　${index + 1}/${chapters.size}", color = ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = { store.setReading(fontSp = data.fontSp - 2) }, modifier = Modifier.testTag(Tags.LIB_FONT_DOWN)) { Text("A-", color = ink) }
            TextButton(onClick = { store.setReading(fontSp = data.fontSp + 2) }, modifier = Modifier.testTag(Tags.LIB_FONT_UP)) { Text("A+", color = ink) }
            TextButton(onClick = { store.setReading(night = !night) }, modifier = Modifier.testTag(Tags.LIB_NIGHT)) { Text(if (night) "日" else "夜", color = ink) }
        }
        val pct = if (scroll.maxValue > 0) scroll.value * 100 / scroll.maxValue else 100
        Box(Modifier.fillMaxWidth().height(2.dp).background(ink.copy(alpha = .15f))) {
            Box(Modifier.fillMaxWidth(pct / 100f).height(2.dp).background(Sakura.Aqua))
        }
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text(chapter?.text ?: "（沒有內容）", color = ink, fontSize = data.fontSp.sp, lineHeight = (data.fontSp * 1.75f).sp, modifier = Modifier.testTag(Tags.LIB_TEXT))
            Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (index > 0) FogButton("上一章", FogButtonKind.GHOST, { onKey(chapters[index - 1].key) })
                if (index < chapters.size - 1) FogButton("下一章", FogButtonKind.AQUA, { onKey(chapters[index + 1].key) }, Modifier.testTag(Tags.LIB_NEXT))
                else if (dsh != null) FogButton(if (asked) "正在寫下一章…" else "請它寫下一章", FogButtonKind.AQUA, {
                    asked = true
                    scope.launch { runCatching { dsh.send(b.sessionId, Chapters.NEXT) }.onFailure { asked = false } }
                }, Modifier.testTag(Tags.LIB_WRITE_NEXT), enabled = !asked)
            }
            Text("讀到 $pct%", color = ink.copy(alpha = .6f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun Cards(cards: List<CharacterCard>, store: LibraryStore, onBack: () -> Unit) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(LocalPagePadding.current),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("‹ 書架") }
        FogPanel(title = "設定卡", en = "CARDS", meta = "${cards.size} 張", index = 0, modifier = Modifier.testTag(Tags.LIB_CARDS_PAGE)) {
            Text("角色、世界觀、文風都可以寫在卡上。開新書時選一張，它會當作故事的開場設定。", color = Sakura.TextSecondary, fontSize = 12.sp)
            cards.forEach { c ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { editing = c.id; name = c.name; body = c.body }.padding(vertical = 8.dp)) {
                        Text(c.name, color = Sakura.Text, fontSize = 15.sp)
                        Text(c.body, color = Sakura.TextSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { store.removeCard(c.id) }) { Text("刪除", color = Sakura.RoseBrown, fontSize = 12.sp) }
                }
            }
            if (editing == null) FogButton("新增設定卡", FogButtonKind.AQUA, { editing = ""; name = ""; body = "" }, Modifier.testTag(Tags.LIB_CARD_NEW))
            else {
                OutlinedTextField(name, { name = it }, label = { Text("名稱") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(Tags.LIB_CARD_NAME))
                OutlinedTextField(body, { body = it }, label = { Text("內容（角色、世界、文風…）") }, minLines = 5, modifier = Modifier.fillMaxWidth().testTag(Tags.LIB_CARD_BODY))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FogButton("儲存", FogButtonKind.AQUA, {
                        val id = editing
                        if (id.isNullOrEmpty()) store.newCard(name, body) else store.saveCard(CharacterCard(id, name.ifBlank { "新設定卡" }, body))
                        editing = null
                    }, Modifier.testTag(Tags.LIB_CARD_SAVE))
                    FogButton("取消", FogButtonKind.GHOST, { editing = null })
                }
            }
        }
    }
}

/** The models a book can be written with, as (id, label). */
fun bookModels(hasClaudeKey: Boolean): List<Pair<String, String>> =
    AgentModels.available(hasClaudeKey).map { it.id to (AgentModels.displayName(it.id) ?: it.id) }
