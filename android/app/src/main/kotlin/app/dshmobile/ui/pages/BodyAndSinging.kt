package app.dshmobile.ui.pages

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.ui.BodyShotRow
import app.dshmobile.ui.BodyUi
import app.dshmobile.ui.LocalIo
import app.dshmobile.ui.LyricsOutcome
import app.dshmobile.ui.MediaUi
import app.dshmobile.ui.SingingUi
import app.dshmobile.ui.SongRefRow
import app.dshmobile.ui.SongResult
import app.dshmobile.ui.rememberLoaded
import app.dshmobile.ui.theme.Block
import app.dshmobile.ui.theme.ChipPill
import app.dshmobile.ui.theme.InkField
import app.dshmobile.ui.theme.KText
import app.dshmobile.ui.theme.PageFrame
import app.dshmobile.ui.theme.Pal
import app.dshmobile.ui.theme.PillButton
import app.dshmobile.ui.theme.Type
import app.dshmobile.ui.theme.sticker
import app.dshmobile.ui.theme.tap
import app.dshmobile.ui.theme.Glyph
import app.dshmobile.ui.theme.GlyphIcon
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A vault photo, loaded off the main thread. */
@Composable
private fun VaultPhoto(media: MediaUi, photoId: String, modifier: Modifier) {
    val bmp by produceState<ImageBitmap?>(null, photoId) { value = media.thumbnail(photoId) }
    Box(modifier.clip(RoundedCornerShape(12.dp)).sticker(Pal.Lavender, RoundedCornerShape(12.dp), 0.dp, 1.5.dp), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it, null, Modifier.fillMaxWidth().aspectRatio(0.75f), contentScale = ContentScale.Crop) }
    }
}

