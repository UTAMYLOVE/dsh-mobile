# @dshm/ui

dsh plugin that injects three rows into dsh's `index.html` through `webserver/index-inject`:

1. `style` — the FOGCITY theme (`src/fogcity.ts`): pink liquid glass, built for speed on a phone. dsh's `--dsw-*` tokens first,
   a few selectors on stable hooks (`data-*`, `role`) for the glass panels; `backdrop-filter` only on the input bar and pop-ups;
   no animations of its own; nothing moves with `prefers-reduced-motion: reduce`.
2. `style` — hides what the managed-settings script marks and shows the note
   「這些設定由 DSH Mobile 管理，請點右上角齒輪」 in its place (`src/managed.ts`).
3. `script` — a MutationObserver that marks the settings DSH Mobile manages through its `--patch` (models and keys, subagent
   limits, Agent Teams, web search, session-log upload) and the DeepSeek sign-in. Missing blocks are ignored; errors are swallowed.

## Fonts

Embedded as base64 WOFF2 data URIs in `src/fonts.ts` (Latin subset only, so the stylesheet never fetches anything and CJK text
uses the system font). Both fonts are licensed under the **SIL Open Font License 1.1** (https://openfontlicense.org).

| Font | Copyright | Source file (npm package) | sha256 |
|---|---|---|---|
| Orbitron 500 | Copyright 2018 The Orbitron Project Authors (https://github.com/theleagueof/orbitron) | `@fontsource/orbitron@5.3.0` `files/orbitron-latin-500-normal.woff2` | `c13360768270c65d6c97701b100a88993885671dfdfd2a400aa58ea0195381ea` |
| Orbitron 700 | same | `@fontsource/orbitron@5.3.0` `files/orbitron-latin-700-normal.woff2` | `ee6acc5ad5349018f9ca71fab8118144160e110838931874150a1ac3f97db38d` |
| Chakra Petch 400 | Copyright 2018 The Chakra Petch Project Authors (https://github.com/m4rc1e/Chakra-Petch) | `@fontsource/chakra-petch@5.3.0` `files/chakra-petch-latin-400-normal.woff2` | `7d75be85dd1627e27ec151e9a3701e661bc4f8c2a93611bdd92f3a05d6bbd942` |
| Chakra Petch 600 | same | `@fontsource/chakra-petch@5.3.0` `files/chakra-petch-latin-600-normal.woff2` | `a5888696e9eb1b4bbbecc8eb3922b8369f49d4bddb72263e033cbd17f399be76` |

The fontsource packages repackage the Google Fonts builds (https://github.com/google/fonts). To regenerate `src/fonts.ts`:
`npm pack` the two packages, check the sha256 above, and write each file as `export const <NAME> = "<base64>";`.

The bundled plugin (`dist/index.js`, fonts inside) must stay at or below 300 KB; a vitest checks it.
