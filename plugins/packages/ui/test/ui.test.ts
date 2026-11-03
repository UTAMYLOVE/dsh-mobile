import { build } from "esbuild";
import { fileURLToPath } from "node:url";
import { describe, expect, it, vi } from "vitest";
import * as plugin from "../src/index.js";
import { FOGCITY_CSS, PALETTE } from "../src/fogcity.js";
import { MANAGED_CSS, MANAGED_SCRIPT } from "../src/managed.js";

type Row = { kind: string; text?: string };
type Listener = (table: Row[]) => void;

function makeCtx() {
  const listeners = new Map<string, Listener[]>();
  const logger = { info: vi.fn(), warn: vi.fn(), error: vi.fn() };
  const ctx = {
    logger,
    on: vi.fn((event: string, fn: Listener) => {
      listeners.set(event, [...(listeners.get(event) ?? []), fn]);
      return () => undefined;
    }),
  };
  const emit = (event: string): Row[] => {
    const table: Row[] = [];
    for (const fn of listeners.get(event) ?? []) fn(table);
    return table;
  };
  return { ctx, emit, listeners, logger };
}

/** The CSS without the embedded font data (base64 is not CSS and may contain anything). */
const css = FOGCITY_CSS.replace(/url\(data:font\/woff2;base64,[A-Za-z0-9+/=]+\)/g, "url(FONT)");

/** Every `selector { declarations }` rule (one level of @media nesting is flattened). */
function rules(source: string): Array<{ selector: string; body: string }> {
  const out: Array<{ selector: string; body: string }> = [];
  for (const m of source.matchAll(/([^{}]+)\{([^{}]*)\}/g)) out.push({ selector: m[1]!.trim(), body: m[2]! });
  return out;
}

/** The declarations of the first rule whose selector contains [selector]. */
function block(source: string, selector: string): string {
  const r = rules(source).find((x) => x.selector.includes(selector));
  if (!r) throw new Error(`no rule for ${selector}`);
  return r.body;
}

/** `name: value` pairs of every declaration in [source]. */
function declarations(source: string): Array<[string, string]> {
  const out: Array<[string, string]> = [];
  for (const r of rules(source)) {
    for (const d of r.body.split(";")) {
      const i = d.indexOf(":");
      if (i > 0) out.push([d.slice(0, i).trim(), d.slice(i + 1).trim()]);
    }
  }
  return out;
}

const PALETTE_HEX = new Set(Object.values(PALETTE).map((c) => c.toUpperCase()));
const rgbOf = (hex: string) => [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16)).join(",");
const PALETTE_RGB = new Set(Object.values(PALETTE).map(rgbOf));
const AQUA = "#A5DEE4";
const DARK = "[data-ds-dark-theme]";

describe("@dshm/ui plugin", () => {
  it("is named dshm-ui", () => {
    expect(plugin.name).toBe("dshm-ui");
  });

  it("injects the FOGCITY theme, the managed-settings style and the managed-settings script", () => {
    const { ctx, emit } = makeCtx();
    plugin.apply(ctx);
    expect(emit("webserver/index-inject")).toEqual([
      { kind: "style", text: FOGCITY_CSS },
      { kind: "style", text: MANAGED_CSS },
      { kind: "script", text: MANAGED_SCRIPT },
    ]);
  });

  it("only listens to the index-inject event", () => {
    const { ctx, listeners } = makeCtx();
    plugin.apply(ctx);
    expect([...listeners.keys()]).toEqual(["webserver/index-inject"]);
  });

  it("appends to a table that already has rows", () => {
    const { ctx, listeners } = makeCtx();
    plugin.apply(ctx);
    const table: Row[] = [{ kind: "script", text: "x" }];
    for (const fn of listeners.get("webserver/index-inject") ?? []) fn(table);
    expect(table).toHaveLength(4);
    expect(table[0]).toEqual({ kind: "script", text: "x" });
    expect(table.slice(1).map((r) => r.kind)).toEqual(["style", "style", "script"]);
  });

  it("logs that it loaded, without CSS", () => {
    const { ctx, logger } = makeCtx();
    plugin.apply(ctx);
    expect(logger.info).toHaveBeenCalledTimes(1);
    const msg = String(logger.info.mock.calls[0]![0]);
    expect(msg).toContain("[dshm-ui]");
    expect(msg.length).toBeLessThan(200);
  });

  it("needs no service, so headless compositions load it", () => {
    expect((plugin.inject ?? []) as string[]).toEqual([]);
  });

  it("bundles to at most 300 KB with the fonts inside", async () => {
    const entry = fileURLToPath(new URL("../src/index.ts", import.meta.url));
    const out = await build({ entryPoints: [entry], bundle: true, platform: "node", format: "esm", target: "node24", write: false });
    const bytes = out.outputFiles[0]!.contents.byteLength;
    expect(bytes).toBeGreaterThan(30_000); // the fonts really are inside
    expect(bytes).toBeLessThanOrEqual(300 * 1024);
  });
});

