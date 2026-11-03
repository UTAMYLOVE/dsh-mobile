# Stage 1 final fix wave — lane X report (data, pages, activity)

Branch `final-fix-x` (worktree /home/user/wt-t3), from main 721e9e8. Commits 7f5a9d5..2e0532f (11 commits, plus this report). Nothing pushed.

Status: **DONE_WITH_CONCERNS** (concerns at the end).

Verification (final, after the last code commit):
- Full unit suite `./gradlew :app:testDebugUnitTest --max-workers=2`: **1017 tests, 0 failures, 0 errors, 0 skipped**.
- `:app:assembleDebug` and `:app:assembleDebugAndroidTest`: both build, with no compiler warnings in the output.
- I did not touch any lane-Y file (ui/fog/*, FogShell, Dock, PromptSheet, StatusPanel, FogBackground, Glass, Hud, .github/workflows/*). The settings confirm card uses `Modifier.glass` and `FogButton` without changing them.
- Red lines: no new permissions or dependencies. `by viewModels()`, `AndroidViewModel` and `SavedStateHandle` come in through activity-compose 1.12.4, which already pulls activity-ktx and lifecycle-viewmodel-savedstate. Logs carry exception class names only. No colours outside the palette. The new strings are in strings.xml.

RED evidence. For behaviour that the old API could already reach, I ran the new tests against the old code and they failed:
- A-I1 (adoption commented out): 2 of 28 failed.
- B2-I2 plus the overview cache (main changes reverse-applied): 2 of 9 failed.
- B1-I5 ShellNav: 3 of 5 failed.
- View-model group: 5 failed, namely `aCreateWhileAnotherRunsJoinsIt`, `aTextSendCutShortPutsTheTextBack`, `aProcessDeathRestores…`, `turningThePhoneKeeps…` and `aDeepLinkWaitsWhileSettingsIsBusy`.
- B2-M6 list back: 1 of 9 failed.

The remaining tests need a new API (`runningHint`, `codec`, `prompts`, `ChatUiCache`, `has`, `workRunning`, `LinkOpen`), so their RED was a compile failure.

## Findings

### A-I1: joining a reply that is already streaming
- Commit: 7f5a9d5.
- Change: `ConversationReducer.activeAttempt()` reads `snapshot.assistantStream.activeAttempt` and folds its compact records into a `LiveReply` (`text-chunks`, `reasoning-chunks`, `tool-call-chunks` names, single `chunk`s). This follows the dsh source: `SessionAssistantStreamAccumulator.snapshot()` and history.js `follow`, which cuts on `assistantStreamOrdinal`. `ConversationStore.adoptActiveAttempt()` seeds the StringBuilders, adopts the attempt id and publishes, so the chunks that arrive afterwards without a `start` land. A snapshot with an active attempt also sets `running`.
- Tests: ConversationStoreTest `aSnapshotWithAnActiveAttemptShowsTheReplyAndTakesItsChunks` and `anActiveAttemptPastTheMessageCapStillCountsAsRunning`.
- Not done: the optional e2e (drop the mux during E2E_SLOW).

### A-I2: RPC encoding on the main thread
- Commit: 99bd687.
- Change: `DshRemote` takes `codec: CoroutineContext = Dispatchers.Default`. The following now run on it:
  - `call()` building the envelope and parsing the answer;
  - building the prompt's content;
  - the upload's base64;
  - mapping the `session/list` rows.
- The upload KDoc now gives the real peak, about 4× the file.
- Test: DshRemoteCallTest `encodingAndDecodingRunOnTheCodecDispatcherNotTheCallers`.

### A-I3: background work waiting on an answer
- Commit: ff6cf9e.
- `WorkRemote.prompts` exposes `$events` prompts. Fakes get a default.
- `UiHolder.foreground` is a new StateFlow.
- `DshService.watchPrompts` combines the prompts with foreground. While something is pending and no screen is started, it posts 「DSH 在等你回答」 (`Notifier.showWaitingForAnswer`, ID 8, alerts channel). The tap opens `WorkTracker.topLevelOf(agentId)`, so a child's question opens its parent. A new eventId alerts again. The notice is withdrawn when nothing waits or a screen is up.
- Tests: DshServiceTest `aQuestionWhileNobodyLooksIsAnnouncedAndOpensTheTopLevelConversation` and `aQuestionWhileTheScreenIsUpPostsNothing`; NotifierTest `waitingForAnAnswerOpensThatConversationAndCanBeWithdrawn`.

### A-I4: attachment memory
- Commits: bf061e0 for the cap; the OOM catch is in 7f5a9d5.
- `AttachmentSender.INLINE_TOTAL_LIMIT` is 10 MB. Inline picture bytes stop there and the remaining pictures go up as files.
- `ConversationStore.send` catches `OutOfMemoryError` and sets `sendError = out-of-memory`. The dock words that with `attach_out_of_memory`.
- Serialization moved off Main (A-I2).
- Tests: AttachmentsTest `inlinePicturesStopAtTenMegabytesAndTheRestAreUploaded`; ConversationStoreTest `runningOutOfMemoryWhileSendingIsReportedNotThrown`.

### B2-I1, B2-M1 (part) and the T9 note: state across recreation and process death
- Commit: 0e039bf.
- New `ui/MainViewModel` (`AndroidViewModel` + `SavedStateHandle`) holds:
  - `ShellNav`;
  - the composer (text, attachments, the `sending` flag, the error line);
  - the settings draft;
  - the canLeave guard;
  - the pending deep link.
- Saved state keeps tab, open id, listOpen and composer text. Attachments and the settings draft are never put in a Bundle.
- A fresh start still opens `uiPrefs.lastSessionId`. The inverted `savedInstanceState == null` guard is gone.
- Sends hold the view model instead of the activity.
- Not done, as ruled: extracting ShellFollower or AppUpdateFlow.
- Tests: MainActivityTest `aProcessDeathRestoresTheTabTheConversationTheListAndTheDraft` (a new activity with the saved Bundle and a fresh ViewModelStore), `turningThePhoneKeepsTheDraftAndTheAttachments` (recreate) and `aFreshStartOpensTheLastConversation`.

### B1-I5: ShellNav back
- Commit: e978b6e.
- The list and the conversation are cleared only when the tab is CHAT. Otherwise back calls `select(OVERVIEW)`, so canLeave is asked; a refusal still consumes the back.
- Tests: ShellNavTest `backOnAnotherTabLeavesTheHiddenChatAlone`, `backAsksCanLeaveLikeATabTap` and `backFromTheListWithNothingOpenGoesToTheOverview`.

### B2-I2: Keystore decrypts on tab entry
- Commit: 43dc4fa.
- `KeyVault.has(p)`: the default is `get != null`; `AndroidKeyVault` uses `prefs.contains`.
- It is now used by the chat and agents tabs, `SettingsDraft.from`, the Settings key rows and the overview's agent limit.
- Deviation: the onboarding gate in `onCreate` keeps `get()`. A key that the Keystore can no longer decrypt must send the owner back to onboarding.
- Test: MainActivityTest `enteringTheTabsNeverDecryptsAKey`.

### B2-I3: parsing on every visit
- Commit: 8977d25.
- `ChatUiCache` (BlockCache, the open thinking set, the scroll index and offset) is held by `ShellSession.chatUi(id)` next to the store and evicted with it.
- Eager parsing is capped at the newest `EAGER_ROWS = 20` messages, with at most 20 000 characters together. The rest go through `parseMissing` behind placeholders.
- Test: ChatScreenTest `onlyTheNewestMessagesAreParsedInTheCompositionAndTheCacheOutlivesTheScreen`.

### B2-I4: confirm before stopping running work
- Commit: 3d9079a.
- `SettingsEnv.workRunning` is true when any session in the current tree is running, subagents included. While it is true, apply, update and rollback show an inline glass card (`Tags.CONFIRM_CARD`):
  - 「有對話正在進行，套用會中斷它」 with 「等做完」 / 「仍要套用」;
  - the same card with 更新 / 退版.
- The crash notification's rollback AlertDialog adds the same sentence and buttons when work is running.
- Tests: SettingsScreenTest `applyWhileWorkRunsAsksFirstAndWaitingChangesNothing`, `applyAnywayStopsAndRestarts`, `updateAndRollbackAskWithTheirOwnVerb` and `withoutWorkNothingIsAsked`.

## Minors

**From review A**
- Notifier request codes (ff6cf9e): the working notification's tap uses ID 9. Test: NotifierTest `theWorkingTapKeepsItsConversationAfterThePlainNotificationIsBuilt`.
- Overview `distinctUntilChanged` plus cached limit and version (43dc4fa): read once per collection. Test: OverviewSourceTest `theLimitAndVersionAreReadOncePerCollectionAndRepeatsAreDropped`.
- Running fallback past the 500-message cap (7f5a9d5): an active attempt counts as running. When the snapshot was cut (`hasMore`), the tree's `summaries[id].running` is used as well (`runningHint`). Tests: `aCappedSnapshotFallsBackToTheTreesRunningState` and `aWholeSnapshotIgnoresAStaleRunningHint`.
- Stop retrying on non-transient codes (7f5a9d5): `DshErrors.isPermanent` covers session/not-found, subagent/*, gateway/bad-request and similar.
  - The ConversationStore follow waits for an explicit `retry()` (the banner).
  - The SessionTree control/workspace loops stop.
  - Tests: `aPermanentFollowFailureIsNotRetriedUntilAsked`, SessionTreeTest `aPermanentlyRefusedStreamIsNotReopened` and `aTransientStreamFailureIsReopened`.
- t21 KDoc (25d8dc5): says it is an intent flag only and points at PerfGateTest.

**From review B2**
- M2 (3d9079a): re-tapping the selected cell does nothing, and 「目前自動降為：X」 shows when the effective level is below the owner's. Tests: `tappingTheSelectedEffectCellChangesNothing` and `anAutomaticallyLoweredLevelIsShown`.
- M3 (3d9079a): display names, plus 「未套用」 until applied. Test: `leadModelsShowTheirNamesAndAnUnappliedChoiceSaysSo`.
- M4 (0e039bf): the text comes back in `finally` unless the outcome is Sent. Test: `aTextSendCutShortPutsTheTextBack`.
- M5 (0e039bf): `pendingOpen` opens once settings is free. Test: the updated `aDeepLinkWaitsWhileSettingsIsBusy`.
- M6 (8977d25): a list BackHandler closes the card, then the menu. Test: `backClosesTheCardsAndTheMenuFirst`.
- M7 (0e039bf): `SessionIndex.create` joins a create that is already in flight. Test: `aCreateWhileAnotherRunsJoinsIt`.
- M8 (8977d25): open thinking carries from `think:live:<attempt>` to the finished message. Test: `openThinkingStaysOpenWhenTheReplyFinishes`.
- M9 (8977d25): loopback links are refused, and `LinkOpen` separates REFUSED (「這個連結不能在瀏覽器開啟」) from NO_BROWSER. Test: ExternalLinksTest `loopbackLinksAreRefused`.
- M10: not done (controller ruling: stage 2).
- M11 (8977d25): a store with a pending or failed send is never evicted. Tests: `aConversationHoldingASendIsNotEvicted` and `eachConversationKeepsItsChatUiCacheWhileCached`.
- M12 (0e039bf): app-level `overviewState` via `stateIn(applicationScope, WhileSubscribed(5 s), null)`. No dedicated test (a one-frame flash is hard to assert in Robolectric).
- M1 (part, 3d9079a): `SettingsJobs.rollback` is the one rollback for both MainActivity and Settings. Covered by the existing rollback tests in MainActivityTest and SettingsScreenTest.

**From review B1**
- B1-M7 (3d9079a): `DshMobileApp.loopbackHttp` is one OkHttpClient shared by the controller, both remotes and Settings' update health check.

## Deviations
- B2-I2: onboarding keeps a real `get()`, as explained above.
- B2-I4: the confirm card is inline in the section of the action that asks, not an overlay. This follows the plan's "no floating control covers another" rule. The notification rollback keeps its AlertDialog, now with the warning text.
- The running hint is used only when the snapshot was cut by the window (`hasMore`), so a stale tree cannot keep STOP up after a complete snapshot.
- A-I3 uses the alerts channel (high importance), because dsh is blocked until the owner answers.

## Concerns
- `MainViewModel` uses androidx ViewModel. It adds no new dependency, but it differs from the plan header's "no androidx ViewModel" for ShellSession; the ruling allowed it for the composer.
- The permanent-code list in `DshErrors.isPermanent` is my judgement from the dsh source's `RemoteError` codes. Review it once more.
- A-I3 relies on the service's own `$events` client receiving the waterfalls, the same as the UI's client. This is verified with the fake only, not against a real dsh.
- The optional e2e checks were not added: the reconnect while streaming (A-I1) and the finish during E2E_SLOW (§5.11).
