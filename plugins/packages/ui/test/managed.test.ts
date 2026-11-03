// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { MANAGED_ATTR, MANAGED_CSS, MANAGED_NOTE, MANAGED_RULES, MANAGED_SCRIPT } from "../src/managed.js";

/** Runs the injected script the way the page does: as source text, in this window. */
function runScript(): void {
  // eslint-disable-next-line no-new-func
  new Function(MANAGED_SCRIPT)();
}

const frame = () => new Promise((r) => setTimeout(r, 40));
const marked = () => [...document.querySelectorAll(`[${MANAGED_ATTR}]`)];

/** Shapes taken from dsh 0.2.1-rc.5's rendered settings (see docs/sdd-ledger/followup-1-report.md). */
const SUBAGENT_PAGE = `
  <div data-plugin-item-detail="subagent"><section data-plugin-config="true"><div data-slot="plugins.item" style="display: contents;"><div>
    <section id="limits" aria-labelledby=":r11:-limits"><h3 id=":r11:-limits">运行限制</h3>
      <input id="plugin-config-subagent-depth" value="2"><input id="plugin-config-subagent-capacity" value="6"></section>
    <section id="models" aria-labelledby=":r11:-models"><h3>模型选择</h3><button role="switch" aria-label="允许 Agent 为子智能体选择模型"></button></section>
    <button id="save">保存</button>
  </div></div></section></div>`;
const WEB_SEARCH_PAGE = `
  <div data-plugin-item-detail="web-search"><section id="ws" data-plugin-config="true"><input id="plugin-config-web-search-key" type="password">
    <input id="plugin-config-web-search-endpoint"></section></div>`;
const PLUGIN_LIST = `
  <section data-plugin-panel="true"><ul>
    <li id="sub" data-plugin-item="subagent">子智能体</li>
    <li id="team" data-plugin-package="@deepseek-ai/dsh-experimental-agent-team-profile" data-plugin-status="running">智能体团队<button role="switch"></button></li>
    <li id="voice" data-plugin-package="@deepseek-ai/dsh-experimental-voice-input-bundle">语音输入</li>
  </ul></section>`;
const MODELS_SECTION = `
  <div role="dialog"><div data-slot="settings.section" style="display: contents;"><div id="models-section"><h2>模型</h2><ul><li>
    <span>DeepSeek</span><button aria-label="编辑 DeepSeek (deepseek-official)">编辑</button></li></ul></div></div></div>`;
const GENERAL_SECTION = `
  <div data-slot="settings.section" style="display: contents;"><div id="general">
    <div data-slot="settings.general.item" style="display: contents;"><div id="lang">语言</div></div>
    <div data-slot="settings.general.item" style="display: contents;"><div id="upload"><span>上传</span>
      <button role="switch" aria-checked="false" aria-label="在使用官方模型 API 时上传 Session Log"></button></div></div>
  </div></div>`;
const ACCOUNT_MENU = `
  <div role="menu"><button id="settings" role="menuitem">设置</button><button id="signin" role="menuitem"><span></span><span>登录</span></button></div>`;

afterEach(() => {
  document.body.innerHTML = "";
  vi.restoreAllMocks();
});

