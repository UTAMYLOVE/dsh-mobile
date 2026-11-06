package app.dshmobile.ui.shell

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.ui.fog.FogMotion
import app.dshmobile.ui.fog.FogText
import app.dshmobile.ui.fog.LocalEffects
import app.dshmobile.ui.fog.glass
import app.dshmobile.ui.fog.glow
import app.dshmobile.ui.theme.Sakura

/** What the dock's input row shows and does; the screen owns the text (later tasks: the open conversation's draft). */
data class ComposerUi(
    val text: String,
    val onText: (String) -> Unit,
    val enabled: Boolean,
    val canSend: Boolean,
    val running: Boolean,
    val onSend: () -> Unit,
    val onStop: () -> Unit,
    val onAttachImage: () -> Unit = {},
    val onAttachFile: () -> Unit = {},
    val chips: List<AttachmentChip> = emptyList(),
    val onRemoveChip: (String) -> Unit = {},
    val error: String? = null,
    /** A one-line state above the field (編輯中…), with ✕ to cancel it; null shows nothing. */
    val notice: String? = null,
    val onCancelNotice: () -> Unit = {},
)

/** One picked attachment, shown above the input row until it is sent or removed. */
data class AttachmentChip(
    val id: String,
    val name: String,
    val isImage: Boolean,
    /** Decodes a small preview of an image (off the main thread, subsampled); null for files or when it cannot be shown. */
    val thumbnail: (suspend () -> ImageBitmap?)? = null,
)

private val Round = RoundedCornerShape(50)
private val DockShape = RoundedCornerShape(30.dp)

/**
 * The floating glass dock: picked attachments, the input row (attach, text, send or stop) and the five tabs with the sliding
 * pill. [enabled] is false until dsh is ready: input and send are disabled and the hint says why. Attach stays usable (picking
 * is local; nothing is read or sent until a send).
 */
@Composable
internal fun Dock(
    composer: ComposerUi,
    enabled: Boolean,
    tab: ShellTab,
    onSelect: (ShellTab) -> Unit,
    onToggleAttachMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            // A layer of its own: the pill's slide and the composer's caret re-record the dock, not the shell around it.
            .graphicsLayer { }
            .glass(DockShape)
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
    ) {
        if (composer.chips.isNotEmpty()) {
            ChipRow(composer.chips, composer.onRemoveChip)
            Spacer(Modifier.height(10.dp))
        }
        composer.notice?.let { n ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).background(Sakura.Aqua.copy(alpha = .35f), RoundedCornerShape(12.dp))
                    .padding(start = 10.dp).testTag(Tags.EDIT_BANNER),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(n, color = Sakura.Text, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(40.dp).clickable(role = Role.Button, onClick = composer.onCancelNotice).semantics { contentDescription = "取消" },
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = Sakura.Text, fontSize = 14.sp) }
            }
        }
        composer.error?.let {
            Text(
                it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp).testTag(Tags.COMPOSER_ERROR),
            )
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundButton(
                description = stringResource(R.string.shell_attach),
                enabled = true,
                tag = Tags.ATTACH,
                onClick = onToggleAttachMenu,
                background = { a ->
                    Modifier
                        .glow(Sakura.Aqua.copy(alpha = .70f * a), 14.dp, CircleShape)
                        .background(Sakura.Aqua.copy(alpha = .40f * a), CircleShape)
                        .border(1.dp, Sakura.Aqua.copy(alpha = .95f * a), CircleShape)
                },
            ) { tint -> PlusIcon(tint) }
            InputField(composer, enabled, Modifier.weight(1f))
            val pink = { a: Float ->
                Modifier
                    .glow(Sakura.Primary.copy(alpha = .85f * a), 18.dp, CircleShape, dy = 6.dp)
                    .background(Brush.radialGradient(listOf(Sakura.Background.copy(alpha = a), Sakura.Primary.copy(alpha = a))), CircleShape)
                    .border(1.dp, Color.White.copy(alpha = .9f * a), CircleShape)
            }
            if (composer.running && composer.text.isBlank()) {
                RoundButton(stringResource(R.string.shell_stop), enabled, Tags.STOP, composer.onStop, pink) { tint -> StopIcon(tint) }
            } else {
                RoundButton(stringResource(R.string.shell_send), enabled && composer.canSend, Tags.SEND, composer.onSend, pink) { tint -> SendIcon(tint) }
            }
        }
        Spacer(Modifier.height(8.dp))
        TabRow(tab, onSelect)
    }
}

