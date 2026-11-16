package app.dshmobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dshmobile.R
import app.dshmobile.ui.fog.AvatarCrop
import app.dshmobile.ui.fog.BackdropStyle
import app.dshmobile.ui.fog.Intro
import app.dshmobile.ui.fog.IntroStyle
import app.dshmobile.ui.fog.LocalLookPicture
import app.dshmobile.ui.fog.Look
import app.dshmobile.ui.fog.LookController
import app.dshmobile.ui.fog.LookStore
import app.dshmobile.ui.fog.PictureAlign
import app.dshmobile.ui.fog.Segmented
import app.dshmobile.ui.fog.avatarSquare
import app.dshmobile.ui.fog.rememberAvatarCrop
import app.dshmobile.ui.fog.rememberLookPicture
import app.dshmobile.ui.theme.Sakura
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 外觀主題: the background style and its floating things, the user's own picture (recoloured to the six colours) and the startup
 * animation. Every choice applies at once; sliders commit when let go.
 */
@Composable
internal fun LookSettings(controller: LookController) {
    val look by controller.look.collectAsStateWithLifecycle()
    val set = controller::update
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Segmented(
            options = listOf(stringResource(R.string.look_style_cosy), stringResource(R.string.look_style_kawaii)),
            selected = look.style.ordinal,
            onSelect = { i -> set { it.copy(style = BackdropStyle.entries[i]) } },
            tagPrefix = LookTags.STYLE,
        )
        if (look.style == BackdropStyle.KAWAII) {
            Text(stringResource(R.string.look_kawaii_hint), style = MaterialTheme.typography.bodySmall)
            Check(R.string.look_petals, look.petals, "petals") { v -> set { it.copy(petals = v) } }
            Check(R.string.look_sparkles, look.sparkles, "sparkles") { v -> set { it.copy(sparkles = v) } }
            Check(R.string.look_hearts, look.hearts, "hearts") { v -> set { it.copy(hearts = v) } }
            Check(R.string.look_clouds, look.clouds, "clouds") { v -> set { it.copy(clouds = v) } }
            Percent(R.string.look_amount, look.amount, "amount") { v -> set { it.copy(amount = v) } }
            Percent(R.string.look_speed, look.speed, "speed") { v -> set { it.copy(speed = v) } }
        }

        HorizontalDivider(color = Sakura.MilkTea)
        PictureSettings(controller, look)

        HorizontalDivider(color = Sakura.MilkTea)
        IntroSettings(controller, look.intro)

        HorizontalDivider(color = Sakura.MilkTea)
        OutlinedButton(onClick = controller::restoreDefaults, modifier = Modifier.testTag(LookTags.RESTORE)) { Text(stringResource(R.string.look_restore)) }
        Text(stringResource(R.string.look_restore_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PictureSettings(controller: LookController, look: Look) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bad by remember { mutableStateOf(false) }
    // The system photo picker: no storage permission is needed, the app only sees the one picture chosen.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { bad = !controller.setPicture { context.contentResolver.openInputStream(uri) } }
    }
    val set = controller::update
    Text(stringResource(R.string.look_picture), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.look_picture_hint), style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { bad = false; pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            modifier = Modifier.testTag(LookTags.PICK),
        ) { Text(stringResource(if (look.hasPicture) R.string.look_picture_change else R.string.look_picture_pick)) }
        if (look.hasPicture) {
            TextButton(onClick = { scope.launch { controller.removePicture() } }, modifier = Modifier.testTag(LookTags.REMOVE)) {
                Text(stringResource(R.string.look_picture_remove))
            }
        }
    }
    if (bad) Text(stringResource(R.string.look_picture_bad), color = Sakura.RoseBrown, style = MaterialTheme.typography.bodySmall)
    if (look.hasPicture) {
        Percent(R.string.look_six, look.six, "six") { v -> set { it.copy(six = v) } }
        Text(stringResource(R.string.look_six_hint), style = MaterialTheme.typography.bodySmall)
        Percent(R.string.look_aqua, look.aqua, "aqua") { v -> set { it.copy(aqua = v) } }
        Percent(R.string.look_opacity, look.opacity, "opacity", min = LookStore.MIN_OPACITY) { v -> set { it.copy(opacity = v) } }
        Text(stringResource(R.string.look_align))
        Segmented(
            options = listOf(stringResource(R.string.look_align_top), stringResource(R.string.look_align_center), stringResource(R.string.look_align_bottom)),
            selected = look.align.ordinal,
            onSelect = { i -> set { it.copy(align = PictureAlign.entries[i]) } },
            tagPrefix = LookTags.ALIGN,
        )
        CompositionLocalProvider(LocalLookPicture provides controller.pictureFile) { AvatarRow(controller, look) }
    }
}

