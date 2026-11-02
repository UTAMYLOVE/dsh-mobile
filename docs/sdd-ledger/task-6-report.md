# Task 6 report: OpenClaw-style memory plugin (+ credential-write guard)

Status: DONE_WITH_CONCERNS (concerns at the end; none block the task).

## What was implemented
- `plugins/packages/memory` (`@dshm/memory`, private, ESM; deps `@dshm/common: workspace:*`, `proper-lockfile`; dev `@types/proper-lockfile`).
  - `src/store.ts`: `MemoryStore`, `MemoryPathError`. Startup context (MEMORY.md, USER.md, today, yesterday; local-time dates), `search`, `get`, `append` (daily/long/user), `resolveInside` (rejects `..`, absolute, non-`.md`, symlinks out of the workspace including intermediate directories and dangling links). All writes go through an in-process promise chain plus the `proper-lockfile` lock on `memory/.lock` (lock dir is `memory/.lock.lock`; retries; `onCompromised` is a no-op so a lost lock can never crash dsh). Extra helpers for dream: `today()`, `dailyDates()`, `readDaily()`.
  - `src/dream.ts`: `dream(store, llm, statePath)`, `DreamParseError`. Notes strictly after `lastProcessedDate` and before today; no notes means no llm call. Reply may be wrapped in a json fence; strict shape check (`promote: string[]`, `remind: {title, body}[]`, both required). Promotions go to MEMORY.md as `### YYYY-MM-DD 整理` through the lock; state written atomically (tmp + rename) only after a successful parse and append. A prompt-size cap (80k chars) processes a big backlog in chunks, advancing state only as far as it read.
  - `src/guard.ts`: `registerCredentialGuard` on `connection/request`; blocks `/api/credentials/set`, `/api/credentials/unset`, `/api/account/startSignIn` with 403 `{"ok":false,"error":"dshm: keys are managed by the app"}`. Hardened over the hello probe: the path is stripped of query/hash, percent-decoded, dot-segment and duplicate-slash normalised, trailing slash stripped, compared case-insensitively. (The hello code used `new URL(req.url, ...)`, which parses `//api/...` as a host and so would have missed that form.)
  - `src/index.ts`: `name = 'dshm-memory'`, `inject = ['tools','systemPrompt']`; tools `memory_search`, `memory_get`, `memory_write`; `systemPrompt.context` (order 9000, `interpolate: false`); compaction listener; guard; routes `GET|HEAD /dshm/health/memory`, `POST /dshm/heartbeat` (409 busy, 502 `dream_parse`, 500 `dream_failed`, `requireAuth` first line of each handler). `DSHM_WORKSPACE` unset or not absolute: warn, tools/routes/context disabled (the credential guard stays active, deliberately).
- `plugins/hello/` deleted (`git rm`).
- Build: `plugins/package.json` `build` script (esbuild, root devDependency `esbuild ^0.28.2`) bundles to the single ESM file `plugins/packages/memory/dist/index.js` (73 KB, `node:*` external, a `createRequire` banner so bundled CJS deps like graceful-fs can `require` builtins). `.gitignore` gained `dist/`.
- CI `rootfs` job: pnpm 11.28.10 + node 24 setup, `pnpm -C plugins install --frozen-lockfile && pnpm -C plugins build`, copy to `out/staging/plugins/memory/index.js`, `build-rootfs.sh ... out/staging`. `rootfs/smoke-test.sh` checks `/opt/dshm/plugins/memory/index.js`. **The rootfs job is CI-verified only** (needs docker + qemu; not run locally). I checked the YAML parses and `bash -n` on the smoke test; `pnpm install --frozen-lockfile` and `build` were run locally.

