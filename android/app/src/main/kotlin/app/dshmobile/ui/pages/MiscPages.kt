package app.dshmobile.ui.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.ui.EvalUi
import app.dshmobile.ui.MediaUi
import app.dshmobile.ui.MemoUi
import app.dshmobile.ui.SingingUi
import app.dshmobile.ui.SongResult
import app.dshmobile.ui.TraineeUi
import app.dshmobile.ui.rememberIoRunner
import app.dshmobile.ui.rememberLoaded
import app.dshmobile.ui.theme.Block
import app.dshmobile.ui.theme.ChipPill
import app.dshmobile.ui.theme.Glyph
import app.dshmobile.ui.theme.GlyphIcon
import app.dshmobile.ui.theme.InkField
import app.dshmobile.ui.theme.KText
import app.dshmobile.ui.theme.Pal
import app.dshmobile.ui.theme.PageFrame
import app.dshmobile.ui.theme.PillButton
import app.dshmobile.ui.theme.ScoreDot
import app.dshmobile.ui.theme.Type
import app.dshmobile.ui.theme.XiaokuiAvatar
import app.dshmobile.ui.theme.sticker
import app.dshmobile.ui.theme.tap
import kotlinx.coroutines.launch

/** The memo list: tick, delete, add. */
@Composable
fun MemoPage(memo: MemoUi, onBack: () -> Unit) {
    val list = rememberLoaded { memo.list() }
    val run = rememberIoRunner()
    var text by remember { mutableStateOf("") }
    PageFrame(stringResource(R.string.memo_title), onBack) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { InkField(text, { text = it }, stringResource(R.string.memo_hint), tag = "memo_field") }
            PillButton(stringResource(R.string.memo_add), {
                val t = text.trim()
                if (t.isNotEmpty()) { run { memo.add(t, null) }; text = ""; list.reload() }
            }, Modifier.testTag("memo_add"), enabled = text.isNotBlank(), size = Type.Small)
        }
        val rows = list.value
        if (rows != null && rows.isEmpty()) KText(stringResource(R.string.memo_empty), alpha = 0.7f)
        rows?.forEach { r ->
            Row(
                Modifier.fillMaxWidth().testTag("memo_${r.id}").sticker(Pal.White, RoundedCornerShape(16.dp), 2.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.size(26.dp).sticker(if (r.done) Pal.Mint else Pal.Paper, CircleShape, shadow = 0.dp).tap({ run { memo.setDone(r.id, !r.done) }; list.reload() }).testTag("memo_tick_${r.id}"),
                    contentAlignment = Alignment.Center,
                ) { if (r.done) GlyphIcon(Glyph.Check, size = 15.dp) }
                Column(Modifier.weight(1f)) {
                    KText(r.text, alpha = if (r.done) 0.5f else 1f)
                    r.due?.let { KText(it, size = Type.Tiny, alpha = 0.65f) }
                }
                Box(Modifier.size(30.dp).tap({ run { memo.remove(r.id) }; list.reload() }).testTag("memo_del_${r.id}"), contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.Close, size = 16.dp, color = Pal.Ink.copy(alpha = 0.6f))
                }
            }
        }
    }
}

private fun parseColor(hex: String, fallback: Color): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(fallback)

