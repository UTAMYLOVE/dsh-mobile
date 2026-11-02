import type { IncomingMessage, ServerResponse } from "node:http";

/** The slice of dsh's `connection` service that plugin routes need. */
interface ConnectionService {
  requestRejection(req: IncomingMessage): number | undefined;
}

interface AuthContext {
  get(name: "connection"): ConnectionService | undefined;
}

function reject(res: ServerResponse, status: number): void {
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
  });
  res.end(JSON.stringify({ ok: false, error: "dshm_unauthorized" }));
}

/**
 * Guards a route registered with `ctx.webServer.register`, which dsh does not
 * protect itself. Call first in every handler and return when it yields false;
 * the response has then already been written. Fails closed (503) when the
 * connection service is unavailable.
 */
export function requireAuth(ctx: AuthContext, req: IncomingMessage, res: ServerResponse): boolean {
  const connection = ctx.get("connection");
  if (!connection) {
    reject(res, 503);
    return false;
  }
  const status = connection.requestRejection(req);
  if (status === undefined) return true;
  reject(res, status);
  return false;
}
