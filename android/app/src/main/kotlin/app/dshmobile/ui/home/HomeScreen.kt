package app.dshmobile.ui.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.agency.core.agent.Today
import app.agency.core.plan.ItemStatus
import app.agency.core.plan.PlanItem
import app.agency.core.plan.Screen
import app.agency.core.store.Message
import app.agency.core.store.Role
import app.dshmobile.R
import app.dshmobile.ui.UiGraph
import app.dshmobile.ui.cards.CardActions
import app.dshmobile.ui.cards.CardView
import app.dshmobile.ui.cards.ScoreRow
import app.dshmobile.ui.theme.ChipPill
import app.dshmobile.ui.theme.Glyph
import app.dshmobile.ui.theme.GlyphIcon
import app.dshmobile.ui.theme.KText
import app.dshmobile.ui.theme.Pal
import app.dshmobile.ui.theme.PillShape
import app.dshmobile.ui.theme.Type
import app.dshmobile.ui.theme.XiaokuiAvatar
import app.dshmobile.ui.theme.paper
import app.dshmobile.ui.theme.sticker
import app.dshmobile.ui.theme.tap
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/** True for a stored message that was not sent. */
val Message.failed: Boolean get() = meta["failed"]?.toString() == "true"

/**
 * Home: the header, three stickers, the unfolding handbook page, the conversation, the next-thing pill, quick replies and the
 * input. [onOpen] navigates (screens other than the camera); [micAvailable] null = ask the system whether an on-device
 * recogniser exists.
 */
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    graph: UiGraph,
    onOpen: (Screen, String) -> Unit,
    onTrainee: () -> Unit,
    onSettings: () -> Unit,
    micAvailable: Boolean? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val today by vm.today.collectAsState()
    val messages by vm.messages.collectAsState()
    val local by vm.local.collectAsState()
    val thinking by vm.thinking.collectAsState()
    val attachments by vm.attachments.collectAsState()
    val stickersOpen by vm.stickersOpen.collectAsState()
    val handbookOpen by vm.handbookOpen.collectAsState()
    val chipsOpen by vm.chipsOpen.collectAsState()
    val input = remember { TextFieldState() }

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    val camera: (String) -> Unit = { slot ->
        scope.launch {
            val photo = graph.media.takePhoto(slotWord(context, slot))
            if (photo != null) {
                vm.addAttachments(listOf(photo))
                vm.send(context.getString(R.string.say_photo, slotWord(context, slot)))
            }
        }
    }
    val nav = remember(onOpen) { Navigator { screen, arg -> if (screen == Screen.CAMERA) camera(arg) else onOpen(screen, arg) } }

    val actions = remember(graph, vm) {
        object : CardActions {
            override fun answer(messageId: Long, cardIndex: Int, answer: String) = vm.answerCard(messageId, cardIndex, answer)
            override fun open(screen: String, arg: String) {
                val s = runCatching { Screen.valueOf(screen) }.getOrNull() ?: return
                nav.open(s, arg)
            }

            override fun openUrl(url: String) = openLink(context, url)
            override fun watch(lessonId: Long) {
                scope.launch { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { graph.lessons.watch(lessonId) } }
            }
        }
    }

    // mic
    val micOverride = app.dshmobile.ui.LocalMicOverride.current
    val micOk = micAvailable ?: micOverride ?: remember { OnDeviceSpeech.available(context) }
    val speech = remember { OnDeviceSpeech(context) }
    var listening by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { speech.stop() } }
    fun startListening() {
        listening = true
        speech.start({ text, _ -> input.setTextAndPlaceCursorAtEnd(text) }, { listening = false })
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) startListening() }

    val inputActions = remember(vm, graph) {
        InputActions(
            onSend = { vm.send(input.text.toString()); input.clearText() },
            onCamera = { camera("") },
            onGallery = { scope.launch { vm.addAttachments(graph.media.pickPhotos()) } },
            onFile = { scope.launch { graph.media.pickFile()?.let { vm.addAttachments(listOf(it)) } } },
            onMic = {
                if (listening) {
                    speech.stop(); listening = false
                } else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startListening()
                } else {
                    askMic.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onRemove = { vm.removeAttachment(it) },
            onUris = { uris -> scope.launch { vm.addAttachments(graph.media.fromUris(uris)) } },
        )
    }

    Column(Modifier.fillMaxSize().paper().safeDrawingPadding().padding(horizontal = 20.dp)) {
        Header(today, stickersOpen, vm::toggleStickers, onTrainee, onSettings)
        val t = today
        if (t != null && stickersOpen) {
            StickerRow(t, handbookOpen, vm::toggleHandbook)
            if (handbookOpen) Handbook(t, { vm.onTap(it.tap, nav) })
        }
        Conversation(
            messages = messages, local = local, thinking = thinking, actions = actions, graph = graph, vm = vm,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        if (t != null) {
            t.plan.next?.let { next -> NextPill(next, Modifier.padding(top = 8.dp, bottom = 10.dp)) { vm.onTap(next.tap, nav) } }
            if (t.chips.isNotEmpty()) {
                ChipsRow(t.chips, chipsOpen, vm::toggleChips, { vm.send(it) })
            }
        }
        InputBar(input, attachments, micOk, listening, inputActions, Modifier.padding(bottom = 10.dp))
    }
}

private fun slotWord(context: Context, slot: String): String = context.getString(
    when (slot) {
        "FIRST" -> R.string.slot_first
        "DINNER" -> R.string.slot_dinner
        "NIGHT" -> R.string.slot_night
        else -> R.string.slot_other
    },
)

internal fun openLink(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun Header(today: Today?, stickersOpen: Boolean, onToggle: () -> Unit, onTrainee: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        val cdTrainee = stringResource(R.string.cd_trainee)
        Box(Modifier.tap(onTrainee).semantics { contentDescription = cdTrainee }.testTag("avatar")) { XiaokuiAvatar(72.dp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KText(stringResource(R.string.home_name), size = Type.Title, bold = true)
                KText("✦", size = 15.sp)
            }
            Row(
                Modifier.tap(onToggle).testTag("toggle_stickers").padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                KText(today?.stageLabel ?: "", size = 13.sp, alpha = 0.65f)
                GlyphIcon(if (stickersOpen) Glyph.ChevronUp else Glyph.ChevronDown, size = 16.dp, color = Pal.Ink.copy(alpha = 0.65f))
            }
        }
        val cdSettings = stringResource(R.string.cd_settings)
        Box(
            Modifier.size(40.dp).sticker(Pal.White, CircleShape, shadow = 2.dp).tap(onSettings).semantics { contentDescription = cdSettings }.testTag("settings"),
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(Glyph.Gear, size = 22.dp) }
    }
}

@Composable
private fun StickerRow(today: Today, handbookOpen: Boolean, onToggleHandbook: () -> Unit) {
    val fills = listOf(Pal.Lavender, Pal.Pink, Pal.Mint)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        today.stickers.take(3).forEachIndexed { i, s ->
            // one tag per node: a second testTag on the same node is dropped, so the handbook toggle carries its own
            val base = Modifier.weight(1f).sticker(fills[i % 3], RoundedCornerShape(12.dp), 2.dp)
            Column(
                (if (i == 2) base.tap(onToggleHandbook).testTag("toggle_handbook") else base.testTag("sticker_$i")).padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    KText(s.label, size = 12.sp, alpha = 0.85f, maxLines = 1)
                    if (i == 2) GlyphIcon(if (handbookOpen) Glyph.ChevronUp else Glyph.ChevronDown, size = 14.dp)
                }
                KText(s.value, size = 17.sp, bold = true, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Handbook(today: Today, onTapItem: (PlanItem) -> Unit) {
    val plan = today.plan
    Column(
        Modifier.padding(top = 10.dp, bottom = 8.dp).testTag("handbook").sticker(Pal.White, RoundedCornerShape(18.dp), 4.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp).heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            KText(stringResource(R.string.handbook_title), size = 15.sp)
            KText(stringResource(R.string.handbook_progress, plan.done, plan.total), size = 15.sp)
        }
        plan.items.forEach { item ->
            val isNext = plan.next?.id == item.id
            HandbookRow(item, isNext) { onTapItem(item) }
        }
        if (plan.adjustments.isNotEmpty()) {
            Column(Modifier.padding(top = 8.dp).fillMaxWidth().background(Pal.Paper, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp).testTag("adjustments")) {
                KText(stringResource(R.string.handbook_adjustments), size = 13.5.sp, bold = true)
                plan.adjustments.forEach { KText(stringResource(R.string.handbook_adjust_line, it.what, it.why), size = 13.5.sp) }
            }
        }
    }
}

private val TimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")

@Composable
private fun HandbookRow(item: PlanItem, isNext: Boolean, onClick: () -> Unit) {
    val dim = when (item.status) {
        ItemStatus.DONE -> 0.55f
        ItemStatus.OFF -> 0.4f
        ItemStatus.MISSED -> 0.6f
        else -> 1f
    }
    val rowMod = Modifier.fillMaxWidth().testTag("item_${item.id}")
        .let { if (isNext) it.background(Pal.Pink, RoundedCornerShape(12.dp)) else it.drawBehind { dashed(size.width) } }
        .let { if (item.status == ItemStatus.OFF) it else it.tap(onClick) }
        .padding(horizontal = 6.dp, vertical = 8.dp)
    Row(rowMod, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusMark(item.status)
        KText(item.title, Modifier.weight(1f), size = Type.Body, bold = isNext, alpha = dim)
        val right = item.detail.ifBlank { item.at?.format(TimeFmt) ?: "" }
        if (right.isNotBlank()) KText(right, size = 13.sp, alpha = dim * 0.9f)
        if (isNext) GlyphIcon(Glyph.ArrowRight, size = 14.dp)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.dashed(w: Float) {
    drawLine(
        Pal.Ink.copy(alpha = 0.2f), Offset(0f, 0f), Offset(w, 0f), 1.5.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
    )
}

@Composable
private fun StatusMark(status: ItemStatus) {
    val fill: Color = when (status) {
        ItemStatus.DONE -> Pal.Mint
        ItemStatus.LATE -> Pal.Butter
        ItemStatus.MISSED -> Pal.Paper
        else -> Color.Transparent
    }
    Box(Modifier.size(22.dp).background(fill, CircleShape).border(2.dp, Pal.Ink.copy(alpha = if (status == ItemStatus.OFF) 0.35f else 1f), CircleShape), contentAlignment = Alignment.Center) {
        when (status) {
            ItemStatus.DONE -> GlyphIcon(Glyph.Check, size = 13.dp)
            ItemStatus.LATE -> KText("!", size = 13.sp, bold = true)
            ItemStatus.MISSED -> GlyphIcon(Glyph.Close, size = 11.dp, color = Pal.Ink.copy(alpha = 0.6f))
            else -> {}
        }
    }
}

private sealed interface ChatItem {
    val key: String
    data class Stored(val m: Message) : ChatItem { override val key get() = "m${m.id}" }
    data class Local(val m: LocalMessage) : ChatItem { override val key get() = "l${m.id}" }
}

@Composable
private fun Conversation(
    messages: List<Message>,
    local: List<LocalMessage>,
    thinking: Boolean,
    actions: CardActions,
    graph: UiGraph,
    vm: HomeViewModel,
    modifier: Modifier,
) {
    val items = remember(messages, local) { (messages.map { ChatItem.Stored(it) } + local.map { ChatItem.Local(it) }).asReversed() }
    val state = rememberLazyListState()
    LaunchedEffect(items.firstOrNull()?.key, thinking) {
        if (state.firstVisibleItemIndex <= 3) state.scrollToItem(0)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index to state.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (last != null && total > 0 && last >= total - 2) vm.loadOlder() }
    }
    LazyColumn(
        modifier.testTag("conversation"), state = state, reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Top),
    ) {
        if (thinking) item(key = "thinking") { Bubble(stringResource(R.string.home_thinking), mine = false, Modifier.testTag("thinking"), alpha = 0.7f) }
        items(items, key = { it.key }) { ci ->
            when (ci) {
                is ChatItem.Stored -> MessageView(ci.m, actions, graph, { vm.resend(ci.m.id) })
                is ChatItem.Local -> LocalView(ci.m) { vm.resendLocal(ci.m.id) }
            }
        }
    }
}

@Composable
private fun LocalView(m: LocalMessage, onResend: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (m.text.isNotBlank()) Bubble(m.text, mine = true, Modifier)
        if (m.attachments.isNotEmpty()) {
            KText(stringResource(R.string.attach_count, m.attachments.size), size = Type.Tiny, alpha = 0.6f, modifier = Modifier.padding(top = 2.dp))
        }
        if (m.failed) KText(stringResource(R.string.msg_resend), Modifier.padding(top = 2.dp).tap(onResend).testTag("resend_local"), size = 13.sp, color = Pal.Warn, bold = true)
    }
}

@Composable
private fun MessageView(m: Message, actions: CardActions, graph: UiGraph, onResend: () -> Unit) {
    if (m.role == Role.NOTE) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            KText(m.text, size = 13.sp, alpha = 0.6f, modifier = Modifier.testTag("note"))
        }
        return
    }
    val mine = m.role == Role.OWNER
    val align = if (mine) Alignment.End else Alignment.Start
    val scoreIdx = m.cards.indexOfFirst { it is app.agency.core.agent.Card.Score }
    val inline = scoreIdx >= 0 && m.text.isNotBlank()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (m.text.isNotBlank()) {
            Bubble(m.text, mine, Modifier) {
                if (inline) {
                    ScoreRow(m.cards[scoreIdx] as app.agency.core.agent.Card.Score, { actions.answer(m.id, scoreIdx, it.toString()) }, Modifier.padding(top = 8.dp))
                }
            }
        }
        m.cards.forEachIndexed { i, c ->
            if (inline && i == scoreIdx) return@forEachIndexed
            CardView(c, m.id, i, actions, graph.media, Modifier.fillMaxWidth(0.88f))
        }
        if (m.failed) KText(stringResource(R.string.msg_resend), Modifier.tap(onResend).testTag("resend_${m.id}"), size = 13.sp, color = Pal.Warn, bold = true)
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean, modifier: Modifier, alpha: Float = 1f, extra: @Composable () -> Unit = {}) {
    val shape = RoundedCornerShape(20.dp, 20.dp, if (mine) 4.dp else 20.dp, if (mine) 20.dp else 4.dp)
    val maxW = (LocalConfiguration.current.screenWidthDp * 0.84f).dp
    Column(modifier.widthIn(max = maxW).sticker(if (mine) Pal.Lavender else Pal.White, shape, shadow = 0.dp).padding(horizontal = 14.dp, vertical = 10.dp)) {
        KText(text, size = Type.Body, alpha = alpha)
        extra()
    }
}

