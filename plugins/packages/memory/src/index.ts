import { isAbsolute, join } from "node:path";
import type { IncomingMessage, ServerResponse } from "node:http";
import { requireAuth } from "@dshm/common";
import { DreamParseError, dream, type Llm } from "./dream.js";
import { registerCredentialGuard } from "./guard.js";
import { MemoryPathError, MemoryStore, type AppendTarget } from "./store.js";

export const name = "dshm-memory";
// webServer is deliberately absent: headless compositions have no such service
// and an injected-but-missing service leaves the plugin pending forever.
export const inject = ["tools", "systemPrompt"];

const DREAM_TIMEOUT_MS = 120_000;
const COMPACTION_NOTE = "(compaction) 對話已壓縮，重要事項請以 memory_write 保存";
const DREAM_SYSTEM = "You are a precise assistant. Follow the output format exactly.";

/** The slice of the dsh (Cordis) context this plugin uses. */
interface PluginCtx {
  logger: { info(msg: string): void; warn(msg: string): void };
  get(name: string): any;
  effect(fn: () => unknown, name?: string): unknown;
  inject(deps: string[], callback: (ctx: PluginCtx) => void): unknown;
  on(event: string, listener: (...args: any[]) => unknown): unknown;
  tools: { register(tool: Record<string, unknown>): unknown };
  systemPrompt: { context(entry: Record<string, unknown>): unknown };
}

/**
 * One non-streaming text completion on the profile's default model (row
 * `agent-default-model`): `ctx.agentDefaultModel.currentSelection()` names the
 * route, `ctx.llm.prepareCall` validates it, `call.stream` yields the chunks.
 */
