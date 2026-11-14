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

/** The guard's settings from the environment the app starts dsh with; undefined when the workspace is not known. */
export function readEnv(e: Record<string, string | undefined>): GuardEnv | undefined {
  const workspace = e["DSHM_WORKSPACE"];
  if (workspace === undefined || !isAbsolute(workspace)) return undefined;
  const level = LEVELS.includes(e["DSHM_GUARD_LEVEL"] as Level) ? (e["DSHM_GUARD_LEVEL"] as Level) : "smart";
  const list = (value: string | undefined) => (value ?? "").split(":").filter((p) => isAbsolute(p)).map((p) => (p.length > 1 ? p.replace(/\/+$/, "") : p));
  const temp = [...new Set([...list(e["TMPDIR"]), "/tmp"])];
  const home = e["HOME"] !== undefined && isAbsolute(e["HOME"]) ? e["HOME"].replace(/\/+$/, "") : undefined;
  const dshHome = list(e["DSH_HOME"]);
  return { level, workspace: workspace.replace(/\/+$/, ""), temp, protectedRoots: [...new Set([...dshHome, ...list(e["DSHM_GUARD_PROTECTED"])])], ...(home === undefined ? {} : { home }) };
}

export function apply(ctx: PluginCtx): void {
  const env = readEnv(process.env);
  if (env === undefined) {
    ctx.logger.warn("[dshm-guard] DSHM_WORKSPACE is unset or not an absolute path; the guard is off");
    return;
  }
  ctx.on(
    "tools/pre-execute",
    async (exec, next) => {
      const agent = exec.agent;
      if (agent === undefined) return next();
      let mode: unknown;
      let policy: unknown;
      try {
        mode = ctx.get("sandboxPolicy")?.resolve({ session: agent.session })?.mode;
        policy = ctx.get("approval")?.effectivePolicy(agent.session);
        // dsh's Auto review judges its own conversations.
        if (ctx.get("permissionPresets")?.current(agent.session) === "auto") return next();
      } catch (e) {
        ctx.logger.warn(`[dshm-guard] cannot read the conversation's permissions: ${e instanceof Error ? e.message : String(e)}`);
      }
      // A conversation still on one of dsh's sandboxed modes keeps dsh's own rules (escalation asks the owner).
      if (mode !== undefined && mode !== "danger-full-access") return next();
      // Nobody can be asked from a subagent: it works at the smart level and what would ask is refused with the reason.
      const cannotAsk = policy === "never" && env.level !== "full";
      const level: Level = cannotAsk && env.level === "ask" ? "smart" : env.level;
      const verdict = classify(exec.name, exec.arguments, level === env.level ? env : { ...env, level });
      if (verdict.kind === "allow") return next();
      if (verdict.kind === "deny") return { kind: "deny", reason: `DSH Mobile refused this call (${verdict.why}): ${verdict.text}` };
      if (cannotAsk) {
        return { kind: "deny", reason: `DSH Mobile: this needs the owner's approval (${verdict.why}) and a subagent cannot ask. Do not work around it; report it to the lead agent, which can ask. ${verdict.text}` };
      }
      const downstream = await next();
      if (downstream.kind !== "allow") return downstream;
      return { kind: "ask", reason: `DSH Mobile (${verdict.why}): ${verdict.text}`, displayReason: { en: verdict.text, zh: verdict.text } };
    },
    { prepend: true },
  );
  ctx.logger.info(`[dshm-guard] loaded (level ${env.level}, workspace ${env.workspace})`);
}
