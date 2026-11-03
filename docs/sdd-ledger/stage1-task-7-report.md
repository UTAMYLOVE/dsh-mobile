# Stage 1 Task 7 report: native Markdown

Status: DONE_WITH_CONCERNS (see Deviations / Concerns).

## Changes
- `android/gradle/libs.versions.toml`: `commonmark = "0.32.0"` plus three libraries (commonmark, gfm-tables, gfm-strikethrough). All three jars and poms resolve through the Gradle mirror (HTTP 200 on the mirror, build resolved them).
- `android/app/build.gradle.kts`: three `implementation` lines.
- `android/app/src/main/assets/licenses/commonmark-BSD-2.txt`: verbatim LICENSE.txt from the `commonmark-parent-0.32.0` tag.
- `android/app/src/main/kotlin/app/dshmobile/ui/markdown/Markdown.kt`: `MdBlock`, `MarkdownParser` (MAX_CHARS 200 000, truncation notice paragraph), `LINK_TAG`, `rememberMarkdown` (Dispatchers.Default; while streaming at most one parse per 100 ms), `MarkdownView`.
- Tests: `ui/markdown/MarkdownParserTest.kt` (11), `MarkdownViewTest.kt` (4).

## Behaviour notes
- Inline: bold, italic, strikethrough, code (Monospace, white alpha .6 background), links. Only http/https links are clickable (`LinkAnnotation.Clickable(tag=url)` plus a `LINK_TAG` string annotation with the URL); other schemes render as plain text. View rewrites each Clickable's listener to call `onLink`.
- Raw HTML (block and inline) is literal text. Images are never loaded: "〔圖片〕alt", linked only if the URL is http/https.
- Code blocks and tables scroll horizontally (`horizontalScroll`); table columns are as wide as the widest cell, capped at 240 dp, via a custom `Layout`.
- Hostile nesting is bounded (block depth 24, inline depth 24, then plain text; plain-text extraction is iterative). Tests cover 50 000 nested quotes and 30 000 unclosed emphasis markers.

## Tests
- RED: first run after writing both test files failed to compile (`Unresolved reference 'MarkdownView'` etc., no implementation).
- GREEN: `--tests 'app.dshmobile.ui.markdown.*'` gives MarkdownParserTest 11/11 and MarkdownViewTest 4/4, 0 failures. The only compiler warnings are pre-existing ones in DshVersionsTest and RootfsInstallerTest.
- Full suite: `:app:testDebugUnitTest` full run: 643 tests, 0 failures, 0 errors, 0 skipped (58 classes), BUILD SUCCESSFUL.

## Deviations
1. License copyright line: the brief says "Copyright (c) 2015, Atlassian Pty Ltd" "per the original file"; the original LICENSE.txt at tag 0.32.0 reads "Copyright (c) 2015, Robin Stocker". Kept the original verbatim.
2. Soft line breaks render as a newline (not a space) so chat replies and poems keep their line structure; hard breaks also newline.
3. The inline-code "rounded corner 6" cannot be expressed in a SpanStyle; the background is drawn square. `LINK_TAG` is both a string annotation tag (what the brief's test wording implies) and the Clickable's tag is the URL (what the brief's interface text implies); both are present.
4. `MarkdownView` uses a plain Column (not lazy) so a long reply's last paragraph is composed (required by the 100k-character test); the chat page should host it inside its own lazy list.
5. `rememberMarkdown` parses synchronously for non-streaming text of 2 000 chars or less (first frame without flicker); everything else parses on Dispatchers.Default.
6. Link colour is the body text colour with underline (the palette forbids other text colours).

## Concerns
- `rememberMarkdown` and the debounce have no dedicated test (the brief lists none).
