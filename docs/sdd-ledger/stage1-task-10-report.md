# Stage 1 Task 10 report: approval and question glass sheet

Status: DONE. Lane branch `lane-t10`, base 41393f1.

## What changed
- `ui/shell/PromptSheet.kt` (new): `promptOrigin`, `PromptSheet` (stateless card, selection state inside, keyed by eventId), `PromptHost` (reads `DshEvents.prompts`, shows `first()`, "還有 N 個", busy and error handling, `BackHandler(enabled = true) {}` so back does nothing, never answers on its own).
- Card layout: scrim (sibling, swallows touches) plus a 30 dp glass card; title and English subtitle fixed on top, reason or questions in a `PROMPT_SCROLL` scroll area (`weight(1f, fill = false)`), error line, more-indicator and buttons fixed below. Spring rise 550 ms, scrim 350 ms, snapped when `pageMs == 0` (reduced motion).
- Minimal additive edits to shared files: `ui/MainActivity.kt` (import plus `overlay = { PromptHost(session?.events, session?.tree) }`), `ui/shell/Tags.kt` (PROMPT_SHEET, PROMPT_SCROLL, PROMPT_MORE, APPROVE_ONCE, REJECT, OPTION, QUESTION_SUBMIT, QUESTION_DECLINE, PROMPT_ERROR), `res/values/strings.xml` (prompt_* strings). `FogShell.kt` untouched (its existing `overlay` slot is used). One more additive edit: `chat/SessionIndex.kt` got `SessionRows.isTopLevel(state, id)`.
- Tests: `PromptSheetTest` (the brief's cases plus single-choice replace, busy, no-error, no-count, fresh selection per prompt), `PromptSheetLayoutTest` (412 dp: `theSheetFitsAt412dpWithoutCoveringItsOwnButtons`, plus six long questions), `PromptOriginTest`, and two `MainActivityTest` cases (the test class gained a `createEmptyComposeRule`, an `rpcBodies` list and an rpc handler on the fake dsh; those tests use `.visible()`).

## Evidence
- RED: the tests were written first and failed to compile (no `PromptSheet`, `promptOrigin` or Tags). I did not capture a separate runtime RED. After the first GREEN attempts, the failures were in my layout: a clickable scrim around the card merged the card's semantics, which I fixed by making the scrim a sibling.
- Mutation check: with `BackHandler(enabled = false)`, `backDoesNotDismissAPendingPrompt` fails.
- GREEN: full unit suite 805 tests, 0 failures, no warnings.

## Deviations
1. `promptOrigin` has a fourth optional parameter `isTopLevel` (default false): `SessionRows.titleOf` returns null for both untitled top-level conversations and subagents, so without it an untitled conversation could not show 「新對話」. Wired in `PromptHost` from the tree.
2. The origin strings are Kotlin constants (the function is a plain, non-composable function the tests call without a context); all card text is in strings.xml.
3. Card fill has an extra 60 percent `#FEDFE1` base under the specified white-to-`#FEDFE1` gradient. In the render without backdrop blur, the dock labels showed through the buttons at the specified alpha. Easy to remove in `PromptSheet.kt` (`onDrawBehind`).
4. Extra tags `PROMPT_SCROLL` and `PROMPT_MORE`.
5. A question with no options does not block submit; 不回答 is always available.

## Notes for Task 11
Pass `originOf` through `PromptHost(events, tree, originOf)`; MainActivity currently uses the default.

## Renders (throwaway test, not committed, 412x915 dp, FogShell behind)
- /tmp/claude-0/-home-user/1e630f02-b974-5959-b741-b3d92fa6411e/scratchpad/t10-shots/approval.png
- /tmp/claude-0/-home-user/1e630f02-b974-5959-b741-b3d92fa6411e/scratchpad/t10-shots/approval-long.png (2 000-character reason scrolls in the card, error line, buttons intact)
- /tmp/claude-0/-home-user/1e630f02-b974-5959-b741-b3d92fa6411e/scratchpad/t10-shots/question.png

## Concerns
- The card covers the dock, but TalkBack can still focus the dock beneath the scrim (the touch is blocked, the semantics are not hidden).
