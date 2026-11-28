package app.dshmobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import app.agency.core.agent.Attachment
import app.agency.core.agent.Card
import app.dshmobile.ui.cards.CardActions
import app.dshmobile.ui.cards.CardView
import app.dshmobile.ui.home.Door
import app.dshmobile.ui.home.PhotoRoute
import app.dshmobile.ui.home.PhotoRouting
import app.dshmobile.ui.home.stageOf
import app.dshmobile.ui.onboarding.OnboardStep
import app.dshmobile.ui.onboarding.OnboardingFlow
import app.dshmobile.ui.pages.BodyPhotosPage
import app.dshmobile.ui.pages.QuizPage
import app.dshmobile.ui.pages.SingingPage
import app.dshmobile.ui.settings.SettingsPage
import app.dshmobile.ui.settings.SettingsRoot
import app.dshmobile.ui.sheets.MealLogSheet
import app.dshmobile.ui.sheets.mealTotals
import app.dshmobile.wire.ui.TimerPlans
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pure rules of the fix wave: where a photo goes, which doors show, the timer plans, the meal totals. */
class FixWavePureTest {
    private fun photo(gallery: Boolean = false) = Attachment.Photo("p1", ByteArray(0), gallery)

    @Test fun aSpotPhotoGoesToTheSpotCheckAndNeverToChat() {
        val photos = DemoPhotos(spotOpen = true)
        val r = PhotoRouting.decide("spot", photo(), photos, "沒有檢查", "存不起來")
        assertTrue(r is PhotoRoute.Done)
        assertEquals(listOf("spot:p1"), photos.log.toList())
    }

    @Test fun aSpotPhotoWithNoOpenCheckSaysSoInsteadOfGoingToChat() {
        val r = PhotoRouting.decide("spot", photo(), DemoPhotos(spotOpen = false), "沒有檢查", "存不起來")
        assertEquals(PhotoRoute.Said("沒有檢查"), r)
    }

    @Test fun aRecordedMealWithoutAPhotoTakesItDirectly() {
        val photos = DemoPhotos(mealRecorded = true)
        val r = PhotoRouting.decide("DINNER", photo(gallery = true), photos, "", "")
        assertTrue(r is PhotoRoute.Done)
        assertEquals(listOf("meal:DINNER:p1:true"), photos.log.toList())
    }

    @Test fun aMealNotRecordedYetGoesToChatNamingTheSlot() {
        assertEquals(PhotoRoute.Chat("FIRST"), PhotoRouting.decide("FIRST", photo(), DemoPhotos(mealRecorded = false), "", ""))
        assertEquals(PhotoRoute.Chat(""), PhotoRouting.decide("", photo(), DemoPhotos(), "", ""))
    }

    @Test fun aBodyPhotoIsKept() {
        val photos = DemoPhotos()
        assertTrue(PhotoRouting.decide("body", photo(), photos, "", "") is PhotoRoute.Done)
        assertTrue(PhotoRouting.decide("photo", photo(), photos, "", "") is PhotoRoute.Done)
        assertEquals(listOf("body:p1", "body:p1"), photos.log.toList())
    }

    @Test fun doorsOpenWithTheStage() {
        assertEquals(listOf(Door.LESSONS, Door.MEMO, Door.MONEY, Door.TRAINEE), Door.at(2))
        assertTrue(Door.SINGING in Door.at(3))
        assertFalse(Door.EVAL in Door.at(3))
        assertEquals(Door.entries.toList(), Door.at(4))
        assertEquals(4, stageOf("第 4 階段 · 第 2 天"))
        assertEquals(1, stageOf(""))
    }

    @Test fun timerPlansNameThemesInChineseAndPersonalTaskIsTenMinutes() {
        val t = app.agency.core.rules.TrainingRule()
        val theme = TimerPlans.of("THEME_PRACTICE", t, themeName = "表情週", themeMinutes = 5)
        assertEquals("表情週", theme.title)
        assertFalse(theme.title.contains("THEME"))
        assertEquals(300, theme.moves.single().seconds)
        val personal = TimerPlans.of("PERSONAL_TASK", t)
        assertEquals(600, personal.moves.single().seconds)
        val groom = TimerPlans.of("GROOMING", t, groomingDue = listOf("指甲", "頭髮"))
        assertEquals(listOf("指甲", "頭髮"), groom.checklist)
    }

