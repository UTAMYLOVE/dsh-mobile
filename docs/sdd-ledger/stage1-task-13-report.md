# Stage 1 Task 13 report: settings in the native shell

## Built
- `runtime/VerifiedDsh.kt`: `VerifiedDsh` (`VERSIONS = ["0.2.1-rc.5"]`, `isVerified`, `newest`) and `VerifiedUpdatePolicy` (`target`, `previous`). The list is a Kotlin constant; `rootfs/versions.env` is untouched.
- `DshVersions.updateWithAutoRollback(runtime, health, target: String? = null)`: with a target, `npm view` is skipped.
- `AgentSettingsStore.novelModel` (default `claude-sonnet-5-5`, unknown stored values read back as the default; pref key `novel_model`).
- `SettingsScreen`: every section is a `FogPanel` (Chinese title plus KEYS / AGENTS / MODELS / EFFECTS / BUDGET / HEARTBEAT / DSH / SYSTEM / EXPORT / APPLY). New EFFECTS panel (`Segmented`, tags `effect-0..2`), novel-model radio list inside MODELS (tags `novel-model-<id>`, display names, saved at once, no restart), the verified-list note (`verified-note`) and the red unverified warning (`unverified-warning`, `#B3261E` via the theme's error colour).
- `buildSettingsEnv`: `checkUpdate()` uses `VerifiedUpdatePolicy.target` (null gives `UpToDate`); `previous()` and the rollback go through `VerifiedUpdatePolicy.previous`. `MainActivity` builds `MainOps` the same way, so the crash notification never rolls back to an unverified version.
- Overview: `unverifiedDshVersion` is wired in `DshMobileApp` as in the brief. `OverviewSource` got a trailing `context` parameter (default empty, the app passes `Dispatchers.IO`) and ends in `.flowOn(context)`, so the version link and prefs are read off the main thread.
- Rulings: settings and overview collectors use `collectAsStateWithLifecycle`. `lifecycle-runtime-compose` is already on the classpath (via `activity-compose`; `LifecycleEventEffect` and `FogShell` already use it), so `libs.versions.toml` is unchanged. `SettingsJobsTest` now uses a `CoroutineExceptionHandler` in its test scope. Production was left alone: every `SettingsJobs` block in the app catches its own exceptions.

## Final signatures
- `SettingsVersions.verified(): List<String>` (new).
- `SettingsEnv(..., onEffectLevel, effectLevel: StateFlow<EffectLevel> = MutableStateFlow(FULL))`. `effectLevel` is `app.effectsController.level`. This replaces the brief's `uiPrefs`, because the control must show the controller's level. `onEffectLevel` is called only from the segment tap, never on recomposition or draft restore.
- `Tags.EFFECT = "effect-"`, `NOVEL_MODEL = "novel-model-"`, `UNVERIFIED_WARNING`, `VERIFIED_NOTE`.
- Strings: `set_version_uptodate` now takes `%1$s` (the newest verified version). New `set_*` strings for the panels, effect cells, novel model, verified note and warning.

## Tests
- New: `VerifiedDshTest`, `VerifiedUpdatePolicyTest`, `DshVersionsTest.aGivenTargetSkipsNpmView`, `AgentSettingsStoreTest.novelModelDefaultsToSonnetAndPersists`.
- `SettingsScreenTest` (7 added): `effectStrengthDefaultsToFullAndSavesAtOnce`, `theEffectControlFollowsTheAppsLevelAndNeverReappliesIt`, `novelModelListsOnlyAvailableModelsAndSaves`, `withAClaudeKeyTheNovelModelDefaultsToSonnetAndCanBeChanged`, `theVerifiedListIsShown`, `anUnverifiedCurrentVersionWarns`, `rollbackIsDisabledWhenThereIsNoVerifiedPrevious`.
- `MainActivityTest` (2 added): `theCrashNotificationRollbackNeverTargetsAnUnverifiedVersion`, `settingsUpdateFromAnUnverifiedCurrentTargetsTheNewestVerified`.
- RED was a compile failure of the new tests, then GREEN.

## Deviations
- Existing `MainActivityTest` rollback tests used the fake versions 0.2.0 and 0.2.1. 0.2.0 is now unverified and correctly filtered, so those literals became `0.2.1-rc.5` (previous) and `0.2.2` (current). Only literals changed.
- `SettingsScreenTest`'s uptodate assertion passes the version argument.
- `SettingsEnv.effectLevel` replaces `uiPrefs`, as described above.
- The warning is not shown when the current version could not be read (shown as "unknown").

## Concerns
- No 412 dp screenshot was rendered. Layout was checked only through the Robolectric semantics tests.
- The lead-model radios still show raw ids. The novel-model radios show display names.
