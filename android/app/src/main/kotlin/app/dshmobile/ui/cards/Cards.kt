package app.dshmobile.ui.cards

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agency.core.agent.Card
import app.dshmobile.R
import app.dshmobile.ui.MediaUi
import app.dshmobile.ui.theme.Corner14
import app.dshmobile.ui.theme.Corner18
import app.dshmobile.ui.theme.KText
import app.dshmobile.ui.theme.Pal
import app.dshmobile.ui.theme.PillButton
import app.dshmobile.ui.theme.PillShape
import app.dshmobile.ui.theme.ScoreDot
import app.dshmobile.ui.theme.Type
import app.dshmobile.ui.theme.sticker
import app.dshmobile.ui.theme.tap

/** What a tap on a card can do; the home screen supplies it. */
interface CardActions {
    /** A score dot, a proposal's yes/no, a video approved or declined: [answer] is "4", "yes", "no", "approve:7", "decline:7". */
    fun answer(messageId: Long, cardIndex: Int, answer: String)
    fun open(screen: String, arg: String)
    fun openUrl(url: String)
    fun watch(lessonId: Long)
}

/** Formats 40.0 as 40 and 22.5 as 22.5. */
fun fmtNumber(v: Double): String = if (v == Math.floor(v)) v.toInt().toString() else "%.1f".format(v)

/** Five dots, 1..5. Used inside a bubble or alone. */
@Composable
fun ScoreRow(card: Card.Score, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.testTag("score"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (n in 1..5) ScoreDot(n, picked = card.picked == n, onClick = { onPick(n) })
    }
}

@Composable
fun CardView(
    card: Card,
    messageId: Long,
    index: Int,
    actions: CardActions,
    media: MediaUi?,
    modifier: Modifier = Modifier,
) {
    when (card) {
        is Card.Score -> Box(modifier.sticker(Pal.White, Corner18, 3.dp).padding(12.dp)) {
            ScoreRow(card, { actions.answer(messageId, index, it.toString()) })
        }
        is Card.MealCard -> MealCardView(card, modifier)
        is Card.Proposal -> ProposalView(card, modifier) { actions.answer(messageId, index, it) }
        is Card.Videos -> VideosView(card, modifier, actions, messageId, index)
        is Card.Places -> PlacesView(card, modifier, actions)
        is Card.Open -> PillButton(card.label, { actions.open(card.screen, card.arg) }, modifier.testTag("card_open"), fill = Pal.PinkStrong)
        is Card.Summary -> SummaryView(card, modifier)
        is Card.Ceremony -> CeremonyView(card, modifier)
        is Card.Photo -> PhotoView(card, media, modifier)
        is Card.File -> Row(
            modifier.testTag("card_file").sticker(Pal.White, PillShape, 2.dp).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(width = 14.dp, height = 18.dp).background(Pal.Lavender, RoundedCornerShape(3.dp)).border(1.5.dp, Pal.Ink, RoundedCornerShape(3.dp)))
            KText(card.name, size = Type.Small, bold = true)
            KText(stringResource(R.string.card_file_chars, card.chars), size = Type.Tiny, alpha = 0.7f)
        }
    }
}

@Composable
private fun CardFrame(header: String, headerFill: Color, modifier: Modifier, tag: String, content: @Composable () -> Unit) {
    Column(modifier.testTag(tag).sticker(Pal.White, Corner18, 3.dp).clip(Corner18)) {
        Box(Modifier.fillMaxWidth().background(headerFill).padding(horizontal = 14.dp, vertical = 6.dp)) {
            KText(header, size = 13.sp)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(Pal.Ink))
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) { content() }
    }
}

