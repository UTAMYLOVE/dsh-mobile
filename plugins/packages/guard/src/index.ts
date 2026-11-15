import { realpathSync } from "node:fs";
import { isAbsolute } from "node:path";
import { classify, type GuardEnv, type Level } from "./classify.js";

export const name = "dshm-guard";
// The services read below are optional on purpose: an injected-but-missing service would leave the plugin pending forever.
export const inject = ["tools"];

/**
 * DSH Mobile's permission levels (設定 → 權限), decided before a tool call runs.
 *
 * Android has neither of dsh's sandbox backends (no bubblewrap, no Landlock for apps), so dsh's own `workspace-write` mode
 * refuses every command until the model retries it with an escalation the owner approves, and a subagent (whose approval
 * policy dsh pins to `never`) could not run a command at all. The app therefore runs conversations with full access inside
 * Android's own app sandbox and this guard supplies the judgement: ordinary work runs, the risky kinds ask the owner, a short
 * list is refused. It is a convenience and a seat belt, not a security boundary; the boundary is Android's app sandbox.
 */
interface Agent { session?: unknown }
interface Exec { name: string; arguments?: unknown; agent?: Agent }
type Decision = { kind: string; reason?: string; displayReason?: { en: string; zh: string } };
interface PluginCtx {
  logger: { info(msg: string): void; warn(msg: string): void };
  get(name: string): any;
  on(event: "tools/pre-execute", listener: (exec: Exec, next: () => Promise<Decision>) => Promise<Decision>, options?: { prepend?: boolean }): unknown;
}

const LEVELS: readonly Level[] = ["ask", "smart", "full"];
/** dsh's presets the app configures (DshPatch.permission), plus dsh's own Auto review. */
const GUARDED = "dshm-guarded";
const FULL_ACCESS = "danger-full-access";
const AUTO = "auto";

/** `from=to;from=to` pairs of absolute paths, without trailing slashes; anything else is dropped. */
function pairs(value: string | undefined): [string, string][] {
  const trim = (p: string) => (p.length > 1 ? p.replace(/\/+$/, "") : p);
  return (value ?? "").split(";").map((s) => s.split("=")).filter((p): p is [string, string] => p.length === 2 && isAbsolute(p[0]!) && isAbsolute(p[1]!))
    .map(([from, to]) => [trim(from), trim(to)]);
}

/** The folder's real name (symbolic links resolved); undefined when it cannot be read. */
function realName(p: string): string | undefined {
  try {
    return realpathSync(p);
  } catch {
    return undefined;
  }
}

/**
 * The shortest pair of folders that makes [real] and [given] the same path: /data/data and /data/user/0 for the app's folders
 * on Android, where /data/user/0 is a link to /data/data. Neither side is ever "/".
 */
export function linkPair(real: string, given: string): [string, string] {
  const a = real.split("/");
  const b = given.split("/");
  while (a.length > 2 && b.length > 2 && a[a.length - 1] === b[b.length - 1]) { a.pop(); b.pop(); }
  return [a.join("/"), b.join("/")];
}

/**
 * The guard's settings from the environment the app starts dsh with; undefined when the workspace is not known. [real] reads a
 * folder's real name (a parameter so tests can fake the phone's links).
 */
export function readEnv(e: Record<string, string | undefined>, real: (p: string) => string | undefined = realName): GuardEnv | undefined {
  const workspace = e["DSHM_WORKSPACE"];
  if (workspace === undefined || !isAbsolute(workspace)) return undefined;
  const level = LEVELS.includes(e["DSHM_GUARD_LEVEL"] as Level) ? (e["DSHM_GUARD_LEVEL"] as Level) : "smart";
  const list = (value: string | undefined) => (value ?? "").split(":").filter((p) => isAbsolute(p)).map((p) => (p.length > 1 ? p.replace(/\/+$/, "") : p));
  const temp = [...new Set([...list(e["TMPDIR"]), "/tmp"])];
  const home = e["HOME"] !== undefined && isAbsolute(e["HOME"]) ? e["HOME"].replace(/\/+$/, "") : undefined;
  const dshHome = list(e["DSH_HOME"]);
  const root = workspace.replace(/\/+$/, "");
  const protectedRoots = [...new Set([...dshHome, ...list(e["DSHM_GUARD_PROTECTED"])])];
  // The folders' other names: native mode's DSHN_REMAP (/root/workspace and /root/.dsh onto the phone's folders, the shim's own
  // table), proot's DSHM_GUARD_ALIASES (the workspace's host path, which proot also binds).
  const named = [...pairs(e["DSHN_REMAP"]), ...pairs(e["DSHM_GUARD_ALIASES"])];
  // And their real names: dsh resolves links in every path it records or prints (a conversation's folder, a tool's <path>), so
  // on the phone the model also sees /data/data/... for the /data/user/0/... folders it was given. Without these, a write in
  // the workspace under that name asked as "outside" and deleting dsh's home under it was not refused.
  const linked = new Map<string, [string, string]>();
  for (const p of [root, ...protectedRoots, ...temp, ...(home === undefined ? [] : [home])]) {
    const r = real(p);
    if (r === undefined || !isAbsolute(r) || r === p) continue;
    const pair = linkPair(r.length > 1 ? r.replace(/\/+$/, "") : r, p);
    if (pair[0] !== pair[1]) linked.set(pair.join("="), pair);
  }
  const aliases = [...named, ...linked.values()];
  return {
    level, workspace: root, temp, protectedRoots,
    ...(home === undefined ? {} : { home }), ...(aliases.length === 0 ? {} : { aliases }),
  };
}

