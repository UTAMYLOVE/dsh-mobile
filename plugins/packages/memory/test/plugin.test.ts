import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { connect, type AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as plugin from "../src/index.js";

type Handler = (req: IncomingMessage, res: ServerResponse) => unknown;
type Tool = { name: string; execute(args: unknown, exec: unknown): Promise<unknown> };

interface FakeOptions {
  rejection?: number;
  webServer?: boolean;
  llmReply?: string | (() => Promise<string>);
  noLlm?: boolean;
}

function makeCtx(options: FakeOptions = {}) {
  const tools = new Map<string, Tool>();
  const routes = new Map<string, Handler>();
  const listeners = new Map<string, ((...args: any[]) => any)[]>();
  const contexts: { name: string; order: number; interpolate: boolean; text: () => string }[] = [];
  const logger = { info: vi.fn(), warn: vi.fn(), error: vi.fn() };
  const llmCalls: unknown[] = [];
  const llm = {
    prepareCall: vi.fn(async (config: Record<string, unknown>) => ({
      config,
      stream: async function* (request: unknown) {
        llmCalls.push(request);
        const reply = options.llmReply ?? '{"promote":[],"remind":[]}';
        const text = typeof reply === "function" ? await reply() : reply;
        yield { type: "text-delta", index: 0, text };
        yield { type: "finish", reason: { kind: "stop" } };
      },
    })),
  };
  const services: Record<string, unknown> = {
    connection: { requestRejection: () => options.rejection },
    llm,
    agentDefaultModel: { currentSelection: () => ({ provider: "prov", model: "mod" }) },
  };
  if (options.noLlm) delete services.llm;
  if (options.webServer !== false) {
    services.webServer = {
      register: ({ path, handler }: { path: string; handler: Handler }) => {
        routes.set(path, handler);
        return () => routes.delete(path);
      },
    };
  }
  const ctx = {
    logger,
    get: (name: string) => services[name],
    effect: (fn: () => unknown) => void fn(),
    inject: (deps: string[], cb: (c: unknown) => void) => {
      if (deps.every((d) => services[d] !== undefined)) cb(ctx);
    },
    tools: { register: (t: Tool) => void tools.set(t.name, t) },
    systemPrompt: { context: (c: (typeof contexts)[number]) => void contexts.push(c) },
    on: (event: string, fn: (...args: any[]) => any) => {
      listeners.set(event, [...(listeners.get(event) ?? []), fn]);
    },
  };
  return { ctx, tools, routes, listeners, contexts, logger, llm, llmCalls };
}

let base: string;
let root: string;
const servers: Server[] = [];

beforeEach(() => {
  base = mkdtempSync(join(tmpdir(), "dshm-plugin-"));
  root = join(base, "ws");
  mkdirSync(root);
  process.env.DSHM_WORKSPACE = root;
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date(2026, 10, 5, 14, 5, 0));
});

afterEach(async () => {
  vi.useRealTimers();
  delete process.env.DSHM_WORKSPACE;
  for (const s of servers.splice(0)) {
    s.closeAllConnections();
    await new Promise<void>((resolve) => s.close(() => resolve()));
  }
  rmSync(base, { recursive: true, force: true });
});

