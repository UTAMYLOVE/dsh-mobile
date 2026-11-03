# Stage 1 Task 8 report: the chat page

Branch `lane-t8` (base 41393f1). Status: DONE_WITH_CONCERNS (concerns at the end; none blocks).

## What changed

### New files (`android/app/src/main/kotlin/app/dshmobile/`)

**`ui/chat/ChatScreen.kt`**
- `ModelChoice` (:80), `ChatUiState` (:88) and `modelChoices(hasClaudeKey, current)` (:103), which builds from `AgentModels.available`.
- `ChatUiState` takes the brief's eight fields, then three more with defaults: `followError`, `olderError`, `modelLabel`.
- `ChatScreen` (:409) has the brief's signature, plus three defaulted callbacks after `modifier`: `onRetryFollow`, `onRetrySend`, `onDismissSend`.
- **Long replies (controller ruling).**
  - Each message is parsed into `MdBlock`s, then cut into `Piece`s (`pieces()` :126). Each piece is its own lazy item with a stable key (`a:<key>#<i>`) and a content type.
  - Paragraphs over 600 characters or 16 lines are cut at line ends, about 400 characters per piece. A single line too long for that is cut at 400 characters and never splits a surrogate pair.
  - Code is cut into 30-line pieces and lists into 10-item pieces; tables stay whole (Task 7 caps them at 500 rows).
  - A message of at most 3 000 characters and 12 pieces stays one item with the real glass bubble. Anything longer is drawn piece by piece, and each piece paints its slice of one continuous background: top corners first, tail last, no seams.
- `BlockCache` (:233):
  - Parses once per message key: inline up to 2 000 characters, otherwise on `Dispatchers.Default`, with a "長回覆整理中…" placeholder meanwhile.
  - A just-finished reply reuses its last live parse, so it never blinks.
  - It drops entries for messages that are gone.
- The live reply (`rememberLiveEntry` :372) parses inline while short, then through Task 7's `conflatedParse`.
- The list is `reverseLayout`, newest at index 0.
  - Following the newest: when the rows change, a list resting exactly at the bottom calls `requestScrollToItem(0)` (:482). The position is read without observation, so scrolling never re-runs the check, and nothing happens while a scroll is in progress. Scrolled up, the list keeps its place by key.
  - The durable rows and the live/pending rows are built and remembered separately, so a streaming token never rebuilds a long conversation's rows.
  - Rows are `@Immutable` and the row callbacks are remembered once, so unchanged rows skip recomposition.
- The header is a glass panel: 「☰ 對話」 (36 dp pill in a 48 dp target), the title (17 sp, one line, ellipsized) and the model chip (Chakra Petch 11 sp, display name in capitals).
- The model menu is an inline 30 dp glass card titled 「這個對話使用的模型」 with 48 dp option rows. It pushes the messages down, so it never covers a control, and back closes it.
- Under the header: the follow-error banner with 重新連線, and the send/stop/model error line. A 10 dp fade softens messages under them.

**`ui/chat/MessageViews.kt`**
- `citedSources` (:70) returns 1-based numbers in source order. A URL counts when it ends there; a trailing slash is ignored; `…/one-more`, `…/one/two` and `…?x` do not count as `…/one`.
- `circled` (:74) gives ①–⑳, then (21). `sourceLabel` (:109) is the title, else the host.
- The views:
  - `Bubble` (whole), `BubbleSegment` (+ `segmentBackground`), `RawBubble`, `PlaceholderBubble` and `TypingBubble` (「正在回覆…」 / 「正在使用 web_search」).
  - `ThinkingToggle` (folded 「思考過程 ▸」) and `ReasoningPiece`: shown reasoning is several items too.
  - `ToolCard`: status 執行中／等待確認／完成／失敗, the error in `#B3261E`, and 「分享」 only when there is a `sharePath`.
  - `SearchCard`: 「搜尋網路 · N 個來源」 with source chips that call `onLink`.
  - `NoticeLine` (「已停止」) and `PendingBubble`: a faded user bubble; 「排隊中」 when queued; 重送／移除 when failed.