describe("FOGCITY_CSS palette", () => {
  it("is the FOGCITY palette: six colours and the two text colours", () => {
    expect(PALETTE).toEqual({
      sakura: "#FEDFE1",
      milkTea: "#D7C4BB",
      pink: "#DC9FB4",
      white: "#FFFFFF",
      aqua: "#A5DEE4",
      roseBrown: "#9E7A7A",
      text: "#5E4445",
      textSecondary: "#7D5F60",
    });
  });

  it("uses no hex colour outside the palette", () => {
    const hexes = [...css.matchAll(/#[0-9a-fA-F]{3,8}\b/g)].map((m) => m[0].toUpperCase());
    expect(hexes.length).toBeGreaterThan(10);
    for (const h of hexes) expect(PALETTE_HEX, h).toContain(h);
  });

  it("builds every rgb()/rgba() from a palette colour", () => {
    const fns = [...css.matchAll(/rgba?\(([^)]*)\)/g)];
    expect(fns.length).toBeGreaterThan(5);
    for (const m of fns) {
      const channels = m[1]!.split(",").slice(0, 3).map((s) => s.trim()).join(",");
      expect(PALETTE_RGB, m[0]).toContain(channels);
    }
  });

  it("uses no colour keywords or other colour functions", () => {
    expect(css).not.toMatch(/\b(black|white|gray|grey|pink|red|blue)\b(?![-\w])/i);
    expect(css).not.toMatch(/\b(hsla?|hwb|lab|lch|oklab|oklch|color-mix)\(/i);
  });

  it("paints the light body #FEDFE1 with static glows, never in dark mode", () => {
    const body = rules(css).find((r) => r.body.includes("background-color: #FEDFE1"));
    expect(body).toBeDefined();
    expect(body!.selector).toContain(`:not(${DARK})`);
    expect(body!.body).toMatch(/background-image:\s*radial-gradient/);
    for (const r of rules(css)) if (/background/.test(r.body)) expect(r.selector).not.toMatch(/\[data-ds-dark-theme\](?!\))/);
  });

  it("sets the text colours to the two rose browns", () => {
    const light = block(css, `body:not(${DARK})`);
    expect(light).toContain("--dsw-alias-label-primary: #5E4445");
    expect(light).toContain("--dsw-alias-label-secondary: #7D5F60");
  });

  it("never uses aqua as a text colour", () => {
    const textProps = /^(color|caret-color|-webkit-text-fill-color|--dsw-alias-label-[\w-]+|--dsw-alias-link|--dsw-alias-brand-text|--dsw-alias-brand-primary)$/;
    for (const [name, value] of declarations(css)) {
      if (!textProps.test(name)) continue;
      expect(value.toUpperCase(), name).not.toContain(AQUA);
      expect(value.replace(/\s/g, ""), name).not.toContain("165,222,228");
    }
    expect(css.toUpperCase()).toContain(AQUA); // still used, as a fill and glow
  });

  it("dark mode: only the accents become pink, no backgrounds change", () => {
    const dark = rules(css).filter((r) => /\[data-ds-dark-theme\](?!\))/.test(r.selector));
    expect(dark.length).toBe(1);
    const body = dark[0]!.body;
    expect(body).toContain("--dsw-static-deepseek-400: #DC9FB4");
    expect(body).toContain("--dsw-static-deepseek-450: #DC9FB4");
    expect(body).not.toMatch(/--dsw-alias-bg-(?!document-selection)|background|--dsw-alias-label-/);
  });
});

