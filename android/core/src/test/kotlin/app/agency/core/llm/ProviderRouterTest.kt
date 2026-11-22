package app.agency.core.llm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderRouterTest {
    @Test fun callsOnlyTheChosenProviderAndNeverFailsOver() = runBlocking {
        val calls = mutableListOf<String>()
        fun client(name: String) = LlmClient { calls += name; ChatResult.Failed(Fail.SERVER) }
        var choice = ProviderChoice.CLAUDE
        val router = ProviderRouter({ choice }, client("deepseek"), client("claude"), client("compat"))
        assertEquals(ChatResult.Failed(Fail.SERVER), router.chat(LlmTestKit.user))
        choice = ProviderChoice.COMPAT
        router.chat(LlmTestKit.user)
        choice = ProviderChoice.DEEPSEEK
        router.chat(LlmTestKit.user)
        assertEquals(listOf("claude", "compat", "deepseek"), calls)
    }
}
