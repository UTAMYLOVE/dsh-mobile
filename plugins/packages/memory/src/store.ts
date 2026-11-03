import {
  appendFileSync,
  closeSync,
  fstatSync,
  lstatSync,
  mkdirSync,
  openSync,
  readFileSync,
  readSync,
  readdirSync,
  realpathSync,
} from "node:fs";
import { dirname, isAbsolute, join, relative, resolve, sep } from "node:path";
import lockfile from "proper-lockfile";

/** A path that is not an in-workspace Markdown file (or does not exist). */
export class MemoryPathError extends Error {
  override name = "MemoryPathError";
}

export type AppendTarget = "daily" | "long" | "user";
export interface SearchHit {
  path: string;
  line: number;
  text: string;
}

const DAILY_RE = /^\d{4}-\d{2}-\d{2}\.md$/;
const LOCK_FILE = join("memory", ".lock");
const LOCK_OPTIONS = {
  realpath: false,
  stale: 15_000,
  retries: { retries: 200, factor: 1.2, minTimeout: 5, maxTimeout: 100 },
  // A lock that was lost (stalled event loop, clock jump) must never crash dsh.
  onCompromised: () => {},
} as const;

/** Upper bound (UTF-8 bytes) of the context injected into every conversation: it rides along in each request's system prompt. */
export const STARTUP_CONTEXT_MAX_BYTES = 32 * 1024;
/** The last line of a context cut at [STARTUP_CONTEXT_MAX_BYTES]. */
export const STARTUP_CONTEXT_TRUNCATED =
  "[dshm: memory context truncated at 32 KiB; use memory_search / memory_get for the rest]";

/** The longest prefix of `text` that fits in `maxBytes` UTF-8 bytes, never splitting a character. */
function truncateUtf8(text: string, maxBytes: number): string {
  let used = 0;
  let out = "";
  for (const ch of text) {
    const n = Buffer.byteLength(ch, "utf8");
    if (used + n > maxBytes) break;
    used += n;
    out += ch;
  }
  return out;
}

const pad = (n: number): string => String(n).padStart(2, "0");
/** Local-time (TZ) calendar date, YYYY-MM-DD. */
export const formatDate = (d: Date): string => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const formatTime = (d: Date): string => `${pad(d.getHours())}:${pad(d.getMinutes())}`;

function fileEndsWithNewline(path: string): boolean {
  let fd: number | undefined;
  try {
    fd = openSync(path, "r");
    const { size } = fstatSync(fd);
    if (size === 0) return true;
    const buf = Buffer.alloc(1);
    readSync(fd, buf, 0, 1, size - 1);
    return buf[0] === 0x0a;
  } catch {
    return true; // missing file: nothing to separate from
  } finally {
    if (fd !== undefined) closeSync(fd);
  }
}

/**
 * OpenClaw-style Markdown memory under one workspace root: `MEMORY.md`
 * (long term), `USER.md` and `memory/YYYY-MM-DD.md` (daily notes).
 * Every path it touches is confined to the root, symlinks included.
 */
export class MemoryStore {
  private tail: Promise<unknown> = Promise.resolve();

  constructor(
    readonly root: string,
    private readonly now: () => Date = () => new Date(),
  ) {}

  /** Today's date in local time, YYYY-MM-DD. */
  today(): string {
    return formatDate(this.now());
  }

