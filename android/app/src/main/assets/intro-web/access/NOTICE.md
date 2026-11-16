# 科技檔案授權 — notice

This folder is an adapted copy of the startup animation from
**6shenhonghong9/dsh-startup-screen** (https://github.com/6shenhonghong9/dsh-startup-screen).

- Commit used: `7f3d2837c1aaef8293abc69093b0a35c80d0f0e8` (2026-11-01, "Add files via upload"), version 1.3.0 in its package.json.
- Licence: MIT. Copyright (c) 2026 沈宏宏. The original `LICENSE` file is in this folder, unchanged.
- Files taken: `lib/splash.js` and `lib/splash.css`. Both were edited; the changes are listed below.
- Files not taken: the DSH host plugin (`lib/index.js`), the DSH settings panel (`lib/client.js`), the offline preview page, tests, install scripts, docs and screenshots, and every audio file.

## What is not shipped, and why

| Original asset | Origin (per the repo) | Decision |
|---|---|---|
| `lib/assets/line-1.mp3` … `line-5.mp3`, the voice lines | Cut from a third-party video. The README says the MIT licence does not cover them. | Dropped. |
| `lib/assets/line-6.mp3`, the closing line | Described as "self-made", but AUDIO-FIELDS.md says the voice was cloned from the same third-party video, using a model with non-commercial terms. | Dropped. |
| `lib/assets/sfx-tick.mp3`, `sfx-ticks-run.mp3` | Cut from the same third-party video. | Dropped. |
| Sound synthesised in code (WebAudio blips, sweep, chime) and Web Speech text-to-speech | The original author's code. | Removed. The animation is silent. |
| DeepSeek whale logo (an SVG path in `splash.js`, taken from DSH's favicon) | DeepSeek's logo. | Removed. The user's picture is used instead, or a plain drawn figure. |
| Wordmarks: 深度求索 / DEEPSEEK / DEEPSEEK HARNESS / POWERED BY DEEPSEEK / D E E P S E E K | DeepSeek's names. | Replaced with DSH MOBILE text (below). |
| Default name `JOYCE MOORE` | Possibly a character name from the reference film. | Replaced with the app's name parameter. |
| `docs/screenshots/*.jpg` | The author's screenshots. | Not shipped. They were used as a visual reference only. |

Two things are kept: the grain texture, which is an inline SVG `feTurbulence` data URI in the original CSS (code, MIT), and the fonts. No font files are shipped. Text uses local system font names only.

## Every change made

### Hosting and contract

1. Added `index.html`. It has a Content-Security-Policy meta tag (`default-src 'self'; … connect-src 'none'; media-src 'none' …`), loads `splash.css` and `splash.js` from this folder, and contains no inline script. A `data:` favicon link stops the browser from asking for `/favicon.ico`.
2. Settings now come from the URL query string. The original read `window.__DSH_STARTUP__`, which the DSH host injected.
   - Contract parameters: `name`, `welcome`, `number`, `level`, `seconds`, `reduced`, `skippable`, `picture` and `lang`. `lang` is accepted but not read: zh-TW is the only text set, so every value shows the zh-TW text.
   - Optional extras:
     - `interact=1` restores the original "tap the button to continue" on steps 1 and 2. The default is off. While a step waits for that tap, the 15 s stall watchdog is paused, so the run only goes on when the button is tapped. The app should not pass `interact`.
     - `theme=dark` uses the original dark plate.
   - Each text parameter is trimmed, stripped of control and bidi characters, and cut to 24 characters. All text is inserted with `textContent`.
     - The characters removed are U+0000–U+001F, U+007F–U+009F, U+200B–U+200F, U+202A–U+202E and U+2066–U+2069.
     - The regex that removes them (`splash.js`, `text()` in `cfg()`) is written with ASCII `\uXXXX` escapes only. No file in this folder contains a raw zero-width or bidi control character, so every line reads the same in any editor or diff.
3. Speed comes from `seconds`: `speed = 18.7 s / seconds`, clamped to the original range of 0.35×–3×. Without `seconds` the original speed (1×) is used. At 1× the whole run takes about 18.7 s. The shortest possible run is about 6 s.
4. When the run ends, `window.DSHM.done()` is called and `location.hash` is set to `#done`. This happens at the moment the original removed its root and fired `dsh-startup:done`.
5. Skipping:
   - The original SKIP ▸ label and the Esc key are kept.
   - With `skippable=1`, a tap anywhere also skips.
   - With `skippable=0`, the SKIP label is hidden and taps are ignored. When `interact=1`, taps on the step 1–2 button continue the sequence instead of skipping it.
6. `reduced=1` shows a still frame for 1.5 s, then a 0.62 s fade; `done()` fires 0.7 s after the fade starts. The frame is the archive plate with the brand block, the frame and footer, and the welcome block, with no motion.
7. The page background is transparent. During the final fade, whatever sits under the WebView shows through, the same way the original revealed the app under it.

### Removed code paths

- The Windows desktop title-bar hack (`chromeSync`), which edited the host app's DOM and CSS variables.
- Replay and re-injection of the script (`injectSelf`, which created `<script src>` at runtime), the `window.__DSH_STARTUP_API__` global and the other `__DSH_STARTUP_*` globals.
- The 9-second "script never ran" fallback.
- The "liquid glass" option and the app-backdrop blur, which put CSS filters on the host app's own nodes.
- The `sessionStorage` and `localStorage` "already shown" flags and the play-mode logic.
- All audio: fetching the `/dsh-startup/asset/*.mp3` files, the WebAudio synth, Web Speech (TTS), and the gesture listeners that unlocked audio.

### Timing

- Each step in the original waited until its voice line had finished. The recordings are not shipped, so each line's recorded length is kept as a silent beat: 1.96, 1.16, 1.07, 1.18 and 1.70 s, and 3.73 s for the closing line. Steps therefore last as long as they did with the voice.
- The small orange voice meter (bottom left) lights up during each beat. It is scaled with `seconds`.
- All other waits, transitions and keyframes are the original values.

### Look

- Where the whale appeared (the black ident, the step 3 "drawn in one stroke" mark, and the welcome card), the user's picture now shows when `picture=1`. It sits in a thin square frame. Since 2026-11-16 this is `avatar.img` (see below).
  - On step 3 the frame is drawn as one stroke first and the picture then fades in. This replaces "outline, then fill".
  - If there is no picture, or neither `avatar.img` nor `picture.img` loads, a plain drawn head-and-shoulders figure is used. That figure is filled after its outline is drawn, like the whale.
- Brand text:

  | Original | Now |
  |---|---|
  | Black plate: 深度求索 / DEEPSEEK / DEEPSEEK HARNESS | DSH 行動版 / DSH MOBILE / MOBILE OS |
  | Brand block: DEEPSEEK / SYNTHESIZE INTELLIGENCE / HARNESS OS | DSH / SYNTHESIZE INTELLIGENCE / MOBILE OS |
  | Welcome box | DSH MOBILE |
  | Footer | POWERED BY DSH |
  | Welcome label | D S H · M O B I L E |
  | Step 3 label | The user's name (or number), letter-spaced |
  | `REV 0.1.0 · WEB PROFILE` | `REV 0.1.0 · MOBILE PROFILE` |

- Names and greeting:
  - The closing line 「欢迎 NAME 访问」 is now the app's welcome line, 「歡迎詞，名字」. Without a name it is just the welcome words, which default to 歡迎回來.
  - The ID card and the `ID CONFIRMED` readout use the `name`, `number` and `level` parameters. Blank values show 訪客, No.0001 and Lv.1.
- On-screen Chinese is converted to Traditional (for example 需要訪問許可, 身份資料確認, 讀取完畢 · 核驗通過). The English subtitles are unchanged.
  - The key hint 「按 Enter / Space，或点击按钮继续」 is now 「點一下按鈕繼續」, and it shows only when the step really waits (`interact=1`).
- Fonts: Android's `sans-serif-condensed` and the Traditional Chinese Noto names are placed first in the stacks. The original lists follow, unchanged.
- Phone layout:
  - Two sets of phone rules were added: portrait (width up to 600px) and landscape (height up to 520px). They only move and resize the existing pieces so nothing overlaps on a phone screen. Since the layout fixes below they are container queries on the page's own full-screen box, not media queries.
  - A small fit step scales down the font of a one-line text that would run past the screen edge. This covers the status line, the mono subtitle, the ident text and the WELCOME lines.
  - The wide letter-spacing comes from the original CSS, where `text-indent` is inherited by every per-letter span. It is kept as it is.
- The hex waterfall is written with `textContent` and `white-space: pre`. The original used `innerHTML` with `<br>`.
- The ID card and the hint are written with `textContent`. The original used `innerHTML`.

### Layout fixes (2026-11-15)

All of these are in `splash.css`. Nothing else changed.

1. The phone rules now ask the page's own full-screen box for its size, not the viewport. The root has `container-type: size`, the rules use `@container` instead of `@media`, and heights use `cqh` and `cqmax` instead of `vh` and `vmax`.
   - Why: in the app's WebView the viewport height reads as 0. So every phone, held upright too, got the landscape rules, and anything sized in `vh` was 0.
   - What that looked like on the CI emulator (320×640): the step count, the English subtitle and POWERED BY DSH were drawn on top of each other at the bottom, the big Chinese step title was missing (font size 0), and the verification ring was missing (size 0).
2. Portrait screens under 700px tall (900px since 2026-11-16, see below): the hex digits of step 4 moved from under the clock line to the empty space under the picture (`top: 61%`, `height: 104px`). They used to run over the REV/clock line and the step title.
3. Portrait: the letter-spaced name under the picture and the welcome line stay 16px inside each screen edge and wrap onto a centred second line when they are too long. A long Latin name used to run to both screen edges, with one letter left alone on a second line.
4. Landscape: the middle column moved down so it no longer runs over the REV/clock line.
   - The step title column moved from 32% to 50% of the height, the ring from 30% to 48%, the buttons from 44% to 58%, and WELCOME from 45% to 54%.
   - The hex digits are narrower on narrower screens (at most half the width minus 222px, and 200px at most), so they no longer run over the English subtitle line.
5. Screens that are both short and narrow (a 640×320 phone on its side, split screen):
   - Up to 720px wide: the bottom line's small letters use the portrait size (8px), and the ID line uses 9px, so the step count, the subtitle and POWERED BY DSH fit side by side.
   - Up to 600px wide: the subtitle moves up a line, and the ID line and the orange voice bars go above it.
6. Very short screens (under 340px tall, for example a phone on its side under the status bar):
   - The brand block, the picture, WELCOME and the ID card values are smaller, and the REV line and step bars sit higher.
   - The middle column hangs from just under the step bars instead of being centred, so it grows downwards when the picture comes in. The ring and the buttons follow it.

### Avatar and full-height WebView (2026-11-16)

1. All three picture spots are small squares (the black ident, the step 3 mark under the title, the welcome card), so they now show `avatar.img`: the 512×512 square around the face that the app serves next to `picture.img`. If `avatar.img` fails to load, the same spot loads `picture.img`; if that fails too, the drawn figure is used. With `picture=0` neither file is asked for. This page has no large background picture, so nothing else uses `picture.img`. Change in `splash.js` (`makeMark()`).
2. The app's WebView now has the real phone height, so the page sees an ordinary viewport. Every size was checked again in that mode. The changes below are in `splash.css`, plus two lines in `splash.js`.
   - Step 5 on upright screens: the ring's top arc ran through the REV/clock line and the step bars on short screens (320×616, 320×640, 360×640). The ring now starts below the step bars, and the step title moves down with it (with a short slide) so it stays in the ring's centre.
   - Step 5 on upright screens: the ring cut the first and last letters of the step title and of its English line. While the ring is shown, these two lines are sized from the ring so they fit inside it, and the hidden caret no longer takes room, so the title is centred. `splash.js` measures the two lines again when the ring comes in.
   - Step 4 on upright screens: the hex digits moved under the picture only on screens under 700px tall, so at 701–900px tall (360×720) they still ran over the REV/clock line. The limit is now 900px.
   - Step 4 on screens around 600px tall (320×600 to 320×616): the hex window reached about 3px into the ID CONFIRMED line. Its height is now limited so it ends above that line.
   - Very short landscape screens (640×320): the ring's upper left arc ran through the clock. The ring is a little smaller there (72% of the height instead of 78%), and the REV/clock line uses 8px letters, so the arc passes to the right of the clock.
   - In landscape the ring still crosses the middle of the step title. The ring is limited by the screen height there, so the title cannot fit inside it; the title is drawn above the ring and stays readable.

Credit for the design, motion and code: 沈宏宏 (MIT). The original README says its visual style references an anime work. Only the author's own code is used here.