/** The app's Auto review switch as it applies at the level (DSHM_AUTO_REVIEW, "1"/"0"); undefined from an app that does not say. */
export function readAutoReview(e: Record<string, string | undefined>): boolean | undefined {
  const v = e["DSHM_AUTO_REVIEW"];
  return v === "1" ? true : v === "0" ? false : undefined;
}

/**
 * The preset a top-level conversation should be on: the same rule ShellSession follows before the owner's sends
 * (DshMobileApp.autoReviewWanted). undefined leaves it as it is: an app that does not say whether Auto review is on never moves a
 * conversation onto or off "auto".
 */
export function wantedPreset(level: Level, autoReview: boolean | undefined, current: unknown): string | undefined {
  if (autoReview === undefined && current === AUTO) return undefined;
  if (level === "full") return FULL_ACCESS;
  if (autoReview === true) return AUTO;
  return GUARDED;
}

export function apply(ctx: PluginCtx): void {
  const env = readEnv(process.env);
  if (env === undefined) {
    ctx.logger.warn("[dshm-guard] DSHM_WORKSPACE is unset or not an absolute path; the guard is off");
    return;
  }
  const autoReview = readAutoReview(process.env);
  ctx.on(
    "tools/pre-execute",
    async (exec, next) => {
      const agent = exec.agent;
      if (agent === undefined) return next();
      // A real subagent (or teammate) has origin "subagent"; a fork has a parent but is the owner's own conversation.
      const subagent = (agent.session as { header?: { origin?: unknown } } | undefined)?.header?.origin === "subagent";
      let mode: unknown;
      let policy: unknown;
      let auto = false;
      try {
        const presets = ctx.get("permissionPresets");
        mode = ctx.get("sandboxPolicy")?.resolve({ session: agent.session })?.mode;
        policy = ctx.get("approval")?.effectivePolicy(agent.session);
        let preset: unknown = presets?.current(agent.session);
        // The app puts a conversation on its level's preset only before the owner's own sends (ShellSession.ensurePreset).
        // Turns dsh starts itself (a reminder, a helper, a conversation dsh moved to danger-full-access at a clean stop) kept a
        // stale preset, and at 一般 every risky step was refused as if from a subagent. The same rule is applied here, and this
        // very call is judged by the new state (dsh reads the approval policy when it asks).
        let moved = false;
        if (!subagent && presets !== undefined) {
          const wanted = wantedPreset(env.level, autoReview, preset);
          if (wanted !== undefined && preset !== wanted) {
            presets.set(agent.session, wanted);
            preset = wanted;
            mode = FULL_ACCESS;
            policy = wanted === FULL_ACCESS ? "never" : "ask";
            moved = true;
          }
        }
        // The call that moves a conversation onto "auto" is still judged by the level: dsh's own Auto review may already have
        // passed this call on while the conversation was on its old preset (whichever listener runs first), and then nothing
        // would review it or ask. From the next call on, the reviewer judges.
        auto = preset === AUTO && !moved;
      } catch (e) {
        ctx.logger.warn(`[dshm-guard] cannot read the conversation's permissions: ${e instanceof Error ? e.message : String(e)}`);
      }
      // dsh's Auto review judges its own conversations, but the short always-refused list (deleting DSH's own data, a fork bomb)
      // stays refused there too, as the 權限 note promises. The level's questions are not added: that would make the review
      // pointless at 每次都問.
      if (auto) {
        const hard = classify(exec.name, exec.arguments, { ...env, level: "full" });
        return hard.kind === "deny" ? { kind: "deny", reason: `DSH Mobile refused this call (${hard.why}): ${hard.text}` } : next();
      }
      // A conversation still on one of dsh's sandboxed modes keeps dsh's own rules (escalation asks the owner).
      if (mode !== undefined && mode !== FULL_ACCESS) return next();
      // Nobody can be asked from a subagent: it works at the smart level and what would ask is refused with the reason.
      const cannotAsk = policy === "never" && env.level !== "full";
      const level: Level = cannotAsk && env.level === "ask" ? "smart" : env.level;
      const verdict = classify(exec.name, exec.arguments, level === env.level ? env : { ...env, level });
      if (verdict.kind === "allow") return next();
      if (verdict.kind === "deny") return { kind: "deny", reason: `DSH Mobile refused this call (${verdict.why}): ${verdict.text}` };
      if (cannotAsk) {
        return subagent
          ? { kind: "deny", reason: `DSH Mobile: this needs the owner's approval (${verdict.why}) and a subagent cannot ask. Do not work around it; report it to the lead agent, which can ask. ${verdict.text}` }
          : { kind: "deny", reason: `DSH Mobile (${verdict.why}): 這個對話目前不能詢問你，這一步沒有執行。Do not work around it; tell the owner what was not done and why. ${verdict.text}` };
      }
      const downstream = await next();
      if (downstream.kind !== "allow") return downstream;
      return { kind: "ask", reason: `DSH Mobile (${verdict.why}): ${verdict.text}`, displayReason: { en: verdict.text, zh: verdict.text } };
    },
    { prepend: true },
  );
  ctx.logger.info(`[dshm-guard] loaded (level ${env.level}, workspace ${env.workspace})`);
}