async function serve(routes: Map<string, Handler>): Promise<string> {
  const server = createServer((req, res) => {
    const handler = routes.get(new URL(req.url ?? "/", "http://x").pathname);
    if (!handler) {
      res.writeHead(404).end();
      return;
    }
    void handler(req, res);
  });
  servers.push(server);
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  return `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
}

const mount = (fake: ReturnType<typeof makeCtx>) => plugin.apply(fake.ctx as never);
const writeNote = (date: string, text: string) => {
  mkdirSync(join(root, "memory"), { recursive: true });
  writeFileSync(join(root, "memory", `${date}.md`), text);
};

describe("plugin shape", () => {
  it("exports the dsh plugin contract and registers tools, context injection and routes", () => {
    expect(plugin.name).toBe("dshm-memory");
    expect(plugin.inject).not.toContain("webServer");
    const fake = makeCtx();
    mount(fake);
    expect([...fake.tools.keys()].sort()).toEqual(["memory_get", "memory_search", "memory_write"]);
    expect([...fake.routes.keys()].sort()).toEqual(["/dshm/health/memory", "/dshm/heartbeat"]);
    expect(fake.contexts).toHaveLength(1);
    expect(fake.contexts[0]).toMatchObject({ name: "dshm-memory", order: 9000, interpolate: false });
    writeNote("2026-11-04", "- yesterday");
    expect(fake.contexts[0]!.text()).toContain("## memory/2026-11-04.md");
  });

  it("without webServer the tools still register and no route is attempted", () => {
    const fake = makeCtx({ webServer: false });
    mount(fake);
    expect(fake.tools.size).toBe(3);
    expect(fake.routes.size).toBe(0);
  });

  it("without DSHM_WORKSPACE it warns and disables tools and routes but keeps the credential guard", () => {
    delete process.env.DSHM_WORKSPACE;
    const fake = makeCtx();
    mount(fake);
    expect(fake.logger.warn).toHaveBeenCalledWith(expect.stringContaining("DSHM_WORKSPACE"));
    expect(fake.tools.size).toBe(0);
    expect(fake.routes.size).toBe(0);
    expect(fake.contexts).toHaveLength(0);
    expect(fake.listeners.has("connection/request")).toBe(true);
  });

  it("a relative DSHM_WORKSPACE is refused, never resolved against cwd", () => {
    process.env.DSHM_WORKSPACE = "relative/dir";
    const fake = makeCtx();
    mount(fake);
    expect(fake.logger.warn).toHaveBeenCalled();
    expect(fake.tools.size).toBe(0);
  });
});

describe("routes", () => {
  it("routes reject unauthenticated requests via requireAuth", async () => {
    const fake = makeCtx({ rejection: 401 });
    mount(fake);
    writeNote("2026-11-04", "- needs dreaming");
    const url = await serve(fake.routes);
    const health = await fetch(`${url}/dshm/health/memory`);
    expect(health.status).toBe(401);
    expect(await health.json()).toEqual({ ok: false, error: "dshm_unauthorized" });
    const beat = await fetch(`${url}/dshm/heartbeat`, { method: "POST" });
    expect(beat.status).toBe(401);
    expect(fake.llm.prepareCall).not.toHaveBeenCalled();
    expect(fake.llmCalls).toHaveLength(0);
  });

  it("health returns ok when authenticated and rejects wrong methods", async () => {
    const fake = makeCtx();
    mount(fake);
    const url = await serve(fake.routes);
    const res = await fetch(`${url}/dshm/health/memory`);
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true });
    expect((await fetch(`${url}/dshm/health/memory`, { method: "POST" })).status).toBe(405);
    expect((await fetch(`${url}/dshm/heartbeat`)).status).toBe(405);
  });

  it("heartbeat returns promoted and reminders", async () => {
    const fake = makeCtx({
      llmReply: '```json\n{"promote":["uses pnpm"],"remind":[{"title":"Ann","body":"Friday"}]}\n```',
    });
    mount(fake);
    writeNote("2026-11-04", "- decided pnpm");
    writeNote("2026-11-05", "- today is excluded");
    const url = await serve(fake.routes);
    const res = await fetch(`${url}/dshm/heartbeat`, { method: "POST" });
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({
      promoted: ["uses pnpm"],
      reminders: [{ title: "Ann", body: "Friday" }],
    });
    expect(fake.llm.prepareCall).toHaveBeenCalledWith(
      expect.objectContaining({ provider: "prov", model: "mod" }),
      expect.anything(),
    );
    const request = JSON.stringify(fake.llmCalls[0]);
    expect(request).toContain("decided pnpm");
    expect(request).not.toContain("today is excluded");
    expect(readFileSync(join(root, "MEMORY.md"), "utf8")).toContain("### 2026-11-04 整理\n- uses pnpm");
    expect(JSON.parse(readFileSync(join(root, "memory", ".dream-state.json"), "utf8"))).toEqual({
      lastProcessedDate: "2026-11-04",
    });
  });

  it("heartbeat maps parse errors to 502 and other failures to 500 without leaking details", async () => {
    const fake = makeCtx({ llmReply: "not json at all" });
    mount(fake);
    writeNote("2026-11-04", "- x");
    const url = await serve(fake.routes);
    const parse = await fetch(`${url}/dshm/heartbeat`, { method: "POST" });
    expect(parse.status).toBe(502);
    expect(await parse.json()).toEqual({ error: "dream_parse" });

    const failing = makeCtx({
      llmReply: async () => {
        throw new Error("secret-token-xyz exploded");
      },
    });
    mount(failing);
    const server2 = await serve(failing.routes);
    const res = await fetch(`${server2}/dshm/heartbeat`, { method: "POST" });
    expect(res.status).toBe(500);
    const text = await res.text();
    expect(JSON.parse(text)).toEqual({ error: "dream_failed" });
    expect(text).not.toContain("secret-token");
    expect(failing.logger.warn).toHaveBeenCalled();
  });

  it("a second concurrent heartbeat gets 409 dream_busy", async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    const fake = makeCtx({
      llmReply: async () => {
        await gate;
        return '{"promote":[],"remind":[]}';
      },
    });
    mount(fake);
    writeNote("2026-11-04", "- x");
    const url = await serve(fake.routes);
    const first = fetch(`${url}/dshm/heartbeat`, { method: "POST" });
    await vi.waitFor(() => expect(fake.llm.prepareCall).toHaveBeenCalled());
    const second = await fetch(`${url}/dshm/heartbeat`, { method: "POST" });
    expect(second.status).toBe(409);
    expect(await second.json()).toEqual({ error: "dream_busy" });
    release();
    expect((await first).status).toBe(200);
    // the slot is free again afterwards
    expect((await fetch(`${url}/dshm/heartbeat`, { method: "POST" })).status).toBe(200);
  });

  it("heartbeat is 500 (not a crash) when the model service is unavailable", async () => {
    const fake = makeCtx({ noLlm: true });
    mount(fake);
    writeNote("2026-11-04", "- x");
    const url = await serve(fake.routes);
    const res = await fetch(`${url}/dshm/heartbeat`, { method: "POST" });
    expect(res.status).toBe(500);
    expect(await res.json()).toEqual({ error: "dream_failed" });
  });
});

describe("credential guard", () => {
  function fakeRes() {
    const res = { status: 0, headers: {} as Record<string, unknown>, body: "", ended: false };
    return Object.assign(res, {
      writeHead(status: number, headers?: Record<string, unknown>) {
        res.status = status;
        res.headers = headers ?? {};
        return res;
      },
      end(chunk?: string) {
        res.body = chunk ?? "";
        res.ended = true;
      },
    });
  }
  async function run(url: string, method = "POST") {
    const fake = makeCtx();
    mount(fake);
    const guard = fake.listeners.get("connection/request")![0]!;
    const res = fakeRes();
    const next = vi.fn(async () => "passed");
    const result = await guard({ url, method }, res, next);
    return { res, next, result };
  }

  it.each([
    "/api/credentials/set",
    "/api/credentials/unset",
    "/api/account/startSignIn",
    "/api/credentials/set?x=1",
    "/api/credentials/set/",
    "//api/credentials/set",
    "/api/credentials/%73et",
    "/api/foo/../credentials/set",
    "/API/Credentials/Set",
  ])("credential RPC %s is refused with 403", async (url) => {
    const { res, next } = await run(url);
    expect(res.status).toBe(403);
    expect(res.ended).toBe(true);
    expect(res.headers).toMatchObject({ "content-type": "application/json; charset=utf-8" });
    expect(JSON.parse(res.body)).toEqual({ ok: false, error: "dshm: keys are managed by the app" });
    expect(next).not.toHaveBeenCalled();
  });

  it.each(["/api/credentials/describe", "/api/workspace/create", "/", "/api/credentials/settings"])(
    "other path %s passes through to next()",
    async (url) => {
      const { res, next, result } = await run(url);
      expect(next).toHaveBeenCalledTimes(1);
      expect(result).toBe("passed");
      expect(res.ended).toBe(false);
    },
  );

  it("an undecodable path is passed on rather than crashing the guard", async () => {
    const { next } = await run("/api/%E0%A4%A");
    expect(next).toHaveBeenCalled();
  });
});

describe("credential guard over a real HTTP server (raw request targets)", () => {
  async function rawRequest(target: string): Promise<{ status: number; body: string; nextCalls: number }> {
    const fake = makeCtx();
    mount(fake);
    const guard = fake.listeners.get("connection/request")![0]!;
    let nextCalls = 0;
    const srv = createServer((req, res) => {
      void guard(req, res, async () => {
        nextCalls++;
        res.writeHead(200, { "content-type": "text/plain" }).end("passed");
      });
    });
    servers.push(srv);
    await new Promise<void>((resolve) => srv.listen(0, "127.0.0.1", resolve));
    const port = (srv.address() as AddressInfo).port;
    // A raw socket keeps the client from normalising the request target.
    const raw = await new Promise<string>((resolve, reject) => {
      const sock = connect(port, "127.0.0.1", () =>
        sock.write(`POST ${target} HTTP/1.0\r\nHost: 127.0.0.1\r\nContent-Length: 0\r\n\r\n`),
      );
      let data = "";
      sock.on("data", (c) => (data += c));
      sock.on("end", () => resolve(data));
      sock.on("error", reject);
    });
    return { status: Number(/^HTTP\/1\.1 (\d+)/.exec(raw)?.[1]), body: raw.split("\r\n\r\n")[1] ?? "", nextCalls };
  }

  it.each([
    "/api\\credentials\\set",
    "/api/credentials\\set",
    "/api\\..\\api/credentials/set",
    "/api%5ccredentials%5cset",
    "/api%5Ccredentials/set",
    "/API/Credentials/Set/",
  ])("raw POST %s is refused with 403 and next() is not called", async (target) => {
    const { status, body, nextCalls } = await rawRequest(target);
    expect(status).toBe(403);
    expect(JSON.parse(body)).toEqual({ ok: false, error: "dshm: keys are managed by the app" });
    expect(nextCalls).toBe(0);
  });

  it("a raw allowed path passes through", async () => {
    const { status, body, nextCalls } = await rawRequest("/api/credentials/describe");
    expect(status).toBe(200);
    expect(body).toBe("passed");
    expect(nextCalls).toBe(1);
  });
});

describe("compaction", () => {
  it("compaction/start appends a daily note line and never throws", async () => {
    const fake = makeCtx();
    mount(fake);
    const listener = fake.listeners.get("session/event")![0]!;
    await listener({ id: "s1" }, { type: "compaction/start" });
    await listener({ id: "s1" }, { type: "message/user" });
    expect(readFileSync(join(root, "memory", "2026-11-05.md"), "utf8")).toBe(
      "- 14:05 (compaction) 對話已壓縮，重要事項請以 memory_write 保存\n",
    );
    expect(fake.logger.warn).not.toHaveBeenCalled();
  });

  it("a write failure is only logged, and garbage events are ignored", async () => {
    const fake = makeCtx();
    mount(fake);
    writeFileSync(join(root, "memory"), "i am a file, so mkdir memory/ fails");
    const listener = fake.listeners.get("session/event")![0]!;
    await expect(listener({}, { type: "compaction/start" })).resolves.not.toThrow();
    expect(fake.logger.warn).toHaveBeenCalledTimes(1);
    expect(() => listener({}, undefined)).not.toThrow();
    expect(() => listener({}, null)).not.toThrow();
  });
});

describe("tools", () => {
  it("memory_write, memory_search and memory_get work end to end", async () => {
    const fake = makeCtx();
    mount(fake);
    const t = (n: string) => fake.tools.get(n)!;
    await t("memory_write").execute({ target: "daily", text: "bought oat milk" }, {});
    await t("memory_write").execute({ target: "long", text: "- user is vegetarian" }, {});
    const search = (await t("memory_search").execute({ query: "oat milk" }, {})) as {
      results: { path: string; line: number; text: string }[];
    };
    expect(search.results).toEqual([
      { path: "memory/2026-11-05.md", line: 1, text: "- 14:05 bought oat milk" },
    ]);
    expect(await t("memory_get").execute({ path: "MEMORY.md" }, {})).toContain("user is vegetarian");
    expect(await t("memory_get").execute({ path: "memory/2026-11-05.md", from: 1, lines: 1 }, {})).toBe(
      "- 14:05 bought oat milk",
    );
  });

  it("path errors become plain tool errors, and bad arguments are refused", async () => {
    const fake = makeCtx();
    mount(fake);
    const get = fake.tools.get("memory_get")!;
    const err = await get.execute({ path: "../../etc/passwd" }, {}).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(Error);
    expect((err as Error).name).toBe("Error");
    expect((err as Error).message).toMatch(/memory/);
    await expect(get.execute({}, {})).rejects.toThrow(/path/);
    await expect(fake.tools.get("memory_write")!.execute({ target: "nope", text: "x" }, {})).rejects.toThrow(
      /target/,
    );
    await expect(fake.tools.get("memory_search")!.execute({ query: 5 }, {})).rejects.toThrow(/query/);
  });
});