- Palette: aqua is used only for fills, dots and rims, never for text; links keep Task 7's body colour with an underline.

**`ui/chat/ExternalLinks.kt`**
- `ExternalLinks.open` (:10) handles http/https with a host only: `ACTION_VIEW` + `CATEGORY_BROWSABLE`, and `NEW_TASK` outside an activity.
- It returns false for every other scheme and when no browser is found (`ActivityNotFoundException` or `SecurityException`).

**`ui/chat/ChatPage.kt`** (new; see deviation 1)
- `ChatPage` (:121) is the chat tab: the list while `nav.listOpen` or when no conversation is open, otherwise the conversation (`key(session, id)`).
  - Lasting actions (create, rename, delete, send, model) run in `session.scope`, so leaving the page does not cut them short.
  - The open conversation is closed when the tree marks it archived.
  - A picked model shows at once, until dsh's `modelSelection.next` arrives or the change fails. The current model is `picked ?: nextModel ?: model ?: leadModel`.
- `ListSearch` (:64) implements the search ruling: about 250 ms debounce with `collectLatest`, results only for the current query, a blank query clears at once, and a failure shows as empty. Until dsh answers, the list shows titles that match the current query.
- `TAIPEI`, `msUntilNextMidnight` (:55) and `rememberToday` (:94) implement the day-group ruling: today is recomputed at midnight in Asia/Taipei and on every ON_RESUME.
- `sendFromDock` (:258) and `DockSend`: send to the open conversation, or `index.create()` first, and show it.

### Changed files

- **`chat/SessionTree.kt`** (unknown-session ruling).
  - `HostEvent.SessionStatus` for an id not in `summaries` signals a conflated channel.
  - A collector waits `unknownRefreshMs` (constructor parameter, default 500), drains the channel and calls `refreshNow()`: one list call per burst (:130, :138–158).
- **`chat/ConversationStore.kt`**: new `olderError: StateFlow<String?>` (:264). `loadOlder` reports there (`unexpected-answer`, safe code or class name) instead of in `sendError`, and clears it on success (deviation 2).
- **`ui/shell/ShellSession.kt`**: `conversation(id)` (:43) keeps an access-ordered LRU of 3 stores and closes the least recently used. `close()` also closes the stores.
- **`ui/markdown/Markdown.kt`**: new public `MarkdownBlock(block, onLink, modifier)` (:326), which draws one block exactly as `MarkdownView` does (deviation 3).
- **`ui/chat/ConversationListScreen.kt`** (Task 6): the ✕ close button is now a plain round 48 dp box. `FogButton`'s 20 dp side padding left the glyph 8 dp, so it rendered as "…" (visible in the 412 dp render). Deviation 4.
- **`ui/MainOps.kt`**: `recordDeepLink` removed.

### Shared files (Tasks 10 and 14 edit them in parallel)