/**
 * 調整頭像 (the owner: 「所有功能都要可調」): which part of the picture the small round or square spots show (every startup
 * look's portrait or identity window, and the community looks' avatar). The row shows the current avatar; it opens [AvatarDialog].
 */
@Composable
private fun AvatarRow(controller: LookController, look: Look) {
    var open by remember { mutableStateOf(false) }
    val picture = rememberLookPicture(look)
    val crop = rememberAvatarCrop(look)
    val label = stringResource(R.string.look_avatar)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClickLabel = label) { open = true }
            .testTag(LookTags.AVATAR),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(
                stringResource(if (look.avatar == null) R.string.look_avatar_now_auto else R.string.look_avatar_now_own),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        AvatarPreview(picture, crop, 44.dp, Modifier.testTag(LookTags.AVATAR_PREVIEW))
    }
    if (open) AvatarDialog(controller, look, picture) { open = false }
}

/**
 * The avatar editor: the picture with a round frame over it (drag to move it, pinch or 大小 to make it bigger or smaller, never
 * past the picture's edge), the avatar as it will look, 「自動」 back to the app's guess and 「完成」 to keep the frame.
 */
@Composable
private fun AvatarDialog(controller: LookController, look: Look, picture: ImageBitmap?, onClose: () -> Unit) {
    // Null follows 自動 (the guess); the frame becomes the user's own as soon as it is moved.
    val working = remember { mutableStateOf(look.avatar) }
    val guess = rememberAvatarCrop(look.copy(avatar = null))
    val crop = working.value ?: guess
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag(LookTags.AVATAR_DIALOG),
        title = { Text(stringResource(R.string.look_avatar)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.look_avatar_hint), style = MaterialTheme.typography.bodySmall)
                if (picture == null || crop == null) {
                    Text(stringResource(R.string.look_avatar_loading), color = Sakura.TextSecondary)
                } else {
                    AvatarFrame(picture, crop) { working.value = it }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AvatarPreview(picture, crop, 72.dp, Modifier.testTag(LookTags.AVATAR_LIVE))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.look_avatar_size))
                            Slider(
                                value = crop.fitted(picture.width, picture.height).size,
                                onValueChange = { s -> working.value = crop.copy(size = s).fitted(picture.width, picture.height) },
                                valueRange = AvatarCrop.MIN_SIZE..1f,
                                modifier = Modifier.testTag(LookTags.AVATAR_SIZE),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    working.value?.let { mine -> controller.update { it.copy(avatar = mine) } }
                    onClose()
                },
                modifier = Modifier.testTag(LookTags.AVATAR_DONE),
            ) { Text(stringResource(R.string.look_avatar_done)) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    working.value = null
                    controller.update { it.copy(avatar = null) }
                },
                modifier = Modifier.testTag(LookTags.AVATAR_AUTO),
            ) { Text(stringResource(R.string.look_avatar_auto)) }
        },
    )
}

/**
 * The picture, as large as fits (at most 300 dp high), under a soft white veil with a round hole: the avatar. A drag moves the
 * frame with the finger, a pinch makes it smaller (closer) or bigger; [onChange] gets a crop that stays inside the picture.
 */
