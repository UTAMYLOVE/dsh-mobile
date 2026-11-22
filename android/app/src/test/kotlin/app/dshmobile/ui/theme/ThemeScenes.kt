package app.dshmobile.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import app.dshmobile.DshMobileApp
import app.dshmobile.R
import app.dshmobile.agents.AgentStatus
import app.dshmobile.agents.AgentsRemote
import app.dshmobile.agents.Mood
import app.dshmobile.agents.Prop
import app.dshmobile.agents.RunningAgents
import app.dshmobile.agents.Stage
import app.dshmobile.agents.WarLogRow
import app.dshmobile.agents.WarRoomRecorder
import app.dshmobile.chat.ChatItem
import app.dshmobile.chat.DayGroup
import app.dshmobile.chat.FakeIndexRemote
import app.dshmobile.chat.Helper
import app.dshmobile.chat.HelperState
import app.dshmobile.chat.HelperTiming
import app.dshmobile.chat.InlineCard
import app.dshmobile.chat.LiveReply
import app.dshmobile.chat.NoticeKind
import app.dshmobile.chat.PendingSend
import app.dshmobile.chat.PresentedFile
import app.dshmobile.chat.Project
import app.dshmobile.chat.SessionRow
import app.dshmobile.chat.SessionTree
import app.dshmobile.chat.SessionUsage
import app.dshmobile.chat.Todo
import app.dshmobile.chat.TodoStatus
import app.dshmobile.chat.ToolStatus
import app.dshmobile.chat.WebSource
import app.dshmobile.chat.WorkspaceFile
import app.dshmobile.chat.sum
import app.dshmobile.config.AgentSettingsStore
import app.dshmobile.gateway.CostMeter
import app.dshmobile.gateway.InMemorySharedPreferences
import app.dshmobile.gateway.MutableClock
import app.agency.core.metering.PriceTable
import app.agency.core.metering.Provider
import app.dshmobile.library.LibraryStore
import app.dshmobile.overview.OverviewState
import app.dshmobile.overview.Reminder
import app.dshmobile.remote.AskQuestion
import app.dshmobile.remote.MuxState
import app.dshmobile.remote.PendingPrompt
import app.dshmobile.runtime.LaunchUrl
import app.dshmobile.runtime.NativeModeControl
import app.dshmobile.runtime.PermissionLevel
import app.dshmobile.runtime.UpdateOutcome
import app.dshmobile.security.KeyVault
import app.dshmobile.security.SessionToken
import app.dshmobile.service.DshHolderRuntime
import app.dshmobile.service.DshPhase
import app.dshmobile.service.DshStatus
import app.dshmobile.service.RunningDsh
import app.dshmobile.system.PhantomState
import app.dshmobile.ui.DshStatusView
import app.dshmobile.ui.LicensesPage
import app.dshmobile.ui.LicensesTags
import app.dshmobile.ui.LookSettings
import app.dshmobile.ui.LookTags
import app.dshmobile.ui.MainOps
import app.dshmobile.ui.MainUiState
import app.dshmobile.ui.SettingsEnv
import app.dshmobile.ui.SettingsJobs
import app.dshmobile.ui.SettingsScreen
import app.dshmobile.ui.SettingsVersions
import app.dshmobile.ui.agents.AgentsTab
import app.dshmobile.ui.agents.WarStrip
import app.dshmobile.ui.chat.BlockCache
import app.dshmobile.ui.chat.ChatDataPanel
import app.dshmobile.ui.chat.ChatHome
import app.dshmobile.ui.chat.ChatScreen
import app.dshmobile.ui.chat.ChatUiCache
import app.dshmobile.ui.chat.ChatUiState
import app.dshmobile.ui.chat.ContemplateCard
import app.dshmobile.ui.chat.ContextBar
import app.dshmobile.ui.chat.ConversationListScreen
import app.dshmobile.ui.chat.HelpersPage
import app.dshmobile.ui.chat.ListActions
import app.dshmobile.ui.chat.ListTab
import app.dshmobile.ui.chat.ListTabs
import app.dshmobile.ui.chat.LockedPage
import app.dshmobile.ui.chat.LongChatNudge
import app.dshmobile.ui.chat.PresentCard
import app.dshmobile.ui.chat.ProjectPage
import app.dshmobile.ui.chat.ProjectsTab
import app.dshmobile.ui.chat.ResearchCounts
import app.dshmobile.ui.chat.RowFlags
import app.dshmobile.ui.chat.TocEntry
import app.dshmobile.ui.chat.TocTags
import app.dshmobile.ui.chat.TodoBar
import app.dshmobile.ui.chat.modelChoices
import app.dshmobile.ui.common.SvgTags
import app.dshmobile.ui.fog.Backdrop
import app.dshmobile.ui.fog.BackdropStyle
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.Effects
import app.dshmobile.ui.fog.EffectsController
import app.dshmobile.ui.fog.FogBackground
import app.dshmobile.ui.fog.FogPanel
import app.dshmobile.ui.fog.FogSemantics
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.LocalBackdrop
import app.dshmobile.ui.fog.LocalLook
import app.dshmobile.ui.fog.LocalLookPicture
import app.dshmobile.ui.fog.Look
import app.dshmobile.ui.fog.LookController
import app.dshmobile.ui.fog.LookPictures
import app.dshmobile.ui.fog.LookStore
import app.dshmobile.ui.fog.UiPrefs
import app.dshmobile.ui.fog.effectsFor
import app.dshmobile.ui.fog.rememberBackdrop
import app.dshmobile.ui.library.LibraryDsh
import app.dshmobile.ui.library.LibraryScreen
import app.dshmobile.ui.markdown.MarkdownParser
import app.dshmobile.ui.overview.OverviewScreen
import app.dshmobile.ui.overview.WeatherCard
import app.dshmobile.ui.overview.WeatherTags
import app.dshmobile.ui.shell.AssistantChip
import app.dshmobile.ui.shell.AttachmentChip
import app.dshmobile.ui.shell.ComposerUi
import app.dshmobile.ui.shell.FogShell
import app.dshmobile.ui.shell.LocalPagePadding
import app.dshmobile.ui.shell.PromptSheet
import app.dshmobile.ui.shell.ShareSheet
import app.dshmobile.ui.shell.SharedContent
import app.dshmobile.ui.shell.ShellNav
import app.dshmobile.ui.shell.ShellTab
import app.dshmobile.ui.shell.StatusSeg
import app.dshmobile.ui.shell.Tags
import app.dshmobile.weather.Place
import app.dshmobile.weather.WeatherFetch
import app.dshmobile.weather.WeatherService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import androidx.lifecycle.ViewModelProvider
import app.dshmobile.BuildConfig
import app.dshmobile.config.DshPatch
import app.dshmobile.log.ErrorLog
import app.dshmobile.runtime.DesktopSignals
import app.dshmobile.runtime.RootfsInstaller
import app.dshmobile.service.DshController
import app.dshmobile.system.OnboardingState
import app.dshmobile.ui.MainActivity
import app.dshmobile.ui.MainViewModel
import org.robolectric.Robolectric
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

typealias SceneRule = AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>

/** What every scene is drawn inside: the theme (and nothing else). */
typealias SceneWrap = @Composable (@Composable () -> Unit) -> Unit

/**
 * The screens every theme test draws, built the same way each time from invented data (小雨, 主代理人, 研究員, 寫手, 審稿員) and
 * pictures made here. Each scene sets [rule]'s content inside [wrap] (by default `FogTheme(effects)`, today's CLASSIC look), waits
 * until nothing more will change, and remembers how [snap] must take its picture: the whole window, a tall page cropped to its
 * measured height (`scene-root`), a dialog's own window over the activity, or with the shell header's live clock blanked. A page
 * that fills its window is shown whole by giving its test a window tall enough and checking it with [assertWhole].
 *
 * Determinism: every scene sets the default time zone to Asia/Taipei ([close] puts the previous one back); the war scenes pin
 * Robolectric's clock to [NOW] before `setContent` (AgentsTab's `System.currentTimeMillis()` follows it only where Robolectric
 * instruments `app.dshmobile.ui.agents`: the war tests' `@Config(instrumentedPackages)`; [war] fails when it does not); text,
 * models and prices that depend on the wall clock are avoided (Claude models in the war room, whose price does not change with
 * the hour; forecasts dated 2099); random ids are replaced (the bookshelf); the more-features shell header's own clock, which
 * reads the JVM's clock, is blanked.
 */
