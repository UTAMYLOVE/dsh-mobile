# dsh web UI: protocol, data and extensibility recon (dsh 0.2.1-rc.5)

Date: 2026-11-03. Environment: Linux container, Node 24.22.1, scratch install `scratchpad/dsh-scratch`, Chromium via Playwright.
`dsh web` ran with a throwaway `DSH_HOME=recon/home`, `HOME=recon/fakehome`, and a fake Anthropic-Messages SSE model server (`recon/tools/fake-llm.mjs`, scripted by `recon/tools/scenario.json`). Every browser request, response and WebSocket frame was recorded (`recon/logs/*.jsonl`).

Labels: **[observed]** = seen in captured traffic, DOM or screenshots. **[read source]** = read in `node_modules/@deepseek-ai/*` code, typings or README. **[inferred]** = my conclusion, not directly tested.

In this report, `NM` = `/tmp/claude-0/-home-user/1e630f02-b974-5959-b741-b3d92fa6411e/scratchpad/dsh-scratch/node_modules/@deepseek-ai`.

---

## 0. Decision-relevant summary

1. **Protocol.** The protocol is small, JSON-only and easy to reimplement. Unary calls are HTTP POST to `/api/<ns>/<method>`. Every stream goes over one WebSocket, `/api/remote.mux`, using 4 client frame types (`open`, `item`, `end`, `cancel`) and 3 server frame types (`item`, `error`, `end`), all keyed by a client-chosen `streamId`. I wrote an independent Node client (`recon/tools/raw-client.mjs`, ~60 lines) that authenticated, listed sessions, created a session, followed it, sent a prompt, received the streamed answer and **answered an approval prompt**. [observed]
2. **Two-way events.** Server-to-client notifications and the **approval / ask-user prompts** arrive on a special `$events` stream. The answer goes back as a unary POST to `/api/$events/result`. [observed]
3. **Typed client.** The official typed client (`ctx.remote.<ns>.<method>()`) can be used two ways:
   - (a) In the browser, as a **client plugin inside the official shell**. Verified: my probe plugin called `ctx.remote.session.list`, added a whole new page, and **replaced the entire app layout**.
   - (b) Standalone in Node, from the published `client.node.js` entries. Verified for unary calls only.
   There is no standalone *browser* bundle: the `client.browser.js` files need the shell's `window.__ModuleLoader__`.
4. **Replacing the UI is supported by the slot system.** A client plugin can:
   - register into the built-in `root` slot at `priority: -1`, which shadows the official `AppFrame` completely ("lowest renders");
   - add keyed `main` pages and switch to them with `ctx.layout.selectPanel(key)`;
   - shadow or extend ~90 named slots (chat nodes, composer, header, sidebar, …).
   [observed + read source]
5. **Multi-agent data is rich and live.** One host-wide `session/control` stream pushes per-session projections for the lead **and every subagent or teammate session**: `tokenUsage`, `sessionStats`, `subagentTiming`, `subagentCatalog`, `agentTeam` (members, phases, task board), `title`, `turnOutline`, `contextPressure`. `$events` adds `api-session/added` (with `parentSessionId` and `origin: "subagent"`), `api-session/status` (running true/false) and `api-session/removed`. Teammate messages, workflow runs and team tasks are durable session events. [observed + read source]
6. **Usage, not cost.** Token usage is available per session and per assistant message. There is **no money cost** anywhere (no prices in the model catalog). A cost page must apply its own price table. Lead `tokenUsage` does **not** include children, so sum the child sessions. [observed]
7. **Workspace picker. Important bug in our current findings patch.**
   - The picker uses **no browser API**: no `showDirectoryPicker`, no `webkitdirectory`. It works through RPC: `directoryPicker/list|createDirectory|pick` and `workspace/create`.
   - The patch in `docs/dsh-findings.md` disables `directory-picker` and inserts only the *host* row `@deepseek-ai/dsh-host-directory-picker-browse`. That leaves the **client half** (`@deepseek-ai/dsh-client-ui-directory-picker-browse`) unloaded, so **"Choose workspace" does nothing and the sidebar "Add workspace" button is missing**. [observed]
   - Adding the client row fixes it. [observed]
   - Separately, `workspace/initializeDefault` fails on Linux without `xdg-user-dir` (`spawn xdg-user-dir ENOENT`). Fix: configure `workspace-controller.documentsDirectory`. [observed + read source]

---

## 1. Client/server protocol

### 1.1 Authentication (recap, re-verified) [observed]
- `GET /?token=<launch token>` returns 303 and sets `dsh-auth-<hash>` (HttpOnly, SameSite=Strict, Path=/).
- Every `/api/*` call and the WebSocket need that cookie.
- WebSocket upgrade results (`recon/tools/raw-client.mjs` output):
  - no cookie → **401**
  - cookie + `Origin: http://evil.example` → **403**
  - cookie and no Origin header (non-browser client) → **accepted**

  A page served from the same origin by our own plugin route (`ctx.webServer.register`) automatically carries the cookie (same origin, `Path=/`). [inferred]

