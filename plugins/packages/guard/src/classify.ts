import { posix } from "node:path";

/**
 * What the owner chose in the app (設定 → 權限): `ask` asks before anything that changes something, `smart` lets ordinary work
 * in the workspace run and asks before the risky kinds below, `full` never asks. A short list is refused at every level.
 */
export type Level = "ask" | "smart" | "full";

export interface GuardEnv {
  level: Level;
  /** The workspace (absolute). Relative paths are read against it. */
  workspace: string;
  /** Temporary folders the agent may write freely (`$TMPDIR`, `/tmp`). */
  temp: string[];
  /** The app's own folders outside the workspace (dsh's home, the runtime): never deleted, changed only after asking. */
  protectedRoots: string[];
  home?: string;
  /**
   * Other names of the same folders, `[alias, name]`: since 0.7.0 either mode runs the other mode's conversations, whose folder
   * is spelled the other way (native maps /root/workspace and /root/.dsh onto the phone's folders, proot binds the workspace at
   * its host path too). A path under an alias is read as the same path under the name the guard knows, so writing in the
   * workspace under its other name is not "outside", and deleting dsh's home under its other name is still refused.
   */
  aliases?: [string, string][];
}

/** Why a call is held back; the app shows [text] to the owner, and the model reads it when the call is refused. */
export type Verdict =
  | { kind: "allow" }
  | { kind: "ask"; why: string; text: string }
  | { kind: "deny"; why: string; text: string };

const ALLOW: Verdict = { kind: "allow" };

/** Tools that change nothing by themselves at any level (reading, searching, planning, asking, the app's own tools). */
const MUTATING_TOOLS = new Set(["bash", "write", "edit"]);
/** Tools that start work the owner did not look at: asked about at the `ask` level only (a teammate is a subagent too). */
const DELEGATING_TOOLS = new Set(["subagent", "subagent_fork", "spawn_teammate", "workflow", "schedule_create", "schedule_update", "schedule_delete", "job_kill"]);

