#!/usr/bin/env node
// life.mjs: DSH Mobile 的生活紀錄工具（記帳、熱量、健身、體重、外送週結算）。
// 資料都在工作區的 生活/ 資料夾，是一般的 CSV 和 JSON：使用者自己擁有，可以用試算表打開或匯出。
// AI 用這支程式算所有的數字，自己不心算。Node 20 以上，沒有任何相依套件。說明：node life.mjs help
//
// Data layout (all under <workspace>/生活/):
//   設定.json                every adjustable setting; missing keys fall back to DEFAULTS
//   帳本/YYYY-MM.csv         日期,時間,類型,金額,分類,帳戶,桶,內容,備註,來源,id
//   熱量/YYYY-MM.csv         日期,時間,餐別,內容,熱量下限,熱量上限,蛋白質,脂肪,碳水,來源,id
//   健身/YYYY-MM.csv         日期,動作,組數,次數或秒數,備註,id
//   體重.csv                 日期,體重,體脂,備註   (no id column: a row's id is t<YYMMDD>-<hash of its values>, see weightBaseId)
//   我的食物庫.csv           名稱,份量,熱量,蛋白質,脂肪,碳水
// Files an older skill wrote with other headers (in 生活/ or in <workspace>/帳本, <workspace>/熱量) are read by header name and
// never rewritten, except by `migrate --yes`.

import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";

export const FORMAT_VERSION = 1;
export const COMMAND_NAMES = ["help", "init", "add", "undo", "edit", "today", "month", "buckets", "recent", "find", "settings", "set", "food", "migrate", "uber"];

// ───────────────────────── text and numbers ─────────────────────────

const pad2 = (n) => String(n).padStart(2, "0");
const isObj = (v) => v !== null && typeof v === "object" && !Array.isArray(v);
const str = (v) => (v === undefined || v === null ? "" : String(v).trim());
const clone = (v) => (v === undefined ? undefined : JSON.parse(JSON.stringify(v)));
const WEEK = "日一二三四五六";

/** Full-width digits and punctuation (from Chinese keyboards) and the various minus signs, as ASCII. For parsing only. */
export function halfWidth(s) {
  return String(s ?? "")
    .replace(/[！-～]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 0xfee0))
    .replace(/[　   ]/g, " ")
    .replace(/[−‒–—―﹘﹣]/g, "-");
}
const norm = (s) => halfWidth(s).replace(/\s+/g, "").toLowerCase();

/** A plain number ("約650", "70.5kg", "1,200") or null. */
export function parseNum(v) {
  if (typeof v === "number") return Number.isFinite(v) ? v : null;
  if (typeof v !== "string") return null;
  const s = halfWidth(v).trim().replace(/^(約|大約|大概|差不多|~)\s*/, "").replace(/\s*(大卡|千卡|kcal|cal|公克|克|g|公斤|kg|%|組|次|下|秒|分鐘)$/i, "").replace(/,/g, "").trim();
  return /^-?\d+(\.\d+)?$/.test(s) ? Number(s) : null;
}

/** [low, high] from 650, "650-800", "650~800", "650到800" or [650, 800]; null when it is not a number or a range. */
export function parseRange(v) {
  if (Array.isArray(v)) {
    if (v.length === 0 || v.length > 2) return null;
    const a = parseNum(v[0]), b = parseNum(v[v.length - 1]);
    return a === null || b === null ? null : [Math.min(a, b), Math.max(a, b)];
  }
  if (typeof v === "number") return Number.isFinite(v) ? [v, v] : null;
  const s = halfWidth(str(v)).replace(/大卡|千卡|kcal/gi, "").trim();
  const m = s.match(/^(?:約|大約)?\s*(\d+(?:\.\d+)?)\s*(?:-|~|到|至)\s*(\d+(?:\.\d+)?)$/);
  if (m) { const a = Number(m[1]), b = Number(m[2]); return [Math.min(a, b), Math.max(a, b)]; }
  const n = parseNum(s);
  return n === null ? null : [n, n];
}

/** Money as integer cents ("NT$1,200", "120元", "1.2萬", -45); null when it is not money. */
export function parseMoney(v) {
  if (typeof v === "number") return Number.isFinite(v) ? Math.round(v * 100) : null;
  let s = halfWidth(str(v)).replace(/NT\$|NTD|TWD|新台幣|新臺幣|台幣|臺幣|塊錢|元|塊|\$|\s/gi, "").replace(/,/g, "");
  if (!s) return null;
  let neg = false;
  if (/^\(.+\)$/.test(s)) { neg = true; s = s.slice(1, -1); }
  if (s[0] === "-") { neg = !neg; s = s.slice(1); } else if (s[0] === "+") s = s.slice(1);
  let mult = 1;
  const u = s.match(/^(\d+(?:\.\d+)?)(萬|万|千|k)$/i);
  if (u) { s = u[1]; mult = u[2] === "萬" || u[2] === "万" ? 10000 : 1000; }
  if (!/^\d+(\.\d+)?$/.test(s)) return null;
  const cents = Math.round(Number(s) * mult * 100);
  return neg ? -cents : cents;
}

const group3 = (n) => String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ",");
/** 1,240 / 95.40 / -258 */
export function fmtMoney(cents) {
  const a = Math.abs(Math.round(cents));
  const frac = a % 100;
  return (cents < 0 ? "-" : "") + group3(Math.floor(a / 100)) + (frac ? "." + pad2(frac) : "");
}
/** The amount as written in a CSV cell: 120, 95.4 */
function plainMoney(cents) {
  const a = Math.abs(cents), frac = a % 100;
  return (cents < 0 ? "-" : "") + String(Math.floor(a / 100)) + (frac ? "." + pad2(frac).replace(/0$/, "") : "");
}
const money = (cents) => Math.round(cents) / 100;
const fmtInt = (n) => (n < 0 ? "-" : "") + group3(Math.round(Math.abs(n)));
const fmtDec = (n, d = 1) => String(Number(n.toFixed(d)));
const numCell = (n) => (n === null || n === undefined ? "" : fmtDec(n, 1));
const fmtKcal = (lo, hi) => (Math.round(lo) === Math.round(hi) ? fmtInt(lo) : `${fmtInt(lo)}–${fmtInt(hi)}`);
const pct = (x) => Math.round(x * 100);

// ───────────────────────── dates ─────────────────────────

const ymd = (y, m, d) => `${y}-${pad2(m)}-${pad2(d)}`;
const daysInMonth = (y, m) => new Date(Date.UTC(y, m, 0)).getUTCDate();
const dateUTC = (s) => { const [y, m, d] = s.split("-").map(Number); return new Date(Date.UTC(y, m - 1, d)); };
export function addDays(s, n) {
  const d = dateUTC(s);
  d.setUTCDate(d.getUTCDate() + n);
  return ymd(d.getUTCFullYear(), d.getUTCMonth() + 1, d.getUTCDate());
}
const weekday = (s) => WEEK[dateUTC(s).getUTCDay()];
const monthOf = (s) => s.slice(0, 7);
function addMonths(month, n) {
  let [y, m] = month.split("-").map(Number);
  m += n;
  while (m < 1) { m += 12; y--; }
  while (m > 12) { m -= 12; y++; }
  return `${y}-${pad2(m)}`;
}
const dayDiff = (a, b) => Math.round((dateUTC(b) - dateUTC(a)) / 86400000);

/** "今天", "昨天", "2026-11-14", "2026/11/14", "11/14", "11月14日" → YYYY-MM-DD, or null. A date without a year is the latest one not far in the future. */
export function parseDate(v, today, yearHint = null) {
  const s = halfWidth(str(v)).replace(/\s+/g, "").replace(/號$/, "日");
  if (!s || s === "今天" || s === "今日") return today;
  if (s === "昨天" || s === "昨日") return addDays(today, -1);
  if (s === "前天") return addDays(today, -2);
  if (s === "大前天") return addDays(today, -3);
  if (s === "明天") return addDays(today, 1);
  let y, m, d, k;
  if ((k = s.match(/^(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})日?$/))) [y, m, d] = [Number(k[1]), Number(k[2]), Number(k[3])];
  else if ((k = s.match(/^(\d{4})(\d{2})(\d{2})$/))) [y, m, d] = [Number(k[1]), Number(k[2]), Number(k[3])];
  else if ((k = s.match(/^(\d{1,2})[-/.月](\d{1,2})日?$/))) {
    [m, d] = [Number(k[1]), Number(k[2])];
    y = yearHint ?? Number(today.slice(0, 4));
    if (yearHint === null && m >= 1 && m <= 12 && d >= 1 && d <= daysInMonth(y, m) && dayDiff(today, ymd(y, m, d)) > 31) y--;
  } else return null;
  if (!(m >= 1 && m <= 12 && d >= 1 && d <= daysInMonth(y, m) && y >= 1900 && y <= 2200)) return null;
  return ymd(y, m, d);
}

/** "12:30", "9:05", "下午3:20" → HH:MM; "" when empty; null when it is not a time. */
export function parseTime(v) {
  let s = halfWidth(str(v)).replace(/\s+/g, "");
  if (!s) return "";
  let shift = null;
  const p = s.match(/^(上午|早上|凌晨|中午|下午|晚上|傍晚)/);
  if (p) { shift = p[1]; s = s.slice(p[1].length); }
  const k = s.match(/^(\d{1,2})(?::|點|点|時)(\d{2})?分?$/) ?? s.match(/^(\d{1,2})(\d{2})$/);
  if (!k) return null;
  let h = Number(k[1]);
  const min = Number(k[2] ?? 0);
  if (shift && h <= 12) h = to24(shift, h);
  if (h > 23 || min > 59) return null;
  return `${pad2(h)}:${pad2(min)}`;
}
function to24(shift, h) {
  if (/下午|晚上|傍晚|PM/i.test(shift)) return h < 12 ? h + 12 : h;
  if (/中午/.test(shift)) return h < 11 ? h + 12 : h;
  return h === 12 ? 0 : h; // 上午, 早上, 凌晨, AM
}