### 1.2 Unary RPC (HTTP) [observed]
```
POST /api/<namespace>/<method>
content-type: application/json
{"type":"client-request","rpcId":"<uuid>","method":"<namespace>/<method>","payload":{"args":{<named args>}}}
→ 200 {"type":"server-response","rpcId":"<same>","result":{"ok":true,"value":...}}
   or  {"type":"server-response","rpcId":"…","result":{"ok":false,"error":{"code":"gateway/internal","message":"spawn xdg-user-dir ENOENT","details":{}}}}
```
- `args` are **named**, using the TypeScript parameter names from the generated signatures (see 2.x). Example: `session/list` takes `{"_request":{}}`; `workspace/create` takes `{"request":{"path":"…"}}`; `commands/list` takes `{"agentId":"session-…"}`. [observed]
- A business failure is still HTTP 200 with `ok:false`. A non-200 status is a transport or auth failure. [observed]
- Results that carry binary data use `multipart/form-data`: a JSON `metadata` part plus attachments. Only a few endpoints do this (`workspaceFiles/readBytes`, `officeToPdf/render`). [read source, `NM/dsh-client-connection/README.md`]

### 1.3 Stream multiplexer `/api/remote.mux` (WebSocket, text frames, JSON) [read source + observed]

Source of truth: `NM/dsh-api-gateway/lib/types/stream-protocol.d.ts` and `.js` (exported as `@deepseek-ai/dsh-api-gateway/stream-protocol`). The parser enforces **exact key sets**, so do not add extra keys.

Client → server:
```ts
{type:'open',   streamId, endpoint, payload}   // payload = {args:{…named args…}}
{type:'item',   streamId, value?}               // uplink item (e.g. terminal input); omit value = undefined
{type:'end',    streamId}                       // half-close uplink
{type:'cancel', streamId}                       // stop the stream
```
Server → client:
```ts
{type:'item',  streamId, value?}
{type:'error', streamId, error:{code, message, details:{}}}
{type:'end',   streamId}
```
- `streamId` is any non-empty string chosen by the client. The browser uses UUIDs; my raw client used `"ev1"`, `"f1"`. [observed]
- A duplicate `open` with a live id closes the whole socket. `item`/`end`/`cancel` for an already-finished id are dropped. [read source, gateway README]
- After `cancel`, the server sends **no** `end` frame; the stream just stops. [observed, `raw-client.frames.json`]
- Heartbeat: the server sends WS Ping every 2 s (`websocketHeartbeatIntervalMs`), and browsers answer Pong automatically. A peer silent for 2 intervals is terminated. Uplink buffer per stream is 256 KiB (`streamInboxBytes`). [read source]
- On reconnect the client must reopen every stream. Stream openings deliver a fresh baseline or snapshot, so no replay is needed. One-way `$events` notifications are **not** replayed. **Pending approval or question waterfalls are re-delivered with the same `eventId`**: I closed the browser with an approval pending, and a new page received the same `eventId` and answered it. [observed `tool.jsonl` → `appr.jsonl`]

Real sample. The browser's first frames after load (`logs/load.jsonl`):
```json
> {"type":"open","streamId":"bfd6…","endpoint":"remoteAccess/follow","payload":{"args":{}}}
> {"type":"open","streamId":"9f61…","endpoint":"account/watch","payload":{"args":{}}}
> {"type":"open","streamId":"bcf9…","endpoint":"workspace/follow","payload":{"args":{}}}
> {"type":"open","streamId":"5a34…","endpoint":"$events","payload":{"args":{}}}
< {"type":"item","streamId":"5a34…","value":{"type":"ready","clientId":"e9da7f84-…","host":{"home":"/…/fakehome"}}}
< {"type":"item","streamId":"bcf9…","value":{"type":"baseline","value":{"items":[],"archivedSessionIds":[],"pinnedSessionIds":[]}}}
> {"type":"open","streamId":"f434…","endpoint":"session/control","payload":{"args":{}}}
< {"type":"item","streamId":"f434…","value":{"type":"baseline","value":{"projections":{}}}}
```

### 1.4 The `$events` stream (forwarded host events + interactive prompts) [read source + observed]
- Open with `{"type":"open","streamId":X,"endpoint":"$events","payload":{"args":{}}}`. The first item is `{type:'ready', clientId, host:{home}}`. **Keep `clientId`**: it is needed to answer prompts.
- Later items:
  - `{type:'emit', event, args:[…]}` for one-way notifications.
  - `{type:'waterfall', event, eventId, agentId, request:{…}}` for prompts that need an answer.
  - `{type:'cancel', eventId}` when the server withdraws a prompt.
- Answer a waterfall with unary `POST /api/$events/result`:
  - args `{"clientId":"…","eventId":"…","outcome":{"kind":"result","value":<answer>}}`
  - `{"kind":"next"}` passes the prompt to the next answerer.
  - `{"kind":"rejected","error":{name,message}}` rejects it.
