// Motion for the page stack: count-up numbers, drawn rings/bars, staggered panels — and nothing of it with reduced motion.
// Numbers: <span data-n="1234" data-k="key" data-f="money|l|sign">1,234</span> (the final text is already in the HTML).
// Drawn values: an element with style="--p:0.42" and data-k="key" (CSS draws it from --p once its page is `on`).
import { money } from './common.js';

export const still = () => { try { return matchMedia('(prefers-reduced-motion: reduce)').matches; } catch (e) { return false; } };

const FMT = {
  money: v => money(v),
  l: v => (v / 1000).toFixed(1),
  sign: v => (Math.round(v) > 0 ? '+' : Math.round(v) < 0 ? '−' : '') + money(Math.abs(v)),
};
const fmt = (el, v) => (FMT[el.dataset.f] || FMT.money)(v);
const memo = new WeakMap();   // page element → { n: Map(key → number), p: Map(key → --p), raf }

// settle(page, { enter }) — after a page was (re)drawn: on enter numbers count up from 0; on a redraw they tween
// from the last shown value, and drawn values slide from their last --p to the new one.
export function settle(page, { enter = false } = {}) {
  const m = memo.get(page) || { n: new Map(), p: new Map(), raf: 0 };
  memo.set(page, m);
  page.querySelectorAll('.pn').forEach((p, i) => p.style.setProperty('--d', Math.min(i, 6) * 70 + 'ms'));
  const nums = [...page.querySelectorAll('[data-n]')], draws = [...page.querySelectorAll('[data-k][style*="--p"]')];
  const quiet = still();
  // drawn values on a redraw: start from where they were
  if (!enter && !quiet) {
    const moved = [];
    for (const el of draws) {
      const was = m.p.get(el.dataset.k), now = el.style.getPropertyValue('--p');
      if (was != null && was !== now) { el.style.setProperty('--p', was); el.style.transitionDelay = '0s'; moved.push([el, now]); }
    }
    if (moved.length) { void page.offsetWidth; for (const [el, now] of moved) el.style.setProperty('--p', now); }
  }
  m.p = new Map(draws.map(el => [el.dataset.k, el.style.getPropertyValue('--p')]));
  // numbers
  cancelAnimationFrame(m.raf);
  const runs = [];
  for (const el of nums) {
    const to = +el.dataset.n, key = el.dataset.k, from = enter ? 0 : (key && m.n.has(key) ? m.n.get(key) : to);
    if (!quiet && from !== to) runs.push([el, from, to]);
  }
  m.n = new Map(nums.filter(el => el.dataset.k).map(el => [el.dataset.k, +el.dataset.n]));
  if (!runs.length) return;
  for (const [el, from] of runs) el.textContent = fmt(el, from);
  const t0 = performance.now();
  const step = now => {
    const k = Math.min(1, (now - t0) / 950), e = 1 - Math.pow(1 - k, 3);
    for (const [el, from, to] of runs) el.textContent = fmt(el, k < 1 ? from + (to - from) * e : to);
    if (k < 1) m.raf = requestAnimationFrame(step);
  };
  m.raf = requestAnimationFrame(step);
}

// a number that counts: n(value, key, format)
export const num = (v, k = '', f = 'money', cls = 'o') => `<span class="${cls}" data-n="${Math.round(+v || 0)}"${k ? ` data-k="${k}"` : ''}${f !== 'money' ? ` data-f="${f}"` : ''}>${FMT[f](Math.round(+v || 0))}</span>`;
// --p for drawn values, clamped to 0…1
export const frac = (a, b) => (b > 0 ? Math.max(0, Math.min(1, a / b)) : 0).toFixed(4);
