package app.dshmobile.ui.agents

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.dshmobile.agents.AgentsRemote
import app.dshmobile.agents.RunningAgents
import app.dshmobile.chat.FakeIndexRemote
import app.dshmobile.chat.SessionTree
import app.dshmobile.chat.sum
import app.dshmobile.chat.waitUntil
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.effectsFor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port")
class AgentsTabTest {
    @get:Rule val rule = createComposeRule()
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() = sessionScope.cancel()

    @Test fun aStopSurvivesLeavingTheTab() {
        val gate = CompletableDeferred<Unit>()
        val started = AtomicInteger()
        val completed = AtomicInteger()
        val remote = object : AgentsRemote {
            override suspend fun interruptSubagent(childSessionId: String, parentSessionId: String) {
                started.incrementAndGet()
                gate.await()
                completed.incrementAndGet()
            }
        }
        val tree = SessionTree(FakeIndexRemote(listOf(sum("s1"), sum("c1", parent = "s1", running = true))), sessionScope)
        val agents = RunningAgents(tree, remote, sessionScope)
        waitUntil("row") { agents.rows.value.isNotEmpty() }
        var shown by mutableStateOf(true)
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.SAVER, reducedMotion = true)) {
                if (shown) AgentsTab(agents, tree, sessionScope, 6) {}
            }
        }
        rule.onNodeWithTag("agent-stop-c1").performClick()
        waitUntil("in flight") { started.get() == 1 }
        rule.runOnUiThread { shown = false } // the tab leaves the composition while the interrupt is gated
        rule.waitForIdle()
        gate.complete(Unit)
        waitUntil("completed") { completed.get() == 1 }
        assertEquals(1, started.get())
    }
}