- Allowlist of forwarded events (`NM/dsh-api-remotes/lib/types/remote-events.js`, the only events a client can receive):
  - Session lifecycle: `api-session/added|removed|status|activity|error`.
  - Interactive prompts (**waterfall**): `approval/request`, `user-questions/request`.
  - Other: `agent-preset/selected`, `commands/change`, `credentials/*`, `goal/activation-changed`, `cordis/*`, `llm/adapters-updated`, `permission-presets/catalog-changed`, `plugin-manager/*`, `plugin-market/changed`, `settings/document-updated`, `schedule/changed`, `deepseek-account/*`.
- Delivery: only **owner** peers receive waterfalls; `peer.interactions` must be `answer`. [read source] When the official UI and our UI are both connected, both receive the prompt. My guess is that the first answer wins and the server cancels the other (`cancel` frame). [inferred, not tested]

### 1.5 Reusable client libraries in node_modules [read source + observed]

| Package / entry | What it is | Usable standalone? |
|---|---|---|
| `@deepseek-ai/dsh-api-gateway/stream-protocol` (`lib/types/stream-protocol.js`) | Pure ESM: frame parsers `parseRemoteStreamServerMessage`, constants `REMOTE_STREAM_MUX_PATH`, `$events` result parser | **Yes**, browser-safe (one import: `dsh-typert-protocol`, which pulls `zod` + `dsh-brand`). Its README says it exists for "native Desktop callers". |
| `@deepseek-ai/dsh-api-gateway/client` → `lib/client.node.js` (ESM) / `lib/client.browser.js` | `ctx.remote` service: typed namespaces, `$on`, `$stream`, reconnect, `RemoteJournalStream` / `RemoteSnapshotStream` | Node entry: yes, inside a Cordis `Context`. Browser entry: **only inside the dsh shell** (`window.__ModuleLoader__.load({id,factory})`). |
| `@deepseek-ai/dsh-client-connection/client` → `client.node.js` exports `installConnection(ctx,{interactions,transport:{fetch,streamBaseUrl,createWebSocket}})` | RPC and WebSocket transport, generations, retry | Same as the row above. |
| `@deepseek-ai/dsh-typert-registry/client` (`client.node.js`) | `typert` service, required by the gateway client | Same as the row above. |
| `@deepseek-ai/<pkg>/remote` = `lib/typert.remote-client.js` (session-controller, workspace-controller, subagent, user-questions, …) | Generated descriptors plus zod codecs for each namespace; mount with `ctx.remote.$mount(TYPERT_REMOTE)` | ESM with plain `zod` import; bundleable. |
| `lib/typert.remote-client.d.ts` in each API package | Exact typed signatures (`TypertRemoteMap`) | Use as the API reference. The full list is in `recon/remote-map.txt` (144 endpoints). |
| `lib/typert.host.js` in each API package | zod schemas for every argument and result (365 KB for the session controller) | Reference for exact shapes. |

**Verified standalone typed Node client** (`recon/tools/typed/typed-client.mjs`):
- Composition: `new Context()`, then `installConnection` with a cookie-injecting `fetch` and a `ws` WebSocket factory, then `ctx.plugin(typertRegistryClient)`, then `ctx.plugin(gatewayClient)`, then `ctx.get('remote').$mount(SESSION_REMOTE)`.
- Result: `remote.session.list({})` returned `{ok:true, items: 9}`. [observed]
- An attempt to iterate `remote.session.control()` in the same script printed nothing. Typed stream use outside the shell is **unverified**.

The client bundles (`lib/client.js`) of `@deepseek-ai/dsh-api-remotes`, `-session-controller` and the UI packages exist **only** in shell module-loader format. They depend on modules the shell provides (`react`, `@deepseek-ai/cordis`, `@deepseek-ai/dsh-client-store`, `@deepseek-ai/dsh-client-ui-primitives`, …), which are not separate packages in node_modules (`dsh-client-store`, `-ui-slots`, `-ui-primitives` and `dsh-client-web` are baked into `dsh-web-frontend/dist/assets/index-*.js`).

**Recommendation** [inferred]:
- For a separate SPA served by our own plugin route, implement the ~7 frame shapes plus `fetch` POST yourself, using `.d.ts` / `typert.host.js` as the schema reference. Optionally import `stream-protocol` for parsing.
- For a UI that runs inside the official shell, write a client plugin and use `ctx.remote` (section 5).

---

## 2. Chat essentials (method names, args, shapes)

All shapes below were observed in `recon/logs/chat1.jsonl`, `tool.jsonl`, `int.jsonl`, `sub.jsonl` and `team.jsonl`. Types are in `NM/dsh-api-session-controller/lib/types/types.d.ts`.

### 2.1 Endpoints

