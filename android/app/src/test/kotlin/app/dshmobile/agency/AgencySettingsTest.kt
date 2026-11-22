package app.dshmobile.agency

import app.agency.core.llm.ProviderChoice
import app.agency.core.migrate.AgencyPrefKeys
import app.dshmobile.agency.support.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgencySettingsTest {
    private val prefs = InMemorySharedPreferences()
    private val s = AgencySettings(prefs)

    @Test fun theValuesTheImporterCopiedAreRead() {
        prefs.edit().putString(AgencyPrefKeys.PROVIDER, "CLAUDE").putString(AgencyPrefKeys.MODEL_CLAUDE, "claude-haiku-5-5").apply()
        assertEquals(ProviderChoice.CLAUDE, s.provider)
        assertEquals("claude-haiku-5-5", s.claudeModel)
    }

    @Test fun anythingElseReadsAsTheDefault() {
        prefs.edit().putString(AgencyPrefKeys.PROVIDER, "OPENAI").putString(AgencyPrefKeys.MODEL_DEEPSEEK, "deepseek-chat").apply()
        assertEquals(ProviderChoice.DEEPSEEK, s.provider)
        assertEquals("deepseek-flash", s.deepseekModel)
        assertNull(s.compatEndpoint())
    }

    @Test fun theCompatibleServiceNeedsHttpsAndHasADailyCap() {
        s.compatUrl = "http://example.test/v1"
        s.compatModel = "m"
        assertNull(s.compatEndpoint())
        s.compatUrl = "https://example.test/v1/chat/completions"
        assertEquals("https://example.test/v1/", s.compatEndpoint()!!.base.toString())
        assertFalse(s.compatEndpoint()!!.tools)
        repeat(AgencySettings.COMPAT_DAILY_CAP) { assertTrue(s.takeCompatRequest("2026-11-23")) }
        assertFalse(s.takeCompatRequest("2026-11-23"))
        assertTrue(s.takeCompatRequest("2026-11-24"))
    }
}