class ThemeScenes(
    private val rule: SceneRule,
    val effects: Effects = SAVER,
    val wrap: SceneWrap = { c -> FogTheme(effects) { c() } },
) {
    /**
     * The same scenes worn in [theme]: every scene is drawn inside `FogTheme(effects, theme)`, and the scenes that launch
     * MainActivity (which picks its theme from the saved look) save [theme] as 主題 and 顏色 first.
     */
    constructor(rule: SceneRule, theme: Theme, effects: Effects = SAVER) : this(rule, effects, { c -> FogTheme(effects, theme) { c() } }) {
        worn = theme
    }

    /** The theme the scenes wear (CLASSIC unless made with a theme): what a launched MainActivity is told to wear. */
    var worn: Theme = Themes.Classic
        private set

    enum class Tone { LIGHT, MID, DARK }

    /** How [snap] takes the current scene's picture. */
    private class Shot(
        /** Crop to the bounds of the node with this tag ([ROOT]: a tall page measured at its full height). */
        val crop: String? = null,
        /** Draw the shown dialogs' windows over the activity's. */
        val dialogs: Boolean = false,
        /** Only the newest dialog's window (a full-screen dialog). */
        val dialogOnly: Boolean = false,
        /** Nodes painted grey from their start over [MASK_WIDTH_DP] (text that shows the real wall clock). */
        val masks: List<SemanticsMatcher> = emptyList(),
    )

    private var shot = Shot()
    private val scopes = ArrayList<CoroutineScope>()
    private var tempDir: File? = null
    private val temp: File get() = tempDir ?: Files.createTempDirectory("scenes").toFile().also { tempDir = it }

    /** The JVM's default time zone before the scenes set Asia/Taipei; [close] puts it back. */
    private val zone: TimeZone = TimeZone.getDefault()

    /**
     * Ends what the scenes started (the war room's session tree, a launched MainActivity, the temp files) and puts back the
     * default time zone, so Asia/Taipei never leaks into the test classes that run after this one; call from `@After`.
     */
    fun close() {
        main?.let { m -> runCatching { m.pause().stop().destroy() } }
        if (main != null) ApplicationProvider.getApplicationContext<DshMobileApp>().shellSessions.detach()
        scopes.forEach { it.cancel() }
        tempDir?.deleteRecursively()
        TimeZone.setDefault(zone)
    }

    // ---- taking the picture ------------------------------------------------------------------------------------------------

    /**
     * The current scene as a bitmap (the decorView drawn as ChatHomeTest does, plus the scene's dialogs, crop and masks); also
     * written to `<dshm.shots>/<name>.png` when that property is set.
     */
    fun snap(name: String): Bitmap = snap(name, rows = null)

    /** [snap], cropped to [rows] of the window (the settings tab's halves) instead of the scene's own crop. */
    private fun snap(name: String, rows: IntRange?): Bitmap {
        rule.waitForIdle()
        val decor = rule.activity.window.decorView
        var bitmap = if (shot.dialogOnly) drawWindow(dialogWindows().last()) else drawWindow(decor)
        if (shot.dialogs && !shot.dialogOnly) {
            val canvas = Canvas(bitmap)
            val base = IntArray(2).also { decor.getLocationOnScreen(it) }
            dialogWindows().forEach { v ->
                val at = IntArray(2).also { v.getLocationOnScreen(it) }
                canvas.save()
                canvas.translate((at[0] - base[0]).toFloat(), (at[1] - base[1]).toFloat())
                rule.runOnUiThread { v.draw(canvas) }
                canvas.restore()
            }
        }
        if (shot.masks.isNotEmpty()) {
            // A fixed width from the text's start: the clock's own width changes with its digits.
            val canvas = Canvas(bitmap)
            val grey = Paint().apply { color = 0xFF808080.toInt() }
            val width = MASK_WIDTH_DP * rule.density.density
            shot.masks.forEach { m ->
                rule.onAllNodes(m, useUnmergedTree = true).bounds().forEach { r ->
                    canvas.drawRect(floor(r.left), floor(r.top), floor(r.left) + width, ceil(r.bottom), grey)
                }
            }
        }
        val crop: Rect? = when {
            rows != null -> Rect(0f, rows.first.toFloat(), bitmap.width.toFloat(), rows.last + 1f)
            // The node's own bounds, not clipped to the window. A page longer than its window either lies past it or is
            // squeezed into it, reaching its bottom edge: both fail below instead of turning into a cut golden.
            shot.crop != null -> rule.onNodeWithTag(shot.crop!!, useUnmergedTree = true).fetchSemanticsNode().let { n ->
                Rect(n.positionInWindow.x, n.positionInWindow.y, n.positionInWindow.x + n.size.width, n.positionInWindow.y + n.size.height)
            }
            else -> null
        }
        if (crop != null) {
            val left = crop.left.toInt()
            val top = crop.top.toInt()
            val right = Math.round(crop.right)
            val bottom = Math.round(crop.bottom)
            // A crop by tag must end inside the window: one that reaches its bottom edge may have been squeezed to fit it.
            val inside = if (rows != null) bottom <= bitmap.height else bottom < bitmap.height
            check(left >= 0 && top >= 0 && right <= bitmap.width && inside && right > left && bottom > top) {
                "$name does not fit its ${bitmap.width} × ${bitmap.height} window: $crop (give its test a taller window)"
            }
            bitmap = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        }
        System.getProperty("dshm.shots")?.let { dir -> PixelCompare.write(bitmap, File(dir, "$name.png")) }
        return bitmap
    }

    /**
     * Fails unless nothing under the node tagged [tag] (itself included) scrolls vertically: the window holds all of that page or
     * sheet, so its golden pins every row of it (plan: tall pages at their full measured height).
     */
    fun assertWhole(tag: String) {
        rule.waitForIdle()
        val scrolls = rule.onAllNodes(scrollsY and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))), useUnmergedTree = true)
            .fetchSemanticsNodes()
        check(scrolls.isNotEmpty()) { "nothing scrolls under $tag: the check would prove nothing" }
        scrolls.forEach { n ->
            val range = n.config[SemanticsProperties.VerticalScrollAxisRange]
            check(range.value() == 0f && range.maxValue() == 0f) {
                "a list under $tag scrolls (at ${range.value()} of ${range.maxValue()}): its window is too short for all of it"
            }
        }
    }

    /** The dialogs' windows (an application sub-window each, over the activity's), oldest first. */
    private fun dialogWindows(): List<android.view.View> {
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val views = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<*>
        return views.filterIsInstance<android.view.View>().filter {
            (it.layoutParams as? android.view.WindowManager.LayoutParams)?.type == android.view.WindowManager.LayoutParams.TYPE_APPLICATION
        }
    }

    private fun drawWindow(view: android.view.View): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(bitmap)) }
        return bitmap
    }

    private fun SemanticsNodeInteractionCollection.bounds(): List<Rect> = fetchSemanticsNodes().map { it.boundsInWindow }

    // ---- building blocks -----------------------------------------------------------------------------------------------------

    private fun show(shot: Shot = Shot(), wrapWith: SceneWrap = wrap, content: @Composable () -> Unit) {
        TimeZone.setDefault(TAIPEI)
        this.shot = shot
        rule.setContent { wrapWith { content() } }
        rule.waitForIdle()
    }

    /** The shell as MainActivity builds it, with [look] and [picture] behind it. */
    @Composable
    private fun Shell(
        nav: ShellNav = ShellNav(),
        composer: ComposerUi = composer(),
        status: MainUiState = MainUiState.Ready,
        connection: MuxState? = MuxState.OPEN,
        look: Look = Look(),
        picture: File? = null,
        overlay: @Composable () -> Unit = {},
        modal: Boolean = false,
        reconnectMs: Long = 2_000,
        page: @Composable (ShellTab) -> Unit,
    ) = CompositionLocalProvider(LocalLook provides look, LocalLookPicture provides picture) {
        FogShell(
            status = status, connection = connection, nav = nav, composer = composer, onRetry = {}, onExportErrorLog = {},
            overlay = overlay, modalOverlay = modal, reconnectChipDelayMs = reconnectMs, page = page,
        )
    }

    /**
     * A page on its own with the shell's side and top padding, on the plain pink ground ([backdrop]: on the app's backdrop, the
     * ground and its glows sized to the page, which glass panels sample). Tall pages are measured at their full height and
     * cropped to it ([ROOT]); [fill] pages take the whole window.
     */
    @Composable
    private fun Stage(fill: Boolean = false, backdrop: Boolean = false, content: @Composable () -> Unit) {
        val layer: Backdrop = rememberBackdrop()
        Box(if (fill) Modifier.fillMaxSize().testTag(ROOT) else Modifier.fillMaxWidth().testTag(ROOT)) {
            if (backdrop) FogBackground(layer, Modifier.matchParentSize()) else Box(Modifier.matchParentSize().background(Sakura.Background))
            CompositionLocalProvider(
                LocalBackdrop provides (if (backdrop) layer else null),
                LocalPagePadding provides PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            ) { content() }
        }
    }

    private fun waitFor(matcher: SemanticsMatcher, what: String = matcher.description) {
        rule.waitUntil(10_000) { rule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        check(rule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) { "never shown: $what" }
    }

    private fun waitForPicture() = waitFor(SemanticsMatcher.keyIsDefined(FogSemantics.Picture), "the background picture")

    /**
     * Waits until a dialog's window is up and laid out. Its layout runs on the main looper, which only the rule's waitForIdle
     * drives (waitUntil only advances the compose clock), hence the loop.
     */
    private fun waitForDialog() {
        val end = System.nanoTime() + 10_000_000_000L
        while (true) {
            rule.waitForIdle()
            if (dialogWindows().any { it.width > 0 && it.height > 0 && !it.isLayoutRequested }) break
            check(System.nanoTime() < end) { "no dialog came up: ${dialogWindows().map { "${it.width}×${it.height}" }}" }
            Thread.sleep(10)
        }
        rule.waitForIdle()
    }

    /** [file] recoloured as the background shows it, kept in LookPictures' cache, so the scene never waits on a decode. */
    private fun warm(file: File, look: Look) = runBlocking { LookPictures.load(file, look.picture, look.six, look.aqua) }

    // ---- chat ----------------------------------------------------------------------------------------------------------------

    /**
     * The chat page in the shell: a user bubble, a reply with Markdown (heading, list, code block, table) and an inline card, a
     * tool card running and one done, a search card, a present card, a three-piece long message, a notice line, a typing
     * bubble and a pending bubble. In a 320 × 2100 dp window all of it shows; at 320 × 640 the newest part does.
     */
    fun chat(look: Look = Look(), picture: File? = null) {
        val ui = ChatUiCache().apply { preParse(this, chatItems) }
        show {
            Shell(look = look, picture = picture, composer = composer(running = true)) {
                ChatScreen(
                    state = chatState(chatItems, live = LiveReply("a9", "", "", emptyList()), running = true, pending = listOf(PendingSend("r1", "小雨想再問一題", emptyList(), queued = true))),
                    onOpenList = {}, onPickModel = {}, onLink = {}, onShare = {}, onLoadOlder = {}, ui = ui,
                )
            }
        }
        if (picture != null) waitForPicture()
    }

    /** The chat at 粉嫩二次元 with every float on (petals, sparkles, hearts, clouds). */
    fun chatKawaii() = chat(look = Look(style = BackdropStyle.KAWAII, petals = true, sparkles = true, hearts = true, clouds = true))

    /** The chat over a MID test picture. */
    fun chatPicture(tone: Tone = Tone.MID) {
        val file = testPicture(temp, tone)
        val look = Look(picture = 1L)
        warm(file, look)
        chat(look, file)
    }

    /** 對話紀錄's home page: the greeting, 試試看 and the recent conversations. */
    fun home() = show {
        Shell { Box(Modifier.fillMaxSize().padding(LocalPagePadding.current)) { ChatHome(recentRows, onSuggest = {}, onOpen = {}, onSettings = {}) } }
    }

    /** The conversation list (pinned, today, yesterday, earlier; a running, a temporary, a project and a locked row). */
    fun list(sheetOpen: Boolean) {
        show { Shell(nav = ShellNav().apply { listOpen = true }) { ListPage() } }
        if (sheetOpen) rule.onNodeWithTag(Tags.SESSION_MENU + "a").performClick()
        rule.waitForIdle()
    }

    /** The list's 重新命名 card over it. */
    fun renameCard() {
        list(sheetOpen = true)
        rule.onNodeWithTag(Tags.RENAME).performScrollTo().performClick()
        rule.waitForIdle()
    }

    /** The list's 刪除 card over it. */
    fun deleteCard() {
        list(sheetOpen = true)
        rule.onNodeWithTag(Tags.DELETE).performScrollTo().performClick()
        rule.waitForIdle()
    }

    @Composable
    private fun ListPage(tabs: (@Composable () -> Unit)? = null, tabPage: (@Composable () -> Unit)? = null) =
        Box(Modifier.fillMaxSize().padding(LocalPagePadding.current)) {
            ConversationListScreen(
                listGroups, "", {}, {}, {}, { _, _ -> }, {}, {},
                pinned = listOf(row("p", "小雨的待辦", "週五前交報告")),
                flags = { id -> RowFlags(pinned = id == "p", locked = id == "c", temporary = id == "b", project = "台股".takeIf { id == "a" }) },
                actions = ListActions(onPin = { _, _ -> }, onLock = { _, _ -> }, onMove = {}, onShelf = {}, onExport = {}, onNewTemporary = {}),
                tabs = tabs, tabPage = tabPage, onSettings = {},
            )
        }

    /** The ＋ sheet open over the dock (attachments, the five modes, two assistants). */
    fun plusSheet() {
        val c = composer().copy(assistants = listOf(AssistantChip("p1", "台股研究員"), AssistantChip("p2", "小雨的寫手")))
        show { Shell(composer = c) { Box(Modifier.fillMaxSize().padding(LocalPagePadding.current)) { ChatHome(recentRows, {}, {}, {}) } } }
        rule.onNodeWithTag(Tags.ATTACH).performClick()
        rule.waitForIdle()
    }

    /** dsh asks before a command runs: the approval card over the chat. */
    fun prompt() = show {
        Shell(
            overlay = {
                PromptSheet(
                    PendingPrompt.Approval("e1", "s1", "bash", "c1", "自動審查覺得這一步有風險：會刪掉舊的報告"), "小雨的對話", 1, false, null,
                    onAllowOnce = {}, onReject = {}, onAnswer = {}, onDecline = {}, detail = "rm -rf reports/old",
                )
            },
            modal = true,
        ) { ShortChat() }
    }

    @Composable
    private fun ShortChat(toc: List<TocEntry> = emptyList()) = ChatScreen(
        state = chatState(shortItems), onOpenList = {}, onPickModel = {}, onLink = {}, onShare = {}, onLoadOlder = {}, toc = toc,
        onAddToShelf = if (toc.isEmpty()) null else ({}),
    )

    /** A long press on the owner's message: its menu (複製, 朗讀, 編輯…) in its own window over the chat. */
    fun messageMenu() {
        show(Shot(dialogs = true)) { Shell { ShortChat() } }
        rule.onNodeWithTag(Tags.USER_BUBBLE).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        waitForDialog()
    }

    /** ⋮ → 目錄: the contents drawer in its own window over the chat. */
    fun contents() {
        val toc = listOf(TocEntry(1, "e1", "小雨想知道週末天氣"), TocEntry(1, "e2", "週末天氣", heading = true), TocEntry(2, "e3", "記一筆午餐"))
        show(Shot(dialogs = true)) { Shell { ShortChat(toc) } }
        rule.onNodeWithTag(Tags.CHAT_MORE).performClick()
        rule.onNodeWithTag(TocTags.OPEN).performClick()
        waitForDialog()
    }

    /** The chat behind the more-features shell: Header (DSH MOBILE, its clock blanked), the tab row, the status line, the reconnecting chip. */
    fun chatMore() {
        val nav = ShellNav().apply { more = true; tab = ShellTab.CHAT }
        val status = listOf(StatusSeg("work", "思考中"), StatusSeg("model", "FLASH"), StatusSeg("context", "上下文 23%", .23f), StatusSeg("cost", "$0.03"))
        show(Shot(masks = listOf(headerClock))) {
            Shell(nav = nav, composer = composer().copy(status = status), connection = MuxState.RECONNECTING, reconnectMs = 0) { ShortChat() }
        }
        waitFor(hasTestTag(Tags.RECONNECTING))
    }

    /**
     * The chat's extras, part one: the model menu open with its effort and reviewer chips, and in the dock two attachment tiles,
     * the edit notice, the one-shot mode chip and the composer's error.
     */
    fun chatExtras() {
        val ui = ChatUiCache().apply { modelMenu = true }
        val thumb = testThumbnail()
        val c = composer().copy(
            chips = listOf(AttachmentChip("i1", "雲.png", true) { thumb }, AttachmentChip("f1", "報告.pdf", false)),
            notice = ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.edit_banner),
            mode = app.dshmobile.chat.ComposeMode.RESEARCH,
            error = "附件太大，請換一個小一點的檔案",
        )
        show {
            Shell(composer = c) {
                ChatScreen(
                    state = chatState(shortItems).copy(effort = "high", reviewRounds = 1),
                    onOpenList = {}, onPickModel = {}, onLink = {}, onShare = {}, onLoadOlder = {}, ui = ui,
                )
            }
        }
    }

    /**
     * The chat's banners: the follow banner, the error line, and the ↓ newest pill (the list scrolled up, then a new message
     * arrives below).
     */
    fun chatBanners() {
        val items = (1..12).flatMap { listOf(ChatItem.User("u$it", "第 $it 個問題", emptyList()), ChatItem.Assistant("a$it", it.toLong(), null, "第 $it 個回答")) }
        var state by mutableStateOf(chatState(items).copy(followError = "ended", sendError = "session/model-unavailable"))
        val ui = ChatUiCache().apply { scrollIndex = 6 }
        show { Shell { ChatScreen(state = state, onOpenList = {}, onPickModel = {}, onLink = {}, onShare = {}, onLoadOlder = {}, ui = ui) } }
        state = state.copy(items = state.items + ChatItem.User("u13", "新的問題", emptyList()))
        waitFor(hasTestTag(Tags.SCROLL_TO_NEWEST))
    }

    /** The chat's extras, part two: the reasoning shown, the stats line and 建議下一步 under the newest reply. */
    fun chatExtrasReply() {
        val items = listOf(
            ChatItem.User("e1", "小雨想寫一首短詩", emptyList()),
            ChatItem.Assistant("e2", 2, "先想主題：雨。\n再想節奏：三行。", "雨落在窗上\n小雨數著水珠\n一顆、兩顆、三顆", followUps = listOf("再寫一首", "換成英文", "加上標題")),
        )
        val ui = ChatUiCache().apply { expanded = setOf("think:a:e2") }
        show {
            Shell {
                ChatScreen(
                    state = chatState(items).copy(replyLines = mapOf("e2" to "DeepSeek Flash · 1.2 秒 · 320 tok")),
                    onOpenList = {}, onPickModel = {}, onLink = {}, onShare = {}, onLoadOlder = {}, ui = ui,
                )
            }
        }
    }

    /** The pull-out data panel on its own (its four tiles, the spirits, the speed curve, the decisions and the files). */
    fun dataPanel() = show(Shot(crop = ROOT)) {
        Stage {
            Column(Modifier.padding(LocalPagePadding.current)) {
                ChatDataPanel(
                    team = warAgents, speeds = listOf(42.0, 55.0, 61.0, 48.0, 70.0), costUsd = .031, contextFill = .23f, cacheHit = .64,
                    tools = listOf(
                        ChatItem.Tool("t1", "web_search", "台北 天氣", ToolStatus.DONE),
                        ChatItem.Tool("t2", "bash", "rm -rf data/", ToolStatus.FAILED, error = "denied by auto review"),
                        ChatItem.Tool("t3", "write", "notes/週報.md", ToolStatus.WAITING_APPROVAL),
                    ),
                    files = listOf(WorkspaceFile("notes/週報.md", 2_048, NOW), WorkspaceFile("data/記帳.xlsx", 18_432, NOW)),
                )
            }
        }
    }

    /** The context bar (上下文 and its fill) on its own: the app keeps it but no page shows it today. */
    fun contextBar() = show(Shot(crop = ROOT)) {
        Stage { Column(Modifier.padding(LocalPagePadding.current)) { ContextBar(.42f, "上下文 42% · 84K / 200K") } }
    }

    // ---- pages -------------------------------------------------------------------------------------------------------------

    /**
     * 外觀主題 as Settings shows it, over the backdrop at its full height: SettingsScreen's transparent Surface (whose
     * `contentColor = Sakura.Text` is what inks LookSettings' plain Text) around its Section (the glass panel).
     */
    fun settingsLook(controller: LookController = lookController()) = show(Shot(crop = ROOT)) {
        Stage(backdrop = true) {
            // SettingsScreen.kt:520: the settings tab's Surface is what gives LookSettings' plain Text its ink.
            Surface(color = Color.Transparent, contentColor = Sakura.Text) {
                Column(Modifier.padding(LocalPagePadding.current)) {
                    FogPanel(title = stringResource(R.string.set_section_look), en = stringResource(R.string.set_en_look), index = 3, modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { LookSettings(controller) }
                    }
                }
            }
        }
    }

    /**
     * LookSettings flat on the ground (no panel, no backdrop) under SakuraTheme, at its full height: from F6 on, the rows below
     * the new block are compared with this golden shifted by the block's height.
     */
    fun settingsLookFlat(picture: Boolean, kawaii: Boolean, wrapWith: SceneWrap = { c -> SakuraTheme { c() } }) {
        val c = lookController()
        if (kawaii) c.update { it.copy(style = BackdropStyle.KAWAII, petals = true, sparkles = true, hearts = true, clouds = true) }
        if (picture) setPicture(c)
        show(Shot(crop = ROOT), wrapWith) { Box(Modifier.background(Sakura.Background).testTag(ROOT)) { LookSettings(c) } }
    }

    /** 調整頭像: the avatar editor dialog, in its own window. */
    fun avatarDialog() {
        val c = lookController()
        setPicture(c)
        show(Shot(dialogOnly = true)) { Box(Modifier.background(Sakura.Background)) { LookSettings(c) } }
        rule.onNodeWithTag(LookTags.AVATAR).performClick()
        waitForDialog()
        waitFor(hasTestTag(LookTags.AVATAR_LIVE))
    }

    /** The settings tab as the shell shows it: embedded, 進階設定 open, over a test picture and 粉嫩二次元 (the app's own look). */
    fun settingsAll() {
        val app = ApplicationProvider.getApplicationContext<DshMobileApp>()
        app.look.update { it.copy(style = BackdropStyle.KAWAII, petals = true, sparkles = true, hearts = true, clouds = true) }
        if (!app.look.look.value.hasPicture) setPicture(app.look)
        warm(app.look.pictureFile, app.look.look.value)
        val env = settingsEnv()
        val nav = ShellNav().apply { tab = ShellTab.SETTINGS }
        show {
            Shell(nav = nav, look = app.look.look.value, picture = app.look.pictureFile) {
                SettingsScreen(env, onClose = null, contentPadding = LocalPagePadding.current, advancedOpen = true)
            }
        }
        waitForPicture()
    }

    /**
     * [settingsAll]'s upper half, at the top of the tab: from the shell's header down to 外觀主題's panel (金鑰, 代理人, 模型),
     * which F6's rows at the top of 外觀主題 never move. Its window must hold all of it above the dock.
     */
    fun snapSettingsUpper(name: String): Bitmap {
        val range = settingsScroll().config[SemanticsProperties.VerticalScrollAxisRange]
        check(range.value() == 0f) { "the settings tab is not at its top (${range.value()})" }
        val look = panelTop(R.string.set_section_look)
        check(look <= dockTop() - px(DOCK_FADE_DP)) { "$name: 外觀主題 starts at $look, under the dock's fade (${dockTop()}): give its test a taller window" }
        return snap(name, 0 until look)
    }

    /**
     * [settingsAll]'s lower half, scrolled to the end of the tab: from 效果強度's panel down to the dock (效果強度 … 套用並重啟).
     * Taken from the end, every row of it sits at the same place in the window, over the same backdrop, however much F6 adds to
     * 外觀主題 above it. Its window must hold all of it below the header.
     */
    fun snapSettingsLower(name: String): Bitmap {
        val range = settingsScroll().config[SemanticsProperties.VerticalScrollAxisRange]
        rule.onNode(settingsColumn, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, range.maxValue() - range.value()) }
        rule.waitForIdle()
        val end = settingsScroll().config[SemanticsProperties.VerticalScrollAxisRange]
        check(end.value() == end.maxValue()) { "the settings tab did not reach its end (${end.value()} of ${end.maxValue()})" }
        val effects = panelTop(R.string.set_section_effects)
        val stage = rule.onNodeWithTag(Tags.SHELL_STAGE, useUnmergedTree = true).fetchSemanticsNode().positionInWindow.y.roundToInt()
        check(effects >= stage + px(TOP_FADE_DP)) { "$name: 效果強度 starts at $effects, under the header's fade ($stage): give its test a taller window" }
        return snap(name, effects until dockTop())
    }

    /** The settings tab's own scrolling column (the only vertical scroll on the stage). */
    private val settingsColumn = scrollsY and hasAnyAncestor(hasTestTag(Tags.SHELL_STAGE))

    private fun settingsScroll() = rule.onAllNodes(settingsColumn, useUnmergedTree = true).fetchSemanticsNodes().single()

    /** The top of the settings panel titled [title] in the window: its title row sits [PANEL_TOP_DP] inside the glass. */
    private fun panelTop(title: Int): Int {
        val text = ApplicationProvider.getApplicationContext<android.content.Context>().getString(title)
        val heading = rule.onAllNodes(hasText(text, substring = false) and hasAnyAncestor(hasTestTag(Tags.SHELL_STAGE)), useUnmergedTree = true)
            .fetchSemanticsNodes().single()
        return heading.positionInWindow.y.roundToInt() - px(PANEL_TOP_DP)
    }

    private fun dockTop(): Int = rule.onNodeWithTag(Tags.DOCK, useUnmergedTree = true).fetchSemanticsNode().positionInWindow.y.roundToInt()

    private fun px(dp: Int): Int = Math.round(dp * rule.density.density)

    /** The open-source licences page; [open]: that licence's full text shown, and the picture cropped to it. */
    fun licences(open: String? = null) {
        show(Shot(crop = open?.let { LicensesTags.TEXT + it })) { LicensesPage(onClose = {}) }
        if (open != null) {
            rule.onNodeWithTag(LicensesTags.SHOW + open).performClick()
            rule.waitForIdle()
        }
    }

    /** A locked conversation: 「這個對話已鎖定」 and 解鎖. */
    fun lock() = show { Shell { LockedPage(onUnlock = {}) } }

    /** Something shared from another app: the share sheet over the shell. */
    fun shareSheet() = show {
        Shell(overlay = { ShareSheet(SharedContent("小雨分享的一段文字：週末去看海", null, emptyList()), {}, {}, {}, {}) }, modal = true) {
            Box(Modifier.fillMaxSize().padding(LocalPagePadding.current)) { ChatHome(recentRows, {}, {}, {}) }
        }
    }

    /** dsh is not ready: the status panel instead of the chat ([loading]: still starting, else could not start). */
    fun statusPanel(loading: Boolean) = show {
        Shell(status = if (loading) MainUiState.Loading else MainUiState.Error) { Box(Modifier.fillMaxSize()) }
    }

    /** 總覽 at its full height: the spend ring and cells, the weather card, the war strip, the agents and heartbeat, reminders, recent. */
    fun overview() {
        val weather = weatherService()
        show(Shot(crop = ROOT)) {
            Stage {
                OverviewScreen(
                    overviewState, {}, {}, weather = { WeatherCard(weather) },
                    warRoom = { WarStrip(warAgents, warLog, DshStatus(DshPhase.STOPPED, 0)) {} },
                )
            }
        }
        waitFor(hasTestTag(WeatherTags.CHART))
    }

    /** The weather card on its own (a report from fixed, invented forecasts). */
    fun weatherCard() {
        val weather = weatherService()
        show(Shot(crop = ROOT)) { Stage { Column(Modifier.padding(LocalPagePadding.current)) { WeatherCard(weather) } } }
        waitFor(hasTestTag(WeatherTags.CHART))
    }

    /** 書架: two books and a character card. */
    fun library() {
        val file = File(temp, "lib-${System.nanoTime()}/" + LibraryStore.RELATIVE_PATH)
        LibraryStore(file, clock = { NOW }).apply {
            newCard("小雨", "愛吃草莓，怕打雷")
            addBook("霧城", "s1", null, "deepseek-v4-pro")
            addBook("小雨的夏天", "s2", null, "claude-sonnet-5-5")
        }
        // A book's cover colour comes from its id, a random UUID: fixed ids, so the shelf looks the same every run.
        var n = 0
        file.writeText(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").replace(file.readText()) { "id-" + (++n) })
        val store = LibraryStore(file, clock = { NOW })
        check(store.state.value.books.all { it.id.startsWith("id-") }) { "the shelf's ids: ${store.state.value.books.map { it.id }}" }
        val dsh = LibraryDsh(startBook = { _, _ -> "s3" }, conversation = { MutableStateFlow(app.dshmobile.chat.ConversationState(it)) }, send = { _, _ -> }, openChat = {})
        show { Stage(fill = true) { Box(Modifier.padding(LocalPagePadding.current)) { LibraryScreen(store, dsh, listOf("deepseek-v4-pro" to "DeepSeek V4 Pro"), "deepseek-v4-pro") } } }
    }

    /** A project's page inside the list (the tab row, its instructions, files and conversations); the more-features shell. */
    fun project() = show(Shot(masks = listOf(headerClock))) {
        Shell(nav = ShellNav().apply { more = true; listOpen = true }) {
            ListPage(
                tabs = { ListTabs(ListTab.PROJECTS, {}, helpers = 2) },
                tabPage = {
                    ProjectPage(
                        Project("tw", "台股研究", "每天整理外資動向，用表格回答。", assistant = true, template = null, createdAt = NOW, files = listOf("名單.csv", "筆記.md")),
                        listOf(row("a", "外資連三日買超"), row("b", "法說會重點")),
                        onBack = {}, onSave = {}, onAssistant = {}, onAddFile = {}, onRemoveFile = {}, onOpenChat = {}, onNewChat = {},
                    )
                },
            )
        }
    }

    /** The list's 專案 tab: the project tiles and the templates; the more-features shell. */
    fun projects() = show(Shot(masks = listOf(headerClock))) {
        Shell(nav = ShellNav().apply { more = true; listOpen = true }) {
            ListPage(
                tabs = { ListTabs(ListTab.PROJECTS, {}, helpers = 2) },
                tabPage = {
                    ProjectsTab(
                        listOf(
                            Project("tw", "台股研究", "每天整理外資動向。", assistant = true, template = null, createdAt = NOW),
                            Project("rain", "小雨的寫作", "", assistant = false, template = null, createdAt = NOW),
                        ),
                        assistantsOnly = false, chatsOf = { if (it == "tw") 3 else 0 }, onOpen = {}, onCreate = { _, _ -> },
                    )
                },
            )
        }
    }

    /** 小幫手: an active, a paused and a lost helper, and the form. */
    fun helpers() = show {
        Stage(fill = true) {
            Box(Modifier.padding(LocalPagePadding.current)) {
                HelpersPage(
                    listOf(
                        Helper("h1", "s1", "晨報", "整理外資動向和天氣", HelperTiming.Daily("07:50")),
                        Helper("h2", "s2", "週記", "幫小雨寫一週回顧", HelperTiming.Weekly(5, "21:00"), paused = true),
                        Helper("h3", "s3", "提醒喝水", "提醒小雨喝水", HelperTiming.Every(60)),
                    ),
                    stateOf = { when (it.id) { "h2" -> HelperState.PAUSED; "h3" -> HelperState.LOST; else -> HelperState.ACTIVE } },
                    lastLineOf = { if (it.id == "h1") "今天晴，外資買超 120 億" else null },
                    now = NOW, onOpen = {}, onToggle = { _, _ -> }, onRecreate = {}, onDelete = {}, onCreate = { _, _, _ -> },
                )
            }
        }
    }

    /** dsh's detailed status in Settings (SettingsScreen.kt:546, inside the tab's Surface at :520): a slow health check, with 強制結束 offered. */
    fun dshStatus() = show(Shot(crop = ROOT)) {
        Stage {
            // SettingsScreen.kt:520: the settings tab's Surface is what gives DshStatusView's plain Text its ink.
            Surface(color = Color.Transparent, contentColor = Sakura.Text) {
                Column(Modifier.padding(LocalPagePadding.current)) {
                    DshStatusView(DshStatus(DshPhase.HEALTH_CHECK, sinceMs = 0, port = 38123), onForceRestart = {}, onOpenAppSettings = {}, now = { 95_000L })
                }
            }
        }
    }

    /** The cards above and under the conversation: the long-chat nudge, the todo bar (open) and the three-stance card. */
    fun workCards() = show(Shot(crop = ROOT)) {
        Stage {
            Column(Modifier.padding(LocalPagePadding.current), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LongChatNudge(onNew = {}, onLater = {})
                TodoBar(listOf(Todo("查外資動向", TodoStatus.DONE), Todo("整理法說會重點", TodoStatus.IN_PROGRESS), Todo("寫成報告", TodoStatus.PENDING)), expanded = true, onToggle = {})
                ContemplateCard(listOf(agent("s1", "樂觀派", Mood.DONE), agent("s2", "保守派", Mood.RUNNING), agent("s3", "數據派", Mood.DONE)))
            }
        }
    }

    /** dsh's plan review card (計畫好了，要開始做嗎？) over the chat. */
    fun planCard() = show {
        Shell(
            overlay = {
                PromptSheet(
                    PendingPrompt.Question(
                        "e1", "s1",
                        listOf(AskQuestion("plan-review", "Plan review", "Approve?", listOf("Approve", "Keep planning"), false, "# 研究計畫\n1. 查外資\n2. 寫週報", true, "Approve")),
                    ),
                    "小雨的對話", 0, false, null, {}, {}, {}, {}, onPlan = { _, _ -> },
                )
            },
            modal = true,
        ) { ShortChat() }
    }

    /** What `present` handed over: files with an inline drawing (and one that cannot be read), and a deep-research report. */
    fun resultCards() {
        val ws = workspace()
        show(Shot(crop = ROOT)) {
            Stage {
                Column(Modifier.padding(LocalPagePadding.current), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PresentCard("c1", presentedFiles, null, ws, {}, {})
                    PresentCard("c2", listOf(PresentedFile("reports/外資動向.md", "深度研究報告")), ResearchCounts(4, 9), ws, {}, {})
                }
            }
        }
        waitFor(hasTestTag(SvgTags.IMAGE))
        waitFor(hasTestTag(SvgTags.BROKEN))
    }

    /** A drawing opened full screen (its own window). */
    fun svgZoom() {
        val ws = workspace()
        show(Shot(dialogOnly = true)) { Stage(fill = true) { PresentCard("c1", presentedFiles.take(1), null, ws, {}, {}) } }
        waitFor(hasTestTag(SvgTags.IMAGE))
        rule.onNodeWithTag(SvgTags.IMAGE, useUnmergedTree = true).performClick()
        waitForDialog()
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(SvgTags.IMAGE), useUnmergedTree = true).fetchSemanticsNodes().size >= 2 }
        rule.waitForIdle()
    }

    /**
     * The other-mode question (a conversation made in the other runtime mode; MainActivity's own AlertDialog), in its window:
     * MainActivity with setup finished and a dsh that never starts, its view model asked as a send to such a conversation does.
     */
    fun otherModeDialog() {
        TimeZone.setDefault(TAIPEI)
        shot = Shot(dialogOnly = true)
        val app = ApplicationProvider.getApplicationContext<DshMobileApp>()
        val state = OnboardingState(app.getSharedPreferences(OnboardingState.PREFS, android.content.Context.MODE_PRIVATE))
        OnboardingState.Step.values().forEach { state.markDone(it) }
        File(app.filesDir, "rootfs/${RootfsInstaller.VERSION_MARKER}").apply { parentFile!!.mkdirs() }.writeText(BuildConfig.ROOTFS_VERSION)
        val noKeys = object : KeyVault {
            override fun get(p: Provider): String? = null
            override fun put(p: Provider, key: String) = Unit
            override fun clear(p: Provider) = Unit
        }
        swap(app, "keyVault", object : KeyVault {
            override fun get(p: Provider): String? = "sk-test-key-0123456789"
            override fun put(p: Provider, key: String) = Unit
            override fun clear(p: Provider) = Unit
        })
        val dir = File(temp, "dsh").apply { File(this, "rootfs/etc").mkdirs() }
        swap(
            app, "controller",
            DshController(
                patchProvider = { DshPatch.render(it) }, filesDir = dir, isRootfsInstalled = { true },
                launcher = { _, _ -> throw java.io.IOException("no dsh in the theme scenes") },
                vault = noKeys, meter = CostMeter(InMemorySharedPreferences(), PriceTable(), clock = MutableClock()),
                errorLog = ErrorLog(dir), healthCheck = { true }, dnsServers = { emptyList() },
                onNotify = {}, onCostAlert = {}, onRateGuard = {}, launchTimeoutMs = 10_000,
                killer = DesktopSignals.killer(listOf("dshm-scenes-none-${System.nanoTime()}")),
            ),
        )
        // MainActivity wears the saved 主題 (跟著開場 would be 櫻紋陣): tell it the scenes' theme.
        app.look.update { it.copy(theme = worn.id, themeColors = worn.mode) }
        app.coachSettings.openOnCoach = false // the scenes show the chat shell, not the coach
        val activity = Robolectric.buildActivity(MainActivity::class.java, android.content.Intent().setComponent(android.content.ComponentName(app, MainActivity::class.java)))
            .also { main = it }.setup().get()
        rule.waitForIdle()
        rule.runOnUiThread { ViewModelProvider(activity)[MainViewModel::class.java].otherMode = true }
        waitForDialog()
    }

    private var main: org.robolectric.android.controller.ActivityController<MainActivity>? = null

    /** Replaces one of the app's lazy parts (MainActivityTest's way). */
    private fun swap(app: DshMobileApp, field: String, value: Any) {
        DshMobileApp::class.java.getDeclaredField("$field\$delegate").apply { isAccessible = true }.set(app, lazyOf(value))
    }

    // ---- the war room ------------------------------------------------------------------------------------------------------

    /**
     * 指揮室: AgentsTab with a fake recorder and three agents (主代理人 running, 研究員 running, 寫手 waiting; WarRoomPinkTest's
     * input), at its full height. The Robolectric clock is pinned to [NOW] first, so its clock reads [clockText].
     */
    fun war(listMode: Boolean = false) {
        SystemClock.setCurrentTimeMillis(NOW)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val remote = FakeIndexRemote(
            listOf(
                sum("lead", running = true, title = "主代理人", model = "claude-sonnet-5-5", updatedAt = 3),
                sum("k1", parent = "lead", origin = "subagent", running = true, model = "claude-sonnet-5-5"),
                sum("k2", parent = "lead", origin = "subagent", running = false, model = "claude-haiku-5-5"),
            ),
        )
        remote.control(warBaseline)
        val tree = SessionTree(remote, scope)
        val agents = RunningAgents(tree, object : AgentsRemote { override suspend fun interruptSubagent(childSessionId: String, parentSessionId: String) {} }, scope)
        val deadline = System.nanoTime() + 10_000_000_000L
        while ((tree.state.value.summaries.size < 3 || tree.state.value.projections.size < 3 || agents.rows.value.size < 2) && System.nanoTime() < deadline) Thread.sleep(5)
        check(tree.state.value.projections.size == 3) { "the war room's tree never loaded" }
        check(agents.rows.value.size >= 2) { "the war room's agents never loaded (${agents.rows.value.size} rows)" }
        var t = NOW - 60_000
        val recorder = WarRoomRecorder(clock = { t })
        warSamples(tree.state.value).forEach { s -> recorder.sample(s); t += 5_000 }
        t = NOW - 40_000
        recorder.note("派出", "研究員 · 台股新聞")
        t = NOW - 25_000
        recorder.note("派出", "寫手 · 週報")
        t = NOW - 10_000
        recorder.note("錯誤", "rm -rf data/ · 已擋下")
        show(Shot(crop = ROOT)) {
            Stage {
                AgentsTab(
                    agents, tree, scope, 6, onOpenParent = {}, recorder = recorder, openId = "lead", lead = flowOf(null),
                    todayUsd = 1.85, limits = "並行上限 6 · 團隊 3 · 深度 2", alertUsd = 5.0, team = 3,
                )
            }
        }
        // AgentsTab's clock follows the pinned SystemClock only where Robolectric instruments the war room's package.
        check(rule.onAllNodes(hasText("● " + clockText())).fetchSemanticsNodes().isNotEmpty()) {
            "the war room's clock is not pinned to ThemeScenes.NOW (${clockText()}): " +
                "add @Config(instrumentedPackages = [\"app.dshmobile.ui.agents\"]) until F10's AgentsTab(now)"
        }
        if (listMode) {
            rule.onNodeWithTag(Tags.WAR_LIST_TOGGLE).performClick()
            rule.waitForIdle()
        }
    }

    /** The war room's 清單模式 (AgentsScreen), as the page's first row toggles it. */
    fun agentsList() = war(listMode = true)

    // ---- the settings page's fakes (SettingsScreenTest's fixtures) -----------------------------------------------------------

    private fun settingsEnv(): SettingsEnv {
        val vault = object : KeyVault {
            override fun get(p: Provider): String? = if (p == Provider.DEEPSEEK) "sk-deepseek-0123456789" else null
            override fun put(p: Provider, key: String) = Unit
            override fun clear(p: Provider) = Unit
        }
        val runtime = object : DshHolderRuntime {
            override fun retain(owner: String) = Unit
            override fun release(owner: String) = Unit
            override fun stopIfUnretained(): Boolean = false
            override fun stop() = Unit
            override suspend fun ensureRunning(): RunningDsh = RunningDsh(1, 2, SessionToken.generate(), LaunchUrl("http://127.0.0.1:1/?token=x", 1))
        }
        val versions = object : SettingsVersions {
            override fun current(): String = "0.2.1-rc.5"
            override fun previous(): String? = "0.2.0"
            override fun verified(): List<String> = listOf("0.2.1-rc.5")
            override fun updateTarget(): String? = "0.2.2"
            override suspend fun checkUpdate(): UpdateOutcome = UpdateOutcome.UpToDate
            override suspend fun rollback(): MainOps.RollbackOutcome = MainOps.RollbackOutcome.NoPrevious
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        val effects = EffectsController(UiPrefs(InMemorySharedPreferences()))
        return SettingsEnv(
            vault = vault, store = AgentSettingsStore(InMemorySharedPreferences()),
            meter = CostMeter(InMemorySharedPreferences(), PriceTable(), clock = MutableClock()),
            phantomState = { PhantomState.ENABLED }, runtime = runtime, isRunning = MutableStateFlow(true), versions = versions,
            applyAgentTeams = { true }, scheduleHeartbeat = {}, supervise = {}, openDeveloperSettings = {},
            exportErrorLog = {}, exportMemory = {}, exportWorkspace = {}, jobs = SettingsJobs(scope, runtime),
            effectLevel = effects.level, onEffectLevel = { effects.set(it) }, effectiveLevel = effects.effective,
            nativeMode = NativeModeControl(available = true, enabled = MutableStateFlow(true), set = {}, permission = MutableStateFlow(PermissionLevel.SMART)),
        )
    }

    private fun lookController() = LookController(LookStore(InMemorySharedPreferences()), File(temp, "look-" + System.nanoTime()), now = { NOW })

    private fun setPicture(c: LookController) {
        val file = testPicture(temp, Tone.MID)
        check(runBlocking { c.setPicture { file.inputStream() } }) { "the test picture was refused" }
        warm(c.pictureFile, c.look.value)
    }

    private fun workspace(): File = File(temp, "ws-" + System.nanoTime()).apply {
        File(this, "drawings/雨滴.svg").apply { parentFile!!.mkdirs() }.writeText(
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 120"><rect width="200" height="120" fill="#F5E6EE"/>""" +
                """<circle cx="60" cy="60" r="34" fill="#DC9FB4"/><circle cx="130" cy="50" r="24" fill="#A8D8DC"/>""" +
                """<path d="M10 100 Q55 80 100 100 T190 100" stroke="#7D5F60" stroke-width="4" fill="none"/></svg>""",
        )
        File(this, "drawings/壞掉的.svg").writeText("<svg nope")
    }

    private fun weatherService(): WeatherService {
        val prefs = InMemorySharedPreferences()
        val ws = File(temp, "weather-" + System.nanoTime()).apply { mkdirs() }
        // Forecasts dated 2099, so "now" is always before them and the report never depends on the real clock.
        val times = (0 until 48).map { String.format(Locale.US, "2099-01-%02dT%02d:00", 1 + it / 24, it % 24) }
        fun series(base: Double, step: Double) = (0 until 48).joinToString(",") { String.format(Locale.US, "%.1f", base + step * (it % 24) - (if (it % 24 > 14) (it % 24 - 14) * .5 else 0.0)) }
        val t = times.joinToString(",") { "\"$it\"" }
        val rain = (0 until 48).joinToString(",") { if (it % 24 in 14..17) "1.5" else "0" }
        val forecast = """{"hourly":{"time":[$t],
            "temperature_2m_ecmwf_ifs025":[${series(18.0, .4)}],"precipitation_ecmwf_ifs025":[$rain],
            "temperature_2m_gfs_seamless":[${series(18.5, .38)}],"precipitation_gfs_seamless":[$rain],
            "temperature_2m_icon_seamless":[${series(17.8, .42)}],"precipitation_icon_seamless":[$rain]}}"""
        val members = (1..10).joinToString(",") { m ->
            "\"precipitation_member%02d\":[%s]".format(m, (0 until 48).joinToString(",") { h -> if (h % 24 in 14..16 && m <= 7) "2.0" else "0" })
        }
        val ensemble = """{"hourly":{"time":[$t],$members}}"""
        val fetch = WeatherFetch { url ->
            Json.parseToJsonElement(if (url.contains("ensemble-api")) ensemble else forecast)
        }
        return WeatherService(fetch, prefs, ws, cwaKey = { null }, clock = { NOW }).apply { choose(Place("台北", 25.03, 121.56, "臺北市")) }
    }

    private fun testThumbnail() = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { b ->
        Canvas(b).apply {
            drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 64f, 64f, 0xFFA8D8DC.toInt(), 0xFFDC9FB4.toInt(), Shader.TileMode.CLAMP) })
            drawCircle(40f, 26f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        }
    }.asImageBitmap()

    companion object {
        /** 2026-11-12 13:30:45 in Taipei: the war room's clock and every timestamp the scenes show. */
        val NOW: Long = Instant.parse("2026-11-12T05:30:45Z").toEpochMilli()
        val TAIPEI: TimeZone = TimeZone.getTimeZone("Asia/Taipei")
        val SAVER: Effects = effectsFor(EffectLevel.SAVER, reducedMotion = true)
        val FULL: Effects = effectsFor(EffectLevel.FULL, reducedMotion = false)

        /** How wide a masked clock is blanked (the shell header's `yyyy.MM.dd  HH:mm` is at most ~150 dp). */
        const val MASK_WIDTH_DP = 200

        /** The tag of a tall page's root: [snap] crops to it. */
        const val ROOT = "scene-root"

        /** A node that scrolls vertically (a scrolling column or a lazy list). */
        private val scrollsY = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

        /** FogPanel's top padding (FogPanel.kt): a settings panel's title row starts this far inside its glass. */
        private const val PANEL_TOP_DP = 16
        /** FogShell's `DockFade`: the band above the dock over which a page fades out. */
        private const val DOCK_FADE_DP = 24
        /** FogShell's `TopFade`: the fade at the top of the page viewport, under the header. */
        private const val TOP_FADE_DP = 16

        /** [NOW] as the war room's clock shows it (WarOps' `HH:mm:ss` in Asia/Taipei). */
        fun clockText(): String = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Asia/Taipei")).format(Instant.ofEpochMilli(NOW))

        /**
         * A generated 256 × 384 picture (a vertical gradient with three circles) in [tone], written as PNG into [dir]: the only
         * pictures the theme tests use (never an owner's file).
         */
        fun testPicture(dir: File, tone: Tone): File {
            val (top, bottom, a, b, c) = when (tone) {
                Tone.LIGHT -> listOf(0xFFFFF7F2, 0xFFF3E4EC, 0xFFFFFFFF, 0xFFF6D7E2, 0xFFDDEFF1)
                Tone.MID -> listOf(0xFFB49AAE, 0xFF7E93A8, 0xFFE8C9D6, 0xFF5E6E86, 0xFFC7E3E5)
                Tone.DARK -> listOf(0xFF1E1B26, 0xFF2E2234, 0xFF4A3550, 0xFF14121A, 0xFF35505A)
            }.map { it.toInt() }
            val bitmap = Bitmap.createBitmap(256, 384, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, 384f, top, bottom, Shader.TileMode.CLAMP) })
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                drawCircle(80f, 110f, 56f, p.apply { color = a })
                drawCircle(190f, 210f, 44f, p.apply { color = b })
                drawCircle(110f, 300f, 64f, p.apply { color = c })
            }
            dir.mkdirs()
            return File(dir, "test-picture-${tone.name.lowercase()}-${System.nanoTime()}.png").also { PixelCompare.write(bitmap, it) }
        }

        // ---- invented data ---------------------------------------------------------------------------------------------------

        private val headerClock = SemanticsMatcher("the shell header's clock") { n ->
            n.config.getOrNull(SemanticsProperties.Text)?.any { Regex("""\d{4}\.\d{2}\.\d{2}\s+\d{2}:\d{2}""").matches(it.text) } == true
        }

        private fun composer(running: Boolean = false) =
            ComposerUi("", {}, enabled = true, canSend = false, running = running, onSend = {}, onStop = {})

        private fun row(id: String, title: String, last: String? = "最後一句", running: Boolean = false) =
            SessionRow(id, title, last, "deepseek-flash", null, NOW, running)

        private val recentRows = listOf(
            SessionRow("s1", "台股外資動向", "外資連三日買超", "deepseek-flash", null, 30, false),
            SessionRow("s2", "小雨的夏天 第三章", null, "claude-sonnet-5-5", null, 20, true),
            SessionRow("s3", "11 月記帳", "午餐 120", "deepseek-flash", null, 10, false),
        )

        private val listGroups = listOf(
            DayGroup.TODAY to listOf(row("a", "今天的外資動向", running = true), row("b", "臨時問一下")),
            DayGroup.YESTERDAY to listOf(row("c", "小雨的日記")),
            DayGroup.EARLIER to listOf(row("d", "", last = null)),
        )

        private fun chatState(
            items: List<ChatItem>, live: LiveReply? = null, running: Boolean = false, pending: List<PendingSend> = emptyList(),
        ) = ChatUiState("小雨的週末計畫", items, live, running, pending, modelChoices(hasClaudeKey = false, current = "deepseek-flash"), false, null)

        private val shortItems = listOf(
            ChatItem.User("e1", "小雨想知道**週末**天氣", emptyList()),
            ChatItem.Assistant("e2", 2, null, "## 週末天氣\n\n- 週六晴\n- 週日午後有雨"),
            ChatItem.User("e3", "幫我記一筆午餐 120", emptyList()),
            ChatItem.Assistant("e4", 4, null, "記好了：午餐 120 元。"),
        )

        private val markdownReply = """
            |## 週末天氣
            |
            |- 週六 **晴**，高溫 26°C
            |- 週日 *午後雷陣雨*
            |
            |```python
            |print("記得帶傘")
            |```
            |
            || 日 | 天氣 | 降雨 |
            ||---|---|---|
            || 六 | 晴 | 10% |
            || 日 | 雨 | 70% |
            |
            |午餐 120 元也記好了。
        """.trimMargin()

        /** Three long code blocks: more than 3,000 characters in three pieces, so the reply is drawn as three segments. */
        private val longReply = (1..3).joinToString("\n\n") { n ->
            "```text\n" + (1..3).joinToString("\n") { l -> "第 $n 段第 $l 行：" + "霧從河面升起，小雨走過長長的橋，數著橋下的燈。".repeat(15) } + "\n```"
        }

        private val presentedFiles = listOf(
            PresentedFile("drawings/雨滴.svg", "小雨畫的雨滴"),
            PresentedFile("drawings/壞掉的.svg", null),
            PresentedFile("deliverables/週報.md", "本週重點"),
            PresentedFile("deliverables/記帳.xlsx", null),
        )

        private val chatItems = listOf(
            ChatItem.User("e1", "幫我看看 **台北** 週末天氣，順便記一筆午餐", emptyList()),
            ChatItem.Assistant(
                "e2", 2, "先查天氣，再記帳。", markdownReply,
                cards = listOf(InlineCard(InlineCard.Type.LEDGER, listOf("項目" to "午餐", "金額" to "-120", "類別" to "餐飲", "本月支出" to "-5,480"))),
            ),
            ChatItem.Tool("c1", "bash", "python3 analyse.py", ToolStatus.RUNNING),
            ChatItem.Tool("c2", "read", "notes/台北.md", ToolStatus.DONE),
            ChatItem.Tool(
                "c3", "web_search", "台北 週末 天氣", ToolStatus.DONE,
                sources = listOf(WebSource("https://example.com/weather", "天氣小站"), WebSource("https://news.example.org/a/b", "")),
            ),
            ChatItem.Tool("c4", "present", "週報", ToolStatus.DONE, presented = listOf(PresentedFile("deliverables/週報.md", "本週重點"), PresentedFile("deliverables/記帳.xlsx", null))),
            ChatItem.Assistant("e5", 5, null, longReply),
            ChatItem.Notice("n6", NoticeKind.STOPPED, "已停止"),
        )

        /** The long reply is parsed up front (the app parses it off the main thread), so no frame shows its placeholder. */
        private fun preParse(ui: ChatUiCache, items: List<ChatItem>) {
            items.filterIsInstance<ChatItem.Assistant>().forEach { a -> ui.blocks.store("a:" + a.key, BlockCache.Entry(a.text, MarkdownParser.parse(a.text))) }
        }

        private fun agent(id: String, label: String, mood: Mood, lead: Boolean = false, stage: Stage = Stage.WRITE) = AgentStatus(
            id, if (lead) null else "lead", if (lead) 0 else 1, label, mood, stage, Prop.PEN,
            "撰寫 reports/news.md", if (mood == Mood.WAITING) "等你允許：write" else null, "claude-sonnet-5-5", "high",
            SessionUsage(inputTokens = 4_000, outputTokens = 900, turns = 1, steps = 3),
            .23f, 60_000, 0, "外資連三日買超", null, listOf("撰寫 reports/news.md ✓"), lead,
        )

        private val warAgents = listOf(
            agent("lead", "主代理人", Mood.RUNNING, lead = true, stage = Stage.PLAN),
            agent("k1", "研究員", Mood.RUNNING, stage = Stage.SEARCH),
            agent("k2", "寫手", Mood.WAITING),
        )

        private val warLog = listOf(
            WarLogRow(NOW - 40_000, "派出", "研究員 · 台股新聞", "第 1 層"),
            WarLogRow(NOW - 25_000, "完成", "審稿員 · 校對", "交回 900 tok"),
            WarLogRow(NOW - 10_000, "錯誤", "rm -rf data/", "已擋下"),
        )

        private val overviewState = OverviewState(
            deepseekUsd = 0.0089, claudeUsd = 0.0034, monthUsd = 12.5, alertUsd = 5.0, agentsRunning = 2, agentsLimit = 6,
            nextHeartbeatAtMs = NOW + 15 * 60_000L, heartbeatIntervalMin = 45, nowMs = NOW,
            reminders = listOf(Reminder("記得澆花", "週三下午", NOW)),
            recent = listOf(SessionRow("s1", "台北週末天氣", "晴", "DeepSeek", null, NOW, false), SessionRow("s2", "小雨的夏天", null, "Claude", null, NOW, false)),
            unverifiedDsh = "0.3.1",
        )

        private val warBaseline: String = run {
            fun values(label: String?, input: Int, output: Int, extra: String) =
                """{"values":{${label?.let { "\"subagent\":{\"label\":\"$it\"}," } ?: ""}"tokenUsage":{"uncachedInputTokens":$input,"outputTokens":$output},""" +
                    """"sessionStats":{"turns":1,"steps":3,"decodeMs":20000,"decodeTokens":$output},"contextPressure":{"projectedTokens":46000,"contextWindow":200000}$extra}}"""
            """{"type":"baseline","value":{"projections":{""" +
                """"lead":${values(null, 4000, 900, ",\"modelSelection\":{\"lastUsed\":{\"model\":\"claude-sonnet-5-5\",\"reasoningEffort\":\"high\"}}")},""" +
                """"k1":${values("研究員", 3000, 1200, ",\"subagentTiming\":{\"settledMs\":42000},\"turnOutline\":[{\"turn\":1,\"prompt\":\"查台股新聞\",\"response\":\"外資連三日買超\"}]")},""" +
                """"k2":${values("寫手", 1500, 400, ",\"subagentTiming\":{\"settledMs\":18000},\"inbox\":{\"user\":[{\"text\":\"等你\"}]}")}""" +
                "}}}"
        }

        /** Seven samples of the team's speed, rising and falling (each 5 s apart). */
        private fun warSamples(base: app.dshmobile.chat.SessionTreeState): List<app.dshmobile.chat.SessionTreeState> =
            listOf(800, 1200, 1500, 1100, 1700, 2100, 1900).map { tokens ->
                base.copy(projections = base.projections.mapValues { (_, p) ->
                    p + ("sessionStats" to Json.parseToJsonElement("""{"turns":1,"steps":3,"decodeMs":20000,"decodeTokens":$tokens}"""))
                })
            }
    }
}