@Composable
private fun NextPill(next: PlanItem, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().testTag("next_pill").sticker(Pal.PinkStrong, PillShape, 4.dp).tap(onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val time = next.at?.format(TimeFmt)
            KText(if (time != null) stringResource(R.string.home_next_at, time) else stringResource(R.string.home_next), size = 12.sp, alpha = 0.8f)
            KText(next.title, size = 17.sp, bold = true)
        }
        GlyphIcon(Glyph.ArrowRight, size = 20.dp)
    }
}

@Composable
private fun ChipsRow(chips: List<String>, open: Boolean, onToggle: () -> Unit, onSend: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val cd = stringResource(R.string.chips_label)
        Row(
            Modifier.tap(onToggle).testTag("toggle_chips").semantics { contentDescription = cd }.padding(end = 2.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(Modifier.size(26.dp).sticker(Pal.White, CircleShape, shadow = 0.dp, outline = 1.5.dp), contentAlignment = Alignment.Center) {
                GlyphIcon(if (open) Glyph.ChevronDown else Glyph.ChevronUp, size = 15.dp)
            }
            if (!open) KText(cd, size = 13.sp, alpha = 0.65f)
        }
        if (open) {
            Row(Modifier.horizontalScroll(rememberScrollState()).testTag("chips"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chips.forEach { c -> ChipPill(c, { onSend(c) }, Modifier.testTag("chip_$c")) }
            }
        }
    }
}