- **`ui/MainActivity.kt`** (+81/−9, additive).
  - New members:
    - field `dockError` (:99)
    - restoring `nav.openSessionId = uiPrefs.lastSessionId` on a fresh create (:123)
    - `lastSessionId` saved in `onStop` (:196; what is open when the user leaves)
    - in `Shell()`, a `runningFlow` (only the open conversation's `running`, distinct)
    - the composer wired to `onDockSend` / `stopReply` / `dockError` (:228–234)
    - `ShellTab.CHAT -> session?.let { ChatTab(it) }` (:244)
    - new private members `ChatTab` (:252; Claude key and lead model re-read whenever the tab is shown), `onDockSend` (:262), `stopReply` (:277), `openLink` (:283, with the 「找不到可以開啟連結的瀏覽器」 toast) and `shareWorkspaceFile` (:287; IO, logs the class name only, then a toast)
    - the deep link: `LaunchRequest.OpenSession -> nav.openSession(id)` (:379)
  - Imports added. The comment of `EmptyPage` no longer says "chat".
  - Nothing of FogShell's call shape, `overlay`, effects or lifecycle changed.
- **`ui/shell/Tags.kt`**: one appended block after `DELETE_CANCEL` (:52–92).
  - The brief's 17 tags.
  - Plus `CHAT_MESSAGES`, `MODEL_MENU`, `FOLLOW_ERROR`, `FOLLOW_RETRY`, `CHAT_ERROR`, `OLDER_ERROR`, `SEND_RETRY-`, `SEND_DISMISS-` and `PENDING_BUBBLE-`.
  - No prefix tag is a prefix of another.
- **`res/values/strings.xml`**: one appended block of `chat_*` strings (:167–200).
- **`ui/shell/FogShell.kt`**: not touched.

## Tests

New:
- **`ui/chat/ChatScreenTest`** (21): the brief's 11 names, plus
  - `anAnswerWithoutLinksHasNoCites`, `aFailedSendOffersResendAndRemove`, `withAClaudeKeyTheClaudeModelsAreChoicesToo`
  - `aLoadOlderFailureIsAShortNote`, `aBrokenFollowShowsABannerThatReconnects`, `aSendErrorIsShownInWords`, `theListButtonOpensTheList`
  - `aLongReplyIsHostedAsManySmallItems`: a chapter of over 100k characters becomes 2–60 composed items, each shorter than the list, newest end first.
  - `aLongParagraphWithoutBreaksIsSplitToo`: 60 000 characters with no line break.
  - `theNewestMessageIsInViewAndOlderOnesScrollIn`.
- **`ui/chat/ChatScreenLayoutTest`** (2; `w412dp-h915dp-port`, `@GraphicsMode(NATIVE)` so text is measured for real; the chat page inside the real `FogShell`). The content is a title three times too long, a reply and a user message with an unbroken 200-character URL, a search card with an over-long source title, a tool card with share, and a queued bubble.
  - It checks that the header controls are on screen and apart, the title stays on one line, and the header sits below the shell header.
  - The bubbles are at most 88 %, keep the 16 dp margin, and the URL wraps.
  - Chips and the share button stay inside their cards.
  - The newest message ends at least 24 dp above the dock.
  - The model menu (five options with a Claude key) is on screen, covers no header control, has 48 dp rows that do not overlap, and the messages start below it.
- **`ui/chat/CitationsTest`** (3): the brief's two, plus trailing-slash tolerance.
- **`ui/chat/ExternalLinksTest`** (3): http/https start `ACTION_VIEW`/`BROWSABLE`/`NEW_TASK`; file/content/intent/javascript/mailto/`https://`/garbage never start anything; no browser returns false.
- **`ui/chat/ChatPageTest`** (4; virtual time on a `TestCoroutineScheduler`, not `runTest`, see the full suite below): debounce and asking only for the last query; an older query's late answer is never shown and a blank query clears at once; a failed search shows empty; midnight in Taipei.
- **`chat/SessionTreeTest.aStatusForAnUnknownSessionRefetchesTheListOnce`**: three statuses give one list call; the new session arrives with `running`; a known session's status makes no call.
- **`ui/shell/ShellSessionHolderTest.conversationsAreCachedUpToThreeAndTheLeastRecentlyUsedIsClosed`**: LRU order, the evicted store's follow ends, a re-request gives a new store, and `close()` ends them all. The fake's `follow` now records who is following.
- **`ui/MainActivityTest`**: the brief's six (`sendingFromAnotherTabSwitchesToChatAndPrompts`, `withoutAnOpenConversationSendingCreatesOne`, `aDeepLinkOpensThatConversation`, `stopShowsWhileRunningWithAnEmptyInput`, `sendIsDisabledWhileDshIsNotReady`, `theModelChipShowsTheNextModelBeforeItIsUsed`).
  - They replace `aSessionIdIsOnlyLoggedForTheDeepLinkTodo`.
  - The class gains `@get:Rule compose = createEmptyComposeRule()`, and the activities in these tests are built `.visible()` (the compose roots only register when attached).
  - The fake dsh's RPC is now a per-test `rpc` field (default 404, as before).
  - The chat tests also check:
    - the input is empty after a send
    - create comes before prompt, `lastSessionId` is saved on stop
    - a deep link through `newIntent` while on SETTINGS
    - typed text turns STOP back into SEND (queue)
    - the chip shows the lead model before the projection arrives
- **`ui/MainOpsTest`**: the `recordDeepLink` test is deleted, and `nothingIsLoggedWithoutADeepLink` is renamed to `nothingIsLoggedOnItsOwn`.
- **`chat/ConversationStoreTest.anOlderPageWithoutRecordsReportsAnUnexpectedAnswer`**: it asserts `olderError`, and that `sendError` stays null.

**RED.** The first compile of the new tests failed: unresolved `ChatScreen`, `ChatUiState`, `ModelChoice`, `modelChoices`, `ListSearch`, `msUntilNextMidnight`, and no parameter `unknownRefreshMs`, among about 60 errors. One error was a test bug: `onNewIntent` is protected, so the test now uses `ActivityController.newIntent`.

**First GREEN attempt, `ui.chat.*`: 5 failures.**
- **A real bug.** `theNewestMessageIsInViewAndOlderOnesScrollIn` ran out of heap. The first version kept a `follow` flag, updated from a `snapshotFlow` on `isScrollInProgress`. When a scroll ended, the content lambda re-ran and saw `isScrollInProgress == false` while `follow` was still stale `true`, and yanked the list back to the bottom; the test's scroll then looped. A user's fling would have snapped back the same way. The fix is the unobserved position check described above (no flag).
- The `ListSearch` tests used `advanceUntilIdle`, which does not advance `backgroundScope` delays; they now use `advanceTimeBy` + `runCurrent`.
- The 412 dp test looked for the user bubble while it was above the viewport; it now scrolls to it.
- The long-chapter fixture was 99k characters; it is now 111k.

**`MainActivityTest`**
- "No compose hierarchies found" was fixed by `.visible()`.
- `assertTextEquals("")` fails on an empty field because its `Text` holds the hint, so the test checks `EditableText` instead.
- `lastSessionId` was first saved by a `snapshotFlow` collector outside composition, then by a `LaunchedEffect` in `Shell()`. Both depend on frame timing (the first failed in the class run, the second in the loaded full run), so it is now written in `onStop`. That is deterministic, and the test stops the activity and then reads the preference.

**Focused GREEN.**
- 138/138: `ui.chat.*`, `chat.*`, `ui.shell.*`, `ui.markdown.*`.
- `MainActivityTest` + `MainOpsTest`: 53 + 6, all green.
- No Kotlin warnings from new or changed files.

## Full suite

`./gradlew :app:testDebugUnitTest :app:assembleDebugAndroidTest --max-workers=2`: **823 tests, 0 failures, 83 classes**, BUILD SUCCESSFUL, and the androidTest APK builds. No Kotlin warnings in the build log.

The first full run had 2 failures.
- **`ChatPageTest.aFailedSearchShowsNoResults`: `UncaughtExceptionsBeforeTest`.** The leaked exception is `SettingsJobsTest`'s intended `IllegalStateException("boom")`, which already existed (Task 4's report notes it on stderr). `runTest` blames any earlier uncaught exception in the JVM on itself. `ChatPageTest` now drives a `TestCoroutineScheduler` directly, so it does not depend on the test order.
- **`withoutAnOpenConversationSendingCreatesOne`** (`lastSessionId`): see `onStop` above.