/** The trainee card: an idol-profile card, the photocard wall and the sticker diary of perfect days. */
@Composable
fun TraineePage(trainee: TraineeUi, media: MediaUi, onBack: () -> Unit) {
    val card = rememberLoaded { trainee.card() }.value
    PageFrame(stringResource(R.string.trainee_title), onBack) {
        if (card == null) return@PageFrame
        val member = parseColor(card.colorHex, Pal.Lavender)
        Column(Modifier.fillMaxWidth().testTag("trainee_card").sticker(Pal.White, RoundedCornerShape(22.dp), 4.dp)) {
            Box(Modifier.fillMaxWidth().background(member, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    app.dshmobile.ui.theme.TraineeAvatar(76.dp, member)
                    Column(Modifier.weight(1f)) {
                        KText(stringResource(R.string.trainee_stage_name), size = Type.Tiny, alpha = 0.75f)
                        KText(card.stageName.ifBlank { stringResource(R.string.trainee_no_name) }, size = 28.sp, bold = true, modifier = Modifier.testTag("trainee_name"))
                        KText(card.stageLabel, size = Type.Small, alpha = 0.75f)
                    }
                    Box(Modifier.size(54.dp).sticker(Pal.Butter, CircleShape, shadow = 2.dp), contentAlignment = Alignment.Center) {
                        KText(card.level, size = 26.sp, bold = true, modifier = Modifier.testTag("trainee_level"))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(Pal.Ink))
            Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                KText(stringResource(R.string.trainee_days, card.daysSinceJoin), size = Type.Body, bold = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(16.dp).background(member, CircleShape).border(2.dp, Pal.Ink, CircleShape))
                    KText(stringResource(R.string.trainee_member_color), size = Type.Small, alpha = 0.75f)
                }
            }
        }
        KText(stringResource(R.string.trainee_wall), size = Type.Heading, bold = true)
        if (card.photocards.isEmpty()) KText(stringResource(R.string.trainee_wall_empty), size = Type.Small, alpha = 0.7f)
        card.photocards.chunked(3).forEach { rowCards ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowCards.forEachIndexed { i, pc ->
                    val fill = listOf(Pal.Pink, Pal.Lavender, Pal.Mint)[i % 3]
                    Column(Modifier.weight(1f).aspectRatio(0.72f).testTag("photocard").sticker(fill, RoundedCornerShape(14.dp), 3.dp).padding(8.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Box(Modifier.fillMaxWidth().weight(1f).background(Pal.White, RoundedCornerShape(8.dp)).border(1.5.dp, Pal.Ink, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                            Canvas(Modifier.size(34.dp)) { star(Pal.PinkStrong) }
                        }
                        KText(pc.title, size = 12.sp, bold = true, modifier = Modifier.padding(top = 4.dp))
                        KText(pc.date, size = 11.sp, alpha = 0.7f)
                    }
                }
                repeat(3 - rowCards.size) { Box(Modifier.weight(1f)) }
            }
        }
        KText(stringResource(R.string.trainee_diary), size = Type.Heading, bold = true)
        Block(Modifier.testTag("diary")) {
            card.diary.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    week.forEach { d ->
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).sticker(if (d.perfect) Pal.Butter else Pal.Paper, CircleShape, shadow = 0.dp, outline = if (d.perfect) 2.dp else 1.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (d.perfect) Canvas(Modifier.size(20.dp)) { star(Pal.PinkStrong) } else KText(d.label, size = 12.sp, alpha = 0.5f)
                        }
                    }
                    repeat(7 - week.size) { Box(Modifier.weight(1f)) }
                }
            }
            KText(stringResource(R.string.trainee_diary_note), size = Type.Tiny, alpha = 0.65f)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.star(fill: Color) {
    val c = Offset(size.width / 2, size.height / 2)
    val p = Path()
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) size.width / 2 else size.width / 4.4f
        val a = Math.toRadians(-90.0 + i * 36.0)
        val x = c.x + (r * Math.cos(a)).toFloat()
        val y = c.y + (r * Math.sin(a)).toFloat()
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    drawPath(p, fill)
    drawPath(p, Pal.Ink, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
}

/** The weekly and monthly evaluation: record the pieces, confirm the suggested scores. */
@Composable
fun EvalPage(eval: EvalUi, onBack: () -> Unit, onConfirmed: () -> Unit, startMonthly: Boolean = false) {
    var monthly by remember { mutableStateOf(startMonthly) }
    var pushUps by remember { mutableStateOf("") }
    var plank by remember { mutableStateOf("") }
    val view = rememberLoaded(monthly) { eval.view(monthly) }
    val scope = rememberCoroutineScope()
    val run = rememberIoRunner()
    val scores = remember(monthly, view.value) { mutableStateMapOf<String, Int>().also { m -> view.value?.items?.forEach { i -> i.suggested?.let { m[i.key] = it } } } }
    PageFrame(stringResource(R.string.eval_title), onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipPill(stringResource(R.string.eval_weekly), { monthly = false }, Modifier.testTag("eval_weekly"), if (!monthly) Pal.PinkStrong else Pal.Butter)
            ChipPill(stringResource(R.string.eval_monthly), { monthly = true }, Modifier.testTag("eval_monthly"), if (monthly) Pal.PinkStrong else Pal.Butter)
        }
        val v = view.value ?: return@PageFrame
        KText(v.title, size = Type.Heading, bold = true)
        v.items.forEach { item ->
            Block(Modifier.testTag("eval_${item.key}")) {
                KText(item.label, bold = true)
                if (!item.recorded) {
                    PillButton(stringResource(R.string.eval_record), {
                        scope.launch { if (eval.record(monthly, item.key)) view.reload() }
                    }, Modifier.testTag("eval_record_${item.key}"), Pal.Lavender, Type.Small)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (n in 1..5) ScoreDot(n, picked = scores[item.key] == n, onClick = { scores[item.key] = n })
                    }
                    if (item.reason.isNotBlank()) KText(item.reason, size = Type.Small, alpha = 0.75f)
                }
            }
        }
        if (monthly) {
            Block(Modifier.testTag("eval_fitness")) {
                KText(stringResource(R.string.eval_fit_title), bold = true)
                InkField(pushUps, { pushUps = it.filter(Char::isDigit).take(3); run { eval.setFitness(pushUps.toIntOrNull(), plank.toIntOrNull()) } }, stringResource(R.string.eval_fit_push), keyboard = androidx.compose.ui.text.input.KeyboardType.Number, tag = "eval_pushups")
                InkField(plank, { plank = it.filter(Char::isDigit).take(4); run { eval.setFitness(pushUps.toIntOrNull(), plank.toIntOrNull()) } }, stringResource(R.string.eval_fit_plank), keyboard = androidx.compose.ui.text.input.KeyboardType.Number, tag = "eval_plank")
            }
        }
        PillButton(stringResource(R.string.eval_confirm), {
            val snapshot = scores.toMap()
            run { eval.confirm(monthly, snapshot) }
            onConfirmed()
        }, Modifier.testTag("eval_confirm"), enabled = !v.confirmed && scores.isNotEmpty())
    }
}
