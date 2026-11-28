package app.dshmobile.ui.theme

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.dshmobile.R

/** A full-screen page on the paper: a back button, a title, then [content] in a scrolling column. */
@Composable
fun PageFrame(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, scroll: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize().paper().safeDrawingPadding().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val cd = stringResource(R.string.cd_back)
            Box(
                Modifier.size(40.dp).sticker(Pal.White, CircleShape, shadow = 2.dp).tap(onBack).semantics { contentDescription = cd }.testTag("back"),
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(Glyph.ChevronLeft, size = 22.dp) }
            Spacer(Modifier.width(12.dp))
            KText(title, size = Type.Title, bold = true, modifier = Modifier.testTag("page_title"))
        }
        val body = if (scroll) Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()) else Modifier.weight(1f).fillMaxWidth()
        Column(body, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
            Spacer(Modifier.size(16.dp))
        }
    }
}

/** A small sheet from the bottom over a dimmed home; tapping outside closes it. */
@Composable
fun SheetFrame(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Pal.Ink.copy(alpha = 0.35f)).tap(onClose, role = Role0).testTag("scrim")) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding().navigationBarsPadding()
                .sticker(Pal.Paper, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), shadow = 0.dp)
                .tap({}, role = Role0).padding(horizontal = 20.dp, vertical = 16.dp).testTag("sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            KText(title, size = Type.Heading, bold = true)
            content()
        }
    }
}

private val Role0 = androidx.compose.ui.semantics.Role.Button

/** A labelled pill text field. */
@Composable
fun InkField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    minLines: Int = 1,
    visual: VisualTransformation = VisualTransformation.None,
    tag: String = "",
) {
    val shape = if (singleLine) PillShape else RoundedCornerShape(18.dp)
    Box(modifier.fillMaxWidth().sticker(Pal.White, shape, shadow = 0.dp).padding(horizontal = 16.dp, vertical = 10.dp)) {
        BasicTextField(
            value = value, onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().let { if (tag.isNotEmpty()) it.testTag(tag) else it },
            textStyle = TextStyle(fontFamily = Kai, fontSize = Type.Body, color = Pal.Ink),
            cursorBrush = SolidColor(Pal.Ink), singleLine = singleLine, minLines = minLines,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard), visualTransformation = visual,
            decorationBox = { inner ->
                Box { if (value.isEmpty()) KText(hint, alpha = 0.45f); inner() }
            },
        )
    }
}

/** A plain white sticker block for page content. */
@Composable
fun Block(modifier: Modifier = Modifier, fill: androidx.compose.ui.graphics.Color = Pal.White, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().sticker(fill, Corner18, 3.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}