The second full run was green.

## The 412 dp render (throwaway, not committed)

`@GraphicsMode(NATIVE)` + `decorView.draw(Canvas)`, the chat page inside `FogShell` at `w412dp-h915dp-port`, effect level FULL with reduced motion. The test source is kept beside the images as `ChatShotsTest.kt.txt`.

| Image | What it shows |
|---|---|
| `01-conversation.png` | Thinking folded; glass reply with list, links and 「來源 ①②」; user bubble; write card with 分享; failed bash card with its error; 「已停止」 |
| `02-streaming-and-queued.png` | Live reply with its thinking toggle; faded queued bubble with 排隊中; STOP in the dock |
| `03-typing-and-failed-send.png` | 「正在使用 web_search」; failed send with the dsh line and 重送／移除 |
| `04-model-menu-and-banners.png` | Inline model menu (Claude Sonnet selected); follow banner with 重新連線; error line; messages fading under them |
| `05-long-title-and-url.png` | Ellipsized title; unbroken URL wrapped in both bubbles; 5-column table and long code line scrolling sideways inside the bubble |
| `06-long-chapter-bottom.png`, `07-long-chapter-top.png` | A 120-paragraph chapter drawn piece by piece, continuous background, lit top-left corner, rounded bottom with tail |
| `08-load-older-note.png` | 「載入更早的訊息」 with 「更早的訊息沒有載入（unexpected-answer）」 |
| `09-list.png` | The hosted conversation list with the fixed ✕ |

