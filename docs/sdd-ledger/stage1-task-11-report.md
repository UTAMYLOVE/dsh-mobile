# Stage 1 Task 11: agents tab (simple list + stop)

Branch `lane-t11` (base b70babf). Status: DONE.

## What changed
- `agents/RunningAgents.kt` (new): `AgentsRemote`, `AgentRow`, `AgentRows.from(SessionTreeState)`, `RunningAgents` (rows and runningCount derived from `tree.state`, no baseline or event loop of its own, Ruling 16; `originOf`; `stop`; plus `stopping` and `failed` StateFlows).
- `ui/agents/AgentsScreen.kt` (new): panel 代理人/AGENTS, meta 在跑 N / 上限 M, rows (aqua glow dot while running, milk-tea dot when finished, label, 來自：父對話標題 tap opens the parent, 叫它停 AQUA button, 停止中… while in flight), empty state.
- `remote/DshRemote.kt`: `interruptSubagent` is now `override` (Task 2 body unchanged).
- Minimal additive edits to shared files:
  - `ui/shell/ShellSession.kt`: `ShellRemote : ... AgentsRemote`; `val agents = RunningAgents(tree, remote, scope)`.
  - `ui/shell/Tags.kt`: `AGENT_ROW`, `AGENT_STOP`, `AGENTS_EMPTY`.
  - `res/values/strings.xml`: 10 `agents_*` strings.
  - `ui/MainActivity.kt`: `PromptHost(..., originOf = session.agents::originOf)` (Ruling 15); `ShellTab.AGENTS -> AgentsTab(session)`; private `AgentsTab` composable (limit from `AgentSettingsStore.load(phantom, hasClaude).maxConcurrent`, parent click calls `nav.openSession`); 3 imports.
- Tests: `agents/RunningAgentsTest` (10), `ui/agents/AgentsScreenTest` (7), `MainActivityTest.aSubagentApprovalNamesTheSubagent`; `ShellSessionHolderTest.FakeRemote` got the one-line `interruptSubagent` override.

## TDD
RED: the first Gradle run failed to compile the new tests (`Unresolved reference 'AgentsRemote' / 'RunningAgents' / 'AgentRows'`). GREEN: RunningAgentsTest 10/10, AgentsScreenTest 7/7, MainActivityTest 51/51 (focused run). Full suite: 826 tests, 0 failures, 0 errors, 0 skipped (BUILD SUCCESSFUL).

## Deviations and decisions
1. `AgentsScreen` has one extra trailing parameter `failed: Set<String> = emptySet()` (Controller ruling: show a readable error on a failed stop). The brief's parameters are unchanged and in order. `RunningAgents` also exposes `stopping`/`failed` for the screen.
2. The failed-stop text is a fixed string 「沒能叫停，請再試一次」 in the app's error colour (#B3261E, allowed by the plan header). The exception text is never shown or stored.
3. A tap while that child's stop is in flight, or on a row that is not running, is ignored.
4. `AgentsScreen` owns its vertical scroll and applies `LocalPagePadding` itself (the brief's signature has no modifier).
5. `originOf` with an untitled parent says 「來自 新對話」; the `AgentRows` label fallback is 「子代理人」 (so an unlabelled child reads 子代理人「子代理人」（來自 …）, as the brief's formula gives).
6. Rows are children by `parentId != null` (brief), not by `origin`.

## 412 dp layout
`AgentsScreenTest.longLabelsEllipsizeAndTheStopButtonNeverOverlapsThem` (qualifiers w412dp-h915dp-port): the text column is a `weight(1f)` sibling of the button; label and origin use maxLines = 2 with ellipsis; asserts button.left >= text.right, button.right <= 412 dp, and text height < 120 dp.

## Screenshot (throwaway test, not committed)
/tmp/claude-0/-home-user/1e630f02-b974-5959-b741-b3d92fa6411e/scratchpad/t11-shots/agents-list.png (412x915 in FogShell: running rows, a long label, a finished row with a long parent title, a stopping row, a failed-stop error).

## Concerns
- A stop that succeeds but whose status never turns false leaves 停止中… until the next status event (as ruled: "until the status turns false"); there is no timeout.
- Finished subagents stay listed for as long as dsh lists them (no cap).
