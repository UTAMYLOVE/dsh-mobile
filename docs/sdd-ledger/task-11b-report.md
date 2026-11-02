# Task 11b report: settings screen and agent config writer

Status: DONE_WITH_CONCERNS (see Concerns).

## Final signatures
```kotlin
// config/AgentSettings.kt
data class AgentSettings(val maxConcurrent: Int, val maxDepth: Int = 2, val teamSize: Int = 8, val leadModel: String, val agentTeams: Boolean = true)
    // init require: maxConcurrent 1..32, maxDepth 0..3, teamSize 1..16 (IllegalArgumentException); no workerModel (Ruling 1)
    companion { MAX_CONCURRENT_RANGE; MAX_DEPTH_RANGE; TEAM_SIZE_RANGE; fun defaults(phantom: PhantomState, hasClaudeKey: Boolean): AgentSettings }
        // DISABLED -> 16, else 6; leadModel = deepseek-flash whatever the key
object AgentModels { DEEPSEEK_PROVIDER = "deepseek-official"; ANTHROPIC_PROVIDER = "anthropic"; DEFAULT_MODEL = "deepseek-flash"
    data class Model(id, provider) { isClaude }
    fun available(hasClaudeKey): List<Model>   // flash, v4-pro (+ claude-sonnet-5-5, claude-opus-5-5, claude-haiku-5-5 with a key)
    fun find(id: String?): Model?; fun resolve(id: String?, hasClaudeKey: Boolean): Model }   // unknown / Claude-without-key -> deepseek-flash
// config/AgentSettingsStore.kt
class AgentSettingsStore(prefs: SharedPreferences) { fun load(phantom, hasClaudeKey): AgentSettings; fun save(s); var heartbeatIntervalMin: Int /*30..60, default 45*/; companion PREFS }
// config/AgentConfigWriter.kt
class AgentConfigWriter(dshHome: File /* rootfs/root/.dsh */) {
    fun patchSection(s: AgentSettings, hasClaudeKey: Boolean): String
    @Synchronized fun ensureAgentTeamsBundle(enabled: Boolean): Boolean
    companion { TEAM_BUNDLE = "@deepseek-ai/dsh-experimental-agent-team-profile" } }
class AgentPatchProvider(vault: KeyVault, store: AgentSettingsStore, writer: AgentConfigWriter, phantom: () -> PhantomState, pluginDir = DEFAULT_PLUGIN_DIR) : (String) -> String
// config/DshPatch.kt
fun render(gatewayBase: String, pluginDir: String = DEFAULT_PLUGIN_DIR, agentSection: String = "", includeAnthropic: Boolean = false): String
// service/DshController.kt: new required ctor param  patchProvider: (gatewayBase: String) -> String  (after onRateGuard)
// ui/SettingsScreen.kt
interface SettingsVersions { current(); previous(); suspend checkUpdate(): UpdateOutcome; suspend rollback(): MainOps.RollbackOutcome }
class SettingsEnv(vault, store, meter, phantomState, runtime: DshRuntime, isRunning: StateFlow<Boolean>, versions, applyAgentTeams, scheduleHeartbeat, supervise, openDeveloperSettings, exportErrorLog, exportMemory)
@Composable fun SettingsScreen(env: SettingsEnv, onClose: () -> Unit = {}, modifier: Modifier = Modifier)
// ui/SettingsActivity.kt: ComponentActivity (exported=false, no new permission); wires DshMobileApp singletons; holds the UI (UiHolder) while started
// ui/UiHolder.kt: onUiStart/onUiStop now count started screens (see Concerns 1)
// DshMobileApp: + agentSettingsStore, agentConfigWriter (lazy); controller gets patchProvider = AgentPatchProvider(...); onCreate schedules the heartbeat with the saved interval
```

