package app.dshmobile.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.dshmobile.ui.LicensesTags
import app.dshmobile.ui.chat.HelperTags
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

/**
 * 原本的樣子, frozen: every key screen as the app draws it today, compared with its golden in `src/test/resources/classic` (see
 * [PixelCompare] for the tolerance). A CLASSIC golden never changes; a mismatch is a bug in the change, never a reason to update
 * the golden (plan §4.4; the one documented exception is F6's `settings-look`).
 *
 * 320 × 640 at mdpi (one pixel per dp). Every page is pinned whole: tall pages are laid out in a 3,000 dp window and cropped to
 * their measured height; pages and sheets that fill their window are laid out in one tall enough for all of them, which
 * [ThemeScenes.assertWhole] checks; the chat in a window tall enough for its whole conversation; the settings tab, longer than
 * any window, as its top at 640 and its two halves around 外觀主題 ([ThemeScenes.snapSettingsUpper] / `Lower`).
 */
private const val WINDOW = "w320dp-h640dp-mdpi"
/** Tall pages: laid out in this window, cropped to their measured height. */
private const val TALL = "w320dp-h3000dp-mdpi"
/** The chat, tall enough for its whole conversation. */
private const val CHAT_WINDOW = "w320dp-h2100dp-mdpi"
/** 對話紀錄's home and 小幫手: all of the page above the dock. */
private const val PAGE_WINDOW = "w320dp-h1000dp-mdpi"
/** The list, its sheet, a project, the projects and the ＋ sheet: all of the page or sheet above the dock. */
private const val SHELL_WINDOW = "w320dp-h1300dp-mdpi"
/** The model menu with all its chips above the dock's extras. */
private const val MENU_WINDOW = "w320dp-h900dp-mdpi"
/** The licences page, whole. */
private const val LICENCES_WINDOW = "w320dp-h3700dp-mdpi"
/** Either half of the settings tab (金鑰 … 模型; 效果強度 … 套用並重啟) between the header and the dock. */
private const val SETTINGS_WINDOW = "w320dp-h3300dp-mdpi"
/**
 * AgentsTab reads `System.currentTimeMillis()` for the war room's clock; instrumented by Robolectric, that call follows the
 * pinned SystemClock (ThemeScenes.NOW) instead of the wall clock (F10 replaces this with AgentsTab's `now`).
 */
private const val WAR_PACKAGE = "app.dshmobile.ui.agents"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = WINDOW)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClassicPixelsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var scenes: ThemeScenes

    @Before fun setUp() {
        scenes = ThemeScenes(rule)
    }

    @After fun tearDown() {
        scenes.close()
    }

    private fun check(name: String) = PixelCompare.assertMatches(name, scenes.snap(name))

    /** A scene at FULL, the frame clock held: the frame at 0 ms and the one at 1,000 ms. */
    private fun atFull(name: String, scene: ThemeScenes.() -> Unit) {
        rule.mainClock.autoAdvance = false
        scenes.close()
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

    @Config(qualifiers = PAGE_WINDOW)
    @Test fun home() { scenes.home(); scenes.assertWhole(Tags.SHELL_STAGE); check("home") }

    @Config(qualifiers = SHELL_WINDOW)
    @Test fun list() { scenes.list(sheetOpen = false); scenes.assertWhole(Tags.SHELL_STAGE); check("list") }

    @Config(qualifiers = SHELL_WINDOW)
    @Test fun listSheet() { scenes.list(sheetOpen = true); scenes.assertWhole(Tags.SHELL_STAGE); check("list-sheet") }

    @Test fun renameCard() { scenes.renameCard(); check("rename") }

    @Test fun deleteCard() { scenes.deleteCard(); check("delete") }

    @Config(qualifiers = SHELL_WINDOW)
    @Test fun plusSheet() { scenes.plusSheet(); scenes.assertWhole(Tags.PLUS_SHEET); check("plus-sheet") }

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

    /**
     * The rest of the settings tab, in two halves F6's rows at the top of 外觀主題 cannot move: 金鑰 … 模型 at the top of the
     * tab, and 效果強度 … 套用並重啟 scrolled to its end (外觀主題 itself is settings-look and the flat goldens).
     */
    @Config(qualifiers = SETTINGS_WINDOW)
    @Test fun settingsAllHalves() {
        scenes.settingsAll()
        val upper = scenes.snapSettingsUpper("settings-all-upper")
        val lower = scenes.snapSettingsLower("settings-all-lower")
        // Both are compared (and written out when missing) before either failure is reported.
        val failures = listOf("settings-all-upper" to upper, "settings-all-lower" to lower).mapNotNull { (n, b) -> runCatching { PixelCompare.assertMatches(n, b) }.exceptionOrNull() }
        failures.firstOrNull()?.let { throw it }
    }

    @Config(qualifiers = LICENCES_WINDOW)
    @Test fun licences() { scenes.licences(); scenes.assertWhole(LicensesTags.PAGE); check("licences") }

    /** A licence's full text open: the fixed-width block (cropped to it). */
    @Config(qualifiers = LICENCES_WINDOW)
    @Test fun licenceText() { scenes.licences(open = "commonmark"); check("licences-text") }

    @Test fun lock() { scenes.lock(); check("lock") }

    @Test fun shareSheet() { scenes.shareSheet(); check("share-sheet") }

    @Test fun statusLoading() { scenes.statusPanel(loading = true); check("status-loading") }

    @Test fun statusError() { scenes.statusPanel(loading = false); check("status-error") }

    @Test fun chatMore() { scenes.chatMore(); check("chat-more") }

    @Config(qualifiers = TALL)
    @Test fun overview() { scenes.overview(); check("overview") }

    @Test fun library() { scenes.library(); check("library") }

    @Config(qualifiers = SHELL_WINDOW)
    @Test fun project() { scenes.project(); scenes.assertWhole(Tags.SHELL_STAGE); check("project") }

    @Config(qualifiers = SHELL_WINDOW)
    @Test fun projects() { scenes.projects(); scenes.assertWhole(Tags.SHELL_STAGE); check("projects") }

    @Config(qualifiers = PAGE_WINDOW)
    @Test fun helpers() { scenes.helpers(); scenes.assertWhole(HelperTags.PAGE); check("helpers") }

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

    @Config(qualifiers = MENU_WINDOW)
    @Test fun chatExtras() {
        scenes.chatExtras()
        // The whole model menu, its effort and review chips included, shows above the dock and its fade.
        val menu = rule.onNodeWithTag(Tags.MODEL_MENU).fetchSemanticsNode().boundsInRoot
        val dock = rule.onNodeWithTag(Tags.DOCK).fetchSemanticsNode().boundsInRoot
        assertTrue("the model menu (${menu.bottom}) ends above the dock's fade (${dock.top})", menu.bottom <= dock.top - 24f * rule.density.density)
        rule.onNodeWithTag(Tags.REVIEW_OPTION + app.dshmobile.chat.ReviewRounds.MAX).assertIsDisplayed()
        check("chat-extras")
    }

    @Test fun chatBanners() { scenes.chatBanners(); check("chat-banners") }

    @Test fun chatExtrasReply() { scenes.chatExtrasReply(); check("chat-extras-reply") }

    @Config(qualifiers = TALL)
    @Test fun avatarDialog() { scenes.avatarDialog(); check("avatar-dialog") }

    @Test fun otherModeDialog() { scenes.otherModeDialog(); check("other-mode-dialog") }

    @Test fun svgZoom() { scenes.svgZoom(); check("svg-zoom") }
}