/** Programs that only read (with the flags checked in [readOnlySegment]). */
const READ_ONLY = new Set([
  "ls", "cat", "head", "tail", "wc", "grep", "egrep", "fgrep", "rg", "stat", "file", "du", "df", "pwd", "echo", "printf", "date",
  "which", "type", "printenv", "id", "whoami", "uname", "basename", "dirname", "realpath", "readlink", "sort", "uniq", "cut", "tr",
  "diff", "cmp", "md5sum", "sha1sum", "sha256sum", "true", "false", "test", "[", "sleep", "seq", "jq", "tree", "nproc", "free", "ps",
]);
const GIT_READ_ONLY = new Set(["status", "log", "diff", "show", "branch", "rev-parse", "ls-files", "blame", "describe", "remote", "config", "shortlog", "grep", "cat-file", "reflog", "version", "--version"]);
const SHELLS = new Set(["sh", "bash", "dash", "zsh", "ash", "ksh"]);
const INTERPRETERS = new Set([...SHELLS, "node", "python", "python3", "perl", "ruby", "php"]);
const SYSTEM = new Set(["su", "sudo", "doas", "mount", "umount", "reboot", "shutdown", "setprop", "pm", "am", "cmd", "settings", "svc", "input", "dd", "shred", "insmod", "rmmod", "iptables", "pkill", "killall", "mkfs", "fdisk"]);
const NETWORK_SENDERS = new Set(["scp", "sftp", "ftp", "nc", "ncat", "netcat", "telnet", "ssh"]);
const WRAPPERS = new Set(["nohup", "time", "command", "exec", "builtin", "nice", "busybox", "toybox", "stdbuf", "timeout"]);
/** Places every program may name without it meaning anything about the owner's files. */
const HARMLESS = [/^\/dev\/(null|zero|stdin|stdout|stderr|tty|urandom|random|fd\/\d+)$/, /^\/proc\//, /^\/system\//, /^\/(usr\/)?bin\//, /^\/apex\//, /^\/etc\//];

interface Segment { words: string[]; redirects: string[] }

/**
 * Splits a command line into its simple commands (`;`, `&&`, `||`, `|`, `&`, newlines, and the insides of `$( )` and
 * backticks), each as words with quotes removed and the targets of its output redirections. Here-document bodies are text,
 * not commands. No variable is expanded: a word that still carries `$` is treated as unknown by the callers.
 */
export function parse(command: string): { segments: Segment[]; pipes: number[] } {
  const segments: Segment[] = [];
  const pipes: number[] = []; // indexes of segments whose input is a pipe
  const lines = dropHereDocs(command);
  let words: string[] = [], redirects: string[] = [], word = "", has = false, pendingRedirect = false, piped = false;
  const endWord = () => {
    if (!has) return;
    if (pendingRedirect) { redirects.push(word); pendingRedirect = false; } else words.push(word);
    word = ""; has = false;
  };
  const endSegment = (nextPiped: boolean) => {
    endWord();
    if (words.length > 0 || redirects.length > 0) { if (piped) pipes.push(segments.length); segments.push({ words, redirects }); }
    words = []; redirects = []; pendingRedirect = false; piped = nextPiped;
  };
  const s = lines;
  for (let i = 0; i < s.length; i++) {
    const c = s[i]!;
    if (c === "\\" && i + 1 < s.length) { word += s[++i]; has = true; continue; }
    if (c === "'") { const j = s.indexOf("'", i + 1); const end = j < 0 ? s.length : j; word += s.slice(i + 1, end); has = true; i = end; continue; }
    if (c === '"') {
      let j = i + 1;
      for (; j < s.length && s[j] !== '"'; j++) { if (s[j] === "\\" && j + 1 < s.length) { word += s[++j]; } else word += s[j]; }
      has = true; i = j; continue;
    }
    if (c === "$" && s[i + 1] === "(" || c === "`") {
      // A command substitution: its inside is commands too. The outer word keeps a `$` so nobody trusts it as a literal.
      const open = c === "`" ? i : i + 1;
      const close = c === "`" ? s.indexOf("`", i + 1) : matching(s, open);
      const end = close < 0 ? s.length : close;
      const inner = parse(s.slice(open + 1, end));
      for (const [k, seg] of inner.segments.entries()) { if (inner.pipes.includes(k)) pipes.push(segments.length); segments.push(seg); }
      word += "$"; has = true; i = end; continue;
    }
    if (c === "<" && s[i + 1] === "(" || c === ">" && s[i + 1] === "(") {
      const close = matching(s, i + 1); const end = close < 0 ? s.length : close;
      const inner = parse(s.slice(i + 2, end));
      for (const seg of inner.segments) segments.push(seg);
      word += "$"; has = true; i = end; continue;
    }
    if (c === ">" ) {
      // `2>`, `&>`: the digits or `&` just read are part of the operator, not a word.
      if (has && /^(\d+|&)$/.test(word)) { word = ""; has = false; } else endWord();
      if (s[i + 1] === ">") i++;
      if (s[i + 1] === "&") { // `>&2`, `>&-`: a descriptor, not a file
        let j = i + 2; while (j < s.length && /[\d-]/.test(s[j]!)) j++;
        i = j - 1; continue;
      }
      pendingRedirect = true; continue;
    }
    if (c === "<") { endWord(); continue; }
    if (c === "|") { if (s[i + 1] === "|") { i++; endSegment(false); } else { if (s[i + 1] === "&") i++; endSegment(true); } continue; }
    if (c === "&") { if (s[i + 1] === "&") i++; else if (s[i + 1] === ">") { continue; } endSegment(false); continue; }
    if (c === ";" || c === "\n" || c === "(" || c === ")" || c === "{" && /\s/.test(s[i + 1] ?? " ") || c === "}" && !has) { endSegment(false); continue; }
    if (c === " " || c === "\t" || c === "\r") { endWord(); continue; }
    if (c === "#" && !has) { const nl = s.indexOf("\n", i); i = (nl < 0 ? s.length : nl) - 1; continue; }
    word += c; has = true;
  }
  endSegment(false);
  return { segments, pipes };
}

function matching(s: string, open: number): number {
  let depth = 0;
  for (let i = open; i < s.length; i++) {
    const c = s[i];
    if (c === "\\") { i++; continue; }
    if (c === "'") { const j = s.indexOf("'", i + 1); if (j < 0) return -1; i = j; continue; }
    if (c === "(") depth++;
    else if (c === ")" && --depth === 0) return i;
  }
  return -1;
}

/** The command without the bodies of its here-documents (`<<EOF ... EOF`, `<<-'EOF'`): they are data. */
function dropHereDocs(command: string): string {
  if (!command.includes("<<")) return command;
  const out: string[] = [];
  const lines = command.split("\n");
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]!;
    out.push(line.replace(/<<-?\s*(['"]?)([A-Za-z_][A-Za-z0-9_]*)\1/g, " "));
    const ends = [...line.matchAll(/<<(-?)\s*(['"]?)([A-Za-z_][A-Za-z0-9_]*)\2/g)].map((m) => ({ strip: m[1] === "-", word: m[3]! }));
    for (const end of ends) {
      i++;
      while (i < lines.length && (end.strip ? lines[i]!.replace(/^\t+/, "") : lines[i]!) !== end.word) i++;
    }
  }
  return out.join("\n");
}

/** The program a segment runs and its arguments, past `VAR=x` assignments and wrappers like `nohup` or `env`. */
function program(seg: Segment): { name: string; args: string[]; elevated: boolean } | undefined {
  let w = seg.words.slice();
  let elevated = false;
  for (;;) {
    while (w.length > 0 && /^[A-Za-z_][A-Za-z0-9_]*=/.test(w[0]!)) w = w.slice(1);
    const first = w[0];
    if (first === undefined) return undefined;
    const name = first.includes("/") ? posix.basename(first) : first;
    if (name === "env") { w = w.slice(1).filter((x, k, all) => !(x.startsWith("-") && k === 0) || all.length === 1); continue; }
    if (name === "xargs") { w = w.slice(1); while (w.length > 0 && w[0]!.startsWith("-")) w = w.slice(/^-[IinLPsd]$/.test(w[0]!) ? 2 : 1); continue; }
    if (name === "sudo" || name === "doas") { elevated = true; w = w.slice(1); while (w.length > 0 && w[0]!.startsWith("-")) w = w.slice(/^-[ugCh]$/.test(w[0]!) ? 2 : 1); continue; }
    if (WRAPPERS.has(name)) { w = w.slice(1); while (w.length > 0 && (w[0]!.startsWith("-") || /^\d+[smhd]?$/.test(w[0]!))) w = w.slice(1); continue; }
    return { name, args: w.slice(1), elevated };
  }
}

function expandHome(p: string, env: GuardEnv): string {
  if (env.home === undefined) return p;
  if (p === "~") return env.home;
  if (p.startsWith("~/")) return env.home + p.slice(1);
  return p.replace(/^\$\{?HOME\}?(?=\/|$)/, env.home);
}

const inside = (p: string, root: string) => p === root || p.startsWith(root.endsWith("/") ? root : root + "/");
const unknown = (word: string) => /[$`]/.test(word);

/** The absolute path a word names when it is read as a file (relative to [cwd]); glob characters stay as they are. */
function resolve(word: string, cwd: string, env: GuardEnv): string {
  const expanded = expandHome(word, env);
  const p = posix.resolve(cwd, expanded);
  // Every path check goes through here, so the whole-workspace and app-folder checks see the alias as the folder itself.
  for (const [from, to] of env.aliases ?? []) {
    if (p === from || p.startsWith(from + "/")) return to + p.slice(from.length);
  }
  return p;
}

function free(p: string, env: GuardEnv): boolean {
  return inside(p, env.workspace) || env.temp.some((t) => inside(p, t));
}

function harmless(p: string): boolean {
  return HARMLESS.some((r) => r.test(p));
}

/** Whether [p] is one of the app's own folders, or a folder above one of them or above the workspace. */
function precious(p: string, env: GuardEnv): boolean {
  const roots = [...env.protectedRoots, env.workspace, ...(env.home === undefined ? [] : [env.home])];
  return roots.some((r) => p === r || inside(r, p)) || env.protectedRoots.some((r) => inside(p, r));
}

const AWKS = new Set(["awk", "gawk", "mawk", "nawk"]);

/**
 * The words of a sed, awk or perl call that name files. Their scripts are text, not paths (`sed -i '/TODO/d' notes.md` named
 * "/TODO/d", and the guard asked about a place outside the workspace that does not exist): the value of a flag that takes one is
 * left out (sed -e -f -l, awk -F -v -f, perl -e and its bundles like -ne), and for sed and awk without -e/-f the first word is
 * the script. undefined for any other program.
 */
function scriptFileOperands(name: string, args: string[]): string[] | undefined {
  const sed = name === "sed", awk = AWKS.has(name), perl = name === "perl";
  if (!sed && !awk && !perl) return undefined;
  // Short flags whose value follows (attached, or as the next word), and the ones among them that carry the script.
  const valued = sed ? "efl" : awk ? "Fvf" : "eE";
  const script = sed ? "ef" : awk ? "f" : "eE";
  // Flags whose optional value can only be attached (sed -i.bak, perl -i.orig -Mmodule -Ilib ...): the rest of the word.
  const attachedOnly = sed ? "i" : perl ? "iIMmFxCdD" : "";
  const long = sed ? ["--expression", "--file", "--line-length"] : awk ? ["--field-separator", "--assign", "--file"] : [];
  const longScript = sed ? ["--expression", "--file"] : awk ? ["--file"] : [];
  const words: string[] = [];
  let scriptGiven = false;
  for (let k = 0; k < args.length; k++) {
    const a = args[k]!;
    if (a === "--") { words.push(...args.slice(k + 1)); break; }
    if (!a.startsWith("-") || a === "-") { words.push(a); continue; }
    if (a.startsWith("--")) {
      const [flag, attached] = a.split("=", 2) as [string, string | undefined];
      if (!long.includes(flag)) continue;
      if (longScript.includes(flag)) scriptGiven = true;
      if (attached === undefined) k++;
      continue;
    }
    for (let j = 1; j < a.length; j++) {
      const c = a[j]!;
      if (attachedOnly.includes(c)) break;
      // perl -l and -0 take attached octal digits only (-l0, -00), then more flags may follow (-lne).
      if (perl && (c === "l" || c === "0")) { while (j + 1 < a.length && /[0-7]/.test(a[j + 1]!)) j++; continue; }
      if (!valued.includes(c)) continue;
      if (script.includes(c)) scriptGiven = true;
      if (j === a.length - 1) k++; // the value is the next word
      break; // the rest of this word is the value
    }
  }
  return (sed || awk) && !scriptGiven ? words.slice(1) : words;
}

/** The words of [args] that may name files: a sed/awk/perl script is not one, also inside `find … -exec sed …`. */
function fileOperands(name: string, args: string[]): string[] {
  const scripted = scriptFileOperands(name, args);
  if (scripted !== undefined) return scripted;
  if (name !== "find") return args.filter((a) => !a.startsWith("-"));
  const out: string[] = [];
  for (let k = 0; k < args.length; k++) {
    const a = args[k]!;
    if (!["-exec", "-execdir", "-ok", "-okdir"].includes(a)) { if (!a.startsWith("-")) out.push(a); continue; }
    let end = k + 1;
    while (end < args.length && args[end] !== ";" && args[end] !== "+") end++;
    const [run, ...rest] = args.slice(k + 1, end);
    if (run !== undefined) out.push(...fileOperands(run.includes("/") ? posix.basename(run) : run, rest));
    k = end;
  }
  return out;
}

const short = (command: string) => (command.length > 160 ? command.slice(0, 157) + "…" : command).replace(/\s+/g, " ").trim();

function readOnlySegment(seg: Segment): boolean {
  if (seg.redirects.some((r) => !harmless(r))) return false;
  const p = program(seg);
  if (p === undefined) return seg.words.every((w) => /^[A-Za-z_][A-Za-z0-9_]*=/.test(w)); // only assignments
  if (p.name === "git") { const sub = p.args.find((a) => !a.startsWith("-")); return sub !== undefined && GIT_READ_ONLY.has(sub) && !(sub === "config" && p.args.filter((a) => !a.startsWith("-")).length > 2) && !(sub === "branch" && p.args.some((a) => /^-[dDmMcC]/.test(a))) && !(sub === "remote" && p.args.some((a) => ["add", "remove", "rm", "set-url", "rename"].includes(a))); }
  if (p.name === "find") return !p.args.some((a) => ["-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint", "-fprintf", "-fls"].includes(a));
  if (p.name === "sed") return !p.args.some((a) => /^-[a-zA-Z]*i/.test(a) || a.startsWith("--in-place"));
  if (p.name === "cd") return true;
  if ((p.name === "node" || p.name === "python3" || p.name === "python" || p.name === "npm" || p.name === "pnpm") && p.args.length === 1 && ["-v", "--version", "-V"].includes(p.args[0]!)) return true;
  return READ_ONLY.has(p.name);
}

/** The risky kind a bash command belongs to at the `smart` level, the worst one first; undefined when it is ordinary work. */
function risk(command: string, cwd: string, env: GuardEnv): Verdict | undefined {
  const { segments, pipes } = parse(command);
  let ask: Verdict | undefined;
  const hold = (why: string, text: string) => { ask ??= { kind: "ask", why, text }; };
  const cmd = short(command);
  if (/(^|[;&|\s])eval\s/.test(command)) hold("remote-code", `要用 eval 執行組出來的指令，內容事先看不到：${cmd}`);
  if (/<\(\s*(curl|wget)\b/.test(command) || /\b(source|\.)\s+<\(/.test(command)) hold("remote-code", `要直接執行從網路抓下來的程式：${cmd}`);
  let here = cwd;
  for (const [index, seg] of segments.entries()) {
    const p = program(seg);
    for (const r of seg.redirects) {
      if (unknown(r) || harmless(r)) continue;
      const target = resolve(r, here, env);
      if (free(target, env)) continue;
      hold(precious(target, env) ? "protected" : "outside", `要寫入工作區以外的檔案 ${target}：${cmd}`);
    }
    if (p === undefined) continue;
    const { name, args } = p;
    if (p.elevated) hold("system", `要用系統管理員權限執行：${cmd}`);
    const operands = args.filter((a) => !a.startsWith("-"));
    const flags = args.filter((a) => a.startsWith("-"));
    if (name === "cd" && operands[0] !== undefined && !unknown(operands[0])) { here = resolve(operands[0], here, env); continue; }

    if (name === "rm" || name === "rmdir" || name === "unlink" || name === "find" && args.includes("-delete")) {
      const recursive = name === "rm" && flags.some((f) => /^-[a-zA-Z]*[rR]/.test(f) || f === "--recursive") || name === "find";
      const targets = name === "find" ? operands.slice(0, 1) : operands;
      const filtered = name === "find" && args.some((a) => /^-(i?name|i?path|i?regex|type|newer|[amc]time|[amc]min|size|empty|maxdepth)$/.test(a));
      for (const raw of targets) {
        const t = expandHome(raw, env);
        if (unknown(t)) { if (recursive) hold("delete", `要刪除整個資料夾，但路徑要執行時才知道：${cmd}`); continue; }
        const bare = t.replace(/\/+$/, "") || "/";
        const everything = /^(\.|\*|\.\*|\.\/\*|\.\/)$/.test(t) && !filtered;
        const target = resolve(/[*?]/.test(bare) ? posix.dirname(bare) : bare, here, env);
        if (recursive && (target === "/" || precious(target, env) && !(target === env.workspace && everything) && target !== env.workspace || env.protectedRoots.some((r) => inside(target, r)))) {
          return { kind: "deny", why: "delete-app", text: `不能刪除 ${target}：那是 DSH 自己的資料或它上層的資料夾，刪了對話和設定會壞掉。指令：${cmd}` };
        }
        if (!free(target, env)) hold(precious(target, env) ? "protected" : "outside", `要刪除工作區以外的 ${target}：${cmd}`);
        else if (recursive && !filtered && (everything && here === env.workspace || target === env.workspace)) hold("delete", `要刪除整個工作區的內容：${cmd}`);
      }
      continue;
    }
    if (SYSTEM.has(name) || name.startsWith("mkfs")) { hold("system", `要執行會影響系統或其他程式的指令（${name}）：${cmd}`); continue; }
    if (name === "kill" && args.some((a) => a === "-1" || a === "0")) { hold("system", `要一次停掉很多程式：${cmd}`); continue; }
    if (name === ":(){" || /^:\(\)\{/.test(seg.words.join(""))) return { kind: "deny", why: "fork-bomb", text: `這個指令會不斷複製自己把手機塞爆：${cmd}` };

    if (name === "curl" && args.some((a, k) => /^(-d|--data.*|-F|--form.*|-T|--upload-file|--json)$/.test(a) || /^-[a-zA-Z]*[dFT]$/.test(a) || (a === "-X" || a === "--request") && /^(POST|PUT|PATCH|DELETE)$/i.test(args[k + 1] ?? "") || /^-X(POST|PUT|PATCH|DELETE)$/i.test(a))) hold("network-send", `要把資料送到網路上：${cmd}`);
    if (name === "wget" && args.some((a) => /^--(post-data|post-file|method|body-data|body-file)/.test(a))) hold("network-send", `要把資料送到網路上：${cmd}`);
    if (NETWORK_SENDERS.has(name) || name === "rsync" && operands.some((o) => /^[\w.-]+@?[\w.-]*:/.test(o) || o.startsWith("rsync://"))) hold("network-send", `要連到別台機器（${name}）：${cmd}`);
    if (name === "git") {
      const sub = operands[0];
      if (sub === "push") hold("network-send", `要把程式碼推到遠端：${cmd}`);
      if (sub === "clean" && flags.some((f) => /^-[a-zA-Z]*f/.test(f) || f === "--force")) hold("delete", `要刪掉所有沒加入版本控制的檔案：${cmd}`);
      if (sub === "reset" && flags.includes("--hard")) hold("delete", `要丟掉還沒提交的修改：${cmd}`);
    }
    if (SHELLS.has(name) && pipes.includes(index) && (operands.length === 0 || operands[0] === "-")) hold("remote-code", `要把前一個指令的輸出直接當成程式執行：${cmd}`);
    if (INTERPRETERS.has(name) && !SHELLS.has(name) && pipes.includes(index) && (args.length === 0 || args[0] === "-") && segments.slice(0, index).some((s) => ["curl", "wget", "base64", "xxd"].includes(program(s)?.name ?? ""))) hold("remote-code", `要直接執行從網路抓下來的程式：${cmd}`);
    if ((name === "npm" || name === "pnpm" || name === "yarn") && flags.some((f) => f === "-g" || f === "--global") && operands.some((o) => ["install", "i", "add", "link", "uninstall", "remove", "rm"].includes(o))) hold("global-install", `要安裝或移除全域套件，會改到 DSH 的執行環境：${cmd}`);

    if (readOnlySegment(seg)) continue;
    // Anything else that names a place outside the workspace: for copies only the destination counts.
    const copies = name === "cp" || name === "ln" || name === "install" || name === "rsync";
    const paths = copies ? operands.slice(-1) : fileOperands(name, args);
    for (const o of paths) {
      if (unknown(o) || !/^(\/|~|\$\{?HOME|\.\.(\/|$))/.test(o) && !o.includes("/../")) continue;
      const target = resolve(o, here, env);
      if (free(target, env) || harmless(target)) continue;
      hold(precious(target, env) ? "protected" : "outside", `要動到工作區以外的 ${target}：${cmd}`);
    }
  }
  return ask;
}

/** One file path the fs tools were given, judged at the `smart` level. */
function pathRisk(tool: string, filePath: unknown, env: GuardEnv): Verdict | undefined {
  if (typeof filePath !== "string" || filePath === "") return undefined;
  const target = resolve(filePath, env.workspace, env);
  if (free(target, env)) return undefined;
  const verb = tool === "edit" ? "修改" : "寫入";
  return { kind: "ask", why: precious(target, env) ? "protected" : "outside", text: `要${verb}工作區以外的檔案 ${target}` };
}

/**
 * The decision for one tool call. `full` only refuses the short hardline list; `smart` also asks before the risky kinds;
 * `ask` asks before every command that is not plainly read-only, every file change, and before work is handed to other agents.
 */
export function classify(tool: string, args: unknown, env: GuardEnv): Verdict {
  const a = (typeof args === "object" && args !== null ? args : {}) as Record<string, unknown>;
  if (tool === "bash") {
    const command = typeof a["command"] === "string" ? a["command"] : "";
    const workdir = typeof a["workdir"] === "string" && a["workdir"] !== "" ? resolve(a["workdir"], env.workspace, env) : env.workspace;
    const found = risk(command, workdir, env);
    if (found?.kind === "deny") return found;
    if (env.level === "full") return ALLOW;
    if (found !== undefined) return found;
    if (env.level === "ask" && !parse(command).segments.every(readOnlySegment)) return { kind: "ask", why: "command", text: `要執行指令：${short(command)}` };
    return ALLOW;
  }
  if (env.level === "full") return ALLOW;
  if (tool === "write" || tool === "edit") {
    const found = pathRisk(tool, a["file_path"], env);
    if (found !== undefined) return found;
    if (env.level === "ask") return { kind: "ask", why: "file", text: `要${tool === "edit" ? "修改" : "寫入"}檔案 ${String(a["file_path"] ?? "")}` };
    return ALLOW;
  }
  if (env.level === "ask" && DELEGATING_TOOLS.has(tool)) {
    const what = typeof a["description"] === "string" ? a["description"] : typeof a["title"] === "string" ? a["title"] : tool;
    const agent = tool.startsWith("subagent") || tool === "spawn_teammate";
    return { kind: "ask", why: "delegate", text: agent ? `要派出子代理人：${what}（它會照「一般」等級自己工作）` : `要執行 ${tool}：${what}` };
  }
  // An MCP server's tool can do anything its server does; 每次都問 asks first. At smart and full they run: the owner added
  // those servers himself.
  if (env.level === "ask" && tool.startsWith("mcp__")) return { kind: "ask", why: "mcp", text: `要使用 MCP 伺服器的工具 ${tool}` };
  return ALLOW;
}

export const isMutatingTool = (tool: string) => MUTATING_TOOLS.has(tool) || DELEGATING_TOOLS.has(tool) || tool.startsWith("mcp__");
