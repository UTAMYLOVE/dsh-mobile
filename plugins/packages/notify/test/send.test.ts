import { createServer, type IncomingMessage, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NotifyError, sendNotify } from "../src/send.js";

interface Seen {
  method: string | undefined;
  url: string | undefined;
  contentType: string | undefined;
  body: unknown;
}

const servers: Server[] = [];

afterEach(async () => {
  vi.useRealTimers();
  for (const s of servers.splice(0)) {
    s.closeAllConnections();
    await new Promise<void>((resolve) => s.close(() => resolve()));
  }
});

async function readBody(req: IncomingMessage): Promise<string> {
  let raw = "";
  for await (const chunk of req) raw += chunk;
  return raw;
}

/** A real HTTP gateway; `status` is what it answers with. Returns the base URL (token path included) and what it saw. */
async function fakeGateway(status = 200): Promise<{ gateway: string; seen: Seen[] }> {
  const seen: Seen[] = [];
  const server = createServer(async (req, res) => {
    const raw = await readBody(req);
    seen.push({
      method: req.method,
      url: req.url,
      contentType: req.headers["content-type"],
      body: JSON.parse(raw || "null"),
    });
    res.writeHead(status, { "content-type": "application/json" });
    res.end(status === 200 ? '{"ok":true}' : '{"ok":false}');
  });
  servers.push(server);
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  return { gateway: `http://127.0.0.1:${(server.address() as AddressInfo).port}/s3cret-token`, seen };
}

describe("sendNotify against a real gateway", () => {
  it("POSTs JSON to <gateway>/notify with the title, body and sessionId", async () => {
    const { gateway, seen } = await fakeGateway();
    await sendNotify(gateway, { title: "Build done", body: "All green", sessionId: "sess-1" });
    expect(seen).toEqual([
      {
        method: "POST",
        url: "/s3cret-token/notify",
        contentType: "application/json",
        body: { title: "Build done", body: "All green", sessionId: "sess-1" },
      },
    ]);
  });

  it("omits sessionId when none is given", async () => {
    const { gateway, seen } = await fakeGateway();
    await sendNotify(gateway, { title: "t", body: "b" });
    expect(seen[0]!.body).toEqual({ title: "t", body: "b" });
  });

  it("tolerates a trailing slash on the gateway", async () => {
    const { gateway, seen } = await fakeGateway();
    await sendNotify(`${gateway}/`, { title: "t", body: "b" });
    expect(seen[0]!.url).toBe("/s3cret-token/notify");
  });

  it("throws NotifyError carrying the status code but not the gateway URL on a 500", async () => {
    const { gateway } = await fakeGateway(500);
    const error = await sendNotify(gateway, { title: "t", body: "b" }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(NotifyError);
    const message = (error as NotifyError).message;
    expect(message).toContain("500");
    expect(message).not.toContain("s3cret-token");
    expect(message).not.toContain("127.0.0.1");
  });

  it("throws NotifyError for other non-2xx statuses (404, 3xx)", async () => {
    for (const status of [404, 302]) {
      const { gateway } = await fakeGateway(status);
      await expect(sendNotify(gateway, { title: "t", body: "b" })).rejects.toThrow(new RegExp(String(status)));
    }
  });

  it("wraps a network error in NotifyError without leaking the gateway URL", async () => {
    const { gateway } = await fakeGateway();
    const dead = gateway.replace(/:\d+\//, ":1/"); // port 1: connection refused
    const error = await sendNotify(dead, { title: "t", body: "b" }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(NotifyError);
    expect((error as NotifyError).message).not.toContain("s3cret-token");
  });
});

describe("sendNotify truncation", () => {
  it("cuts a long title to 60 code points including the ellipsis", async () => {
    const { gateway, seen } = await fakeGateway();
    await sendNotify(gateway, { title: "a".repeat(100), body: "b" });
    const title = (seen[0]!.body as { title: string }).title;
    expect(Array.from(title)).toHaveLength(60);
    expect(title).toBe(`${"a".repeat(59)}…`);
  });

  it("never splits a surrogate pair at the cut", async () => {
    const { gateway, seen } = await fakeGateway();
    await sendNotify(gateway, { title: "😀".repeat(100), body: "🎉".repeat(600) });
    const { title, body } = seen[0]!.body as { title: string; body: string };
    expect(Array.from(title)).toHaveLength(60);
    expect(title).toBe(`${"😀".repeat(59)}…`);
    expect(Array.from(body)).toHaveLength(500);
    expect(body).toBe(`${"🎉".repeat(499)}…`);
    expect(Buffer.from(title, "utf8").toString("utf8")).toBe(title); // valid UTF-8, no lone surrogate
  });

  it("cuts the body to 500 code points", async () => {
    const { gateway, seen } = await fakeGateway();
    await sendNotify(gateway, { title: "t", body: "x".repeat(501) });
    const body = (seen[0]!.body as { body: string }).body;
    expect(Array.from(body)).toHaveLength(500);
    expect(body.endsWith("…")).toBe(true);
  });

  it("leaves text at exactly the limit untouched", async () => {
    const { gateway, seen } = await fakeGateway();
    await sendNotify(gateway, { title: "😀".repeat(60), body: "x".repeat(500) });
    const { title, body } = seen[0]!.body as { title: string; body: string };
    expect(title).toBe("😀".repeat(60));
    expect(body).toBe("x".repeat(500));
  });
});

describe("sendNotify with an injected fetch", () => {
  it("passes an abort signal and gives up after 10 seconds", async () => {
    vi.useFakeTimers();
    let signal: AbortSignal | undefined;
    const hang = vi.fn((_url: unknown, init?: RequestInit) => {
      signal = init?.signal ?? undefined;
      return new Promise<Response>((_resolve, reject) => {
        signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
      });
    });
    const pending = sendNotify("http://gw.invalid/tok", { title: "t", body: "b" }, hang as unknown as typeof fetch);
    const assertion = expect(pending).rejects.toThrow(/timed out/);
    await vi.advanceTimersByTimeAsync(9_999);
    expect(signal?.aborted).toBe(false);
    await vi.advanceTimersByTimeAsync(2);
    await assertion;
    expect(signal?.aborted).toBe(true);
  });

  it("wraps a thrown error whose message contains the URL", async () => {
    const boom = (async () => {
      throw new TypeError("fetch failed for http://gw.invalid/s3cret-token/notify");
    }) as unknown as typeof fetch;
    const error = await sendNotify("http://gw.invalid/s3cret-token", { title: "t", body: "b" }, boom).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(NotifyError);
    expect((error as NotifyError).message).not.toContain("s3cret-token");
  });
});
