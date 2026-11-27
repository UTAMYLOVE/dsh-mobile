package app.dshmobile.ui.warroom

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.agency.core.persona.Roles
import app.dshmobile.agency.notify.AgencyNotifier
import app.dshmobile.ui.kit.SakuraTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 指揮室's role cards and the notification titles carry the manager's name (owner 2026-11-26: 小葵, 小葵姐). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StaffCardTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theManagerIsXiaoKuiAndTheLaterRolesHaveNotArrived() {
        rule.setContent { SakuraTheme { Box { StaffCard() } } }
        rule.onNodeWithTag(StaffTags.ROLE + Roles.MANAGER.id).assertTextContains("經紀人 小葵：台灣名夏葵、韓文名해나。你叫她小葵姐，她叫你小雨。")
        rule.onNodeWithTag(StaffTags.ROLE + Roles.BODY.id).assertTextContains("體態管理老師　在職")
        Roles.LATER.forEach { rule.onNodeWithTag(StaffTags.ROLE + it.id).assertTextContains("還沒到任", substring = true) }
        assertTrue(AgencyNotifier.SCHEDULE_TITLE.startsWith("小葵姐") && AgencyNotifier.RIDING_TITLE.startsWith("小葵姐"))
    }
}
