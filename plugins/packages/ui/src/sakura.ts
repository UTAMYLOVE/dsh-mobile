/**
 * The sakura (light pink) theme as one stylesheet for dsh's index.html.
 *
 * Every colour of dsh's web UI hangs off the `--dsw-static-deepseek-*` scale (light mode's primary is step 500), so the
 * scale is swapped for pinks and all aliases built on it follow. Text colours (`--dsw-alias-label-primary`,
 * `--dsw-alias-brand-primary`, `--dsw-alias-brand-text`) are deliberately left alone: they are near-black text, not accents.
 *
 * Selectors: dsh declares light tokens on `:root`/`body` and dark ones on `body[data-ds-dark-theme]`. `html body` is one
 * type selector more specific than a plain `body` rule, so this sheet wins whatever its position in the page; a custom
 * property set on `body` itself also beats one dsh sets on `:root`. The dark rule is `html body[data-ds-dark-theme]`
 * (more specific than the light one and than dsh's own dark rule) and touches accents only.
 */
const DARK = "[data-ds-dark-theme]";

export const SAKURA_CSS = `:root, html body {
  --dsw-static-deepseek-50: #fff0f5;
  --dsw-static-deepseek-100: #ffd6e3;
  --dsw-static-deepseek-200: #fcb8cf;
  --dsw-static-deepseek-300: #f98fb2;
  --dsw-static-deepseek-400: #f06595;
  --dsw-static-deepseek-450: #e24a82;
  --dsw-static-deepseek-500: #d6336c;
  --dsw-static-deepseek-600: #c2255c;
  --dsw-static-deepseek-700: #a61e4d;
  --dsw-static-deepseek-800: #8a1a41;
  --dsw-static-deepseek-900: #6d1535;
  --dsw-alias-bg-base: #fff5f8;
  --dsw-alias-bg-layer-1: #fff5f8;
  --dsw-alias-bg-layer-2: #fff5f8;
  --dsw-alias-bg-layer-3: #fff5f8;
  --dsw-alias-bg-module-platform: #fff5f8;
  --dsw-alias-bg-document-selection: rgba(214, 51, 108, 0.4);
  --dsw-focus-ring-color: #d6336c;
}
html body:not(${DARK}) {
  background-color: #fff5f8;
}
html body${DARK} {
  --dsw-static-deepseek-400: #f06595;
  --dsw-static-deepseek-450: #e64980;
  --dsw-static-deepseek-800: #34415b;
  --dsw-static-deepseek-900: #283142;
}
`;
