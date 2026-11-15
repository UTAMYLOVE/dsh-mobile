# 光點聚形波浪 (`wave`): notice

This folder holds the boot animation from **dsh-opening-free** (repository `opening-free-0.21.0`) by bigfatorangecatjohn. It is adapted so DSH Mobile can play it on its own in a locked-down WebView.

## Source

| | |
|---|---|
| Repository | github.com/bigfatorangecatjohn/opening-free-0.21.0 (branch `master`, package `dsh-opening-free` 0.21.1) |
| Commit | `b75f3e3e5346b48156c2193ca1c11ec3a78a60af`, read with `git ls-remote` on 2026-11-15. `HEAD` and `master` both point to it. |
| How it was fetched | codeload.github.com refused the tarball (403: repository not enabled for this session). Each file was downloaded as data from `cdn.jsdelivr.net/gh/bigfatorangecatjohn/opening-free-0.21.0@b75f3e3…/<path>`. All 59 files matched the sha256 and size in jsDelivr's listing for that commit. The 5 MB demo video was not downloaded. Nothing was installed, built or run. |
| Licence | MIT License, Copyright (c) 2026 bigfatorangecatjohn. The original `LICENSE` file is in this folder, unchanged. The licence covers the code. It does not cover the whale picture or the audio (see Assets). |

## What is used from the original

| Original file | Here | Notes |
|---|---|---|
| `plugin/dsh-opening/client.src.js` | `wave.js` | The cut that plays at boot (the `Cyber` component, `makeCyberScene`, `prepareWhale`, `phaseOf`, the helpers). Changed as listed below. Every change is marked `[DSH Mobile]`. |
| the `CSS` string inside `client.src.js` | `wave.css` | Only the rules the cut uses. Changed as listed below, each marked `[DSH Mobile]`. |
| `assets/boot-zh-short.sequence.json`, `assets/boot-zh.sequence.json` | the `CUES` table in `wave.js` | The timing numbers, exactly as `tools/build-client.mjs` inlined them. The step captions are converted to Traditional Chinese. |
| `LICENSE` | `LICENSE` | Unchanged. |
| (none) | `index.html` | New. The original had no page of its own: dsh loaded the plugin into its web page and drew the cut in the `shell.overlay` slot. |

**Not used:**

- `plugin/dsh-opening/client.js`. This is the built file. It is exactly a banner plus `client.src.js`, and this was checked byte for byte. The banner holds the eight audio tracks and the whale picture as base64 data, so none of it is used.
- In `client.src.js`:
  - the plugin wrapper (`window.__ModuleLoader__`, `require('react')`, `require('react-dom')`, `ctx.slots`, `ctx.locale`)
  - the Settings page with its eight language cards and preview queues, which used `localStorage`
  - the older `Stage` cut with `makeField`, `Rings`, the grain, grid and vignette layers and the "DEEPSEEK HARNESS / ACCESS CONTROL" top bar. That cut is never rendered by the original.
  - the English, Russian and Japanese text and styles
- `index.js`: the plugin's host half, which is empty.
- `package.json`, `cordis.patch.yml`, `locale/*.json`, `icon.svg`, `screenshots.json`, `screenshots/*.jpg`: dsh packaging and store material.
- `tools/*`: the offline audio, build, recording and packaging scripts.
- `docs/*`, `README.md`, `HANDOFF.md`, `AGENTS.md`.
- `demo/dsh-opening-demo-short.mp4`.

## Assets

| Original asset | Origin and licence | Decision |
|---|---|---|
| `assets/whale.png` (128,820 B). This is the mark the dots gather into, which then "freezes into ink". | The official DeepSeek whale logo, a trademark of 深度求索. The README calls it a fan tribute, "不要商用". The MIT licence does not cover it. | **Dropped.** It is replaced by the user's own picture (`picture.img`) in a round mark. With no picture, or if the picture fails to load, a disc is drawn in code in the original's own near-black ink, with the first character of the user's name (or of the welcome words) cut out. |
| `assets/boot-{zh,en,ru,ja}.ogg` and `boot-*-short.ogg` (8 files) | An offline mix of three things: TTS voices (Kokoro-82M, Apache-2.0; for Russian, Silero v5, CC BY-NC-SA, non-commercial), a whale-call sample cut from a copyrighted reference film the author kept out of the repository, and synthesised effects. | **Dropped.** The animation is silent. The original already fell back to the page clock when autoplay blocked the sound, and that fallback is now the only clock. |
| `assets/*.sequence.json` (8) | The author's timing data. MIT. | Kept as numbers only: the Chinese short and full cuts. |
| Voice lines and on-screen words: 「欢迎回来，指挥员」, 「访问协议」 and the others | The author's text. | The salute is now the user's welcome words and name. The step captions are in Traditional Chinese. |
| Fonts | The original bundles none. It uses system fonts ("Segoe UI", "Microsoft YaHei", system-ui, ui-monospace, Consolas…). | Unchanged. On Android these resolve to the system's sans-serif and monospace fonts. |
| Images or video drawn at run time | None. The wave, burst, dots, rings and tick bar are all drawn in code. | Nothing to do. |

## Changes

1. **Parameters come from the query string.**
   - The parameters are `?name=&welcome=&number=&level=&seconds=&reduced=0|1&skippable=0|1&picture=1|0&lang=zh-TW`, plus an optional `cut=short|full`.
   - Text has control and direction-override characters removed, and is limited to 24 characters (grapheme clusters).
   - Text is only ever a text node or an `aria-label`. Nothing is parsed as HTML.
   - `number`, `level` and `lang` are accepted and not shown: this cut has no ID readout, and its words are in Traditional Chinese only.
