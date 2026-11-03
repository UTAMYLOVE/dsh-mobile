# Stage 1, Task 2: mux streams, reconnect and `$events` (second half of DshRemote)

Status: DONE_WITH_CONCERNS (see Concerns; none blocking).
Branch: `lane-t1`, on top of Task 1 (8d85b62).

## What changed

New, under `android/app/src/main/kotlin/app/dshmobile/remote/`:

- `MuxFrames.kt`
  - `MuxFrames.open/item/end/cancel` (:15–31) build exactly the client key sets. `item(id, null)` leaves out `value`, and `item(id, JsonNull)` sends `"value":null`.
  - `MuxFrames.parse` (:37) never throws. It returns null for non-objects, a missing or empty `streamId`, or an unknown `type`. An error's code goes through `DshErrors.safeCode`. A missing `error` object gives `refused` with an empty message.
  - `ServerFrame` (:61).
- `MuxConnection.kt`
  - `MuxState` (:22), `StreamSignal` (:25) and `class MuxConnection` (:50).
  - There is one inbound queue (`Channel(UNLIMITED)`). Every OkHttp listener callback (opened, text, gone) is queued, and a single coroutine on `Dispatchers.IO` drains it in `handle` (:176). That coroutine parses every frame and routes it to that stream's own `Channel(UNLIMITED)`. Because there is one ordered queue, an old socket's frames are always delivered before the `Reopened` of the next socket.
  - `stream` (:81) returns a cold flow:
    - `register` (:117) allocates `s<n>` from a counter in this instance that never repeats, and sends `open` if the socket is up.
    - Server `end` completes the flow.
    - Server `error` emits `Failed(safeCode, "dsh <endpoint>: <code>")` and then completes the flow.
    - Cancelling the collector sends `cancel` (`unregister`, :128), but only when an `open` went out and the stream was not already finished by dsh.
  - `connectLoop` (:134):
    1. The first try runs at once. Each later try waits `backoffMs[min(failures, last)]`, and the counter resets after every successful open.
    2. A 401 on the upgrade calls `relogin()` before the next try. Every other try first runs `beforeConnect()` (see Deviation 1).
    3. Any failure sets `RECONNECTING`.
    4. On open, every live stream that had been opened before gets `Reopened`, then its `open` is sent again with the same id. Streams that dsh ended or failed are not reopened.
  - `dropForTest` (:91) cancels the socket abruptly.
  - `close` (:96) is idempotent. It sets `CLOSED`, stops the loop, closes the socket with 1000, and completes every flow quietly (no `Failed`, no exception). `stream()` after `close` completes empty.
  - `internal lastFailure` (:62) is the only diagnostic string. It holds only an exception class name or an HTTP status. Nothing here logs.
- `DshEvents.kt`
  - Types: `PendingPrompt` (:28), `AskQuestion` (:45), `HostEvent` (:48), `class DshEvents` (:63).
  - Collects `$events` from construction. If dsh ends or fails that stream, it reopens it after 1 s with a fresh id. It stops when the mux is CLOSED.
  - `ready` sets `clientId`.
  - `waterfall approval/request` creates an `Approval`. Its `reason` is `displayReason.zh`, else `.en`, else `reason`.
  - `waterfall user-questions/request` creates a `Question`. Options are read from `{label}` objects or from plain strings, and `multiSelect` defaults to false. An unknown waterfall is ignored, so it is left to another client.
  - `cancel{eventId}` removes the card.
  - `emit api-session/added|status|removed` becomes a `HostEvent`:
    - `SessionAdded` uses Task 1's `SessionSummary.from`.
    - `status` takes `[id, bool]`.
    - `removed` takes `[id]` or `[{sessionId}]`.
    - Other emits are ignored.
  - `hostEvents` has no replay and a 256-event buffer with `DROP_OLDEST`, so a slow collector never stalls prompts.
  - Reconnect handling:
    - `connectionLost` (:152) clears `clientId` and marks every card unconfirmed. The cards stay in the list.
    - `onReady` (:160) starts a grace timer that is tagged with a generation number.
    - `onPrompt` (:179) replaces a card with the same `eventId` in place. Its position is kept, and it is removed from the unconfirmed set.
    - `dropUnconfirmed` (:171) runs after `redeliveryGraceMs` and removes only cards that were not re-delivered. It runs only for the current generation, so a second drop during the grace starts the grace over.
  - Answering:
    - `allowOnce`, `reject`, `answer` and `decline` (:96–110) go through `respond` (:119). It waits up to 5 s for a `clientId` while reconnecting, otherwise throws `DshRpcException("not-connected")`.
    - It then calls `$events/result` with exactly `{clientId, eventId, outcome}`, and removes the card only on success.
    - A `DshRpcException` is rethrown with `DshErrors.safeCode(code)`, so a failed answer keeps its card and throws.