@Composable
private fun AvatarFrame(picture: ImageBitmap, crop: AvatarCrop, onChange: (AvatarCrop) -> Unit) {
    val change by rememberUpdatedState(onChange)
    // The crop a gesture goes on from: several moves can come between two frames, so each one starts from the last one's result
    // (and from the slider's or 自動's, which arrive with the next composition).
    val last = remember { arrayOf(crop) }
    SideEffect { last[0] = crop }
    val description = stringResource(R.string.look_avatar_frame)
    // At most 300 dp high, and at most 40 % of the screen, so the whole editor fits on a small or turned phone.
    val tallest = min(300f, LocalConfiguration.current.screenHeightDp * .4f).dp
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val maxW = with(density) { maxWidth.toPx() }
        val maxH = with(density) { tallest.toPx() }
        val k = min(maxW / picture.width, maxH / picture.height)
        val dw = (picture.width * k).coerceAtLeast(1f)
        val dh = (picture.height * k).coerceAtLeast(1f)
        Canvas(
            Modifier
                .size(with(density) { dw.toDp() }, with(density) { dh.toDp() })
                .semantics { contentDescription = description }
                .testTag(LookTags.AVATAR_PICTURE)
                .pointerInput(picture, dw, dh) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val c = last[0]
                        val next = AvatarCrop(c.x + pan.x / dw, c.y + pan.y / dh, c.size / zoom.coerceAtLeast(.01f)).fitted(picture.width, picture.height)
                        last[0] = next
                        change(next)
                    }
                },
        ) {
            drawImage(picture, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium)
            val sq = avatarSquare(picture.width, picture.height, crop)
            val s = size.width / picture.width
            val r = sq.side * s / 2f
            val c = Offset((sq.left + sq.side / 2f) * s, (sq.top + sq.side / 2f) * s)
            val hole = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                addOval(Rect(c, r))
            }
            drawPath(hole, Sakura.White.copy(alpha = .6f))
            drawCircle(Sakura.White, r, c, style = Stroke(3.dp.toPx()))
            drawCircle(Sakura.Primary, r, c, style = Stroke(1.5.dp.toPx()))
        }
    }
}

/** The avatar ([crop] of [picture]) in a circle [size] across, with a white and pink rim; a blush disc while it is not ready. */
@Composable
private fun AvatarPreview(picture: ImageBitmap?, crop: AvatarCrop?, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size).clip(CircleShape)) {
        if (picture == null || crop == null) {
            drawRect(Sakura.Background)
        } else {
            val sq = avatarSquare(picture.width, picture.height, crop)
            drawImage(
                picture, IntOffset(sq.left, sq.top), IntSize(sq.side, sq.side),
                dstSize = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()), filterQuality = FilterQuality.Medium,
            )
        }
        drawCircle(Sakura.White, this.size.minDimension / 2f - 1.5.dp.toPx(), style = Stroke(3.dp.toPx()))
        drawCircle(Sakura.Primary, this.size.minDimension / 2f - .75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
    }
}

