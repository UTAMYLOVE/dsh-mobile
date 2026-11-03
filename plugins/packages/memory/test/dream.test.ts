import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DreamParseError, dream } from "../src/dream.js";
import { MemoryStore } from "../src/store.js";

const NOW = new Date(2026, 10, 5, 10, 0, 0);

let base: string;
let root: string;
let statePath: string;
let store: MemoryStore;

beforeEach(() => {
  base = mkdtempSync(join(tmpdir(), "dshm-dream-"));
  root = join(base, "ws");
  mkdirSync(join(root, "memory"), { recursive: true });
  statePath = join(root, "memory", ".dream-state.json");
  store = new MemoryStore(root, () => NOW);
});

afterEach(() => {
  rmSync(base, { recursive: true, force: true });
});

const note = (date: string, text: string) => writeFileSync(join(root, "memory", `${date}.md`), text);
const state = () => (existsSync(statePath) ? JSON.parse(readFileSync(statePath, "utf8")) : undefined);

describe("dream", () => {
  it("dream promotes into MEMORY.md and advances state", async () => {
    note("2026-11-02", "- 09:00 old, already processed");
    note("2026-11-03", "- 09:00 decided to use pnpm");
    note("2026-11-04", "- 10:00 meeting with Ann on Friday");
    note("2026-11-05", "- 10:00 today, must be excluded");
    writeFileSync(statePath, JSON.stringify({ lastProcessedDate: "2026-11-02" }));
    const llm = vi.fn(async () =>
      "```json\n" +
        JSON.stringify({
          promote: ["Project uses pnpm"],
          remind: [{ title: "Ann meeting", body: "Friday" }],
        }) +
        "\n```",
    );
    const result = await dream(store, llm, statePath);
    expect(result).toEqual({
      promoted: ["Project uses pnpm"],
      reminders: [{ title: "Ann meeting", body: "Friday" }],
    });
    expect(llm).toHaveBeenCalledTimes(1);
    const prompt = (llm.mock.calls[0] as unknown as [string])[0];
    expect(prompt).toContain("decided to use pnpm");
    expect(prompt).toContain("meeting with Ann");
    expect(prompt).not.toContain("already processed");
    expect(prompt).not.toContain("must be excluded");
    expect(readFileSync(join(root, "MEMORY.md"), "utf8")).toBe("\n### 2026-11-04 整理\n- Project uses pnpm\n");
    expect(state()).toEqual({ lastProcessedDate: "2026-11-04" });
  });

  it("dream with no state processes every note before today", async () => {
    note("2026-11-01", "- a");
    const llm = vi.fn(async () => '{"promote":[],"remind":[]}');
    const result = await dream(store, llm, statePath);
    expect(result).toEqual({ promoted: [], reminders: [] });
    expect(existsSync(join(root, "MEMORY.md"))).toBe(false);
    expect(state()).toEqual({ lastProcessedDate: "2026-11-01" });
  });

  it("dream on non-JSON writes nothing and keeps state", async () => {
    note("2026-11-04", "- something");
    writeFileSync(statePath, JSON.stringify({ lastProcessedDate: "2026-11-03" }));
    await expect(dream(store, async () => "Sure! Here are the highlights...", statePath)).rejects.toThrow(
      DreamParseError,
    );
    expect(existsSync(join(root, "MEMORY.md"))).toBe(false);
    expect(state()).toEqual({ lastProcessedDate: "2026-11-03" });
  });

  it.each([
    ["wrong promote type", '{"promote":"x","remind":[]}'],
    ["missing remind", '{"promote":["a"]}'],
    ["reminder without body", '{"promote":[],"remind":[{"title":"t"}]}'],
    ["array root", "[]"],
    ["non-string promote entry", '{"promote":[1],"remind":[]}'],
  ])("dream rejects structurally invalid JSON (%s)", async (_label, reply) => {
    note("2026-11-04", "- something");
    await expect(dream(store, async () => reply, statePath)).rejects.toThrow(DreamParseError);
    expect(existsSync(join(root, "MEMORY.md"))).toBe(false);
    expect(existsSync(statePath)).toBe(false);
  });

  it("dream with no new notes does not call llm", async () => {
    note("2026-11-05", "- only today");
    writeFileSync(statePath, JSON.stringify({ lastProcessedDate: "2026-11-04" }));
    const llm = vi.fn(async () => "never");
    expect(await dream(store, llm, statePath)).toEqual({ promoted: [], reminders: [] });
    expect(llm).not.toHaveBeenCalled();
    expect(state()).toEqual({ lastProcessedDate: "2026-11-04" });
  });

  it("an unreadable state file is treated as no state", async () => {
    note("2026-11-04", "- x");
    writeFileSync(statePath, "{not json");
    const llm = vi.fn(async () => '{"promote":[],"remind":[]}');
    await dream(store, llm, statePath);
    expect(llm).toHaveBeenCalledTimes(1);
  });

  it("an oversized backlog is processed in chunks, advancing state only as far as it read", async () => {
    const big = "x".repeat(60_000);
    note("2026-11-01", big);
    note("2026-11-02", big);
    note("2026-11-03", "small");
    const llm = vi.fn(async () => '{"promote":[],"remind":[]}');
    await dream(store, llm, statePath);
    expect(state()).toEqual({ lastProcessedDate: "2026-11-01" });
    await dream(store, llm, statePath);
    expect(state()).toEqual({ lastProcessedDate: "2026-11-03" });
  });
});