/** 全身照: the same-angle photos in date order, the first beside the latest. The photos stay on the phone. */
@Composable
fun BodyPhotosPage(body: BodyUi, media: MediaUi, onBack: () -> Unit, onTake: () -> Unit) {
    val shots = rememberLoaded { body.timeline() }
    PageFrame(stringResource(R.string.bodyphotos_title), onBack) {
        val list: List<BodyShotRow> = shots.value ?: return@PageFrame
        if (list.isEmpty()) {
            KText(stringResource(R.string.bodyphotos_empty), alpha = 0.75f, modifier = Modifier.testTag("bodyphotos_empty"))
        }
        if (list.size >= 2) {
            KText(stringResource(R.string.bodyphotos_compare), size = Type.Heading, bold = true)
            Row(Modifier.fillMaxWidth().testTag("bodyphotos_compare"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(list.first() to R.string.bodyphotos_first, list.last() to R.string.bodyphotos_latest).forEach { (s, label) ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        VaultPhoto(media, s.photoId, Modifier.fillMaxWidth())
                        KText("${stringResource(label)}　${s.date}", size = Type.Small, bold = true)
                        if (s.note.isNotBlank()) KText(s.note, size = Type.Tiny, alpha = 0.7f)
                    }
                }
            }
        }
        if (list.isNotEmpty()) {
            KText(stringResource(R.string.bodyphotos_all), size = Type.Heading, bold = true)
            list.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { s ->
                        Column(Modifier.weight(1f).testTag("bodyphoto_${s.photoId}")) {
                            VaultPhoto(media, s.photoId, Modifier.fillMaxWidth())
                            KText(s.date, size = Type.Tiny, alpha = 0.8f)
                        }
                    }
                    repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
        PillButton(stringResource(R.string.bodyphotos_take), onTake, Modifier.testTag("bodyphotos_take"), Pal.Lavender, Type.Small)
    }
}

/** The pitch-curve picture; decoded off the main thread. */
@Composable
private fun ChartImage(png: ByteArray) {
    val bmp by produceState<ImageBitmap?>(null, png) {
        value = withContext(Dispatchers.Default) { runCatching { BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap() }.getOrNull() }
    }
    bmp?.let { Image(it, stringResource(R.string.sing_chart), Modifier.fillMaxWidth().aspectRatio(2f).testTag("singing_chart"), contentScale = ContentScale.FillWidth) }
}

/**
 * Singing: a reference to sing along with, how strict to be, record, stop, the numbers code computed, the pitch-curve picture,
 * and (only with a tick each time) the lyric check. Nothing is sent anywhere without that tick.
 */
@Composable
fun SingingPage(singing: SingingUi, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val io = LocalIo.current
    var recording by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SongResult?>(null) }
    var empty by remember { mutableStateOf(false) }
    val refs = rememberLoaded { singing.references() }
    val canLyrics = rememberLoaded { singing.lyricsAvailable() }
    var chosen by remember { mutableStateOf<String?>(null) }
    var importMsg by remember { mutableStateOf<String?>(null) }
    var strict by remember { mutableStateOf(true) }
    var lyrics by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    var lyricOut by remember { mutableStateOf<LyricsOutcome?>(null) }
    var checking by remember { mutableStateOf(false) }
    PageFrame(stringResource(R.string.singing_title), onBack) {
        KText(stringResource(R.string.singing_hint), size = Type.Small, alpha = 0.75f)
        Block(Modifier.testTag("singing_refs")) {
            KText(stringResource(R.string.sing_ref_title), bold = true)
            KText(stringResource(R.string.sing_ref_hint), size = Type.Small, alpha = 0.8f)
            val list: List<SongRefRow> = refs.value.orEmpty()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChipPill(stringResource(R.string.sing_ref_none), { chosen = null; singing.chooseReference(null) }, Modifier.testTag("ref_none"), if (chosen == null) Pal.PinkStrong else Pal.Butter)
            }
            list.forEach { r ->
                ChipPill(r.title + "（" + r.source + "）", { chosen = r.id; singing.chooseReference(r.id) }, Modifier.testTag("ref_${r.id}"), if (chosen == r.id) Pal.PinkStrong else Pal.Butter)
            }
            PillButton(stringResource(R.string.sing_ref_import), {
                scope.launch {
                    val r = singing.importReference()
                    importMsg = r.failure ?: r.title?.let { "ok:$it" }
                    if (r.title != null) refs.reload()
                }
            }, Modifier.testTag("ref_import"), Pal.Lavender, Type.Small)
            importMsg?.let { m ->
                if (m.startsWith("ok:")) KText(stringResource(R.string.sing_ref_ok, m.removePrefix("ok:")), size = Type.Small, modifier = Modifier.testTag("ref_msg"))
                else KText(m, size = Type.Small, color = Pal.Warn, modifier = Modifier.testTag("ref_msg"))
            }
        }
        Block {
            KText(stringResource(R.string.sing_strict_title), bold = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipPill(stringResource(R.string.sing_normal), { strict = false; singing.setStrict(false) }, Modifier.testTag("strict_normal"), if (!strict) Pal.PinkStrong else Pal.Butter)
                ChipPill(stringResource(R.string.sing_strict), { strict = true; singing.setStrict(true) }, Modifier.testTag("strict_strict"), if (strict) Pal.PinkStrong else Pal.Butter)
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PillButton(
                stringResource(if (recording) R.string.singing_stop else R.string.singing_start),
                {
                    scope.launch {
                        if (!recording) {
                            denied = !singing.start(); recording = !denied; result = null; empty = false; lyricOut = null; consent = false
                        } else {
                            recording = false
                            val r = singing.stop()
                            result = r; empty = r == null
                        }
                    }
                },
                Modifier.testTag("singing_toggle"), if (recording) Pal.Butter else Pal.PinkStrong, size = 20.sp,
            )
        }
        if (recording) KText(stringResource(R.string.singing_recording), align = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("singing_recording"))
        if (denied) KText(stringResource(R.string.singing_denied), color = Pal.Warn)
        if (empty) KText(stringResource(R.string.singing_empty), color = Pal.Warn)
        result?.let { r ->
            Block(Modifier.testTag("singing_result"), fill = Pal.Mint) {
                KText(stringResource(R.string.singing_in_tune, r.inTunePct), bold = true)
                KText(stringResource(R.string.singing_range, r.range))
                KText(stringResource(R.string.singing_phrase, r.longestPhraseSec))
                KText(stringResource(R.string.singing_loud, r.loudness))
                if (r.shift.isNotBlank()) KText(r.shift, bold = true, modifier = Modifier.testTag("singing_shift"))
                r.compare.forEach { KText(it, size = Type.Small) }
                if (r.comment.isNotBlank() && r.compare.isEmpty()) KText(r.comment, size = Type.Small, alpha = 0.85f)
            }
            r.chart?.let { png ->
                Block(Modifier.testTag("singing_chart_block")) {
                    KText(stringResource(R.string.sing_chart), bold = true)
                    ChartImage(png)
                }
            }
            if (canLyrics.value == true) {
                Block(Modifier.testTag("singing_lyrics")) {
                    KText(stringResource(R.string.sing_lyrics_title), bold = true)
                    InkField(lyrics, { lyrics = it }, stringResource(R.string.sing_lyrics_hint), singleLine = false, minLines = 3, tag = "lyrics_field")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            Modifier.testTag("lyrics_consent").sticker(if (consent) Pal.Mint else Pal.Paper, RoundedCornerShape(6.dp), 0.dp, 2.dp)
                                .tap({ consent = !consent }).padding(2.dp),
                            contentAlignment = Alignment.Center,
                        ) { Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { if (consent) GlyphIcon(Glyph.Check, size = 14.dp) } }
                        KText(stringResource(R.string.sing_lyrics_consent), Modifier.weight(1f))
                    }
                    PillButton(stringResource(R.string.sing_lyrics_go), {
                        val ok = consent
                        scope.launch {
                            checking = true
                            lyricOut = withContext(io) { singing.checkLyrics(lyrics, ok) }
                            // the tick is for this one time only
                            consent = false
                            checking = false
                        }
                    }, Modifier.testTag("lyrics_go"), Pal.Lavender, Type.Small, enabled = consent && lyrics.isNotBlank() && !checking)
                    lyricOut?.let { o ->
                        o.failure?.let { KText(it, size = Type.Small, color = Pal.Warn, modifier = Modifier.testTag("lyrics_failure")) }
                        o.lines.forEach { KText(it, size = Type.Small, modifier = Modifier.testTag("lyrics_line")) }
                    }
                }
            } else if (canLyrics.value == false) {
                KText(stringResource(R.string.sing_lyrics_need_key), size = Type.Small, alpha = 0.7f)
            }
        }
    }
}
