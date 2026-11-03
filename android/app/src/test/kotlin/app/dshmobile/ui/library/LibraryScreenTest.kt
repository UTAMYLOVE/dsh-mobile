package app.dshmobile.ui.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.dshmobile.chat.ChatItem
import app.dshmobile.chat.ConversationState
import app.dshmobile.library.LibraryStore
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.effectsFor
import app.dshmobile.ui.shell.Tags
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port")
class LibraryScreenTest {
    @get:Rule val rule = createComposeRule()
    private val store = LibraryStore(File(Files.createTempDirectory("lib").toFile(), LibraryStore.RELATIVE_PATH))
    private val conv = MutableStateFlow(ConversationState("s1"))
    private val sent = mutableListOf<Pair<String, String>>()
    private val opened = mutableListOf<String>()
    private val dsh = LibraryDsh(
        startBook = { _, opening -> sent += "new" to opening; "s1" },
        conversation = { conv },
        send = { id, text -> sent += id to text },
        openChat = { opened += it },
    )

    private fun show(d: LibraryDsh? = dsh) = rule.setContent {
        FogTheme(effectsFor(EffectLevel.SAVER, reducedMotion = true)) {
            LibraryScreen(store, d, listOf("deepseek-v4-pro" to "DeepSeek V4 Pro"), "deepseek-v4-pro")
        }
    }

    @Test fun aNewBookOpensWithTheCardAndItsRepliesBecomeReadableChapters() {
        store.newCard("小美", "愛吃草莓")
        show()
        rule.onNodeWithTag(Tags.LIB_NEW).performClick()
        rule.onNodeWithTag(Tags.LIB_TITLE).performTextInput("霧城")
        rule.onNodeWithText("小美").performClick()
        rule.onNodeWithTag(Tags.LIB_CREATE).performClick()
        rule.waitUntil(5_000) { store.state.value.books.isNotEmpty() }
        assertTrue(sent.single().second.contains("愛吃草莓"))
        conv.value = ConversationState("s1", items = listOf(ChatItem.Assistant("e2", 2, null, "第一章 霧\n" + "雨".repeat(400))))
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.LIB_BOOK_PAGE).assertIsDisplayed()
        rule.onNodeWithTag(Tags.LIB_READ).performClick()
        rule.onNodeWithTag(Tags.LIB_READER).assertIsDisplayed()
        rule.onNodeWithTag(Tags.LIB_FONT_UP).performClick()
        rule.onNodeWithTag(Tags.LIB_NIGHT).performClick()
        rule.waitForIdle()
        assertEquals(20, store.state.value.fontSp)
        assertTrue(store.state.value.night)
        rule.onNodeWithTag(Tags.LIB_WRITE_NEXT).performScrollTo().performClick()
        rule.waitUntil(5_000) { sent.size == 2 }
        assertEquals("s1", sent[1].first)
        rule.onNodeWithTag(Tags.LIB_WRITE_NEXT).assertIsNotEnabled()
    }

    @Test fun withoutDshTheShelfStillShowsStoredBooks() {
        store.addBook("舊書", "s7", null, null)
        show(null)
        rule.onNodeWithTag(Tags.LIB_NEW).assertIsNotEnabled()
        rule.onNodeWithText("舊書").assertIsDisplayed()
    }

    @Test fun cardsCanBeWritten() {
        show()
        rule.onNodeWithTag(Tags.LIB_CARDS).performClick()
        rule.onNodeWithTag(Tags.LIB_CARD_NEW).performClick()
        rule.onNodeWithTag(Tags.LIB_CARD_NAME).performTextInput("世界觀")
        rule.onNodeWithTag(Tags.LIB_CARD_BODY).performTextInput("霧都")
        rule.onNodeWithTag(Tags.LIB_CARD_SAVE).performClick()
        rule.waitForIdle()
        assertEquals("世界觀", store.state.value.cards.single().name)
    }
}
