package app.dshmobile.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.dshmobile.ui.kit.SakuraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 設定 › 授權 after dsh (arch T09): SQLCipher, AndroidX, OkHttp, Kotlin and the Kai credit, each with its Chinese line. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LicensesPageTest {
    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var closed = 0

    private fun show() = rule.setContent { SakuraTheme { Box { LicensesPage(onClose = { closed++ }) } } }

    @Test fun everyNoticeHasItsChineseLineItsCopyrightAndItsFullText() {
        show()
        assertEquals(listOf("sqlcipher", "androidx", "okhttp", "kotlin", "kai"), OpenSourceLicences.notices.map { it.id })
        for (n in OpenSourceLicences.notices) {
            rule.onNodeWithText(context.getString(n.what)).performScrollTo()
            assertTrue(context.getString(n.what).any { it in '一'..'鿿' })
            n.copyrights.forEach { rule.onNodeWithText(it).performScrollTo() }
            rule.onNodeWithTag(LicensesTags.TEXT + n.id).assertDoesNotExist()
            rule.onNodeWithTag(LicensesTags.SHOW + n.id).performScrollTo().performClick()
            val text = rule.onNodeWithTag(LicensesTags.TEXT + n.id).performScrollTo()
            if (n.asset == OpenSourceLicences.APACHE) text.assertTextContains("Apache License", substring = true).assertTextContains("Version 2.0", substring = true)
            else text.assertTextContains("Redistribution and use in source and binary forms", substring = true)
            rule.onNodeWithTag(LicensesTags.SHOW + n.id).performScrollTo().performClick()
        }
        rule.onNodeWithTag(LicensesTags.CLOSE).performScrollTo().performClick()
        assertEquals(1, closed)
    }

    @Test fun nothingOfDshIsListedAnyMore() {
        val all = OpenSourceLicences.notices.joinToString { it.project + it.copyrights.joinToString() }
        listOf("PRoot", "proot", "talloc", "Termux", "Node", "dsh", "Orbitron", "commonmark").forEach { assertFalse(it, all.contains(it)) }
    }

    @Test fun everyListedAssetExists() {
        OpenSourceLicences.notices.map { it.asset }.distinct().forEach { f -> assertTrue(f, context.assets.open(f).use { it.readBytes().size } > 1000) }
        assertEquals(listOf("apache-2.0.txt", "sqlcipher-BSD.txt"), context.assets.list("licenses")!!.sorted())
    }
}
