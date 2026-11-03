package app.dshmobile.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.chat.SessionRows
import app.dshmobile.remote.AskQuestion
import app.dshmobile.remote.DshEvents
import app.dshmobile.remote.PendingPrompt
import app.dshmobile.ui.fog.FogButton
import app.dshmobile.ui.fog.FogButtonKind
import app.dshmobile.ui.fog.FogDimens
import app.dshmobile.ui.fog.FogMotion
import app.dshmobile.ui.fog.FogText
import app.dshmobile.ui.fog.LocalEffects
import app.dshmobile.ui.fog.cssLinear
import app.dshmobile.ui.fog.glow
import app.dshmobile.ui.theme.Sakura
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

const val ORIGIN_SUBAGENT = "子代理人"
const val ORIGIN_NEW_CONVERSATION = "新對話"

/**
 * Where a prompt comes from: a top-level conversation shows its title (an untitled one "新對話"), anything else whatever
 * [originOf] knows (Task 11: 子代理人「標籤」（來自 父對話標題）), else "子代理人". [isTopLevel] tells an untitled conversation from a subagent.
 */
fun promptOrigin(
    agentId: String,
    titleOf: (String) -> String?,
    originOf: (String) -> String? = { null },
    isTopLevel: (String) -> Boolean = { false },
): String =
    titleOf(agentId)?.takeIf { it.isNotBlank() }
        ?: if (isTopLevel(agentId)) ORIGIN_NEW_CONVERSATION else originOf(agentId)?.takeIf { it.isNotBlank() } ?: ORIGIN_SUBAGENT

private val SheetShape = RoundedCornerShape(FogDimens.Sheet)
private val Pill = RoundedCornerShape(50)

/**
 * The modal glass card for a permission request or the model's question. It cannot be dismissed (the scrim eats touches, back is
 * swallowed by [PromptHost]) and nothing answers it but the user. The reason or the questions scroll inside the card; the
 * buttons stay below them, so a long text never pushes them off the screen or under each other.
 */
@Composable
fun PromptSheet(
    prompt: PendingPrompt,
    origin: String,
    waitingCount: Int,
    busy: Boolean,
    error: String?,
    onAllowOnce: () -> Unit,
    onReject: () -> Unit,
    onAnswer: (Map<String, List<String>>) -> Unit,
    onDecline: () -> Unit,
    connecting: Boolean = false,
) {
    val animate = LocalEffects.current.pageMs > 0
    val rise = remember { Animatable(if (animate) 1f else 0f) }
    val scrim = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (animate) {
            launch { scrim.animateTo(1f, tween(FogMotion.SCRIM_MS)) }
            rise.animateTo(0f, tween(FogMotion.SHEET_MS, easing = FogMotion.Spring))
        }
    }
    // a new prompt (even in the same sheet) starts with nothing picked
    val selection = remember(prompt.eventId) { mutableStateMapOf<String, List<String>>() }
    val cardTitle = if (prompt is PendingPrompt.Approval) stringResource(R.string.prompt_approval_title, prompt.toolName) else stringResource(R.string.prompt_question_title)
    Box(Modifier.fillMaxSize().semantics { isTraversalGroup = true }) {
        // the scrim swallows every touch: the pages below are out of reach while dsh waits (a sibling of the card, so the
        // card's own semantics are not merged into the scrim's click)
        Box(
            Modifier
                .fillMaxSize()
                .semantics { traversalIndex = 1f } // after the card
                .background(Sakura.Text.copy(alpha = .28f * scrim.value))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(start = FogDimens.DockSide, end = FogDimens.DockSide, top = 24.dp, bottom = 12.dp)
                .graphicsLayer { translationY = rise.value * size.height * 1.15f }
                .glow(Sakura.RoseBrown.copy(alpha = .16f), 30.dp, SheetShape, dy = 12.dp)
                .clip(SheetShape)
                .drawWithCache {
                    val brush = cssLinear(140f, size, 0f to Color.White.copy(alpha = .88f), 1f to Sakura.Background.copy(alpha = .70f))
                    onDrawBehind { drawRect(Sakura.Background.copy(alpha = .6f)); drawRect(brush) } // the base keeps the dock from showing through the buttons
                }
                .border(1.2.dp, Color.White.copy(alpha = .95f), SheetShape)
                .semantics {
                    // announced when it appears, and read before anything else
                    paneTitle = cardTitle
                    liveRegion = LiveRegionMode.Polite
                    isTraversalGroup = true
                    traversalIndex = 0f
                }
                .testTag(Tags.PROMPT_SHEET)
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp),
        ) {
            val isApproval = prompt is PendingPrompt.Approval
            Text(
                stringResource(if (isApproval) R.string.prompt_approval_label else R.string.prompt_question_label),
                style = FogText.Label.copy(letterSpacing = 0.24.em),
            )
            Text(
                cardTitle,
                color = Sakura.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.em,
                modifier = Modifier.padding(top = 4.dp),
            )
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .padding(top = 12.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag(Tags.PROMPT_SCROLL),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (prompt) {
                    is PendingPrompt.Approval -> {
                        if (prompt.reason.isNotBlank()) Text(prompt.reason, color = Sakura.Text, fontSize = 15.sp, lineHeight = 24.sp)
                        Text(stringResource(R.string.prompt_origin, origin), style = FogText.Label)
                    }
                    is PendingPrompt.Question -> {
                        Text(stringResource(R.string.prompt_origin, origin), style = FogText.Label)
                        prompt.questions.forEach { q ->
                            QuestionBlock(q, selection[q.id].orEmpty(), busy) { label -> selection[q.id] = toggled(q, selection[q.id].orEmpty(), label) }
                        }
                    }
                }
            }
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp).testTag(Tags.PROMPT_ERROR))
            }
            if (connecting) {
                Text(stringResource(R.string.prompt_connecting), style = FogText.Label, modifier = Modifier.padding(top = 8.dp).testTag(Tags.PROMPT_CONNECTING))
            }
            if (waitingCount > 0) {
                Text(stringResource(R.string.prompt_more, waitingCount), style = FogText.Label, modifier = Modifier.padding(top = 8.dp).testTag(Tags.PROMPT_MORE))
            }
            if (prompt is PendingPrompt.Approval) {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FogButton(stringResource(R.string.prompt_allow_once), FogButtonKind.PINK, onAllowOnce, Modifier.weight(1f).testTag(Tags.APPROVE_ONCE), enabled = !busy)
                    FogButton(stringResource(R.string.prompt_reject), FogButtonKind.GHOST, onReject, Modifier.weight(1f).testTag(Tags.REJECT), enabled = !busy)
                }
            } else if (prompt is PendingPrompt.Question) {
                val complete = prompt.questions.all { it.options.isEmpty() || selection[it.id].orEmpty().isNotEmpty() }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FogButton(
                        stringResource(R.string.prompt_submit), FogButtonKind.PINK,
                        { onAnswer(prompt.questions.associate { it.id to selection[it.id].orEmpty() }) },
                        Modifier.weight(1f).testTag(Tags.QUESTION_SUBMIT), enabled = complete && !busy,
                    )
                    FogButton(stringResource(R.string.prompt_decline), FogButtonKind.GHOST, onDecline, Modifier.weight(1f).testTag(Tags.QUESTION_DECLINE), enabled = !busy)
                }
            }
        }
    }
}

