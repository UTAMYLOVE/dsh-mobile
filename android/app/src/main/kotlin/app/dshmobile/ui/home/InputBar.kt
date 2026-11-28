package app.dshmobile.ui.home

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.agency.core.agent.Attachment
import app.dshmobile.R
import app.dshmobile.ui.theme.ChipPill
import app.dshmobile.ui.theme.Glyph
import app.dshmobile.ui.theme.GlyphIcon
import app.dshmobile.ui.theme.Kai
import app.dshmobile.ui.theme.KText
import app.dshmobile.ui.theme.Pal
import app.dshmobile.ui.theme.PillShape
import app.dshmobile.ui.theme.Type
import app.dshmobile.ui.theme.sticker
import app.dshmobile.ui.theme.tap

/** What the input row asks of its host. */
class InputActions(
    val onSend: () -> Unit,
    val onCamera: () -> Unit,
    val onGallery: () -> Unit,
    val onFile: () -> Unit,
    val onMic: () -> Unit,
    val onRemove: (Attachment) -> Unit,
    /** Image / file URIs pasted from the clipboard or inserted by a keyboard. */
    val onUris: (List<Uri>) -> Unit,
)

/**
 * The full chat input: attachment chips above, then 「＋」 (拍照 / 相簿 / 檔案), the text field (paste of images and files
 * is accepted), and the mic or the send button.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun InputBar(
    state: TextFieldState,
    attachments: List<Attachment>,
    micAvailable: Boolean,
    listening: Boolean,
    actions: InputActions,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Column(modifier) {
        if (attachments.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.forEachIndexed { i, a -> AttachmentChip(a, Modifier.testTag("attachment_$i")) { actions.onRemove(a) } }
            }
        }
        if (menu) {
            Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipPill(stringResource(R.string.plus_camera), { menu = false; actions.onCamera() }, Modifier.testTag("menu_camera"))
                ChipPill(stringResource(R.string.plus_gallery), { menu = false; actions.onGallery() }, Modifier.testTag("menu_gallery"))
                ChipPill(stringResource(R.string.plus_file), { menu = false; actions.onFile() }, Modifier.testTag("menu_file"))
            }
        }
        val desc = stringResource(R.string.cd_plus)
        Row(
            Modifier.fillMaxWidth().sticker(Pal.White, PillShape, shadow = 0.dp).padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(32.dp).sticker(if (menu) Pal.Butter else Pal.Lavender, CircleShape, shadow = 0.dp, outline = 1.5.dp)
                    .tap({ menu = !menu }).semantics { contentDescription = desc }.testTag("plus"),
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(Glyph.Plus, size = 16.dp) }
            BasicTextField(
                state = state,
                modifier = Modifier.weight(1f).padding(vertical = 4.dp).testTag("input").contentReceiver(object : ReceiveContentListener {
                    override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
                        val clip = transferableContent.clipEntry.clipData
                        val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                        if (uris.isEmpty()) return transferableContent
                        actions.onUris(uris)
                        return null
                    }
                }),
                textStyle = TextStyle(fontFamily = Kai, fontSize = Type.Body, color = Pal.Ink),
                cursorBrush = SolidColor(Pal.Ink),
                lineLimits = TextFieldLineLimits.MultiLine(1, 4),
                decorator = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (state.text.isEmpty()) KText(stringResource(R.string.home_input_hint), size = Type.Body, alpha = 0.5f)
                        inner()
                    }
                },
            )
            val canSend = state.text.isNotBlank() || attachments.isNotEmpty()
            if (canSend) {
                val d = stringResource(R.string.cd_send)
                Box(
                    Modifier.size(34.dp).sticker(Pal.PinkStrong, CircleShape, shadow = 0.dp, outline = 1.5.dp)
                        .tap(actions.onSend).semantics { contentDescription = d }.testTag("send"),
                    contentAlignment = Alignment.Center,
                ) { GlyphIcon(Glyph.Send, size = 17.dp) }
            } else if (micAvailable) {
                val d = stringResource(R.string.cd_mic)
                Box(
                    Modifier.size(34.dp).sticker(if (listening) Pal.PinkStrong else Pal.Mint, CircleShape, shadow = 0.dp, outline = 1.5.dp)
                        .tap(actions.onMic).semantics { contentDescription = d }.testTag("mic"),
                    contentAlignment = Alignment.Center,
                ) { GlyphIcon(Glyph.Mic, size = 18.dp) }
            }
        }
    }
}

@Composable
private fun AttachmentChip(a: Attachment, modifier: Modifier, onRemove: () -> Unit) {
    val name = when (a) {
        is Attachment.Photo -> stringResource(R.string.attach_photo)
        is Attachment.File -> a.name
    }
    val remove = stringResource(R.string.cd_remove)
    Row(
        modifier.sticker(Pal.Lavender, PillShape, shadow = 2.dp).padding(start = 12.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        KText(name, size = Type.Small, maxLines = 1)
        Box(
            Modifier.size(22.dp).background(Pal.White, CircleShape).tap(onRemove).semantics { contentDescription = remove }.testTag("remove_attachment"),
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(Glyph.Close, size = 12.dp) }
    }
}
