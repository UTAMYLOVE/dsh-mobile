# Stage 1, Task 1: protocol envelope and unary typed calls (first half of DshRemote)

Status: DONE_WITH_CONCERNS (one concern, about the 1 MiB response cap. See Concerns.)
Branch: `lane-t1`.

## What changed

All new files, under `android/app/src/main/kotlin/app/dshmobile/remote/`:

- `Rpc.kt`
  - `RpcEnvelope.request` (:18) builds `{type:"client-request", rpcId, method, payload:{args}}` with exactly those keys.
  - `RpcEnvelope.parse` (:29) never throws:
    - `ok:true` returns `Ok(value ?: JsonNull)`.
    - `ok:false` returns `Err(DshErrors.safeCode(error.code), error.message)`.
    - Anything else returns `Err("unexpected-answer", …)`, without echoing the body. This covers non-JSON, a missing `result`, and an `ok` that is not a boolean.
  - `RpcResult` (:47) and `DshRpcException(code, message) : IOException` (:53).
  - `DshErrors.safeCode` (:62) uses the same regex as `WorkspaceRegistrar.CODE`, `[A-Za-z0-9/_.-]{1,80}`. Anything else becomes `"refused"`. It also provides the constants `REFUSED` and `UNEXPECTED_ANSWER`.
- `Model.kt`
  - `SessionSummary` (:14), with `from` (:33) implementing the field mapping from the brief.
  - `SearchHit` (:56).
  - `PromptPart` (:59).
  - Internal JSON helpers `obj` and `string`, which are reused by `DshRemote`.
- `DshRemote.kt`
  - `class DshRemote(client, scope, zone)` (:27).
  - `call` (:41):
    1. Logs in once, using a mutex and a double-checked `loggedIn` flag.
    2. Sends the request with `rpcId = "dshm-" + UUID`.
    3. On HTTP 401, logs in again under the mutex and retries once.
    4. Any other non-200 status throws `IOException("dsh <method>: HTTP <code>")`.
    5. On `ok:false`, throws `DshRpcException(code, "dsh <method>: <code>")`.
  - The typed calls are at :58–131. Each sends exactly the args shown in the brief's comments.
    - `prompt` always sends `mode:"queue"` and `clientTimeZone = zone.id`.
    - An image part sends `name` only when it is non-null.
    - A file part sends only `receiptId`.
    - `upload` encodes the bytes with `java.util.Base64`.
  - `close()` (:134).

Tests, under `android/app/src/test/kotlin/app/dshmobile/remote/`:

- `RpcEnvelopeTest.kt`: the five tests from the brief.
  - `garbageIsAnErrNotAThrow` checks four more bad bodies besides `<html>`.
- `DshRemoteCallTest.kt`: the seven tests from the brief, plus three additions:
  - `loginHappensOnceAndA401LogsInAgain` (:50) checks three things:
    - Two calls make one login.
    - One 401 causes one more login and a retry.
    - A second 401 becomes a plain `IOException`, with no retry loop.
  - `typedCallsReadTheirValues` (:132) checks the values that come back:
    - `createSession`, `rename`, `searchSessions` and `page` return what dsh sent.
    - A value without `sessionId` throws `DshRpcException("unexpected-answer")`.
  - `anUnfinishedTurnShowsItsPromptAsTheLastLine` (:198) covers the deviation below.

No existing files were modified.

## Tests: RED, then GREEN

RED: `./gradlew :app:testDebugUnitTest --max-workers=2 --tests 'app.dshmobile.remote.*'` failed in `:app:compileDebugUnitTestKotlin`:
```
RpcEnvelopeTest.kt:16:41 Unresolved reference 'RpcEnvelope'.
DshRemoteCallTest.kt:34:26 Unresolved reference 'DshRemote'.
DshRemoteCallTest.kt:63:30 Unresolved reference 'DshRpcException'.
```

GREEN, with the same command: BUILD SUCCESSFUL.
```
app.dshmobile.remote.RpcEnvelopeTest   tests="5"  failures="0" errors="0"
app.dshmobile.remote.DshRemoteCallTest tests="10" failures="0" errors="0"
```
- `system-out` and `system-err` are empty for both suites.
- The new files produce no compiler warnings.
- The build still shows three `w:` lines from `DshVersionsTest.kt` and `RootfsInstallerTest.kt`. Those files were not touched by this task.

Full unit suite, `./gradlew :app:testDebugUnitTest --max-workers=2`: BUILD SUCCESSFUL, 643 tests, 0 failures, 0 errors, 0 skipped.

## Deviations (smallest reasonable decisions)

1. **`lastLine` when the response is empty.** The brief says `response ?: prompt`. dsh sends `"response":""` for a turn that is still running or waiting for approval (recon `appr.jsonl`, turn 2). With a null-only fallback, the list row would show nothing. So an empty response also falls back to the prompt. A test covers this: `anUnfinishedTurnShowsItsPromptAsTheLastLine`.
2. **Exception messages never carry dsh's free-text `message`.** `DshRpcException.message` is `"dsh <method>: <safe code>"`. dsh's message is free text that can contain paths, user content or URLs, and the red line forbids tokens in exception messages. `RpcResult.Err.message` still carries dsh's raw text, as `errResultCarriesCodeAndMessage` requires. Its KDoc says it must not be shown or logged as is.
3. **`parse` sanitises the code itself.** `RpcEnvelope.parse` already runs `DshErrors.safeCode`, so no `Err` ever holds an unsafe code. An `ok:false` with no code becomes `"refused"`.
4. **Missing fields in a value.** If a typed call's value lacks its expected field, the call throws `DshRpcException("unexpected-answer")`. This applies to `items`, `sessionId`, `title` and `receiptId`. Malformed rows are skipped instead: `session/list` rows without `sessionId`, and `session/search` hits without `sessionId`.
5. **`call` checks the method name.** It requires the name to match `[A-Za-z0-9$_-]+(/[A-Za-z0-9_-]+)+`, which allows `$events/result`. This keeps the request path under `/api/`. `DshClient` still enforces the origin.
6. **`close()` sets a flag.** Later calls throw `IOException("dsh remote closed")`. The `scope` is kept as a private property for Task 2's mux and is not cancelled, because the caller owns it.

## Concerns

- **Large responses can fail.** `DshClient.postJsonForText` reads at most 1 MiB of a response body (`peekBody`). `session/list` rows carry full projections, including `turnOutline` and `tokenUsage`. With many or long sessions, the body can exceed 1 MiB. It is then cut off and parses as `unexpected-answer`. `session/page` with a large `maxMessages` is exposed to the same limit. This task did not change `DshClient`, because that file is outside its scope. A later task, or the controller, should decide whether to raise the cap for RPC.
