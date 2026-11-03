// HUD pieces shared by the pages: the big ring, small rings, the 7-day food-spending bars, panel headings.
import { foodSpent } from '../core/ledger.js';
import { esc, money } from './common.js';

const WEEK = '日一二三四五六';
const utc = d => Date.UTC(+d.slice(0, 4), +d.slice(5, 7) - 1, +d.slice(8, 10));
export const back = (d, k) => new Date(utc(d) - k * 864e5).toISOString().slice(0, 10);
export const weekday = d => WEEK[new Date(utc(d)).getUTCDay()];

// a panel heading: dot · title · rule · note
export const ph = (title, note = '', ice = false) => `<div class="ph k${ice ? ' ice' : ''}"><span>${title}</span><b></b>${note ? `<em>${note}</em>` : ''}</div>`;

const C = r => +(2 * Math.PI * r).toFixed(2);
// the 240-unit HUD ring: rotating tick ring, track, value arc (--p), rotating dashed ice ring
export function hudRing(p, key, { size = 240, inner = '' } = {}) {
  const big = size >= 200, r = big ? 100 : 96;
  return `<div class="ring" style="width:${size}px;height:${size}px"><svg aria-hidden="true" width="${size}" height="${size}" viewBox="0 0 240 240">
    <circle class="spin" cx="120" cy="120" r="114" fill="none" stroke="#9E7A7A" stroke-width="${big ? 7 : 9}" stroke-dasharray="${big ? '1.2 10.74' : '1.6 10.34'}"/>
    <circle cx="120" cy="120" r="${r}" fill="none" stroke="rgba(158,122,122,.18)" stroke-width="${big ? 4 : 8}"/>
    <circle class="arc" data-k="${key}" cx="120" cy="120" r="${r}" fill="none" stroke="#DC9FB4" stroke-width="${big ? 5 : 9}" stroke-linecap="round" stroke-dasharray="${C(r)}" transform="rotate(-90 120 120)" style="--c:${C(r)};--p:${p};filter:drop-shadow(0 0 7px #DC9FB4)"/>
    <circle class="spin rev" cx="120" cy="120" r="${big ? 87 : 78}" fill="none" stroke="#A5DEE4" stroke-width="${big ? 1.5 : 2}" stroke-dasharray="${big ? '3 9' : '4 10'}" opacity=".8"/>
    ${big ? '<circle cx="120" cy="120" r="77" fill="none" stroke="rgba(158,122,122,.3)" stroke-width="1"/>' : ''}</svg>
    <div class="mid">${inner}</div></div>`;
}
// a 44 px macro ring
export const miniRing = (p, key, color) => `<svg aria-hidden="true" width="44" height="44" viewBox="0 0 44 44"><circle cx="22" cy="22" r="18" fill="none" stroke="rgba(158,122,122,.18)" stroke-width="4"/>
  <circle class="arc" data-k="${key}" cx="22" cy="22" r="18" fill="none" stroke="${color}" stroke-width="4" stroke-linecap="round" stroke-dasharray="113.1" transform="rotate(-90 22 22)" style="--c:113.1;--p:${p}"/></svg>`;

// the last 7 days of food spending (today last, pink; over the cap: ice) with a dashed cap line
export function weekBars(L, today, cap, { key, labels = false, height = 120 } = {}) {
  const days = Array.from({ length: 7 }, (_, i) => back(today, 6 - i)).map(d => ({ d, v: foodSpent(L, d) }));
  const top = Math.max(1, cap * 7 / 6, ...days.map(x => x.v));
  const said = days.map(x => `${x.d === today ? '今天' : '星期' + weekday(x.d)} ${money(x.v)}`).join('、');
  const bars = days.map((x, i) => {
    const cls = 'bar' + (x.d === today ? ' t' : '') + (cap > 0 && x.v > cap ? ' ov' : '');
    const s = `<s data-k="${key}${i}" style="--p:${(x.v / top).toFixed(4)};--d:${150 + i * 60}ms"></s>`;
    return labels ? `<span class="${cls}"><span class="bv">${money(x.v)}</span><span class="col">${s}</span><span class="bl">${x.d === today ? '今' : weekday(x.d)}</span></span>`
      : `<span class="${cls}"><span class="col">${s}</span></span>`;
  }).join('');
  // with labels, the bar column sits between a 12 px value row and a 16 px day row (+ 2 × 4 px gaps): it starts 20 px up
  const capAt = labels ? `calc(20px + (100% - 36px) * ${(cap / top).toFixed(4)})` : `${(cap / top * 100).toFixed(2)}%`;
  return `<div class="bars week" role="img" aria-label="七天餐費：${esc(said)}" style="height:${height}px">
    ${cap > 0 ? `<span class="capline" style="--cap:${capAt}" aria-hidden="true">${labels ? `<span class="k">上限 ${money(cap)}</span>` : ''}</span>` : ''}${bars}</div>`;
}
