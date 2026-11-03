# Stage 1 Task 5 report: conversation reducer and store

Branch `lane-t5` (base c9e6212).

## What changed
- `chat/ConversationState.kt`: state and item types, exactly as in the brief, plus one extra defaulted field (see deviations).
- `chat/ConversationReducer.kt`: `reduce`, `prepend`, `reopened`. Pure and total (catches `RuntimeException`; a snapshot whose `records` is not an array is ignored).
- `chat/ConversationStore.kt`: `ChatRemote`, `PendingSend`, `ConversationStore`.
- `remote/DshRemote.kt`: `DshRemote : ChatRemote`; six existing methods marked `override`.

## Merge note for Task 4
One line when the branches merge: in `ui/shell/ShellSession.kt` change `interface ShellRemote` to `interface ShellRemote : ChatRemote` (import `app.dshmobile.chat.ChatRemote`) and drop the six duplicated members if ShellRemote declares them (follow, prompt, cancel, page, selectModel, upload), keeping the signatures identical.

## Design
- Store collects `follow()` on a single-threaded context (`Dispatchers.Default.limitedParallelism(1)`, injectable as a trailing constructor parameter `reduceContext` for tests).
- Live text and reasoning go into one StringBuilder each per attempt; `state.live` is published at most every 50 ms (one coalesced publish), so a 100k-character reply is linear. `end`, snapshot and `Reopened` drop the builders and cancel a pending publish, so a late publish cannot resurrect a live reply.
- `Reopened`: live and running reset, items kept, pending bubbles kept; the next snapshot replaces items, and pending bubbles whose requestId is in `acceptedRequestIds` leave.
- `acceptedRequestIds` is unioned across snapshots (a reopened snapshot window may no longer include an older accepted prompt).
- `sendError`: `DshRpcException` goes through `DshErrors.safeCode`; any other exception gives its class simple name. `stop`, `loadOlder` and `selectModel` failures are reported in the same flow.

## Tests
RED: with the three main files removed, `--tests 'app.dshmobile.chat.*'` fails to compile (unresolved `ConversationState`). GREEN: 28 tests in `app.dshmobile.chat.*` (all 17 reducer and 8 store names from the brief, plus `anAssistantToolCallBlockAlsoMakesTheCardOnlyOnce` and `liveDeltasArePublishedAtABoundedRate`: 10,000 deltas yield at most 3 state publishes). One test initially failed (`records:5` snapshot wiped the items) and was fixed in the reducer.

## Deviations
1. `ConversationState` has an extra `internal val approvals: Map<String,String> = emptyMap()` (approval id to callId): `approval/decided` carries only the approval id, not the callId, so the card could not be found otherwise.
2. `ConversationStore` has a trailing `reduceContext` parameter with a default; `ChatRemote` lives in `ConversationStore.kt`.
3. Snapshot `firstSeq` = minimum `seq` among the snapshot's records (the protocol doc has no explicit field); title falls back to `projections.values.title` when no `session/title` event is in the records.
4. An `approval/asked` that arrives before its tool card creates a card (name from `toolName`); a later `tool/call` fills in name and summary. `tool/result` for a callId without a card is ignored (also for a card that lives in an older, not yet loaded page).
5. Duplicate-key user/assistant/notice events are dropped (race between snapshot cursor and live event).
6. Markdown-link fallback for sources only applies to `web_search`; `meta.sources` applies to any tool.

Full unit suite: 751 tests, 0 failures.

## Concerns
- `StreamSignal.Failed` is ignored by the store (the flow completes, no follow resumes); a screen-level retry is up to Task 4/8.
- `session/page` answer shape was assumed to be snapshot-like (`records[].event`, `hasMore`), not observed.

## Fix round 1
- Follow health: `followError` (safe code, `ended`, or exception class name), re-follow with backoff 1 s, 2 s, 5 s, then 10 s, public `retry()`, collect wrapped against non-cancellation exceptions; dead flows also reset live/running. Cleared on the next value.
- Replayed events: `ConversationState.lastSeq` (internal; snapshot max record seq or cursor). Event frames with seq <= lastSeq are dropped.
- `loadOlder` with a page lacking `records` sets `sendError = "unexpected-answer"`.
- Stream handling removed from `ConversationReducer.reduce` (it now ignores `assistant-stream` frames, so the store is the only production path). The concatenating version stays as `internal reduceLive`, documented as test reference only.
- Pending bubbles: after 60 s without dsh recording them, `PendingSend.failed = true` (new defaulted field); `retrySend(requestId)` resends under a new id, `dismissSend(requestId)` drops it.
- RED: new tests fail to compile against the old main code (`followError`, `reduceLive` unresolved). GREEN: 8 new tests (replayed approval, replayed turn/start, reduce ignores stream, failed follow recovers, ended + retry, throwing flow, page without records, bubble timeout and retry); chat suite passes, full suite 759 tests, 0 failures.

## Fix round 2
- The 60 s pending-bubble timeout now runs only while `state.running` is false: it starts once the conversation is idle and restarts after each turn ends, so a queued prompt behind a long turn is not marked failed (and retry cannot double-send it). A bubble sent while idle still fails at 60 s.
- RED: `aQueuedBubbleDuringALongTurnIsNotFailedUntilSixtySecondsAfterItEnds` failed against the old timer. GREEN: it and `anIdleSentBubbleStillFailsAtSixtySeconds` pass; full suite 761 tests, 0 failures.
