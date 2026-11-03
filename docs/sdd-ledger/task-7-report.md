# Task 7 report: notify plugin

Status: DONE (CI `rootfs` changes are CI-verified only, see Concerns).

## What was implemented
- `plugins/packages/notify` (`@dshm/notify`, private, ESM, dep `@dshm/common: workspace:*`).
  - `src/send.ts`: `sendNotify(gateway, {title, body, sessionId?}, fetchImpl = fetch)` and `NotifyError`. Title truncated to 60 and body to 500 Unicode code points (`Array.from`, so surrogate pairs are never split; a cut text ends in `…` counted in the limit). POST `<gateway>/notify` (trailing slashes on the gateway tolerated), `content-type: application/json`, `sessionId` omitted when absent. Non-2xx throws `NotifyError("gateway answered HTTP <status>")` (`redirect: "manual"`, so a 3xx is a failure too). Transport errors become `NotifyError("could not reach the gateway (ECONNREFUSED)")`; only a sanitised error code is kept, never the raw message, so the URL/token cannot leak. 10 s timeout implemented with an own `AbortController` + `setTimeout` (testable with fake timers), message "gateway timed out after 10s". The response body is cancelled unread.
  - `src/index.ts`: `name = 'dshm-notify'`, `inject = ['tools']`. Tool `notify_user({title, body})` with a description saying it sends a phone notification and must be used only when the user's attention is genuinely needed. Returns `"sent"`; on failure returns a readable string (`notification failed: <NotifyError message>`); bad/missing args return a readable string too; it never throws. `DSHM_GATEWAY` unset or empty: tool still registered, `ctx.logger.warn` once at load, tool returns `"notifications unavailable"`. Route `GET|HEAD /dshm/health/notify` -> `{"ok":true}`, `requireAuth` first line, 405 otherwise. Routes are registered through `ctx.inject(['webServer'], ...)` (the Task 6 boot-order finding). The gateway value is read once, never logged.
- Build: `plugins/package.json` `build` now runs the same esbuild command for memory then notify (`packages/notify/dist/index.js`, 5.4 KB single ESM file, same `createRequire` banner). Memory code untouched.
- CI `rootfs` job stages `out/staging/plugins/notify/index.js`; `rootfs/smoke-test.sh` checks `/opt/dshm/plugins/notify/index.js`. `plugins/pnpm-lock.yaml` gained the `packages/notify` importer.

## How the session id is obtained
From the tool's second `execute` argument, `exec: ToolRunContext` (`@deepseek-ai/dsh-tools`, `lib/types/index.d.ts`: `ToolRunContext extends ToolExecution extends ToolExecutionInput`). `ToolExecutionInput.agent?: Agent` ("the agent on whose behalf the call runs"), and `Agent.id: SessionId` ("Session-backed Agent identity", `@deepseek-ai/dsh-agent`, `lib/types/types.d.ts`). So `exec.agent?.id`. (Other dsh tools do the same, e.g. `dsh-tool-bash` uses `exec.agent?.id`.) `agent` is optional (absent for calls without a live agent), so the plugin sends `sessionId` only when `exec.agent.id` is a non-empty string and omits it otherwise. I read it defensively as `unknown` instead of importing the dsh types (not a dependency of this repo, same approach as memory).

## Tests (`pnpm -C plugins test`): 97 passed (6 files); notify: send 12, plugin 17
- send (real `node:http` gateway): correct JSON + path `/s3cret-token/notify` + content-type; sessionId omitted; trailing slash; 500 -> NotifyError with "500" and no URL/token/host; 404 and 302; unreachable port wrapped without token; title 60 (ASCII and 😀), body 500 (🎉), no split pair (valid UTF-8 round trip); exact-limit text untouched; 10 s timeout with fake timers (not aborted at 9.999 s, aborted after); thrown error whose message contains the URL is sanitised.
- plugin (fake ctx + real `node:http`): contract/registration (tool, route, description wording, parameters); no webServer; session id passed to the gateway; omitted for missing agent / undefined exec / non-string id; truncation end to end; gateway 500 and unreachable return a string without the token and logs stay token-free; invalid args (8 shapes) return a readable string and send nothing; no `DSHM_GATEWAY` (unset and empty): registered, one warn, `"notifications unavailable"`; health route 401 unauthenticated, 503 without the connection service, 200 `{"ok":true}` (with and without a gateway), HEAD 200, POST 405, unauthenticated POST 401 (auth before method).
- `pnpm -C plugins typecheck`: clean. `pnpm -C plugins build`: clean, both bundles.

## TDD evidence
- RED (tests first): `Test Files 2 failed | 4 passed (6)`, `Tests 68 passed (68)`; `Error: Cannot find module '../src/send.js'` (and `../src/index.js`), i.e. the implementation did not exist (the 68 passing are common + memory).
- GREEN: `Test Files 6 passed (6)`, `Tests 97 passed (97)` (68 + 12 + 17).

## Live check against real dsh 0.2.1-rc.5 (scratch install in the scratchpad `dsh-scratch`)
Both bundled plugins (copied to a dir without package.json) loaded via a `--patch` cordis yml; a tiny `node:http` server was the fake gateway (`DSHM_GATEWAY=http://127.0.0.1:<port>/tok-live`).
```
$ node dsh-scratch/node_modules/@deepseek-ai/dsh/lib/bin.js --profile web --patch p.cordis.yml --no-open --host 127.0.0.1 --port 0
dsh web: http://127.0.0.1:44603/?token=<redacted>
--- GET /dshm/health/notify, no cookie
HTTP/1.1 401 Unauthorized
{"ok":false,"error":"dshm_unauthorized"}
--- GET launch URL (-c jar) -> 303; GET /dshm/health/notify with -b jar
HTTP/1.1 200 OK
{"ok":true}
--- occurrences of the token string in dsh's output: 0
```
dsh and the fake gateway were killed (`ps` shows none) and the temp dir removed. The `notify_user` tool itself was not invoked live (needs a model); its HTTP path is covered by the real-server unit tests.

## Files changed
- Added: `plugins/packages/notify/{package.json,src/{send,index}.ts,test/{send,plugin}.test.ts}`, `docs/sdd-ledger/task-7-report.md`
- Modified: `plugins/package.json`, `plugins/pnpm-lock.yaml`, `.github/workflows/ci.yml`, `rootfs/smoke-test.sh`

## Self-review findings (fixed during the work)
- Transport error messages from fetch can carry URL fragments; replaced with a fixed message plus a regex-validated error code only.
- Timeout built on a plain `setTimeout` instead of `AbortSignal.timeout` so it is deterministic under fake timers; timer cleared in `finally`.
- `redirect: "manual"` so a gateway redirect can never forward the token path to another host.

## Concerns
1. The CI `rootfs` staging and smoke-test lines are CI-verified only (need docker); locally I checked the YAML parses and `bash -n` passes, and that the bundle builds to the staged path.
2. The tool's `execute` returns an error string for failures (as the brief asks), whereas memory throws; as a result a failed notification is not an `isError` tool result in dsh. Easy to change if the controller prefers the other.
3. The `sessionId` extraction was verified against dsh's `.d.ts` and by analogy with other dsh tools, but not exercised with a real agent call (no model key).
