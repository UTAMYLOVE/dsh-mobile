package app.dshmobile.ui.agency

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agency.core.model.Message
import app.agency.core.model.Profile
import app.agency.core.persona.PromptInput
import app.agency.core.rules.CoachRules
import app.agency.core.time.AgencyClock
import app.dshmobile.R
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.ImportNotice
import app.dshmobile.agency.StartState
import app.agency.core.brain.handlers.OnboardingStatus
import app.dshmobile.ui.agency.onboarding.BaselineBadges
import app.dshmobile.ui.agency.onboarding.OnboardingFlow
import app.dshmobile.ui.kit.Pill
import app.dshmobile.ui.kit.Sakura
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 公司 (T06): a simple trainee header (the full idol card is T10), the import notice once, the one conversation (imported lines
 * with their role labels) and the composer.
 */
@Composable
fun HomeScreen(agency: AgencyApp, ready: StartState.Ready, actions: AgencyActions = AgencyActions()) {
    // collectOnMain / onMain below are defensive: on the phone these already run on the main thread; under the Compose test
    // rule they would not. Keep them (AgencyMainThreadTest fails without each).
    val revision by ready.brain.revision.collectOnMain()
    val busy by ready.brain.busy.collectOnMain()
    val notice by agency.notice.collectOnMain()
    var limit by rememberSaveable { mutableIntStateOf(PAGE) }
    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    LaunchedEffect(revision, limit) {
        onMain {
            val (list, count) = withContext(Dispatchers.IO) {
                val list = runCatching { ready.store.messages.recent(limit) }.getOrDefault(emptyList())
                list to runCatching { ready.store.messages.count() }.getOrDefault(list.size)
            }
            messages = list
            total = count
        }
    }
    val profile by produceState<Profile?>(null, revision) {
        onMain { value = withContext(Dispatchers.IO) { runCatching { ready.store.profile.get() }.getOrNull() } }
    }
    val onboarding by produceState<OnboardingStatus?>(null, revision) {
        onMain { value = withContext(Dispatchers.IO) { runCatching { ready.brain.onboardingStatus() }.getOrNull() } }
    }
    Column(Modifier.fillMaxSize().imePadding().testTag(AgencyTags.HOME)) {
        TraineeHeader(profile ?: Profile())
        onboarding?.let { status ->
            Row(Modifier.fillMaxWidth().background(Sakura.White).padding(horizontal = 16.dp).padding(bottom = 8.dp)) { BaselineBadges(agency, status) }
        }
        notice?.let { NoticeBar(it, agency::dismissNotice) }
        ChatList(messages, hasOlder = total > messages.size, onOlder = { limit += PAGE }, modifier = Modifier.weight(1f))
        onboarding?.let { OnboardingFlow(agency, it, actions) }
        Composer(busy = busy, onSend = agency::send)
    }
}

private const val PAGE = 500

@Composable
private fun TraineeHeader(p: Profile) {
    val day = joinDay(p, AgencyClock.system())
    Row(
        Modifier.fillMaxWidth().background(Sakura.White).padding(horizontal = 16.dp, vertical = 10.dp).testTag(AgencyTags.TRAINEE),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(p.companyName ?: PromptInput.DEFAULT_COMPANY, color = Sakura.Text, fontSize = 17.sp)
            p.stageName?.let { Text(it, color = Sakura.TextSecondary, fontSize = 13.sp) }
        }
        Pill(stringResource(R.string.ag_trainee, p.level))
        day?.let { Pill(stringResource(R.string.ag_join_day, it)) }
    }
}

/** 入社第 N 天: the join day is day 1; null without a join day. */
internal fun joinDay(p: Profile, clock: AgencyClock): Long? {
    val join = p.joinDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val today = CoachRules.coachDay(clock.now(), clock.zone, p.dayStartHour)
    return (ChronoUnit.DAYS.between(join, today) + 1).takeIf { it >= 1 }
}

@Composable
private fun NoticeBar(n: ImportNotice, onDismiss: () -> Unit) {
    val text = stringResource(
        when (n) {
            ImportNotice.PARTIAL -> R.string.ag_notice_partial
            ImportNotice.MEDIA_PENDING -> R.string.ag_notice_media
            ImportNotice.FAILED -> R.string.ag_notice_failed
        },
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).background(Sakura.Aqua.copy(alpha = .5f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp).testTag(AgencyTags.NOTICE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Sakura.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss, modifier = Modifier.testTag(AgencyTags.NOTICE_OK)) {
            Text(stringResource(R.string.ag_notice_ok), color = Sakura.Text)
        }
    }
}