@Composable
fun MealCardView(card: Card.MealCard, modifier: Modifier = Modifier) {
    CardFrame(card.title, Pal.Mint, modifier, "card_meal") {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KText(stringResource(R.string.card_kcal, card.kcal), size = Type.Heading, bold = true)
            KText(stringResource(R.string.card_protein, fmtNumber(card.proteinG)), size = Type.Small, alpha = 0.85f)
        }
        val frac = if (card.dayTargetKcal > 0) (card.dayKcal.toFloat() / card.dayTargetKcal).coerceIn(0f, 1f) else 0f
        Box(Modifier.padding(top = 6.dp, bottom = 4.dp).fillMaxWidth().height(12.dp).clip(PillShape).background(Pal.Paper).border(2.dp, Pal.Ink, PillShape)) {
            Box(Modifier.fillMaxWidth(frac).height(12.dp).background(Pal.PinkStrong))
        }
        KText(stringResource(R.string.card_today_total, card.dayKcal, card.dayTargetKcal), size = Type.Small)
        KText(stringResource(R.string.card_today_protein, fmtNumber(card.dayProteinG), card.dayTargetProteinG), size = Type.Tiny, alpha = 0.75f)
        if (card.estimated || card.needsPhoto) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (card.estimated) Mark(stringResource(R.string.card_estimate), Pal.Butter)
                if (card.needsPhoto) Mark(stringResource(R.string.card_no_photo), Pal.Pink)
            }
        }
        for (w in card.warnings) KText(w, Modifier.padding(top = 4.dp), size = Type.Small, color = Pal.Warn, bold = true)
    }
}

@Composable
private fun Mark(text: String, fill: Color) {
    Box(Modifier.sticker(fill, PillShape, shadow = 0.dp, outline = 1.5.dp).padding(horizontal = 9.dp, vertical = 2.dp)) {
        KText(text, size = Type.Tiny, bold = true)
    }
}

@Composable
fun ProposalView(card: Card.Proposal, modifier: Modifier, onAnswer: (String) -> Unit) {
    CardFrame(stringResource(R.string.card_proposal), Pal.Lavender, modifier, "card_proposal") {
        KText(card.title, size = 17.sp, bold = true)
        if (card.body.isNotBlank()) KText(card.body, Modifier.padding(top = 2.dp), size = Type.Small)
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            when (card.answer) {
                Card.Answer.OPEN -> {
                    PillButton(stringResource(R.string.card_yes), { onAnswer("yes") }, Modifier.testTag("proposal_yes"), fill = Pal.Mint)
                    PillButton(stringResource(R.string.card_no), { onAnswer("no") }, Modifier.testTag("proposal_no"), fill = Pal.Paper)
                }
                Card.Answer.YES -> Mark(stringResource(R.string.card_yes_done), Pal.Mint)
                Card.Answer.NO -> Mark(stringResource(R.string.card_no_done), Pal.Paper)
            }
        }
    }
}

