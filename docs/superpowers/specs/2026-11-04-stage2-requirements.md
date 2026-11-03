# Stage 2 (release 0.3.0) — owner requirements, collected 2026-11-03/04

The owner (宇哥) said: 「你直接做下一版」 ("just build the next version"). One release covers everything below. Do not split it into small versions.

## A. Bug fix (top priority)
- On a real phone, 「套用並重啟」 (settings apply-and-restart) got stuck. After that, sends failed with `session/writer-held`.
  - This is dsh's SessionAlreadyOwnedError: either an in-process writer, or an flock on session.lock held by a live process.
  - Force-stopping the app fixed it, so a live process held the lock. Most likely the old dsh was still alive when the new one started.
- An investigation report will be at docs/sdd-ledger/bug-writer-held.md. It may not exist yet; it is being written now.
- Fix it:
  - The restart must fully stop the old dsh process tree before starting a new one. It must also detect a leftover dsh on start.
  - The UI must say clearly when a restart is stuck or failed, rather than spinning forever.
  - Show the same send refusal only once, not in both the chat banner and the dock.

## B. Chat box controls (next to the existing model chip)
1. **思考強度 (thinking strength).**
   - dsh supports `reasoningEffort` in `modelSelection` (`off` | `low` | `high` | `max`), set through session/selectModel (see docs/dsh-ui-protocol.md §modelSelection and the dsh source in scratchpad dsh-scratch).
   - Show it per conversation, applied to the next turn, like the model chip.
   - Show only the levels the selected model supports. The pi-ai `reasoningEfforts` catalog lists them.
2. **重複審查幾次 (review rounds, N = 0…3).** The owner chose: 「另派審查員檢查 N 次」.
   - After the reply is written, a separate reviewer subagent checks it and lists problems. The original agent then revises from that feedback. This repeats N times.
   - Default is 0 (off).
   - Implement without modifying official dsh. For example, the app adds a clear instruction to the prompt telling the lead agent to spawn a reviewer subagent with the dsh subagent tool and revise, N rounds. A dshm plugin tool is also an option if that is cleaner.
   - Show progress, e.g. 「審查 1/2」.

