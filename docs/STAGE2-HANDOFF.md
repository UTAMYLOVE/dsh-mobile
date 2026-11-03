# Stage 2 (0.3.0) handoff — read this first in a new session

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
  - Unfinished work-in-progress is on the local branch s2-killfix, commit 412c251, in worktree /home/user/wt-s2a. It is NOT pushed (pushing other branches is not allowed), so it is lost if the container restarts. If it is gone, redo it from the bug report.
- A partial, incomplete stage-2 design draft is in docs/superpowers/specs/2026-11-04-dsh-mobile-stage2-design.md. No plan file exists yet.
- Design references are in docs/design-reference/: agents-fui-reference.jpg and dashboard-reference-*.jpg.

## Process the owner chose: "精簡版" (lite)
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
