package app.dshmobile.ui.shell

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.effectsFor
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port")
class ShareSheetTest {
    @get:Rule val rule = createComposeRule()

    @Test fun sharedTextBecomesADraft() {
        val c = SharedContent.from(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "外資今天大賣台積電 300 億"))!!
        assertEquals("外資今天大賣台積電 300 億", c.message(""))
        assertEquals("是不是要跌？\n\n「外資今天大賣台積電 300 億」", c.message("是不是要跌？"))
        assertNull(SharedContent.from(Intent(Intent.ACTION_VIEW)))
        assertNull(SharedContent.from(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "  ")))
    }

    @Test fun sharedImageBecomesAnAttachment() {
        val img = Uri.parse("content://x/1.jpg")
        val c = SharedContent.from(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, img))!!
        assertEquals(listOf(img), c.uris)
        val evil = SharedContent.from(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///data/data/app.dshmobile/files/x")))
        assertNull("file:// paths are refused", evil)
        val many = SharedContent.from(Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList((1..12).map { Uri.parse("content://x/$it") })))!!
        assertEquals(SharedContent.MAX_FILES, many.uris.size)
    }

    @Test fun saveToShelfAddsAFile() {
        val ws = Files.createTempDirectory("ws").toFile()
        val saved = SharedSaver(ws, null) { 0 }.save(SharedContent("https://example.com 好文", "外資報告", emptyList()))
        val (rel, title) = saved.single()
        assertTrue(rel, rel.startsWith("shared/19700101-0800-外資報告") && rel.endsWith(".md"))
        assertEquals("外資報告", title)
        assertEquals("# 外資報告\n\nhttps://example.com 好文\n", File(ws, rel).readText())
    }

    @Test fun sheetOffersThreeChoices() {
        val asked = mutableListOf<String>()
        var shelf = 0
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.SAVER, reducedMotion = true)) {
                ShareSheet(SharedContent("一段文字", null, emptyList()), { asked += it }, { asked += "add:$it" }, { shelf++ }, {})
            }
        }
        rule.onNodeWithText("分享了一段文字").assertExists()
        rule.onNodeWithTag(ShareTags.QUESTION).performTextInput("這是真的嗎")
        rule.onNodeWithTag(ShareTags.ASK).performClick()
        rule.onNodeWithTag(ShareTags.ADD).performClick()
        rule.onNodeWithTag(ShareTags.SHELF).performClick()
        assertEquals(listOf("這是真的嗎", "add:這是真的嗎"), asked)
        assertEquals(1, shelf)
    }
}