/** The labels picked for [q] after tapping [label]: a multi-select toggles it (kept in option order), a single choice replaces. */
private fun toggled(q: AskQuestion, picked: List<String>, label: String): List<String> =
    if (!q.multiSelect) listOf(label)
    else q.options.filter { (it in picked) != (it == label) }

@Composable
private fun QuestionBlock(q: AskQuestion, picked: List<String>, busy: Boolean, onTap: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!q.header.isNullOrBlank()) {
            Text(
                q.header, style = FogText.Label,
                modifier = Modifier
                    .background(Color.White.copy(alpha = .6f), Pill)
                    .border(1.dp, Sakura.Primary.copy(alpha = .6f), Pill)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
        Text(q.question, color = Sakura.Text, fontSize = 15.sp, lineHeight = 24.sp)
        q.options.forEachIndexed { i, label ->
            val on = label in picked
            Box(
                Modifier
                    .fillMaxWidth()
                    .alpha(if (busy) .45f else 1f)
                    .heightIn(min = 48.dp)
                    .background(if (on) Sakura.Primary.copy(alpha = .38f) else Color.White.copy(alpha = .5f), Pill)
                    .border(1.dp, if (on) Sakura.Primary else Color.White.copy(alpha = .9f), Pill)
                    .clip(Pill)
                    .semantics { selected = on }
                    .clickable(enabled = !busy, role = if (q.multiSelect) Role.Checkbox else Role.RadioButton) { onTap(label) }
                    .padding(horizontal = 18.dp, vertical = 10.dp)
                    .testTag("${Tags.OPTION}${q.id}-$i"),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(label, color = Sakura.Text, fontSize = 14.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal)
            }
        }
    }
}

/**
 * Shows the first of [events]' pending prompts as a [PromptSheet] over everything (inside [FogShell]'s overlay slot) and
 * answers it through [events]; the next one appears when this one is answered. Back does nothing while a prompt waits. A failed
 * answer keeps the card with an error and enables the buttons again.
 */
@Composable
fun PromptHost(events: DshEvents?, tree: app.dshmobile.chat.SessionTree?, originOf: (String) -> String? = { null }) {
    val prompts by (events?.prompts ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList()) }).collectAsState()
    val prompt = prompts.firstOrNull() ?: return
    val clientId by (events?.clientId ?: remember { kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }).collectAsState()
    BackHandler(enabled = true) {} // dsh is waiting: back must not hide the card
    val scope = rememberCoroutineScope()
    var busyId by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    val state = tree?.state?.collectAsState()?.value
    val origin = promptOrigin(
        prompt.agentId,
        titleOf = { id -> state?.let { SessionRows.titleOf(it, id) } },
        originOf = originOf,
        isTopLevel = { id -> state?.let { SessionRows.isTopLevel(it, id) } == true },
    )
    val errorText = stringResource(R.string.prompt_error)
    fun send(block: suspend () -> Unit) {
        if (busyId != null || events == null) return
        busyId = prompt.eventId
        failed = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = prompt.eventId // never the exception text: it may carry prompt or protocol details
            } finally {
                busyId = null
            }
        }
    }
    PromptSheet(
        prompt = prompt,
        origin = origin,
        waitingCount = prompts.size - 1,
        busy = busyId == prompt.eventId,
        error = errorText.takeIf { failed == prompt.eventId },
        onAllowOnce = { (prompt as? PendingPrompt.Approval)?.let { p -> send { events?.allowOnce(p) } } },
        onReject = { (prompt as? PendingPrompt.Approval)?.let { p -> send { events?.reject(p) } } },
        onAnswer = { picked -> (prompt as? PendingPrompt.Question)?.let { p -> send { events?.answer(p, picked) } } },
        onDecline = { (prompt as? PendingPrompt.Question)?.let { p -> send { events?.decline(p) } } },
        connecting = busyId == prompt.eventId && clientId == null,
    )
}

/** True while a prompt waits: the shell hides everything under the card from accessibility then. */
@Composable
fun rememberHasPrompt(events: DshEvents?): Boolean {
    val prompts by (events?.prompts ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList()) }).collectAsState()
    return prompts.isNotEmpty()
}
