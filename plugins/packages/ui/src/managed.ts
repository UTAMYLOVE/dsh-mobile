/**
 * Hides the parts of dsh's web settings that DSH Mobile manages through its `--patch` (models and keys, subagent limits,
 * Agent Teams, web search, session-log upload) and the DeepSeek sign-in that the key guard blocks anyway. Both sides could
 * otherwise edit the same values and dsh answers a form write that conflicts with the patch with "本部署没有接受这些值".
 *
 * How: an inline script ([MANAGED_SCRIPT]) watches the page with a MutationObserver, finds each managed block from a stable
 * anchor ([MANAGED_RULES]: ids, data-* attributes, the provider id; text only where dsh offers nothing else) and marks it with
 * [MANAGED_ATTR]. [MANAGED_CSS] then hides the block's content and shows [MANAGED_NOTE] in its place. Nothing in dsh's DOM is
 * moved or inserted (React keeps owning its nodes); a block that is not found is left alone, and every error is swallowed.
 */

export const MANAGED_ATTR = "data-dshm-managed";
export const MANAGED_NOTE = "這些設定由 DSH Mobile 管理，請點右上角齒輪";

/**
 * How a managed block is found: every element matching `anchor` (and, if given, whose trimmed text is one of `text`) leads to
 * the block `self` (the anchor), `section` (its closest `<section>`) or `childOf` (its ancestor that is a direct child of an
 * element matching that selector). `mode` "note" hides the block's content behind the note; "gone" removes it entirely.
 */
export interface ManagedRule {
  readonly anchor: string;
  readonly text?: readonly string[];
  readonly block: "self" | "section" | { readonly childOf: string };
  readonly mode: "note" | "gone";
}

/** dsh 0.2.1-rc.5; the selector table with the evidence is in docs/sdd-ledger/followup-1-report.md. */
export const MANAGED_RULES: readonly ManagedRule[] = [
  // Plugins > 子智能体: the 运行限制 section (row `subagent`: maxDepth, maxActiveSubagents). The model choice stays.
  { anchor: "#plugin-config-subagent-depth", block: "section", mode: "note" },
  { anchor: "#plugin-config-subagent-capacity", block: "section", mode: "note" },
  // Plugins > 网页搜索: the whole form is row `web-search-deepseek` (key, endpoint = the gateway URL, budget).
  { anchor: '[data-plugin-item-detail="web-search"] [data-plugin-config]', block: "self", mode: "note" },
  // Plugins > 智能体团队: the app owns the bundle entry and the `agent-team` row.
  { anchor: '[data-plugin-item-detail="agent-team"] [data-plugin-config]', block: "self", mode: "note" },
  { anchor: 'li[data-plugin-package="@deepseek-ai/dsh-experimental-agent-team-profile"]', block: "self", mode: "note" },
  // Settings > 模型: providers, base URLs, model lists and API keys (rows `llm-deepseek`, `llm-pi-ai`). Found by provider id.
  { anchor: 'button[aria-label$="(deepseek-official)"]', block: { childOf: '[data-slot="settings.section"]' }, mode: "note" },
  // Settings > 通用设置: "Upload Session Log" (row `session-log-deepseek`). dsh gives the row no id; its label is the anchor.
  {
    anchor: '[role="switch"][aria-label*="Session Log"]',
    block: { childOf: '[data-slot="settings.general.item"]' },
    mode: "note",
  },
  // Account menu > 登录: DeepSeek sign-in stores a grant (blocked with 403 by the memory plugin's key guard).
  { anchor: '[role="menuitem"]', text: ["登录", "登入", "Sign in", "Log in"], block: "self", mode: "gone" },
];

/**
 * Runs in the page (serialized with `Function.prototype.toString`, so it must stay self-contained: no imports, no outer
 * variables). Marks the blocks now and again, at most once per frame, whenever dsh adds or replaces nodes.
 */
export function watchManagedSettings(doc: Document, rules: readonly ManagedRule[], attr: string): void {
  try {
    const blockOf = (anchor: Element, how: ManagedRule["block"]): Element | null => {
      if (how === "self") return anchor;
      if (how === "section") return anchor.closest("section");
      for (let e: Element | null = anchor; e && e.parentElement; e = e.parentElement) {
        if (e.parentElement.matches(how.childOf)) return e;
      }
      return null;
    };
    const mark = (): void => {
      for (const rule of rules) {
        try {
          for (const anchor of Array.from(doc.querySelectorAll(rule.anchor))) {
            if (rule.text && rule.text.indexOf((anchor.textContent || "").trim()) < 0) continue;
            const block = blockOf(anchor, rule.block);
            if (block && block.getAttribute(attr) !== rule.mode) block.setAttribute(attr, rule.mode);
          }
        } catch (_) {
          // One rule failing (an unknown selector in an old WebView) must not stop the others.
        }
      }
    };
    let queued = false;
    const run = (): void => {
      queued = false;
      try {
        mark();
      } catch (_) {
        // never break dsh's page
      }
    };
    const schedule = (): void => {
      if (queued) return;
      queued = true;
      if (typeof requestAnimationFrame === "function") requestAnimationFrame(run);
      else setTimeout(run, 16);
    };
    mark();
    new MutationObserver(schedule).observe(doc.documentElement, { childList: true, subtree: true });
  } catch (_) {
    // never break dsh's page
  }
}

/** The inline script for `webserver/index-inject` (a classic script in the page's head). */
export const MANAGED_SCRIPT = `(${watchManagedSettings.toString()})(document, ${JSON.stringify(MANAGED_RULES)}, ${JSON.stringify(MANAGED_ATTR)});`;

/** A CSS string literal body with every non-ASCII character escaped, so the stylesheet stays ASCII. */
function cssEscape(text: string): string {
  let out = "";
  for (const ch of text) {
    const code = ch.codePointAt(0)!;
    out += code < 0x80 && ch !== '"' && ch !== "\\" ? ch : `\\${code.toString(16)} `;
  }
  return out;
}

/** Hides what [MANAGED_SCRIPT] marked; the note uses dsh's own secondary text colour, so it reads in both themes. */
export const MANAGED_CSS = `[${MANAGED_ATTR}="gone"] {
  display: none !important;
}
[${MANAGED_ATTR}="note"] {
  pointer-events: none;
}
[${MANAGED_ATTR}="note"] > * {
  display: none !important;
}
[${MANAGED_ATTR}="note"]::before {
  content: "${cssEscape(MANAGED_NOTE)}";
  display: block;
  padding: 10px 4px;
  font-size: 12px;
  line-height: 18px;
  color: var(--dsw-alias-label-secondary);
}
`;