/** Now in the settings' time zone (empty: the phone's), or LIFE_NOW ("2026-11-14 12:30") for tests. */
export function nowParts(tz) {
  const fixed = process.env.LIFE_NOW;
  if (fixed) {
    const m = fixed.match(/^(\d{4}-\d{2}-\d{2})(?:[T ](\d{2}:\d{2}))?/);
    if (m) return { date: m[1], time: m[2] ?? "12:00" };
  }
  const d = new Date();
  try {
    const parts = Object.fromEntries(new Intl.DateTimeFormat("en-US", { timeZone: tz || undefined, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).formatToParts(d).map((p) => [p.type, p.value]));
    return { date: `${parts.year}-${parts.month}-${parts.day}`, time: `${parts.hour === "24" ? "00" : parts.hour}:${parts.minute}` };
  } catch {
    return { date: ymd(d.getFullYear(), d.getMonth() + 1, d.getDate()), time: `${pad2(d.getHours())}:${pad2(d.getMinutes())}` };
  }
}
function showDate(date, ctx) {
  const [y, m, d] = date.split("-").map(Number);
  return (ctx && String(y) !== ctx.now.date.slice(0, 4) ? `${y}/` : "") + `${m}/${d}`;
}

// ───────────────────────── CSV ─────────────────────────

export function parseCsv(text) {
  if (text.charCodeAt(0) === 0xfeff) text = text.slice(1);
  const rows = [];
  let row = [], field = "", quoted = false, started = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"') { if (text[i + 1] === '"') { field += '"'; i++; } else quoted = false; } else field += c;
      continue;
    }
    if (c === '"') { quoted = true; started = true; continue; }
    if (c === ",") { row.push(field); field = ""; started = true; continue; }
    if (c === "\r") continue;
    if (c === "\n") { row.push(field); rows.push(row); row = []; field = ""; started = false; continue; }
    field += c; started = true;
  }
  if (started || row.length) { row.push(field); rows.push(row); }
  return rows.filter((r) => !(r.length === 1 && r[0].trim() === ""));
}
const csvField = (v) => { const s = v === undefined || v === null ? "" : String(v); return /[",\n\r]/.test(s) || /^\s|\s$/.test(s) ? `"${s.replace(/"/g, '""')}"` : s; };
export const csvLine = (cells) => cells.map(csvField).join(",");

function writeAtomic(file, text) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const tmp = `${file}.${process.pid}.tmp`;
  fs.writeFileSync(tmp, text);
  fs.renameSync(tmp, file);
}
function readText(file) {
  try { return fs.readFileSync(file, "utf8"); } catch (e) { if (e.code === "ENOENT") return null; throw e; }
}

// ───────────────────────── settings ─────────────────────────

export const DEFAULTS = {
  說明: "生活紀錄的設定。可以直接改這個檔，也可以跟 AI 說（例如「每月預算改成 15000」）。null 表示自動。",
  版本: FORMAT_VERSION,
  幣別: "TWD",
  單位: "元",
  時區: "",
  功能: { 記帳: true, 熱量: true, 健身: true, 體重: true, 外送: false },
  帳戶: ["現金"],
  預設帳戶: "現金",
  支出分類: ["早餐", "午餐", "晚餐", "宵夜", "點心", "手搖飲", "咖啡", "超商", "交通", "機車油錢", "日用品", "服飾", "娛樂", "醫療", "電話網路", "房租水電", "其他"],
  收入分類: ["薪水", "外送收入", "獎金", "零用錢", "其他收入"],
  餐別: ["早餐", "午餐", "晚餐", "宵夜", "點心", "飲料"],
  每月預算: 0,
  分類預算: {},
  桶: [],
  分類對應桶: {},
  預設桶: "",
  桶結轉: true,
  桶開始月份: "",
  身體: { 身高: null, 體重: null, 年齡: null, 性別: "", 活動量: "輕度", 目標: "維持" },
  熱量目標: { 熱量: null, 蛋白質: null, 脂肪: null, 碳水: null },
  提醒: { 開啟: false, 時間: "21:00", 內容: "今天花了什麼、吃了什麼？跟我說一聲就幫你記。" },
  外送: { 平台: "Uber", 最低時薪: 196, 每單保底: 45, 時薪倍數: 1.25, 服務分鐘上限: 180, 法規施行日: "2026-07-21" },
  食物庫建議次數: 3,
  重複檢查: true,
};
const ZERO_DECIMALS = new Set(["TWD", "JPY", "KRW", "VND", "IDR", "CLP", "ISK", "HUF"]);
const FEATURE = { 帳: "記帳", 熱量: "熱量", 健身: "健身", 體重: "體重" };

function merge(base, over) {
  if (!isObj(base) || !isObj(over)) return over === undefined ? clone(base) : clone(over);
  const out = clone(base);
  for (const [k, v] of Object.entries(over)) out[k] = isObj(base[k]) && isObj(v) ? merge(base[k], v) : clone(v);
  return out;
}

/** Types the rest of the program relies on, whatever the file says (a hand-edited file may be off). */
function sane(s) {
  const list = (v, d) => (Array.isArray(v) ? [...new Set(v.map(str).filter(Boolean))] : clone(d));
  s.帳戶 = list(s.帳戶, DEFAULTS.帳戶);
  if (s.帳戶.length === 0) s.帳戶 = ["現金"];
  if (!s.帳戶.includes(str(s.預設帳戶))) s.預設帳戶 = s.帳戶[0];
  s.支出分類 = list(s.支出分類, DEFAULTS.支出分類);
  s.收入分類 = list(s.收入分類, DEFAULTS.收入分類);
  s.餐別 = list(s.餐別, DEFAULTS.餐別);
  s.桶 = Array.isArray(s.桶) ? s.桶.filter(isObj).map((b) => ({ ...b, 名稱: str(b.名稱), 比例: Number(b.比例) || 0 })).filter((b) => b.名稱) : [];
  for (const k of ["功能", "分類預算", "分類對應桶", "身體", "熱量目標", "提醒", "外送"]) if (!isObj(s[k])) s[k] = clone(DEFAULTS[k]);
  for (const [k, v] of Object.entries(DEFAULTS.功能)) if (typeof s.功能[k] !== "boolean") s.功能[k] = v;
  for (const [k, v] of Object.entries(DEFAULTS.外送)) if (typeof v === "number" && !(Number(s.外送[k]) > 0)) s.外送[k] = v;
  s.每月預算 = Number(s.每月預算) > 0 ? Number(s.每月預算) : 0;
  s.食物庫建議次數 = Number.isInteger(s.食物庫建議次數) && s.食物庫建議次數 >= 2 ? s.食物庫建議次數 : DEFAULTS.食物庫建議次數;
  s.幣別 = str(s.幣別) || "TWD";
  s.單位 = str(s.單位) || s.幣別;
  return s;
}

function paths(ws) {
  const life = path.join(ws, "生活");
  return { ws, life, settings: path.join(life, "設定.json"), food: path.join(life, "我的食物庫.csv"), backup: path.join(life, "舊格式備份") };
}

function loadSettings(p) {
  let user = {}, problem = null, exists = false;
  const text = readText(p.settings);
  if (text !== null) {
    exists = true;
    try {
      user = JSON.parse(text.replace(/^﻿/, ""));
      if (!isObj(user)) { problem = "生活/設定.json 不是 JSON 物件"; user = {}; }
    } catch (e) { problem = `生活/設定.json 讀不懂（${e.message}）`; user = {}; }
  }
  return { settings: sane(merge(DEFAULTS, user)), user, exists, problem };
}

function saveSettings(ctx, user) {
  if (ctx.settingsProblem) throw new UserError(`${ctx.settingsProblem}，先修好或刪掉這個檔再改設定。`, 2);
  const full = merge(DEFAULTS, user);
  writeAtomic(ctx.p.settings, JSON.stringify(full, null, 2) + "\n");
  ctx.user = full;
  ctx.s = sane(merge(DEFAULTS, full));
}

/** The first write creates 生活/ and a full 設定.json, so the owner can see every setting there is. */
function ensureInit(ctx) {
  if (!ctx.settingsExists && !ctx.settingsProblem) { saveSettings(ctx, clone(DEFAULTS)); ctx.settingsExists = true; return true; }
  return false;
}

/** Calorie and macro targets: the settings' own numbers, else from height, weight, age, sex, activity and goal, else 2000 kcal. */
export function targets(s, latestWeight = null) {
  const b = s.身體 ?? {};
  const w = parseNum(latestWeight ?? b.體重) ?? parseNum(b.體重), h = parseNum(b.身高);
  const age = parseNum(b.年齡) ?? 30;
  const factor = { 久坐: 1.2, 輕度: 1.375, 中度: 1.55, 高度: 1.725 }[b.活動量] ?? 1.375;
  const r50 = (x) => Math.round(x / 50) * 50, r5 = (x) => Math.round(x / 5) * 5;
  let kcal = 2000, basis = "一般成人參考值", bmr = null;
  if (w && h) {
    bmr = 10 * w + 6.25 * h - 5 * age + (b.性別 === "男" ? 5 : b.性別 === "女" ? -161 : -78);
    let tdee = bmr * factor;
    if (b.目標 === "減重") tdee = Math.max(tdee * 0.85, bmr, 1200);
    else if (b.目標 === "增肌") tdee *= 1.1;
    kcal = r50(tdee);
    basis = "身高體重估算";
  }
  const t = s.熱量目標 ?? {};
  const own = parseNum(t.熱量);
  if (own) { kcal = own; basis = "自己設定"; }
  const perKg = { 減重: 1.6, 增肌: 1.8 }[b.目標] ?? 1.2;
  const protein = parseNum(t.蛋白質) ?? (w ? r5(w * perKg) : r5((kcal * 0.2) / 4));
  const fat = parseNum(t.脂肪) ?? r5((kcal * 0.3) / 9);
  const carbs = parseNum(t.碳水) ?? Math.max(0, r5((kcal - protein * 4 - fat * 9) / 4));
  return { 熱量: kcal, 蛋白質: protein, 脂肪: fat, 碳水: carbs, basis, bmr: bmr === null ? null : Math.round(bmr) };
}

// ───────────────────────── tables ─────────────────────────

export const COLUMNS = {
  帳: ["日期", "時間", "類型", "金額", "分類", "帳戶", "桶", "內容", "備註", "來源", "id"],
  熱量: ["日期", "時間", "餐別", "內容", "熱量下限", "熱量上限", "蛋白質", "脂肪", "碳水", "來源", "id"],
  健身: ["日期", "動作", "組數", "次數或秒數", "備註", "id"],
  體重: ["日期", "體重", "體脂", "備註"],
  食物庫: ["名稱", "份量", "熱量", "蛋白質", "脂肪", "碳水"],
};
const TABLES = {
  帳: { dir: "帳本", prefix: "b", legacyRoot: true },
  熱量: { dir: "熱量", prefix: "k", legacyRoot: true },
  健身: { dir: "健身", prefix: "w" },
  體重: { file: "體重.csv", prefix: "t" },
};
/** Header names older files may use, per field. */
const ALIASES = {
  帳: { date: ["日期", "date"], time: ["時間", "时间", "time"], type: ["類型", "类型", "收支", "type"], amount: ["金額", "金额", "amount"], category: ["分類", "分类", "類別", "类别", "category"], account: ["帳戶", "账户", "account"], bucket: ["桶", "bucket"], content: ["內容", "内容", "項目", "项目", "品項", "名稱", "content", "item"], note: ["備註", "备注", "note"], source: ["來源", "来源", "source"], id: ["id", "編號"] },
  熱量: { date: ["日期", "date"], time: ["時間", "时间", "time"], meal: ["餐別", "餐别", "meal"], content: ["內容", "内容", "項目", "食物", "名稱", "content"], kcal: ["熱量", "热量", "卡路里", "大卡", "kcal"], lo: ["熱量下限"], hi: ["熱量上限"], protein: ["蛋白質", "蛋白质", "protein"], fat: ["脂肪", "fat"], carbs: ["碳水", "碳水化合物", "carbs"], note: ["備註", "备注", "note"], source: ["來源", "来源", "source"], id: ["id", "編號"] },
  健身: { date: ["日期", "date"], action: ["動作", "动作", "項目", "運動"], sets: ["組數", "组数"], reps: ["次數或秒數", "次數", "秒數"], note: ["備註", "备注"], id: ["id", "編號"] },
  體重: { date: ["日期", "date"], weight: ["體重", "体重", "weight"], fat: ["體脂", "体脂", "體脂率"], note: ["備註", "备注"] },
};
const REQUIRED = { 帳: ["date", "amount"], 熱量: ["date"], 健身: ["date", "action"], 體重: ["date", "weight"] };
const INCOME_WORDS = /收入|薪|獎金|奖金|紅包收|退款|利息|股利|入帳/;

function headerMap(table, header) {
  const map = {};
  for (const [field, names] of Object.entries(ALIASES[table])) {
    const i = header.findIndex((h) => names.includes(h));
    if (i >= 0) map[field] = i;
  }
  if (!REQUIRED[table].every((f) => map[f] !== undefined)) return null;
  if (table === "熱量" && map.kcal === undefined && map.lo === undefined) return null;
  return map;
}

function normType(v) {
  const s = norm(v);
  if (!s) return null;
  if (["支出", "花費", "花", "支", "expense", "out"].includes(s)) return "支出";
  if (["收入", "進帳", "入帳", "收", "income", "in"].includes(s)) return "收入";
  if (["轉帳", "转账", "轉", "transfer"].includes(s)) return "轉帳";
  return undefined;
}

function rowFromCells(table, map, cells, { today, yearHint, legacy }) {
  const cell = (k) => (map[k] === undefined ? "" : str(cells[map[k]]));
  const date = parseDate(cell("date"), today, yearHint);
  if (!date) return null;
  const time = parseTime(cell("time")) || "";
  if (table === "帳") {
    const cents = parseMoney(cell("amount"));
    if (cents === null) return null;
    let content = cell("content"), note = cell("note");
    if (legacy && !content && note) { content = note; note = ""; }
    const type = map.type === undefined ? null : normType(cell("type")) ?? "支出";
    return { table, date, time, type, cents: type ? Math.abs(cents) : cents, rawCents: cents, category: cell("category"), account: cell("account"), bucket: cell("bucket"), content, note, source: cell("source"), id: cell("id") };
  }
  if (table === "熱量") {
    let lo = parseNum(cell("lo")), hi = parseNum(cell("hi"));
    if (lo === null && hi === null) { const r = parseRange(cell("kcal")); if (!r) return null; [lo, hi] = r; }
    lo ??= hi; hi ??= lo;
    let content = cell("content"), note = cell("note");
    if (legacy && !content && note) { content = note; note = ""; }
    const macro = (k) => { const r = parseRange(cell(k)); return r ? (r[0] + r[1]) / 2 : null; };
    return { table, date, time, meal: cell("meal"), content, lo: Math.min(lo, hi), hi: Math.max(lo, hi), protein: macro("protein"), fat: macro("fat"), carbs: macro("carbs"), note, source: cell("source"), id: cell("id") };
  }
  if (table === "健身") {
    const sets = parseNum(cell("sets"));
    return { table, date, action: cell("action"), sets: sets === null ? 1 : Math.max(1, Math.round(sets)), reps: cell("reps"), note: cell("note"), id: cell("id") };
  }
  const weight = parseNum(cell("weight"));
  if (weight === null) return null;
  return { table, date, weight, fat: parseNum(cell("fat")), note: cell("note"), id: "" };
}

function toCells(table, r) {
  if (table === "帳") return [r.date, r.time, r.type, plainMoney(r.cents), r.category, r.account, r.bucket, r.content, r.note, r.source, r.id];
  if (table === "熱量") return [r.date, r.time, r.meal, r.content, numCell(r.lo), numCell(r.hi), numCell(r.protein), numCell(r.fat), numCell(r.carbs), r.source, r.id];
  if (table === "健身") return [r.date, r.action, r.sets, r.reps, r.note, r.id];
  return [r.date, numCell(r.weight), numCell(r.fat), r.note];
}
const serialize = (table, rows) => "﻿" + [COLUMNS[table], ...rows.map((r) => toCells(table, r))].map(csvLine).join("\n") + "\n";

/** Every file of a table: 生活/<dir>/YYYY-MM.csv, and for 帳 and 熱量 the older skill's <workspace>/<dir>/YYYY-MM.csv. */
function sources(p, table) {
  const t = TABLES[table];
  if (t.file) {
    const file = path.join(p.life, t.file);
    return fs.existsSync(file) ? [{ file, month: null, legacyRoot: false }] : [];
  }
  const out = [];
  const scan = (dir, legacyRoot) => {
    let names = [];
    try { names = fs.readdirSync(dir); } catch { return; }
    for (const n of names) { const m = n.match(/^(\d{4})-(\d{2})\.csv$/); if (m) out.push({ file: path.join(dir, n), month: `${m[1]}-${m[2]}`, legacyRoot }); }
  };
  scan(path.join(p.life, t.dir), false);
  if (t.legacyRoot) scan(path.join(p.ws, t.dir), true);
  return out.sort((a, b) => a.month.localeCompare(b.month) || Number(a.legacyRoot) - Number(b.legacyRoot));
}

function readTableFile(src, table, ctx) {
  const res = { ...src, kind: "empty", rows: [], bad: 0, header: [] };
  const text = readText(src.file);
  if (text === null) return res;
  const grid = parseCsv(text);
  if (grid.length === 0) return res;
  const header = grid[0].map((h) => str(h));
  res.header = header;
  const isNew = header.join(",") === COLUMNS[table].join(",") && !src.legacyRoot;
  const map = headerMap(table, header);
  if (!map) { res.kind = "unknown"; res.bad = grid.length - 1; return res; }
  res.kind = isNew ? "new" : "legacy";
  const yearHint = src.month ? Number(src.month.slice(0, 4)) : null;
  for (let i = 1; i < grid.length; i++) {
    const row = rowFromCells(table, map, grid[i], { today: ctx.now.date, yearHint, legacy: !isNew });
    if (!row) { res.bad++; continue; }
    Object.assign(row, { file: src.file, legacy: !isNew, line: i });
    res.rows.push(row);
  }
  if (table === "帳" && map.type === undefined) {
    // An older file says income and expense by the sign (the earlier app skill wrote expenses negative), or not at all.
    const anyNegative = res.rows.some((r) => r.rawCents < 0);
    for (const r of res.rows) {
      r.type = r.rawCents < 0 ? "支出" : anyNegative || INCOME_WORDS.test(r.category) || ctx.s.收入分類.includes(r.category) ? "收入" : "支出";
      r.cents = Math.abs(r.rawCents);
    }
  }
  if (table === "體重") giveWeightIds(res.rows);
  return res;
}

/**
 * 體重.csv has no id column (adding one would turn every existing file into an old-format file that needs `migrate --yes`),
 * so a weighing's id is computed from what the row says: t<YYMMDD>-<4 base36 of a hash of weight|fat|note>. An id from the
 * row's position within the day (the earlier t<YYMMDD>-2) named another row once one was deleted: a repeated `undo` (a
 * retried tool call, or the model reusing the id) then deleted the other weighing of that day. A content id stops naming
 * anything once its row is gone or changed. The hash is over the cells as written, so the id read back is the one handed out.
 */
function weightBaseId(r) {
  const h = crypto.createHash("sha256").update(`${numCell(r.weight)}|${numCell(r.fat)}|${r.note ?? ""}`).digest();
  return `t${r.date.replace(/-/g, "").slice(2)}-${h.readUInt32BE(0).toString(36).padStart(4, "0").slice(-4)}`;
}
/** Gives [rows] (in file order) their ids; only rows with the same id base (identical rows) get -2, -3 ... [seen] counts bases already given. */
function giveWeightIds(rows, seen = new Map()) {
  for (const r of rows) {
    const base = weightBaseId(r), n = (seen.get(base) ?? 0) + 1;
    seen.set(base, n);
    r.id = n > 1 ? `${base}-${n}` : base;
  }
  return seen;
}

/** All files and rows of a table (cached for the run). */
function data(ctx, table) {
  ctx.cache ??= {};
  if (!ctx.cache[table]) {
    const files = sources(ctx.p, table).map((s) => readTableFile(s, table, ctx));
    ctx.cache[table] = { files, rows: files.flatMap((f) => f.rows) };
  }
  return ctx.cache[table];
}
const rowsOf = (ctx, table) => data(ctx, table).rows;
const targetFile = (p, table, date) => (TABLES[table].file ? path.join(p.life, TABLES[table].file) : path.join(p.life, TABLES[table].dir, `${monthOf(date)}.csv`));

function newId(table, date, taken) {
  const d = date.replace(/-/g, "").slice(2);
  for (;;) {
    const r = crypto.randomBytes(4).readUInt32BE(0).toString(36).padStart(4, "0").slice(-4);
    const id = `${TABLES[table].prefix}${d}-${r}`;
    if (!taken.has(id)) { taken.add(id); return id; }
  }
}

function dupKey(r) {
  if (r.table === "帳") return `${r.date}|${r.type}|${r.cents}|${norm(r.content || r.note || r.category)}`;
  if (r.table === "熱量") return `${r.date}|${norm(r.meal)}|${norm(r.content)}|${Math.round(r.lo)}|${Math.round(r.hi)}`;
  if (r.table === "健身") return `${r.date}|${norm(r.action)}|${r.sets}|${norm(r.reps)}`;
  return `${r.date}|${r.weight}`;
}

// ───────────────────────── food library ─────────────────────────

function foodLibrary(ctx) {
  if (ctx.food) return ctx.food;
  const text = readText(ctx.p.food);
  const items = [];
  if (text !== null) {
    const grid = parseCsv(text);
    const header = (grid[0] ?? []).map(str);
    const at = (names) => header.findIndex((h) => names.includes(h));
    const idx = { name: at(["名稱", "名称", "食物"]), size: at(["份量", "分量"]), kcal: at(["熱量", "热量", "大卡"]), protein: at(["蛋白質", "蛋白质"]), fat: at(["脂肪"]), carbs: at(["碳水", "碳水化合物"]) };
    for (const cells of grid.slice(1)) {
      const name = str(cells[idx.name]);
      const kcal = parseNum(str(cells[idx.kcal]));
      if (!name || kcal === null) continue;
      const n = (k) => (idx[k] < 0 ? null : parseNum(str(cells[idx[k]])));
      items.push({ name, size: idx.size < 0 ? "" : str(cells[idx.size]), kcal, protein: n("protein"), fat: n("fat"), carbs: n("carbs") });
    }
  }
  ctx.food = items;
  return items;
}
const findFood = (ctx, name) => foodLibrary(ctx).find((f) => norm(f.name) === norm(name));
function saveFood(ctx, items) {
  writeAtomic(ctx.p.food, "﻿" + [COLUMNS.食物庫, ...items.map((f) => [f.name, f.size, numCell(f.kcal), numCell(f.protein), numCell(f.fat), numCell(f.carbs)])].map(csvLine).join("\n") + "\n");
  ctx.food = items;
}

// ───────────────────────── buckets ─────────────────────────

const stepOf = (s) => (ZERO_DECIMALS.has(s.幣別.toUpperCase()) ? 100 : 1);

/** Splits [cents] by the rules (percent) into whole currency units; the parts always add up to [cents] (largest remainder). */
export function allocate(cents, rules, step = 100) {
  const total = rules.reduce((a, r) => a + r.比例, 0);
  const parts = rules.map((r) => ({ name: r.名稱, exact: (cents * r.比例) / 100 }));
  if (total < 100 - 1e-9) parts.push({ name: "未分配", exact: (cents * (100 - total)) / 100 });
  for (const p of parts) p.cents = Math.floor(p.exact / step) * step;
  let left = cents - parts.reduce((a, p) => a + p.cents, 0);
  const order = parts.map((p, i) => i).sort((a, b) => (parts[b].exact - parts[b].cents) - (parts[a].exact - parts[a].cents) || a - b);
  for (let k = 0; left >= step && k < order.length * 4; k++) { parts[order[k % order.length]].cents += step; left -= step; }
  if (left !== 0) parts[order[0]].cents += left;
  return parts.map(({ name, cents: c }) => ({ name, cents: c }));
}
const allocationCell = (parts) => parts.map((p) => `${p.name} ${plainMoney(p.cents)}`).join("、");
function parseAllocation(cell, names) {
  const s = str(cell);
  if (!s || !/\d/.test(s)) return null;
  const out = [];
  for (const part of s.split(/[、;；]/)) {
    const m = part.trim().match(/^(.+?)\s*[:：=]?\s*(-?\d[\d,]*(?:\.\d+)?)$/);
    if (!m || !(names.includes(m[1].trim()) || m[1].trim() === "未分配")) return null;
    out.push({ name: m[1].trim(), cents: parseMoney(m[2]) });
  }
  return out;
}
const bucketNames = (s) => s.桶.map((b) => b.名稱);
/** The bucket an expense is paid from: the one written on the row, else its category's, else the default (the first). */
function expenseBucket(s, r) {
  const names = bucketNames(s);
  if (names.includes(r.bucket)) return { name: r.bucket, guessed: false };
  const n = s.分類對應桶[r.category] ?? s.預設桶;
  return { name: names.includes(n) ? n : names[0], guessed: true };
}
const splitArrow = (v) => str(v).split(/\s*(?:→|->|=>|＞|>)\s*/).map((x) => x.trim()).filter(Boolean);

/** Each bucket's balance on [upto]: opening amounts, income splits, spending and transfers since 桶開始月份 (or only this month without 桶結轉). */
function bucketState(ctx, upto) {
  const s = ctx.s;
  if (s.桶.length === 0) return null;
  const names = bucketNames(s);
  const rows = rowsOf(ctx, "帳");
  const month = monthOf(upto);
  const first = rows.length ? rows.reduce((a, r) => (r.date < a ? r.date : a), upto) : upto;
  const start = /^\d{4}-\d{2}$/.test(s.桶開始月份) ? s.桶開始月份 : monthOf(first);
  const from = s.桶結轉 === false ? month : start;
  const state = new Map(names.map((n) => [n, { name: n, carry: 0, inflow: 0, outflow: 0, transfer: 0 }]));
  const get = (n) => { if (!state.has(n)) state.set(n, { name: n, carry: 0, inflow: 0, outflow: 0, transfer: 0 }); return state.get(n); };
  // A bucket's 起始 is the money it already had when the buckets started: an opening balance, carried like any other.
  if ((s.桶結轉 !== false && month >= start) || month === start) for (const b of s.桶) get(b.名稱).carry += parseMoney(b.起始 ?? 0) ?? 0;
  let guessed = 0;
  for (const r of rows) {
    if (r.date > upto || monthOf(r.date) < from) continue;
    const slot = monthOf(r.date) < month ? "carry" : null;
    const add = (n, c, kind) => { const b = get(n); if (slot) b.carry += c; else b[kind] += c; };
    if (r.type === "收入") {
      const split = parseAllocation(r.bucket, names);
      if (split) split.forEach((p) => add(p.name, p.cents, "inflow"));
      else if (names.includes(r.bucket)) add(r.bucket, r.cents, "inflow");
      else { guessed++; allocate(r.cents, s.桶, stepOf(s)).forEach((p) => add(p.name, p.cents, "inflow")); }
    } else if (r.type === "支出") {
      const b = expenseBucket(s, r);
      if (b.guessed) guessed++;
      add(b.name, -r.cents, "outflow");
    } else if (r.type === "轉帳") {
      const [a, b] = splitArrow(r.bucket);
      if (names.includes(a) && names.includes(b)) { add(a, -r.cents, "transfer"); add(b, r.cents, "transfer"); }
    }
  }
  const list = [...state.values()].map((b) => ({ ...b, outflow: -b.outflow, balance: b.carry + b.inflow + b.outflow + b.transfer, percent: s.桶.find((x) => x.名稱 === b.name)?.比例 ?? null }));
  return { start, carryOver: s.桶結轉 !== false, buckets: list.filter((b) => b.percent !== null || b.balance !== 0), guessed };
}

// ───────────────────────── rows from the agent ─────────────────────────

class UserError extends Error {
  constructor(message, code = 2) { super(message); this.code = code; }
}
const get = (o, ...keys) => { for (const k of keys) if (o[k] !== undefined && o[k] !== null && o[k] !== "") return o[k]; return undefined; };

function tableOf(it) {
  const t = norm(get(it, "表", "table") ?? "");
  const map = { 帳: "帳", 帳本: "帳", 記帳: "帳", 花費: "帳", 收支: "帳", ledger: "帳", 熱量: "熱量", 飲食: "熱量", 吃: "熱量", 食物: "熱量", food: "熱量", kcal: "熱量", 健身: "健身", 運動: "健身", workout: "健身", 體重: "體重", weight: "體重" };
  if (t) return map[t] ?? null;
  if (get(it, "金額", "amount") !== undefined) return "帳";
  if (get(it, "動作") !== undefined) return "健身";
  if (get(it, "餐別", "熱量", "熱量下限", "熱量上限") !== undefined) return "熱量";
  if (get(it, "體重") !== undefined) return "體重";
  return null;
}

function guessMeal(time, meals) {
  const h = Number((time || "12:00").slice(0, 2));
  const pick = h >= 5 && h < 10 ? "早餐" : h >= 10 && h < 14 ? "午餐" : h >= 14 && h < 17 ? "點心" : h >= 17 && h < 21 ? "晚餐" : "宵夜";
  return meals.includes(pick) || meals.length === 0 ? pick : meals[0];
}

/** One item the agent passed, as a row (without its id yet). Returns { row, notes } or throws UserError. */
function rowFromItem(ctx, it, forced = null) {
  if (!isObj(it)) throw new UserError("每一筆都要是 JSON 物件，例如 {\"表\":\"帳\",\"金額\":120,\"分類\":\"午餐\"}");
  const table = forced ?? tableOf(it);
  if (!table) throw new UserError("看不出是哪一種紀錄，加上 \"表\": \"帳\"、\"熱量\"、\"健身\" 或 \"體重\"");
  const s = ctx.s, today = ctx.now.date, notes = [];
  const dateIn = get(it, "日期", "date");
  const date = parseDate(dateIn ?? "", today);
  if (!date) throw new UserError(`日期「${dateIn}」看不懂，寫成 2026-11-14、11/14、今天或昨天`);
  if (date > today) notes.push(`${showDate(date, ctx)} 是未來的日期，確認一下是不是打錯了`);
  const timeIn = get(it, "時間", "time");
  let time = parseTime(timeIn ?? "");
  if (time === null) throw new UserError(`時間「${timeIn}」看不懂，寫成 12:30`);
  if (!time && date === today && table !== "健身" && table !== "體重") time = ctx.now.time;
  const content = str(get(it, "內容", "項目", "品項", "名稱", "食物", "content"));
  const note = str(get(it, "備註", "note"));
  const source = str(get(it, "來源", "source")) || "對話";

  if (table === "帳") {
    const typeIn = get(it, "類型", "type");
    let type = typeIn === undefined ? "支出" : normType(typeIn);
    if (!type) throw new UserError(`類型「${typeIn}」看不懂，用 支出、收入 或 轉帳`);
    const amountIn = get(it, "金額", "amount");
    let cents = parseMoney(amountIn ?? "");
    if (cents === null) throw new UserError(`金額「${amountIn ?? ""}」看不懂${content ? `（${content}）` : ""}`);
    if (cents === 0) throw new UserError(`金額是 0${content ? `（${content}）` : ""}`);
    if (cents < 0) { cents = -cents; if (typeIn === undefined) type = "支出"; }
    const category = str(get(it, "分類", "類別", "category")) || (type === "收入" ? "其他收入" : type === "轉帳" ? "轉帳" : "其他");
    const known = type === "收入" ? s.收入分類 : s.支出分類;
    if (type !== "轉帳" && !known.includes(category)) notes.push(`「${category}」不在${type === "收入" ? "收入" : "支出"}分類清單裡（要加的話：set ${type === "收入" ? "收入" : "支出"}分類 +${category}）`);
    let account = str(get(it, "帳戶", "account"));
    const from = str(get(it, "從", "from")), to = str(get(it, "到", "to"));
    if (type === "轉帳" && !account && from && to) account = `${from}→${to}`;
    if (!account) account = s.預設帳戶;
    for (const a of splitArrow(account)) if (!s.帳戶.includes(a)) notes.push(`帳戶「${a}」不在清單裡（要加的話：set 帳戶 +${a}）`);
    let bucket = str(get(it, "桶", "bucket"));
    if (s.桶.length) {
      const names = bucketNames(s);
      if (type === "支出") {
        bucket ||= expenseBucket(s, { bucket: "", category }).name;
        if (!names.includes(bucket)) throw new UserError(`沒有「${bucket}」這個桶（有：${names.join("、")}）`);
      } else if (type === "收入") {
        if (bucket && !names.includes(bucket) && !parseAllocation(bucket, names)) throw new UserError(`沒有「${bucket}」這個桶（有：${names.join("、")}）`);
        if (!bucket) bucket = allocationCell(allocate(cents, s.桶, stepOf(s)));
      } else if (bucket) {
        const [a, b] = splitArrow(bucket);
        if (!names.includes(a) || !names.includes(b)) throw new UserError(`桶之間轉帳寫成「生活費→娛樂費」（有：${names.join("、")}）`);
      }
    }
    return { row: { table, date, time, type, cents, category, account, bucket, content, note, source, id: "" }, notes };
  }

  if (table === "熱量") {
    if (!content) throw new UserError("熱量要寫吃了什麼（內容）");
    const meal = str(get(it, "餐別", "meal")) || guessMeal(time || ctx.now.time, s.餐別);
    const servings = parseNum(get(it, "份數", "份") ?? 1) ?? 1;
    let range = null, protein = null, fat = null, carbs = null, fromLibrary = false;
    const loIn = get(it, "熱量下限"), hiIn = get(it, "熱量上限");
    if (loIn !== undefined || hiIn !== undefined) {
      const lo = parseNum(loIn ?? hiIn), hi = parseNum(hiIn ?? loIn);
      if (lo === null || hi === null) throw new UserError(`「${content}」的熱量看不懂`);
      range = [Math.min(lo, hi), Math.max(lo, hi)];
    } else if (get(it, "熱量", "kcal") !== undefined) {
      range = parseRange(get(it, "熱量", "kcal"));
      if (!range) throw new UserError(`「${content}」的熱量「${get(it, "熱量", "kcal")}」看不懂，給範圍，例如 [500, 650]`);
    }
    const macro = (k) => { const v = get(it, k); if (v === undefined) return null; const r = parseRange(v); if (!r || r[0] < 0) throw new UserError(`「${content}」的${k}看不懂`); return (r[0] + r[1]) / 2; };
    protein = macro("蛋白質"); fat = macro("脂肪"); carbs = macro("碳水");
    if (!range) {
      const f = findFood(ctx, content);
      if (!f) throw new UserError(`「${content}」沒有熱量，給一個範圍，例如 "熱量": [500, 650]（我的食物庫裡沒有這一樣）`);
      range = [f.kcal * servings, f.kcal * servings];
      protein ??= f.protein === null ? null : f.protein * servings;
      fat ??= f.fat === null ? null : f.fat * servings;
      carbs ??= f.carbs === null ? null : f.carbs * servings;
      fromLibrary = true;
      notes.push(`「${content}」用我的食物庫的數字（${f.size || "1 份"} ${fmtInt(f.kcal)} 大卡${servings !== 1 ? ` × ${fmtDec(servings)}` : ""}）`);
    } else if (servings !== 1) range = [range[0] * servings, range[1] * servings];
    if (range[0] < 0 || range[1] > 10000) throw new UserError(`「${content}」的熱量 ${fmtKcal(range[0], range[1])} 不合理`);
    if (!s.餐別.includes(meal)) notes.push(`餐別「${meal}」不在清單裡（${s.餐別.join("、")}）`);
    return { row: { table, date, time, meal, content, lo: range[0], hi: range[1], protein, fat, carbs, note: "", source: fromLibrary && source === "對話" ? "食物庫" : source, id: "" }, notes };
  }

  if (table === "健身") {
    const action = str(get(it, "動作", "項目", "運動")) || content;
    if (!action) throw new UserError("健身要寫做了什麼動作");
    const setsIn = get(it, "組數", "組");
    const sets = setsIn === undefined ? 1 : parseNum(setsIn);
    if (sets === null || sets < 1 || sets > 100 || !Number.isInteger(sets)) throw new UserError(`「${action}」的組數「${setsIn}」看不懂`);
    let reps = str(get(it, "次數或秒數", "次數", "下"));
    const secs = get(it, "秒數", "秒");
    if (!reps && secs !== undefined) reps = `${str(secs).replace(/秒$/, "")}秒`;
    const mins = get(it, "分鐘");
    if (!reps && mins !== undefined) reps = `${str(mins).replace(/分鐘$/, "")}分鐘`;
    return { row: { table, date, action, sets, reps, note, id: "" }, notes };
  }

  const weight = parseNum(get(it, "體重", "weight") ?? "");
  if (weight === null || weight < 20 || weight > 400) throw new UserError(`體重「${get(it, "體重") ?? ""}」看不懂（公斤）`);
  const fatIn = get(it, "體脂", "體脂率");
  const fat = fatIn === undefined ? null : parseNum(fatIn);
  if (fatIn !== undefined && (fat === null || fat < 1 || fat > 80)) throw new UserError(`體脂「${fatIn}」看不懂（%）`);
  return { row: { table, date, weight, fat, note, id: "" }, notes };
}

// ───────────────────────── wording ─────────────────────────

const unit = (ctx) => ctx.s.單位 || "元";
const m$ = (ctx, cents) => `${fmtMoney(cents)} ${unit(ctx)}`;

function describe(ctx, r, withId = true) {
  const parts = [];
  if (r.table === "帳") {
    parts.push(`${r.type} ${m$(ctx, r.cents)}`, r.category, r.content, r.account);
    if (ctx.s.桶.length && r.bucket) parts.push(r.type === "收入" && /\d/.test(r.bucket) ? `分到 ${r.bucket.replace(/(-?\d+(?:\.\d+)?)/g, (n) => fmtMoney(parseMoney(n)))}` : `${r.bucket}`);
  } else if (r.table === "熱量") {
    const macros = [["蛋白質", r.protein], ["脂肪", r.fat], ["碳水", r.carbs]].filter(([, v]) => v !== null).map(([k, v]) => `${k} ${fmtInt(v)} g`).join("、");
    parts.push(`熱量 ${fmtKcal(r.lo, r.hi)} 大卡`, r.meal, r.content + (macros ? `（${macros}）` : ""));
  } else if (r.table === "健身") parts.push(`健身 ${r.action} ${r.sets} 組${r.reps ? ` × ${r.reps}` : ""}`, r.note);
  else parts.push(`體重 ${fmtDec(r.weight)} kg${r.fat !== null ? `（體脂 ${fmtDec(r.fat)}%）` : ""}`, r.note);
  const when = showDate(r.date, ctx) + (r.time ? ` ${r.time}` : "");
  return `${parts.filter(Boolean).join("｜")}　${when}${withId ? (r.id ? `　編號 ${r.id}` : r.legacy ? "　（舊格式檔，沒有編號）" : "") : ""}`;
}

// ───────────────────────── summaries ─────────────────────────

function ledgerSum(rows) {
  const out = { expense: 0, income: 0, byCategory: new Map(), count: 0 };
  for (const r of rows) {
    if (r.type === "支出") { out.expense += r.cents; out.byCategory.set(r.category || "其他", (out.byCategory.get(r.category || "其他") ?? 0) + r.cents); out.count++; }
    else if (r.type === "收入") { out.income += r.cents; out.count++; }
  }
  return out;
}
const sortedCats = (m) => [...m.entries()].sort((a, b) => b[1] - a[1]);

function calorieSum(rows) {
  const out = { lo: 0, hi: 0, protein: 0, fat: 0, carbs: 0, missing: 0, count: rows.length, given: { protein: 0, fat: 0, carbs: 0 } };
  for (const r of rows) {
    out.lo += r.lo; out.hi += r.hi;
    if (r.protein === null && r.fat === null && r.carbs === null) out.missing++;
    for (const k of ["protein", "fat", "carbs"]) if (r[k] !== null) { out[k] += r[k]; out.given[k]++; }
  }
  return out;
}

function latestWeight(ctx, upto) {
  return rowsOf(ctx, "體重").filter((r) => r.date <= upto).sort((a, b) => a.date.localeCompare(b.date)).at(-1) ?? null;
}

function budgetOf(ctx, date) {
  const s = ctx.s;
  const month = monthOf(date);
  const monthRows = rowsOf(ctx, "帳").filter((r) => monthOf(r.date) === month && r.date <= date);
  const sum = ledgerSum(monthRows);
  const [y, m] = month.split("-").map(Number);
  const daysLeft = daysInMonth(y, m) - Number(date.slice(8, 10)) + 1;
  const step = stepOf(s);
  const monthly = s.每月預算 > 0 ? (() => {
    const budget = Math.round(s.每月預算 * 100), remaining = budget - sum.expense;
    return { budget, remaining, daysLeft, perDay: remaining > 0 ? Math.floor(remaining / daysLeft / step) * step : 0 };
  })() : null;
  const categories = Object.entries(s.分類預算).filter(([, v]) => parseMoney(v) > 0).map(([cat, v]) => {
    const budget = parseMoney(v), spent = sum.byCategory.get(cat) ?? 0;
    return { category: cat, budget, spent, remaining: budget - spent };
  });
  return { monthExpense: sum.expense, monthIncome: sum.income, monthly, categories };
}

function todayReport(ctx, date) {
  const s = ctx.s, f = s.功能;
  const rep = { date, weekday: weekday(date) };
  if (f.記帳) {
    const rows = rowsOf(ctx, "帳").filter((r) => r.date === date);
    const sum = ledgerSum(rows);
    const b = budgetOf(ctx, date);
    const buckets = bucketState(ctx, date);
    if (buckets) {
      const spent = new Map();
      for (const r of rows) if (r.type === "支出") { const n = expenseBucket(s, r).name; spent.set(n, (spent.get(n) ?? 0) + r.cents); }
      for (const b of buckets.buckets) b.today = spent.get(b.name) ?? 0;
    }
    rep.ledger = {
      expense: money(sum.expense), income: money(sum.income), count: sum.count,
      byCategory: sortedCats(sum.byCategory).map(([category, c]) => ({ category, amount: money(c) })),
      monthExpense: money(b.monthExpense),
      budget: b.monthly && { monthly: money(b.monthly.budget), remaining: money(b.monthly.remaining), daysLeft: b.monthly.daysLeft, perDay: money(b.monthly.perDay) },
      categoryBudgets: b.categories.map((c) => ({ category: c.category, budget: money(c.budget), spent: money(c.spent), remaining: money(c.remaining) })),
      buckets: buckets && buckets.buckets.map((x) => ({ name: x.name, spentToday: money(x.today ?? 0), balance: money(x.balance) })),
    };
    rep._ledger = { sum, b, buckets };
  }
  if (f.熱量) {
    const rows = rowsOf(ctx, "熱量").filter((r) => r.date === date);
    const c = calorieSum(rows);
    const t = targets(s, latestWeight(ctx, date)?.weight ?? null);
    rep.calories = {
      low: Math.round(c.lo), high: Math.round(c.hi), count: c.count, target: t.熱量, targetBasis: t.basis,
      remainingLow: Math.round(t.熱量 - c.hi), remainingHigh: Math.round(t.熱量 - c.lo),
      protein: { sum: Math.round(c.protein), target: t.蛋白質, rows: c.given.protein }, fat: { sum: Math.round(c.fat), target: t.脂肪, rows: c.given.fat }, carbs: { sum: Math.round(c.carbs), target: t.碳水, rows: c.given.carbs },
      missingMacros: c.missing,
    };
  }
  if (f.健身) rep.workout = { items: rowsOf(ctx, "健身").filter((r) => r.date === date).map((r) => ({ action: r.action, sets: r.sets, reps: r.reps, id: r.id })) };
  if (f.體重) { const w = latestWeight(ctx, date); rep.weight = { latest: w && { date: w.date, weight: w.weight, fat: w.fat } }; }
  return rep;
}

function calorieLine(c) {
  const target = `目標 ${fmtInt(c.target)}`;
  if (c.count === 0) return `熱量：還沒記（${target} 大卡）`;
  const a = c.remainingLow, b = c.remainingHigh;
  const tail = a >= 0 ? `，還有約 ${fmtKcal(a, b)} 大卡` : b < 0 ? `，超過目標約 ${fmtKcal(-b, -a)} 大卡` : "，差不多到目標了";
  return `熱量 ${fmtKcal(c.low, c.high)} 大卡（${target}）${tail}`;
}
function macroLine(c) {
  if (c.count === 0 || c.missingMacros === c.count) return null;
  const parts = [["蛋白質", c.protein], ["脂肪", c.fat], ["碳水", c.carbs]].filter(([, m]) => m.rows > 0).map(([k, m]) => `${k} ${fmtInt(m.sum)}/${fmtInt(m.target)} g`);
  return `${parts.join("、")}${c.missingMacros ? `（${c.missingMacros} 筆沒有營養素）` : ""}`;
}

function todayText(ctx, rep) {
  const isToday = rep.date === ctx.now.date;
  const lines = [`${showDate(rep.date, ctx)}（${rep.weekday}）${isToday ? "今天" : ""}`];
  if (rep.ledger) {
    const { sum, b, buckets } = rep._ledger;
    if (sum.count === 0) lines.push("還沒記帳");
    else {
      if (sum.expense) lines.push(`支出 ${m$(ctx, sum.expense)}：${sortedCats(sum.byCategory).map(([k, v]) => `${k} ${fmtMoney(v)}`).join("、")}`);
      if (sum.income) lines.push(`收入 ${m$(ctx, sum.income)}`);
    }
    if (b.monthly) {
      const mo = b.monthly;
      lines.push(mo.remaining >= 0 ? `本月已花 ${m$(ctx, b.monthExpense)}，預算還剩 ${m$(ctx, mo.remaining)}，之後每天約 ${m$(ctx, mo.perDay)}（含今天剩 ${mo.daysLeft} 天）` : `本月已花 ${m$(ctx, b.monthExpense)}，超出預算 ${m$(ctx, -mo.remaining)}`);
    } else lines.push(`本月已花 ${m$(ctx, b.monthExpense)}`);
    for (const c of b.categories) lines.push(`${c.category}：本月 ${fmtMoney(c.spent)} / 預算 ${fmtMoney(c.budget)}${c.remaining >= 0 ? `，還剩 ${fmtMoney(c.remaining)}` : `，超過 ${fmtMoney(-c.remaining)}`}`);
    if (buckets) lines.push(`桶：${buckets.buckets.map((x) => `${x.name}${x.today ? ` 今天花 ${fmtMoney(x.today)}，` : " "}剩 ${fmtMoney(x.balance)}`).join("、")}`);
  }
  if (rep.calories) { lines.push(calorieLine(rep.calories)); const m = macroLine(rep.calories); if (m) lines.push(m); }
  if (rep.workout && rep.workout.items.length) lines.push(`運動：${rep.workout.items.map((w) => `${w.action} ${w.sets}×${w.reps || "1"}`).join("、")}`);
  if (rep.weight?.latest) lines.push(`體重 ${fmtDec(rep.weight.latest.weight)} kg（${showDate(rep.weight.latest.date, ctx)}）`);
  return lines.join("\n");
}

/** The one-sentence month summary (「這個月手搖飲 1,240 元，比上月多 40%」), comparing like with like. */
function monthSentence(ctx, cur, prev, partial) {
  const total = cur.expense;
  if (total === 0) return "這個月還沒有支出紀錄";
  const than = partial ? "比上月同期" : "比上月";
  let best = null;
  for (const [cat, c] of cur.byCategory) {
    const p = prev.byCategory.get(cat) ?? 0;
    if (p <= 0) continue;
    const change = (c - p) / p;
    if (Math.abs(change) < 0.2 || Math.max(c, p) < total * 0.05) continue;
    if (!best || Math.abs(c - p) > Math.abs(best.c - best.p)) best = { cat, c, p, change };
  }
  if (best) return `這個月${best.cat} ${m$(ctx, best.c)}，${than}${best.change > 0 ? "多" : "少"} ${Math.abs(pct(best.change))}%`;
  if (prev.expense > 0) {
    const change = (total - prev.expense) / prev.expense;
    if (Math.abs(change) < 0.05) return `這個月總共花 ${m$(ctx, total)}，跟上月${partial ? "同期" : ""}差不多`;
    return `這個月總共花 ${m$(ctx, total)}，${than}${change > 0 ? "多" : "少"} ${Math.abs(pct(change))}%`;
  }
  const [cat, c] = sortedCats(cur.byCategory)[0];
  return `這個月花最多的是${cat} ${m$(ctx, c)}，占 ${pct(c / total)}%`;
}

function monthReport(ctx, month) {
  const s = ctx.s, f = s.功能;
  const now = ctx.now.date;
  const partial = month === monthOf(now);
  const day = partial ? Number(now.slice(8, 10)) : 31;
  const prevMonth = addMonths(month, -1);
  const rep = { month, partial, upTo: partial ? now : null };
  if (f.記帳) {
    const all = rowsOf(ctx, "帳");
    const rows = all.filter((r) => monthOf(r.date) === month && (!partial || r.date <= now));
    const prevRows = all.filter((r) => monthOf(r.date) === prevMonth && (!partial || Number(r.date.slice(8, 10)) <= day));
    const cur = ledgerSum(rows), prev = ledgerSum(prevRows);
    const expenses = rows.filter((r) => r.type === "支出");
    const top = [...expenses].sort((a, b) => b.cents - a.cents).slice(0, 5);
    const freq = new Map();
    for (const r of expenses) { const k = r.content || r.category; if (k) freq.set(k, (freq.get(k) ?? 0) + 1); }
    const frequent = [...freq.entries()].filter(([, n]) => n >= 2).sort((a, b) => b[1] - a[1]).slice(0, 3);
    const buckets = bucketState(ctx, partial ? now : `${month}-${pad2(daysInMonth(...month.split("-").map(Number)))}`);
    rep.ledger = {
      expense: money(cur.expense), income: money(cur.income), net: money(cur.income - cur.expense), count: cur.count,
      legacyRows: rows.filter((r) => r.legacy).length,
      byCategory: sortedCats(cur.byCategory).map(([category, c]) => ({ category, amount: money(c), share: cur.expense ? pct(c / cur.expense) : 0, previous: money(prev.byCategory.get(category) ?? 0), change: prev.byCategory.get(category) ? pct((c - prev.byCategory.get(category)) / prev.byCategory.get(category)) : null })),
      previous: { month: prevMonth, expense: money(prev.expense), income: money(prev.income), samePeriod: partial },
      top: top.map((r) => ({ date: r.date, content: r.content || r.category, category: r.category, amount: money(r.cents), id: r.id })),
      frequent: frequent.map(([content, count]) => ({ content, count })),
      buckets: buckets && buckets.buckets.map((b) => ({ name: b.name, carry: money(b.carry), inflow: money(b.inflow), outflow: money(b.outflow), transfer: money(b.transfer), balance: money(b.balance) })),
      budget: s.每月預算 > 0 ? { monthly: s.每月預算, remaining: money(Math.round(s.每月預算 * 100) - cur.expense) } : null,
      sentence: monthSentence(ctx, cur, prev, partial),
    };
    rep._ledger = { cur, top, frequent, buckets };
  }
  if (f.熱量) {
    const rows = rowsOf(ctx, "熱量").filter((r) => monthOf(r.date) === month && (!partial || r.date <= now));
    const days = [...new Set(rows.map((r) => r.date))];
    const c = calorieSum(rows);
    const t = targets(s, latestWeight(ctx, partial ? now : `${month}-31`)?.weight ?? null);
    rep.calories = days.length ? { days: days.length, avgLow: Math.round(c.lo / days.length), avgHigh: Math.round(c.hi / days.length), target: t.熱量 } : { days: 0, target: t.熱量 };
  }
  if (f.健身) {
    const rows = rowsOf(ctx, "健身").filter((r) => monthOf(r.date) === month);
    const by = new Map();
    for (const r of rows) by.set(r.action, (by.get(r.action) ?? 0) + r.sets);
    rep.workout = { days: new Set(rows.map((r) => r.date)).size, sets: [...by.entries()].sort((a, b) => b[1] - a[1]).map(([action, sets]) => ({ action, sets })) };
  }
  if (f.體重) {
    const rows = rowsOf(ctx, "體重").filter((r) => monthOf(r.date) === month).sort((a, b) => a.date.localeCompare(b.date));
    rep.weight = rows.length ? { first: { date: rows[0].date, weight: rows[0].weight }, last: { date: rows.at(-1).date, weight: rows.at(-1).weight }, change: Number((rows.at(-1).weight - rows[0].weight).toFixed(1)) } : null;
  }
  return rep;
}

function monthText(ctx, rep) {
  const [y, m] = rep.month.split("-").map(Number);
  const lines = [`${y} 年 ${m} 月${rep.partial ? `（到 ${showDate(rep.upTo, ctx)}）` : ""}`];
  if (rep.ledger) {
    const L = rep.ledger, { cur, top, frequent } = rep._ledger;
    if (L.count === 0) lines.push("這個月還沒記帳");
    else {
      lines.push(`支出 ${m$(ctx, cur.expense)}、收入 ${m$(ctx, cur.income)}、結餘 ${m$(ctx, cur.income - cur.expense)}`);
      if (L.budget) lines.push(L.budget.remaining >= 0 ? `預算 ${fmtMoney(L.budget.monthly * 100)}，還剩 ${m$(ctx, L.budget.remaining * 100)}` : `預算 ${fmtMoney(L.budget.monthly * 100)}，超出 ${m$(ctx, -L.budget.remaining * 100)}`);
      if (L.byCategory.length) lines.push(`分類：${L.byCategory.slice(0, 8).map((c) => `${c.category} ${fmtMoney(c.amount * 100)}（${c.share}%${c.change !== null ? `，${c.change >= 0 ? "+" : ""}${c.change}%` : ""}）`).join("、")}`);
      if (L.buckets) lines.push(`桶：${L.buckets.map((b) => `${b.name} 剩 ${fmtMoney(b.balance * 100)}`).join("、")}`);
      if (top.length) lines.push(`花最多的：${top.map((r) => `${showDate(r.date, ctx)} ${r.content || r.category} ${fmtMoney(r.cents)}`).join("、")}`);
      if (frequent.length) lines.push(`最常買：${frequent.map(([k, n]) => `${k} ${n} 次`).join("、")}`);
      if (L.legacyRows) lines.push(`（含舊格式帳本 ${L.legacyRows} 筆）`);
    }
    lines.push(`月結：${L.sentence}`);
  }
  if (rep.calories) lines.push(rep.calories.days ? `熱量：記了 ${rep.calories.days} 天，平均每天 ${fmtKcal(rep.calories.avgLow, rep.calories.avgHigh)} 大卡（目標 ${fmtInt(rep.calories.target)}）` : "熱量：這個月還沒記");
  if (rep.workout && rep.workout.days) lines.push(`健身：${rep.workout.days} 天，${rep.workout.sets.slice(0, 5).map((w) => `${w.action} ${w.sets} 組`).join("、")}`);
  if (rep.weight) lines.push(rep.weight.first.date === rep.weight.last.date ? `體重：${fmtDec(rep.weight.last.weight)} kg（${showDate(rep.weight.last.date, ctx)}）` : `體重：${fmtDec(rep.weight.first.weight)} → ${fmtDec(rep.weight.last.weight)} kg（${rep.weight.change > 0 ? "+" : ""}${fmtDec(rep.weight.change)}）`);
  return lines.join("\n");
}

function todayLine(ctx, date) {
  const parts = [];
  if (ctx.s.功能.記帳) parts.push(`支出 ${m$(ctx, ledgerSum(rowsOf(ctx, "帳").filter((r) => r.date === date)).expense)}`);
  if (ctx.s.功能.熱量) {
    const c = calorieSum(rowsOf(ctx, "熱量").filter((r) => r.date === date));
    if (c.count) parts.push(`熱量 ${fmtKcal(c.lo, c.hi)} / ${fmtInt(targets(ctx.s, latestWeight(ctx, date)?.weight ?? null).熱量)} 大卡`);
  }
  return parts.length ? `${date === ctx.now.date ? "今天到目前" : `${showDate(date, ctx)} 合計`}：${parts.join("；")}` : null;
}

function ledgerCard(ctx, rows) {
  const L = rows.filter((r) => r.table === "帳" && r.type !== "轉帳");
  if (L.length === 0) return null;
  const month = monthOf(L[0].date);
  const monthExpense = ledgerSum(rowsOf(ctx, "帳").filter((r) => monthOf(r.date) === month)).expense;
  const total = L.reduce((a, r) => a + (r.type === "收入" ? r.cents : -r.cents), 0);
  const title = L.length === 1 ? L[0].content || L[0].category : `${L[0].content || L[0].category}等 ${L.length} 筆`;
  const sign = total > 0 ? "+" : "";
  // The total is of the entry's month: a back-dated entry from last month is named by its month, not 本月.
  const label = month === monthOf(ctx.now.date) ? "本月支出" : `${Number(month.slice(5))}月支出`;
  return ["<!--dshm-card ledger", `項目: ${title.slice(0, 40)}`, `金額: ${sign}${fmtMoney(total)}`, ...(L.length === 1 ? [`類別: ${L[0].category}`] : []), `${label}: ${monthExpense > 0 ? "-" : ""}${fmtMoney(monthExpense)}`, "-->"].join("\n");
}

// ───────────────────────── commands ─────────────────────────

function readStdin() {
  try { return fs.readFileSync(0, "utf8"); } catch { return ""; }
}
function jsonInput(args, what) {
  const text = (args.positional.join(" ").trim() || (process.stdin.isTTY ? "" : readStdin())).trim();
  if (!text) throw new UserError(`${what} 要給 JSON，例如：add '{"表":"帳","金額":120,"分類":"午餐","內容":"雞腿便當"}'`);
  try { return JSON.parse(text); } catch (e) { throw new UserError(`JSON 看不懂：${e.message}`); }
}

function cmdAdd(ctx, args) {
  let items = jsonInput(args, "add");
  if (!Array.isArray(items)) items = [items];
  if (items.length === 0) throw new UserError("沒有要記的東西");
  if (items.length > 60) throw new UserError("一次最多 60 筆");
  const rows = [], notes = [], skipped = [], errors = [];
  items.forEach((it, i) => {
    try {
      const r = rowFromItem(ctx, it);
      if (!ctx.s.功能[FEATURE[r.row.table]]) { skipped.push(`${r.row.content || r.row.action || r.row.table}（${FEATURE[r.row.table]}功能關著，沒記）`); return; }
      rows.push(r.row); notes.push(...r.notes);
    } catch (e) { if (!(e instanceof UserError)) throw e; errors.push(items.length > 1 ? `第 ${i + 1} 筆：${e.message}` : e.message); }
  });
  if (errors.length) throw new UserError(`沒有記任何東西：\n${errors.map((e) => `・${e}`).join("\n")}`, 2);
  if (rows.length === 0) return { code: 0, text: `沒有記：${skipped.join("、")}`, json: { status: "skipped", rows: [], skipped } };

  // Files an older skill wrote cannot take new rows without being rewritten: ask first (migrate --yes).
  const old = new Set(), foreign = new Set();
  for (const r of rows) {
    const file = targetFile(ctx.p, r.table, r.date);
    const f = data(ctx, r.table).files.find((x) => x.file === file);
    if (f && f.kind === "unknown") foreign.add(path.relative(ctx.p.ws, file));
    else if (f && f.kind !== "new" && f.kind !== "empty") old.add(path.relative(ctx.p.ws, file));
  }
  if (foreign.size) {
    const msg = `沒有記：${[...foreign].join("、")} 的欄位認不出來，不像是這個工具的紀錄。告訴使用者：把那個檔改名或搬到別的資料夾，這個月的新紀錄才能記進去（檔案本身沒有動）。`;
    return { code: 3, text: msg, json: { status: "foreign_file", files: [...foreign] } };
  }
  if (old.size) {
    const msg = `沒有記：${[...old].join("、")} 是舊格式的檔案，新的紀錄沒辦法直接加進去。\n先問使用者要不要升級成新格式（舊資料都會保留，原檔備份到 生活/舊格式備份/）。同意的話執行 migrate --yes，再記一次。`;
    return { code: 3, text: msg, json: { status: "old_format", files: [...old] } };
  }

  const checkDup = ctx.s.重複檢查 !== false && !args.flags.force;
  const dups = [];
  if (checkDup) {
    for (const r of rows) {
      const twin = rowsOf(ctx, r.table).find((x) => x.date === r.date && dupKey(x) === dupKey(r));
      if (twin) dups.push({ row: r, twin });
    }
  }
  if (dups.length) {
    const text = `沒有記任何東西：下面這${dups.length > 1 ? "幾" : ""}筆在同一天已經有一模一樣的了：\n${dups.map((d) => `・${describe(ctx, d.twin)}`).join("\n")}\n先問使用者是不是重複了。真的又買／又做了一次，就加 --force 再記一次；不是的話，把這筆拿掉再記其他的。`;
    return { code: 3, text, json: { status: "duplicate", duplicates: dups.map((d) => ({ id: d.twin.id, existing: describe(ctx, d.twin, false) })) } };
  }

  const created = ensureInit(ctx);
  const byFile = new Map();
  for (const r of rows) {
    const file = targetFile(ctx.p, r.table, r.date);
    if (!byFile.has(file)) byFile.set(file, { table: r.table, rows: [] });
    byFile.get(file).rows.push(r);
  }
  for (const [file, g] of byFile) {
    if (g.table === "體重") {
      // New rows go after the file's rows, so they are counted after them, as the next read will.
      const seen = new Map();
      for (const x of rowsOf(ctx, "體重")) { const b = weightBaseId(x); seen.set(b, (seen.get(b) ?? 0) + 1); }
      giveWeightIds(g.rows, seen);
    } else {
      const taken = new Set(rowsOf(ctx, g.table).map((x) => x.id).filter(Boolean));
      for (const r of g.rows) r.id = newId(g.table, r.date, taken);
    }
    const text = readText(file);
    if (text === null) writeAtomic(file, serialize(g.table, g.rows));
    else fs.appendFileSync(file, (text.length && !text.endsWith("\n") ? "\n" : "") + g.rows.map((r) => csvLine(toCells(g.table, r))).join("\n") + "\n");
  }
  ctx.cache = {};

  // Hints: a likely (not exact) duplicate, and food the owner keeps eating that is not in 我的食物庫 yet.
  const fresh = new Set(rows.map((r) => r.id));
  for (const r of rows.filter((x) => x.table === "帳")) {
    const near = rowsOf(ctx, "帳").find((x) => !fresh.has(x.id) && x.date === r.date && x.type === r.type && x.cents === r.cents && x.category === r.category && norm(x.content) !== norm(r.content));
    if (near) notes.push(`同一天也有一筆 ${near.category} ${m$(ctx, near.cents)}（${near.content || "沒寫內容"}，${near.id || "舊檔"}），如果是同一筆，可以撤銷這次的 ${r.id}`);
  }
  const N = ctx.s.食物庫建議次數;
  for (const r of rows.filter((x) => x.table === "熱量" && x.source !== "食物庫")) {
    if (findFood(ctx, r.content)) continue;
    const since = addDays(ctx.now.date, -90);
    const count = rowsOf(ctx, "熱量").filter((x) => x.date >= since && norm(x.content) === norm(r.content)).length;
    if (count === N) notes.push(`「${r.content}」已經記過 ${count} 次了，可以問使用者要不要存進我的食物庫（food learn ${r.content}），之後就用他自己的數字`);
  }

  const lines = [`已記 ${rows.length} 筆：`, ...rows.map((r) => `・${describe(ctx, r)}`)];
  if (skipped.length) lines.push(`沒記：${skipped.join("、")}`);
  const dates = [...new Set(rows.map((r) => r.date))];
  for (const d of dates) { const t = todayLine(ctx, d); if (t) lines.push(t); }
  if (rows.length) lines.push(`打錯的話：undo ${rows.map((r) => r.id).join(" ")}`);
  for (const n of [...new Set(notes)]) lines.push(`提示：${n}`);
  if (created) lines.push(`（第一次使用：設定在 生活/設定.json，可以跟使用者說什麼都能改）`);
  const card = ledgerCard(ctx, rows);
  if (card) lines.push("記帳卡片（放在回覆最後）：", card);
  return { code: 0, text: lines.join("\n"), json: { status: "ok", rows: rows.map((r) => rowJson(ctx, r)), skipped, notes: [...new Set(notes)], card } };
}

function rowJson(ctx, r) {
  const out = { 表: r.table, id: r.id, 日期: r.date, text: describe(ctx, r, false) };
  if (r.table === "帳") Object.assign(out, { 時間: r.time, 類型: r.type, 金額: money(r.cents), 分類: r.category, 帳戶: r.account, 桶: r.bucket, 內容: r.content, 備註: r.note, 來源: r.source });
  else if (r.table === "熱量") Object.assign(out, { 時間: r.time, 餐別: r.meal, 內容: r.content, 熱量下限: r.lo, 熱量上限: r.hi, 蛋白質: r.protein, 脂肪: r.fat, 碳水: r.carbs, 來源: r.source });
  else if (r.table === "健身") Object.assign(out, { 動作: r.action, 組數: r.sets, 次數或秒數: r.reps, 備註: r.note });
  else Object.assign(out, { 體重: r.weight, 體脂: r.fat, 備註: r.note });
  if (r.legacy) out.舊格式 = true;
  return out;
}

const TABLE_BY_PREFIX = { b: "帳", k: "熱量", w: "健身", t: "體重" };
function findById(ctx, id) {
  const table = TABLE_BY_PREFIX[id[0]];
  if (!table || !/^[bkwt]\d{6}(-[0-9a-z]+){0,2}$/.test(id)) return null;
  const f = data(ctx, table).files.find((x) => x.rows.some((r) => r.id === id));
  if (!f) return null;
  return { table, file: f, row: f.rows.find((r) => r.id === id) };
}
function rewriteFile(ctx, table, f, rows) {
  if (f.kind !== "new") throw new UserError(`${path.relative(ctx.p.ws, f.file)} 是舊格式的檔案，裡面的紀錄不能用編號改`);
  writeAtomic(f.file, serialize(table, rows));
}

function cmdUndo(ctx, args) {
  const ids = args.positional;
  if (ids.length === 0) throw new UserError("undo 要給編號（確認行裡有；或用 recent 看最近幾筆）");
  const found = [], missing = [];
  for (const id of ids) { const x = findById(ctx, id); if (x) found.push(x); else missing.push(id); }
  if (missing.length) throw new UserError(`找不到 ${missing.join("、")}，什麼都沒刪。用 recent 看最近的編號`);
  for (const x of found) if (x.file.kind !== "new") throw new UserError(`${path.relative(ctx.p.ws, x.file.file)} 是舊格式的檔案，裡面的紀錄不能用編號刪`);
  const byFile = new Map();
  for (const x of found) { if (!byFile.has(x.file.file)) byFile.set(x.file.file, { x, ids: new Set() }); byFile.get(x.file.file).ids.add(x.row.id); }
  for (const { x, ids: gone } of byFile.values()) rewriteFile(ctx, x.table, x.file, x.file.rows.filter((r) => !gone.has(r.id)));
  ctx.cache = {};
  const lines = ["已刪掉：", ...found.map((x) => `・${describe(ctx, x.row, false)}`)];
  for (const d of [...new Set(found.map((x) => x.row.date))]) { const t = todayLine(ctx, d); if (t) lines.push(t); }
  return { code: 0, text: lines.join("\n"), json: { status: "ok", removed: found.map((x) => rowJson(ctx, x.row)) } };
}

function itemFromRow(r) {
  if (r.table === "帳") return { 日期: r.date, 時間: r.time, 類型: r.type, 金額: money(r.cents), 分類: r.category, 帳戶: r.account, 桶: r.bucket, 內容: r.content, 備註: r.note, 來源: r.source };
  if (r.table === "熱量") return { 日期: r.date, 時間: r.time, 餐別: r.meal, 內容: r.content, 熱量下限: r.lo, 熱量上限: r.hi, 蛋白質: r.protein, 脂肪: r.fat, 碳水: r.carbs, 來源: r.source };
  if (r.table === "健身") return { 日期: r.date, 動作: r.action, 組數: r.sets, 次數或秒數: r.reps, 備註: r.note };
  return { 日期: r.date, 體重: r.weight, 體脂: r.fat, 備註: r.note };
}

function cmdEdit(ctx, args) {
  const id = args.positional.shift();
  if (!id) throw new UserError("edit 要給編號和要改的欄位，例如 edit b261114-a1b2 '{\"金額\":150}'");
  const x = findById(ctx, id);
  if (!x) throw new UserError(`找不到 ${id}`);
  if (x.file.kind !== "new") throw new UserError(`${path.relative(ctx.p.ws, x.file.file)} 是舊格式的檔案，裡面的紀錄不能用編號改`);
  const change = jsonInput(args, "edit");
  if (!isObj(change)) throw new UserError("要改的欄位寫成 JSON 物件，例如 {\"金額\":150}");
  const before = itemFromRow(x.row);
  const merged = { ...before };
  for (const [k, v] of Object.entries(change)) merged[k] = v;
  if (x.table === "帳" && !("桶" in change) && x.row.type === "收入" && /\d/.test(x.row.bucket) && ("金額" in change || "類型" in change)) merged.桶 = "";
  if (x.table === "熱量" && ("熱量" in change)) { delete merged.熱量下限; delete merged.熱量上限; }
  for (const k of Object.keys(merged)) if (merged[k] === null) delete merged[k];
  const { row, notes } = rowFromItem(ctx, merged, x.table);
  row.id = x.row.id; // keeps the first date when the date changes: undo and edit look the id up in every file
  const target = targetFile(ctx.p, x.table, row.date);
  if (x.table === "體重") {
    // A weighing's id is its date and values (weightBaseId), so the edited row gets the id the next read will give it.
    const rows = x.file.rows.map((r) => (r.id === id ? row : r));
    giveWeightIds(rows);
    rewriteFile(ctx, x.table, x.file, rows);
  } else if (target === x.file.file) rewriteFile(ctx, x.table, x.file, x.file.rows.map((r) => (r.id === id ? row : r)));
  else {
    const other = data(ctx, x.table).files.find((f) => f.file === target);
    if (other && other.kind !== "new" && other.kind !== "empty") throw new UserError(`${path.relative(ctx.p.ws, target)} 是舊格式的檔案，先 migrate --yes`);
    rewriteFile(ctx, x.table, x.file, x.file.rows.filter((r) => r.id !== id));
    const text = readText(target);
    if (text === null) writeAtomic(target, serialize(x.table, [row]));
    else fs.appendFileSync(target, (text.endsWith("\n") || !text ? "" : "\n") + csvLine(toCells(x.table, row)) + "\n");
  }
  ctx.cache = {};
  const lines = ["已改：", `・原本 ${describe(ctx, x.row, false)}`, `・現在 ${describe(ctx, row)}`];
  const t = todayLine(ctx, row.date); if (t) lines.push(t);
  for (const n of notes) lines.push(`提示：${n}`);
  return { code: 0, text: lines.join("\n"), json: { status: "ok", before: rowJson(ctx, x.row), after: rowJson(ctx, row) } };
}

function dateArg(ctx, args) {
  const v = args.flags.date ?? args.positional[0];
  if (v === undefined) return ctx.now.date;
  const d = parseDate(v, ctx.now.date);
  if (!d) throw new UserError(`日期「${v}」看不懂`);
  return d;
}
function monthArg(ctx, v) {
  if (v === undefined || v === true) return monthOf(ctx.now.date);
  const s = halfWidth(str(v));
  if (s === "上個月" || s === "上月") return addMonths(monthOf(ctx.now.date), -1);
  if (s === "這個月" || s === "本月") return monthOf(ctx.now.date);
  let m = s.match(/^(\d{4})[-/年](\d{1,2})月?$/);
  if (m && Number(m[2]) >= 1 && Number(m[2]) <= 12) return `${m[1]}-${pad2(m[2])}`;
  m = s.match(/^(\d{1,2})月?$/);
  if (m && Number(m[1]) >= 1 && Number(m[1]) <= 12) { const y = Number(ctx.now.date.slice(0, 4)); const mm = Number(m[1]); return `${mm > Number(ctx.now.date.slice(5, 7)) ? y - 1 : y}-${pad2(mm)}`; }
  throw new UserError(`月份「${v}」看不懂，寫成 2026-11`);
}

function cmdToday(ctx, args) {
  const rep = todayReport(ctx, dateArg(ctx, args));
  const text = todayText(ctx, rep);
  delete rep._ledger;
  return { code: 0, text, json: rep };
}

function cmdMonth(ctx, args) {
  const rep = monthReport(ctx, monthArg(ctx, args.flags.month ?? args.positional[0]));
  const text = monthText(ctx, rep);
  delete rep._ledger;
  return { code: 0, text, json: rep };
}

function cmdBuckets(ctx, args) {
  const s = ctx.s;
  if (s.桶.length === 0) return { code: 0, text: "還沒設定桶。設定方法：set 桶 '[{\"名稱\":\"生活費\",\"比例\":92},{\"名稱\":\"娛樂費\",\"比例\":8}]'（比例加起來 100）", json: { buckets: [] } };
  const total = s.桶.reduce((a, b) => a + b.比例, 0);
  const lines = [`桶：${s.桶.map((b) => `${b.名稱} ${b.比例}%`).join("、")}${total < 100 ? `（還有 ${fmtDec(100 - total)}% 沒分配）` : ""}`];
  const out = { rules: s.桶.map((b) => ({ name: b.名稱, percent: b.比例 })) };
  const incomeIn = args.flags.income ?? args.positional[0];
  if (incomeIn !== undefined) {
    const cents = parseMoney(incomeIn);
    if (cents === null || cents <= 0) throw new UserError(`金額「${incomeIn}」看不懂`);
    const parts = allocate(cents, s.桶, stepOf(s));
    lines.push(`${m$(ctx, cents)} 這樣分：${parts.map((p) => `${p.name} ${fmtMoney(p.cents)}`).join("、")}`);
    out.allocation = parts.map((p) => ({ name: p.name, amount: money(p.cents) }));
  }
  const upto = args.flags.month ? (() => { const m = monthArg(ctx, args.flags.month); const [y, mm] = m.split("-").map(Number); const end = `${m}-${pad2(daysInMonth(y, mm))}`; return end < ctx.now.date ? end : ctx.now.date; })() : ctx.now.date;
  const st = bucketState(ctx, upto);
  const monthName = `${Number(upto.slice(5, 7))} 月`;
  for (const b of st.buckets) lines.push(`・${b.name}：${st.carryOver ? `上月結轉 ${fmtMoney(b.carry)}，` : ""}${monthName}分到 ${fmtMoney(b.inflow)}，花了 ${fmtMoney(b.outflow)}${b.transfer ? `，轉入轉出 ${b.transfer > 0 ? "+" : ""}${fmtMoney(b.transfer)}` : ""}，剩 ${m$(ctx, b.balance)}`);
  if (st.guessed) lines.push(`（有 ${st.guessed} 筆沒標桶，照分類對應或預設桶算）`);
  out.date = upto; out.carryOver = st.carryOver; out.start = st.start;
  out.buckets = st.buckets.map((b) => ({ name: b.name, percent: b.percent, carry: money(b.carry), inflow: money(b.inflow), outflow: money(b.outflow), transfer: money(b.transfer), balance: money(b.balance) }));
  return { code: 0, text: lines.join("\n"), json: out };
}

const tableArg = (v) => (v === undefined ? null : tableOf({ 表: v }));
function allRows(ctx, table) {
  return (table ? [table] : ["帳", "熱量", "健身", "體重"]).filter((t) => ctx.s.功能[FEATURE[t]] || table).flatMap((t) => rowsOf(ctx, t).map((r, i) => ({ r, i })));
}
const newestFirst = (a, b) => b.r.date.localeCompare(a.r.date) || str(b.r.time).localeCompare(str(a.r.time)) || b.i - a.i;

function cmdRecent(ctx, args) {
  const n = Math.min(100, Math.max(1, parseNum(args.positional[0] ?? args.flags.n ?? 10) ?? 10));
  const list = allRows(ctx, tableArg(args.flags.表 ?? args.flags.table)).sort(newestFirst).slice(0, n).map((x) => x.r);
  return { code: 0, text: list.length ? list.map((r) => `・${describe(ctx, r)}`).join("\n") : "還沒有任何紀錄", json: { rows: list.map((r) => rowJson(ctx, r)) } };
}

function cmdFind(ctx, args) {
  const words = args.positional.map(norm).filter(Boolean);
  if (words.length === 0) throw new UserError("find 要給關鍵字，例如 find 咖啡");
  const month = args.flags.month !== undefined ? monthArg(ctx, args.flags.month) : null;
  const hit = (r) => { const hay = norm([r.content, r.category, r.note, r.action, r.meal, r.account, r.bucket].filter(Boolean).join(" ")); return words.every((w) => hay.includes(w)); };
  const all = allRows(ctx, tableArg(args.flags.表 ?? args.flags.table)).filter(({ r }) => hit(r) && (!month || monthOf(r.date) === month)).sort(newestFirst).map((x) => x.r);
  const ledger = ledgerSum(all.filter((r) => r.table === "帳"));
  const kcal = calorieSum(all.filter((r) => r.table === "熱量"));
  const lines = [`找到 ${all.length} 筆${month ? `（${month}）` : ""}`];
  if (ledger.count) lines.push(`支出合計 ${m$(ctx, ledger.expense)}${ledger.income ? `、收入合計 ${m$(ctx, ledger.income)}` : ""}（${ledger.count} 筆）`);
  if (kcal.count) lines.push(`熱量合計 ${fmtKcal(kcal.lo, kcal.hi)} 大卡（${kcal.count} 筆）`);
  lines.push(...all.slice(0, 30).map((r) => `・${describe(ctx, r)}`));
  if (all.length > 30) lines.push(`（只列最近 30 筆）`);
  return { code: 0, text: lines.join("\n"), json: { count: all.length, expense: money(ledger.expense), income: money(ledger.income), kcalLow: Math.round(kcal.lo), kcalHigh: Math.round(kcal.hi), rows: all.slice(0, 100).map((r) => rowJson(ctx, r)) } };
}

function settingsText(ctx) {
  const s = ctx.s;
  const t = targets(s, latestWeight(ctx, ctx.now.date)?.weight ?? null);
  const onoff = (v) => (v ? "開" : "關");
  const lines = [
    `設定（生活/設定.json）${ctx.settingsProblem ? `\n注意：${ctx.settingsProblem}，現在先用預設值` : ""}`,
    `功能：${Object.entries(s.功能).map(([k, v]) => `${k} ${onoff(v)}`).join("、")}`,
    `幣別 ${s.幣別}（${unit(ctx)}）；帳戶：${s.帳戶.join("、")}（預設 ${s.預設帳戶}）`,
    `支出分類：${s.支出分類.join("、")}`,
    `收入分類：${s.收入分類.join("、")}`,
    `每月預算：${s.每月預算 > 0 ? m$(ctx, s.每月預算 * 100) : "沒設"}；分類預算：${Object.keys(s.分類預算).length ? Object.entries(s.分類預算).map(([k, v]) => `${k} ${fmtMoney(parseMoney(v) ?? 0)}`).join("、") : "沒設"}`,
    `桶：${s.桶.length ? s.桶.map((b) => `${b.名稱} ${b.比例}%`).join("、") + (Object.keys(s.分類對應桶).length ? `；對應：${Object.entries(s.分類對應桶).map(([k, v]) => `${k}→${v}`).join("、")}` : "") + `；預設桶 ${s.預設桶 || s.桶[0].名稱}；結轉 ${onoff(s.桶結轉 !== false)}` : "沒設"}`,
    `熱量目標：${fmtInt(t.熱量)} 大卡、蛋白質 ${t.蛋白質} g、脂肪 ${t.脂肪} g、碳水 ${t.碳水} g（${t.basis}${t.basis === "一般成人參考值" ? "；填身高、體重、年齡會更準" : ""}）`,
    `身體：${[["身高", "cm"], ["體重", "kg"], ["年齡", "歲"]].map(([k, u]) => `${k} ${s.身體[k] ?? "沒填"}${s.身體[k] ? ` ${u}` : ""}`).join("、")}、性別 ${s.身體.性別 || "沒填"}、活動量 ${s.身體.活動量}、目標 ${s.身體.目標}`,
    `餐別：${s.餐別.join("、")}`,
    `提醒：${s.提醒.開啟 ? `開，每天 ${s.提醒.時間}` : `關（${s.提醒.時間}）`}`,
    `外送（${s.外送.平台}）：最低時薪 ${s.外送.最低時薪}、每單保底 ${s.外送.每單保底}、倍數 ${s.外送.時薪倍數}`,
    `重複檢查 ${onoff(s.重複檢查 !== false)}；同樣的食物記 ${s.食物庫建議次數} 次就提議存進食物庫`,
  ];
  return lines.join("\n");
}

function cmdSettings(ctx) {
  const t = targets(ctx.s, latestWeight(ctx, ctx.now.date)?.weight ?? null);
  return { code: 0, text: settingsText(ctx), json: { file: path.relative(ctx.p.ws, ctx.p.settings), exists: ctx.settingsExists, problem: ctx.settingsProblem, settings: ctx.s, targets: t } };
}

// What `set` accepts, by key; `*` is any name (a category, a bucket).
const KEYS = {
  幣別: "currency", 單位: "string", 時區: "tz",
  "功能.記帳": "bool", "功能.熱量": "bool", "功能.健身": "bool", "功能.體重": "bool", "功能.外送": "bool",
  帳戶: "list", 預設帳戶: "account", 支出分類: "list", 收入分類: "list", 餐別: "list",
  每月預算: "money", "分類預算.*": "money?",
  桶: "buckets", "桶.*": "percent?", "桶.*.起始": "money", "分類對應桶.*": "bucket?", 預設桶: "bucket", 桶結轉: "bool", 桶開始月份: "month",
  "身體.身高": "num?:80:250", "身體.體重": "num?:20:400", "身體.年齡": "num?:10:120", "身體.性別": "enum:男,女,", "身體.活動量": "enum:久坐,輕度,中度,高度", "身體.目標": "enum:減重,維持,增肌",
  "熱量目標.熱量": "kcal?", "熱量目標.蛋白質": "num?:0:500", "熱量目標.脂肪": "num?:0:400", "熱量目標.碳水": "num?:0:1000",
  "提醒.開啟": "bool", "提醒.時間": "time", "提醒.內容": "string",
  "外送.平台": "string", "外送.最低時薪": "num:1:100000", "外送.每單保底": "num:0:100000", "外送.時薪倍數": "num:0.1:10", "外送.服務分鐘上限": "num:10:1440", "外送.法規施行日": "date",
  食物庫建議次數: "num:2:100", 重複檢查: "bool",
};
function keySpec(key) {
  if (KEYS[key]) return { spec: KEYS[key], parts: key.split(".") };
  const parts = key.split(".");
  for (const [k, spec] of Object.entries(KEYS)) {
    const kp = k.split(".");
    if (kp.length === parts.length && kp.every((p, i) => p === "*" ? parts[i].length > 0 : p === parts[i])) return { spec, parts };
  }
  return null;
}
const NULLS = new Set(["null", "自動", "清除", "刪除", "移除", "none", "無", "不設", "沒有", ""]);
function parseBool(v) {
  const s = norm(v);
  if (["true", "開", "打開", "開啟", "on", "是", "要", "1", "yes"].includes(s)) return true;
  if (["false", "關", "關掉", "關閉", "off", "否", "不要", "0", "no"].includes(s)) return false;
  return null;
}
const nameOk = (n) => n && !/[、;；,，→>=:：\s"]/.test(n) && !/^\d+$/.test(n) && n.length <= 20;

function applySet(ctx, user, key, raw) {
  const found = keySpec(key);
  if (!found) {
    const near = Object.keys(KEYS).filter((k) => k.split(".")[0] === key.split(".")[0]);
    throw new UserError(`沒有「${key}」這個設定${near.length ? `（可以改的有：${near.join("、")}）` : "（用 settings 看全部）"}`);
  }
  const { spec, parts } = found;
  const [kind, ...opt] = spec.replace("?", "").split(":");
  const nullable = spec.includes("?");
  const s = ctx.s;
  const v = typeof raw === "string" ? raw.trim() : raw;
  const setPath = (val) => {
    let o = user;
    for (const p of parts.slice(0, -1)) { if (!isObj(o[p])) o[p] = {}; o = o[p]; }
    if (val === undefined) delete o[parts.at(-1)]; else o[parts.at(-1)] = val;
  };
  const isNull = typeof v === "string" && NULLS.has(norm(v)) || v === null;
  if (nullable && isNull) {
    if (parts[0] === "桶") { user.桶 = (user.桶 ?? s.桶).filter((b) => b.名稱 !== parts[1]); return `拿掉了桶「${parts[1]}」`; }
    setPath(parts[0] === "身體" || parts[0] === "熱量目標" ? null : undefined);
    return `${key} 改回自動／不設`;
  }
  const num = () => { const n = typeof v === "number" ? v : parseNum(v) ?? (parseMoney(v) !== null ? parseMoney(v) / 100 : null); if (n === null) throw new UserError(`${key} 要是數字，「${v}」看不懂`); return n; };
  switch (kind) {
    case "bool": { const b = typeof v === "boolean" ? v : parseBool(v); if (b === null) throw new UserError(`${key} 要是 開 或 關`); setPath(b); return `${key} ${b ? "開" : "關"}`; }
    case "string": setPath(str(v)); return `${key} 改成「${str(v)}」`;
    case "currency": { const c = str(v).toUpperCase(); if (!/^[A-Z]{3}$/.test(c)) throw new UserError("幣別用三個英文字母，例如 TWD"); setPath(c); return `幣別改成 ${c}`; }
    case "tz": { const z = str(v); try { if (z) new Intl.DateTimeFormat("en-US", { timeZone: z }); } catch { throw new UserError(`時區「${z}」看不懂，例如 Asia/Taipei；空白表示用手機的時區`); } setPath(z); return `時區改成 ${z || "手機的時區"}`; }
    case "time": { const t = parseTime(v); if (!t) throw new UserError(`時間「${v}」看不懂，寫成 21:30`); setPath(t); return `${key} 改成 ${t}`; }
    case "date": { const d = parseDate(v, ctx.now.date); if (!d) throw new UserError(`日期「${v}」看不懂`); setPath(d); return `${key} 改成 ${d}`; }
    case "month": { setPath(monthArg(ctx, v)); return `${key} 改成 ${monthArg(ctx, v)}`; }
    case "money": case "num": case "percent": case "kcal": {
      const n = num();
      if (kind === "money" && n < 0) throw new UserError(`${key} 不能是負的`);
      if (kind === "num" && opt.length === 2 && (n < Number(opt[0]) || n > Number(opt[1]))) throw new UserError(`${key} 要在 ${opt[0]} 到 ${opt[1]} 之間`);
      if (kind === "kcal") {
        if (n < 800) throw new UserError("每天低於 800 大卡太低了，只適合在醫師指導下進行，沒有改。");
        if (n > 6000) throw new UserError("每天超過 6000 大卡不太合理，沒有改。");
        setPath(n);
        return `每天熱量目標改成 ${fmtInt(n)} 大卡${n < 1200 ? "（這個目標偏低，長期吃太少對身體不好，建議先問過醫師或營養師）" : ""}`;
      }
      if (kind === "percent") {
        if (n < 0 || n > 100) throw new UserError("比例要在 0 到 100 之間");
        if (!nameOk(parts[1])) throw new UserError(`桶的名字「${parts[1]}」不能有空白或標點`);
        const list = clone(user.桶 ?? s.桶);
        const b = list.find((x) => x.名稱 === parts[1]);
        if (b) b.比例 = n; else list.push({ 名稱: parts[1], 比例: n });
        user.桶 = list;
        return `桶「${parts[1]}」比例 ${fmtDec(n)}%`;
      }
      if (parts[0] === "桶" && parts[2] === "起始") {
        const list = clone(user.桶 ?? s.桶);
        const b = list.find((x) => x.名稱 === parts[1]);
        if (!b) throw new UserError(`沒有「${parts[1]}」這個桶`);
        b.起始 = n; user.桶 = list;
        return `桶「${parts[1]}」起始金額 ${m$(ctx, Math.round(n * 100))}`;
      }
      setPath(n);
      return kind === "money" ? `${key} 改成 ${m$(ctx, Math.round(n * 100))}${n === 0 && key === "每月預算" ? "（不設預算）" : ""}` : `${key} 改成 ${fmtDec(n, 2)}`;
    }
    case "enum": { const val = str(v); if (!opt[0].split(",").includes(val)) throw new UserError(`${key} 只能是 ${opt[0].split(",").filter(Boolean).join("、")}`); setPath(val); return `${key} 改成 ${val || "不填"}`; }
    case "account": { const a = str(v); const list = user.帳戶 ?? s.帳戶; if (!list.includes(a)) throw new UserError(`帳戶清單裡沒有「${a}」（先 set 帳戶 +${a}）`); setPath(a); return `預設帳戶改成 ${a}`; }
    case "bucket": {
      const b = str(v); const names = (user.桶 ?? s.桶).map((x) => x.名稱);
      if (!names.includes(b)) throw new UserError(`沒有「${b}」這個桶（有：${names.join("、") || "還沒設"}）`);
      setPath(b); return `${key} 改成 ${b}`;
    }
    case "list": {
      const cur = [...(Array.isArray(user[key]) ? user[key] : s[key])];
      let next;
      if (Array.isArray(v)) next = v.map(str).filter(Boolean);
      else if (/^\[/.test(v)) { try { next = JSON.parse(v).map(str).filter(Boolean); } catch { throw new UserError(`${key} 的 JSON 看不懂`); } }
      else if (/^[+＋]/.test(v)) next = [...cur, ...v.slice(1).split(/[、,，]/).map(str).filter(Boolean)];
      else if (/^[-－]/.test(v)) { const gone = new Set(v.slice(1).split(/[、,，]/).map(str)); next = cur.filter((x) => !gone.has(x)); if (next.length === cur.length) throw new UserError(`${key} 裡沒有「${[...gone].join("、")}」`); }
      else next = v.split(/[、,，]/).map(str).filter(Boolean);
      next = [...new Set(next)];
      for (const n of next) if (!nameOk(n)) throw new UserError(`「${n}」不能有空白或標點`);
      if (next.length === 0) throw new UserError(`${key} 至少要留一個`);
      user[key] = next;
      if (key === "帳戶" && !next.includes(user.預設帳戶 ?? s.預設帳戶)) user.預設帳戶 = next[0];
      return `${key}：${next.join("、")}`;
    }
    case "buckets": {
      let list = v;
      if (typeof v === "string") { try { list = NULLS.has(norm(v)) ? [] : JSON.parse(v); } catch { throw new UserError("桶寫成 JSON，例如 [{\"名稱\":\"生活費\",\"比例\":92},{\"名稱\":\"娛樂費\",\"比例\":8}]"); } }
      if (!Array.isArray(list)) throw new UserError("桶要是一個清單");
      const clean = list.map((b) => {
        if (!isObj(b)) throw new UserError("每個桶寫成 {\"名稱\":\"生活費\",\"比例\":92}");
        const name = str(b.名稱 ?? b.name), p = parseNum(b.比例 ?? b.percent);
        if (!nameOk(name)) throw new UserError(`桶的名字「${name}」不能是空的，也不能有空白或標點`);
        if (p === null || p < 0 || p > 100) throw new UserError(`「${name}」的比例要在 0 到 100 之間`);
        const out = { 名稱: name, 比例: p };
        if (b.起始 !== undefined) { const c = parseMoney(b.起始); if (c === null) throw new UserError(`「${name}」的起始金額看不懂`); out.起始 = c / 100; }
        return out;
      });
      if (new Set(clean.map((b) => b.名稱)).size !== clean.length) throw new UserError("桶的名字重複了");
      user.桶 = clean;
      return clean.length ? `桶：${clean.map((b) => `${b.名稱} ${fmtDec(b.比例)}%`).join("、")}` : "拿掉了所有的桶";
    }
  }
  throw new UserError(`${key} 不能這樣改`);
}

function cmdSet(ctx, args) {
  const pos = args.positional;
  if (pos.length < 2 || pos.length % 2) throw new UserError("set 要給 設定 和 值，可以一次好幾組，例如 set 每月預算 15000 分類預算.手搖飲 1500");
  if (ctx.settingsProblem) throw new UserError(`${ctx.settingsProblem}，先修好或刪掉這個檔再改設定。`);
  const user = merge(DEFAULTS, ctx.user ?? {});
  const said = [];
  for (let i = 0; i < pos.length; i += 2) said.push(applySet(ctx, user, pos[i], pos[i + 1]));
  // Bucket rules are checked as a whole: at most 100%, names the other settings point at still exist.
  const buckets = Array.isArray(user.桶) ? user.桶 : [];
  const total = buckets.reduce((a, b) => a + (Number(b.比例) || 0), 0);
  if (total > 100 + 1e-9) throw new UserError(`桶的比例加起來是 ${fmtDec(total)}%，超過 100%，沒有改`);
  if (buckets.length && !/^\d{4}-\d{2}$/.test(str(user.桶開始月份))) user.桶開始月份 = monthOf(ctx.now.date);
  const names = buckets.map((b) => b.名稱);
  if (isObj(user.分類對應桶)) for (const [k, b] of Object.entries(user.分類對應桶)) if (!names.includes(b)) delete user.分類對應桶[k];
  if (user.預設桶 && !names.includes(user.預設桶)) user.預設桶 = "";
  saveSettings(ctx, user);
  const lines = ["已改：", ...said.map((x) => `・${x}`)];
  if (buckets.length && total < 100 - 1e-9) lines.push(`提示：桶的比例加起來 ${fmtDec(total)}%，剩下的 ${fmtDec(100 - total)}% 會算在「未分配」`);
  if (pos.some((k, i) => i % 2 === 0 && k.startsWith("提醒."))) lines.push(ctx.s.提醒.開啟 ? `提示：用排程工具建立每天 ${ctx.s.提醒.時間} 的提醒（內容：${ctx.s.提醒.內容}），舊的提醒排程記得刪掉` : "提示：把每天的記帳提醒排程刪掉");
  if (pos.some((k, i) => i % 2 === 0 && (k.startsWith("身體.") || k.startsWith("熱量目標.")))) { const t = targets(ctx.s, latestWeight(ctx, ctx.now.date)?.weight ?? null); lines.push(`熱量目標現在是 ${fmtInt(t.熱量)} 大卡、蛋白質 ${t.蛋白質} g、脂肪 ${t.脂肪} g、碳水 ${t.碳水} g（${t.basis}）`); }
  return { code: 0, text: lines.join("\n"), json: { status: "ok", changed: said, settings: ctx.s } };
}

function cmdInit(ctx) {
  const created = ensureInit(ctx);
  return { code: 0, text: created ? "建好了：生活/設定.json（所有設定和預設值都在裡面）" : ctx.settingsProblem ? `${ctx.settingsProblem}` : "生活/設定.json 已經有了", json: { created, file: path.relative(ctx.p.ws, ctx.p.settings) } };
}

function foodAverage(ctx, name, days = 180) {
  const since = addDays(ctx.now.date, -days);
  const rows = rowsOf(ctx, "熱量").filter((r) => r.date >= since && norm(r.content) === norm(name));
  if (rows.length === 0) return null;
  const avg = (k) => { const v = rows.map((r) => r[k]).filter((x) => x !== null); return v.length ? Math.round((v.reduce((a, b) => a + b, 0) / v.length) * 10) / 10 : null; };
  return { count: rows.length, kcal: Math.round(rows.reduce((a, r) => a + (r.lo + r.hi) / 2, 0) / rows.length / 5) * 5, protein: avg("protein"), fat: avg("fat"), carbs: avg("carbs"), name: rows.at(-1).content };
}
const foodText = (f) => `${f.name}${f.size ? `（${f.size}）` : ""} ${fmtInt(f.kcal)} 大卡${[["蛋白質", f.protein], ["脂肪", f.fat], ["碳水", f.carbs]].filter(([, v]) => v !== null).map(([k, v]) => `、${k} ${fmtDec(v)} g`).join("")}`;

function cmdFood(ctx, args) {
  const sub = args.positional.shift() ?? "list";
  const lib = foodLibrary(ctx);
  if (sub === "list") return { code: 0, text: lib.length ? `我的食物庫（${lib.length} 樣）：\n${lib.map((f) => `・${foodText(f)}`).join("\n")}` : "我的食物庫還是空的。常吃的東西可以存進來：food add '{\"名稱\":\"雞腿便當\",\"份量\":\"1 個\",\"熱量\":780}'", json: { items: lib } };
  if (sub === "find") {
    const w = norm(args.positional.join(""));
    const hits = lib.filter((f) => norm(f.name).includes(w) || w.includes(norm(f.name)));
    return { code: 0, text: hits.length ? hits.map((f) => `・${foodText(f)}`).join("\n") : `我的食物庫沒有「${args.positional.join("")}」`, json: { items: hits } };
  }
  if (sub === "add" || sub === "learn") {
    let item;
    if (sub === "learn") {
      const name = args.positional.join(" ").trim();
      if (!name) throw new UserError("food learn 要給名稱");
      const a = foodAverage(ctx, name);
      if (!a) throw new UserError(`熱量紀錄裡沒有「${name}」`);
      item = { name: a.name, size: "1 份", kcal: a.kcal, protein: a.protein, fat: a.fat, carbs: a.carbs };
    } else {
      const it = args.positional.length >= 2 && !/^[[{]/.test(args.positional[0]) ? { 名稱: args.positional[0], 熱量: args.positional[1], 份量: args.positional[2] } : jsonInput(args, "food add");
      if (!isObj(it)) throw new UserError("food add 給一個 JSON 物件，例如 {\"名稱\":\"雞腿便當\",\"份量\":\"1 個\",\"熱量\":780,\"蛋白質\":35}");
      const name = str(it.名稱 ?? it.name), kcal = parseNum(it.熱量 ?? it.kcal);
      if (!name) throw new UserError("食物要有名稱");
      if (kcal === null || kcal < 0 || kcal > 10000) throw new UserError(`「${name}」的熱量看不懂（給一個數字，你自己知道的值）`);
      const n = (k) => (it[k] === undefined || it[k] === null || it[k] === "" ? null : parseNum(it[k]));
      item = { name, size: str(it.份量 ?? it.size), kcal, protein: n("蛋白質"), fat: n("脂肪"), carbs: n("碳水") };
    }
    ensureInit(ctx);
    const replaced = lib.some((f) => norm(f.name) === norm(item.name));
    saveFood(ctx, [...lib.filter((f) => norm(f.name) !== norm(item.name)), item]);
    return { code: 0, text: `${replaced ? "更新了" : "存進"}我的食物庫：${foodText(item)}${sub === "learn" ? "（用過去紀錄的平均）" : ""}`, json: { status: "ok", item, replaced } };
  }
  if (sub === "remove") {
    const name = args.positional.join(" ").trim();
    if (!lib.some((f) => norm(f.name) === norm(name))) throw new UserError(`我的食物庫沒有「${name}」`);
    saveFood(ctx, lib.filter((f) => norm(f.name) !== norm(name)));
    return { code: 0, text: `從我的食物庫拿掉了「${name}」`, json: { status: "ok" } };
  }
  if (sub === "suggest") {
    const since = addDays(ctx.now.date, -90);
    const counts = new Map();
    for (const r of rowsOf(ctx, "熱量")) if (r.date >= since && r.content && !findFood(ctx, r.content)) counts.set(norm(r.content), (counts.get(norm(r.content)) ?? 0) + 1);
    const list = [...counts.entries()].filter(([, n]) => n >= ctx.s.食物庫建議次數).sort((a, b) => b[1] - a[1]).map(([k]) => foodAverage(ctx, k, 90)).filter(Boolean);
    return { code: 0, text: list.length ? `常吃但還沒存的：\n${list.map((a) => `・${a.name} 記過 ${a.count} 次，平均約 ${fmtInt(a.kcal)} 大卡（food learn ${a.name}）`).join("\n")}` : "沒有常吃但還沒存進食物庫的東西", json: { items: list } };
  }
  throw new UserError("food 後面接 list、find、add、learn、remove 或 suggest");
}

/** Files an older skill wrote (other headers, or in <workspace>/帳本 and <workspace>/熱量). */
function legacyFiles(ctx) {
  const out = [];
  for (const table of ["帳", "熱量", "健身", "體重"]) for (const f of data(ctx, table).files) if (f.kind === "legacy" || f.kind === "unknown") out.push({ table, f });
  return out;
}

function cmdMigrate(ctx, args) {
  const list = legacyFiles(ctx);
  if (list.length === 0) return { code: 0, text: "沒有舊格式的檔案", json: { files: [] } };
  const rel = (f) => path.relative(ctx.p.ws, f);
  if (!args.flags.yes) {
    const lines = ["這些是舊格式的檔案（照舊會被讀到，但新紀錄加不進去，也不能用編號刪改）：", ...list.map(({ f }) => `・${rel(f.file)}：${f.rows.length} 筆${f.bad ? `，${f.bad} 列看不懂` : ""}${f.kind === "unknown" ? "（欄位認不出來，不會搬）" : ""}`), "升級：migrate --yes（每筆會換成新格式、加上編號，原檔搬到 生活/舊格式備份/）。先問使用者同不同意。"];
    return { code: 0, text: lines.join("\n"), json: { files: list.map(({ f }) => ({ file: rel(f.file), rows: f.rows.length, bad: f.bad, kind: f.kind })) } };
  }
  ensureInit(ctx);
  const moved = [], kept = [];
  const targets = new Map(); // target file → { table, rows }
  for (const { table, f } of list) {
    if (f.kind === "unknown") { kept.push(rel(f.file)); continue; }
    for (const r of f.rows) {
      const row = { ...r, legacy: false, source: r.source || "舊帳本" };
      if (table === "熱量" && row.note) { row.content = row.content ? `${row.content}（${row.note}）` : row.note; row.note = ""; }
      if (table === "帳") { row.account ||= ""; row.bucket ||= ""; row.category ||= row.type === "收入" ? "其他收入" : "其他"; }
      const file = targetFile(ctx.p, table, row.date);
      if (!targets.has(file)) targets.set(file, { table, rows: [] });
      targets.get(file).rows.push(row);
    }
  }
  // Back up every source first (copy), then write the targets, then remove the originals.
  const backups = [];
  for (const { f } of list) {
    if (f.kind === "unknown") continue;
    let dst = path.join(ctx.p.backup, path.relative(ctx.p.ws, f.file).replace(/^生活[\\/]/, ""));
    for (let n = 2; fs.existsSync(dst); n++) dst = dst.replace(/(\.\d+)?\.csv$/, `.${n}.csv`);
    fs.mkdirSync(path.dirname(dst), { recursive: true });
    fs.copyFileSync(f.file, dst);
    backups.push({ src: f.file, dst });
  }
  for (const [file, g] of targets) {
    const existing = data(ctx, g.table).files.find((x) => x.file === file && x.kind === "new");
    const keep = existing ? existing.rows : [];
    const taken = new Set(rowsOf(ctx, g.table).map((x) => x.id).filter(Boolean));
    for (const r of g.rows) if (g.table !== "體重") r.id = newId(g.table, r.date, taken);
    const all = [...keep, ...g.rows].sort((a, b) => a.date.localeCompare(b.date) || str(a.time).localeCompare(str(b.time)));
    writeAtomic(file, serialize(g.table, all));
    moved.push(`${rel(file)}（${g.rows.length} 筆）`);
  }
  for (const b of backups) if (!targets.has(b.src)) fs.rmSync(b.src);
  ctx.cache = {};
  const lines = ["升級好了：", ...moved.map((m) => `・${m}`), `原檔備份在 ${rel(ctx.p.backup)}/`];
  if (kept.length) lines.push(`沒有搬（欄位認不出來）：${kept.join("、")}`);
  const bad = list.reduce((a, { f }) => a + f.bad, 0);
  if (bad) lines.push(`有 ${bad} 列看不懂，沒有搬（原檔在備份裡）`);
  return { code: 0, text: lines.join("\n"), json: { status: "ok", files: moved, backup: rel(ctx.p.backup), skipped: kept, badRows: bad } };
}

// ───────────────────────── Uber weekly statement ─────────────────────────

const MONEY_SRC = String.raw`(-)?\s?NT\s?\$\s?(-)?\s?(\d[\d,]*(?:\.\d+)?)`;
const DT_SRC = String.raw`(?:(\d{4})\s*年\s*)?(\d{1,2})\s*月\s*(\d{1,2})\s*日(?:\s*[(]?\s*(?:周|週|星期|禮拜|礼拜)\s*[一二三四五六日天]\s*[)]?)?(?:[\s/|·,，]*(上午|下午|中午|晚上|凌晨|早上|傍晚|AM|PM)?\s*(\d{1,2})\s*:\s*(\d{2})(?:\s*(AM|PM))?)?`;
const moneyCents = (m) => (m[1] ? -1 : 1) * (m[2] ? -1 : 1) * Math.round(Number(m[3].replace(/,/g, "")) * 100);
const TO_TRAD = { 车: "車", 费: "費", 价: "價", 础: "礎", 现: "現", 额: "額", 余: "餘", 监: "監", 湾: "灣", 员: "員", 项: "項", 结: "結", 奖: "獎", 励: "勵", 调: "調", 整: "整", 单: "單", 时: "時", 间: "間", 计: "計", 总: "總", 补: "補", 贴: "貼" };
const trad = (s) => s.replace(/[一-鿿]/g, (c) => TO_TRAD[c] ?? c);

function dtOf(m) {
  const out = { year: m[1] ? Number(m[1]) : null, month: Number(m[2]), day: Number(m[3]), minutes: null };
  if (m[5] !== undefined) {
    let h = Number(m[5]);
    const shift = m[4] ?? m[7];
    if (shift) h = to24(shift, h);
    out.minutes = h * 60 + Number(m[6]);
  }
  return out;
}

function tokenize(text) {
  const out = [];
  const dtRe = new RegExp(DT_SRC, "g"), mRe = new RegExp(MONEY_SRC, "g");
  const pushText = (t) => { const x = t.replace(/[|｜/]/g, " ").replace(/\s+/g, " ").trim(); if (x && /[\p{L}\p{N}]/u.test(x)) out.push({ kind: "text", text: x }); };
  let i = 0;
  while (i < text.length) {
    dtRe.lastIndex = i; mRe.lastIndex = i;
    const d = dtRe.exec(text), m = mRe.exec(text);
    const next = d && (!m || d.index <= m.index) ? d : m;
    if (!next) { pushText(text.slice(i)); break; }
    if (next.index > i) pushText(text.slice(i, next.index));
    out.push(next === d ? { kind: "dt", ...dtOf(d) } : { kind: "money", cents: moneyCents(m) });
    i = next.index + Math.max(1, next[0].length);
  }
  return out;
}

/** Known labels take, in the order they appear, the first amount after them that no earlier label took. */
function labelValues(text, labels) {
  const moneys = [...text.matchAll(new RegExp(MONEY_SRC, "g"))].map((m) => ({ index: m.index, cents: moneyCents(m) }));
  const found = labels.map(([key, re]) => { const m = re.exec(text); return m && { key, end: m.index + m[0].length, index: m.index }; }).filter(Boolean).sort((a, b) => a.index - b.index);
  const taken = new Set(), out = {};
  for (const f of found) {
    const k = moneys.findIndex((m, j) => m.index >= f.end && !taken.has(j));
    if (k >= 0) { taken.add(k); out[f.key] = moneys[k].cents; }
  }
  return out;
}

function rowKind(name) {
  if (/小费|小費|tip/i.test(name)) return "tip";
  if (/保底|监管|監管|guarantee|minimum/i.test(name)) return "guarantee";
  if (/delivery|配送|外送|送餐|订单|訂單|trip|行程|eats/i.test(name)) return "delivery";
  if (/现金|現金|cash/i.test(name)) return "cash";
  return "other";
}

export function parseUber(raw, cfg = DEFAULTS.外送, fallbackYear = null) {
  const text = halfWidth(raw).replace(/\r/g, "");
  const problems = [];
  const tx = /(^|\n)[ \t]*交易(?:明细|明細|记录|紀錄|記錄)?(?=[ \t]*(\n|$)|[ \t]+处理|[ \t]+處理)/.exec(text);
  let head = text, body = "";
  if (tx) { head = text.slice(0, tx.index); body = text.slice(tx.index + tx[0].length); }
  else {
    const re = new RegExp(DT_SRC, "g");
    let m;
    while ((m = re.exec(text))) if (!m[1] && m[5] !== undefined) { head = text.slice(0, m.index); body = text.slice(m.index); break; }
    problems.push("找不到「交易」表格的標題，從第一個有時間的日期開始當交易明細");
  }
  const period = [...head.matchAll(/(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日/g)].slice(0, 2).map((m) => ymd(Number(m[1]), Number(m[2]), Number(m[3])));
  const year = period[0] ? Number(period[0].slice(0, 4)) : fallbackYear ?? new Date().getFullYear();

  const sum = labelValues(head, [["opening", /(?:初始|期初)[余餘][额額]/], ["earnings", /您的收入|你的收入/], ["cash", /已收取[现現]金/], ["closing", /期末[余餘][额額]/]]);
  for (const [k, zh] of [["opening", "初始餘額"], ["earnings", "您的收入"], ["cash", "已收取現金"], ["closing", "期末餘額"]]) if (sum[k] === undefined) problems.push(`找不到「${zh}」`);
  if (sum.cash !== undefined) sum.cash = Math.abs(sum.cash);

  // The earnings page: label, amount pairs from 车费 on; groups 车费 / 其他收入 / 小费 with their items.
  const groups = { fares: { zh: "車費", total: null, parts: [] }, other: { zh: "其他收入", total: null, parts: [] }, tips: { zh: "小費", total: null, parts: [] } };
  const start = head.search(/车费|車費/);
  if (start >= 0) {
    const region = head.slice(start);
    let last = 0, cur = null;
    for (const m of region.matchAll(new RegExp(MONEY_SRC, "g"))) {
      const label = region.slice(last, m.index).split("\n").map((x) => x.replace(/[|｜]/g, " ").trim()).filter(Boolean).at(-1) ?? "";
      last = m.index + m[0].length;
      const cents = moneyCents(m);
      if (/^(车费|車費)$/.test(label)) { cur = groups.fares; cur.total = cents; }
      else if (/^其他收入$/.test(label)) { cur = groups.other; cur.total = cents; }
      else if (/^(小费|小費)$/.test(label)) { cur = groups.tips; cur.total = cents; }
      else if (/^(您的收入|你的收入|总计|總計|合计|合計)$/.test(label) || !label) cur = null;
      else if (cur) cur.parts.push({ label: trad(label), cents });
    }
  } else problems.push("找不到收入明細（車費、其他收入、小費）");
  for (const g of Object.values(groups)) if (g.total === null && g.parts.length) g.total = g.parts.reduce((a, p) => a + p.cents, 0);

  // The 交易 table: processed date+time, activity, activity date+time, then amounts (earnings, [cash], [net], balance).
  const rows = [], loose = [];
  let cur = null, state = "idle", lastText = "";
  const close = () => { if (cur) rows.push(cur); cur = null; state = "idle"; };
  for (const t of tokenize(body)) {
    if (t.kind === "dt") {
      if (cur && (state === "name" || state === "processed") && !cur.activityAt) { cur.activityAt = t; state = "activity"; continue; }
      if (t.minutes === null) continue;
      close();
      cur = { processedAt: t, name: "", amounts: [] };
      state = "processed";
    } else if (t.kind === "text") {
      lastText = t.text;
      if (!cur) continue;
      if (state === "processed" || state === "name") { cur.name = cur.name ? `${cur.name} ${t.text}` : t.text; state = "name"; }
      else if (state === "amounts") close();
    } else if (cur) { cur.amounts.push(t.cents); state = "amounts"; }
    else loose.push({ cents: t.cents, label: lastText });
  }
  close();
  const parsed = [];
  rows.forEach((r, i) => {
    if (r.amounts.length === 0) { problems.push(`交易第 ${i + 1} 列（${r.name || "沒有名稱"}）沒有金額`); return; }
    const balance = r.amounts.length >= 2 ? r.amounts.at(-1) : null;
    const vals = r.amounts.length >= 2 ? r.amounts.slice(0, -1) : r.amounts;
    const kind = rowKind(r.name);
    let earn = vals[0], net = vals.at(-1), cash = vals.length >= 3 ? vals[1] : vals.length === 2 ? net - earn : 0;
    if (kind === "cash" && vals.length === 1 && earn < 0) { cash = earn; earn = 0; }
    parsed.push({ n: i + 1, name: r.name, kind, processedAt: r.processedAt, activityAt: r.activityAt ?? null, vals, earn, net, cash, balance });
  });

  // Order of the table (oldest or newest first), from the running balance.
  const fits = (order) => {
    const list = order === "asc" ? parsed : [...parsed].reverse();
    let prev = sum.opening ?? null, ok = 0, bad = [];
    for (const r of list) {
      if (r.balance === null) { prev = null; continue; }
      if (prev !== null) { if (prev + r.net === r.balance) ok++; else bad.push(r); }
      prev = r.balance;
    }
    return { order, ok, bad };
  };
  const asc = fits("asc"), desc = fits("desc");
  const chain = desc.ok > asc.ok ? desc : asc;
  // A row with two amounts before the balance is [earnings, net] or [earnings, cash]: the balance decides.
  if (chain.bad.length) {
    const list = chain.order === "asc" ? parsed : [...parsed].reverse();
    for (let k = 0; k < list.length; k++) {
      const r = list[k];
      if (!chain.bad.includes(r) || r.vals.length !== 2) continue;
      const prev = k === 0 ? sum.opening : list[k - 1].balance;
      if (prev !== undefined && prev !== null && prev + r.vals[0] + r.vals[1] === r.balance) { r.cash = r.vals[1]; r.net = r.vals[0] + r.vals[1]; chain.bad.splice(chain.bad.indexOf(r), 1); }
    }
  }
  for (const r of parsed) if (r.vals.length >= 3 && r.earn + r.cash !== r.net) problems.push(`交易第 ${r.n} 列：收入 ${fmtMoney(r.earn)} 加現金 ${fmtMoney(r.cash)} 不等於淨額 ${fmtMoney(r.net)}`);

  const total = (f) => parsed.filter(f).reduce((a, r) => a + r.earn, 0);
  const tx$ = { earnings: total(() => true), cash: -parsed.reduce((a, r) => a + r.cash, 0), fares: total((r) => r.kind === "delivery"), tips: total((r) => r.kind === "tip"), guarantee: total((r) => r.kind === "guarantee"), other: total((r) => r.kind === "other" || r.kind === "cash") };

  const checks = [];
  const check = (name, expected, actual) => { if (expected === undefined || expected === null || actual === undefined || actual === null) return; checks.push({ name, ok: expected === actual, expected: money(expected), actual: money(actual), diff: money(actual - expected) }); };
  for (const g of Object.values(groups)) if (g.parts.length && g.total !== null) check(`${g.zh}分項加總 = ${g.zh}`, g.total, g.parts.reduce((a, p) => a + p.cents, 0));
  if (groups.fares.total !== null || groups.other.total !== null || groups.tips.total !== null) check("車費 + 其他收入 + 小費 = 您的收入", sum.earnings, (groups.fares.total ?? 0) + (groups.other.total ?? 0) + (groups.tips.total ?? 0));
  if (sum.opening !== undefined && sum.earnings !== undefined && sum.cash !== undefined) check("期初 + 收入 − 現金 = 期末", sum.closing, sum.opening + sum.earnings - sum.cash);
  if (parsed.length) {
    check("交易明細的收入加總 = 您的收入", sum.earnings, tx$.earnings);
    check("交易明細的現金加總 = 已收取現金", sum.cash, tx$.cash);
    if (groups.fares.total !== null) check("外送單的收入加總 = 車費", groups.fares.total, tx$.fares);
    if (groups.tips.total !== null) check("小費列加總 = 小費", groups.tips.total, tx$.tips);
    if (groups.other.total !== null) check("保底和其他列加總 = 其他收入", groups.other.total, tx$.guarantee + tx$.other);
    const balanced = parsed.filter((r) => r.balance !== null);
    if (balanced.length) {
      checks.push({ name: "逐列餘額接得上", ok: chain.bad.length === 0, expected: null, actual: null, diff: null, rows: chain.bad.map((r) => r.n) });
      const lastBal = (chain.order === "asc" ? balanced.at(-1) : balanced[0]).balance;
      check("最後一列的餘額 = 期末餘額", sum.closing, lastBal);
    }
  } else problems.push("交易明細一列都沒讀到");
  const stray = loose.filter((l) => !/总计|總計|合计|合計|total/i.test(l.label));
  if (stray.length) problems.push(`有 ${stray.length} 個金額不屬於任何一列交易（${stray.slice(0, 3).map((l) => fmtMoney(l.cents)).join("、")}）`);

  // 外送員權益保障及外送平台管理法: per order at least max(每單保底, minutes × 時薪倍數 × 最低時薪 / 60). An estimate only.
  const est = { wage: cfg.最低時薪, floor: cfg.每單保底, multiplier: cfg.時薪倍數, orders: 0, counted: 0, skipped: 0, topUp: 0, perOrder: [] };
  const abs = (dt) => { let y = dt.year ?? year; if (dt.year === null && period[0] && dt.month < Number(period[0].slice(5, 7)) - 6) y++; return Date.UTC(y, dt.month - 1, dt.day) / 60000 + (dt.minutes ?? 0); };
  for (const r of parsed.filter((x) => x.kind === "delivery")) {
    est.orders++;
    if (!r.activityAt || r.activityAt.minutes === null || r.processedAt.minutes === null) { est.skipped++; continue; }
    const minutes = abs(r.processedAt) - abs(r.activityAt);
    if (!(minutes > 0 && minutes <= cfg.服務分鐘上限)) { est.skipped++; continue; }
    const min = Math.max(Math.round(cfg.每單保底 * 100), Math.round((minutes * cfg.時薪倍數 * cfg.最低時薪 * 100) / 60));
    const topUp = Math.max(0, min - r.earn);
    est.counted++;
    est.topUp += topUp;
    est.perOrder.push({ row: r.n, minutes, fare: money(r.earn), minimum: money(min), topUp: money(topUp) });
  }
  const paid = tx$.guarantee || (groups.other.parts.filter((p) => /保底/.test(p.label)).reduce((a, p) => a + p.cents, 0) || null);
  est.guaranteePaid = paid;
  est.difference = paid === null ? null : est.topUp - paid;
  const lawDate = cfg.法規施行日 || DEFAULTS.外送.法規施行日;
  est.beforeLaw = period[1] ? period[1] < lawDate : false;

  return { period, sum, groups, rows: parsed, tx: tx$, checks, problems, estimate: est, lawDate };
}

function uberText(ctx, u) {
  const L = [], $ = (c) => `${fmtMoney(c)} 元`;
  L.push(`Uber 週結算${u.period.length === 2 ? ` ${showDate(u.period[0], ctx)}–${showDate(u.period[1], ctx)}` : ""}`);
  if (u.sum.opening !== undefined) L.push(`期初餘額 ${$(u.sum.opening)}`);
  if (u.sum.earnings !== undefined) L.push(`收入 ${$(u.sum.earnings)}`);
  for (const g of Object.values(u.groups)) if (g.total !== null) L.push(`・${g.zh} ${$(g.total)}${g.parts.length ? `（${g.parts.map((p) => `${p.label} ${fmtMoney(p.cents)}`).join("、")}）` : ""}`);
  if (u.sum.cash !== undefined) L.push(`收取現金 ${$(u.sum.cash)}`);
  if (u.sum.closing !== undefined) {
    const c = u.sum.closing;
    L.push(`期末餘額 ${$(c)}：${c < 0 ? `要轉 ${fmtMoney(-c)} 元給 Uber` : c > 0 ? `Uber 會付給你 ${$(c)}` : "這週不用轉帳"}`);
  }
  const bad = u.checks.filter((c) => !c.ok);
  if (bad.length === 0 && u.problems.length === 0) L.push(`核對：全部對得上（${u.checks.length} 項，交易明細 ${u.rows.length} 列）`);
  else {
    L.push("核對：有對不上的地方，照實告訴使用者，不要自己猜：");
    for (const c of bad) L.push(c.rows ? `・${c.name}：第 ${c.rows.join("、")} 列接不上` : `・${c.name}：應該是 ${fmtMoney(c.expected * 100)}，算出來 ${fmtMoney(c.actual * 100)}（差 ${fmtMoney(c.diff * 100)}）`);
    for (const p of u.problems) L.push(`・${p}`);
  }
  const e = u.estimate;
  if (e.orders) {
    L.push(`外送法每單保底（估算，僅供參考，以官方公告和平台計算為準）：`);
    L.push(`・算法：每單至少 max(${e.floor} 元, 服務分鐘 × ${e.multiplier} × 最低時薪 ${e.wage} ÷ 60)，不夠的部分由平台補`);
    L.push(`・服務分鐘用「處理時間 − 活動時間」估，不一定等於法規認定的時間`);
    L.push(`・${e.orders} 單裡算了 ${e.counted} 單${e.skipped ? `（${e.skipped} 單時間看不出來，沒算）` : ""}：估計應補約 ${$(e.topUp)}`);
    if (e.guaranteePaid !== null) {
      const d = e.difference;
      L.push(`・結算單上的監管性保底收入 ${$(e.guaranteePaid)}${d === 0 ? "，跟估算一樣" : d > 0 ? `，比估算少約 ${$(d)}（可能少補，可以向平台確認）` : `，比估算多約 ${$(-d)}`}`);
    }
    if (u.period[1] && e.beforeLaw) L.push(`・這週在 ${u.lawDate} 法規施行以前，保底估算只是參考`);
  }
  return L.join("\n");
}

function cmdUber(ctx, args) {
  let text;
  if (args.flags.file) {
    const f = path.resolve(ctx.p.ws, String(args.flags.file));
    text = readText(f);
    if (text === null) throw new UserError(`找不到 ${args.flags.file}`);
  } else text = process.stdin.isTTY ? "" : readStdin();
  if (!str(text)) throw new UserError("沒有收到結算單的文字：用 --file 給文字檔，或從 stdin 傳進來");
  const u = parseUber(text, ctx.s.外送, Number(ctx.now.date.slice(0, 4)));
  const out = uberText(ctx, u);
  const lines = [out];
  if (u.sum.earnings !== undefined && ctx.s.功能.記帳 && u.checks.every((c) => c.ok) && u.problems.length === 0) {
    const label = u.period.length === 2 ? `${showDate(u.period[0], ctx)}–${showDate(u.period[1], ctx)}` : "";
    lines.push(`要記進帳本的話（先問使用者）：add '${JSON.stringify({ 表: "帳", 類型: "收入", 金額: money(u.sum.earnings), 分類: ctx.s.收入分類.includes("外送收入") ? "外送收入" : ctx.s.收入分類[0], 內容: `${ctx.s.外送.平台} 週收入 ${label}`.trim(), 日期: u.period[1] ?? "今天", 來源: "uber" })}'`);
  }
  const ok = u.checks.every((c) => c.ok) && u.problems.length === 0;
  const json = {
    status: ok ? "ok" : "mismatch", period: u.period,
    opening: u.sum.opening === undefined ? null : money(u.sum.opening), earnings: u.sum.earnings === undefined ? null : money(u.sum.earnings), cash: u.sum.cash === undefined ? null : money(u.sum.cash), closing: u.sum.closing === undefined ? null : money(u.sum.closing),
    owesUber: u.sum.closing !== undefined && u.sum.closing < 0, transferToUber: u.sum.closing !== undefined && u.sum.closing < 0 ? money(-u.sum.closing) : 0,
    breakdown: Object.fromEntries(Object.entries(u.groups).map(([k, g]) => [k, { total: g.total === null ? null : money(g.total), parts: g.parts.map((p) => ({ label: p.label, amount: money(p.cents) })) }])),
    transactions: { rows: u.rows.length, deliveries: u.rows.filter((r) => r.kind === "delivery").length, earnings: money(u.tx.earnings), cash: money(u.tx.cash), fares: money(u.tx.fares), tips: money(u.tx.tips), guarantee: money(u.tx.guarantee), other: money(u.tx.other) },
    checks: u.checks, problems: u.problems,
    estimate: { isEstimate: true, minimumHourlyWage: u.estimate.wage, perOrderFloor: u.estimate.floor, multiplier: u.estimate.multiplier, orders: u.estimate.orders, counted: u.estimate.counted, skipped: u.estimate.skipped, topUp: money(u.estimate.topUp), guaranteePaid: u.estimate.guaranteePaid === null ? null : money(u.estimate.guaranteePaid), difference: u.estimate.difference === null ? null : money(u.estimate.difference), beforeLaw: u.estimate.beforeLaw, perOrder: u.estimate.perOrder },
  };
  return { code: 0, text: lines.join("\n"), json };
}

// ───────────────────────── main ─────────────────────────

const HELP = `生活紀錄工具（資料在工作區的 生活/）
用法：node life.mjs <指令> ...（每個指令加 --json 會輸出 JSON）
  add '<JSON>'            記一筆或多筆（JSON 陣列）；同一天一模一樣的會先擋下，確定要記加 --force
      帳    {"表":"帳","類型":"支出","金額":120,"分類":"午餐","內容":"雞腿便當","帳戶":"現金","日期":"今天","時間":"12:30"}
      熱量  {"表":"熱量","餐別":"午餐","內容":"雞腿便當","熱量":[700,850],"蛋白質":35,"脂肪":25,"碳水":95}
      健身  {"表":"健身","動作":"伏地挺身","組數":3,"次數或秒數":15}
      體重  {"表":"體重","體重":70.5,"體脂":22}
  undo <編號> ...          刪掉（編號在確認行裡）
  edit <編號> '<JSON>'     改一筆，例如 edit b261114-a1b2 '{"金額":150}'
  today [日期]             今天（或某天）的花費、預算、熱量、營養素、運動
  month [YYYY-MM]          月結：總計、分類、桶、花最多的、和上個月比
  buckets [--income 金額]  桶的餘額；給金額就算這筆收入怎麼分
  recent [筆數]            最近記的幾筆和編號
  find <關鍵字> [--month YYYY-MM]  找紀錄並加總
  settings                 看所有設定
  set <設定> <值> ...       改設定，例如 set 每月預算 15000 支出分類 +寵物 功能.健身 false
  food [list|find|add|learn|remove|suggest]  我的食物庫
  init                     建立 生活/設定.json（第一次記帳時也會自動建）
  migrate [--yes]          把舊格式的帳本、熱量檔升級（先看清單，--yes 才做）
  uber [--file 文字檔]      Uber 週結算（文字也可以從 stdin 傳）
其他：--dir <工作區>（預設是 DSHM_WORKSPACE 或目前的資料夾）`;

const VALUE_FLAGS = new Set(["dir", "date", "file", "income", "month", "n", "表", "table"]);
export function parseArgs(argv) {
  const flags = {}, positional = [];
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith("--") && a.length > 2) {
      const eq = a.indexOf("=");
      const key = eq > 0 ? a.slice(2, eq) : a.slice(2);
      if (eq > 0) flags[key] = a.slice(eq + 1);
      else if (VALUE_FLAGS.has(key) && i + 1 < argv.length) flags[key] = argv[++i];
      else flags[key] = true;
    } else positional.push(a);
  }
  return { flags, positional };
}

export function run(argv) {
  const [command = "help", ...rest] = argv;
  const args = parseArgs(rest);
  if (command === "help" || command === "--help" || command === "-h") return { code: 0, text: HELP, json: { commands: COMMAND_NAMES } };
  const ws = path.resolve(String(args.flags.dir || process.env.DSHM_WORKSPACE || process.cwd()));
  const p = paths(ws);
  const { settings, user, exists, problem } = loadSettings(p);
  const ctx = { p, s: settings, user, settingsExists: exists, settingsProblem: problem, now: nowParts(settings.時區) };
  const commands = { init: cmdInit, add: cmdAdd, undo: cmdUndo, edit: cmdEdit, today: cmdToday, month: cmdMonth, buckets: cmdBuckets, recent: cmdRecent, find: cmdFind, settings: cmdSettings, set: cmdSet, food: cmdFood, migrate: cmdMigrate, uber: cmdUber };
  const fn = commands[command];
  if (!fn) return { code: 2, text: `沒有「${command}」這個指令。\n${HELP}`, json: { status: "error", error: `unknown command ${command}` } };
  try {
    const out = fn(ctx, args);
    if (problem && command !== "settings" && command !== "init") out.text = `注意：${problem}，現在先用預設值。\n${out.text}`;
    return out;
  } catch (e) {
    if (e instanceof UserError) return { code: e.code, text: e.message, json: { status: "error", error: e.message } };
    return { code: 1, text: `出錯了：${e.message}`, json: { status: "error", error: String(e.stack ?? e) } };
  }
}

const isMain = (() => {
  try { return !!process.argv[1] && fs.realpathSync(process.argv[1]) === fs.realpathSync(fileURLToPath(import.meta.url)); } catch { return false; }
})();
if (isMain) {
  const argv = process.argv.slice(2);
  const out = run(argv);
  const asJson = argv.includes("--json");
  process.stdout.write((asJson ? JSON.stringify(out.json, null, 1) : out.text) + "\n");
  process.exitCode = out.code;
}
