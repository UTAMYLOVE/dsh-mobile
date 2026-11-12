package app.dshmobile.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import app.dshmobile.chat.InlineCard
import app.dshmobile.chat.SessionRow
import app.dshmobile.remote.MuxState
import app.dshmobile.ui.MainUiState
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.shell.LocalPagePadding
import app.dshmobile.ui.fog.effectsFor
import app.dshmobile.ui.shell.ComposerUi
import app.dshmobile.ui.shell.FogShell
import app.dshmobile.ui.shell.ShellNav
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The simple shell's home page and the cards of a reply: they work at the owner's large font, and a picture of each is kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatHomeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val rows = listOf(
        SessionRow("s1", "台股外資動向", null, null, null, 30, false),
        SessionRow("s2", "霧城 第三章", null, null, null, 20, true),
        SessionRow("s3", "11 月記帳", null, null, null, 10, false),
    )

    private fun shell(content: @androidx.compose.runtime.Composable () -> Unit) = rule.setContent {
        FogTheme(effectsFor(EffectLevel.SAVER, reducedMotion = true)) {
            FogShell(
                status = MainUiState.Ready, connection = MuxState.OPEN, nav = ShellNav(),
                composer = ComposerUi("", {}, enabled = true, canSend = false, running = false, onSend = {}, onStop = {}),
                onRetry = {}, onExportErrorLog = {},
            ) { Box(Modifier.fillMaxSize().padding(LocalPagePadding.current)) { content() } }
        }
    }

    private fun snap(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(bitmap)) }
        val dir = File(System.getProperty("dshm.shots") ?: return).apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun theHomePageSuggestsOpensAndLeadsToTheListAndSettings() {
        val picked = mutableListOf<String>()
        shell { ChatHome(rows, onSuggest = { picked += "suggest:$it" }, onOpen = { picked += "open:$it" }, onOpenList = { picked += "list" }, onSettings = { picked += "settings" }) }
        rule.onNodeWithTag(HomeTags.HOME).assertIsDisplayed()
        snap("home")
        rule.onNodeWithTag(HomeTags.SUGGEST + 1).performScrollTo().performClick()
        rule.onNodeWithTag(HomeTags.RECENT + "s2").performScrollTo().performClick()
        rule.onNodeWithTag(HomeTags.LIST).performScrollTo().performClick()
        rule.onNodeWithTag(HomeTags.SETTINGS).performScrollTo().performClick()
        assertEquals(listOf("suggest:午餐花了 120", "open:s2", "list", "settings"), picked)
    }

    @Config(sdk = [36], qualifiers = "w360dp-h740dp-port", fontScale = 1.3f)
    @Test fun atALargeFontEveryControlOfTheHomePageCanStillBeTapped() {
        val picked = mutableListOf<String>()
        shell { ChatHome(rows, onSuggest = { picked += it }, onOpen = { picked += it }, onOpenList = { picked += "list" }, onSettings = { picked += "settings" }) }
        snap("home-large-font")
        rule.onNodeWithTag(HomeTags.LIST).performScrollTo().performClick()
        rule.onNodeWithTag(HomeTags.SETTINGS).performScrollTo().performClick()
        rule.onNodeWithTag(HomeTags.SUGGEST + 3).performScrollTo().performClick()
        rule.onNodeWithTag(HomeTags.RECENT + "s3").performScrollTo().performClick()
        assertEquals(listOf("list", "settings", "幫我想一個故事開頭", "s3"), picked)
    }

    @Test fun cardsShowTheirHeadlineAndReadAsOneSentence() {
        val cards = listOf(
            InlineCard(InlineCard.Type.QUOTE, listOf("名稱" to "台積電", "代號" to "2330", "價格" to "1,085", "漲跌幅" to "+1.4%", "時間" to "11/12 13:30 收盤", "來源" to "證交所")),
            InlineCard(InlineCard.Type.QUOTE, listOf("名稱" to "聯發科", "代號" to "2454", "價格" to "1,420", "漲跌幅" to "-0.8%")),
            InlineCard(InlineCard.Type.LEDGER, listOf("項目" to "午餐", "金額" to "-120", "類別" to "餐飲", "本月支出" to "-5,480")),
            InlineCard(InlineCard.Type.REMINDER, listOf("內容" to "繳電話費", "時間" to "明天 09:00")),
        )
        shell { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) { cards.forEach { InlineCardView(it) } } }
        snap("cards")
        val specs = cards.map(::specOf)
        assertEquals(listOf("1,085", "1,420", "-120", "繳電話費"), specs.map { it.headline })
        assertEquals(listOf("+1.4%", "-0.8%", "午餐", "明天 09:00"), specs.map { it.badge })
        assertEquals("台積電 2330", specs[0].title)
        assertEquals(listOf("時間" to "11/12 13:30 收盤", "來源" to "證交所"), specs[0].rest)
        assertEquals(listOf("類別" to "餐飲", "本月支出" to "-5,480"), specs[2].rest)
    }
}