@Composable
fun VideosView(card: Card.Videos, modifier: Modifier, actions: CardActions, messageId: Long, index: Int) {
    CardFrame(stringResource(R.string.card_videos), Pal.Butter, modifier, "card_videos") {
        card.items.forEachIndexed { i, v ->
            val approved = v.lessonId in card.approved
            val declined = v.lessonId in card.declined
            Column(Modifier.padding(top = if (i == 0) 0.dp else 10.dp).fillMaxWidth()) {
                KText(v.title, size = 16.sp, bold = true, alpha = if (declined) 0.45f else 1f)
                val meta = buildString {
                    append(v.subject)
                    v.minutes?.let { append(" · "); append(stringResource(R.string.card_minutes, it)) }
                }
                KText(meta, size = Type.Tiny, alpha = 0.7f)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        approved -> {
                            Mark(stringResource(R.string.card_added), Pal.Mint)
                            ChipLink(stringResource(R.string.card_watch)) { actions.watch(v.lessonId) }
                        }
                        declined -> Mark(stringResource(R.string.card_declined), Pal.Paper)
                        else -> {
                            ChipLink(stringResource(R.string.card_add), Pal.Mint, Modifier.testTag("video_add_${v.lessonId}")) { actions.answer(messageId, index, "approve:${v.lessonId}") }
                            ChipLink(stringResource(R.string.card_no), Pal.Paper, Modifier.testTag("video_no_${v.lessonId}")) { actions.answer(messageId, index, "decline:${v.lessonId}") }
                            ChipLink(stringResource(R.string.card_watch), Pal.Lavender, Modifier.testTag("video_watch_${v.lessonId}")) { actions.openUrl(v.url) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChipLink(text: String, fill: Color = Pal.Lavender, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.sticker(fill, PillShape, shadow = 2.dp).tap(onClick).padding(horizontal = 12.dp, vertical = 5.dp)) {
        KText(text, size = Type.Small, bold = true)
    }
}

@Composable
fun PlacesView(card: Card.Places, modifier: Modifier, actions: CardActions) {
    CardFrame(stringResource(R.string.card_places), Pal.Pink, modifier, "card_places") {
        card.items.forEachIndexed { i, p ->
            Column(Modifier.padding(top = if (i == 0) 0.dp else 10.dp).fillMaxWidth()) {
                KText(p.name, size = 16.sp, bold = true)
                if (p.summary.isNotBlank()) KText(p.summary, size = Type.Small, alpha = 0.8f)
                ChipLink(stringResource(R.string.card_watch), Pal.Lavender, Modifier.padding(top = 6.dp).testTag("place_open_$i")) { actions.openUrl(p.url) }
            }
        }
    }
}

@Composable
fun SummaryView(card: Card.Summary, modifier: Modifier) {
    CardFrame(card.title, Pal.Mint, modifier, "card_summary") {
        card.lines.forEach { KText("· $it", size = Type.Small) }
    }
}

@Composable
fun CeremonyView(card: Card.Ceremony, modifier: Modifier) {
    Box(modifier.fillMaxWidth().testTag("card_ceremony").sticker(Pal.Butter, RoundedCornerShape(22.dp), 4.dp)) {
        Canvas(Modifier.matchParentSize()) {
            // confetti-like stickers in the corners
            val cols = listOf(Pal.PinkStrong, Pal.Lavender, Pal.Mint, Pal.White)
            val spots = listOf(0.06f to 0.2f, 0.93f to 0.15f, 0.12f to 0.85f, 0.88f to 0.8f, 0.5f to 0.07f, 0.97f to 0.5f, 0.03f to 0.5f)
            spots.forEachIndexed { i, (x, y) ->
                drawCircle(Pal.Ink, 7.dp.toPx(), androidx.compose.ui.geometry.Offset(size.width * x, size.height * y))
                drawCircle(cols[i % cols.size], 5.2.dp.toPx(), androidx.compose.ui.geometry.Offset(size.width * x, size.height * y))
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            KText(card.title, size = Type.Heading, bold = true, align = TextAlign.Center)
            KText(card.text, Modifier.padding(top = 4.dp), size = Type.Small, align = TextAlign.Center)
        }
    }
}


@Composable
fun PhotoView(card: Card.Photo, media: MediaUi?, modifier: Modifier) {
    var bmp by remember(card.photoId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(card.photoId) { bmp = media?.thumbnail(card.photoId) }
    Column(modifier.testTag("card_photo").sticker(Pal.White, Corner14, 3.dp).padding(6.dp)) {
        Box(Modifier.size(150.dp, 112.dp).clip(RoundedCornerShape(10.dp)).background(Pal.Lavender), contentAlignment = Alignment.Center) {
            val b = bmp
            if (b != null) {
                Image(b, null, Modifier.fillMaxWidth().height(112.dp), contentScale = ContentScale.Crop)
            } else {
                Canvas(Modifier.size(150.dp, 112.dp)) {
                    drawCircle(Pal.Butter, 14.dp.toPx(), androidx.compose.ui.geometry.Offset(size.width * 0.72f, size.height * 0.3f))
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(0f, size.height); lineTo(size.width * 0.35f, size.height * 0.5f); lineTo(size.width * 0.6f, size.height * 0.8f)
                        lineTo(size.width * 0.8f, size.height * 0.6f); lineTo(size.width, size.height); close()
                    }
                    drawPath(p, Pal.Mint)
                }
            }
        }
        if (card.purpose.isNotBlank()) KText(card.purpose, Modifier.padding(top = 4.dp, start = 4.dp), size = Type.Tiny)
    }
}