## Behaviour
- Patch rows (agent section sits between providers and privacy): `agent-default-model` (provider+model), `subagent` (`maxActiveSubagents = maxConcurrent`, `maxDepth`), and with Agent Teams on `agent-team` (`maxMembers = min(teamSize, maxConcurrent)`, `maxTasks 256`, `maxMessageBytes 65536`, `disposalTimeoutMs 5000`). A Claude `leadModel` without a Claude key, or an unknown model id, is written as `deepseek-official`/`deepseek-flash`; model ids come only from the fixed list, never from stored text.
- No Claude key: `DshPatch.render(includeAnthropic=false)` leaves out the `llm-pi-ai` row; the whole document has no "anthropic" (test), and `render` throws IllegalArgumentException if a caller passes an agent section that names it. Keys appear only as `apiKeyEnv` names.
- `ensureAgentTeamsBundle`: file missing -> false, nothing created (not even `profiles/`); malformed/unexpected shape -> false and untouched; entry already as asked -> true without a write; else only the array entry is added (appended) or removed, all other fields keep value and position (kotlinx `JsonObject` is a LinkedHashMap), two-space indent + trailing newline like dsh's own file, temp file + atomic rename. Synchronized.
- Launch flow (`AgentPatchProvider`, called by the controller on every start, on IO): reads "is there a Claude key" once, loads settings, calls `ensureAgentTeamsBundle(settings.agentTeams)`, and writes the `agent-team` row only if that returned true. Reason (live check below): a row for a bundle that is not loaded makes dsh print `patch: entry "agent-team" not found`. So on the very first dsh start (profile file not there yet) Agent Teams is off; the start after it has the bundle and the row. This is Ruling 4's "retry later", done before every start instead of "at the next stop" (same effect: the file is changed before the next launch; also covers crashes).
- `DshController.guestEnv`: `ANTHROPIC_API_KEY=dshm-proxy` is now set only when the vault holds a Claude key. Reason: pi-ai discovers the anthropic provider from that variable alone (its default endpoint is api.anthropic.com), so without this a user with no Claude key would still get the anthropic provider.
- Settings screen (Compose, SakuraTheme, zh-Hant, password fields): keys (status set/unset, replace, clear; save needs 16..512 chars after trim; vault failure shows a generic message, never the exception text), agent (steppers for concurrency/depth/team size, Agent Teams switch, lead model radio list, "子代理人沿用總指揮模型" note, a note when team size exceeds concurrency), budget (today's US$, alert threshold, hard-cap switch + amount, explicit save, positive finite numbers only), heartbeat (30..60 in 5-minute steps, saves and calls `HeartbeatScheduler.schedule` at once), dsh version (current/previous, check update -> `updateWithAutoRollback`, roll back), dsh running status + "重新啟動" button when stopped (shown after every result, per the Task 9 carry-over), phantom state + button (developer options, or the device-info page when developer options are still hidden; state re-read on resume), exports (error log, memory backup via `Exporter`), "套用並重啟" (round 0 said `save -> ensureAgentTeamsBundle -> stop() -> ...`; since fix round 1 it is `save -> stop() -> ensureAgentTeamsBundle -> supervise -> ensureRunning()`). Only Apply saves the agent settings. While a long action runs all actions are disabled and a progress bar shows; vault, restart, update, rollback and exports run off the main thread. `DshVersions.current()` throwing (missing link) shows "未知" instead of crashing the screen.
- `SettingsActivity` counts as the UI for `UiHolder`, and `UiHolder` now counts started screens (Android starts Settings before Main stops): without that the "ui" hold would end when Main stops and dsh would be stopped ten minutes later while the user is in Settings.
- `supervise` = `serviceGaveUp=false` + `DshService.start()` (an explicit user restart, same rule as MainActivity's Retry). Corrected in fix round 1: apply and restart call it before `ensureRunning`; update and rollback call it afterwards only when dsh is running (round 0 of this report said so but the code did not do it).

## Live check (Ruling 4), scratch dsh 0.2.1-rc.5
Setup: `DSH_HOME=<temp>/home` created by dsh's own first `--dump-config`, `export PATH=/opt/node24/bin:$PATH`, and patches produced by the real `AgentPatchProvider` (a throwaway JUnit test, deleted afterwards), then
`node <scratch>/node_modules/@deepseek-ai/dsh/lib/bin.js --profile web --patch <patch> --dump-config` (launcher flags before app flags). All runs exit 0 with zero bytes on stderr unless noted; temp dirs removed, no dsh process left.

1. Writer on dsh's own `profiles/web/package.json` (diff of the file before/after `ensureAgentTeamsBundle(true)`):
```
<         "@deepseek-ai/dsh-web-app"
>         "@deepseek-ai/dsh-web-app",
>         "@deepseek-ai/dsh-experimental-agent-team-profile"
```
2. Patch E (DeepSeek only, concurrency 6, team size 8 requested, v4-pro lead), bundle present:
```
- id: agent-team
  name: '@deepseek-ai/dsh-experimental-agent-team'
  config: {maxMembers: 6, maxTasks: 256, maxMessageBytes: 65536, disposalTimeoutMs: 5000}   # (block style in the real output)
- id: subagent            config: maxActiveSubagents: 6, maxDepth: 2
- id: agent-default-model config: provider: deepseek-official, model: deepseek-v4-pro
```
   (baseline dump without the bundle: 0 `agent-team` rows; teamSize 8 was clamped to 6.)
3. Patch B (Claude key, `claude-opus-5-5`, concurrency 10, team 4, depth 3): `agent-default-model` provider `anthropic` / model `claude-opus-5-5`; `subagent` 10/3; `agent-team` maxMembers 4; `llm-pi-ai` row with `apiKeyEnv: ANTHROPIC_API_KEY`, `baseURL: http://127.0.0.1:41234/tok/anthropic`.
4. Patch D (Agent Teams off, bundle removed by the writer): 0 `agent-team` rows, `agent-default-model` v4-pro, clean.
5. Patch C (first start, no `$DSH_HOME/profiles` yet): the writer created nothing, patch has no `agent-team` row, dsh starts clean and creates its own profile (no team entry).
6. Control: the same agent-team patch row against a profile without the bundle prints `dsh: [...patch.yml] patch: entry "agent-team" not found` (the reason for omitting the row until the bundle is in place).
7. `grep -ci anthropic` on the four no-Claude patches: 0 each; with Claude key: 4.

## Spec 5.5 gap (known, per Ruling 1)
Spec 5.5 wants a cheap model for "errand" subagents. dsh 0.2.1 has no model key for subagents or teammates (they follow the parent's model; only `tool-subagent.modelSelection` lets the model choose), so only the Lead model is configurable and the settings page says "子代理人沿用總指揮模型". Also not done (Ruling 2): a separate compaction summary model (needs a rewritten preset); it follows the conversation model.

## Tests and results
Full suite `./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest --no-daemon -q`: 486 tests, 0 failures, 0 errors, 0 skipped (409 before, 77 new); only the environment's `Picked up JAVA_TOOL_OPTIONS` line on stderr. `./gradlew :app:assembleDebug --no-daemon -q`: exit 0. ManifestPermissionsTest green (no uses-permission added).
New: AgentSettingsTest 6 (defaultsDependOnPhantomState, ranges, model lists, Claude fallback), AgentSettingsStoreTest 6, AgentConfigWriterTest 19 (all seven brief names: noClaudeKeyMeansNoAnthropicReferences, configNeverContainsRealKey, defaultsDependOnPhantomState [in AgentSettingsTest], baseUrlsPointAtGateway, teamSizeIsClampedToConcurrency, agentTeamsBundleIsAddedWithoutTouchingOtherFields, missingProfilePackageJsonIsNotCreated; plus exact YAML, off switch, unknown model, add twice, disable, order of other fields, malformed files, no temp file left, first-start/second-start behaviour), DshPatchTest +3, DshControllerTest +3 (env without Claude key, provider gets the gateway base and its text is written with 0600, provider failure cleans up), UiHolderTest +1, SettingsScreenTest 36 (Robolectric + Compose, `@Config(sdk=[36])`: no Claude option without a key, keys, password field, apply order stop -> supervise -> ensureRunning, saved settings, budget, heartbeat, versions, running status/restart, busy state, exports, phantom, resume re-read), SettingsActivityTest 3 (declared, not exported; UI hold; Settings-over-Main overlap).

## TDD evidence
- RED: all new/changed tests written first; `compileDebugUnitTestKotlin` failed with unresolved references (`AgentConfigWriter`, `AgentSettings`, `AgentSettingsStore`, `AgentPatchProvider`, `patchSection`, `ensureAgentTeamsBundle`, ...).
- GREEN iterations: config + controller + UiHolder classes 6+6+19+11+38+12 tests, 0 failures on the first run after implementing; SettingsScreenTest first run 35 tests / 3 failed (all test flaws: the raw `InputText` semantic of a password field contains the typed text, the word "claude" also appears in the "Claude 金鑰" label; fixed the matchers as OnboardingScreenTest does) then 36/36 after adding the unreadable-version test; SettingsActivityTest 3/3.
- Final: full suite 486/0/0/0, assembleDebug exit 0.

## Files
Main (new): config/{AgentSettings,AgentSettingsStore,AgentConfigWriter}.kt, ui/{SettingsScreen,SettingsActivity}.kt. Main (changed): config/DshPatch.kt, service/DshController.kt, ui/UiHolder.kt, DshMobileApp.kt, AndroidManifest.xml, res/values/strings.xml (set_* strings; `main_settings_missing` reworded now that the page exists).
Tests (new): config/{AgentSettingsTest,AgentSettingsStoreTest,AgentConfigWriterTest}.kt, ui/{SettingsScreenTest,SettingsActivityTest}.kt. Tests (changed): config/DshPatchTest, service/{DshControllerTest,DshServiceTest}, heartbeat/HeartbeatWorkerTest, ui/{MainActivityTest,UiHolderTest}.

## Compile-verified only (not run on a device)
`SettingsActivity` wiring of the real `DshVersions`/`Exporter`/`HealthCheck`; `DshService.start` from Settings; the share sheet from the IO thread (`Exporter.share` uses an application context + NEW_TASK); opening developer options / device info; real `AndroidKeyVault.put` from the screen; WorkManager re-scheduling after a heartbeat change; a real update/rollback through the screen; Android's actual Activity lifecycle order that the `UiHolder` counting relies on (reproduced in tests with the documented order). The dsh side was verified live with `--dump-config` only, not with a running dsh and a real Agent Teams session.

## Self-review findings (fixed)
- First draft emitted the `agent-team` row regardless of the bundle: the control run showed dsh warns when the bundle is not loaded -> row only once the bundle is in place.
- `ANTHROPIC_API_KEY` env var would have kept the anthropic provider alive for a user without a Claude key (pi-ai env discovery) -> env var only with a key.
- Settings on top of Main would have let the grace timer stop dsh after ten minutes -> UiHolder counts screens, SettingsActivity takes the hold.
- `DshVersions.current()` throws on a missing link; the screen would have crashed on open -> caught, shows "未知".
- Exception texts of vault failures are never shown (they could contain a key); update `reason` texts are truncated to 300 chars.

## Concerns
1. `UiHolder` semantics changed (started-screen counter; the old paired start/stop tests all pass unchanged). A second `onUiStart` without a stop now keeps the hold until two stops.
2. `DshController` env no longer has `ANTHROPIC_API_KEY` without a Claude key; verified by controller test, but not by looking at pi-ai's model list in a running dsh.
3. On the very first dsh start Agent Teams is off (dsh creates `profiles/web/package.json` while starting); it is on from the second start. The Settings page tells the user when Apply could not apply it yet ("下次啟動後生效"). If the user toggles the bundle in dsh's own plugin page, the app's switch wins again at the next start.
4. `agent-default-model` replaces the whole row, so dsh's `reasoningEffort` goes back to dsh's default (high); the brief asked only for provider and model.
5. A saved Claude lead model is overwritten by `deepseek-flash` when the user clears the Claude key and presses Apply (it stays stored until then).
6. Clearing the DeepSeek key is allowed (as the brief says); the next app launch then goes back to onboarding's key step.
7. Compose tests need the Robolectric SDK 36 jar (same as Task 11c/11a).

## Fix round 1
Three issues from review and the coordinator's ruling.

What changed
1. Actions survive leaving the screen. New `ui/SettingsJobs.kt` (one job at a time; `busy` StateFlow plus `applyMessage` / `versionMessage` flows). `DshMobileApp` gets `applicationScope` (SupervisorJob + Dispatchers.IO, never cancelled) and `settingsJobs`; `SettingsEnv` has a new `jobs` parameter. Apply, update, rollback and restart now run as jobs in that scope, each body inside `withContext(NonCancellable)`, so closing or destroying the Activity cannot cut stop -> switch -> start short. The screen observes the flows, so a reopened Settings shows busy for a job that is still running and shows the last result. The Close button is disabled while busy (screen-local key/export work or a job) and `BackHandler(enabled = busy) {}` swallows Back. Versions on screen are re-read when a job ends. Keys and exports stay in the screen scope (cancelling them half way is harmless); their busy state still disables everything.
2. Supervision after update/rollback: both call `env.supervise()` afterwards if `env.isRunning` is true (clears `serviceGaveUp`, starts `DshService`); if dsh is down nothing is touched and the stopped status plus the restart button show. Report wording corrected above.
3. Apply calls `applyAgentTeams` after `stop()` (dsh is down while its profile file changes), before `supervise` and the restart.

Covering tests (SettingsScreenTest 36 -> 48, all in that class): `closeIsDisabledWhileBusyAndBackDoesNotLeave`, `anApplyFinishesAndDshRunsAfterTheActivityIsDestroyedMidAction`, `anUpdateFinishesAfterTheActivityIsDestroyedMidAction`, `aRollbackFinishesAfterTheActivityIsDestroyedMidAction`, `aRestartFinishesAfterTheActivityIsDestroyedMidAction` (each destroys the Activity while the job is blocked on a gate, then releases it and checks the job completed, dsh started, the message is in the jobs state), `aReopenedScreenShowsAJobThatIsStillRunning`, `theResultOfAnEarlierJobIsShownWhenTheScreenOpens`, `onlyOneJobRunsAtATime`, `updateWithDshRunningHandsItToTheService`, `updateWithDshDownDoesNotSuperviseAndOffersARestart`, `rollbackWithDshRunningHandsItToTheService`, `rollbackWithDshDownDoesNotSupervise`; `applyStopsThenEnsureRunning` now pins the order stop, teams, supervise, ensureRunning.

RED / GREEN
- RED: the tests were written after the code this round, so I proved they bite with a mutation run on SettingsScreen.kt (supervise-after-update/rollback removed, Close not disabled, `applyAgentTeams` moved back before `stop()`): `SettingsScreenTest` 48 tests, 7 failed (closeIsDisabledWhileBusyAndBackDoesNotLeave, updateWithDshRunningHandsItToTheService, rollbackWithDshRunningHandsItToTheService, applyStopsThenEnsureRunning, aReopenedScreenShowsAJobThatIsStillRunning, and the apply/update destroy tests through the missing supervise/order). The mutation was reverted. The very first compile of the new tests against round 0 code fails on the unresolved `SettingsJobs` / `jobs`.
- GREEN: `./gradlew :app:testDebugUnitTest --no-daemon -q --tests 'app.dshmobile.ui.Settings*' --tests 'app.dshmobile.ui.MainActivityTest'`: SettingsScreenTest 48, SettingsActivityTest 3, MainActivityTest 30, 0 failures. Full suite `./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest --no-daemon -q`: 498 tests, 0 failures, 0 errors, 0 skipped (486 before, 12 new). `./gradlew :app:assembleDebug --no-daemon -q`: exit 0.
- Note: one earlier full run had a single failure in `DshControllerTest.stdoutAndStderrGoToTheMaskedErrorLogButNotTheLaunchLine` (not touched this round; 3 reruns of the class and the next full run were green), looks like the known timing flake of the fake-process tests.

Files: main `ui/SettingsJobs.kt` (new), `ui/SettingsScreen.kt`, `ui/SettingsActivity.kt`, `DshMobileApp.kt`; test `ui/SettingsScreenTest.kt`; this report.
