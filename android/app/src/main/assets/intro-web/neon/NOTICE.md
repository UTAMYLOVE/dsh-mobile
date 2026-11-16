# 粉色霓虹敘事 (`neon`): notice

This folder holds the startup animation from **Z1ro2** (dsh plugin `dsh-neon-splash-v3`, version 0.2.0) by Z1ro2. It is adapted so that DSH Mobile can play it on its own in a locked-down WebView.

## Source

| | |
|---|---|
| Repository | github.com/wsy304450907-boop/Z1ro2 (branch `main`) |
| Commit | `397742bb37c976d6f4531689f2854f8cc6e2392b`, read with `git ls-remote` on 2026-11-15. `HEAD` and `main` both point to it, and it is the only branch. |
| How it was fetched | codeload.github.com refused the tarball with a 403 (the repository is not enabled for this session). Each of the 38 files was downloaded as data from `cdn.jsdelivr.net/gh/wsy304450907-boop/Z1ro2@397742bb…/<path>`. All 38 matched the sha256 and size in jsDelivr's listing for that commit. Nothing was installed, built or run. |
| Licence | MIT License, Copyright (c) 2026 Z1ro2. The original `LICENSE` file is in this folder, unchanged. |

## What is used from the original

| Original | Here | Notes |
|---|---|---|
| `.preview/client.template.js`: the stages, components, timeline, canvas drawing | `neon.js` | Re-stated as plain DOM code. See the changes below. |
| `.preview/client.template.js`: the `CSS` template string | `neon.css` | Copied rule for rule, with the changes below. |
| `LICENSE` | `LICENSE` | Unchanged. |
| (none) | `index.html` | New. The original had no page of its own: dsh loaded it as a plugin script into its desktop window. |

`client.js` in the repository is the same template with the two media files pasted in as base64. This was checked byte for byte. The readable template was used instead.

**Not used:**

- `index.js`: the dsh host half, a Cordis config schema.
- `cordis.patch.yml`, `package.json`, `locale/*.json`, `icon.svg`: dsh packaging.
- `.preview/*.mjs`: the author's build script and 16 self-test "gates". They were read, never run.
- `README.md`, `TECHNICAL.md`, `ARCHIVE.md`, `CHANGELOG.md`.
- All three files in `assets/` (see the asset table).

## Assets

| Original asset | Origin and licence | Decision |
|---|---|---|
| `assets/window-scene.jpg` (1440×1052, 126,276 B) and `assets/window-scene-thumb.jpg` (160×117): the background, pasted into `client.js` as base64 | A painted anime illustration of a pink-haired girl sitting at a rainy window. The README says it is the author's own and offered under MIT, but this cannot be checked. It looks like the work of an illustrator, and there is no source, artist or EXIF data. | **Dropped.** The user's picture (`picture.img`) takes its place. With no picture, or if the picture fails to load, the original's own CSS stand-in is used: `FallbackScene`, a sky, a city skyline and a white window frame. |
| `assets/voice-welcome-back.mp3` (58,036 B): a real woman's voice saying "welcome back, master" | The README says it is the author's own. TECHNICAL.md says it was "provided by the user". Neither the speaker nor their consent can be checked. | **Dropped.** The animation is silent. The greeting is the user's `welcome` words, shown as text (see change 4). |
| Desktop pet (dsh-pet `.webm` loops, fetched from `/dsh-pet-7340/thumb/main/…`) | Another plugin's files, fetched over HTTP at run time. Their licence is unknown here. | **Dropped**, together with the fetch. The user's picture takes the pet's place (see change 5). |
| Sound effects | Synthesised live with Web Audio, with no audio files. | **Dropped**: the animation must be silent. |
| Fonts | None bundled. The original names system fonts only ("SF Pro", "SF Mono", Consolas, "PingFang SC", …, `system-ui`, `monospace`). | Kept as written. On Android the generic fallbacks apply. |
| `icon.svg` | The author's own 64×64 icon. | Not needed. |

## Changes

Every change in the code is marked `[DSH Mobile]`.

