# Task 0 report: dsh internals recon

Status: DONE_WITH_CONCERNS (all required capabilities exist; concerns below change some spec assumptions)

## What was produced
- `docs/dsh-findings.md`: every section asked for (§Pin, §Plugins, §Config, §Server, §Memory, §UI, §Providers, §Search), each claim tagged [實測] (verified locally) or [文件] (docs or source only).
- `plugins/hello/` (`index.js`, `package.json`, `cordis.yml`): a temporary plugin with no imports. It registers:
  - the tool `hello()`, which returns "hi"
  - `GET /dshm/health/hello`, which returns `{"ok":true}`
  - `GET /dshm/health/hello-auth`, which goes through dsh's own cookie auth
  - a runtime-context probe and an index `<style>` probe

## Environment
- Install: `npm install @deepseek-ai/dsh@0.2.1-rc.5 --ignore-scripts` into `%TEMP%\dsh-recon` (614 packages, outside the repo).
- Official repo: shallow clone at `ce7073d` (2026-10-30), used for docs only.
- Isolated state: `DSH_HOME=%TEMP%\dsh-recon\home` and `DSH_AGENTS_HOME=%TEMP%\dsh-recon\agents`. The user's `~/.dsh` was never touched.
- Workspace: an empty `%TEMP%\dsh-recon\ws` with no `.env`. I read no `.env` files and used no real keys.
- Mock model: there is no built-in mock model (`dsh-llm-mock-server` is only a devDependency). I wrote a small local Anthropic-Messages mock on 127.0.0.1:3999 in the scratchpad, outside the repo, and pointed dsh at it:
  - `DEEPSEEK_BASE_URL=http://127.0.0.1:3999`
  - `DEEPSEEK_API_KEY=sk-mock-not-a-real-key` (a dummy value)
  - The mock answers the first turn with `tool_use hello`, then echoes the tool result back.

## Verification (commands and results)
1. `dsh web` boots on Node 24.22.1. Command: `node .../dsh/lib/bin.js --profile web --patch C:/dsh-mobile/plugins/hello/cordis.yml --no-open --host 127.0.0.1 --port 3080`. stdout shows `dsh web: http://127.0.0.1:3080/?token=<redacted>`.
   - Gotcha: `dsh web --patch ...` fails with `unknown option '--patch'`. Launcher flags must come before app flags.
2. `curl http://127.0.0.1:3080/dshm/health/hello` returns `HTTP/1.1 200` with body `{"ok":true}`. I re-checked this with the final committed file.
3. Auth checks:
   - `GET /` without a cookie: 401
   - `/api/*` without a cookie: 401
   - wrong Host header on `/api`: 403
   - token exchange: 303, then the `dsh-auth-*` HttpOnly cookie is set
   - `/dshm/health/hello-auth`: 401 without the cookie, 200 with it
   - plugin route `/dshm/health/hello` with `Host: evil.example`: still 200, so plugin routes are unprotected
4. Ports: `Get-NetTCPConnection` shows only `127.0.0.1:3080`, with no UDP. The WebSocket is `/api/remote.mux` on the same port. The only child process seen was a native folder-picker worker, which starts when "選擇工作區" (Select workspace) is clicked on a desktop OS.
5. Tool via the web UI: I drove the Browser pane, created a workspace through RPC `workspace/create`, then sent "Call the hello tool." in a new conversation.
   - The UI showed `hello tool returned: [{"type":"text","text":"hi"}]`.
   - The mock logged a `tool_result` with content `hi`.
   - Result: tool registration is verified end to end in a web `standard`-preset session.
6. Agent Teams: I added `@deepseek-ai/dsh-experimental-agent-team-profile` to `profiles/web/package.json` bundles.
   - Web booted normally.
   - The model's tool list included `spawn_teammate`, `wait_agent`, `team_task_*`, `subagent`, `subagent_fork` and `hello`.
7. Session-start injection: `ctx.systemPrompt.context()` text (`DSHM_HELLO_CONTEXT_PROBE`) appeared in the first user message the model received. Verified.
8. Theming hook: a `webserver/index-inject` `{kind:'style'}` row appeared in index.html, and the browser `getComputedStyle(body)` read the injected variable. Verified.
9. Conversation URL: it stayed `http://127.0.0.1:3080/` after creating and using a session, so there is no URL routing.
10. Headless run (`--profile headless`) with the same patch: the plugin stays pending because there is no `webServer` there. This is expected and documented.
11. Cleanup: dsh, the mock and the browser tab are all stopped. A final `Get-NetTCPConnection` and process check showed nothing left on 3080 or 3999.

