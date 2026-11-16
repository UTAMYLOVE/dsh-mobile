package app.dshmobile.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.dshmobile.ui.shell.Tags
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/**
 * 原本的樣子, frozen: every key screen as the app draws it today, compared with its golden in `src/test/resources/classic` (see
 * [PixelCompare] for the tolerance). A CLASSIC golden never changes; a mismatch is a bug in the change, never a reason to update
 * the golden (plan §4.4; the one documented exception is F6's `settings-look`).
 *
 * 320 × 640 at mdpi (one pixel per dp); tall pages are laid out in a 3,000 dp window and cropped to their measured height, and
 * the chat in a window tall enough for its whole conversation.
 */
private const val WINDOW = "w320dp-h640dp-mdpi"
/** Tall pages: laid out in this window, cropped to their measured height. */
private const val TALL = "w320dp-h3000dp-mdpi"
/** The chat, tall enough for its whole conversation. */
/**
 * AgentsTab reads `System.currentTimeMillis()` for the war room's clock; instrumented by Robolectric, that call follows the
 * pinned SystemClock (ThemeScenes.NOW) instead of the wall clock (F10 replaces this with AgentsTab's `now`).
 */
private const val WAR_PACKAGE = "app.dshmobile.ui.agents"
private const val CHAT_WINDOW = "w320dp-h2100dp-mdpi"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = WINDOW)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClassicPixelsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val zone: TimeZone = TimeZone.getDefault()
    private lateinit var scenes: ThemeScenes

    @Before fun setUp() {
        scenes = ThemeScenes(rule)
    }

    @After fun tearDown() {
        scenes.close()
        TimeZone.setDefault(zone)
    }

    private fun check(name: String) = PixelCompare.assertMatches(name, scenes.snap(name))

    /** A scene at FULL, the frame clock held: the frame at 0 ms and the one at 1,000 ms. */
    private fun atFull(name: String, scene: ThemeScenes.() -> Unit) {
        rule.mainClock.autoAdvance = false
        scenes = ThemeScenes(rule, ThemeScenes.FULL)
        scenes.scene()
        val first = scenes.snap("$name-full-0")
        rule.mainClock.advanceTimeBy(1_000)
        val second = scenes.snap("$name-full-1000")
        // Both are compared (and written out when missing) before either failure is reported.
        val failures = listOf("$name-full-0" to first, "$name-full-1000" to second).mapNotNull { (n, b) -> runCatching { PixelCompare.assertMatches(n, b) }.exceptionOrNull() }
        failures.firstOrNull()?.let { throw it }
    }

    // ---- the key screens, SAVER and reduced motion ----

    @Config(qualifiers = CHAT_WINDOW)
    @Test fun chat() {
        scenes.chat()
        val shot = scenes.snap("chat")
        // The whole conversation fits: its oldest message is on screen, below the chat's header.
        val oldest = rule.onNodeWithTag(Tags.USER_BUBBLE).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("the conversation fits the window", oldest.top >= rule.onNodeWithTag(Tags.CHAT_HEADER).fetchSemanticsNode().boundsInRoot.bottom)
        PixelCompare.assertMatches("chat", shot)
    }

    @Test fun home() { scenes.home(); check("home") }

    @Test fun list() { scenes.list(sheetOpen = false); check("list") }

    @Test fun listSheet() { scenes.list(sheetOpen = true); check("list-sheet") }

    @Test fun renameCard() { scenes.renameCard(); check("rename") }

    @Test fun deleteCard() { scenes.deleteCard(); check("delete") }

    @Test fun plusSheet() { scenes.plusSheet(); check("plus-sheet") }

    @Test fun prompt() { scenes.prompt(); check("prompt") }

    @Test fun messageMenu() { scenes.messageMenu(); check("message-menu") }

    @Test fun contents() { scenes.contents(); check("contents") }

    @Config(qualifiers = TALL)
    @Test fun settingsLook() { scenes.settingsLook(); check("settings-look") }

    @Config(qualifiers = TALL, instrumentedPackages = [WAR_PACKAGE])
    @Test fun war() {
        scenes.war()
        val shot = scenes.snap("war")
        // The clock is pinned: a moving clock fails here, loudly, instead of flaking in the comparison.
        rule.onNodeWithText("● " + ThemeScenes.clockText()).assertIsDisplayed()
        PixelCompare.assertMatches("war", shot)
    }

    // ---- motion: FULL, two frames ----

    @Test fun chatAtFull() = atFull("chat") { chat() }

    @Config(qualifiers = TALL, instrumentedPackages = [WAR_PACKAGE])
    @Test fun warAtFull() = atFull("war") {
        war()
        rule.onNodeWithText("● " + ThemeScenes.clockText()).assertExists()
    }

    // ---- the backdrop's own controls ----

    @Test fun chatOverAPicture() { scenes.chatPicture(ThemeScenes.Tone.MID); check("chat-picture-mid") }

    @Test fun chatKawaii() { scenes.chatKawaii(); check("chat-kawaii") }

    @Config(qualifiers = TALL)
    @Test fun settingsLookFlatCosy() { scenes.settingsLookFlat(picture = false, kawaii = false); check("settings-look-flat-cosy") }

    @Config(qualifiers = TALL)
    @Test fun settingsLookFlatKawaii() { scenes.settingsLookFlat(picture = true, kawaii = true); check("settings-look-flat-kawaii") }

    // ---- one golden for every migrated surface that had none (owner O20) ----

    @Test fun settingsAll() { scenes.settingsAll(); check("settings-all") }

    @Test fun licences() { scenes.licences(); check("licences") }

    @Test fun lock() { scenes.lock(); check("lock") }

    @Test fun shareSheet() { scenes.shareSheet(); check("share-sheet") }

    @Test fun statusLoading() { scenes.statusPanel(loading = true); check("status-loading") }

    @Test fun statusError() { scenes.statusPanel(loading = false); check("status-error") }

    @Test fun chatMore() { scenes.chatMore(); check("chat-more") }

    @Config(qualifiers = TALL)
    @Test fun overview() { scenes.overview(); check("overview") }

    @Test fun library() { scenes.library(); check("library") }

    @Test fun project() { scenes.project(); check("project") }

    @Test fun projects() { scenes.projects(); check("projects") }

    @Test fun helpers() { scenes.helpers(); check("helpers") }

    @Config(qualifiers = TALL)
    @Test fun dshStatus() { scenes.dshStatus(); check("dsh-status") }

    @Config(qualifiers = TALL, instrumentedPackages = [WAR_PACKAGE])
    @Test fun agentsList() { scenes.agentsList(); check("agents-list") }

    @Config(qualifiers = TALL)
    @Test fun weatherCard() { scenes.weatherCard(); check("weather-card") }

    @Config(qualifiers = TALL)
    @Test fun workCards() { scenes.workCards(); check("work-cards") }

    @Test fun planCard() { scenes.planCard(); check("plan-card") }

    @Config(qualifiers = TALL)
    @Test fun resultCards() { scenes.resultCards(); check("result-cards") }

    @Config(qualifiers = TALL)
    @Test fun dataPanel() { scenes.dataPanel(); check("data-panel") }

    @Config(qualifiers = TALL)
    @Test fun contextBar() { scenes.contextBar(); check("context-bar") }

    @Test fun chatExtras() { scenes.chatExtras(); check("chat-extras") }

    @Test fun chatBanners() { scenes.chatBanners(); check("chat-banners") }

    @Test fun chatExtrasReply() { scenes.chatExtrasReply(); check("chat-extras-reply") }

    @Config(qualifiers = TALL)
    @Test fun avatarDialog() { scenes.avatarDialog(); check("avatar-dialog") }

    @Test fun otherModeDialog() { scenes.otherModeDialog(); check("other-mode-dialog") }

    @Test fun svgZoom() { scenes.svgZoom(); check("svg-zoom") }
}
