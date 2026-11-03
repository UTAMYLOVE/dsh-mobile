# Stage 2 (0.3.0) handoff — read this first in a new session

## Progress (update this list as work lands)
- DONE (all on claude/dsh-mobile-cloud-handoff-vfhvdh):
  - A restart bug (1cba479), J auto review (5e03cc5), B thinking strength + reviewer rounds (f8c6096), I.1 tables + price (f65d7f1)
  - C stats: per-reply line, context bar, conversation totals, overview per-model stats (4bae534)
  - E/G/H war room: spirits on LIVE FLOW, agent cards with detailed status, log, history of finished agents; overview war strip; chat activity line + data panel (f3085ac)
  - D bookshelf: books = conversations, setting cards, auto chapters, reader, 加入書架 (1537dc9)
  - F + I.2/I.3: share any workspace file, plain-words limit/sandbox errors, phone note for the agent, team default 16 when phantom off, small-screen modal (af7f584)
  - Batch 2: app skills in $DSH_HOME/skills (novel, daily brief, bookkeeping, phone, pages), auto Flash/Pro routing, remote MCP servers (a5e8890). Schedules and long-term memory were already in dsh / our memory plugin.
  - e2e t25–t28 added; t19 switches to 清單模式 first.
- CI GREEN: run 41731946451 attempt 2 at 562802e (attempt 1 had a one-off t23 flake: send stayed disabled, dsh healthy; passed in 3 other runs). Tag command given to the owner: `gh api repos/UTAMYLOVE/dsh-mobile/git/refs -f ref='refs/tags/v0.3.0+5' -f sha=562802e0170ac5443f85e312816628a5f25069bc`.
- Not done / later: native dsh without proot (research); workflow sandbox under proot (needs dsh support); MCP with secrets (would break the keys rule).

## State on 2026-11-04 03:05 (Taipei)
- 0.2.0 is released: v0.2.0+4 at commit 0d06ebf. CI is green and it is published to UTAMYLOVE/dsh-mobile-releases.
- Stage-2 requirements are in docs/superpowers/specs/2026-11-04-stage2-requirements.md (sections A–H.2).
- Owner decisions:
  - Everything ships in one release, 0.3.0+5, built in two internal batches.
  - Batch 1 is A–H:
    - bug fix
    - chat controls: thinking strength, N reviewer rounds
    - stats
    - bookshelf and reading mode
    - war room with cute spirit agents
    - detailed status
    - small-screen fixes
    - file sharing
    - price fix
  - Batch 2 is "evolution" of dsh through official extension points only:
    - skills, for example novel writing and a daily briefing
    - auto model routing
    - long-term memory
    - schedules
    - MCP
    - phone abilities
    - self-proposed pages

    It is not yet in the requirements file. Add it before planning.
  - The agents are cute floating spirits (小精靈／小幽靈): not jellyfish, not pixel art.
  - Use the FOGCITY palette only, on the existing pink glass base.
  - "Status must be detailed" (section G).
- Bug A: the root cause and fix plan are in docs/sdd-ledger/bug-writer-held.md.
  - On Android, Process.destroy() sends SIGTERM. Termux proot ignores SIGTERM, so dsh never dies, and a restart starts a second dsh that holds session.lock.
  - DONE and merged (see Progress).
- A partial, incomplete stage-2 design draft is in docs/superpowers/specs/2026-11-04-dsh-mobile-stage2-design.md. No plan file exists yet.
- Design references are in docs/design-reference/: agents-fui-reference.jpg and dashboard-reference-*.jpg.

## Process: NO SUBAGENTS (owner, 2026-11-04 03:20)
- The owner said: 「不用subagent了真的太燒額度了」. Do all work directly in the main session. Do not dispatch Agent or Workflow calls.
- Keep the token cost down:
  - Read only the files you need, in ranges.
  - Run the focused tests first and the full suite once per batch.
  - Push to CI rarely.
- Start a fresh session per big chunk, so the context stays small.

## Lite process, still applies apart from the subagent parts
- Write a plan first.
- Keep the unit tests and the CI emulator e2e. Push to CI rarely and batch changes per push. Use [skip ci] for ledger-only pushes.
- Give full reviews only to the core: the restart/kill bug, background work, and the connection.
- UI tasks get no per-task review. Run one final whole-branch review: one reviewer, Opus.
- Models:
  - Opus only for hard or core work and the final review.
  - Sonnet for UI implementation and reviews.
  - Haiku for mechanical fixes and re-checks.

## Budget
- The owner's weekly limit was 73% used at 03:05 on Wednesday. It resets Friday 20:59 (Taipei).
- Work in priority order: bug A first, then the batch-1 features, then batch 2.

## Red lines (unchanged)
- Never modify official dsh. Permissions stay the spec's 9. Keys stay in the Keystore only. No uploads.
- Only the owner creates release tags: `gh api repos/UTAMYLOVE/dsh-mobile/git/refs -f ref='refs/tags/v0.3.0+5' -f sha=<green sha>`.
- Push only to claude/dsh-mobile-cloud-handoff-vfhvdh.
- Commit trailers:
  - Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  - Claude-Session: https://claude.ai/code/session_01TjBB5vvKdwZye97QPVXiMw
