export interface NotifyRequest {
  title: string;
  body: string;
  sessionId?: string;
}

export const MAX_TITLE = 60;
export const MAX_BODY = 500;
export const TIMEOUT_MS = 10_000;

/**
 * Failure to deliver a notification. The message names the status code or the
 * failure kind only: the gateway URL carries the access token and must never
 * reach a log or a tool result.
 */
export class NotifyError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "NotifyError";
  }
}

/** Cut to `max` Unicode code points (a surrogate pair is never split); a cut text ends in "…" counted in the limit. */
function truncate(text: string, max: number): string {
  const points = Array.from(text);
  if (points.length <= max) return text;
  return `${points.slice(0, max - 1).join("")}…`;
}

/** A short, URL-free description of a transport error (e.g. "ECONNREFUSED"), when it has one. */
function errorCode(e: unknown): string | undefined {
  const own = (e as { code?: unknown } | null)?.code;
  const cause = (e as { cause?: { code?: unknown } } | null)?.cause?.code;
  const code = typeof cause === "string" ? cause : typeof own === "string" ? own : undefined;
  return code && /^[A-Z0-9_]{1,40}$/.test(code) ? code : undefined;
}

/**
 * Sends one phone notification through the app's local gateway
 * (`POST <gateway>/notify`). `gateway` already contains the access token path.
 * Title and body are truncated to 60 and 500 code points.
 */
export async function sendNotify(gateway: string, req: NotifyRequest, fetchImpl: typeof fetch = fetch): Promise<void> {
  const payload: NotifyRequest = {
    title: truncate(req.title, MAX_TITLE),
    body: truncate(req.body, MAX_BODY),
    ...(req.sessionId === undefined ? {} : { sessionId: req.sessionId }),
  };
  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, TIMEOUT_MS);
  try {
    let res: Response;
    try {
      res = await fetchImpl(`${gateway.replace(/\/+$/, "")}/notify`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(payload),
        redirect: "manual",
        signal: controller.signal,
      });
    } catch (e) {
      if (timedOut) throw new NotifyError(`gateway timed out after ${TIMEOUT_MS / 1000}s`);
      const code = errorCode(e);
      throw new NotifyError(`could not reach the gateway${code ? ` (${code})` : ""}`);
    }
    // Only the status matters; release the connection without reading the body.
    void res.body?.cancel().catch(() => undefined);
    if (res.status < 200 || res.status > 299) throw new NotifyError(`gateway answered HTTP ${res.status}`);
  } finally {
    clearTimeout(timer);
  }
}
