import { CHAKRA_PETCH_400, CHAKRA_PETCH_600, ORBITRON_500, ORBITRON_700 } from "./fonts.js";

/**
 * FOGCITY: pink liquid glass with a sci-fi edge, built for speed on a phone.
 *
 * - Colours: only [PALETTE] (as hex, or as rgba() of a palette colour for transparency). Text uses the two rose browns; aqua is
 *   a fill and glow only, never text (too little contrast). Dark mode only swaps the accents to pink, its backgrounds stay.
 * - Mostly dsh's own `--dsw-*` tokens; a few selectors on stable hooks (`data-*`, `role`) for the glass panels.
 * - Cost: `backdrop-filter` in exactly two places (the input bar and pop-ups), a static background (no animation, no moving
 *   mesh), no animation of our own, hover/press transitions of at most 200 ms, and nothing at all with prefers-reduced-motion.
 * - Fonts: Orbitron for (Latin) titles, Chakra Petch with tabular figures for numbers and small labels, embedded as Latin-only
 *   WOFF2 data URIs; Chinese falls back per glyph to the system font. Licences: packages/ui/README.md.
 *
 * Shapes: dsh rounds its buttons and inputs in class rules (some two classes deep); `:not(#dshm-fogcity)` (an id nothing has)
 * gives the pill rule an id's weight so it wins over all of them without !important.
 *
 * Selectors: dsh declares light tokens on `:root`/`body` and dark ones on `body[data-ds-dark-theme]`; `html body:not(...)` and
 * `html body[...]` are more specific than either, so this sheet wins wherever it sits in the page.
 */
export const PALETTE = {
  sakura: "#FEDFE1", // background
  milkTea: "#D7C4BB",
  pink: "#DC9FB4", // primary, glow
  white: "#FFFFFF",
  aqua: "#A5DEE4", // second accent: fill and glow only
  roseBrown: "#9E7A7A",
  text: "#5E4445",
  textSecondary: "#7D5F60",
} as const;

const LIGHT = "html body:not([data-ds-dark-theme])";
const DARK = "html body[data-ds-dark-theme]";
/** Google Fonts' "latin" subset: the browser never asks these fonts for CJK. */
const LATIN =
  "U+0000-00FF, U+0131, U+0152-0153, U+02BB-02BC, U+02C6, U+02DA, U+02DC, U+0304, U+0308, U+0329, U+2000-206F, U+20AC, U+2122, U+2191, U+2193, U+2212, U+2215, U+FEFF, U+FFFD";
const SYSTEM = "var(--dsh-font-family-text-default)";

const face = (family: string, weight: number, woff2: string) => `@font-face {
  font-family: "${family}";
  font-style: normal;
  font-weight: ${weight};
  font-display: swap;
  src: url(data:font/woff2;base64,${woff2}) format("woff2");
  unicode-range: ${LATIN};
}`;

