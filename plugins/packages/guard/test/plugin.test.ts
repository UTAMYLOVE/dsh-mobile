import { afterEach, describe, expect, it, vi } from "vitest";
import { classify } from "../src/classify.js";
import * as plugin from "../src/index.js";

type Listener = (exec: unknown, next: () => Promise<{ kind: string }>) => Promise<{ kind: string; reason?: string; displayReason?: { zh: string } }>;

const WS = "/data/user/0/app.dshmobile/files/workspace";
const saved = { ...process.env };

const DSH_HOME = "/data/user/0/app.dshmobile/files/dsh-home";

/**
 * The plugin with a fake dsh: [session] is the conversation's state (a subagent has origin "subagent"); `set` moves its preset
 * the way dsh's permissionPresets.set does (full access, approval "ask" except for danger-full-access).
 */
function load(envVars: Record<string, string>, session: { mode?: string; policy?: string; preset?: string; origin?: string } = {}) {
  process.env = { ...saved, DSHM_WORKSPACE: WS, HOME: "/data/user/0/app.dshmobile/files/home", TMPDIR: "/data/user/0/app.dshmobile/cache/tmp", DSH_HOME, ...envVars };
  let listener: Listener | undefined;
  let options: unknown;
  const state = { mode: session.mode ?? "danger-full-access", policy: session.policy ?? "ask", preset: session.preset ?? "dshm-guarded" };
  const set = vi.fn((_session: unknown, preset: string) => {
    state.preset = preset; state.mode = "danger-full-access"; state.policy = preset === "danger-full-access" ? "never" : "ask";
  });
  const services: Record<string, unknown> = {
    sandboxPolicy: { resolve: () => ({ mode: state.mode }) },
    approval: { effectivePolicy: () => state.policy },
    permissionPresets: { current: () => state.preset, set },
  };
  const ctx = {
    logger: { info: vi.fn(), warn: vi.fn() },
    get: (n: string) => services[n],
    on: (_event: string, l: Listener, o: unknown) => { listener = l; options = o; },
  };
  plugin.apply(ctx as never);
  const next = vi.fn(async () => ({ kind: "allow" }));
  const dshSession = session.origin === undefined ? {} : { header: { origin: session.origin } };
  const run = (tool: string, args: unknown) => listener!({ name: tool, arguments: args, agent: { session: dshSession } }, next);
  return { run, next, options, ctx, listener, set, state };
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
    const child = load({ DSHM_GUARD_LEVEL: "ask" }, { policy: "never", origin: "subagent" });
    expect((await child.run("bash", { command: "mkdir x && echo hi > x/a" })).kind).toBe("allow");
    expect((await child.run("write", { file_path: "a.txt", content: "x" })).kind).toBe("allow");
    const refused = await child.run("bash", { command: "git push" });
    expect(refused.kind).toBe("deny");
    expect(refused.reason).toContain("lead agent");
    expect((await load({ DSHM_GUARD_LEVEL: "full" }, { policy: "never", origin: "subagent" }).run("bash", { command: "git push" })).kind).toBe("allow");
    // A real subagent is never moved to another preset.
    const smart = load({ DSHM_AUTO_REVIEW: "0" }, { policy: "never", preset: "danger-full-access", origin: "subagent" });
    expect((await smart.run("bash", { command: "git push" })).reason).toContain("subagent cannot ask");
    expect(smart.set).not.toHaveBeenCalled();
  });

  it("leaves a subagent on one of dsh's sandboxed modes to dsh's own rules", async () => {
    expect(await load({}, { mode: "workspace-write", preset: "custom", origin: "subagent" }).run("bash", { command: "rm -rf /" })).toEqual({ kind: "allow" });
  });

  it("under Auto review the reviewer judges, but the always-refused list stays refused", async () => {
    const auto = load({ DSHM_AUTO_REVIEW: "1" }, { preset: "auto" });
    const deleteApp = await auto.run("bash", { command: `rm -rf ${DSH_HOME}` });
    expect(deleteApp.kind).toBe("deny");
    expect(deleteApp.reason).toContain("(delete-app)");
    // What the level would ask about goes to the reviewer: the guard passes it on.
    expect(await auto.run("bash", { command: "git push" })).toEqual({ kind: "allow" });
    expect(await auto.run("write", { file_path: "/sdcard/a.txt", content: "x" })).toEqual({ kind: "allow" });
    expect(auto.next).toHaveBeenCalledTimes(2);
    // An older app that does not say whether Auto review is on: the conversation stays on "auto".
    const older = load({}, { preset: "auto" });
    expect(await older.run("bash", { command: "git push" })).toEqual({ kind: "allow" });
    expect(older.set).not.toHaveBeenCalled();
  });

  describe("a top-level conversation is put on its level's preset at tool time (reminders and helpers never pass the app's send)", () => {
    it("one on danger-full-access at 一般 is moved to dshm-guarded and asks instead of being refused", async () => {
      const g = load({ DSHM_GUARD_LEVEL: "smart", DSHM_AUTO_REVIEW: "0" }, { policy: "never", preset: "danger-full-access" });
      const v = await g.run("bash", { command: "git push" });
      expect(v.kind).toBe("ask");
      expect(g.set).toHaveBeenCalledWith(expect.anything(), "dshm-guarded");
      // Ordinary work still just runs, and the move happens once.
      expect(await g.run("bash", { command: "ls" })).toEqual({ kind: "allow" });
      expect(g.set).toHaveBeenCalledTimes(1);
    });
    it("with Auto review on it is moved to auto and the reviewer takes over from the next call", async () => {
      const g = load({ DSHM_GUARD_LEVEL: "smart", DSHM_AUTO_REVIEW: "1" }, { policy: "never", preset: "danger-full-access" });
      // dsh's Auto review may already have passed this call on while it was still on danger-full-access: the level judges it.
      const first = await g.run("bash", { command: "git push" });
      expect(first.kind).toBe("ask");
      expect(g.set).toHaveBeenCalledWith(expect.anything(), "auto");
      // Ordinary work on that call still runs without a question.
      const g1 = load({ DSHM_GUARD_LEVEL: "smart", DSHM_AUTO_REVIEW: "1" }, { policy: "never", preset: "danger-full-access" });
      expect(await g1.run("bash", { command: "ls" })).toEqual({ kind: "allow" });
      // Now on auto: the guard passes the next call on to the reviewer.
      g.next.mockClear();
      expect(await g.run("bash", { command: "git push" })).toEqual({ kind: "allow" });
      expect(g.next).toHaveBeenCalledTimes(1);
      expect(g.set).toHaveBeenCalledTimes(1);
      // The always-refused list holds from the very first call on auto.
      const g2 = load({ DSHM_GUARD_LEVEL: "ask", DSHM_AUTO_REVIEW: "1" }, { preset: "dshm-guarded" });
      expect((await g2.run("bash", { command: `rm -rf ${DSH_HOME}` })).kind).toBe("deny");
      expect(g2.set).toHaveBeenCalledWith(expect.anything(), "auto");
    });
    it("at 全部放行 a conversation left on auto is moved to danger-full-access and nothing reviews or asks", async () => {
      const g = load({ DSHM_GUARD_LEVEL: "full", DSHM_AUTO_REVIEW: "0" }, { preset: "auto" });
      expect(await g.run("bash", { command: "rm -rf /sdcard/DCIM" })).toEqual({ kind: "allow" });
      expect(g.set).toHaveBeenCalledWith(expect.anything(), "danger-full-access");
      expect(g.state.policy).toBe("never");
    });
    it("an old workspace-write conversation (preset custom) is moved to dshm-guarded and judged by the guard", async () => {
      const g = load({ DSHM_GUARD_LEVEL: "smart", DSHM_AUTO_REVIEW: "0" }, { mode: "workspace-write", preset: "custom" });
      expect((await g.run("bash", { command: "rm -rf /sdcard/DCIM" })).kind).toBe("ask");
      expect(g.set).toHaveBeenCalledWith(expect.anything(), "dshm-guarded");
      expect(await g.run("bash", { command: "mkdir x" })).toEqual({ kind: "allow" });
    });
    it("a fork (a parent but not a subagent) counts as the owner's own conversation", async () => {
      const g = load({ DSHM_AUTO_REVIEW: "0" }, { policy: "never", preset: "danger-full-access", origin: "fork" });
      expect((await g.run("bash", { command: "git push" })).kind).toBe("ask");
    });
    it("when dsh cannot move it, the refusal does not call it a subagent", async () => {
      const g = load({ DSHM_AUTO_REVIEW: "0" }, { policy: "never", preset: "danger-full-access" });
      g.set.mockImplementation(() => { throw new Error("unknown preset"); });
      const v = await g.run("bash", { command: "git push" });
      expect(v.kind).toBe("deny");
      expect(v.reason).toContain("這個對話目前不能詢問你");
      expect(v.reason).not.toContain("subagent");
    });
  });

  it("chooses the preset by the same rule as the app", () => {
    expect(plugin.wantedPreset("full", true, "auto")).toBe("danger-full-access");
    expect(plugin.wantedPreset("full", false, "dshm-guarded")).toBe("danger-full-access");
    expect(plugin.wantedPreset("smart", true, "dshm-guarded")).toBe("auto");
    expect(plugin.wantedPreset("ask", false, "auto")).toBe("dshm-guarded");
    expect(plugin.wantedPreset("smart", undefined, "auto")).toBeUndefined();
    expect(plugin.wantedPreset("full", undefined, "auto")).toBeUndefined();
    expect(plugin.wantedPreset("smart", undefined, "danger-full-access")).toBe("dshm-guarded");
    expect(plugin.readAutoReview({ DSHM_AUTO_REVIEW: "1" })).toBe(true);
    expect(plugin.readAutoReview({ DSHM_AUTO_REVIEW: "0" })).toBe(false);
    expect(plugin.readAutoReview({})).toBeUndefined();
  });

  it("reads its folders from the environment", () => {
    const same = (p: string) => p;
    expect(plugin.readEnv({ DSHM_WORKSPACE: "/w/", TMPDIR: "/t/", HOME: "/h", DSH_HOME: "/h/.dsh", DSHM_GUARD_PROTECTED: "/opt/dsh:/opt/dshm:relative", DSHM_GUARD_LEVEL: "ask" }, same))
      .toEqual({ level: "ask", workspace: "/w", temp: ["/t", "/tmp"], protectedRoots: ["/h/.dsh", "/opt/dsh", "/opt/dshm"], home: "/h" });
    expect(plugin.readEnv({ DSHM_WORKSPACE: "relative" })).toBeUndefined();
  });

  it("also knows the folders under their link-resolved names (/data/user/0 is a link to /data/data on the phone)", () => {
    const real = (p: string) => (p.startsWith("/data/user/0/") ? p.replace("/data/user/0/", "/data/data/") : p === "/missing" ? undefined : p);
    const e = plugin.readEnv({ DSHM_WORKSPACE: WS, DSH_HOME, TMPDIR: "/data/user/0/app.dshmobile/cache/tmp", DSHM_GUARD_PROTECTED: "/missing", DSHN_REMAP: `/root/workspace=${WS}` }, real);
    // The names the app gave come first; one pair covers every folder under the link.
    expect(e?.aliases).toEqual([["/root/workspace", WS], ["/data/data", "/data/user/0"]]);
    expect(plugin.linkPair("/data/data/app.dshmobile/files/userdata/workspace", "/data/user/0/app.dshmobile/files/userdata/workspace")).toEqual(["/data/data", "/data/user/0"]);
    // A link at the folder itself pairs the folders, and neither side is ever the root.
    expect(plugin.linkPair("/b/real", "/a/ws")).toEqual(["/b/real", "/a/ws"]);
    expect(plugin.linkPair("/private/tmp", "/tmp")).toEqual(["/private/tmp", "/tmp"]);
    // With no links (or none readable), nothing is added.
    expect(plugin.readEnv({ DSHM_WORKSPACE: "/w" }, () => undefined)?.aliases).toBeUndefined();
  });

  it("natively, a write under the workspace's link-resolved name runs and dsh's home under it is refused", async () => {
    const real = (p: string) => p.replace("/data/user/0/", "/data/data/");
    const e = plugin.readEnv({ DSHM_WORKSPACE: WS, DSH_HOME, DSHM_GUARD_LEVEL: "full" }, real)!;
    expect(classify("bash", { command: `rm -rf ${real(DSH_HOME)}` }, e)).toMatchObject({ kind: "deny", why: "delete-app" });
    expect(classify("write", { file_path: `${real(WS)}/notes.md`, content: "x" }, { ...e, level: "smart" }).kind).toBe("allow");
  });

  it("reads the folders' other names: native mode's DSHN_REMAP and proot's DSHM_GUARD_ALIASES", () => {
    const native = plugin.readEnv({ DSHM_WORKSPACE: WS, DSHN_REMAP: `/root/workspace=${WS};/root/.dsh/=${DSH_HOME};bad;relative=/x` });
    expect(native?.aliases).toEqual([["/root/workspace", WS], ["/root/.dsh", DSH_HOME]]);
    const proot = plugin.readEnv({ DSHM_WORKSPACE: "/root/workspace", DSHM_GUARD_ALIASES: `${WS}=/root/workspace` });
    expect(proot?.aliases).toEqual([[WS, "/root/workspace"]]);
  });

  it("in native mode, a write under the proot name of the workspace runs", async () => {
    const g = load({ DSHN_REMAP: `/root/workspace=${WS};/root/.dsh=${DSH_HOME}` });
    expect(await g.run("write", { file_path: "/root/workspace/notes.md", content: "x" })).toEqual({ kind: "allow" });
    expect((await g.run("bash", { command: "rm -rf /root/.dsh" })).kind).toBe("deny");
  });
});