All in `/tmp/claude-0/-home-user/1e630f02-b974-5959-b741-b3d92fa6411e/scratchpad/t8-shots/`.

## Deviations

1. **New file `ui/chat/ChatPage.kt`**, not in the brief's list. It hosts the store/index wiring, search, day groups and the dock send, which keeps the edits to `MainActivity.kt` small while Tasks 10 and 14 edit it. `ListSearch`, `msUntilNextMidnight` and `DockSend` are public there.
2. **`ConversationStore.olderError`** is new, and `loadOlder` no longer writes `sendError`. The ruling asks for a note at 「載入更早的訊息」, and the same code would otherwise also have shown as a send error. One Task 5 assertion was changed to match.
3. **`MarkdownBlock`** was added to Task 7's file. `Block` was private, and the long-reply ruling needs blocks as separate lazy items.
4. **Task 6's list close button** was fixed in `ConversationListScreen.kt` (✕ rendered as "…").
5. **Extra defaulted members.** `ChatUiState` gains `followError`, `olderError` and `modelLabel`; `ChatScreen` gains `onRetryFollow`, `onRetrySend` and `onDismissSend` (after `modifier`). The brief's calls compile unchanged.
6. **Nine extra tags**, listed above.
7. **Where errors show.** A send failure puts the text back into the input (unless something new was typed) and shows `sendError` under the chat header. The dock's own error line (`ComposerUi.error`) is used only when a conversation could not be created, so nothing is shown twice.
8. **Where the model menu lives.** It is inline under the header rather than a floating popup, because no floating control may cover another.
9. **Last conversation.** `lastSessionId` is saved in `onStop` and restored only on a fresh create (`savedInstanceState == null`); a deep link replaces it. An archived open conversation closes back to the list.
10. **Closing the list.** 「關閉」 on the list with no open conversation goes to the overview, so the button is never dead.

## Concerns

- `SettingsJobsTest.aFailingJobAlsoLetsGo` leaks an uncaught "boom" into the test JVM. It already existed. Any `runTest` test that happens to run right after it in the same fork fails with `UncaughtExceptionsBeforeTest`; mine no longer uses `runTest`, but others (for example `ConversationStoreTest`) still could.

- `MainActivityTest` now has a class-wide `createEmptyComposeRule()`; Tasks 10 and 14 add tests to the same class, so expect a textual merge there. Existing tests are unaffected (the full class is green).
- Links inside a bubble sit in a merged semantics node (the bubble is read as one by TalkBack). The link actions themselves are drawn by Task 7's `MarkdownView` and are not exercised under TalkBack.
- **Live → durable handoff.** If dsh sends the stream's `end` before the `assistant/message` event, the reply disappears for that gap. This is a store ordering question, not seen in tests.
- Pieces of a long reply have a flat fill (no blur, no shadow) where a short reply has full glass. The render shows them as one continuous bubble; the difference is visible only side by side.
- A table of 500 rows or a single code line of 100k characters is still one lazy item (tables cannot be split without breaking their column widths).
