package app.dshmobile.ui.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.dshmobile.chat.ChatItem
import app.dshmobile.chat.ConversationReducer
import app.dshmobile.chat.FileKind
import app.dshmobile.chat.PresentedFile
import app.dshmobile.chat.ToolStatus
import app.dshmobile.chat.WebSource
import app.dshmobile.runtime.AppPaths
import app.dshmobile.ui.common.SvgImage
import app.dshmobile.ui.common.SvgTags
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.effectsFor
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
class ResultCardsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun presentedFilesBecomeRows() {
        val args = Json.parseToJsonElement(
            """{"files":[{"path":"reports/2026-11-06-外資.md","description":"外資報告"},{"path":"${AppPaths.GUEST_WORKSPACE}/deliverables/../deliverables/表.xlsx"},{"path":"  "}]}""",
        ) as JsonObject
        val files = ConversationReducer.presented("present", args)
        assertEquals(listOf("reports/2026-11-06-外資.md", "deliverables/表.xlsx"), files.map { it.path })
        assertEquals("外資報告", files[0].description)
        assertEquals(listOf(FileKind.MD, FileKind.XLS), files.map { it.kind })
        assertTrue(ConversationReducer.presented("write", args).isEmpty())

        var opened: String? = null
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.FULL, reducedMotion = false)) { PresentCard("c1", files, null, null, { opened = it }, {}) }
        }
        rule.onNodeWithTag("${ResultTags.PRESENT}c1").assertExists()
        rule.onNodeWithText("表.xlsx").assertExists()
        rule.onNodeWithTag("${ResultTags.OPEN}c1-1").performClick()
        assertEquals("deliverables/表.xlsx", opened)
    }

    @Test fun researchCardCountsSearchesAndSources() {
        val search = { k: String, urls: List<String> -> ChatItem.Tool(k, "web_search", "q", ToolStatus.DONE, urls.map { WebSource(it, it) }) }
        val items = listOf(
            ChatItem.User("e1", "舊問題", emptyList()),
            search("s0", listOf("https://old")),
            ChatItem.User("e2", "研究外資", emptyList()),
            search("s1", listOf("https://a", "https://b")),
            search("s2", listOf("https://b", "https://c")),
            ChatItem.Tool("p1", "present", "", ToolStatus.DONE, presented = listOf(PresentedFile("reports/x.md", null))),
            ChatItem.Tool("p2", "present", "", ToolStatus.DONE, presented = listOf(PresentedFile("deliverables/y.docx", null))),
        )
        val rows = durableRows(items, false, null, BlockCache(), emptySet(), "").rows.filterIsInstance<ChatRow.Present>()
        assertEquals(ResearchCounts(2, 3), rows[0].research)
        assertNull(rows[1].research)
    }

    @Test fun badgesUseOnlyThePalette() {
        val palette = setOf(0xFFFEDFE1, 0xFFDC9FB4, 0xFFA5DEE4, 0xFFD7C4BB, 0xFF9E7A7A, 0xFF5E4445, 0xFF7D5F60, 0xFFA98F90, 0xFFFFFFFF)
        for (k in FileKind.values()) {
            val (bg, fg) = badgeColors(k)
            for (c in listOf(bg, fg)) {
                val argb = c.value.shr(32).toLong() and 0xFFFFFFFFL
                assertTrue("$k ${java.lang.Long.toHexString(argb)}", argb in palette)
            }
        }
    }

    @Test fun badSvgShowsMessage() {
        val file = Files.createTempFile("bad", ".svg").toFile().apply { writeText("<svg nope") }
        rule.setContent { FogTheme(effectsFor(EffectLevel.FULL, reducedMotion = false)) { SvgImage(file) } }
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasTestTag(SvgTags.BROKEN)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("這張圖打不開").assertExists()
    }
}