/** The attach button's small menu, floating just above the dock (never over it). */
@Composable
internal fun AttachMenu(onImage: () -> Unit, onFile: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.glass(DockShape).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MenuItem(stringResource(R.string.shell_attach_image), Tags.ATTACH_IMAGE, onImage)
        MenuItem(stringResource(R.string.shell_attach_file), Tags.ATTACH_FILE, onFile)
    }
}

@Composable
private fun MenuItem(label: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 88.dp)
            .clip(Round)
            .background(Color.White.copy(alpha = .6f))
            .border(1.dp, Color.White.copy(alpha = .9f), Round)
            .testTag(tag)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Sakura.Text, fontSize = 15.sp)
    }
}

@Composable
private fun InputField(composer: ComposerUi, enabled: Boolean, modifier: Modifier) {
    val hint = stringResource(if (enabled) R.string.shell_composer_hint else R.string.shell_composer_hint_disabled)
    BasicTextField(
        value = composer.text,
        onValueChange = composer.onText,
        enabled = enabled,
        textStyle = TextStyle(color = Sakura.Text, fontSize = 15.sp, lineHeight = 22.sp),
        cursorBrush = SolidColor(Sakura.Primary),
        maxLines = 5,
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(Round)
            .background(Color.White.copy(alpha = .5f))
            .border(1.dp, Color.White.copy(alpha = .9f), Round)
            .testTag(Tags.COMPOSER_INPUT),
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 18.dp, vertical = 13.dp), contentAlignment = Alignment.CenterStart) {
                if (composer.text.isEmpty()) {
                    Text(
                        hint, color = Sakura.TextSecondary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(Tags.COMPOSER_HINT),
                    )
                }
                inner()
            }
        },
    )
}

@Composable
private fun RoundButton(
    description: String,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
    background: (alpha: Float) -> Modifier,
    icon: @Composable (tint: Color) -> Unit,
) {
    // Disabled = every colour at .45 alpha, not an alpha layer: a layer would clip the glow (drawn outside the circle) to a square.
    val a = if (enabled) 1f else DISABLED_ALPHA
    Box(
        Modifier
            .size(48.dp)
            .then(background(a))
            .clip(CircleShape)
            .testTag(tag)
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { icon(Sakura.Text.copy(alpha = a)) }
}

private const val DISABLED_ALPHA = .45f

private val IconStroke = 2.dp

@Composable
private fun PlusIcon(tint: Color) = Canvas(Modifier.size(20.dp)) {
    val w = IconStroke.toPx()
    drawLine(tint, Offset(size.width / 2, 2f), Offset(size.width / 2, size.height - 2f), w, StrokeCap.Round)
    drawLine(tint, Offset(2f, size.height / 2), Offset(size.width - 2f, size.height / 2), w, StrokeCap.Round)
}

@Composable
private fun SendIcon(tint: Color) = Canvas(Modifier.size(20.dp)) {
    val w = IconStroke.toPx()
    val cx = size.width / 2
    drawLine(tint, Offset(cx, size.height - 2f), Offset(cx, 2f), w, StrokeCap.Round)
    drawLine(tint, Offset(cx, 2f), Offset(cx - size.width * .32f, size.height * .36f), w, StrokeCap.Round)
    drawLine(tint, Offset(cx, 2f), Offset(cx + size.width * .32f, size.height * .36f), w, StrokeCap.Round)
}

@Composable
private fun StopIcon(tint: Color) = Canvas(Modifier.size(16.dp)) {
    drawRoundRect(tint, cornerRadius = CornerRadius(3.dp.toPx()))
}

@Composable
private fun CrossIcon() = Canvas(Modifier.size(10.dp)) {
    val w = 1.6.dp.toPx()
    drawLine(Color.White, Offset.Zero, Offset(size.width, size.height), w, StrokeCap.Round)
    drawLine(Color.White, Offset(size.width, 0f), Offset(0f, size.height), w, StrokeCap.Round)
}

/**
 * Picked attachments as 64 dp tiles in one line that scrolls sideways at phone width, so the row never covers a button. Each tile
 * has a 24 dp cross at its top right with a 48 dp touch target.
 */
@Composable
private fun ChipRow(chips: List<AttachmentChip>, onRemove: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { chip -> key(chip.id) { Tile(chip, onRemove) } }
    }
}