## dsh model-call API found (for dream)
Installed `@deepseek-ai/dsh@0.2.1-rc.5` into `/tmp/claude-0/-home-user/1e630f02-b974-5959-b741-b3d92fa6411e/scratchpad/dsh-scratch` (outside the repo).
- Service `ctx.llm` (`LlmRuntime`), package `@deepseek-ai/dsh-llm`, `lib/types/index.d.ts` (prepareCall at ~line 407, `stream(options: GenerateOptions): AsyncIterable<StreamChunk>` at ~line 433); `GenerateOptions`/`StreamChunk`/`FinishReason` in `lib/types/types.d.ts`. README: "Any composition that calls a model provider ... streams its requests through this service."
- Route of the "current default model" (row `agent-default-model`): `ctx.agentDefaultModel.currentSelection(): { provider, model, reasoningEffort? }` (`@deepseek-ai/dsh-agent-default-model`, `lib/types/index.d.ts`).
- Pattern (mirrors `@deepseek-ai/dsh-session-title-llm/lib/index.js`): `call = await ctx.llm.prepareCall({provider, model, reasoningEffort?}, signal)`, then `for await (chunk of call.stream({...call.config, system, messages:[{role:'user', content:[{type:'text', text}]}], signal}))`, concatenating `chunk.type === 'text-delta'` text; the terminal `{type:'finish', reason}` has `reason.kind` of `stop | tool-calls | max-tokens | error | aborted` (error/aborted carry `failure.message`). One-shot request-only user input (no id/source) is a valid `RequestMessage`. `purpose` only allows `'compaction' | 'session-title'`, so I leave it unset. Implemented as `createLlm(ctx)` in `src/index.ts`, with a 120 s `AbortSignal.timeout`.
- Verified live: the real path ran against dsh and reached the DeepSeek adapter, which answered `llm-deepseek: no API key for provider route "deepseek-official"` (expected: no key supplied), surfaced as 500 `dream_failed`. A successful completion could not be shown without a real key; unit tests use a fake llm.

## Important finding: `ctx.get('webServer')` races boot order
The brief said to gate route registration with `ctx.get('webServer')`. Live, that returned undefined at apply time (webServer is not in `inject`, so it can mount after our plugin): the first live run had the guard working but `/dshm/health/memory` returned 404. Fix: `ctx.inject(['webServer'], (c) => registerRoutes(c, store))` (Cordis v4 `ctx.inject(deps, cb)`: runs the callback once the service exists, re-runs on service change, and does not hold back the rest of the plugin; in headless it simply never fires). Task 7 (notify plugin) should do the same. Tool registration and `systemPrompt` stay in `inject` as they are present in all compositions.

## Tests (`pnpm -C plugins test`): 61 passed (4 files); memory: store 17, dream 11, plugin 28
- store: startup context (today+yesterday only, month boundary, symlinked MEMORY.md not leaked), search (all terms, newest first, line numbers, limit, empty query, outward symlink), get/resolveInside (`../`, absolute, outward symlink, intermediate symlinked dir, dangling link, non-md, slicing, missing file), append (daily prefix, long/user, write through outward symlink refused, 50 concurrent appends lose nothing, two store instances sharing the file lock).
- dream: promote + state advance + today excluded, no state, non-JSON keeps everything, five invalid shapes, no new notes = no llm call, corrupt state file, chunked backlog.
- plugin (fake ctx + real `node:http`): contract/registration, missing webServer, missing/relative `DSHM_WORKSPACE`, 401 via `requireAuth` with dream/llm never called, health 200/405, heartbeat 200 body + files written, 502/500 (no leak of error text), 409 under concurrency and slot released afterwards, 500 when llm service missing, credential guard (9 blocked forms, 5 pass-through, undecodable path), compaction (line text exact, write failure only warns, garbage events), tools end to end and tool error behaviour. Time: `vi.useFakeTimers({toFake:['Date']})`/injected `now`, never real dates.
- `pnpm -C plugins typecheck`: clean. `pnpm -C plugins build`: clean (single file).

