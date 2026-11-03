package app.dshmobile.library

import app.dshmobile.chat.ChatItem
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {
    private val dir: File = Files.createTempDirectory("lib").toFile()
    private fun store() = LibraryStore(File(dir, LibraryStore.RELATIVE_PATH)) { 42 }
    private fun reply(key: String, chars: Int) = ChatItem.Assistant(key, 1, null, "第一章\n" + "字".repeat(chars))

    @Test fun booksCardsAndPositionSurviveARestart() {
        val a = store()
        val card = a.newCard("小美", "十七歲，愛吃草莓")
        val b = a.addBook("霧城", "s1", card.id, "deepseek-v4-pro")
        assertEquals(b, a.addBook("again", "s1", null, null)) // one conversation, one book
        a.syncChapters(b.id, listOf(Chapter("e3", "正文")))
        a.savePosition(b.id, "e3", 120, 1)
        a.setReading(fontSp = 40, night = true)
        val again = store().state.value
        assertEquals(listOf(card), again.cards)
        val got = again.books.single()
        assertEquals("霧城", got.title)
        assertEquals(listOf(Chapter("e3", "正文")), got.chapters)
        assertEquals("e3", got.readKey)
        assertEquals(120, got.readScroll)
        assertEquals(32, again.fontSp) // clamped
        assertTrue(again.night)
        assertEquals(42L, got.createdAt)
    }

    @Test fun longRepliesBecomeChaptersUnlessExcludedAndShortOnesCanBeAdded() {
        val items = listOf(ChatItem.User("e1", "寫", emptyList()), reply("e2", 400), reply("e3", 20), reply("e4", 500))
        val book = Book("b", "t", "s1")
        assertEquals(listOf("e2", "e4"), Chapters.of(book, items).map { it.key })
        assertEquals(listOf("e3", "e4"), Chapters.of(book.copy(excluded = setOf("e2"), added = setOf("e3")), items).map { it.key })
        // Chapters the loaded window no longer holds stay, older first.
        val stored = book.copy(chapters = listOf(Chapter("e0", "舊")))
        assertEquals(listOf("e0", "e2", "e4"), Chapters.of(stored, items).map { it.key })
        assertEquals(stored.chapters, Chapters.of(stored, emptyList()))
    }

    @Test fun markingNotAChapterDropsItAndTheOpeningCarriesTheCard() {
        val s = store()
        val b = s.addBook("t", "s9", null, null)
        s.syncChapters(b.id, listOf(Chapter("e2", "x"), Chapter("e4", "y")))
        s.setChapter(b.id, "e2", false)
        val now = s.state.value.books.single()
        assertEquals(listOf("e4"), now.chapters.map { it.key })
        assertTrue("e2" in now.excluded)
        val opening = Chapters.opening("霧城", CharacterCard("c", "小美", "愛吃草莓"))
        assertTrue(opening.contains("《霧城》") && opening.contains("愛吃草莓") && opening.contains("第一章"))
    }

    @Test fun aBrokenFileStartsEmpty() {
        val f = File(dir, LibraryStore.RELATIVE_PATH).apply { parentFile!!.mkdirs(); writeText("{not json") }
        assertTrue(LibraryStore(f).state.value.books.isEmpty())
    }
}