function createLlm(ctx: PluginCtx): Llm {
  return async (prompt) => {
    const llm = ctx.get("llm");
    const selection = ctx.get("agentDefaultModel")?.currentSelection();
    if (!llm || !selection) throw new Error("model service unavailable");
    const signal = AbortSignal.timeout(DREAM_TIMEOUT_MS);
    const call = await llm.prepareCall(
      {
        provider: selection.provider,
        model: selection.model,
        ...(selection.reasoningEffort === undefined ? {} : { reasoningEffort: selection.reasoningEffort }),
      },
      signal,
    );
    let text = "";
    let failure: string | undefined;
    for await (const chunk of call.stream({
      ...call.config,
      system: DREAM_SYSTEM,
      messages: [{ role: "user", content: [{ type: "text", text: prompt }] }],
      signal,
    })) {
      if (chunk.type === "text-delta") text += chunk.text;
      else if (chunk.type === "finish" && (chunk.reason.kind === "error" || chunk.reason.kind === "aborted")) {
        failure = `${chunk.reason.kind}: ${chunk.reason.failure?.message ?? "unknown"}`;
      }
    }
    if (failure !== undefined) throw new Error(`model call ${failure}`);
    return text;
  };
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

/** Memory path problems surface as ordinary tool errors (dsh turns a throw into an isError result). */
async function toolErrors<T>(run: () => T | Promise<T>): Promise<T> {
  try {
    return await run();
  } catch (e) {
    if (e instanceof MemoryPathError) throw new Error(`memory: ${e.message}`);
    throw e;
  }
}

function asArgs(args: unknown): Record<string, unknown> {
  return typeof args === "object" && args !== null ? (args as Record<string, unknown>) : {};
}
function optionalInt(value: unknown, field: string): number | undefined {
  if (value === undefined) return undefined;
  if (typeof value !== "number" || !Number.isFinite(value)) throw new Error(`memory: "${field}" must be a number`);
  return Math.floor(value);
}

function registerTools(ctx: PluginCtx, store: MemoryStore): void {
  ctx.tools.register({
    name: "memory_search",
    description:
      "Search the assistant's Markdown memory (MEMORY.md, USER.md, daily notes). All space-separated terms must match, case-insensitive; newest notes first.",
    parameters: {
      type: "object",
      properties: {
        query: { type: "string", description: "Space-separated search terms." },
        limit: { type: "integer", minimum: 1, maximum: 50, description: "Max matches (default 10)." },
      },
      required: ["query"],
    },
    output: {
      schema: {
        type: "object",
        properties: {
          results: {
            type: "array",
            items: {
              type: "object",
              properties: { path: { type: "string" }, line: { type: "integer" }, text: { type: "string" } },
              required: ["path", "line", "text"],
            },
          },
        },
        required: ["results"],
      },
      render: (_args: unknown, value: { results: { path: string; line: number; text: string }[] }) => [
        {
          type: "text",
          text: value.results.length
            ? value.results.map((r) => `${r.path}:${r.line}: ${r.text}`).join("\n")
            : "No matches.",
        },
      ],
    },
    async execute(args: unknown) {
      const { query, limit } = asArgs(args);
      if (typeof query !== "string") throw new Error('memory: "query" must be a string');
      const n = Math.min(50, Math.max(1, optionalInt(limit, "limit") ?? 10));
      return { results: store.search(query, n) };
    },
  });

  ctx.tools.register({
    name: "memory_get",
    description:
      "Read a memory Markdown file by workspace-relative path (e.g. MEMORY.md or memory/2026-11-02.md), optionally from a 1-based line for a number of lines.",
    parameters: {
      type: "object",
      properties: {
        path: { type: "string", description: "Workspace-relative .md path." },
        from: { type: "integer", minimum: 1, description: "First line, 1-based." },
        lines: { type: "integer", minimum: 0, description: "Number of lines." },
      },
      required: ["path"],
    },
    output: { schema: { type: "string" }, render: (_args: unknown, value: string) => [{ type: "text", text: value }] },
    async execute(args: unknown) {
      const { path, from, lines } = asArgs(args);
      if (typeof path !== "string") throw new Error('memory: "path" must be a string');
      return toolErrors(() => store.get(path, optionalInt(from, "from"), optionalInt(lines, "lines")));
    },
  });

  ctx.tools.register({
    name: "memory_write",
    description:
      'Save something to memory. target "daily" = today\'s note (timestamped), "long" = MEMORY.md (durable facts), "user" = USER.md (user preferences).',
    parameters: {
      type: "object",
      properties: {
        target: { type: "string", enum: ["daily", "long", "user"] },
        text: { type: "string", description: "Markdown text to append." },
      },
      required: ["target", "text"],
    },
    output: { schema: { type: "string" }, render: (_args: unknown, value: string) => [{ type: "text", text: value }] },
    async execute(args: unknown) {
      const { target, text } = asArgs(args);
      if (target !== "daily" && target !== "long" && target !== "user") {
        throw new Error('memory: "target" must be "daily", "long" or "user"');
      }
      if (typeof text !== "string") throw new Error('memory: "text" must be a string');
      await toolErrors(() => store.append(target as AppendTarget, text));
      return `saved to ${target}`;
    },
  });
}

function registerRoutes(ctx: PluginCtx, store: MemoryStore): void {
  const webServer = ctx.get("webServer");
  if (!webServer) return; // only reachable when called outside ctx.inject
  const statePath = join(store.root, "memory", ".dream-state.json");
  const llm = createLlm(ctx);
  let dreaming = false;

  ctx.effect(
    () =>
      webServer.register({
        kind: "exact",
        path: "/dshm/health/memory",
        handler(req: IncomingMessage, res: ServerResponse) {
          if (!requireAuth(ctx, req, res)) return;
          if (req.method !== "GET" && req.method !== "HEAD") {
            res.writeHead(405, { allow: "GET, HEAD" }).end();
            return;
          }
          sendJson(res, 200, { ok: true }, req.method === "HEAD");
        },
      }),
    "dshm-memory.route-health",
  );

  ctx.effect(
    () =>
      webServer.register({
        kind: "exact",
        path: "/dshm/heartbeat",
        async handler(req: IncomingMessage, res: ServerResponse) {
          if (!requireAuth(ctx, req, res)) return;
          if (req.method !== "POST") {
            res.writeHead(405, { allow: "POST" }).end();
            return;
          }
          if (dreaming) {
            sendJson(res, 409, { error: "dream_busy" });
            return;
          }
          dreaming = true;
          try {
            const result = await dream(store, llm, statePath);
            sendJson(res, 200, result);
          } catch (e) {
            const parse = e instanceof DreamParseError;
            ctx.logger.warn(`[dshm-memory] dream failed: ${e instanceof Error ? e.message : String(e)}`);
            if (!res.headersSent) sendJson(res, parse ? 502 : 500, { error: parse ? "dream_parse" : "dream_failed" });
          } finally {
            dreaming = false;
          }
        },
      }),
    "dshm-memory.route-heartbeat",
  );
}

/**
 * What the agent must know about running inside the DSH Mobile app (stage 2 §I.2/§I.3): limits are app settings, dsh's own
 * config is regenerated, workflows have no sandbox here.
 */
export const PHONE_NOTE = [
  "# Running inside the DSH Mobile Android app",
  "- Agent limits (concurrent subagents, team size, delegation depth), models and permissions are set by the owner in the app: 設定 → 代理人, then 「套用並重啟」. If a limit stops you, tell the owner which app setting to change. Never edit dsh config files under /opt/dsh or ~/.dsh: the app rewrites them on every start.",
  "- dsh runs in proot on Android, so the workflow sandbox (workspace-write) is not available. Do not start workflows; do the work directly or dispatch subagents instead.",
  "- Files you write under the workspace can be shared from the app (對話 → 數據 → 檔案). Long prose replies in a book conversation become chapters on the app's bookshelf.",
  "- Reply in the owner's language (Traditional Chinese unless asked otherwise).",
].join("\n");

export function apply(ctx: PluginCtx): void {
  // Keys are managed by the app whether or not memory is usable.
  registerCredentialGuard(ctx);

  const workspace = process.env.DSHM_WORKSPACE;
  if (!workspace || !isAbsolute(workspace)) {
    ctx.logger.warn("[dshm-memory] DSHM_WORKSPACE is unset or not an absolute path; memory tools and routes are disabled");
    return;
  }
  const store = new MemoryStore(workspace);

  ctx.systemPrompt.context({
    name: "dshm-phone",
    order: 8990,
    interpolate: false,
    text: () => PHONE_NOTE,
  });
  ctx.systemPrompt.context({
    name: "dshm-memory",
    order: 9000,
    interpolate: false,
    text: () => store.loadStartupContext(),
  });
  registerTools(ctx, store);
  // webServer is optional (absent in headless) and may be mounted after this
  // plugin applies, so ctx.get("webServer") here would race the boot order.
  // ctx.inject runs the callback once the service exists and never blocks the
  // rest of the plugin when it does not.
  ctx.inject(["webServer"], (c) => registerRoutes(c, store));

  // dsh has no "before compaction" hook; compaction/start is the closest signal.
  // [unverified API] so it must never throw into dsh.
  ctx.on("session/event", (_session: unknown, event: { type?: unknown } | null | undefined) => {
    try {
      if (event?.type !== "compaction/start") return;
      return store.append("daily", COMPACTION_NOTE).catch((e: unknown) => {
        ctx.logger.warn(`[dshm-memory] compaction note failed: ${e instanceof Error ? e.message : String(e)}`);
      });
    } catch (e) {
      ctx.logger.warn(`[dshm-memory] compaction hook failed: ${e instanceof Error ? e.message : String(e)}`);
    }
  });

  ctx.logger.info(`[dshm-memory] loaded (workspace ${workspace})`);
}
