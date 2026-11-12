package app.dshmobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.dshmobile.chat.InlineCard
import app.dshmobile.ui.fog.FogFonts
import app.dshmobile.ui.overview.WeatherCard
import app.dshmobile.ui.theme.Sakura

object CardTags {
    const val CARD = "inline-card-"
    const val HEADLINE = "inline-card-headline"
}

private val CardShape = RoundedCornerShape(18.dp)

/**
 * One card of a reply ([InlineCard]), in the six colours. Weather is the app's own analysis card; the others show a headline
 * (the amount, the price, what to be reminded of) over their remaining fields. Rises are pink and falls aqua (Taiwan's red-up
 * convention, in the palette).
 */
@Composable
internal fun InlineCardView(card: InlineCard, modifier: Modifier = Modifier) {
    if (card.type == InlineCard.Type.WEATHER) {
        val app = LocalContext.current.applicationContext as? app.dshmobile.DshMobileApp ?: return
        Column(modifier.fillMaxWidth().padding(horizontal = 10.dp).testTag(CardTags.CARD + card.type.id)) { WeatherCard(app.weather) }
        return
    }
    val spec = specOf(card)
    val said = (listOf(spec.title, spec.headline) + spec.rest.map { "${it.first} ${it.second}" }).joinToString("，")
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .clip(CardShape)
            .background(Color.White.copy(alpha = .78f))
            .border(1.dp, spec.accent.copy(alpha = .7f), CardShape)
            .semantics(mergeDescendants = true) { contentDescription = said }
            .testTag(CardTags.CARD + card.type.id)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(spec.title, color = Sakura.TextSecondary, fontSize = 11.sp, letterSpacing = 0.12.em, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                spec.headline, color = Sakura.Text, fontSize = if (spec.big) 26.sp else 17.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = if (spec.big) FogFonts.ChakraPetch else null, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).testTag(CardTags.HEADLINE),
            )
            spec.badge?.let { b ->
                Text(
                    b, color = if (spec.accent == Sakura.Aqua) Sakura.Text else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FogFonts.ChakraPetch, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(spec.accent).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        spec.rest.forEach { (name, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, color = Sakura.TextSecondary, fontSize = 12.sp)
                Text(value, color = Sakura.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

internal class CardSpec(val title: String, val headline: String, val badge: String?, val rest: List<Pair<String, String>>, val accent: Color, val big: Boolean)

/** What a card shows first: the field that matters for its type, else its first field. */
internal fun specOf(card: InlineCard): CardSpec {
    fun pick(vararg names: String) = names.firstNotNullOfOrNull { n -> card[n]?.let { n to it } }
    val (title, head, badge, accent) = when (card.type) {
        InlineCard.Type.LEDGER -> {
            val amount = pick("金額")
            val income = amount?.second?.trim()?.let { !it.startsWith("-") && !it.startsWith("−") } == true
            Quad("記帳", amount, pick("項目"), if (income) Sakura.Aqua else Sakura.Primary)
        }
        InlineCard.Type.QUOTE -> {
            val change = pick("漲跌幅") ?: pick("漲跌")
            val down = change?.second?.trim()?.let { it.startsWith("-") || it.startsWith("−") || it.startsWith("▼") } == true
            Quad(listOfNotNull(card["名稱"], card["代號"]).joinToString(" ").ifEmpty { "行情" }, pick("價格"), change, if (down) Sakura.Aqua else Sakura.Primary)
        }
        InlineCard.Type.REMINDER -> Quad("提醒", pick("內容"), pick("時間"), Sakura.RoseBrown)
        InlineCard.Type.WEATHER -> Quad("天氣", null, null, Sakura.Aqua)
    }
    val headline = head ?: card.fields.first()
    val used = setOfNotNull(headline.first, badge?.first) + if (card.type == InlineCard.Type.QUOTE) setOf("名稱", "代號") else emptySet()
    return CardSpec(title, headline.second, badge?.second, card.fields.filter { it.first !in used }, accent, big = card.type != InlineCard.Type.REMINDER)
}

private data class Quad(val title: String, val head: Pair<String, String>?, val badge: Pair<String, String>?, val accent: Color)