1. **No framework, no dsh.**
   - The original was a React 18 component that ran inside dsh's module loader (`window.__ModuleLoader__`, `require('react')`). It was mounted in dsh's `shell.overlay` slot, and its settings came from dsh's `configForms`.
   - `neon.js` builds the same elements, with the same class names, in the same order and under the same stage conditions. Text always goes in as text nodes.
   - The timeline machine is unchanged: `buildTimeline`, `advance`, the `loginwait` gate and `skipTo`. So are the opening driver, the login phases and the four canvases (rain, digital waves, ripple, code danmaku).
   - No dsh object needs to be stubbed.
2. **Parameters come from the query string.**
   - The parameters are `?name=&welcome=&number=&level=&seconds=&reduced=0|1&skippable=0|1&picture=1|0&lang=zh-TW`.
   - Control, zero-width and bidi characters are removed from the text.
   - Text is cut by user-perceived characters: 24 for `name` and `welcome`, 16 for `number` and `level`.
   - `level` and `lang` are accepted and ignored. Nothing in the original shows a level, and the text is always Traditional Chinese.
3. **Timing.**
   - Without `seconds`, the animation plays at the original pace. It ends at 41.94 s whether or not SIGN IN is tapped, because the original's moments are counted from the start.
   - With `seconds`, the original's own speed setting (`timeScale`, range 0.05–4) is chosen so that the run lasts about `seconds`. The login gate's fixed waits are scaled with it: the 6 s auto-continue, the 1.4 s ripple and the 0.42 s pause after ACCESS GRANTED.
   - The shortest possible run is about 4 s, set by the 0.05 floor and the original's minimum stage lengths.
   - The original's restore-from-minimise mode, its compact pace and its play-mode settings are not used. The app decides when to play.
4. **Words.**
   - The terminal command `running Deepseek Harness` becomes `running DSH Mobile`. The HUD title `Deepseek Harness` becomes `DSH Mobile`. DeepSeek names and marks are not shipped.
   - The model name in "connecting workspace model …" was a DeepSeek model id. It is now `dsh/default`.
   - The username typed at login was `Z1ro2`, the author's handle. It is now the user's `name`, or 訪客 when there is none. The same goes for `subject:` in the boot script.
   - The large pink byline `designed by Z1ro2` now shows the user's welcome words (small, grey) and name (large, pink). With no name, it shows the welcome words in the large style. The welcome words default to 歡迎回來.
   - The voice line's moment, ACCESS GRANTED, prints `IDENTITY CONFIRMED · <welcome> <name>`. The original printed `WELCOME <NAME>` and played the voice clip.
   - The frame's bottom-right tag `Z1RO2` shows the user's `number`, or `DSH`.
   - The few Chinese strings are now Traditional Chinese, short and plain: the login prompt 點一下 SIGN IN 繼續, the skip hints 點一下即可跳過 and 點 SIGN IN 繼續 · 點別處跳過, and the code comment `// → 編譯介面`.
   - The English HUD and terminal text (SYSTEM ACCESS, USERNAME, the boot log, the plugin list and so on) is part of the look and stays as it was.
5. **Pictures.**
   - With `picture=1`, the user's picture replaces the window scene. It is loaded once from the relative `picture.img`, then blurred into focus exactly as the scene was.
   - It also takes the desktop pet's place above the login card and under the HUD title. It sits in the pet's container, with the pet's height, shadow, drop shadow and bob, cropped to a circle with a pink ring (`.dshm-pet-photo`).
   - As with the pet, this block appears only once the image has loaded, and never on an error.
   - With `picture=0`, or on an error, the original's CSS fallback scene is shown and there is no avatar.
6. **End signal.**
   - At the timeline's `done` moment, which is where the original unmounted its overlay, the page calls `window.DSHM && window.DSHM.done()` once and sets `location.hash = '#done'`.
   - The same signal is sent at once on a skip and on any script error.
   - A last-resort timer also sends it: the planned run, plus the whole login gate, plus 5 s.