## Files changed (repo C:\dsh-mobile)
- `docs/dsh-findings.md` (new)
- `plugins/hello/index.js`, `plugins/hello/package.json`, `plugins/hello/cordis.yml` (new; the overlay uses the absolute path `C:/dsh-mobile/plugins/hello/index.js`)

## Concerns / spec impacts (for the director)
1. **dsh does NOT natively read `MEMORY.md`.** No occurrence exists in any installed package or in the official repo. Natively it reads `AGENTS.md` and `CLAUDE.md` (plus `.local` variants) from `$DSH_HOME`, `~/.agents` and the project chain, once on the first pre-step.
   - Spec 5.3, "shared with dsh's native MEMORY.md", must change.
   - Proposal: our memory plugin injects `MEMORY.md`, `USER.md` and the daily notes via `ctx.systemPrompt.context()`. This mechanism is verified.
2. **There is no pre-compaction hook.** The alternative is to listen to `session/event` for `compaction/start` (concurrent with compaction, not before it), or `agent/created` with a compaction source, or to save memory on every turn or heartbeat. None of these alternatives were tested.
3. **There is no per-conversation URL.**
   - The notification deep link needs a custom scheme plus a script or client plugin that calls the client service `ctx.uiWorkspace.openSession(id)`. This is unverified, and whether an inline injected script can reach the client Cordis ctx is unknown.
   - Fallback: open the dsh home page only.
4. **Built-in auth already exists**: a per-process launch token printed on stdout, then an HttpOnly SameSite=Strict cookie, plus a Host/Origin fence.
   - The spec's passcode and `--require` wrapper are mostly unnecessary. The App reads the `dsh web:` line from stdout and loads that URL in the WebView.
   - BUT routes we register via `webServer.register` are unauthenticated. Our handlers must call `ctx.get('connection').requestRejection(req)` (verified) or check an App passcode.
5. **The key-proxy plan needs two environment variables**: `DEEPSEEK_BASE_URL` for chat and `DEEPSEEK_SEARCH_BASE_URL` for web search, plus `baseURL` for pi-ai/anthropic.
   - dsh sends the dummy key in `x-api-key`.
   - dsh adds `dsh_session_log` and `dsh_plugin_packages` body fields and an anonymous user-id header. These are default-on uploads to DeepSeek; the proxy must strip them for Anthropic. There is also otel telemetry, `DSH_TELEMETRY_MODE=FEEDBACK_ONLY` by default. Worth a privacy decision.
6. **A native directory picker can spawn on desktop.** Recommend the App patch disables `directory-picker` and inserts `dsh-host-directory-picker-browse` (patch verified).
7. **DeepSeek pricing now has peak and off-peak tiers** (off-peak is half), and Search/Fetch is billed per request from 2026-11-01 even inside Harness. The cost meter must handle the time windows.
8. The repo docs (config-catalog) come from main as of 2026-10-30, one day after rc.5. Key config keys were cross-checked against the installed `--dump-config`; subagent `maxActiveSubagents` and `maxDepth` were checked only via docs.

## Commit
`docs: dsh internals findings`. The SHA is reported in the reply.

---

# Fix round 1 (2026-11-02)

## What changed
- `docs/dsh-findings.md`:
  - New §Server subsection, "隱私與金鑰" (privacy and keys), covering items (a)/(b)/(c) below.
  - §Config now has a concrete patch snippet for every setting the config writer will emit, plus a note on Agent Teams and maxDepth.
  - §Providers: every price, peak/off-peak window and billing-start claim is tagged `[文件 <URL>, read 2026-11-02]`, with the authoritative source stated. Added a table pinning one model id per role.
  - Anthropic `x-api-key` is now confirmed (verified).
  - §UI lists the light-mode tokens the pink theme should override.
  - §Plugins and §Config carry a machine-path note.
- `plugins/hello/index.js`: added the credential-write guard probe (a `connection/request` waterfall that returns 403 on `/api/credentials/set`, `/api/credentials/unset` and `/api/account/startSignIn`). Header comment updated.
- `plugins/hello/cordis.yml`: comment saying the absolute path is dev-machine-only; the app generates it at runtime (e.g. `/opt/dshm/plugins/...`).