## C. Stats (「常用的數據都加進去」)
- Under each assistant reply, a small line:
  - tokens per second
  - time to first token
  - input, output and cache tokens
  - cost (US$, from the app's PriceTable)
  - duration
- Next to the composer:
  - a context-fill bar from contextPressure / request/context contextWindow, which changes colour when near full
  - running totals for this conversation: tokens and cost
- Overview additions, where data exists:
  - per-model spend today and this month
  - cache hit rate
  - average tokens per second
  - number of tool calls
- Data sources:
  - assistant/message `usage` and stream timing (chunk `time`)
  - request/context {provider, model, contextWindow}
  - projections: tokenUsage, contextPressure, sessionStats
- Fix the PriceTable: Sonnet 5.5 cache read is US$0.20/M, not 0.10. Re-check every price against the current official list: Haiku 5.5 is 0.10/0.50 (≤100K prompt) and 0.50/2.50 above that; Opus 5.5 is 4/20; Sonnet 5.5 is 2/10; Fable 5.1 is 10/50.

## D. Bookshelf and reading mode (spec §5.5, the original stage 2)
- Character cards: reusable settings cards for characters, world and writing style. The owner writes them, or the AI helps tidy them. Stored in the workspace.
- New book:
  - Name the book, pick or create a character card, and pick the writing model (default: the novel model).
  - This opens a dedicated conversation, with the card as its opening setup.
  - One book is one conversation.
- Chapters:
  - The model's prose replies in that conversation become chapters automatically.
  - The owner can mark a reply as 「不是章節」, or add one by hand.
  - 「加入書架」 is available from any chat.
- Reading mode: full-screen ebook view with font size, night mode, read %, and remembered position. 「下一章」 asks the model in that book's conversation to continue.
- Bookshelf: covers (FOGCITY gradient plus title), chapter count and reading progress.
- Everything is stored in the workspace and included in export.
- Reading mode keeps background effects at minimum (spec §5.9).

## E. Agent monitor wall, sci-fi FUI style (the owner wants it IN THIS VERSION)
- **Correction from the owner: NOT a pixel office.** He wants the *idea* of the pixel-agent visualizers (see at a glance what every agent is doing), drawn in the sci-fi FUI style of his reference: docs/design-reference/agents-fui-reference.jpg, from Oblivion-like film UI. Quote: 「要科幻風…要帥 顏色一樣那幾個顏色」.
- The reference has:
  - a polygon radar or scope with a glowing marker;
  - ring gauges with orbiting dots;
  - small labelled numeric readouts, for example PARTICULATE 2048 or FLOW RATE 226;
  - stacked bar meters;
  - thin-line geometry and dense tabular numbers.
- **Palette stays FOGCITY only:** #FEDFE1 #D7C4BB #DC9FB4 #FFFFFF #A5DEE4 #9E7A7A, ink #5E4445 / #7D5F60. Error red #B3261E is for error states only. Fonts are Orbitron for labels and numbers, Chakra Petch for text.
- This is spec §5.6 (代理人監看牆):
  - **Radar at the top.** The lead is at the centre. Subagents and teammates sit on rings by depth, with lines showing who dispatched whom. Colour shows state: aqua running, pink waiting, milk-tea done, dark rose-brown blinking for error.
  - **Totals** for the current task: cost and tokens.
  - **Wall of small "screens", one per agent.** Each shows its current action as a HUD glyph and label: 搜尋, 讀取, 撰寫, 派出, 詢問, 錯誤, 思考, 完成. It also shows the live last line of text being generated, plus elapsed time, cost and a token bar, styled as FUI readouts and ring gauges.
  - **Tap an agent** for full steps, 傳話 (subagents/prompt) and 停止 (interruptByParent).
  - **Agent Teams:** members, phase and task board, where dsh provides them.
- Data:
  - session/control projections: subagentTiming, tokenUsage, agentTeam, contextPressure;
  - $events api-session/added, status and removed;
  - the child's session/follow, for live text and tool calls.
  - See docs/dsh-ui-protocol.md §4.
- Keep a compact list toggle for accessibility. TalkBack users get the list.
- Performance:
  - Animate only when the tab is visible.
  - Pause in the background.
  - Respect the effect levels: SAVER means static.
  - Respect reduced motion.
  - Cap the number of live follows. Follow only visible screens, for example at most 6.

## F. Deferred items folded into this release
- Small screens, about 640 dp tall with the keyboard up: the rename/delete cards and the list are cramped, and a tap can land on the scrim. Fix the layout.
- Let files created by bash or python be shared, not only write/edit outputs. For example, detect file paths in the workspace from tool results, or add a 「工作區檔案」 browser with share.
- Finished subagents vanish from 代理人 because dsh removes them. Keep a short in-app history, for example the last 20 finished, so the pixel office can show 「完成」 for a while.
- Triage the remaining deferred minors from docs/sdd-ledger/stage1-progress.md and include the cheap ones.

## Constraints (unchanged red lines)
- Do not modify official dsh source. Permissions stay exactly the spec's 9.
- Keys live only in the Android Keystore. They are never given to dsh and never logged.
- Conversation and usage upload stays off.
- Use only the palette above. Fonts are Orbitron and Chakra Petch.
- Performance target: no dropped frames at 120 Hz on S25 Ultra (412×915 dp). CI PerfGate stays advisory on the emulator.
- The release goes through CI green on e2e. The owner tags it. The version is 0.3.0+5.

## G. Owner follow-up: 「記得狀態要詳細」 (status must be detailed)
Apply it everywhere a status is shown.
- **Monitor wall, per agent.** Show each of these:
  - current state, plus the reason when waiting (approval, question, subagent, rate limit);
  - current tool name with a short argument summary, e.g. 搜尋「台北天氣」 or 讀取 notes/a.md;
  - step / turn count;
  - elapsed time, both since start and in the current step;
  - tokens in, out and cache, and cost;
  - model and reasoning strength;
  - context fill;
  - the last generated line;
  - queued messages;
  - parent and depth;
  - the error text, if any;
  - the last N actions as a small timeline.
- **Chat.** Show what the agent is doing right now: thinking, calling a tool, waiting for approval, reviewing (審查 1/2), or queued. Don't show just a spinner.
- **dsh / restart status.**
  - Show the exact phase: stopping, killing leftovers, launching, health check, registering workspace.
  - Show seconds elapsed for that phase.
  - Show what is being waited on.
  - If a step fails, show which step failed and why, in plain Chinese.
- **Overview.**
  - dsh state: running, idle, or stopping, with uptime.
  - port health.
  - number of running agents.
  - the last error.

## H. 「戰情室」(war-room) look — owner's newest direction, 2026-11-04 02:40
- **References**
  - `docs/design-reference/dashboard-reference-agents-orb.jpg`. A dense multi-panel dashboard:
    - a live feed ticker
    - big numbers
    - line charts
    - a central particle network ("THE ORB") with streams of particles flowing into it
    - a decision stack
    - six agent icons with counts
    - a "last actions" log
    - **jellyfish-like creatures ("MEDUSA readers") that drift and move around the data.** The owner pointed these out specifically: 「很像章魚的東西 會跑來跑去」, and said they show the agents' work process.
  - `docs/design-reference/dashboard-reference-terminal.jpg`. A trading-terminal dashboard:
    - huge % numbers
    - comparative line charts
    - gauge
    - ranked bar lists
    - news list
- **The owner's words:** 「對啦就是戰情室超級帥」. 「顏色一樣用我們自己的」 means only the FOGCITY palette. No copied art.
- **Agents as living creatures.** On the monitor wall and the overview, each agent is an original animated jellyfish/medusa creature, drawn in code with our palette.
  - It drifts toward the data it is working on: search, file, subagent, model.
  - Its tentacles pulse while it generates.
  - Its colour shows its state: aqua running, pink waiting, milk-tea done, rose-brown blinking error.
  - Dispatching a subagent spawns a new creature from its parent.
  - Tapping a creature opens the detailed status (section G).
- **Where it goes**
  - **代理人 (monitor wall):** a war room with:
    - the creatures and a particle network of dispatch relations
    - per-agent icon tiles with numbers
    - a live "last actions" log
    - a decision/approval stack
    - token/cost meters
  - **總覽:** a war room with:
    - big spend numbers
    - usage curves over time
    - a per-model ranking
    - a live ticker of recent events (dsh state, replies finished, tools run)
    - gauges for context and cache hit
  - **對話:** a pull-out data panel beside the chat with:
    - a tokens/s curve for the current reply
    - context fill
    - cost
    - a tool-call log for this conversation
- **Background colour (controller ruling, 2026-11-04):** keep the existing FOGCITY base and glass, and build the dense war-room layout with our palette on it. The owner answered only "our colours", so a dark "night war-room" theme is NOT added in 0.3.0. If he asks for it later, add it as a theme switch.
- **Performance still rules.**
  - Particles and creatures animate only on the visible page, and are capped in count.
  - At SAVER, a static drawing.
  - With reduced motion, no drift.
  - The 120 Hz target on S25 Ultra stands.

### H.1 Third reference: `docs/design-reference/dashboard-reference-agent-terminal.jpg` ("Grok Bot Terminal", agent-pipeline terminal; owner: 「這也很是帥」)
Elements to adapt to our palette and our data:
- **Header**
  - A big mascot or status orb with 「running · clock」.
  - The current action pill, e.g. 「EXECUTOR · ENTERED …」.
  - A subtitle such as 「every trade gets a reason」.
  - Big top-right stat tiles, e.g. balance, wins, losses. For us: 今日花費、完成、錯誤、執行中.
- **Left column: one card per agent**
  - Icon, name and a role line.
  - A colored state badge: SCAN, PASS, IDLE, LIVE, WRITE, READING.
  - A one-line rule in accent colour.
  - The latest action line in mono, e.g. 「next $DOZY · spike 3.5×」.
  - The active agent's card is highlighted with a glowing border.
- **Centre: LIVE FLOW pipeline**
  - The stages are chips, e.g. 「SCAN → CHECK → SIZE → EXEC → LOG」. For us: 規劃 → 搜尋/讀取 → 派出 → 撰寫 → 審查 → 完成.
  - Item cards flow along a dotted path between glowing nodes.
  - Small counters per stage.
- **Detail panel**
  - The current item, with key/value rows in mono and a 「reason:」 line.
  - For us: the current tool call, its arguments and why, taken from the reasoning summary when available.
- **Log**
  - A `tail -f`-style log panel: 「time · ENTER/EXIT badge · item · reason」 rows that scroll live.
  - For us: tool calls, approvals, subagent dispatch and finish, each with a short reason.
- **Result strip**
  - Start vs now. For us: session start → now, tokens and cost, with a progress dot.
- **Mapping to dsh data**
  - Agents are the lead plus its subagents and teammates.
  - Each agent's role line is the subagent label/description.
  - The state badge comes from running status and the current tool kind.
  - The log comes from durable events: tool calls, approvals, api-session added/removed.
- The jellyfish creatures (H) can live inside the LIVE FLOW area, moving between stage nodes. The cards and the log give the dense, detailed status (G).

### H.2 Owner change (02:45): NO jellyfish. Agents are cute little sprites / ghosts (小精靈／小幽靈)
- The owner said 「水母好醜」 and replaced the jellyfish with **floating cute little spirits**. Each is a soft round blob with a glowing tail, drawn in code in the FOGCITY palette. The design is original.
- **Moving.** Wherever a spirit flies, that is what it is working on. It flies between the LIVE FLOW stage nodes and the data panels.
- **Expressions.** Its face changes with state:
  - focused eyes while running
  - looks up at you while waiting for your answer
  - sweat drop and rose-brown blink on error
  - happy hop when done
  - sleepy when idle
- **Tool props.** It carries a tiny prop for the tool kind: a magnifier for search, a page for read, a pen for write, and a small spirit splitting off when it dispatches a subagent.
- **Colour** shows state, as in H: aqua running, pink waiting, milk-tea done, rose-brown error.
- **Tap** a spirit to get the detailed status (G).
- **Performance:** they animate only on the visible page; their number is capped; at SAVER they are static; with reduced motion they don't drift.
- Every mention of "jellyfish/medusa" in H means these spirits now.