| Need | Endpoint | Kind | Args (named) → result |
|---|---|---|---|
| List workspaces | `workspace/follow` | **stream** | `{}` → first `{type:'baseline',value:{items:[WorkspaceView],archivedSessionIds,pinnedSessionIds}}`, then increments `{type:'upsert',workspace}`, `{type:'order',workspaceIds}` |
| Create / open workspace | `workspace/create` | unary | `{request:{path:"/abs/dir"}}` → `{workspace:{workspaceId,path,title,sessionIds,createdAt,updatedAt},created}` |
| Other workspace operations | `workspace/rename|delete|insertBefore|pinSession|unpinSession|archiveSession|unarchiveSession|insertSessionBefore|initializeDefault` | unary | see `remote-map.txt` |
| List sessions | `session/list` | unary | `{_request:{}}` → `{items:[SessionSummary]}` (also includes child sessions) |
| Search sessions | `session/search` | unary | `SessionSearchRequest` |
| Create session | `session/create` | unary | `{request:{workspaceId}}`, or `{cwd}`, plus optional `sessionId`, `agentPreset` → `{sessionId, agentPreset}` |
| Open a session (history + live) | `session/follow` | **stream** | `{request:{address:{kind:'session',sessionId}, assistantStream:true, maxMessages:500, turnWindow:{minMessages:50,minTurns:2}}}` |
| Older history | `session/page` | unary | `{request:{address, throughSeq, beforeSeq, maxMessages, turnWindow}}` |
| Send prompt | `session/prompt` | unary | `{request:{requestId:<uuid>, sessionId, mode:'queue'|'steer', content:[{type:'text',text}|{type:'image',mediaType,data}|{type:'file',receiptId}], clientTimeZone}}` → `{accepted:true}` |
| Edit or remove queued prompts | `session/updateQueue` | unary | |
| Interrupt | `session/cancel` | unary | `{request:{sessionId}}` → `{accepted:true}`. Observed effect: `turn/end` with `reason:{kind:'aborted',reason:{kind:'user'}}` |
| Rename / title | `session/rename` | unary | `{request:{sessionId,title}}`. The title also arrives via the `title` projection and `session/title` events |
| Model | `session/modelCatalog`, `session/selectModel` | unary | |
| Preset | `agentPresets/list`, `agentPresets/select(agentId,agentPreset)` | unary | |
| Slash commands | `commands/list(agentId)`, `commands/execute(agentId,line,submittedAttachments)` | unary | |
| Answer approval | `$events/result` | unary | `{clientId,eventId,outcome:{kind:'result',value:'allowed-once'|'rejected'}}` |
| Answer ask_user_question | `$events/result` | unary | `{…,outcome:{kind:'result',value:{answers:[{id:'q1',selected:['Pink']}]}}}`. The alternative `userQuestions/answer(agentId,callId,answer)` also exists |
| Live per-session state (all sessions) | `session/control` | **stream** | `{}` → `{type:'baseline',value:{projections:{[sid]:{asOfSeq,values}}}}`, then `{type:'projection',sessionId,key,value,seq}` |
| Background jobs | `job/list` (stream), `job/follow` (stream), `job/kill` | | |
| File upload for prompts | `fileUploads/upload` (unary, raw body) | | |

`WorkspaceView` sample: `{"workspaceId":"1558e363-…","path":"/…/recon/ws","title":"ws","sessionIds":[],"createdAt":"2026-11-03T05:45:58.341Z","updatedAt":"…"}`.

### 2.2 `session/follow` frames
1. Opening frame: `{type:'snapshot', header:{version:4,id,createdAt,cwd,isSeeded,agentPreset}, cursor, records:[{type:'event',event}], hasMore, projections:{asOfSeq,values}, assistantStream?:{revision, activeAttempt?}}`.
2. After that:
   - durable events: `{type:'event', event:{type, seq, time, data, surfaceOp?}}`;
   - live token frames: `{type:'assistant-stream', frame:{…}}`.

Durable event sequence for one simple turn [observed]:
```
agent/inbox/spliced → turn/start{turn} → agent/inbox/spliced(removed) → step/start{turn,step}
→ system/message → user/message (the prompt; data.source.rpcId == requestId) → user/message (runtime context) → user/message (time)
→ request/header (full tool schema list; large) → request/context{provider,model,contextWindow}
→ session/title (fallback) → session/title-llm-request → session/title (LLM)
→ assistant/message{turn,step,message:{role,content:[{type:'reasoning',text},{type:'text',text}|{type:'tool-call',id,name,arguments}]},usage:{inputTokens,outputTokens,cacheReadTokens,totalTokens},stream:[compressed timing]}
→ tool/call{callId,name,arguments} → (job/registered, job/settled for bash) → tool/result{message:{role:'tool',toolCallId,content:[{type:'text',text}],isError},meta}
→ step/end → step/start … → turn/end{turn,reason:{kind:'completed'|'aborted'}}
```
Approval adds `approval/asked{id,toolName,callId,reason}` and `approval/decided{id,outcome}`. A UI should hide `system/message`, `request/header` and the runtime-context or time `user/message`s. Filter by `data.source.kind`: the real prompt has `kind:'user'`; others are `runtime-context`, `time-context`, `subagent-settled`, `team-message`, …