describe("FOGCITY_CSS motion and cost", () => {
  it("has no running animation of its own", () => {
    expect(css).not.toMatch(/infinite/i);
    expect(css).not.toMatch(/@keyframes/i);
    for (const [name, value] of declarations(css)) {
      if (name.startsWith("animation")) expect(value.replace(/\s*!important/, "")).toBe("none");
    }
  });

  it("keeps every transition at 200 ms or less", () => {
    const durations: number[] = [];
    for (const [name, value] of declarations(css)) {
      if (!/transition|--ds-transition-duration/.test(name)) continue;
      for (const m of value.matchAll(/(\d*\.?\d+)(ms|s)\b/g)) durations.push(m[2] === "s" ? Number(m[1]) * 1000 : Number(m[1]));
    }
    expect(durations.length).toBeGreaterThan(0);
    for (const d of durations) expect(d).toBeLessThanOrEqual(200);
  });

  it("turns all motion off for prefers-reduced-motion", () => {
    const m = /@media\s*\(prefers-reduced-motion:\s*reduce\)\s*\{([\s\S]*?\})\s*\}/.exec(css);
    expect(m).not.toBeNull();
    expect(m![1]).toMatch(/animation:\s*none\s*!important/);
    expect(m![1]).toMatch(/transition:\s*none\s*!important/);
  });

  it("uses backdrop-filter in at most two places (input bar and pop-ups)", () => {
    const count = (css.match(/backdrop-filter/g) ?? []).length;
    expect(count).toBeGreaterThan(0);
    expect(count).toBeLessThanOrEqual(2);
    const selectors = rules(css).filter((r) => r.body.includes("backdrop-filter")).map((r) => r.selector);
    expect(selectors.join(" ")).toContain("[data-composer-card");
    expect(selectors.join(" ")).toContain('[role="dialog"]');
  });

  it("draws the panel edge with a white-pink-aqua gradient through a mask", () => {
    const edge = rules(css).find((r) => /mask/.test(r.body) && /linear-gradient/.test(r.body));
    expect(edge).toBeDefined();
    const grad = /linear-gradient\(([^;]*)\)/.exec(edge!.body)![1]!.toUpperCase();
    expect(grad.indexOf("#FFFFFF")).toBeLessThan(grad.indexOf("#DC9FB4"));
    expect(grad.indexOf("#DC9FB4")).toBeLessThan(grad.indexOf(AQUA));
  });
});

describe("FOGCITY_CSS shapes and fonts", () => {
  it("rounds panels 22px, pop-ups 30px and buttons/inputs as pills", () => {
    const shape = rules(css).find((r) => r.body.includes("--dsw-radius-lg"))!.body;
    expect(shape).toContain("--dsw-radius-lg: 22px");
    expect(shape).toContain("--dsw-radius-panel: 30px");
    expect(block(css, "html body button")).toMatch(/border-radius:\s*999px/);
    expect(block(css, "html body input")).toMatch(/border-radius:\s*999px/);
    expect(css).not.toMatch(/border-radius:\s*0(px)?\s*[;}]/);
    expect(css).not.toMatch(/--dsw-radius-[\w-]+:\s*0(px)?\s*[;}]/);
  });

  it("embeds Orbitron and Chakra Petch as data URIs and loads nothing from outside", () => {
    const faces = [...FOGCITY_CSS.matchAll(/@font-face\s*\{([^}]*)\}/g)].map((m) => m[1]!);
    expect(faces.length).toBe(4);
    expect(faces.filter((f) => f.includes('"Orbitron"')).length).toBe(2);
    expect(faces.filter((f) => f.includes('"Chakra Petch"')).length).toBe(2);
    for (const f of faces) {
      expect(f).toMatch(/src:\s*url\(data:font\/woff2;base64,[A-Za-z0-9+/=]{1000,}\)\s*format\("woff2"\)/);
      expect(f).toContain("unicode-range: U+0000-00FF");
    }
    for (const m of FOGCITY_CSS.matchAll(/url\(([^)]*)\)/g)) expect(m[1]!.startsWith("data:")).toBe(true);
    expect(FOGCITY_CSS).not.toMatch(/@import|https?:\/\//);
  });

  it("titles in Orbitron, numbers and small labels in Chakra Petch with tabular figures, Chinese in the system font", () => {
    expect(block(css, "html body h1")).toMatch(/font-family:\s*"Orbitron",\s*var\(--dsh-font-family-text-default\)/);
    const labels = block(css, "html body button");
    expect(labels).toMatch(/font-family:\s*"Chakra Petch",\s*var\(--dsh-font-family-text-default\)/);
    expect(labels).toContain("font-variant-numeric: tabular-nums");
    // Body text (chat, Chinese) keeps dsh's own system font stack.
    expect(css).not.toMatch(/--dsw-font-family:/);
  });

  it("is well-formed", () => {
    expect(css).not.toContain("<");
    expect(css.split("{").length).toBe(css.split("}").length);
  });
});
