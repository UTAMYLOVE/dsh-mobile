import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { afterEach, describe, expect, it } from "vitest";
import { requireAuth } from "../src/index.js";

type Ctx = Parameters<typeof requireAuth>[0];

let server: Server | undefined;

async function start(ctx: Ctx, onHandler: () => void): Promise<string> {
  server = createServer((req, res) => {
    if (!requireAuth(ctx, req, res)) return;
    onHandler();
    res.writeHead(200, { "content-type": "text/plain" });
    res.end("handler body");
  });
  await new Promise<void>((resolve) => server!.listen(0, "127.0.0.1", resolve));
  return `http://127.0.0.1:${(server.address() as AddressInfo).port}/dshm/x`;
}

const withRejection = (code: number | undefined): Ctx => ({
  get: () => ({ requestRejection: () => code }),
});

afterEach(async () => {
  const s = server;
  server = undefined;
  if (s) await new Promise<void>((resolve) => s.close(() => resolve()));
});

describe("requireAuth", () => {
  it("unauthenticated plugin route gets 401 and handler body never runs", async () => {
    let ran = false;
    const url = await start(withRejection(401), () => (ran = true));
    const res = await fetch(url);
    expect(res.status).toBe(401);
    expect(res.headers.get("cache-control")).toBe("no-store");
    expect(res.headers.get("content-type")).toContain("application/json");
    expect(await res.json()).toEqual({ ok: false, error: "dshm_unauthorized" });
    expect(ran).toBe(false);
  });

  it("wrong host gets 403", async () => {
    let ran = false;
    const url = await start(withRejection(403), () => (ran = true));
    const res = await fetch(url);
    expect(res.status).toBe(403);
    expect(await res.json()).toEqual({ ok: false, error: "dshm_unauthorized" });
    expect(ran).toBe(false);
  });

  it("authenticated request passes through", async () => {
    let ran = false;
    const url = await start(withRejection(undefined), () => (ran = true));
    const res = await fetch(url);
    expect(res.status).toBe(200);
    expect(await res.text()).toBe("handler body");
    expect(ran).toBe(true);
  });

  it("fails closed with 503 when connection service is missing", async () => {
    let ran = false;
    const url = await start({ get: () => undefined }, () => (ran = true));
    const res = await fetch(url);
    expect(res.status).toBe(503);
    expect(res.headers.get("cache-control")).toBe("no-store");
    expect(ran).toBe(false);
  });

  it("passes the incoming request to requestRejection", async () => {
    let seenUrl: string | undefined;
    const ctx: Ctx = {
      get: () => ({
        requestRejection: (req) => {
          seenUrl = req.url;
          return undefined;
        },
      }),
    };
    const url = await start(ctx, () => {});
    await fetch(url);
    expect(seenUrl).toBe("/dshm/x");
  });
});