### 2.3 Live assistant stream (text deltas, thinking, tool calls) [observed]
```json
{"type":"assistant-stream","frame":{"type":"start","attemptId":"session-…:1","revision":1,"turn":1,"step":1,"startedAfterSeq":12}}
{"type":"assistant-stream","frame":{"type":"chunk","attemptId":"…","revision":2,"index":0,"time":…,"chunk":{"type":"block-start","index":0,"blockType":"reasoning"}}}
… "chunk":{"type":"reasoning-delta","index":0,"text":"Let "}
… "chunk":{"type":"block-end","index":0,"block":{"type":"reasoning","text":"Let me think …"}}
… "chunk":{"type":"block-start","index":1,"blockType":"text"}
… "chunk":{"type":"text-delta","index":1,"text":"Hello "}
… "chunk":{"type":"block-start","index":1,"blockType":"tool-call"}
… "chunk":{"type":"tool-call-delta","index":1,"id":"toolu_3_1","name":"bash","argumentsDelta":""}
… "chunk":{"type":"tool-call-delta","index":1,"id":"toolu_3_1","argumentsDelta":"{\"des"}
… "chunk":{"type":"block-end","index":1,"block":{"type":"tool-call","id":"toolu_3_1","name":"bash","arguments":"{…}"}}
… "chunk":{"type":"usage","usage":{"inputTokens":1001,"outputTokens":43,"cacheReadTokens":500,"totalTokens":1544}}
… "chunk":{"type":"finish","reason":{"kind":"stop"|"tool-calls"},"replayState":{…}}
{"type":"assistant-stream","frame":{"type":"end","attemptId":"…","revision":29,"index":27,"outcome":{"kind":"committed","eventType":"assistant/message","seq":16}}}
```
- `index` is dense per attempt and `revision` is monotonic.
- On `end`, replace the live rows with the durable `assistant/message` whose seq is given in the outcome.
- `outcome.kind` can also be `abandoned`.
- On interrupt, the partial message was still `committed`. [observed `int.jsonl`]

### 2.4 Approval prompt [observed]
```json
< {"type":"item","streamId":"<$events>","value":{"type":"waterfall","event":"approval/request","eventId":"dccab651-…","agentId":"session-4176…",
   "request":{"toolName":"write","callId":"toolu_6_1","reason":"escalate sandbox to danger-full-access: Testing approval prompt",
              "displayReason":{"en":"Allow this operation with danger-full-access permissions: …","zh":"允许本次操作使用 …"}}}}
POST /api/$events/result {"type":"client-request","rpcId":"…","method":"$events/result","payload":{"args":{"clientId":"c9b3…","eventId":"dccab651-…","outcome":{"kind":"result","value":"allowed-once"}}}}
→ {"result":{"ok":true}} ; follow stream: approval/decided{outcome:"allowed-once"}
```
Outcome vocabulary: `'allowed-once' | 'rejected' | 'cancelled' | 'unavailable'` (`NM/dsh-user-approval/lib/types/types.d.ts`). Answer with `allowed-once` or `rejected`.

ask_user_question arrives as a waterfall `user-questions/request` with `request:{questions:[{id,question,header,options:[{label}]}]}`. The answer value is `{answers:[{id,selected:[label]}]}`. [observed]

### 2.5 Session title [observed]
- Title change: `session/title` event `{title, messageSeqs, source:{kind:'fallback'|'provider'}}`.
- Projection update: `{type:'projection',key:'title',value:'Fake Session Title'}`.
- List rows carry it in `projections.values.title`.

---

## 3. Workspace picker

**How it works** [read source + observed]:
- The UI calls `ctx.uiWorkspace.pickDirectory()` (`NM/dsh-client-ui-workspace/lib/client.js`). That is filled by one of two client "directory-flow" occupants (slots `sidebar.workspaces.directoryFlow` and `conversation.hero.workspace.directoryFlow`):
  - `@deepseek-ai/dsh-client-ui-directory-picker-native` calls RPC `directoryPicker/pick`, and the **host** spawns `zenity`/`kdialog` (Linux), AppKit (mac) or `IFileOpenDialog` (Windows).
  - `@deepseek-ai/dsh-client-ui-directory-picker-browse` renders the in-app "Select Workspace Directory" dialog. It uses `directoryPicker/list(path?)` → `{path,home,crumbs:[{name,path,hidden}],entries:[…],truncated}` and `directoryPicker/createDirectory(path,name)`.
- Clicking Open calls `workspace/create {request:{path}}`, then `session/create {request:{workspaceId}}`. [observed `chat1.jsonl`]
- **No browser file API is involved.** `showDirectoryPicker` and `webkitdirectory` do not appear in any `lib/*.js` or in the frontend bundle. [read source, grep]

**How `auto` chooses** (`NM/dsh-host-directory-picker-auto/lib/index.js`) [read source]:
```js
if (allowsRemoteAuthorities || !isLoopbackHost(bindHost)) return "browse";
if (ssh) return "browse";
if (platform darwin/win32) return "native";
if (platform !== "linux" || !linuxChooser /* zenity|kdialog on PATH */) return "browse";
return DISPLAY || WAYLAND_DISPLAY ? "native" : "browse";
```
`auto` mounts **both** the host backend row and its client surface row (`BACKEND_PACKAGES` + `SURFACE_PACKAGES`).

