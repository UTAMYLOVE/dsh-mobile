# 櫻花動漫風 (`sakura`): notice

This folder contains the startup animation from **dsh-startup-animation** by KylinQ01, adapted so DSH Mobile can play it on its own in a locked-down WebView.

## Source

| | |
|---|---|
| Repository | github.com/KylinQ01/dsh-startup-animation (branch `main`, package version 2.1.0) |
| Commit | `3a8c57a8fe2113b81ff861e9fc4589ad9bae6460` (from `git ls-remote`, 2026-11-15) |
| How it was fetched | codeload.github.com refused the tarball (403, repository not enabled for the session). Each file was downloaded as data from `cdn.jsdelivr.net/gh/KylinQ01/dsh-startup-animation@3a8c57a8…/<path>`. All 17 files matched the sha256 and size in jsDelivr's listing for that commit. Nothing was installed, built or run. |
| Licence | MIT License, Copyright (c) 2026 KylinQ01. The original `LICENSE` file is in this folder unchanged. |

## What is used from the original

| Original file | Here | Notes |
|---|---|---|
| `assets/boot.js` | `boot.js` | The animation's script. Changed as listed below. Each change is marked `[DSH Mobile]`. |
| `assets/boot.css` | `boot.css` | The animation's styles. Changed as listed below. Each change is marked `[DSH Mobile]`. |
| `LICENSE` | `LICENSE` | Unchanged. |
| (none) | `index.html` | New. The original had no page of its own: its dsh host plugin injected the CSS and script into the dsh web page. |

**Not used:**

- `lib/index.js`: the Node host plugin. It registered HTTP routes, stored uploads under `$DSH_HOME` and injected inline scripts into dsh's `index.html`.
- `client/client.js`: the dsh settings tab, plus a MutationObserver that rewrote the dsh home-screen headline.
- `assets/hero.css` and `assets/wallpaper.css`: dsh home-screen styling.
- `check.mjs`: the Node self-test.
- `cordis.patch.yml` and `package.json`: dsh packaging.
- The four READMEs.
- Both images. See the asset table.

## Assets

| Original asset | Origin and licence | Decision |
|---|---|---|
| `assets/bizhi.jpg` (1259×890, 222,991 B): the background and the dsh wallpaper | Third-party fan art of 和栗薰子 (Waguri Kaoruko, from 『薫る花は凛と咲く』), signed "Mailaohuya". It is not covered by the MIT licence, and no licence is given for it. | **Dropped.** It is replaced by the user's own picture (`picture.img`). With no picture, a plain light ground drawn in CSS takes its place (`.dshs-bg.is-plain`), using the original's own white, pink and lavender. |
| `assets/toxiang.jpg` (1259×1259, 119,513 B): the avatar | An anime girl with cat ears. The origin is unknown, it is not covered by the MIT licence, and no licence is given for it. | **Dropped.** It is replaced by the user's own picture. With no picture, a CSS stand-in is shown: the first character of the user's name, or, when there is no name, a five-petal blossom built from the original's own sakura-petal shape and colours. |
| Default greeting 「你好，我是和栗薰子，欢迎使用Deepseek Harness」 | Text naming the character. It was used only by the dsh home-screen headline, not by the animation. | **Dropped**, together with `client.js`. |
| Audio, video, fonts | The original has none. Text uses the system fonts (`system-ui` …). | Nothing to do. The animation is silent. |

## Changes

1. **Parameters come from the query string**, not from the dsh host's `window.__dshsConfig`.
   - The parameters are `?name=&welcome=&number=&level=&seconds=&reduced=0|1&skippable=0|1&picture=1|0&lang=zh-TW`, plus an optional `festival=auto|off|sakura|snow|newyear`.
   - Text is limited to 24 characters (grapheme clusters), with control characters removed.
   - Text is only written with `textContent` or an `aria-label`, never as HTML.
   - Only constant markup still goes through `innerHTML`.
   - `number`, `level` and `lang` are accepted and ignored. The original screen shows none of them, and the text is always in Traditional Chinese.
2. **Pictures.**
   - The original's `<img src="/dsh-startup/bg">` and `<img src="/dsh-startup/avatar">` (dsh host routes) are replaced.
   - With `picture=1`, both the background and the avatar use the relative `picture.img`.
   - With `picture=0`, or if `picture.img` fails to load, the stand-ins drawn in code are used (see the asset table).
   - When there is no picture to wait for, the entrance starts at once. The original always waited for its two images, with a 1.2 s cap.
