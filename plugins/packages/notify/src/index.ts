import type { IncomingMessage, ServerResponse } from "node:http";
import { requireAuth } from "@dshm/common";
import { NotifyError, sendNotify } from "./send.js";

export const name = "dshm-notify";
// webServer is deliberately absent: headless compositions have no such service
// and an injected-but-missing service leaves the plugin pending forever.
export const inject = ["tools"];

const UNAVAILABLE = "notifications unavailable";

/** The slice of the dsh (Cordis) context this plugin uses. */
interface PluginCtx {
  logger: { info(msg: string): void; warn(msg: string): void };
  get(name: string): any;
  effect(fn: () => unknown, name?: string): unknown;
  inject(deps: string[], callback: (ctx: PluginCtx) => void): unknown;
  tools: { register(tool: Record<string, unknown>): unknown };
}

function sendJson(res: ServerResponse, status: number, value: unknown, head = false): void {
  const body = JSON.stringify(value);
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(body),
    "cache-control": "no-store",
  });
  res.end(head ? undefined : body);
}

/**
 * The calling session, from the tool run context (`ToolRunContext.agent.id`,
 * `@deepseek-ai/dsh-tools` -> `Agent.id: SessionId` from `@deepseek-ai/dsh-agent`).
 * Absent for calls without a live agent; the notification is then sent without one.
 */
function sessionIdOf(exec: unknown): string | undefined {
  const id = (exec as { agent?: { id?: unknown } } | null | undefined)?.agent?.id;
  return typeof id === "string" && id !== "" ? id : undefined;
}

function nonEmpty(value: unknown): value is string {
  return typeof value === "string" && value.trim() !== "";
}

function registerTool(ctx: PluginCtx, gateway: string | undefined): void {
  ctx.tools.register({
    name: "notify_user",
    description:
      "傳一則手機通知給使用者 (send a phone notification to the user). Use it only when the user's attention is genuinely needed, " +
      "for example a long task has finished, or you are blocked and need an answer. Do not use it for routine progress updates. " +
      "Keep the title short (60 characters at most) and the body brief (500 characters at most); longer text is cut.",
    parameters: {
      type: "object",
      properties: {
        title: { type: "string", description: "Short notification title (max 60 characters)." },
        body: { type: "string", description: "Notification text (max 500 characters)." },
      },
      required: ["title", "body"],
    },
    output: { schema: { type: "string" }, render: (_args: unknown, value: string) => [{ type: "text", text: value }] },
    async execute(args: unknown, exec: unknown): Promise<string> {
      if (gateway === undefined) return UNAVAILABLE;
      const { title, body } = (typeof args === "object" && args !== null ? args : {}) as Record<string, unknown>;
      if (!nonEmpty(title)) return 'notify_user: "title" must be a non-empty string';
      if (!nonEmpty(body)) return 'notify_user: "body" must be a non-empty string';
      const sessionId = sessionIdOf(exec);
      try {
        await sendNotify(gateway, { title, body, ...(sessionId === undefined ? {} : { sessionId }) });
        return "sent";
      } catch (e) {
        // NotifyError messages are URL-free by construction; anything else is not echoed.
        return `notification failed: ${e instanceof NotifyError ? e.message : "unexpected error"}`;
      }
    },
  });
}

function registerRoutes(ctx: PluginCtx): void {
  const webServer = ctx.get("webServer");
  if (!webServer) return; // only reachable when called outside ctx.inject
  ctx.effect(
    () =>
      webServer.register({
        kind: "exact",
        path: "/dshm/health/notify",
        handler(req: IncomingMessage, res: ServerResponse) {
          if (!requireAuth(ctx, req, res)) return;
          if (req.method !== "GET" && req.method !== "HEAD") {
            res.writeHead(405, { allow: "GET, HEAD" }).end();
            return;
          }
          sendJson(res, 200, { ok: true }, req.method === "HEAD");
        },
      }),
    "dshm-notify.route-health",
  );
}

export function apply(ctx: PluginCtx): void {
  // DSHM_GATEWAY contains the access token: it is read once, never logged.
  const gateway = process.env.DSHM_GATEWAY || undefined;
  if (gateway === undefined) {
    ctx.logger.warn("[dshm-notify] DSHM_GATEWAY is not set; notify_user will answer \"notifications unavailable\"");
  }
  registerTool(ctx, gateway);
  // webServer is optional (absent in headless) and may be mounted after this
  // plugin applies; see the memory plugin for the boot-order race.
  ctx.inject(["webServer"], (c) => registerRoutes(c));
  ctx.logger.info("[dshm-notify] loaded");
}
