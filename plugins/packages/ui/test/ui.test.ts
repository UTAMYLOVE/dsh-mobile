import { describe, expect, it, vi } from "vitest";
import * as plugin from "../src/index.js";
import { SAKURA_CSS } from "../src/sakura.js";

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

/** The declarations of the rule whose selector list contains `selector` (first match). */
function block(css: string, selector: string): string {
  const re = /([^{}]+)\{([^{}]*)\}/g;
  for (const m of css.matchAll(re)) {
    if (m[1]!.includes(selector)) return m[2]!;
  }
  throw new Error(`no rule for ${selector}`);
}

const DARK = "[data-ds-dark-theme]";

describe("@dshm/ui plugin", () => {
  it("is named dshm-ui", () => {
    expect(plugin.name).toBe("dshm-ui");
  });

  it("pushes exactly one style row on webserver/index-inject", () => {
    const { ctx, emit } = makeCtx();
    plugin.apply(ctx);
    const table = emit("webserver/index-inject");
    expect(table).toEqual([{ kind: "style", text: SAKURA_CSS }]);
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
    expect(table).toHaveLength(2);
    expect(table[0]).toEqual({ kind: "script", text: "x" });
    expect(table[1]!.kind).toBe("style");
  });

  it("logs that it loaded, without CSS", () => {
    const { ctx, logger } = makeCtx();
    plugin.apply(ctx);
    expect(logger.info).toHaveBeenCalledTimes(1);
    expect(String(logger.info.mock.calls[0]![0])).toContain("[dshm-ui]");
  });

  it("needs no service, so headless compositions load it", () => {
    expect((plugin.inject ?? []) as string[]).toEqual([]);
  });
});

describe("SAKURA_CSS", () => {
  it("contains the sakura colours", () => {
    expect(SAKURA_CSS).toContain("#fff5f8");
    expect(SAKURA_CSS).toContain("#d6336c");
    expect(SAKURA_CSS).toContain("#c2255c");
    expect(SAKURA_CSS).toContain("#ffd6e3");
    expect(SAKURA_CSS).toContain("#fff0f5");
  });

  it("never touches the text colours", () => {
    expect(SAKURA_CSS).not.toContain("--dsw-alias-label-primary");
    expect(SAKURA_CSS).not.toContain("--dsw-alias-brand-primary");
    expect(SAKURA_CSS).not.toContain("--dsw-alias-brand-text");
  });

  it("replaces the whole deepseek colour scale in light mode", () => {
    const light = block(SAKURA_CSS, ":root");
    for (const step of [50, 100, 200, 300, 400, 450, 500, 600, 700, 800, 900]) {
      expect(light).toMatch(new RegExp(`--dsw-static-deepseek-${step}:\\s*#[0-9a-f]{6}`));
    }
    expect(light).toContain("--dsw-static-deepseek-50: #fff0f5");
    expect(light).toContain("--dsw-static-deepseek-100: #ffd6e3");
    expect(light).toContain("--dsw-static-deepseek-500: #d6336c");
    expect(light).toContain("--dsw-static-deepseek-600: #c2255c");
  });

  it("paints the light backgrounds #fff5f8", () => {
    const light = block(SAKURA_CSS, ":root");
    for (const token of [
      "--dsw-alias-bg-base",
      "--dsw-alias-bg-layer-1",
      "--dsw-alias-bg-layer-2",
      "--dsw-alias-bg-layer-3",
      "--dsw-alias-bg-module-platform",
    ]) {
      expect(light).toContain(`${token}: #fff5f8`);
    }
    expect(light).toContain("--dsw-focus-ring-color: #d6336c");
    expect(light).toMatch(/--dsw-alias-bg-document-selection:\s*rgba\(214,\s*51,\s*108,\s*0\.4\)/);
  });

  it("sets the body background for the light theme", () => {
    expect(block(SAKURA_CSS, "body:not(" )).toContain("background-color: #fff5f8");
  });

  it("does not paint the dark theme's body light", () => {
    // The plain body rule must exclude the dark theme.
    for (const m of SAKURA_CSS.matchAll(/([^{}]+)\{([^{}]*background-color[^{}]*)\}/g)) {
      expect(m[1]).toContain(`:not(${DARK})`);
    }
  });

  it("dark block: only accents change, no backgrounds", () => {
    const dark = block(SAKURA_CSS, `body${DARK}`);
    expect(dark).toContain("--dsw-static-deepseek-400: #f06595");
    expect(dark).toMatch(/--dsw-static-deepseek-450:\s*#[0-9a-f]{6}/);
    expect(dark).not.toContain("--dsw-alias-bg-");
    expect(dark).not.toContain("background");
    expect(dark).not.toContain("#fff5f8");
  });

  it("dark block keeps the dark tertiary shades (they are backgrounds there)", () => {
    const dark = block(SAKURA_CSS, `body${DARK}`);
    expect(dark).toContain("--dsw-static-deepseek-800: #34415b");
    expect(dark).toContain("--dsw-static-deepseek-900: #283142");
  });

  it("the light rules do not apply to the dark theme", () => {
    // Every selector in the light rule is a plain root/body one, never the dark attribute.
    const m = /([^{}]+)\{[^{}]*--dsw-alias-bg-base[^{}]*\}/.exec(SAKURA_CSS);
    expect(m).not.toBeNull();
    expect(m![1]).not.toContain(DARK);
  });

  it("is a small, well-formed stylesheet", () => {
    expect(SAKURA_CSS).not.toContain("<");
    expect(SAKURA_CSS).not.toContain("@import");
    expect(SAKURA_CSS).not.toMatch(/url\(/);
    expect(SAKURA_CSS.split("{").length).toBe(SAKURA_CSS.split("}").length);
    expect(SAKURA_CSS.length).toBeLessThan(4000);
  });
});
