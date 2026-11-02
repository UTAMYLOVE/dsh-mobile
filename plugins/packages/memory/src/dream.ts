import { readFileSync, renameSync, writeFileSync } from "node:fs";
import type { MemoryStore } from "./store.js";

export interface Reminder {
  title: string;
  body: string;
}
export interface DreamResult {
  promoted: string[];
  reminders: Reminder[];
}
export type Llm = (prompt: string) => Promise<string>;

/** The model reply was not the JSON shape dream asked for. */
export class DreamParseError extends Error {
  override name = "DreamParseError";
}

/** Upper bound on daily-note text sent to the model in one pass. */
const MAX_PROMPT_CHARS = 80_000;
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;

export const DREAM_INSTRUCTIONS = [
  "You maintain a personal assistant's long-term memory.",
  "Below are daily notes written since the last review. Decide:",
  '- "promote": durable facts, decisions and user preferences worth keeping in long-term memory, each one short, self-contained line.',
  '- "remind": things the user should be reminded about, each as {"title","body"}.',
  "Skip chit-chat and anything transient. Reply with ONLY a JSON object, no prose:",
  '{"promote": ["..."], "remind": [{"title": "...", "body": "..."}]}',
].join("\n");

function readState(statePath: string): string | undefined {
  try {
    const value = (JSON.parse(readFileSync(statePath, "utf8")) as { lastProcessedDate?: unknown })
      .lastProcessedDate;
    return typeof value === "string" && DATE_RE.test(value) ? value : undefined;
  } catch {
    return undefined;
  }
}

function writeState(statePath: string, lastProcessedDate: string): void {
  const tmp = `${statePath}.tmp`;
  writeFileSync(tmp, `${JSON.stringify({ lastProcessedDate })}\n`);
  renameSync(tmp, statePath);
}

function parseReply(raw: string): DreamResult {
  let text = raw.trim();
  const fence = /^```(?:json)?\s*\n([\s\S]*?)\n?```\s*$/i.exec(text);
  if (fence) text = fence[1]!.trim();
  let data: unknown;
  try {
    data = JSON.parse(text);
  } catch {
    throw new DreamParseError("model reply is not JSON");
  }
  if (typeof data !== "object" || data === null || Array.isArray(data)) {
    throw new DreamParseError("model reply is not a JSON object");
  }
  const { promote, remind } = data as { promote?: unknown; remind?: unknown };
  if (!Array.isArray(promote) || !promote.every((p) => typeof p === "string")) {
    throw new DreamParseError('"promote" must be an array of strings');
  }
  if (
    !Array.isArray(remind) ||
    !remind.every(
      (r) =>
        typeof r === "object" &&
        r !== null &&
        typeof (r as Reminder).title === "string" &&
        typeof (r as Reminder).body === "string",
    )
  ) {
    throw new DreamParseError('"remind" must be an array of {title, body}');
  }
  return {
    promoted: (promote as string[]).map((p) => p.trim().replace(/\s*\r?\n\s*/g, " ")).filter(Boolean),
    reminders: (remind as Reminder[]).map((r) => ({ title: r.title, body: r.body })),
  };
}

/**
 * Reviews daily notes written after `statePath`'s lastProcessedDate and before
 * today: asks the model what to promote to MEMORY.md and what to remind about.
 * Nothing is written and the state does not move unless the reply parses.
 */
export async function dream(store: MemoryStore, llm: Llm, statePath: string): Promise<DreamResult> {
  const last = readState(statePath);
  const today = store.today();
  const pending = store.dailyDates().filter((d) => (last === undefined || d > last) && d < today);

  const included: { date: string; text: string }[] = [];
  let size = 0;
  for (const date of pending) {
    const text = store.readDaily(date) ?? "";
    if (included.length > 0 && size + text.length > MAX_PROMPT_CHARS) break;
    included.push({ date, text });
    size += text.length;
  }
  if (included.length === 0) return { promoted: [], reminders: [] };

  const notes = included.map((n) => `### ${n.date}\n${n.text.trim()}`).join("\n\n");
  const result = parseReply(await llm(`${DREAM_INSTRUCTIONS}\n\n${notes}`));

  const lastDate = included[included.length - 1]!.date;
  if (result.promoted.length > 0) {
    await store.append("long", `\n### ${lastDate} 整理\n${result.promoted.map((p) => `- ${p}`).join("\n")}`);
  }
  writeState(statePath, lastDate);
  return result;
}