export const FOGCITY_CSS = `${face("Orbitron", 500, ORBITRON_500)}
${face("Orbitron", 700, ORBITRON_700)}
${face("Chakra Petch", 400, CHAKRA_PETCH_400)}
${face("Chakra Petch", 600, CHAKRA_PETCH_600)}
html body {
  --dsw-radius-xs: 10px;
  --dsw-radius-sm: 14px;
  --dsw-radius-md: 18px;
  --dsw-radius-lg: 22px;
  --dsw-radius-xl: 26px;
  --dsw-radius-panel: 30px;
  --dsw-font-family-brand: "Orbitron", ${SYSTEM};
  --ds-transition-duration: 0.15s;
  --ds-transition-duration-fast: 0.1s;
  --ds-transition-duration-slow: 0.2s;
}
${LIGHT} {
  --dsw-static-deepseek-50: #FFFFFF;
  --dsw-static-deepseek-100: rgba(220, 159, 180, 0.22);
  --dsw-static-deepseek-200: rgba(220, 159, 180, 0.36);
  --dsw-static-deepseek-300: #D7C4BB;
  --dsw-static-deepseek-400: #DC9FB4;
  --dsw-static-deepseek-450: #DC9FB4;
  --dsw-static-deepseek-500: #DC9FB4;
  --dsw-static-deepseek-600: #9E7A7A;
  --dsw-alias-bg-base: rgba(254, 223, 225, 0);
  --dsw-alias-bg-layer-1: rgba(255, 255, 255, 0.42);
  --dsw-alias-bg-layer-2: rgba(255, 255, 255, 0.62);
  --dsw-alias-bg-layer-3: rgba(255, 255, 255, 0.8);
  --dsw-alias-bg-module-platform: rgba(255, 255, 255, 0.36);
  --dsw-alias-bg-overlay: rgba(255, 255, 255, 0.72);
  --dsw-alias-bg-multi-select: rgba(220, 159, 180, 0.2);
  --dsw-alias-bg-document-selection: rgba(220, 159, 180, 0.4);
  --dsw-alias-interactive-bg-hover: rgba(220, 159, 180, 0.16);
  --dsw-alias-settings-card-fill: rgba(255, 255, 255, 0.58);
  --dsw-alias-settings-card-stroke: rgba(220, 159, 180, 0.45);
  --dsw-alias-border-l1: rgba(158, 122, 122, 0.08);
  --dsw-alias-border-l2: rgba(158, 122, 122, 0.14);
  --dsw-alias-border-l3: rgba(158, 122, 122, 0.18);
  --dsw-alias-border-l4: rgba(158, 122, 122, 0.24);
  --dsw-alias-label-primary: #5E4445;
  --dsw-alias-label-primary-dimmed: #5E4445;
  --dsw-alias-label-secondary: #7D5F60;
  --dsw-alias-label-tertiary: #7D5F60;
  --dsw-alias-label-caption: #7D5F60;
  --dsw-alias-brand-primary: #5E4445;
  --dsw-alias-brand-text: #5E4445;
  --dsw-alias-link: #5E4445;
  --dsw-alias-button-primary-fill: #DC9FB4;
  --dsw-alias-button-primary-hover: #D7C4BB;
  --dsw-alias-label-primary-foreground: #5E4445;
  --dsw-specific-sidebar-fill: rgba(255, 255, 255, 0.45);
  --dsw-specific-sidebar-nav-item-hover: rgba(220, 159, 180, 0.16);
  --dsw-specific-sidebar-nav-item-active: rgba(220, 159, 180, 0.28);
  --dsw-specific-sidebar-nav-item-active-accent: rgba(220, 159, 180, 0.36);
  --dsw-specific-input-major: rgba(255, 255, 255, 0.72);
  --dsw-specific-bubble: rgba(255, 255, 255, 0.7);
  --dsw-specific-bubble-highlight: rgba(220, 159, 180, 0.36);
  --dsw-specific-selector: rgba(255, 255, 255, 0.6);
  --dsw-specific-tip: rgba(255, 255, 255, 0.7);
  --dsw-focus-ring-color: #DC9FB4;
  color: #5E4445;
  background-color: #FEDFE1;
  background-image: radial-gradient(36rem 28rem at 10% 6%, rgba(220, 159, 180, 0.42), rgba(220, 159, 180, 0) 70%),
    radial-gradient(32rem 26rem at 95% 32%, rgba(165, 222, 228, 0.38), rgba(165, 222, 228, 0) 70%),
    radial-gradient(40rem 30rem at 45% 108%, rgba(255, 255, 255, 0.75), rgba(255, 255, 255, 0) 70%);
}
${DARK} {
  --dsw-static-deepseek-400: #DC9FB4;
  --dsw-static-deepseek-450: #DC9FB4;
  --dsw-focus-ring-color: #DC9FB4;
  --dsw-alias-bg-document-selection: rgba(220, 159, 180, 0.4);
}
html body button:not(#dshm-fogcity),
html body input:not(#dshm-fogcity):not([type="checkbox"]):not([type="radio"]):not([type="range"]),
html body select:not(#dshm-fogcity) {
  border-radius: 999px;
  font-family: "Chakra Petch", ${SYSTEM};
  font-variant-numeric: tabular-nums;
}
html body label,
html body kbd,
html body time {
  font-family: "Chakra Petch", ${SYSTEM};
  font-variant-numeric: tabular-nums;
}
html body h1,
html body h2,
html body h3 {
  font-family: "Orbitron", ${SYSTEM};
}
html body textarea {
  border-radius: 22px;
}
html body [role="dialog"] {
  border-radius: 30px;
}
html body button:not(:disabled) {
  transition: background-color 0.15s ease, color 0.15s ease, box-shadow 0.15s ease, transform 0.12s ease;
}
html body button:active:not(:disabled) {
  transform: scale(0.97);
}
${LIGHT} [data-composer-card],
${LIGHT} [role="dialog"] {
  background-image: linear-gradient(160deg, rgba(255, 255, 255, 0.86), rgba(255, 255, 255, 0.56));
  box-shadow: 0 10px 30px rgba(158, 122, 122, 0.16);
}
${LIGHT} [data-composer-card]::after,
${LIGHT} [data-shortcut-modal]::after {
  content: "";
  position: absolute;
  inset: 0;
  border-radius: inherit;
  padding: 1px;
  background: linear-gradient(135deg, #FFFFFF, #DC9FB4, #A5DEE4);
  -webkit-mask: linear-gradient(#FFFFFF 0 0) content-box, linear-gradient(#FFFFFF 0 0);
  -webkit-mask-composite: xor;
  mask-composite: exclude;
  pointer-events: none;
}
${LIGHT} [data-composer-card] {
  backdrop-filter: blur(12px) saturate(1.15);
}
${LIGHT} [role="dialog"],
${LIGHT} [role="menu"],
${LIGHT} [role="listbox"] {
  backdrop-filter: blur(14px);
}
${LIGHT} [data-composer-card]:focus-within {
  box-shadow: 0 0 0 1px rgba(220, 159, 180, 0.9), 0 0 22px rgba(220, 159, 180, 0.5);
}
${LIGHT} [role="switch"][aria-checked="true"] {
  background-color: #A5DEE4;
  box-shadow: 0 0 10px rgba(165, 222, 228, 0.7);
}
@media (prefers-reduced-motion: reduce) {
  html body *,
  html body *::before,
  html body *::after {
    animation: none !important;
    transition: none !important;
  }
}
`;