## TDD evidence
- RED: tests written first, then `pnpm -C plugins test`:
  ```
  FAIL  packages/memory/test/store.test.ts  Error: Cannot find module '../src/store.js'
  FAIL  packages/memory/test/dream.test.ts  Failed to load url ../src/store.js ... Does the file exist?
  FAIL  packages/memory/test/plugin.test.ts Failed to load url ../src/index.js ... Does the file exist?
  Test Files  3 failed | 1 passed (4)   Tests  5 passed (5)
  ```
  Expected: the implementation modules did not exist yet (the 5 passing are `@dshm/common`'s).
- GREEN: store 17/17 after `store.ts`, dream 11/11 after `dream.ts`, then with `index.ts`/`guard.ts`: `Test Files 4 passed (4), Tests 61 passed (61)`. After the live-check fix (ctx.inject) the same 61 pass (the fake ctx gained an `inject` method mirroring Cordis).

## Live verification against real dsh 0.2.1-rc.5 (Node 24, Linux)
Plugin copied to a dir with NO package.json (same layout as `/opt/dshm/plugins/memory/index.js`), which loaded fine as ESM. `$S` = the scratchpad dir.
```
$ cat $S/live/memory.cordis.yml
- insert:
    - id: dshm-memory
      name: '$S/live/plugin/memory/index.js'          # absolute
$ DSHM_WORKSPACE=$S/live/ws DSH_HOME=$S/live/home DSH_TELEMETRY_MODE=DISABLED \
  node $S/dsh-scratch/node_modules/@deepseek-ai/dsh/lib/bin.js --profile web \
  --patch $S/live/memory.cordis.yml --no-open --host 127.0.0.1 --port 0
dsh web: http://127.0.0.1:41313/?token=FxCrm...
--- (a) GET /dshm/health/memory, no cookie
HTTP/1.1 401 Unauthorized
{"ok":false,"error":"dshm_unauthorized"}
--- (b) GET launch URL with -c jar -> 303; GET /dshm/health/memory with -b jar
HTTP/1.1 200 OK
{"ok":true}
--- POST /dshm/heartbeat, no cookie -> 401 {"ok":false,"error":"dshm_unauthorized"}
--- POST /dshm/heartbeat with cookie (yesterday note present, no model key)
HTTP/1.1 500 Internal Server Error
{"error":"dream_failed"}
   (debug copy of the bundle showed: model call error: llm-deepseek: no API key for provider route "deepseek-official"; ...)
--- (c) POST /api/credentials/set | /api/credentials/unset | /api/account/startSignIn, with cookie
HTTP/1.1 403 Forbidden
{"ok":false,"error":"dshm: keys are managed by the app"}      (x3)
--- control: POST /api/credentials/describe, with cookie -> 200 (passes the guard)
```
Before the `ctx.inject` fix the same run showed `GET /dshm/health/memory` -> 404 with the cookie (route not registered) while the guard already returned 403; that is the race described above. dsh was killed afterwards (`ps` shows no dsh process) and the temp dirs (`$S/live`) deleted. The npm scratch install stays in the scratchpad, outside the repo.

## Files changed
- Added: `plugins/packages/memory/{package.json,src/{store,dream,guard,index}.ts,test/{store,dream,plugin}.test.ts}`, `docs/sdd-ledger/task-6-report.md`
- Deleted: `plugins/hello/{cordis.yml,index.js,package.json}`
- Modified: `plugins/package.json` (build script, esbuild devDependency), `plugins/pnpm-lock.yaml`, `.gitignore` (`dist/`), `.github/workflows/ci.yml`, `rootfs/smoke-test.sh`

## Self-review findings (fixed)
- Guard URL parsing: replaced `new URL(req.url,'http://x')` (host-confusion on `//api/...`) with manual normalisation + tests for encoded, dot-segment, case and double-slash forms.
- `proper-lockfile`'s default `onCompromised` throws from a timer and would crash dsh; replaced with a no-op.
- `ctx.get('webServer')` boot-order race found by the live run; fixed via `ctx.inject`.
- Multi-line `promote` strings are flattened to one bullet so a model reply cannot inject headings/structure into MEMORY.md.
- Write paths (append) also go through `resolveInside`/symlink checks, and the `memory/` dir is checked before the lock is created so a symlinked `memory/` cannot make the lock dir land outside the workspace.

## Concerns
1. Tool errors are thrown as plain `Error("memory: ...")` (dsh converts a throw into an `isError` tool result); the brief's "不丟出未處理例外" was read that way. If a non-error string result was intended, it is a two-line change in `toolErrors`.
2. `compaction/start` via `session/event` is an unverified dsh API (as the brief says); it is implemented defensively and unit-tested only with a fake. Not exercised live (needs a real model to trigger a compaction).
3. The memory tools and the startup-context injection were not exercised live (no model key); the registration mechanism is the one verified by the Task 0 hello plugin.
4. CI `rootfs` changes are unverified locally. Also `docs/dsh-findings.md` still mentions `plugins/hello/` as historical recon; left untouched.
5. The heartbeat's dream is unauthenticated-by-cookie only through `requireAuth` (per the brief); a successful model completion path is covered by unit tests with a fake llm and by the live run only up to the adapter's credential check.