7. **Skipping.**
   - With `skippable=1` (the default), the original's skip works: any press or key ends the animation at once. The original used `mousedown`; here it is `pointerdown`.
   - During login, the original kept clicks for the SIGN IN button and skipped only on keys. A phone has no keys, so a tap outside the login card skips, and a tap on the card never does.
   - With `skippable=0` there is no skip and no hint. SIGN IN still works, because it is part of the story, not a skip.
8. **Reduced motion.**
   - With `reduced=1`, or with no `reduced` and the system asking for less motion, the page shows a still version: the finished HUD frame (scene, edge frame, title, picture, welcome words and name, the full boot log) with every animation and canvas off.
   - It is held for 1.8 s (less if `seconds` is shorter), then fades over 0.2 s. The whole thing is at most about 2 s.
   - The original's `@media (prefers-reduced-motion)` block is now keyed on `html.dshm-calm`.
9. **Layout fixes for a phone.**
   - The original centred the HUD log panel and the signature with `transform: translateX(-50%)`. Their entrance animation (fill-mode `both`) replaces that transform, so they started at the middle of the screen and ran to the right. On a 412 px phone the log panel ran off the screen. Both are now centred with margins.
   - Portrait widths get a one-column preload list and wrapping footer and byline lines.
   - Short (landscape) heights get a smaller picture, a tighter login card, and the HUD text moved above the log panel.
   - The app's WebView tells the page a height of 0, so a height test alone also matched every portrait phone in the app. The landscape rules therefore also need a width over 600 px. For the same reason, in the app the short-portrait rule below applies to every portrait phone.
   - The original put the skip hint at the bottom right, on top of the frame's bottom-right tag (here the user's `number`, or DSH). It is now centred just above the signature line. In landscape, where the log panel reaches down to the frame's bottom bar, it sits in the top bar, left of OPTICS.
   - Short portrait phones (up to 740 px tall, such as 320×640) get the original's own compact picture size (92 px) in the HUD, with the HUD lines a little closer and higher. A long welcome or name no longer runs into the log panel.
   - A name or welcome that has to wrap is split into even lines (`text-wrap: balance`), so a long name never leaves one letter on a line of its own.
   - Nothing else in the layout was changed.
10. **One small robustness fix.**
    - The original decided whether the timeline was "waiting at login" by looking at the next pending moment. That was already `loginwait` before the gate had actually opened.
    - Here it is decided by the gate having really opened. In the original's own timings the two cannot differ, so nothing visible changes.
11. **Removed, because they belong to dsh or were only for its desktop window:**
    - the settings page and its CSS
    - the sound engine, the voice clip and `speechSynthesis`
    - the desktop-pet fetch
    - `localStorage` (display fallbacks) and `sessionStorage` (the "already played" mark)
    - the window focus, blur and visibility listeners that replayed the animation on restore
    - the `window.__dshNeon*` debug probes and the 50 ms layer-opacity tracer
    - the on-screen error box and the `__splashPetBase` / `__splashPetLocal` preview overrides
12. **Contract hooks in `index.html`:**
    - the required Content-Security-Policy `<meta>`
    - `<meta name="referrer" content="no-referrer">`
    - `<link rel="icon" href="data:,">`, so the browser does not ask for `/favicon.ico`
    - the stylesheet and script as files. There is no inline script or style.
    - The page background turns transparent when the final reveal starts, so a transparent WebView shows the app through the fade, as the original showed the dsh window.

## Hosting notes for the app

- Serve `index.html`, `neon.css` and `neon.js`, plus the user's picture as `picture.img` when `picture=1`, all from the same folder.
- The page makes no other request.
- The original pace is about 42 s. `seconds` makes it shorter, down to about 4 s.
- Without a tap on SIGN IN, the login auto-continues 6 s after the password is typed (scaled with `seconds`). By then the code → HUD stage's slot has passed, so that stage is skipped. This is the original's behaviour. Tapping SIGN IN when it lights up plays the full story.
- Keep a ceiling of your own as well: if the WebView never runs the script, nothing can signal done.
