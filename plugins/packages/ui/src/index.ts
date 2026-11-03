import { FOGCITY_CSS } from "./fogcity.js";
import { MANAGED_CSS, MANAGED_SCRIPT } from "./managed.js";

export const name = "dshm-ui";
// No service is injected: the event below is only ever emitted by the web server, so headless compositions simply never call it.
export const inject: string[] = [];

/** The slice of the dsh (Cordis) context this plugin uses. */
interface PluginCtx {
  logger: { info(msg: string): void };
  on(event: "webserver/index-inject", listener: (table: Array<Record<string, unknown>>) => void): unknown;
}

/**
 * Adds three rows to dsh's index.html: the FOGCITY theme, the style that hides the settings DSH Mobile manages, and the inline
 * script that finds those settings (see managed.ts).
 */
export function apply(ctx: PluginCtx): void {
  ctx.on("webserver/index-inject", (table) => {
    table.push({ kind: "style", text: FOGCITY_CSS });
    table.push({ kind: "style", text: MANAGED_CSS });
    table.push({ kind: "script", text: MANAGED_SCRIPT });
  });
  ctx.logger.info("[dshm-ui] FOGCITY theme and managed-settings guard loaded");
}

export { FOGCITY_CSS, MANAGED_CSS, MANAGED_SCRIPT };