describe("managed-settings script", () => {
  it("does nothing and throws nothing on a page without any managed setting", async () => {
    document.body.innerHTML = "<div id=root><p>hello</p></div>";
    expect(() => runScript()).not.toThrow();
    await frame();
    expect(marked()).toEqual([]);
  });

  it("does not throw even when the page has no MutationObserver or querySelectorAll fails", () => {
    const mo = globalThis.MutationObserver;
    (globalThis as { MutationObserver?: unknown }).MutationObserver = undefined;
    vi.spyOn(document, "querySelectorAll").mockImplementation(() => {
      throw new Error("boom");
    });
    try {
      expect(() => runScript()).not.toThrow();
    } finally {
      globalThis.MutationObserver = mo;
    }
  });

  it("hides the subagent limits (depth, concurrency) but keeps the model choice and save button", async () => {
    document.body.innerHTML = SUBAGENT_PAGE;
    runScript();
    await frame();
    expect(marked().map((e) => e.id)).toEqual(["limits"]);
    expect(document.getElementById("limits")!.getAttribute(MANAGED_ATTR)).toBe("note");
  });

  it("hides the whole web search configuration (key, endpoint with the gateway URL, budget)", async () => {
    document.body.innerHTML = WEB_SEARCH_PAGE;
    runScript();
    await frame();
    expect(marked().map((e) => e.id)).toEqual(["ws"]);
  });

  it("hides the Agent Teams card (the app owns that bundle) and nothing else in the plugin list", async () => {
    document.body.innerHTML = PLUGIN_LIST;
    runScript();
    await frame();
    expect(marked().map((e) => e.id)).toEqual(["team"]);
  });

  it("hides the model providers section (base URLs and keys)", async () => {
    document.body.innerHTML = MODELS_SECTION;
    runScript();
    await frame();
    expect(marked().map((e) => e.id)).toEqual(["models-section"]);
  });

  it("hides only the session-log upload row of the general settings", async () => {
    document.body.innerHTML = GENERAL_SECTION;
    runScript();
    await frame();
    expect(marked().map((e) => e.id)).toEqual(["upload"]);
  });

  it("removes the DeepSeek sign-in menu item (blocked: keys live in the app) without a note", async () => {
    document.body.innerHTML = ACCOUNT_MENU;
    runScript();
    await frame();
    expect(marked().map((e) => e.id)).toEqual(["signin"]);
    expect(document.getElementById("signin")!.getAttribute(MANAGED_ATTR)).toBe("gone");
  });

  it("catches blocks that dsh renders later (the settings pages are opened after load)", async () => {
    document.body.innerHTML = "<div id=root></div>";
    runScript();
    await frame();
    expect(marked()).toEqual([]);
    document.getElementById("root")!.innerHTML = SUBAGENT_PAGE + WEB_SEARCH_PAGE;
    await frame();
    expect(marked().map((e) => e.id).sort()).toEqual(["limits", "ws"]);
    // A re-render that replaces the section is marked again.
    document.getElementById("limits")!.remove();
    const again = document.createElement("section");
    again.id = "limits2";
    again.innerHTML = '<input id="plugin-config-subagent-depth">';
    document.querySelector('[data-plugin-item-detail="subagent"] div div')!.prepend(again);
    await frame();
    expect(marked().map((e) => e.id).sort()).toEqual(["limits2", "ws"]);
  });

  it("every rule is a valid selector", () => {
    for (const rule of MANAGED_RULES) expect(() => document.querySelector(rule.anchor)).not.toThrow();
  });
});

describe("managed-settings style", () => {
  it("hides the marked blocks and puts the note in their place", () => {
    expect(MANAGED_NOTE).toBe("這些設定由 DSH Mobile 管理，請點右上角齒輪");
    expect(MANAGED_CSS).toMatch(/\[data-dshm-managed="note"\]\s*>\s*\*\s*\{[^}]*display:\s*none\s*!important/);
    expect(MANAGED_CSS).toMatch(/\[data-dshm-managed="gone"\]\s*\{[^}]*display:\s*none\s*!important/);
    expect(MANAGED_CSS).toMatch(/\[data-dshm-managed="note"\]\s*\{[^}]*pointer-events:\s*none/);
    const content = /content:\s*"([^"]*)"/.exec(MANAGED_CSS)![1]!;
    // CSS escapes (\XXXX ) keep the stylesheet ASCII; decoded it is the note.
    const decoded = content.replace(/\\([0-9a-fA-F]{1,6}) ?/g, (_, h: string) => String.fromCodePoint(parseInt(h, 16)));
    expect(decoded).toBe(MANAGED_NOTE);
  });

  it("the note is readable in both themes (dsh's own secondary text colour)", () => {
    expect(MANAGED_CSS).toContain("color: var(--dsw-alias-label-secondary)");
    expect(MANAGED_CSS).not.toMatch(/#[0-9a-fA-F]{3,8}\b/);
  });

  it("makes a marked subagent depth field invisible in a real layout", async () => {
    document.head.insertAdjacentHTML("beforeend", `<style>${MANAGED_CSS}</style>`);
    document.body.innerHTML = SUBAGENT_PAGE;
    runScript();
    await frame();
    const depth = document.getElementById("plugin-config-subagent-depth")!;
    expect(getComputedStyle(depth.parentElement!).display).toBe("block"); // the section stays, it carries the note
    expect(getComputedStyle(document.querySelector("#limits > h3")!).display).toBe("none");
    expect(getComputedStyle(depth).display).toBe("none");
    expect(getComputedStyle(document.getElementById("models")!).display).not.toBe("none");
  });
});
