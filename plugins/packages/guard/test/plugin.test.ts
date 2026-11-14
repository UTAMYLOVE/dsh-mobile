import { afterEach, describe, expect, it, vi } from "vitest";
import * as plugin from "../src/index.js";

type Listener = (exec: unknown, next: () => Promise<{ kind: string }>) => Promise<{ kind: string; reason?: string; displayReason?: { zh: string } }>;

const WS = "/data/user/0/app.dshmobile/files/workspace";
const saved = { ...process.env };

function load(envVars: Record<string, string>, session: { mode?: string; policy?: string; preset?: string } = {}) {
  process.env = { ...saved, DSHM_WORKSPACE: WS, HOME: "/data/user/0/app.dshmobile/files/home", TMPDIR: "/data/user/0/app.dshmobile/cache/tmp", DSH_HOME: "/data/user/0/app.dshmobile/files/dsh-home", ...envVars };
  let listener: Listener | undefined;
  let options: unknown;
  const services: Record<string, unknown> = {
    sandboxPolicy: { resolve: () => ({ mode: session.mode ?? "danger-full-access" }) },
    approval: { effectivePolicy: () => session.policy ?? "ask" },
    permissionPresets: { current: () => session.preset ?? "dshm-guarded" },
  };
  const ctx = {
    logger: { info: vi.fn(), warn: vi.fn() },
    get: (n: string) => services[n],
    on: (_event: string, l: Listener, o: unknown) => { listener = l; options = o; },
  };
  plugin.apply(ctx as never);
  const next = vi.fn(async () => ({ kind: "allow" }));
  const run = (tool: string, args: unknown) => listener!({ name: tool, arguments: args, agent: { session: {} } }, next);
  return { run, next, options, ctx, listener };
}

afterEach(() => { process.env = { ...saved }; });

describe("dshm-guard", () => {
  it("is mounted before dsh's own pre-execute listeners and does nothing without a workspace", () => {
    expect(load({}).options).toEqual({ prepend: true });
    process.env = { ...saved }; delete process.env["DSHM_WORKSPACE"];
    const on = vi.fn(); const warn = vi.fn();
    plugin.apply({ logger: { info: vi.fn(), warn }, get: () => undefined, on } as never);
    expect(on).not.toHaveBeenCalled(); expect(warn).toHaveBeenCalled();
  });

  it("smart is the default: ordinary work passes on, a risky call asks with the owner's text, a hardline call is refused", async () => {
    const g = load({});
    expect(await g.run("bash", { command: "mkdir reports && echo hi > reports/a.md" })).toEqual({ kind: "allow" });
    const ask = await g.run("bash", { command: "rm -rf /sdcard/DCIM" });
    expect(ask.kind).toBe("ask");
    expect(ask.displayReason?.zh).toBe("要刪除工作區以外的 /sdcard/DCIM：rm -rf /sdcard/DCIM");
    const deny = await g.run("bash", { command: "rm -rf ~" });
    expect(deny.kind).toBe("deny");
    expect(deny.reason).toContain("refused");
  });

  it("a later listener's refusal wins over the question", async () => {
    const g = load({});
    g.next.mockResolvedValueOnce({ kind: "deny" });
    expect((await g.run("bash", { command: "git push" })).kind).toBe("deny");
  });

  it("follows the level the app set", async () => {
    expect((await load({ DSHM_GUARD_LEVEL: "full" }).run("bash", { command: "rm -rf /sdcard/DCIM" })).kind).toBe("allow");
    expect((await load({ DSHM_GUARD_LEVEL: "full" }).run("bash", { command: "rm -rf /" })).kind).toBe("deny");
    expect((await load({ DSHM_GUARD_LEVEL: "ask" }).run("bash", { command: "mkdir x" })).kind).toBe("ask");
    expect((await load({ DSHM_GUARD_LEVEL: "ask" }).run("bash", { command: "ls -la" })).kind).toBe("allow");
    expect((await load({ DSHM_GUARD_LEVEL: "nonsense" }).run("bash", { command: "mkdir x" })).kind).toBe("allow");
  });

  it("a subagent cannot ask: ordinary work runs even at the ask level, and what would ask is refused with the way out", async () => {
    const child = load({ DSHM_GUARD_LEVEL: "ask" }, { policy: "never" });
    expect((await child.run("bash", { command: "mkdir x && echo hi > x/a" })).kind).toBe("allow");
    expect((await child.run("write", { file_path: "a.txt", content: "x" })).kind).toBe("allow");
    const refused = await child.run("bash", { command: "git push" });
    expect(refused.kind).toBe("deny");
    expect(refused.reason).toContain("lead agent");
    expect((await load({ DSHM_GUARD_LEVEL: "full" }, { policy: "never" }).run("bash", { command: "git push" })).kind).toBe("allow");
  });

  it("leaves dsh's sandboxed modes and Auto review to their own rules", async () => {
    expect(await load({}, { mode: "workspace-write" }).run("bash", { command: "rm -rf /" })).toEqual({ kind: "allow" });
    expect(await load({}, { preset: "auto" }).run("bash", { command: "git push" })).toEqual({ kind: "allow" });
  });

  it("reads its folders from the environment", () => {
    expect(plugin.readEnv({ DSHM_WORKSPACE: "/w/", TMPDIR: "/t/", HOME: "/h", DSH_HOME: "/h/.dsh", DSHM_GUARD_PROTECTED: "/opt/dsh:/opt/dshm:relative", DSHM_GUARD_LEVEL: "ask" }))
      .toEqual({ level: "ask", workspace: "/w", temp: ["/t", "/tmp"], protectedRoots: ["/h/.dsh", "/opt/dsh", "/opt/dshm"], home: "/h" });
    expect(plugin.readEnv({ DSHM_WORKSPACE: "relative" })).toBeUndefined();
  });
});
