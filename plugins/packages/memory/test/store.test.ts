import { mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { MemoryPathError, MemoryStore } from "../src/store.js";

// Local-time instant: 2026-11-02 14:05 (the store formats dates in local time).
const NOW = new Date(2026, 10, 2, 14, 5, 0);

let root: string;
let outside: string;
let store: MemoryStore;

beforeEach(() => {
  const base = mkdtempSync(join(tmpdir(), "dshm-store-"));
  root = join(base, "workspace");
  outside = join(base, "outside");
  mkdirSync(root);
  mkdirSync(outside);
  store = new MemoryStore(root, () => NOW);
});

afterEach(() => {
  rmSync(join(root, ".."), { recursive: true, force: true });
});

function write(rel: string, text: string): void {
  mkdirSync(join(root, rel, ".."), { recursive: true });
  writeFileSync(join(root, rel), text);
}

describe("loadStartupContext", () => {
  it("startup context has MEMORY, USER, today and yesterday only", () => {
    write("MEMORY.md", "long term fact");
    write("USER.md", "user likes tea");
    write("memory/2026-11-02.md", "- 09:00 today note");
    write("memory/2026-11-01.md", "- 08:00 yesterday note");
    write("memory/2026-10-31.md", "- 07:00 day before yesterday");
    const ctx = store.loadStartupContext();
    expect(ctx).toContain("## MEMORY.md\nlong term fact");
    expect(ctx).toContain("## USER.md\nuser likes tea");
    expect(ctx).toContain("## memory/2026-11-02.md\n- 09:00 today note");
    expect(ctx).toContain("## memory/2026-11-01.md\n- 08:00 yesterday note");
    expect(ctx).not.toContain("day before yesterday");
    expect(ctx).not.toContain("2026-10-31");
  });

  it("skips missing files and returns an empty string when all are missing", () => {
    expect(store.loadStartupContext()).toBe("");
    write("USER.md", "only user");
    expect(store.loadStartupContext()).toBe("## USER.md\nonly user");
  });

  it("yesterday crosses a month boundary", () => {
    const s = new MemoryStore(root, () => new Date(2026, 11, 1, 0, 30));
    write("memory/2026-11-30.md", "end of november");
    expect(s.loadStartupContext()).toContain("## memory/2026-11-30.md");
  });

  it("does not leak a MEMORY.md symlinked outside the workspace", () => {
    writeFileSync(join(outside, "secret.md"), "TOPSECRET");
    symlinkSync(join(outside, "secret.md"), join(root, "MEMORY.md"));
    expect(store.loadStartupContext()).not.toContain("TOPSECRET");
  });
});

describe("search", () => {
  it("search finds all-term matches, newest first", () => {
    write("memory/2026-11-01.md", "- alpha beta older\n- alpha only");
    write("memory/2026-11-02.md", "- newer BETA and Alpha");
    write("MEMORY.md", "alpha beta in long term");
    write("USER.md", "nothing here");
    const hits = store.search("alpha  beta");
    expect(hits).toEqual([
      { path: "memory/2026-11-02.md", line: 1, text: "- newer BETA and Alpha" },
      { path: "memory/2026-11-01.md", line: 1, text: "- alpha beta older" },
      { path: "MEMORY.md", line: 1, text: "alpha beta in long term" },
    ]);
  });

  it("reports 1-based line numbers, honours the limit, and ignores empty queries", () => {
    write("memory/2026-11-02.md", "x\nneedle one\ny\nneedle two\nneedle three");
    expect(store.search("needle", 2).map((h) => h.line)).toEqual([2, 4]);
    expect(store.search("   ")).toEqual([]);
    expect(store.search("")).toEqual([]);
  });

  it("does not search through symlinks that leave the workspace", () => {
    writeFileSync(join(outside, "x.md"), "needle outside");
    mkdirSync(join(root, "memory"));
    symlinkSync(join(outside, "x.md"), join(root, "memory", "2026-11-02.md"));
    expect(store.search("needle")).toEqual([]);
  });
});

describe("get / resolveInside", () => {
  it("get rejects ../ and absolute paths and outward symlinks", () => {
    writeFileSync(join(outside, "secret.md"), "nope");
    mkdirSync(join(root, "memory"));
    symlinkSync(join(outside, "secret.md"), join(root, "memory", "link-out.md"));
    expect(() => store.get("../secret")).toThrow(MemoryPathError);
    expect(() => store.get("../outside/secret.md")).toThrow(MemoryPathError);
    expect(() => store.get("/etc/passwd")).toThrow(MemoryPathError);
    expect(() => store.get("memory/link-out.md")).toThrow(MemoryPathError);
  });

  it("rejects an intermediate directory symlink that leaves the workspace", () => {
    writeFileSync(join(outside, "deep.md"), "nope");
    symlinkSync(outside, join(root, "memory"));
    expect(() => store.get("memory/deep.md")).toThrow(MemoryPathError);
    expect(() => store.resolveInside("memory/new.md")).toThrow(MemoryPathError);
  });

  it("rejects a dangling symlink and non-.md files", () => {
    symlinkSync(join(outside, "missing.md"), join(root, "dangling.md"));
    write("notes.txt", "x");
    expect(() => store.resolveInside("dangling.md")).toThrow(MemoryPathError);
    expect(() => store.get("notes.txt")).toThrow(MemoryPathError);
    expect(() => store.get("")).toThrow(MemoryPathError);
  });

  it("reads inside files and slices by 1-based line", () => {
    write("memory/2026-11-02.md", "l1\nl2\nl3\nl4");
    expect(store.get("memory/2026-11-02.md")).toBe("l1\nl2\nl3\nl4");
    expect(store.get("memory/2026-11-02.md", 2, 2)).toBe("l2\nl3");
    expect(store.get("./memory/../memory/2026-11-02.md", 4)).toBe("l4");
  });

  it("reports a missing file clearly", () => {
    expect(() => store.get("MEMORY.md")).toThrow(/not found/);
  });
});

describe("append", () => {
  it("daily note gets today's file with a HH:mm prefix per line, creating memory/", async () => {
    await store.append("daily", "first\nsecond");
    expect(readFileSync(join(root, "memory", "2026-11-02.md"), "utf8")).toBe(
      "- 14:05 first\n- 14:05 second\n",
    );
    await store.append("daily", "third");
    expect(readFileSync(join(root, "memory", "2026-11-02.md"), "utf8")).toBe(
      "- 14:05 first\n- 14:05 second\n- 14:05 third\n",
    );
  });

  it("long and user targets append to MEMORY.md and USER.md", async () => {
    write("MEMORY.md", "existing without newline");
    await store.append("long", "- a fact");
    await store.append("user", "- prefers zh-TW");
    expect(readFileSync(join(root, "MEMORY.md"), "utf8")).toBe("existing without newline\n- a fact\n");
    expect(readFileSync(join(root, "USER.md"), "utf8")).toBe("- prefers zh-TW\n");
  });

  it("refuses to write through a symlinked MEMORY.md that points outside", async () => {
    writeFileSync(join(outside, "t.md"), "orig");
    symlinkSync(join(outside, "t.md"), join(root, "MEMORY.md"));
    await expect(store.append("long", "evil")).rejects.toThrow(MemoryPathError);
    expect(readFileSync(join(outside, "t.md"), "utf8")).toBe("orig");
  });

  it("concurrent appends never lose lines", async () => {
    await Promise.all(Array.from({ length: 50 }, (_, i) => store.append("daily", `line-${i}`)));
    const lines = readFileSync(join(root, "memory", "2026-11-02.md"), "utf8").trim().split("\n");
    expect(lines).toHaveLength(50);
    for (let i = 0; i < 50; i++) expect(lines).toContain(`- 14:05 line-${i}`);
  });

  it("separate store instances (two processes' worth) also never lose lines", async () => {
    const other = new MemoryStore(root, () => NOW);
    await Promise.all(
      Array.from({ length: 30 }, (_, i) => (i % 2 ? store : other).append("daily", `x-${i}`)),
    );
    const lines = readFileSync(join(root, "memory", "2026-11-02.md"), "utf8").trim().split("\n");
    expect(lines).toHaveLength(30);
  });
});