3. **Words.**
   - The greeting is the user's `welcome`.
   - Without one, it is the original's 欢迎回来 in Traditional Chinese: **歡迎回來**. On New Year it is 新年快乐 → **新年快樂**.
   - The status line 正在准备你的工作台 → **正在準備你的工作台**.
   - Greetings longer than the original's 4 characters wrap and stay centred.
   - Each character still rises on its own, as in the original. Because every character is its own inline-block, a browser would drop the width of a lone space and could wrap between any two letters of a word. So, for the user's words:
     - Spaces are plain text between the characters. They keep their width, and lines wrap at them.
     - Characters that ordinary text keeps on one line are grouped into one inline-block run, `<b class="dshs-word">`: a Latin word or a number, and a character together with punctuation that must not start a line (，。！？」ー …) or end one (「（ …). A run moves to the next line whole, and wraps inside only if it is wider than the whole line.
     - Lines can still wrap between Han, kana, Hangul and emoji characters, as in ordinary text.
     - Inside a run, each character keeps the same spacing (letter-spacing and indent) as everywhere else.
   - The default 歡迎回來 has no spaces and no runs, so its markup, layout and motion are unchanged.
   - Each character's rise delay is now set in `boot.js`. The first four get the same values as boot.css's `nth-child` rules (0.2 s + 80 ms each), because those rules no longer line up once a run is a child.
4. **Timing.**
   - The original waited for the dsh home screen to mount: at least 1.5 s, at most 5 s, then a 0.7 s exit.
   - Here the whole animation lasts `seconds` (1.5–8, default 3): the scene holds for `seconds − 0.7 s`, then the original 0.7 s exit plays.
   - The hold always lets the whole greeting land, and then stay fully shown for 0.3 s, before the exit starts. In the original, character *i* starts rising at 0.2 s + 80 ms × *i* and takes 0.62 s, so its 4-character greeting lands at 1.06 s.
     - If that 80 ms step would leave the last characters of a longer greeting still rising at the exit, the step after the 4th character shrinks just enough, but not below 20 ms.
     - Only when even a 20 ms step does not fit is the hold made longer.
     - So the hold is never shorter than 1.1 s, nor shorter than the last landing + 0.3 s.
   - Examples:
     - Up to 15 characters at the default 3 s keep the original 80 ms step.
     - 24 characters at 3 s: a 47 ms step, the last character lands at 2.0 s, and the exit starts at 2.3 s, as before.
     - 24 characters at 1.5 s: a 20 ms step, the last character lands at 1.46 s, the exit starts at 1.76 s, and it is over at about 2.5 s.
     - 歡迎回來 at 1.5 s: the exit starts at 1.36 s instead of 1.1 s.
   - Spaces do not rise, so they take no step.
   - The progress bar fills to 92 % over the hold. The original filled it over its first 1.5 s. It completes on exit, as before.
5. **End signal.**
   - When the exit's fade ends, the page calls `window.DSHM && window.DSHM.done()` and sets `location.hash = '#done'`. The fade end is detected by `transitionend`, with the original's timer as a fallback.
   - The same signal is sent at once on a script error.
   - It is also sent by a last-resort timer after 14 s.
   - The signal is only ever sent once.
6. **Skipping.** The original's tap or key to skip works only when `skippable=1`, which is the default. A skip plays the original exit and then signals done.
7. **Reduced motion.**
   - The original's `@media (prefers-reduced-motion: reduce)` block (with its `html:not(.dshs-force-motion)` escape hatch) is now keyed on `html.dshs-calm`.
   - `boot.js` sets `html.dshs-calm` from `reduced=1`. Without a `reduced` parameter, it falls back to the same media query.
   - The rules and their specificity are unchanged.
   - The calm version is also short: at most 2 s in all.
   - In the calm version every character of the greeting appears at once, in 0.2 s, so the stagger timing above does not apply. The spaces and runs from change 3 do apply.
8. **Removed, because they belong to dsh or to its host:**
   - the `sessionStorage` "played in the last 60 s" mark and the early `<head>` script that read it
   - `window.__dshsNoRemember`
   - the dsh home screen's "module assembly" (`assemble()`, `@keyframes dshs-assemble`, `html.dshs-boot #root`), and the `#root` / `textarea` readiness check
   - the `eco` and `fancy` quality tiers (`html.dshs-fx-*`, `html.dshs-motion`). The bundle always plays the default `standard` tier.
   - the birthday egg and its confetti. Its date lived in the host's config.
9. **Kept:**
   - the festival eggs by the phone's date, as the original's default "auto" mode did: sakura petals 3/20–4/20, snow 12/1–2/15, New Year 1/1–1/3
   - the pointer parallax. On a phone it only reacts to a mouse or stylus, as before.
10. **Page and accessibility.**
    - New base rules for a standalone page: no margins, no scrolling, no tap highlight, no text selection. When the exit starts, the page background turns transparent, so a transparent WebView shows the app through the fade.
    - The original hid the splash from screen readers, because the dsh page under it was the content. Here the splash is the page, so it is one `role="img"` whose label is the greeting and the name.
11. **Contract hooks in `index.html`:**
    - the required Content-Security-Policy `<meta>`
    - `<meta name="referrer" content="no-referrer">`
    - `<link rel="icon" href="data:,">`, so the browser does not request `/favicon.ico`
    - the stylesheet and script as files. There is no inline script or style.

## Hosting notes for the app

- Serve `index.html`, `boot.css`, `boot.js` and, when `picture=1`, the user's picture as `picture.img`, all from the same folder.
- The page makes no other request.
- Keep a ceiling of your own as well. If the WebView never runs the script, nothing can signal done.