Modified:

- `runtime/DshClient.kt`
  - `openWebSocket(path, listener)` (:88) uses the same client: cookie jar, `NO_PROXY`, the origin-checked `request(path)`, and no `Origin` header.
  - `postJsonForBoundedText(path, json, maxBytes)` (:81) and `ResponseTooLargeException` (:91) implement the controller ruling. `readBounded` (:112):
    - rejects a body when `Content-Length > max`;
    - otherwise calls `source.request(max + 1)`, and a body that fills it is rejected;
    - otherwise reads the whole body using the content type's charset.
  - `postJsonForText` is unchanged in behaviour (`peekBody`, 1 MiB). It now shares a private `postAndRead` with the new function, which is the same coroutine/cancel code moved without edits.
- `remote/DshRemote.kt`
  - The `scope` is now used. New members:
    - `val mux` (:42)
    - `val events` (:50), started at construction
    - `hostEvents` (:52) and `muxState` (:53)
    - `follow` (:56), with the exact `session/follow` args from the brief
    - `control` (:64) and `workspaces` (:67)
  - The mux's `relogin` logs in under Task 1's `loginLock`. Its `beforeConnect` is Task 1's `ensureLoggedIn`, so the first upgrade already carries the cookie and one login is shared with `call`.
  - `call` now reads through `post` (:173), which uses `postJsonForBoundedText` with a 16 MiB cap. A longer answer throws `DshRpcException("response-too-large", "dsh <method>: response-too-large")`.
  - `close()` also closes the mux.

Tests (`android/app/src/test/kotlin/app/dshmobile/`):

- `runtime/FakeMux.kt`
  - Provides `received`, `send`, `sendItem`, `closeAll`, `rejectNextUpgradeWith` and `onOpen`, as the brief specifies.
  - Extra helpers: `sendEnd`, `sendError`, `opened(endpoint)`, `upgrades`, `openSockets`, and `violations`.
  - Like dsh, it checks the exact client key sets and the "no duplicate live id" rule, and closes the socket on a violation. Every mux test asserts `violations` is empty.
  - Also contains a top-level `eventually(what, timeoutMs) { … }` poller.
- `runtime/FakeDshServer.kt`: new `mux: FakeMux? = null` parameter. An authenticated `GET /api/remote.mux` gets `withWebSocketUpgrade`. Without a mux it still answers 404.
- `remote/MuxFramesTest.kt` (3), `remote/MuxConnectionTest.kt` (10), `remote/DshEventsTest.kt` (11): every test the brief lists, plus these:
  - `MuxConnectionTest.dshRemoteOpensFollowControlAndWorkspacesWithTheDocumentedArgs`: exact `follow`, `control`, `workspace/follow` and `$events` args through `DshRemote`; one login; no unauthenticated request; `CLOSED` after `close()`.
  - `DshEventsTest.answeringBeforeReadyWaitsThenFailsWithoutAClientId`: an answer sent before `ready` waits and then uses the new clientId.
  - `readyGivesTheClientIdUsedForAnswers` also covers `reject`. `answerSendsSelectedLabels` also covers `decline`.
- `remote/DshRemoteAnswerSizeTest.kt` (2), for the controller ruling:
  - A 2 MiB answer is read whole.
  - A 16 MiB + 1 answer fails with `DshRpcException("response-too-large")`. That code survives `safeCode` and there is no `token=` in it.
  - An answer of exactly 16 MiB succeeds.
- `remote/DshRemoteCallTest.kt` (Task 1): two `dsh.requests.last()` became `dsh.requests.last { it.method == "POST" }` (:40, :127). This is needed because DshRemote now opens its mux on construction, and the mux upgrade (a GET answered 404 by a mux-less FakeDshServer, retried with backoff) can be the last recorded request. The assertions are otherwise unchanged.

## Tests: RED, then GREEN

RED: `./gradlew :app:testDebugUnitTest --max-workers=2 --tests 'app.dshmobile.remote.*'` failed in `:app:compileDebugUnitTestKotlin`:
```
DshEventsTest.kt:39:31 Unresolved reference 'MuxConnection'.
DshEventsTest.kt:48:51 Unresolved reference 'DshEvents'.
DshEventsTest.kt:50:38 Unresolved reference 'openWebSocket'.
DshEventsTest.kt:89:59 Unresolved reference 'PendingPrompt'.  … (and so on for MuxFrames, StreamSignal, ResponseTooLarge)
```