**Why it fails in our app** [observed]:
- `docs/dsh-findings.md` §Server patches `directory-picker: disabled` and inserts only `@deepseek-ai/dsh-host-directory-picker-browse`.
- With exactly that patch, the client roster contained **neither** directory-picker client bundle (checked in the `/plugins/??…` combo URLs).
- Clicking "Choose workspace" sent no request and opened nothing, and the sidebar had no "Add workspace" button (`logs/ws2.jsonl`, `ws2-1.png`).
- After also inserting the client row, the browse dialog opens, `directoryPicker/list` is called, and "Add workspace" appears (`logs/ws3.jsonl`, `ws3-2.png`). Required patch:
  ```yaml
  - id: directory-picker
    disabled: true
  - insert:
      - id: directory-picker-browse
        name: '@deepseek-ai/dsh-host-directory-picker-browse'
      - id: directory-picker-browse-ui
        name: '@deepseek-ai/dsh-client-ui-directory-picker-browse'
  ```
  Alternatively, keep `auto`: in proot with no DISPLAY and no zenity it resolves to browse with both halves. [inferred from source]
- The native path could not work on Android in any case: it opens an OS dialog on the *host*, and proot has no display. Even if it were chosen, nothing would appear in the WebView. [inferred]
- A WebView-specific failure would come from a browser picker API, but none is used, so that is ruled out. [read source]

**Second problem** [observed + read source]:
- On load the UI calls `workspace/initializeDefault`, which on Linux runs `xdg-user-dir DOCUMENTS`. Here it returned `{"ok":false,"error":{"code":"gateway/internal","message":"spawn xdg-user-dir ENOENT"}}`.
- README fix: set `workspace-controller` config `documentsDirectory: /abs/path`. The default workspace then becomes `<documentsDirectory>/deepseek-harness/default-workspace`.

---

## 4. Multi-agent data (subagents, workflow, Agent Teams)

### 4.1 What a client can read live

| Data | Where | Shape (observed) |
|---|---|---|
| Child session exists, parent link | `$events` `api-session/added` | `{sessionId:"8381e2d1-…", parentSessionId:"session-f105…", origin:"subagent", agentAvailable, running, blank, cwd, projections:{values:{subagent:{mode:'continuable',label:'Child research task',seq:0}, tokenUsage, …}}}`. Child ids are bare UUIDs; top-level sessions use a `session-` prefix. |
| Running / idle | `$events` `api-session/status` | `[sessionId, true|false]`, for lead and children |
| Child finished and unloaded | `api-session/added` (agentAvailable:false) then `api-session/removed` | |
| List of a parent's children | projection `subagentCatalog` on the parent | `[{mode:'continuable',label,id,createdAt}]`. Also the durable event `subagent/catalog{childId,childCreatedAt,mode,label}` in the parent log |
| Child activity timing | projection `subagentTiming` on the child | `{settledMs, active:{since,through}, lastTurnCompleted}`; `through` ticks while working |
| Per-agent tokens | projection `tokenUsage` per session id (children too) | `{uncachedInputTokens,outputTokens,cacheReadTokens,cacheWriteTokens}` |
| Per-agent stats | projection `sessionStats` | `{turns,steps,llmMs,toolMs,ttftMs,ttftSteps,decodeMs,decodeTokens}` |
| Context fill | `contextPressure`, `contextBreakdown` | `{pressureTokens,projectedTokens,contextWindow}`, `{systemTokens,toolsTokens,messageTokens}` |
| Current activity / last prompt and response | projection `turnOutline` | `[{turn,seq,prompt,response}]` (truncated text) |
| Pending inbox (messages waiting for an agent) | projection `inbox` | `{"next-turn":[msg],"next-step":[msg]}`. The child's initial task appears here |
| Model per agent | projection `modelSelection` | `{lastUsed:{provider,model,reasoningEffort},next}` |
| Child's full transcript, live | `session/follow` with `address:{kind:'subagent',parentSessionId,childSessionId,mode:'continuable'|'one-shot'|'unknown'}` | same frames as section 2 [read source; the official UI did not open it during my run] |
| Child result back to parent | parent log `user/message` with `source.kind:'subagent-settled'`, `senderSessionId` | "Background subagent … finished … Its closing message: …" |
| Agent-to-agent messages | `send_message`: `source.kind:'agent-message'` (subagent); teams: `source.kind:'team-message'{teamId,messageId,senderId,senderName}` and team-log events `team/message/queued{message:{id,senderId,senderName,targetId,content}}`, `team/message/delivered` | [read source `NM/dsh-subagent/lib/types/continuation-messages.d.ts`, `NM/dsh-experimental-agent-team/lib/types/types.d.ts`] |
| Team roster and phases | projection `agentTeam` on the lead | `{members:[{id,name,role:'lead'|'teammate',phase:'provisioning'|'active'|'failed',error?}], tasks:[TeamTaskView], failure?}` |
| Team task board | `agentTeam.tasks` and events `team/task` | `{id:'task-1',revision,subject,description,status:'pending'|'in_progress'|'completed'|'deleted',blockedBy,writeScopes,ownerName?,ready,writeScopeWarnings}` |
| Team member events | `team/member{member:{id,name,description,provider,context,phase}}` | provisioning → active observed |
| Workflow runs | parent log events `tool-workflow/run-start{runId,name}`, `agent-start{runId,seq,label,phase?,childId}`, `agent-end{runId,seq,outcome}`, `run-end{runId,stopReason}` | [read source `NM/dsh-tool-workflow/lib/types`; not run] |
| Running/inactive status as shown to the model | tool result of `list_agents` | `[{target:'lead',role,status:'running'},{target:'alpha-worker',status:'inactive',model,…}]`. The *client* has no RPC for this; derive status from `api-session/status` + `agentTeam` |

