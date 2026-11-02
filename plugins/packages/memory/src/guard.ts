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

/** Path as dsh's router would see it: no query, decoded, dot segments and duplicate slashes folded. */
function normalizedPath(url: string | undefined): string {
  let path = (url ?? "/").split(/[?#]/, 1)[0] || "/";
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
 * `connection/request` is a waterfall every authenticated /api HTTP request
 * passes before its body is read; refuse the credential-writing RPCs here.
 */
export function registerCredentialGuard(ctx: GuardContext): void {
  ctx.on("connection/request", async (req, res, next) => {
    if (BLOCKED.has(normalizedPath(req.url))) {
      res.writeHead(403, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
      res.end(JSON.stringify({ ok: false, error: "dshm: keys are managed by the app" }));
      return;
    }
    return next();
  });
}
