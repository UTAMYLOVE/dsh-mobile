package app.dshmobile.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import app.agency.core.plan.Screen
import app.dshmobile.ui.home.HomeScreen
import app.dshmobile.ui.home.HomeViewModel
import app.dshmobile.ui.onboarding.OnboardingFlow
import app.dshmobile.ui.pages.EvalPage
import app.dshmobile.ui.pages.LessonsPage
import app.dshmobile.ui.pages.MemoPage
import app.dshmobile.ui.pages.QuizPage
import app.dshmobile.ui.pages.SingingPage
import app.dshmobile.ui.pages.TimerPage
import app.dshmobile.ui.pages.TraineePage
import app.dshmobile.ui.settings.SettingsRoot
import app.dshmobile.ui.sheets.BodySheet
import app.dshmobile.ui.sheets.CheckInSheet
import app.dshmobile.ui.sheets.CheckOutSheet
import app.dshmobile.ui.sheets.MoneySheet
import app.dshmobile.ui.theme.paper
import kotlinx.coroutines.withContext

/** Where the owner is, above home. Home itself stays composed underneath so his half-typed words and scroll place stay. */
sealed interface Route {
    data object Lessons : Route
    data class Quiz(val lessonId: Long, val noteOnly: Boolean) : Route
    data class Timer(val task: String) : Route
    data object Memo : Route
    data object Trainee : Route
    data object Settings : Route
    data object Eval : Route
    data object Singing : Route
    data object CheckIn : Route
    data object CheckOut : Route
    data object Body : Route
    data object Money : Route
}

/** Panel state kept in plain app preferences (a per-viewer convenience, nothing private). */
class SharedPrefsPanelPrefs(context: Context) : PanelPrefs {
    private val prefs = context.applicationContext.getSharedPreferences("panels", Context.MODE_PRIVATE)
    override fun get(name: String, default: Boolean) = prefs.getBoolean(name, default)
    override fun set(name: String, open: Boolean) {
        prefs.edit().putBoolean(name, open).apply()
    }
}

private val StageRegex = Regex("第[ 　]*([0-9]+)[ 　]*階段")

/** The root: onboarding while `today().ready` is false, else home with pages and sheets on top. */
@Composable
fun UiRoot(graph: UiGraph, startRoutes: List<Route> = emptyList()) {
    val io = LocalIo.current
    val context = LocalContext.current
    var ready by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(graph) { ready = withContext(io) { graph.company.today().ready } }
    when (ready) {
        null -> Box(Modifier.fillMaxSize().paper())
        false -> OnboardingFlow(graph.onboarding, onFinished = { ready = true })
        true -> MainContent(graph, startRoutes, context)
    }
}

@Composable
private fun MainContent(graph: UiGraph, startRoutes: List<Route>, context: Context) {
    val scope = rememberCoroutineScope()
    val io = LocalIo.current
    val panels = graph.panels ?: remember { SharedPrefsPanelPrefs(context) }
    val vm = remember(graph) { HomeViewModel(graph.company, scope, panels, io) }
    val stack = remember { mutableStateListOf<Route>().also { it.addAll(startRoutes) } }
    val today by vm.today.collectAsState()
    val moodOpen = today?.moodOpen ?: ((today?.stageLabel?.let { StageRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 1) >= 3)

    fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        vm.refresh()
    }

    fun open(screen: Screen, arg: String) {
        when (screen) {
            Screen.LESSONS -> stack.add(Route.Lessons)
            Screen.QUIZ -> arg.toLongOrNull()?.let { stack.add(Route.Quiz(it, false)) } ?: stack.add(Route.Lessons)
            Screen.TIMER -> stack.add(Route.Timer(arg))
            Screen.CHECK_IN -> stack.add(Route.CheckIn)
            Screen.CHECK_OUT -> stack.add(Route.CheckOut)
            Screen.BODY -> stack.add(Route.Body)
            Screen.EVAL -> stack.add(Route.Eval)
            Screen.SINGING -> stack.add(Route.Singing)
            Screen.MEMO -> stack.add(Route.Memo)
            Screen.MONEY -> stack.add(Route.Money)
            Screen.CAMERA -> {}
        }
    }

    Box(Modifier.fillMaxSize()) {
        HomeScreen(vm, graph, onOpen = ::open, onTrainee = { stack.add(Route.Trainee) }, onSettings = { stack.add(Route.Settings) })
        val top = stack.lastOrNull()
        if (top != null) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) {}.testTag("overlay")) {
                when (top) {
                    Route.Lessons -> LessonsPage(graph.lessons, graph.company, ::pop, { id, note -> stack.add(Route.Quiz(id, note)) }, ::pop)
                    is Route.Quiz -> QuizPage(graph.lessons, top.lessonId, top.noteOnly, ::pop, ::pop)
                    is Route.Timer -> TimerPage(graph.timer, top.task, ::pop, ::pop)
                    Route.Memo -> MemoPage(graph.memo, ::pop)
                    Route.Trainee -> TraineePage(graph.trainee, graph.media, ::pop)
                    Route.Settings -> SettingsRoot(graph.settings, graph.backup, graph.oldData, ::pop)
                    Route.Eval -> EvalPage(graph.eval, ::pop, ::pop)
                    Route.Singing -> SingingPage(graph.singing, ::pop)
                    Route.CheckIn -> CheckInSheet(graph.company, ::pop, ::pop)
                    Route.CheckOut -> CheckOutSheet(graph.company, moodOpen, ::pop, ::pop)
                    Route.Body -> BodySheet(graph.body, graph.media, ::pop, ::pop)
                    Route.Money -> MoneySheet(graph.money, ::pop, ::pop)
                }
            }
        }
    }
}
