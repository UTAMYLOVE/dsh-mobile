import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as plugin from "../src/index.js";

type Handler = (req: IncomingMessage, res: ServerResponse) => unknown;
type Tool = { name: string; description: string; execute(args: unknown, exec: unknown): Promise<unknown> };

function makeCtx(options: { rejection?: number; webServer?: boolean; connection?: boolean } = {}) {
  const tools = new Map<string, Tool>();
  const routes = new Map<string, Handler>();
  const logger = { info: vi.fn(), warn: vi.fn(), error: vi.fn() };
  const services: Record<string, unknown> = {};
  if (options.connection !== false) services.connection = { requestRejection: () => options.rejection };
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
  };
  return { ctx, tools, routes, logger };
}

const servers: Server[] = [];

afterEach(async () => {
  delete process.env.DSHM_GATEWAY;
  for (const s of servers.splice(0)) {
    s.closeAllConnections();
    await new Promise<void>((resolve) => s.close(() => resolve()));
  }
});

async function listen(handler: (req: IncomingMessage, res: ServerResponse) => unknown): Promise<string> {
  const server = createServer((req, res) => void handler(req, res));
  servers.push(server);
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  return `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
}

async function serveRoutes(routes: Map<string, Handler>): Promise<string> {
  return listen((req, res) => {
    const handler = routes.get(new URL(req.url ?? "/", "http://x").pathname);
    if (!handler) return res.writeHead(404).end();
    return handler(req, res);
  });
}

/** A real gateway that records each notification and answers with `status`. */
async function fakeGateway(status = 200) {
  const seen: { url: string | undefined; body: Record<string, unknown> }[] = [];
  const origin = await listen(async (req, res) => {
    let raw = "";
    for await (const chunk of req) raw += chunk;
    seen.push({ url: req.url, body: JSON.parse(raw) });
    res.writeHead(status, { "content-type": "application/json" }).end(status === 200 ? '{"ok":true}' : "{}");
  });
  return { gateway: `${origin}/tok-123`, seen };
}

const mount = (fake: ReturnType<typeof makeCtx>) => plugin.apply(fake.ctx as never);
const tool = (fake: ReturnType<typeof makeCtx>): Tool => fake.tools.get("notify_user")!;
const exec = (id: unknown) => ({ agent: id === undefined ? undefined : { id }, signal: new AbortController().signal });

describe("plugin shape", () => {
  it("exports the dsh plugin contract and registers the tool and health route", () => {
    process.env.DSHM_GATEWAY = "http://127.0.0.1:9/tok";
    expect(plugin.name).toBe("dshm-notify");
    expect(plugin.inject).not.toContain("webServer");
    const fake = makeCtx();
    mount(fake);
    expect([...fake.tools.keys()]).toEqual(["notify_user"]);
    expect([...fake.routes.keys()]).toEqual(["/dshm/health/notify"]);
    const description = tool(fake).description;
    expect(description).toContain("手機");
    expect(description).toMatch(/only|僅|只在/i);
  });

  it("the tool declares title and body as required strings", () => {
    process.env.DSHM_GATEWAY = "http://127.0.0.1:9/tok";
    const fake = makeCtx();
    mount(fake);
    expect((tool(fake) as unknown as { parameters: unknown }).parameters).toMatchObject({
      type: "object",
      properties: { title: { type: "string" }, body: { type: "string" } },
      required: ["title", "body"],
    });
  });

  it("without webServer the tool still registers and no route is attempted", () => {
    process.env.DSHM_GATEWAY = "http://127.0.0.1:9/tok";
    const fake = makeCtx({ webServer: false });
    mount(fake);
    expect(fake.tools.size).toBe(1);
    expect(fake.routes.size).toBe(0);
  });
});

describe("notify_user", () => {
  it("sends the notification with the current session id and returns \"sent\"", async () => {
    const { gateway, seen } = await fakeGateway();
    process.env.DSHM_GATEWAY = gateway;
    const fake = makeCtx();
    mount(fake);
    const result = await tool(fake).execute({ title: "Done", body: "Deploy finished" }, exec("session-42"));
    expect(result).toBe("sent");
    expect(seen).toEqual([{ url: "/tok-123/notify", body: { title: "Done", body: "Deploy finished", sessionId: "session-42" } }]);
  });

  it("omits sessionId when the execution has no agent", async () => {
    const { gateway, seen } = await fakeGateway();
    process.env.DSHM_GATEWAY = gateway;
    const fake = makeCtx();
    mount(fake);
    expect(await tool(fake).execute({ title: "t", body: "b" }, exec(undefined))).toBe("sent");
    expect(await tool(fake).execute({ title: "t", body: "b" }, undefined)).toBe("sent");
    expect(await tool(fake).execute({ title: "t", body: "b" }, exec(123))).toBe("sent");
    expect(seen.map((s) => s.body)).toEqual([
      { title: "t", body: "b" },
      { title: "t", body: "b" },
      { title: "t", body: "b" },
    ]);
  });

  it("truncates over-long text through sendNotify", async () => {
    const { gateway, seen } = await fakeGateway();
    process.env.DSHM_GATEWAY = gateway;
    const fake = makeCtx();
    mount(fake);
    await tool(fake).execute({ title: "😀".repeat(80), body: "x".repeat(900) }, exec("s"));
    expect(Array.from(seen[0]!.body.title as string)).toHaveLength(60);
    expect(Array.from(seen[0]!.body.body as string)).toHaveLength(500);
  });

  it("returns a readable error string, never throws, and never leaks the token when the gateway fails", async () => {
    const { gateway } = await fakeGateway(500);
    process.env.DSHM_GATEWAY = gateway;
    const fake = makeCtx();
    mount(fake);
    const result = await tool(fake).execute({ title: "t", body: "b" }, exec("s"));
    expect(typeof result).toBe("string");
    expect(result).toContain("500");
    expect(result).not.toBe("sent");
    expect(result).not.toContain("tok-123");
    for (const log of [fake.logger.info, fake.logger.warn, fake.logger.error]) {
      expect(JSON.stringify(log.mock.calls)).not.toContain("tok-123");
    }
  });

  it("returns an error string when the gateway is unreachable", async () => {
    process.env.DSHM_GATEWAY = "http://127.0.0.1:1/tok-123";
    const fake = makeCtx();
    mount(fake);
    const result = await tool(fake).execute({ title: "t", body: "b" }, exec("s"));
    expect(result).toMatch(/failed/);
    expect(result).not.toContain("tok-123");
  });

  it("rejects missing or non-string title/body with a readable string", async () => {
    const { gateway, seen } = await fakeGateway();
    process.env.DSHM_GATEWAY = gateway;
    const fake = makeCtx();
    mount(fake);
    for (const args of [undefined, null, {}, { title: "t" }, { body: "b" }, { title: 1, body: "b" }, { title: "t", body: {} }, { title: "  ", body: "b" }]) {
      const result = await tool(fake).execute(args, exec("s"));
      expect(result).toMatch(/title|body/);
      expect(result).not.toBe("sent");
    }
    expect(seen).toHaveLength(0);
  });

  it("without DSHM_GATEWAY the tool still registers, answers \"notifications unavailable\" and warns once", async () => {
    delete process.env.DSHM_GATEWAY;
    const fake = makeCtx();
    mount(fake);
    expect(fake.tools.has("notify_user")).toBe(true);
    expect(fake.logger.warn).toHaveBeenCalledTimes(1);
    expect(fake.logger.warn).toHaveBeenCalledWith(expect.stringContaining("DSHM_GATEWAY"));
    expect(await tool(fake).execute({ title: "t", body: "b" }, exec("s"))).toBe("notifications unavailable");
    expect(await tool(fake).execute({ title: "t", body: "b" }, exec("s"))).toBe("notifications unavailable");
    expect(fake.logger.warn).toHaveBeenCalledTimes(1);
  });

  it("treats an empty DSHM_GATEWAY as unset", async () => {
    process.env.DSHM_GATEWAY = "";
    const fake = makeCtx();
    mount(fake);
    expect(await tool(fake).execute({ title: "t", body: "b" }, exec("s"))).toBe("notifications unavailable");
  });

  it("never logs the gateway on load", () => {
    process.env.DSHM_GATEWAY = "http://127.0.0.1:9/tok-123";
    const fake = makeCtx();
    mount(fake);
    for (const log of [fake.logger.info, fake.logger.warn, fake.logger.error]) {
      expect(JSON.stringify(log.mock.calls)).not.toContain("tok-123");
    }
  });
});

describe("GET /dshm/health/notify", () => {
  it("is 401 when unauthenticated (requireAuth)", async () => {
    process.env.DSHM_GATEWAY = "http://127.0.0.1:9/tok";
    const fake = makeCtx({ rejection: 401 });
    mount(fake);
    const base = await serveRoutes(fake.routes);
    const res = await fetch(`${base}/dshm/health/notify`);
    expect(res.status).toBe(401);
    expect(await res.json()).toEqual({ ok: false, error: "dshm_unauthorized" });
  });

  it("fails closed with 503 when the connection service is missing", async () => {
    process.env.DSHM_GATEWAY = "http://127.0.0.1:9/tok";
    const fake = makeCtx({ connection: false });
    mount(fake);
    const base = await serveRoutes(fake.routes);
    expect((await fetch(`${base}/dshm/health/notify`)).status).toBe(503);
  });

  it("is {\"ok\":true} when authenticated, also without a gateway configured", async () => {
    for (const gateway of ["http://127.0.0.1:9/tok", undefined]) {
      if (gateway) process.env.DSHM_GATEWAY = gateway;
      else delete process.env.DSHM_GATEWAY;
      const fake = makeCtx();
      mount(fake);
      const base = await serveRoutes(fake.routes);
      const res = await fetch(`${base}/dshm/health/notify`);
      expect(res.status).toBe(200);
      expect(await res.json()).toEqual({ ok: true });
    }
  });

  it("answers HEAD, and 405 for other methods", async () => {
    process.env.DSHM_GATEWAY = "http://127.0.0.1:9/tok";
    const fake = makeCtx();
    mount(fake);
    const base = await serveRoutes(fake.routes);
    expect((await fetch(`${base}/dshm/health/notify`, { method: "HEAD" })).status).toBe(200);
    const post = await fetch(`${base}/dshm/health/notify`, { method: "POST" });
    expect(post.status).toBe(405);
    expect(post.headers.get("allow")).toBe("GET, HEAD");
  });

  it("checks auth before the method (an unauthenticated POST is 401, not 405)", async () => {
    process.env.DSHM_GATEWAY = "http://127.0.0.1:9/tok";
    const fake = makeCtx({ rejection: 401 });
    mount(fake);
    const base = await serveRoutes(fake.routes);
    expect((await fetch(`${base}/dshm/health/notify`, { method: "POST" })).status).toBe(401);
  });
});