The `session/control` stream is **host-wide**: one stream carries projection updates for every live session, including children. [observed: frames for child `8381e2d1-…` and teammate `52c53b85-…` arrived on the same `session/control` stream]

### 4.2 Control actions available to a client [read source]
- `subagents/prompt {requestId,parentSessionId,childSessionId,mode:'continuable',delivery:'queue'|'steer',content}` sends a message to a child.
- `subagents/interruptByParent(childSessionId,parentSessionId,'continuable')` interrupts a child.
- `session/cancel` stops the lead (it presumably cascades through archive or interrupt, not tested).
- There are no client RPCs for team tasks. They are written by agents through tools; the client only reads them via the projection.

### 4.3 Gaps
- No explicit "failed" status for plain subagents. Use `turn/end.reason` in the child log, `subagentTiming.lastTurnCompleted`, and `api-session/error`. Teams have `phase:'failed'` + `error`. [read source]
- No cost field (section 6).

---

## 5. Extensibility of the existing frontend

### 5.1 Architecture [read source + observed]
- `index.html` loads the shell (`dsh-web-frontend/dist/assets/index-*.js`, React + Cordis + slots + primitives in a frozen module table).
- The shell then fetches combo bundles `/plugins/??<pkg>/client.js,…` for every enabled Loader row whose package declares `dsh.client` in `package.json`. Each bundle is `window.__ModuleLoader__.load({id, factory:(require)=>module.exports})`, exporting `{inject, apply(ctx)}`.
- The whole UI is ~60 such client plugins (list in `logs/load.jsonl`).
- **A local path row works.** `@deepseek-ai/dsh-client-modules` resolves the row path to the nearest `package.json` and serves its `exports["./client"]` (`locatePkgJson` / `nearestPackage`).

  I loaded `recon/tools/probe-client/` through `--patch`:
  ```yaml
  - insert: [{ id: dshm-probe-client, name: '/abs/…/probe-client/lib/index.js' }]
  ```
  `package.json` of the probe:
  ```json
  "exports": {".":"./lib/index.js","./client":"./lib/client.js"},
  "dsh": {"client": {"inject": ["@deepseek-ai/dsh-client-ui-layout","@deepseek-ai/dsh-api-remotes","@deepseek-ai/dsh-client-ui-renderer","@deepseek-ai/dsh-api-session-controller","@deepseek-ai/dsh-client-ui-workspace"], "platform":"web"}}
  ```
  It loaded as `dshm-probe-client/client.js` in the combo URL. [observed]
- Gotcha: the plugin's `inject` must name namespace services such as `"remote.session"`. Without that, `ctx.remote.session` was undefined and the plugin failed to activate ("web boot: 1 entry did not activate"). [observed]

### 5.2 Client API surface relevant to a redesign [read source, partially observed]
- `ctx.slots.register({name, key?, priority?, children?, store?, locale?}, Component)` returns a disposer.
  - Kinds: `single`, `keyed`, `list`.
  - Registering at the same priority in a `single` slot throws. A **different priority shadows it, and the lowest renders** (error message in the shell: `register at a different priority to shadow it (lowest renders)`).
  - Also `ctx.slots.inject(name, fn)`, `registerFactory`, `entries`, `subscribe`, `provideRoot`.
- Built-in `root` slot: the shell renders only `renderSlot('root')`. `@deepseek-ai/dsh-client-ui-layout` registers `AppFrame` there with children `sidebar` (single), `main` (keyed), `rightbar`, `shell.bottom`, `shell.overlay` (list), `shell.leading`.
- **Whole-UI replacement, verified.** `ctx.slots.register({name:'root', priority:-1}, Root)` → after reload `#dshm-root` rendered and **zero** official "New session" buttons remained (`logs/probe-root.png`, `probe.jsonl`: `rootPresent:1, appFrameButtons:0`). The other client plugins stay loaded, so their services (`sessions`, `uiWorkspace`, `remote`) remain usable from the custom root. [observed]
- **New page, verified.** `ctx.slots.register({name:'main', key:'dshm-probe'}, Panel)` then `ctx.layout.selectPanel('dshm-probe')` rendered a full-size pink page next to the official sidebar. Its button called `ctx.remote.session.list({})` and showed 8 (`logs/probe-panel.png`). This is how Plugins and Automation tasks are built (`main` keys `PANEL_ID`, `schedules`). [observed]
- Navigation and services:
  - `ctx.layout`: `selectPanel`, `toggleSidebar`, `openRightbar`, `closeRightbar`, `beginNavigation`.
  - `ctx.uiWorkspace`: `openSession`, `startSession`, `openWorkspace`, `forkSession`, `archiveSession`, `pinSession`, `pickDirectory`, `listDirectory`, `createDirectory`, `initializeDefaultWorkspace`, …
  - `ctx.sessions`: `retain`, `using`, `binding`, `scope`, `create`, `fork`, `search`, … (a client session store with history journals).
  - `ctx.remote.<ns>.<method>`, `ctx.remote.$on(event, listener)` (waterfall listeners may return a value or call `next()`; my probe registered one for `approval/request`), and `ctx.theme`, `ctx.locale`, `ctx.shortcuts`.
