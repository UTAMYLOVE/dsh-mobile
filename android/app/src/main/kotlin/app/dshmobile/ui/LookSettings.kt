package app.dshmobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dshmobile.R
import app.dshmobile.ui.fog.BackdropStyle
import app.dshmobile.ui.fog.Intro
import app.dshmobile.ui.fog.IntroStyle
import app.dshmobile.ui.fog.Look
import app.dshmobile.ui.fog.LookController
import app.dshmobile.ui.fog.LookStore
import app.dshmobile.ui.fog.PictureAlign
import app.dshmobile.ui.fog.Segmented
import app.dshmobile.ui.theme.Sakura
import kotlinx.coroutines.launch
import java.util.Locale

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
}
