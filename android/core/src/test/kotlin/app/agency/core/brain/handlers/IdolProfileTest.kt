package app.agency.core.brain.handlers

import app.agency.core.store.InMemoryAgencyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 藝名 and 代表色 (arch T10): he sets both; code checks them, no model. */
class IdolProfileTest {
    private val store = InMemoryAgencyStore()
    private val idol = IdolProfile(store)

    @Test fun aTypedStageNameIsSaved() = runBlocking {
        val reply = idol.answer("藝名 小雨")!!
        assertTrue(reply, reply.startsWith("記下：藝名「小雨」。"))
        assertTrue(reply, reply.contains("代表色"))
        assertEquals("小雨", store.profile.get()!!.stageName)
        assertEquals("記下：藝名「雨晴」。", idol.answer("藝名改成雨晴。").also { idol.set(null, "#F4A7B9") }?.removeSuffix("代表色在練習生證上選。"))
    }

    @Test fun otherMessagesAreNotStageNames() = runBlocking {
        assertNull(idol.answer("我想想藝名"))
        assertNull(idol.answer("藝名"))
        assertNull(store.profile.get())
    }

    @Test fun latinLettersAndLongNamesAreRefused() = runBlocking {
        assertEquals("藝名用中文字，畫面上不放英文字母。", idol.answer("藝名 Rain"))
        assertEquals("藝名最多 12 個字。", idol.answer("藝名 一二三四五六七八九十一二三"))
        assertNull(store.profile.get())
    }

    @Test fun theCardSetsTheColourAndTheNameButNotABadColour() = runBlocking {
        assertNull(idol.set(null, "#8E2440"))
        assertNull(idol.set("小雨", null))
        assertEquals("代表色不對。", idol.set(null, "red"))
        val p = store.profile.get()!!
        assertEquals("小雨", p.stageName)
        assertEquals("#8E2440", p.memberColor)
    }
}