private val TileShape = RoundedCornerShape(16.dp)

@Composable
private fun Tile(chip: AttachmentChip, onRemove: (String) -> Unit) {
    val remove = stringResource(R.string.shell_remove_attachment, chip.name)
    val thumb = chip.thumbnail
    val image by produceState<ImageBitmap?>(null, chip.id) { value = thumb?.invoke() }
    Box(Modifier.size(72.dp).testTag(Tags.ATTACHMENT_CHIP + chip.id)) {
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .size(64.dp)
                .clip(TileShape)
                .background(Color.White.copy(alpha = .7f))
                .border(1.dp, Sakura.Primary.copy(alpha = .6f), TileShape),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = image
            if (bitmap != null) {
                Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    chip.name, color = Sakura.Text, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .testTag(Tags.ATTACHMENT_REMOVE + chip.id)
                .semantics { contentDescription = remove }
                .clickable(role = Role.Button) { onRemove(chip.id) }
                .minimumInteractiveComponentSize(),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(24.dp).background(Sakura.Text.copy(alpha = .70f), CircleShape), contentAlignment = Alignment.Center) { CrossIcon() }
        }
    }
}

/** Five tabs (Chinese over English) with a white pill that slides to the selected one. */
@Composable
private fun TabRow(selected: ShellTab, onSelect: (ShellTab) -> Unit) {
    val effects = LocalEffects.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cell = maxWidth / ShellTab.entries.size
        val target = cell * selected.ordinal
        val x = animateDpAsState(
            target,
            if (effects.pageMs == 0) snap() else tween(FogMotion.PILL_MS, easing = FogMotion.Spring),
            label = "tab-pill",
        )
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier
                    .offset { IntOffset(x.value.roundToPx(), 0) } // read while placing: the slide never recomposes
                    .width(cell)
                    .fillMaxHeight()
                    .glow(Sakura.RoseBrown.copy(alpha = .18f), 14.dp, Round, dy = 4.dp)
                    .background(Brush.linearGradient(listOf(Color.White.copy(alpha = .85f), Color.White.copy(alpha = .45f))), Round)
                    .border(1.dp, Color.White.copy(alpha = .9f), Round),
            )
        }
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            ShellTab.entries.forEach { t ->
                val on = t == selected
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(Round)
                        .testTag(Tags.TAB + t.name)
                        .selectable(selected = on, role = Role.Tab, onClick = { onSelect(t) })
                        .padding(horizontal = 2.dp, vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        t.zh, color = if (on) Sakura.Text else Sakura.TextSecondary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        letterSpacing = 0.08.em, maxLines = 1, overflow = TextOverflow.Clip,
                    )
                    // Decoration: the tab reads as its Chinese name (the English line may clip at large font sizes on 360 dp).
                    Text(
                        t.en, style = FogText.Label.copy(fontSize = 9.sp, letterSpacing = 0.16.em), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            }
        }
    }
}