    @Test fun mealTotalsAreAddedByCode() {
        val (kcal, protein) = mealTotals(listOf(FoodRow("a", 650, 40.0), FoodRow("b", 130, 2.5)))
        assertEquals(780, kcal)
        assertEquals(42.5, protein, 0.001)
    }

    @Test fun aSayResultOkNeverOffersAResend() {
        // The core answers Ok when the records were saved but her reply is missing (a NOTE tells what was saved).
        val vm = app.dshmobile.ui.home.HomeViewModel(
            DemoCompany(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), null, kotlinx.coroutines.Dispatchers.Unconfined,
        )
        vm.send("我吃了地瓜")
        assertTrue(vm.local.value.none { it.failed })
        assertNull(vm.notice.value)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h828dp-xhdpi")
class FixWaveScreensTest {
    @get:Rule val rule = createComposeRule()

    private fun ComposeContentTestRule.count(tag: String) = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size
    private fun ComposeContentTestRule.has(text: String) = onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test fun importedKeysAreSaidSoAndNextIsEnabled() {
        val ob = object : OnboardingUi by DemoOnboarding() {
            override fun keysSaved() = true
            override fun deepSeekSaved() = false
        }
        rule.show { OnboardingFlow(ob, {}, startAt = OnboardStep.KEYS) }
        rule.onNodeWithTag("keys_imported").assertIsDisplayed()
        assertTrue(rule.has("已經帶入你之前存的金鑰"))
        assertEquals(0, rule.count("key_claude"))
        rule.onNodeWithTag("ob_next").assertIsEnabled()
        rule.onNodeWithTag("keys_change").performClick()
        rule.onNodeWithTag("key_claude").assertIsDisplayed()
    }

    @Test fun keysStepNamesBothKeysAndWhatTheSecondIsFor() {
        rule.show { OnboardingFlow(DemoOnboarding(), {}, startAt = OnboardStep.KEYS) }
        assertTrue(rule.has("主要金鑰（Claude）"))
        assertTrue(rule.has("第二把金鑰（DeepSeek）"))
        assertTrue(rule.has("沒有第二把，小葵就不能幫你搜影片和地點；你還是可以把影片連結貼給她。"))
    }

    @Test fun welcomeOffersRestoreFromBackup() {
        rule.show { OnboardingFlow(DemoOnboarding(), {}, backup = DemoBackup(), onRestored = { true }) }
        rule.onNodeWithTag("ob_restore").performClick()
        rule.onNodeWithTag("ob_restore_pass").performTextInput("secret1")
        rule.onNodeWithTag("ob_restore_pick").performClick()
        rule.until { rule.count("ob_restore_preview") > 0 }
        rule.onNodeWithTag("ob_restore_apply").performClick()
    }

    @Test fun chatOnDeepSeekCannotBePickedWithoutItsKey() {
        rule.show { SettingsRoot(DemoSettings(), DemoBackup(), DemoOldData(), {}, startAt = SettingsPage.MODEL) }
        rule.until { rule.count("model_cheap") > 0 }
        rule.onNodeWithTag("model_cheap").assertIsNotEnabled()
        rule.onNodeWithTag("model_cheap_reason").assertIsDisplayed()
    }

    @Test fun importPageGivesTheFileToXiaokui() {
        val got = CopyOnWriteArrayList<String>()
        rule.show { SettingsRoot(DemoSettings(), DemoBackup(), DemoOldData(), {}, startAt = SettingsPage.IMPORT, media = DemoMedia(), onImportFile = { got += it.name }) }
        rule.onNodeWithTag("import_file").performClick()
        rule.until { got.isNotEmpty() }
        assertEquals(listOf("菜單"), got.toList())
    }

    @Test fun singleActionProposalShowsItsWordAndNoRefuse() {
        val actions = object : CardActions {
            override fun answer(messageId: Long, cardIndex: Int, answer: String) {}
            override fun open(screen: String, arg: String) {}
            override fun openUrl(url: String) {}
            override fun watch(lessonId: Long) {}
        }
        rule.show { Column(Modifier.padding(16.dp)) { CardView(Card.Proposal(5, "匯入了 12 筆", "", yesLabel = "復原"), 1, 0, actions, DemoMedia()) } }
        assertTrue(rule.has("復原"))
        assertEquals(0, rule.count("proposal_no"))
    }

    @Test fun mealSheetTotalsAndSavesWithoutTheModel() {
        val meals = DemoMeals()
        val done = java.util.concurrent.atomic.AtomicBoolean()
        rule.show { MealLogSheet(meals, DemoMedia(), "DINNER", null, {}, { done.set(true) }, {}) }
        rule.until { rule.count("food_0") > 0 }
        rule.onNodeWithTag("meal_save").assertIsNotEnabled()
        rule.onNodeWithTag("food_0").performClick()
        rule.onNodeWithTag("food_1").performClick()
        assertTrue(rule.has("合計 780 大卡，蛋白質 42 克"))
        rule.onNodeWithTag("meal_save").performClick()
        rule.until { done.get() }
        assertEquals(listOf("meal:DINNER:雞腿便當,地瓜:false"), meals.log.toList())
    }

    @Test fun emptyFoodTableSaysSoAndOffersAskingXiaokui() {
        val asked = java.util.concurrent.atomic.AtomicBoolean()
        rule.show { MealLogSheet(DemoMeals(emptyList()), DemoMedia(), "FIRST", null, {}, {}, { asked.set(true) }) }
        rule.until { rule.count("meal_empty") > 0 }
        rule.onNodeWithTag("meal_ask").performClick()
        assertTrue(asked.get())
    }

    @Test fun quizSaysItCannotBeMadeNowAndLeavesTheLesson() {
        val lessons = object : LessonsUi by DemoLessons() {
            override suspend fun quiz(lessonId: Long) = QuizView(lessonId, "換氣", emptyList(), unavailable = "現在出不了題，等一下再試")
        }
        rule.show { QuizPage(lessons, 3, false, {}, {}) }
        rule.until { rule.count("quiz_unavailable") > 0 }
        assertTrue(rule.has("現在出不了題，等一下再試"))
        assertEquals(0, rule.count("note_field"))
    }

    @Test fun lyricCheckNeedsTheTickEveryTime() {
        val singing = DemoSinging(key = true)
        rule.show { SingingPage(singing, {}) }
        rule.onNodeWithTag("singing_toggle").performClick()
        rule.until { rule.count("singing_toggle") > 0 }
        rule.onNodeWithTag("singing_toggle").performClick()
        rule.until { rule.count("singing_lyrics") > 0 }
        rule.onNodeWithTag("lyrics_field").performTextInput("天上星星")
        rule.onNodeWithTag("lyrics_go").assertIsNotEnabled()
        rule.onNodeWithTag("lyrics_consent").performClick()
        rule.onNodeWithTag("lyrics_go").assertIsEnabled()
        rule.onNodeWithTag("lyrics_go").performClick()
        rule.until { rule.count("lyrics_line") > 0 }
        // the tick was for that one time
        rule.onNodeWithTag("lyrics_go").assertIsNotEnabled()
        assertEquals(listOf("lyrics:true"), singing.log.filter { it.startsWith("lyrics") })
    }

    @Test fun singingShowsTheShiftLineAndTheStrictSwitch() {
        val singing = DemoSinging()
        rule.show { SingingPage(singing, {}) }
        rule.onNodeWithTag("strict_normal").performClick()
        assertTrue(singing.log.contains("strict:false"))
        rule.onNodeWithTag("singing_toggle").performClick()
        rule.onNodeWithTag("singing_toggle").performClick()
        rule.until { rule.count("singing_shift") > 0 }
    }

    @Test fun bodyPhotoTimelineComparesFirstAndLatest() {
        rule.show { BodyPhotosPage(DemoBody(), DemoMedia(), {}, {}) }
        rule.until { rule.count("bodyphotos_compare") > 0 }
        assertTrue(rule.has("最早　10/05"))
        assertTrue(rule.has("最新　11/23"))
    }
}
