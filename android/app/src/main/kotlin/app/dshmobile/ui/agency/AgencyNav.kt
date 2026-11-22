package app.dshmobile.ui.agency

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.StartState
import app.dshmobile.ui.kit.LocalAccent
import app.dshmobile.ui.kit.MemberColour
import app.dshmobile.ui.kit.Panel
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.SakuraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.dshmobile.ui.settings.SettingsScreen
import app.dshmobile.ui.warroom.TodayLinksCard
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

/** Test tags of the company's screens. */
object AgencyTags {
    const val OPENING = "ag-opening"
    const val IMPORTING = "ag-importing"
    const val LOCKED = "ag-locked"
    const val UNAVAILABLE = "ag-unavailable"
    const val RETRY = "ag-retry"
    const val HOME = "ag-home"
    const val WAR_ROOM = "ag-war-room"
    const val SETTINGS = "ag-settings"
    const val TAB = "ag-tab-"
    const val CHAT = "ag-chat"
    const val BUBBLE = "ag-bubble-"
    const val COMPOSER = "ag-composer"
    const val SEND = "ag-send"
    const val NOTICE = "ag-notice"
    const val NOTICE_OK = "ag-notice-ok"
    const val TRAINEE = "ag-trainee"
    const val RIDE_START = "ag-ride-start"
    const val RIDE_END = "ag-ride-end"
    const val DISCUSS = "ag-discuss"
    const val MEDIA_NOTICE = "ag-media-notice"
}

enum class AgencyTab(val label: Int) { COMPANY(R.string.ag_tab_company), WAR_ROOM(R.string.ag_tab_warroom), SETTINGS(R.string.ag_tab_settings) }

/** The start screens until the company is open, then the three tabs. */
@Composable
fun AgencyRoot(agency: AgencyApp, actions: AgencyActions = AgencyActions()) {
    // Defensive (main on the phone already; not under the Compose test rule). Keep it: AgencyMainThreadTest fails without it.
    val start by agency.start.collectOnMain()
    Box(Modifier.fillMaxSize().background(Sakura.Background)) {
        when (val s = start) {
            StartState.Opening -> Plate(AgencyTags.OPENING, stringResource(R.string.ag_opening), null, busy = true)
            StartState.Importing -> Plate(AgencyTags.IMPORTING, stringResource(R.string.ag_importing), stringResource(R.string.ag_importing_hint), busy = true)
            // 再試一次 here too: opening deletes nothing, and a Keystore that was only busy after boot may answer now.
            StartState.Locked -> Plate(AgencyTags.LOCKED, stringResource(R.string.ag_locked), stringResource(R.string.ag_locked_hint)) {
                PinkButton(stringResource(R.string.ag_try_again), onClick = agency::start, modifier = Modifier.testTag(AgencyTags.RETRY))
            }
            StartState.Unavailable -> Plate(AgencyTags.UNAVAILABLE, stringResource(R.string.ag_unavailable), stringResource(R.string.ag_unavailable_hint)) {
                PinkButton(stringResource(R.string.ag_try_again), onClick = agency::start, modifier = Modifier.testTag(AgencyTags.RETRY))
            }
            is StartState.Ready -> AgencyNav(agency, s, actions)
        }
    }
}

@Composable
private fun Plate(tag: String, title: String, hint: String?, busy: Boolean = false, action: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).testTag(tag), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (busy) CircularProgressIndicator(color = Sakura.Primary)
            Text(title, color = Sakura.Text, fontSize = 22.sp, textAlign = TextAlign.Center)
            if (hint != null) Text(hint, color = Sakura.TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
            action?.invoke()
        }
    }
}

@Composable
fun AgencyNav(agency: AgencyApp, ready: StartState.Ready, actions: AgencyActions = AgencyActions()) {
    var tab by rememberSaveable { mutableStateOf(AgencyTab.COMPANY) }
    // His 代表色 becomes the accent (arch T10); read again after every message (a 藝名/代表色 change bumps the revision).
    val revision by ready.brain.revision.collectOnMain()
    val colour by produceState<String?>(null, revision) {
        onMain { value = withContext(Dispatchers.IO) { runCatching { ready.store.profile.get()?.memberColor }.getOrNull() } }
    }
    SakuraTheme(MemberColour.accent(colour)) { Tabs(agency, ready, actions, tab) { tab = it } }
}

@Composable
private fun Tabs(agency: AgencyApp, ready: StartState.Ready, actions: AgencyActions, tab: AgencyTab, onTab: (AgencyTab) -> Unit) {
    Scaffold(
        containerColor = Sakura.Background,
        bottomBar = {
            NavigationBar(containerColor = Sakura.White) {
                AgencyTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { onTab(t) },
                        icon = { Dot(tab == t) },
                        label = { Text(stringResource(t.label), fontSize = 13.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedTextColor = Sakura.Text, unselectedTextColor = Sakura.TextSecondary, indicatorColor = Sakura.Background,
                        ),
                        modifier = Modifier.testTag(AgencyTags.TAB + t.name),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                AgencyTab.COMPANY -> HomeScreen(agency, ready, actions)
                AgencyTab.WAR_ROOM -> WarRoomPlaceholder(ready)
                AgencyTab.SETTINGS -> SettingsScreen(agency, ready)
            }
        }
    }
}

@Composable
private fun Dot(on: Boolean) {
    val shape = androidx.compose.foundation.shape.CircleShape
    Box(Modifier.padding(4.dp).background(if (on) LocalAccent.current.fill else Sakura.MilkTea, shape).padding(5.dp))
}

/** 指揮室 is rebuilt in T20; until then one panel and 「今天的連動」 (T11b). */
@Composable
fun WarRoomPlaceholder(ready: StartState.Ready) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(AgencyTags.WAR_ROOM),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Panel(title = stringResource(R.string.ag_warroom_title)) {
            Text(stringResource(R.string.ag_warroom_closed), color = Sakura.Text, fontSize = 15.sp)
        }
        TodayLinksCard(ready)
    }
}
