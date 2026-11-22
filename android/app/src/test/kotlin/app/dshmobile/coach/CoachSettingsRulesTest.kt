package app.dshmobile.coach

import app.dshmobile.gateway.InMemorySharedPreferences
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Test

/** Split out of CoachRulesTest when the rules moved to :core; the settings stay in the app. */
class CoachSettingsRulesTest {
    @Test fun theCompanyRulesWinOverWhateverIsSaved() {
        val s = CoachSettings(InMemorySharedPreferences())
        // 公司規章: the quota, protein and 休假餐 day are fixed, whatever is saved.
        s.profile = CoachProfile(quotaKcal = 1000, proteinG = 90, cheatDay = DayOfWeek.MONDAY)
        assertEquals(1700, s.profile.quotaKcal)
        assertEquals(140, s.profile.proteinG)
        assertEquals(DayOfWeek.FRIDAY, s.profile.cheatDay)
    }
}