## Item 1: privacy and secrets
**(a) Telemetry off.** dsh offers `DSH_TELEMETRY_MODE=DISABLED`, or a patch `session-telemetry-otel.config.mode: DISABLED`.
- [文件]: DISABLED constructs no exporter.
- [實測]: I watched every TCP connection of the dsh PID and its children during a mock run with the privacy patch. There were zero non-loopback connections.
- Caveat: an unrelated node process on this machine (PID 25500, holding a connection to 149.154.166.110) showed up in an unfiltered first attempt. It is not dsh, and the PID-filtered rerun was clean.

**(b) Stop session-log and inventory uploads.** Set `session-log-deepseek.enabled: false` and `plugin-package-inventory-deepseek.enabled: false`.
- [實測], same prompt and headless mock:
  - baseline body keys include `dsh_session_log` and `dsh_plugin_packages`
  - with the patch, both are gone
- `--patch` overlays take precedence over the UI switch [文件].
- The `x-deepseek-harness-user-id` and `x-deepseek-harness-session-id` headers have no switch [文件], so the gateway must strip them.

**(c) Keys saved via the dsh UI.** `dsh-credentials-local` has no read-only mode, and a stored key outranks env vars.
- I found exactly three write RPCs: `credentials/set`, `credentials/unset` and `account/startSignIn`.
- [實測] The `connection/request` guard returned 403 on all three. `credentials/describe` still worked. The `.credentials.yaml` mtime was unchanged and the probe ref was absent.
- Extra mitigations, [文件 only]: pin the provider rows in `--patch` so `settings/mutate` writes conflict, and optionally disable `ui-settings-account`.
- Residual risk: the agent writing the file via shell when the user grants permission.

## Item 2: pricing and model facts
- All rows are tagged with their source URL and the read date (2026-11-02). Peak hours are given in UTC with the Taipei conversion.
- Authoritative sources: DeepSeek's pricing page for DeepSeek. For Anthropic, platform.claude.com pricing beats the models-overview summary; if claude.com/pricing (linked from it, not read) ever differs, it wins.
- Anthropic prices were read from Anthropic's official pricing page this session with the fetch tool, not from memory.
- Canonical ids for the cost table:
  - lead, worker and summarization: `deepseek-flash` (Claude option: `claude-sonnet-5-5`)
  - search: `deepseek-v4-flash`, the dsh default (a legacy alias priced as `deepseek-flash`), plus the per-search fee

## Item 3: config snippets [實測]
- I booted web with one combined `--patch` containing:
  - `llm-deepseek` baseURL + apiKeyEnv (env var unset)
  - `web-search-deepseek` baseURL
  - pi-ai `anthropic`
  - `agent-default-model`
  - `subagent` maxActiveSubagents=12, maxDepth=2
  - `agent-team` maxMembers=4
  - `agent-preset-registry` default `dshm`
  - an inserted `preset-dshm` (copied from the dump's preset-standard) with `compaction-basic` summarizationProvider/Model
  - plus the privacy, browse-picker and hello overlays
- Results:
  - zero activation warnings
  - requests reached the yaml `baseURL` with `x-api-key`
  - the new session used preset "dshm"
  - `/compact` succeeded ("已壓縮 4 條歷史記錄", 4 history items compacted)
- Limits: the summarization model equalled the lead model, so a distinct override was not proven. The search baseURL was only schema-loaded, never exercised.
- Patching a nested preset row (`id: tool-subagent`) hits only the root row (checked in the dump), so per-preset settings need a full preset row. I recommend generating it from `--dump-config` at runtime.
- maxDepth for Agent Teams (from source, not tested): teammates sit at depth 1 and the team is flat, so `maxDepth: 1` is enough. Use `maxDepth: 2` if teammates should spawn their own subagents. Teammates also count against `maxActiveSubagents`.

## Item 4: machine path
Notes added in the findings (§Plugins, the hello section and §Config item 6) and in the `cordis.yml` comment.

## Minor items
- Light-mode pink tokens are listed: the `--dsw-static-deepseek-*` scale (light primary = `-500` `#4176e6`), or the alias list. Text-colour aliases are explicitly excluded. Not yet visually verified.
- Anthropic path [實測] with a mock:
  - `POST /v1/messages?beta=true`, key in `x-api-key`, plain-text round trip OK, no `dsh_*` fields or `x-deepseek-*` headers
  - Tool round trip via Anthropic NOT verified: my simple mock's `tool_use` caused 3,672 requests in 200 s with no `tool_result`. This is likely a mock SSE format mismatch with pi-ai, but the gateway should enforce rate and spend caps.

## Cleanup
dsh, the mocks and the browser tab were stopped. Nothing is listening on 3080 or 3999.