  /**
   * Context injected at conversation start; "" when no file exists. In priority order (MEMORY.md, USER.md, then the daily
   * notes newest first) and at most [STARTUP_CONTEXT_MAX_BYTES]: what does not fit is cut off and a last line says so.
   */
  loadStartupContext(): string {
    const now = this.now();
    const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1);
    const rels = [
      "MEMORY.md",
      "USER.md",
      `memory/${formatDate(now)}.md`,
      `memory/${formatDate(yesterday)}.md`,
    ];
    const sections: string[] = [];
    for (const rel of rels) {
      const text = this.tryRead(rel);
      if (text !== undefined) sections.push(`## ${rel}\n${text.trimEnd()}`);
    }
    const full = sections.join("\n\n");
    if (Buffer.byteLength(full, "utf8") <= STARTUP_CONTEXT_MAX_BYTES) return full;
    const note = `\n\n${STARTUP_CONTEXT_TRUNCATED}`;
    return truncateUtf8(full, STARTUP_CONTEXT_MAX_BYTES - Buffer.byteLength(note, "utf8")).trimEnd() + note;
  }

  /** Case-insensitive all-terms line search, daily notes newest first. */
  search(query: string, limit = 10): SearchHit[] {
    const terms = query.toLowerCase().split(/\s+/).filter(Boolean);
    if (terms.length === 0 || limit <= 0) return [];
    const hits: SearchHit[] = [];
    for (const rel of this.searchOrder()) {
      const text = this.tryRead(rel);
      if (text === undefined) continue;
      const lines = text.split(/\r?\n/);
      for (let i = 0; i < lines.length; i++) {
        const lower = lines[i]!.toLowerCase();
        if (terms.every((t) => lower.includes(t))) {
          hits.push({ path: rel, line: i + 1, text: lines[i]! });
          if (hits.length >= limit) return hits;
        }
      }
    }
    return hits;
  }

  /** Reads a Markdown file (optionally `lines` lines starting at 1-based `from`). */
  get(rel: string, from?: number, lines?: number): string {
    const abs = this.resolveInside(rel);
    let text: string;
    try {
      text = readFileSync(abs, "utf8");
    } catch (e) {
      if ((e as NodeJS.ErrnoException).code === "ENOENT") throw new MemoryPathError(`file not found: ${rel}`);
      throw new MemoryPathError(`cannot read: ${rel}`);
    }
    if (from === undefined && lines === undefined) return text;
    const start = Math.max(1, Math.floor(from ?? 1));
    const all = text.split(/\r?\n/);
    const end = lines === undefined ? all.length : start - 1 + Math.max(0, Math.floor(lines));
    return all.slice(start - 1, end).join("\n");
  }

  /**
   * Appends under the memory lock. `daily` writes today's note with a
   * `- HH:mm ` prefix on each line; `long`/`user` append the text verbatim.
   */
  append(target: AppendTarget, text: string): Promise<void> {
    return this.enqueue(() => {
      const now = this.now();
      let rel: string;
      let body: string;
      if (target === "daily") {
        rel = `memory/${formatDate(now)}.md`;
        const prefix = `- ${formatTime(now)} `;
        const lines = text.split(/\r?\n/).filter((l) => l.trim() !== "");
        body = lines.map((l) => `${prefix}${l.trimEnd()}`).join("\n");
      } else {
        rel = target === "long" ? "MEMORY.md" : "USER.md";
        body = text.replace(/\s+$/, "");
      }
      if (body === "") return;
      const abs = this.resolveInside(rel);
      mkdirSync(dirname(abs), { recursive: true });
      const sep0 = fileEndsWithNewline(abs) ? "" : "\n";
      appendFileSync(abs, `${sep0}${body}\n`);
    });
  }

  /** Daily-note dates (YYYY-MM-DD) found on disk, oldest first. */
  dailyDates(): string[] {
    return this.listMemoryDir()
      .filter((n) => DAILY_RE.test(n))
      .map((n) => n.slice(0, -3))
      .sort();
  }

  /** Content of a daily note, or undefined when absent or unsafe. */
  readDaily(date: string): string | undefined {
    return this.tryRead(`memory/${date}.md`);
  }

  /**
   * Resolves a workspace-relative `.md` path to an absolute one, refusing
   * `..` escapes, absolute paths and any symlink (including intermediate
   * directories and dangling links) that leaves the workspace.
   */
  resolveInside(rel: string): string {
    if (typeof rel !== "string" || rel === "" || rel.includes("\0")) throw new MemoryPathError("invalid path");
    if (isAbsolute(rel)) throw new MemoryPathError(`absolute paths are not allowed: ${rel}`);
    const abs = resolve(this.root, rel);
    const lexical = relative(resolve(this.root), abs);
    if (lexical === "" || lexical === ".." || lexical.startsWith(`..${sep}`) || isAbsolute(lexical)) {
      throw new MemoryPathError(`path escapes the workspace: ${rel}`);
    }
    if (!abs.toLowerCase().endsWith(".md")) throw new MemoryPathError(`only .md files are allowed: ${rel}`);
    this.assertRealInside(abs, rel);
    return abs;
  }

  private assertRealInside(abs: string, rel: string): void {
    let realRoot: string;
    try {
      realRoot = realpathSync(this.root);
    } catch {
      realRoot = resolve(this.root); // root not created yet, so nothing inside can be a link
    }
    let p = abs;
    for (;;) {
      try {
        const real = realpathSync(p);
        if (real !== realRoot && !real.startsWith(realRoot + sep)) {
          throw new MemoryPathError(`path escapes the workspace: ${rel}`);
        }
        return;
      } catch (e) {
        if (e instanceof MemoryPathError) throw e;
        const code = (e as NodeJS.ErrnoException).code;
        if (code !== "ENOENT" && code !== "ENOTDIR") throw new MemoryPathError(`unresolvable path: ${rel}`);
        try {
          lstatSync(p);
          throw new MemoryPathError(`dangling symlink: ${rel}`);
        } catch (e2) {
          if (e2 instanceof MemoryPathError) throw e2;
        }
        const parent = dirname(p);
        if (parent === p) return;
        p = parent;
      }
    }
  }

  private tryRead(rel: string): string | undefined {
    try {
      return readFileSync(this.resolveInside(rel), "utf8");
    } catch {
      return undefined;
    }
  }

  private listMemoryDir(): string[] {
    try {
      this.assertRealInside(join(this.root, "memory"), "memory");
      return readdirSync(join(this.root, "memory")).filter((n) => !n.startsWith(".") && n.endsWith(".md"));
    } catch {
      return [];
    }
  }

  private searchOrder(): string[] {
    const names = this.listMemoryDir();
    const daily = names.filter((n) => DAILY_RE.test(n)).sort().reverse();
    const other = names.filter((n) => !DAILY_RE.test(n)).sort();
    return [...daily, ...other].map((n) => `memory/${n}`).concat(["MEMORY.md", "USER.md"]);
  }

  /** Serialises writes in this process, then takes the cross-process file lock. */
  private enqueue(task: () => void): Promise<void> {
    const run = this.tail.then(async () => {
      this.assertRealInside(join(this.root, "memory"), "memory");
      mkdirSync(join(this.root, "memory"), { recursive: true });
      const release = await lockfile.lock(join(this.root, LOCK_FILE), LOCK_OPTIONS);
      try {
        task();
      } finally {
        await release().catch(() => {});
      }
    });
    this.tail = run.catch(() => {});
    return run;
  }
}