First run after implementing: 41 tests, 4 failures. All four were an NPE in `FakeMux.closeAll`: MockWebServer's server-side `RealWebSocket.cancel()` has no call to cancel. `closeAll` now closes with 1001 (going away) instead. The abrupt client-side drop is covered by `dropForTest` (`aRedeliveredPromptStaysVisibleThroughTheGrace`).

GREEN, with the same command, run 5 times with `--rerun-tasks` to check timing stability: BUILD SUCCESSFUL every time.
```
RpcEnvelopeTest 5/0   MuxFramesTest 3/0   MuxConnectionTest 10/0   DshRemoteCallTest 10/0   DshEventsTest 11/0   DshRemoteAnswerSizeTest 2/0
```
- `system-out` and `system-err` are empty for all six suites.
- The new and changed main files produce no compiler warnings. The only `w:` lines come from files this task did not touch: `AgentConfigWriter.kt:90`, plus the `DshVersionsTest`/`RootfsInstallerTest` warnings noted in Task 1.

Full unit suite, `./gradlew :app:testDebugUnitTest --max-workers=2`: BUILD SUCCESSFUL, 669 tests (643 + 26 new), 0 failures, 0 errors, 0 skipped.

## Deviations (smallest reasonable decisions)

1. **Extra optional constructor parameter `MuxConnection(…, beforeConnect: suspend () -> Unit = {})`.** It runs before every try that is not answering a 401. DshRemote passes `ensureLoggedIn`. Without it, DshRemote's first upgrade would go out with no cookie, which would cause three problems:
   - a guaranteed 401 round trip;
   - a racy second login against `call`'s own first login, which breaks Task 1's "login happens once";
   - an unauthenticated request in `dsh.unauthenticated`.

   The brief's 4-argument form still works unchanged (the tests use it), and `relogin` is still called only for a 401.
2. **`StreamSignal.Failed.message` is `"dsh <endpoint>: <safe code>"`, not dsh's free text.** This is the same rule as Task 1's exception messages. dsh's text can carry URLs or tokens (tested). `ServerFrame.Error.message` still carries dsh's raw text and is documented as not for display or logging.
3. **`close()` completes collectors quietly.** They see no `Failed` and no exception, as `closeStopsReconnectingAndFailsNothingLoudly` requires.
4. **State.** `CONNECTING` lasts only until the first try ends. Any failed try, including the first, sets `RECONNECTING`. A non-401 refusal such as 404 keeps retrying with backoff on the same instance (planner's call: same dsh, keep retrying).
5. **`aDroppedSocketReconnectsAndReopensLiveStreams` uses `backoffMs = listOf(300)`** instead of `listOf(0)`. `StateFlow` conflates values, so with 0 ms the `RECONNECTING` state can be skipped before a collector sees it, which would make the "goes through RECONNECTING" check flaky. All other mux tests use `listOf(0)`.
6. **`FakeMux.closeAll()` sends a 1001 close frame** instead of cutting the TCP connection, because MockWebServer cannot cut a server socket. The client handles `onClosing`, `onClosed` and `onFailure` the same way.
7. **Answering while reconnecting.** An answer waits up to 5 s for the next `ready`'s `clientId`, otherwise it throws `DshRpcException("not-connected")`. This way a tap during a short reconnect still lands, using the new clientId.
8. **Task 1 test edit.** Two `.last()` lookups were narrowed to POST requests, as described above. Task 1's code behaviour changed only where the brief and the ruling require it: `call` now reads through the 16 MiB bounded reader, and `close()` also closes the mux.

## Concerns

- **The 16 MiB cap.** Answers up to 16 MiB are read into memory, as a String plus `JsonElement`. A `session/list` beyond 16 MiB fails with `response-too-large` instead of being truncated, so the list screen must show that code. If a real novel workspace ever gets near that size, `session/list` needs paging; dsh's `_request` has no paging in 0.2.1-rc.5 as far as the recon shows.
- **Mux-less `FakeDshServer` retries.** Every existing test that builds a `DshRemote` against a `FakeDshServer` without a mux now sees background `GET /api/remote.mux` → 404 retries (250 ms → 5 s). This is harmless, but later tasks that count `dsh.requests` should filter by path or method, or pass `mux = FakeMux()`.
- **No client-side ping.** dsh pings every 2 s, and loopback death gives a reset, so a dead dsh is detected. A hung but alive dsh with an open TCP connection would not be detected. OkHttp `pingInterval` could be added later if T15 shows that case.