- Granular slots to shadow instead of the whole root. About 90 names; a selection:
  - Conversation: `main.conversation`, `conversation.view`, `conversation.session`, `conversation.session.header*`, `conversation.chat.flow`, `conversation.chat.node`, `conversation.chat.reasoning.body`, `conversation.user.text.node`, `conversation.chat.assistant-actions`, `conversation.composer`, `conversation.composer.bar`, `conversation.input.*`, `conversation.approval.detail`.
  - Tools: `tool.call.toolview`.
  - Sidebar and shell: `sidebar`, `sidebar.workspaces`, `sidebar.panellist`, `rightbar.session`, `shell.overlay`.

  Full list from a grep of `slots.register` names across `NM/*/lib/client.js`.
- React for a client plugin comes from `require("react")` inside the module-loader factory, matching the shell's single React identity. A plugin bundle must be **hand-built in this format**; there is no official tsdown preset in node_modules. JSX can be compiled with esbuild using `format: 'iife'` around a factory, or written with `React.createElement`. [observed in probe; build tooling inferred]
- Server-side alternatives (already in findings): `webserver/index-inject` rows (`style`, `script`, …) and `ctx.webServer.tapIndex`. These are good for CSS theming but have no access to `ctx`. [read source]

---

## 6. Usage / cost data

- **Per session (each agent):** projection `tokenUsage` `{uncachedInputTokens, outputTokens, cacheReadTokens, cacheWriteTokens}` arrives live on `session/control` and in list rows. The lead value is the lead's own usage only.

  Observed: after spawning a teammate, the lead had `uncachedInputTokens: 2029` (two of its own requests) while the teammate session `52c53b85-…` had its own `1015`.

  A "team total" therefore requires summing the lead and every child session id from `subagentCatalog` or `agentTeam.members`. [observed]
- **Per message:** `assistant/message.data.usage` `{inputTokens,outputTokens,cacheReadTokens,totalTokens}`, plus the live `usage` chunk. [observed]
- **Per session timing:** `sessionStats` (LLM ms, tool ms, TTFT, decode tokens). The official footer shows "4 turns 7 steps · 230 tok/s · 11K tok · Cache hit 33%" from these. [observed]
- **Money:**
  - `session/modelCatalog` has no prices; the only cost-related wording is in descriptions ("at higher cost").
  - `account/getBalance` returns the DeepSeek platform wallet balance, only for signed-in accounts.
  - Our app must keep its own price table, as already sketched in findings §Providers. [observed + read source]

---

## 7. Artefacts (all under `/tmp/claude-0/-home-user/1e630f02-b974-5959-b741-b3d92fa6411e/scratchpad/recon/`)
- `tools/fake-llm.mjs`, `tools/scenario.json`: fake Anthropic SSE model with keyword-scripted tool calls (bash, write-with-approval, ask_user_question, subagent, spawn_teammate, team_task_create, list_agents, slow stream).
- `tools/patch.yml`: privacy patch, browse picker (both halves) and the probe plugin row. `tools/start.sh` starts dsh and the fake model.
- `tools/drive.js` + `tools/s-*.js`: Playwright traffic recorder. `tools/summ.py`: log summariser.
- `tools/raw-client.mjs`: independent raw-protocol client (auth, RPC, mux, prompt, approval answer, negative WS tests).
- `tools/typed/typed-client.mjs`: standalone typed client using the published `client.node.js` entries.
- `tools/probe-client/`: minimal client plugin (new `main` page, `root` shadow, `ctx.remote`).
- `remote-map.txt`: all 144 Remote endpoints with typed signatures.
- `logs/*.jsonl`: full captures (`load`, `ws2` = broken picker, `ws3` = fixed picker, `chat1`, `tool`, `appr`, `sub`, `team`, `int`, `probe`). `logs/*.png` are screenshots; `logs/raw-client.frames.json` holds the raw client's frames.

The environment had no Agent Teams UI beyond the header popover (`team-5.png`: members with Inactive/Current chat, and the shared task card). The official UI shows subagents only as a "1 subagent" header dropdown and a "Subtask status updated" chat node (`sub-2.png`). A dedicated visualization page would be new work, and the data in section 4 is sufficient for it. [observed + inferred]