@Composable
private fun IntroSettings(controller: LookController, intro: Intro) {
    val set: ((Intro) -> Intro) -> Unit = { change -> controller.update { it.copy(intro = change(it.intro)) } }
    Text(stringResource(R.string.look_intro), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.look_intro_hint), style = MaterialTheme.typography.bodySmall)
    Toggle(R.string.look_intro_on, intro.enabled, "intro") { v -> set { it.copy(enabled = v) } }
    Field(R.string.look_intro_name, intro.name, "intro-name") { v -> set { it.copy(name = v) } }
    Field(R.string.look_intro_welcome, intro.welcome, "intro-welcome") { v -> set { it.copy(welcome = v) } }
    Field(R.string.look_intro_number, intro.number, "intro-number") { v -> set { it.copy(number = v) } }
    Field(R.string.look_intro_level, intro.level, "intro-level") { v -> set { it.copy(level = v) } }
    // 1.5 to 8 seconds in half-second steps.
    var seconds by remember(intro.seconds) { mutableFloatStateOf(intro.seconds) }
    Text(stringResource(R.string.look_intro_seconds, String.format(Locale.ROOT, "%.1f", seconds)))
    Slider(
        value = seconds,
        onValueChange = { seconds = Math.round(it * 2) / 2f },
        onValueChangeFinished = { set { it.copy(seconds = seconds) } },
        valueRange = Intro.MIN_SECONDS..Intro.MAX_SECONDS,
        steps = ((Intro.MAX_SECONDS - Intro.MIN_SECONDS) * 2).toInt() - 1,
        modifier = Modifier.testTag(LookTags.SLIDER + "intro-seconds"),
    )
    Toggle(R.string.look_intro_skip_on, intro.skippable, "intro-skip") { v -> set { it.copy(skippable = v) } }
    Toggle(R.string.look_intro_portrait, intro.portrait, "intro-portrait") { v -> set { it.copy(portrait = v) } }
    // 開場樣式: one row per look, its name and one plain line. Right above 預覽, so a pick can be tried at once.
    Text(stringResource(R.string.look_intro_style))
    Column(Modifier.selectableGroup()) {
        IntroStyle.entries.forEach { style ->
            val (name, hint) = styleWords(style)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = intro.style == style, role = Role.RadioButton) { set { it.copy(style = style) } }
                    .testTag(LookTags.INTRO_STYLE + style.id),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = intro.style == style, onClick = null)
                Column(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
                    Text(stringResource(name))
                    Text(stringResource(hint), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    OutlinedButton(onClick = controller::previewIntro, modifier = Modifier.testTag(LookTags.PREVIEW)) { Text(stringResource(R.string.look_intro_preview)) }
}

/** A look's name and its one plain line in the picker. */
private fun styleWords(style: IntroStyle): Pair<Int, Int> = when (style) {
    IntroStyle.SIGIL -> R.string.look_intro_style_sigil to R.string.look_intro_style_sigil_hint
    IntroStyle.STARDUST -> R.string.look_intro_style_stardust to R.string.look_intro_style_stardust_hint
    IntroStyle.PETAL_STORM -> R.string.look_intro_style_petal_storm to R.string.look_intro_style_petal_storm_hint
    IntroStyle.TUNNEL -> R.string.look_intro_style_tunnel to R.string.look_intro_style_tunnel_hint
    IntroStyle.ONE_STROKE -> R.string.look_intro_style_one_stroke to R.string.look_intro_style_one_stroke_hint
    IntroStyle.WEB_SAKURA -> R.string.look_intro_style_web_sakura to R.string.look_intro_style_web_sakura_hint
    IntroStyle.WEB_NEON -> R.string.look_intro_style_web_neon to R.string.look_intro_style_web_neon_hint
    IntroStyle.WEB_WAVE -> R.string.look_intro_style_web_wave to R.string.look_intro_style_web_wave_hint
    IntroStyle.WEB_ACCESS -> R.string.look_intro_style_web_access to R.string.look_intro_style_web_access_hint
}

@Composable
private fun Check(label: Int, on: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(on, onChange, modifier = Modifier.testTag(LookTags.CHECK + tag))
        Text(stringResource(label), Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun Toggle(label: Int, on: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange, modifier = Modifier.testTag(LookTags.SWITCH + tag))
    }
}

/** A 0..100 (or [min]..100) slider that shows its value while dragged and commits it when let go. */
@Composable
private fun Percent(label: Int, value: Int, tag: String, min: Int = 0, onCommit: (Int) -> Unit) {
    var shown by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Text(stringResource(label, shown.toInt()))
    Slider(
        value = shown,
        onValueChange = { shown = it.coerceIn(min.toFloat(), 100f) },
        onValueChangeFinished = { onCommit(shown.toInt()) },
        valueRange = min.toFloat()..100f,
        modifier = Modifier.testTag(LookTags.SLIDER + tag),
    )
}

@Composable
private fun Field(label: Int, value: String, tag: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { onChange(it.take(Intro.MAX_TEXT)) }, singleLine = true,
        label = { Text(stringResource(label)) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth().testTag(LookTags.FIELD + tag),
    )
}

object LookTags {
    const val STYLE = "look-style-"
    const val ALIGN = "look-align-"
    const val CHECK = "look-check-"
    const val SWITCH = "look-switch-"
    const val SLIDER = "look-slider-"
    const val FIELD = "look-field-"
    const val PICK = "look-pick"
    const val REMOVE = "look-remove"
    const val RESTORE = "look-restore"
    const val PREVIEW = "look-preview"
    /** One per look: look-intro-style-sigil, -stardust, -petal-storm, -tunnel, -one-stroke. */
    const val INTRO_STYLE = "look-intro-style-"
    /** 調整頭像: the row (only with a picture), its small preview, and the editor's parts. */
    const val AVATAR = "look-avatar"
    const val AVATAR_PREVIEW = "look-avatar-preview"
    const val AVATAR_DIALOG = "look-avatar-dialog"
    const val AVATAR_PICTURE = "look-avatar-picture"
    const val AVATAR_LIVE = "look-avatar-live"
    const val AVATAR_SIZE = "look-avatar-size"
    const val AVATAR_AUTO = "look-avatar-auto"
    const val AVATAR_DONE = "look-avatar-done"
}