2. **Which cut plays.**
   - The original played the cut chosen in its Settings page, stored in `localStorage`. With nothing stored, that was the 18 s Chinese full cut.
   - Here the default is the Chinese **short cut** (3.808 s): the wave, the burst, the gathering dots, the rings and the salute. It suits the app's 1.5–8 s range.
   - `cut=full` plays the 18 s full cut instead, with the five access steps in Traditional Chinese: 需要訪問許可, 身份資料確認, 接收訪問需求, 開始讀取權限, 讀取完畢，核驗通過.
3. **The mark.**
   - `prepareWhale` became `prepareMark`. The original read the whale PNG and cut out the dark whale by luminance.
   - Now the silhouette is drawn in code, black on a clear canvas, and goes through the same threshold and bounding-box loop.
   - With the user's picture, the silhouette is a disc. The ink body is the picture: cropped to a square from the middle (for a tall picture, from 35 % down, where faces usually are) and laid over the disc. The blue and violet nebulas are then laid on at a third of their strength, so the picture keeps its own colours.
   - Without a picture, the ink body is the original's gradient at full strength, with one character cut out.
   - Everything after the silhouette is the original's, unchanged:
     - the CRT scanlines and top sheen
     - the two silver rims with their chromatic split
     - the halo and the scan blade
     - the torn-slice glitch
     - the ~1800 sample points the dots fly into
   - The mark is prepared before the clock starts. The original prepared it while the wave played.
4. **Words.**
   - The small line 「欢迎回来，」 is now the user's welcome words with a full-width comma. The default is **歡迎回來，**.
   - The big line 「指挥员」 is now the user's name.
   - Without a name, the welcome words take the big line and the small line stays empty.
   - The lead line and the name are kept on one line each. If either would run past 92 % of the screen width at its widest tracking, its font size is reduced.
   - The caption box is as wide as its text, so a long name stays centred.
5. **Timing.**
   - The beat times are the original's: wave 0–0.52 s, burst 0.52 s, dots 0.58–2.03 s (locking at 1.52 s), rings 1.06 s, welcome words 1.62 s, name 2.763 s, fade 0.28 s.
   - `seconds` is the length of the whole run, fade included:
     - Longer than the cut: the beats keep their own speed, and the landed frame holds, with the rings still turning and breathing and the bar still filling, until the fade.
     - Shorter: every beat plays faster by the same factor.
   - The clock starts when the empty stage (the cut's frame at 0 s) is first on screen. Before, a slow first paint in a WebView swallowed most of the 0.52 s wave.
   - The clock is the page clock. The original used the audio position when sound was playing.
6. **Phone sizes.**
   - Upright, the original layout fits as it is.
   - On a short, wide screen (height under 560 px and wider than 1.2× the height, for example 915×412), the original's caption maths pushed the salute up onto the lowest ring. There the whole stage is now drawn at 0.8 of its size, with the rings at 40 % of the height instead of 44 %. The canvas and the caption share this one rule.
   - The original caps the canvas resolution at 2× pixel density. That is kept.
7. **End signal.**
   - When the fade ends, the page removes the stage and calls `window.DSHM && window.DSHM.done()`. It also sets `location.hash = '#done'`.
   - The same signal is sent at once on a script error.
   - A last-resort timer sends it 6 s after the run should have ended.
   - It is only ever sent once.
   - When the fade starts, the page background turns transparent, so a see-through WebView shows the app through the fade. This is what the original's overlay did over the dsh page.
8. **Skipping.** The original's tap or key to skip works only when `skippable=1`, which is the default. As before, a skip ends 170 ms after the tap, during the fade.
9. **Reduced motion.**
   - The original did not play at all under `prefers-reduced-motion`.
   - Here `reduced=1` shows one landed frame: the mark, the rings, the words and a full bar. It holds for 1.5 s (less when `seconds` is shorter, never under 0.8 s), then fades in 0.2 s. The fade comes from the original's reduced-motion rule, which is now keyed on `html.is-calm`. The 1.2 % exit zoom is dropped for this version.
   - Without a `reduced` parameter, the same media query decides.
10. **Removed:**
    - the eight audio tracks and everything that played them (`new Audio`, `track.play()`)
    - `localStorage` (the stored language)
    - the React wrapper. A small `h()` function builds the same element tree as real DOM elements, and the effect runs directly.
    - the Settings page and the preview queues
    - the unused `Stage` cut and its styles
11. **Page and accessibility.**
    - `index.html` carries the required Content-Security-Policy `<meta>`, `<meta name="referrer" content="no-referrer">`, and `<link rel="icon" href="data:,">` so no `/favicon.ico` is requested. It loads the stylesheet and the script as files and has no inline script or style.
    - New base rules for a page of its own: no margins, no scrolling, no tap highlight, no text selection.
    - The `<h1>`'s bottom margin is set to 0, which the dsh page's reset used to do.
    - The stage is one `role="img"` labelled with the welcome line, for example 「歡迎回來，小雨」.

## Hosting notes for the app

- Serve `index.html`, `wave.css` and `wave.js` from one folder. With `picture=1`, also serve the user's picture there as `picture.img`.
- In tests the page made exactly these requests and nothing else.
- Running times:
  - The short cut takes 3.808 s, or `seconds` when it is given, counted from the first frame on screen.
  - Start-up comes before that: decoding the picture and preparing the mark take about 0.1–0.2 s on a desktop CPU, and about 0.6 s with the CPU slowed 4×.
  - The full cut takes 18 s.
  - The still version takes about 1.7 s.
- Keep a ceiling of your own as well. If the WebView never runs the script, nothing can send the done signal.

Credit for the design, motion and code: bigfatorangecatjohn (MIT).
