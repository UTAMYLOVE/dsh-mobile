package app.dshmobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.dshmobile.ui.theme.SakuraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LicensesPageTest {
    @get:Rule val rule = createComposeRule()
    private var closed = 0

    private fun show() = rule.setContent { SakuraTheme { Box { LicensesPage(onClose = { closed++ }) } } }

    @Test fun everyFontShowsItsCopyrightAndItsFullOflText() {
        show()
        for (l in OpenSourceLicences.introFonts + OpenSourceLicences.otherFonts) {
            l.copyrights.forEach { rule.onNodeWithText(it).performScrollTo() }
            rule.onNodeWithTag(LicensesTags.TEXT + l.id).assertDoesNotExist()
            rule.onNodeWithTag(LicensesTags.SHOW + l.id).performScrollTo().performClick()
            rule.onNodeWithTag(LicensesTags.TEXT + l.id).performScrollTo()
                .assertTextContains("SIL OPEN FONT LICENSE Version 1.1", substring = true)
                .assertTextContains("Reserved Font Name", substring = true)
        }
        // The renamed subset names its source and why it was renamed.
        rule.onNodeWithTag(LicensesTags.TEXT + "intro-hand").assertTextContains("Modified Version of 辰宇落雁體 2.0 Thin", substring = true)
    }

    @Test fun theStartupPluginsAreCreditedWithAuthorLinkAndTheMitText() {
        show()
        val credits = OpenSourceLicences.startupPlugins
        assertTrue(credits.size >= 7)
        for (c in credits) {
            rule.onNodeWithText("${c.project} · ${c.author}").performScrollTo()
            rule.onNodeWithText(c.url).performScrollTo()
            rule.onNodeWithText("MIT License, ${c.copyright}").performScrollTo()
            assertTrue(c.url, c.url.startsWith("https://github.com/"))
        }
        rule.onNodeWithTag(LicensesTags.SHOW + "mit").performScrollTo().performClick()
        val mit = rule.onNodeWithTag(LicensesTags.TEXT + "mit").performScrollTo()
        mit.assertTextContains("Permission is hereby granted, free of charge", substring = true)
        // Every credited copyright line travels with the permission notice.
        credits.forEach { mit.assertTextContains(it.copyright, substring = true) }
    }

    @Test fun theLibraryLicenceIsThereAndClosingWorks() {
        show()
        rule.onNodeWithTag(LicensesTags.SHOW + "commonmark").performScrollTo().performClick()
        rule.onNodeWithTag(LicensesTags.TEXT + "commonmark").performScrollTo().assertTextContains("Robin Stocker", substring = true)
        rule.onNodeWithTag(LicensesTags.CLOSE).performScrollTo().performClick()
        assertEquals(1, closed)
    }

    @Test fun everyListedAssetExists() {
        val assets = ApplicationProvider.getApplicationContext<android.content.Context>().assets
        val files = (OpenSourceLicences.introFonts + OpenSourceLicences.otherFonts + OpenSourceLicences.libraries).map { it.asset } + OpenSourceLicences.MIT_ASSET
        files.forEach { f -> assertTrue(f, assets.open(f).use { it.readBytes().size } > 500) }
    }
}
