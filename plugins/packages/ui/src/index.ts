import { SAKURA_CSS } from "./sakura.js";

export const name = "dshm-ui";
// No service is injected: the event below is only ever emitted by the web server, so headless compositions simply never call it.
export const inject: string[] = [];

/** The slice of the dsh (Cordis) context this plugin uses. */
interface PluginCtx {
  logger: { info(msg: string): void };
  on(event: "webserver/index-inject", listener: (table: Array<Record<string, unknown>>) => void): unknown;
}

export function apply(ctx: PluginCtx): void {
  ctx.on("webserver/index-inject", (table) => {
    table.push({ kind: "style", text: SAKURA_CSS });
  });
  ctx.logger.info("[dshm-ui] sakura theme loaded");
}

export { SAKURA_CSS };
