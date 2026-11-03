import { posix } from "node:path";
import type { IncomingMessage, ServerResponse } from "node:http";

/**
 * Browser RPCs that would persist a secret into $DSH_HOME/.credentials.yaml
 * (settings key fields, DeepSeek account sign-in). The app owns every key.
 */
const BLOCKED = new Set(["/api/credentials/set", "/api/credentials/unset", "/api/account/startsignin"]);

type Next = () => unknown;

export interface GuardContext {
  on(
    event: "connection/request",
    listener: (req: IncomingMessage, res: ServerResponse, next: Next) => unknown,
  ): unknown;
}

function fold(path: string): string {
  try {
    path = decodeURIComponent(path);
  } catch {
    // keep the raw path; an undecodable one cannot name a blocked RPC
  }
  path = posix.normalize(`/${path}`).replace(/\/{2,}/g, "/");
  if (path.length > 1 && path.endsWith("/")) path = path.slice(0, -1);
  return path.toLowerCase();
}

/**
 * Candidate paths for one request target. The first is the path exactly as dsh
 * routes it: `new URL(req.url, base).pathname` (WHATWG parsing, which among
 * other things turns `\` into `/`). The second is a manual fold that also
 * covers forms the URL parser reads differently (e.g. `//api/...` as a host).
 */
function candidatePaths(url: string): string[] {
  const candidates: string[] = [];
  try {
    candidates.push(fold(new URL(url, "http://x").pathname));
  } catch {
    // unparseable target: dsh cannot route it either
  }
  candidates.push(fold(url.replace(/\\/g, "/").split(/[?#]/, 1)[0] || "/"));
  return candidates;
}

/** Defence in depth: backslash or an encoded backslash anywhere in an /api target. */
function isApiBackslashForm(url: string): boolean {
  if (!/\\|%5c/i.test(url)) return false;
  return candidatePaths(url).some((p) => p === "/api" || p.startsWith("/api/")) ||
    /^[/\\]+api(?:[/\\?#]|%5c|$)/i.test(url);
}

/**
 * `connection/request` is a waterfall every authenticated /api HTTP request
 * passes before its body is read; refuse the credential-writing RPCs here.
 */
export function registerCredentialGuard(ctx: GuardContext): void {
  ctx.on("connection/request", async (req, res, next) => {
    const url = req.url ?? "/";
    if (candidatePaths(url).some((p) => BLOCKED.has(p)) || isApiBackslashForm(url)) {
      res.writeHead(403, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
      res.end(JSON.stringify({ ok: false, error: "dshm: keys are managed by the app" }));
      return;
    }
    return next();
  });
}
