// Tests for the bookkeeping skill's script (android/app/src/main/assets/skills/dshm-bookkeeping/life.mjs).
// Run: node --test android/app/src/test/node/
// Every workspace is a fresh temporary folder, filled from fixtures/ when a test needs older files. No real data.

import { test, after } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const SCRIPT = path.resolve(here, "../../../main/assets/skills/dshm-bookkeeping/life.mjs");
const FIX = path.join(here, "fixtures");
const lib = await import(pathToFileURL(SCRIPT).href);

const made = [];
after(() => { for (const d of made) fs.rmSync(d, { recursive: true, force: true }); });

function workspace(fixture) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "life-"));
  made.push(dir);
  if (fixture) fs.cpSync(path.join(FIX, fixture), dir, { recursive: true });
  return dir;
}
function life(dir, args, { now = "2026-11-14 12:30", input = "", env = {} } = {}) {
  const r = spawnSync(process.execPath, [SCRIPT, ...args, ...(dir ? ["--dir", dir] : [])], { encoding: "utf8", input, env: { ...process.env, LIFE_NOW: now, DSHM_WORKSPACE: "", ...env } });
  return { code: r.status, out: r.stdout, err: r.stderr };
}
function lifeJson(dir, args, opts) {
  const r = life(dir, [...args, "--json"], opts);
  return { ...r, data: JSON.parse(r.out) };
}
const add = (dir, items, opts = {}) => life(dir, ["add", ...(opts.force ? ["--force"] : []), JSON.stringify(items)], opts);
const read = (dir, rel) => fs.readFileSync(path.join(dir, rel), "utf8");
const lines = (dir, rel) => read(dir, rel).replace(/^﻿/, "").trim().split("\n");
function treeHash(dir) {
  const h = crypto.createHash("sha256");
  const walk = (d) => { for (const n of fs.readdirSync(d).sort()) { const p = path.join(d, n); if (fs.statSync(p).isDirectory()) walk(p); else h.update(path.relative(dir, p) + "\0").update(fs.readFileSync(p)); } };
  walk(dir);
  return h.digest("hex");
}

const LEDGER_HEADER = "日期,時間,類型,金額,分類,帳戶,桶,內容,備註,來源,id";
const KCAL_HEADER = "日期,時間,餐別,內容,熱量下限,熱量上限,蛋白質,脂肪,碳水,來源,id";
const ID = /^[bkw]\d{6}-[0-9a-z]{4}$/;

test("one call records several items, food in both the ledger and the calories, with ids and one confirmation line per row", () => {
  const dir = workspace();
  const r = add(dir, [
    { 表: "帳", 金額: 45, 分類: "早餐", 內容: "蛋餅", 時間: "08:10" },
    { 表: "帳", 金額: 120, 分類: "午餐", 內容: "雞腿便當" },
    { 表: "熱量", 餐別: "午餐", 內容: "雞腿便當", 熱量: [700, 850], 蛋白質: 35 },
    { 表: "健身", 動作: "伏地挺身", 組數: 3, 次數或秒數: 15 },
    { 表: "體重", 體重: 70.5, 體脂: 22 },
  ]);
  assert.equal(r.code, 0, r.out);
  assert.match(r.out, /^已記 5 筆：/);
  const confirm = r.out.split("\n").filter((l) => l.startsWith("・"));
  assert.equal(confirm.length, 5);
  assert.match(confirm[0], /^・支出 45 元｜早餐｜蛋餅｜現金　11\/14 08:10　編號 b261114-[0-9a-z]{4}$/);
  assert.match(confirm[2], /熱量 700–850 大卡｜午餐｜雞腿便當（蛋白質 35 g）/);
  assert.match(confirm[4], /體重 70\.5 kg（體脂 22%）.*編號 t261114-[0-9a-z]{4}$/);
  assert.match(r.out, /打錯的話：undo b261114-\S+ b261114-\S+ k261114-\S+ w261114-\S+ t261114-\S+/);
  assert.match(r.out, /今天到目前：支出 165 元；熱量 700–850 \/ 2,000 大卡/);
  // The ledger card the app shows, with the month's spending from the script.
  assert.match(r.out, /<!--dshm-card ledger\n項目: 蛋餅等 2 筆\n金額: -165\n本月支出: -165\n-->/);

  const ledger = lines(dir, "生活/帳本/2026-11.csv");
  assert.equal(ledger[0], LEDGER_HEADER);
  assert.equal(ledger.length, 3);
  assert.match(ledger[2], /^2026-11-14,12:30,支出,120,午餐,現金,,雞腿便當,,對話,b261114-[0-9a-z]{4}$/);
  assert.ok(read(dir, "生活/帳本/2026-11.csv").startsWith("﻿"), "a BOM so Excel shows the Chinese");
  assert.equal(lines(dir, "生活/熱量/2026-11.csv")[0], KCAL_HEADER);
  assert.match(lines(dir, "生活/熱量/2026-11.csv")[1], /^2026-11-14,12:30,午餐,雞腿便當,700,850,35,,,對話,k261114-/);
  assert.equal(lines(dir, "生活/健身/2026-11.csv")[0], "日期,動作,組數,次數或秒數,備註,id");
  assert.deepEqual(lines(dir, "生活/體重.csv"), ["日期,體重,體脂,備註", "2026-11-14,70.5,22,"]);
  const ids = ledger.slice(1).map((l) => l.split(",").at(-1));
  for (const id of ids) assert.match(id, ID);
  assert.notEqual(ids[0], ids[1]);

  // The first write leaves every setting with its default in 生活/設定.json.
  const s = JSON.parse(read(dir, "生活/設定.json"));
  assert.equal(s.幣別, "TWD");
  assert.deepEqual(s.帳戶, ["現金"]);
  assert.deepEqual(s.桶, []);
  assert.equal(s.提醒.開啟, false);
  assert.equal(s.功能.外送, false);
  for (const c of ["早餐", "午餐", "晚餐", "宵夜", "手搖飲", "超商", "交通", "機車油錢", "日用品", "娛樂", "其他"]) assert.ok(s.支出分類.includes(c), c);
});

test("an exact duplicate on the same day is refused until --force, and nothing of that batch is written", () => {
  const dir = workspace();
  const tea = { 表: "帳", 金額: 60, 分類: "手搖飲", 內容: "珍奶" };
  assert.equal(add(dir, tea).code, 0);
  const r = add(dir, [{ 表: "帳", 金額: 45, 分類: "早餐", 內容: "蛋餅" }, tea]);
  assert.equal(r.code, 3);
  assert.match(r.out, /同一天已經有一模一樣的了/);
  assert.match(r.out, /--force/);
  assert.equal(lines(dir, "生活/帳本/2026-11.csv").length, 2, "the breakfast was not written either");
  assert.equal(lifeJson(dir, ["add", JSON.stringify(tea)]).data.status, "duplicate");
  assert.equal(add(dir, [{ 表: "帳", 金額: 45, 分類: "早餐", 內容: "蛋餅" }, tea], { force: true }).code, 0);
  assert.equal(lines(dir, "生活/帳本/2026-11.csv").length, 4);
  // Not a duplicate: another day, another amount, or another content.
  assert.equal(add(dir, { ...tea, 日期: "昨天" }).code, 0);
  assert.equal(add(dir, { ...tea, 金額: 65 }).code, 0);
  // The same amount and category with other words is saved, with a hint that it may be the same thing.
  const near = add(dir, { ...tea, 內容: "珍珠奶茶" });
  assert.equal(near.code, 0);
  assert.match(near.out, /提示：同一天也有一筆 手搖飲 60 元/);
  // Turning the check off is a setting.
  assert.equal(life(dir, ["set", "重複檢查", "關"]).code, 0);
  assert.equal(add(dir, tea).code, 0);
});

test("workouts are told apart by their action; a CSV that is not a record file is left alone with a clear message", () => {
  const dir = workspace();
  assert.equal(add(dir, { 表: "健身", 動作: "伏地挺身", 組數: 3, 次數或秒數: 15, 備註: "早上" }).code, 0);
  assert.equal(add(dir, { 表: "健身", 動作: "深蹲", 組數: 3, 次數或秒數: 15, 備註: "早上" }).code, 0);
  assert.equal(add(dir, { 表: "健身", 動作: "深蹲", 組數: 3, 次數或秒數: 15 }).code, 3);
  fs.mkdirSync(path.join(dir, "生活/帳本"));
  fs.writeFileSync(path.join(dir, "生活/帳本/2026-10.csv"), "名字,電話\n小明,0912\n");
  const r = lifeJson(dir, ["add", JSON.stringify({ 表: "帳", 金額: 10, 分類: "其他", 日期: "2026-10-01" })]);
  assert.equal(r.code, 3);
  assert.equal(r.data.status, "foreign_file");
  assert.equal(read(dir, "生活/帳本/2026-10.csv"), "名字,電話\n小明,0912\n");
  assert.equal(lifeJson(dir, ["month", "2026-10"]).data.ledger.count, 0);
});

test("input that does not make sense writes nothing and says why", () => {
  const dir = workspace();
  let r = add(dir, [{ 表: "帳", 金額: 120, 分類: "午餐" }, { 表: "帳", 金額: "很多", 分類: "晚餐" }]);
  assert.equal(r.code, 2);
  assert.match(r.out, /第 2 筆：金額「很多」看不懂/);
  assert.equal(fs.existsSync(path.join(dir, "生活")), false);
  assert.match(add(dir, { 表: "帳", 類型: "借錢", 金額: 100 }).out, /類型「借錢」看不懂/);
  assert.match(add(dir, { 表: "帳", 金額: 100, 日期: "2026-02-30" }).out, /日期「2026-02-30」看不懂/);
  assert.match(add(dir, { 表: "熱量", 內容: "神秘便當" }).out, /沒有熱量，給一個範圍/);
  assert.match(add(dir, { 表: "體重", 體重: 7 }).out, /體重「7」看不懂/);
  assert.equal(fs.existsSync(path.join(dir, "生活")), false, "nothing was written by any of these");
  assert.equal(life(dir, ["add", "{not json"]).code, 2);
  assert.equal(add(dir, { 金額: 1, 分類: "其他" }).code, 0, "表 can be left out when the fields say it");
  assert.equal(life(dir, ["nonsense"]).code, 2);
});

test("undo removes exactly the rows named; an unknown id removes nothing", () => {
  const dir = workspace();
  const r = lifeJson(dir, ["add", JSON.stringify([{ 表: "帳", 金額: 120, 分類: "午餐", 內容: "便當" }, { 表: "帳", 金額: 60, 分類: "手搖飲", 內容: "紅茶" }, { 表: "體重", 體重: 70 }])]);
  const [a, b, w] = r.data.rows.map((x) => x.id);
  const bad = life(dir, ["undo", a, "b261114-zzzz"]);
  assert.equal(bad.code, 2);
  assert.equal(lines(dir, "生活/帳本/2026-11.csv").length, 3);
  const ok = life(dir, ["undo", a, w]);
  assert.equal(ok.code, 0, ok.out);
  assert.match(ok.out, /已刪掉：\n・支出 120 元｜午餐｜便當｜現金/);
  assert.match(ok.out, /今天到目前：支出 60 元/);
  assert.deepEqual(lines(dir, "生活/帳本/2026-11.csv").map((l) => l.split(",").at(-1)), ["id", b]);
  assert.deepEqual(lines(dir, "生活/體重.csv"), ["日期,體重,體脂,備註"]);
});

test("edit changes a row in place, and moves it to another month's file when the date changes", () => {
  const dir = workspace();
  const id = lifeJson(dir, ["add", JSON.stringify({ 表: "帳", 金額: 120, 分類: "午餐", 內容: "便當" })]).data.rows[0].id;
  let r = life(dir, ["edit", id, '{"金額":150}']);
  assert.equal(r.code, 0, r.out);
  assert.match(r.out, /原本 支出 120 元.*\n・現在 支出 150 元/);
  assert.match(lines(dir, "生活/帳本/2026-11.csv")[1], new RegExp(`^2026-11-14,12:30,支出,150,午餐,現金,,便當,,對話,${id}$`));
  r = life(dir, ["edit", id, '{"日期":"2026-10-31"}']);
  assert.equal(r.code, 0, r.out);
  assert.equal(lines(dir, "生活/帳本/2026-11.csv").length, 1);
  assert.match(lines(dir, "生活/帳本/2026-10.csv")[1], new RegExp(`^2026-10-31,12:30,支出,150,.*,${id}$`));
  assert.equal(life(dir, ["undo", id]).code, 0);
  assert.equal(lines(dir, "生活/帳本/2026-10.csv").length, 1);
});

// A weighing's id came from its order within the day, so after one undo the same id named the other weighing.
test("a weighing's id names that weighing: a repeated undo finds nothing and the other weighing of the day stays", () => {
  const dir = workspace();
  const morning = lifeJson(dir, ["add", JSON.stringify({ 表: "體重", 體重: 60.2, 備註: "早上" })]).data.rows[0].id;
  const evening = lifeJson(dir, ["add", JSON.stringify({ 表: "體重", 體重: 61, 備註: "晚上" })]).data.rows[0].id;
  assert.match(morning, /^t261114-[0-9a-z]{4}$/);
  assert.match(evening, /^t261114-[0-9a-z]{4}$/);
  assert.notEqual(morning, evening);
  // The id read back from the file is the one handed out.
  assert.deepEqual(lifeJson(dir, ["recent", "--表", "體重"]).data.rows.map((x) => x.id).sort(), [morning, evening].sort());
  assert.equal(life(dir, ["undo", morning]).code, 0);
  const again = life(dir, ["undo", morning]);
  assert.equal(again.code, 2, again.out);
  assert.match(again.out, /找不到/);
  assert.deepEqual(lines(dir, "生活/體重.csv"), ["日期,體重,體脂,備註", "2026-11-14,61,,晚上"]);
  assert.equal(life(dir, ["undo", evening]).code, 0);
  assert.deepEqual(lines(dir, "生活/體重.csv"), ["日期,體重,體脂,備註"]);
});

test("identical weighings on one day get their own ids; an edit gives a weighing a fresh id", () => {
  const dir = workspace();
  const w = { 表: "體重", 體重: 70, 備註: "早上" };
  const first = lifeJson(dir, ["add", JSON.stringify(w)]).data.rows[0].id;
  const second = lifeJson(dir, ["add", "--force", JSON.stringify(w)]).data.rows[0].id;
  assert.equal(second, `${first}-2`);
  assert.equal(life(dir, ["undo", second]).code, 0);
  assert.equal(lines(dir, "生活/體重.csv").length, 2);

  // Moved to the day before: a new id, and the old one no longer names anything.
  const r = lifeJson(dir, ["edit", first, '{"日期":"昨天"}']);
  assert.equal(r.code, 0, r.out);
  const moved = r.data.after.id;
  assert.match(moved, /^t261113-[0-9a-z]{4}$/);
  assert.notEqual(moved, first);
  assert.deepEqual(lines(dir, "生活/體重.csv"), ["日期,體重,體脂,備註", "2026-11-13,70,,早上"]);
  assert.match(life(dir, ["edit", first, '{"體重":71}']).out, /找不到/);
  assert.equal(lifeJson(dir, ["recent", "--表", "體重"]).data.rows[0].id, moved);
  // A changed value is a new id too, the one the text shows.
  const changed = life(dir, ["edit", moved, '{"體重":69.5}']);
  assert.equal(changed.code, 0, changed.out);
  const now = lifeJson(dir, ["recent", "--表", "體重"]).data.rows[0].id;
  assert.notEqual(now, moved);
  assert.match(changed.out, new RegExp(`現在 體重 69\\.5 kg.*編號 ${now}$`, "m"));
  assert.equal(life(dir, ["undo", now]).code, 0);
  assert.deepEqual(lines(dir, "生活/體重.csv"), ["日期,體重,體脂,備註"]);
});

// The card's month total named 本月 even for last month's entry, and printed "-0" when nothing was spent.
test("the ledger card names the entry's month and prints no minus for nothing spent", () => {
  const dir = workspace();
  const now = "2026-11-01 09:00";
  const back = add(dir, { 表: "帳", 金額: 120, 分類: "午餐", 內容: "便當", 日期: "昨天" }, { now });
  assert.equal(back.code, 0, back.out);
  assert.match(back.out, /<!--dshm-card ledger\n項目: 便當\n金額: -120\n類別: 午餐\n10月支出: -120\n-->/);
  const income = add(dir, { 表: "帳", 類型: "收入", 金額: 500, 分類: "其他收入", 內容: "賣二手書" }, { now });
  assert.equal(income.code, 0, income.out);
  assert.match(income.out, /<!--dshm-card ledger\n項目: 賣二手書\n金額: \+500\n類別: 其他收入\n本月支出: 0\n-->/);
});

test("today: spending by category, budget left per day, calories and macros against the targets, workouts", () => {
  const dir = workspace();
  assert.equal(life(dir, ["set", "每月預算", "15000", "分類預算.手搖飲", "1500"]).code, 0);
  assert.equal(add(dir, [
    { 表: "帳", 金額: 1000, 分類: "日用品", 內容: "洗衣精", 日期: "2026-11-02" },
    { 表: "帳", 金額: 120, 分類: "午餐", 內容: "便當" },
    { 表: "帳", 金額: 60, 分類: "手搖飲", 內容: "珍奶" },
    { 表: "帳", 類型: "收入", 金額: 500, 分類: "其他收入", 內容: "賣二手書" },
    { 表: "熱量", 餐別: "午餐", 內容: "便當", 熱量: "700-850", 蛋白質: 35 },
    { 表: "熱量", 餐別: "飲料", 內容: "珍奶", 熱量: [300, 400], 蛋白質: 10, 脂肪: 12 },
    { 表: "熱量", 餐別: "點心", 內容: "蘋果", 熱量: 80 },
    { 表: "健身", 動作: "平板支撐", 組數: 3, 秒數: 60 },
  ]).code, 0);
  const { data, code } = lifeJson(dir, ["today"]);
  assert.equal(code, 0);
  assert.equal(data.ledger.expense, 180);
  assert.equal(data.ledger.income, 500);
  assert.deepEqual(data.ledger.byCategory, [{ category: "午餐", amount: 120 }, { category: "手搖飲", amount: 60 }]);
  assert.equal(data.ledger.monthExpense, 1180);
  assert.deepEqual(data.ledger.budget, { monthly: 15000, remaining: 13820, daysLeft: 17, perDay: 812 });
  assert.deepEqual(data.ledger.categoryBudgets, [{ category: "手搖飲", budget: 1500, spent: 60, remaining: 1440 }]);
  assert.equal(data.calories.low, 1080);
  assert.equal(data.calories.high, 1330);
  assert.equal(data.calories.target, 2000);
  assert.equal(data.calories.remainingLow, 670);
  assert.equal(data.calories.remainingHigh, 920);
  assert.deepEqual(data.calories.protein, { sum: 45, target: 100, rows: 2 });
  assert.equal(data.calories.missingMacros, 1);
  assert.deepEqual(data.workout.items.map((w) => [w.action, w.sets, w.reps]), [["平板支撐", 3, "60秒"]]);
  const text = life(dir, ["today"]).out;
  assert.match(text, /^11\/14（六）今天\n支出 180 元：午餐 120、手搖飲 60\n收入 500 元\n本月已花 1,180 元，預算還剩 13,820 元，之後每天約 812 元（含今天剩 17 天）\n手搖飲：本月 60 \/ 預算 1,500，還剩 1,440/);
  assert.match(text, /熱量 1,080–1,330 大卡（目標 2,000），還有約 670–920 大卡\n蛋白質 45\/100 g、脂肪 12\/65 g（1 筆沒有營養素）\n運動：平板支撐 3×60秒/);
  // Another day, and a day with nothing.
  assert.match(life(dir, ["today", "2026-11-02"]).out, /^11\/2（一）\n支出 1,000 元：日用品 1,000/);
  assert.match(life(dir, ["today", "--date", "昨天"]).out, /還沒記帳/);
});

test("calorie targets follow the body settings; a target that is too low is refused", () => {
  const dir = workspace();
  let r = life(dir, ["set", "身體.身高", "170", "身體.體重", "65", "身體.年齡", "30", "身體.性別", "男", "身體.活動量", "輕度"]);
  assert.equal(r.code, 0, r.out);
  // Mifflin-St Jeor: 10×65 + 6.25×170 − 5×30 + 5 = 1567.5; × 1.375 = 2155 → 2150.
  assert.match(r.out, /熱量目標現在是 2,150 大卡、蛋白質 80 g、脂肪 70 g、碳水 300 g（身高體重估算）/);
  r = life(dir, ["set", "身體.目標", "減重"]);
  assert.match(r.out, /熱量目標現在是 1,850 大卡、蛋白質 105 g/);
  // A weight logged later is the one the targets use.
  assert.equal(add(dir, { 表: "體重", 體重: 80 }).code, 0);
  assert.equal(lifeJson(dir, ["settings"]).data.targets.蛋白質, 130);
  assert.equal(life(dir, ["set", "熱量目標.熱量", "700"]).code, 2);
  r = life(dir, ["set", "熱量目標.熱量", "1100"]);
  assert.equal(r.code, 0);
  assert.match(r.out, /偏低.*醫師或營養師/);
  assert.equal(lifeJson(dir, ["today"]).data.calories.target, 1100);
  assert.equal(life(dir, ["set", "熱量目標.熱量", "自動"]).code, 0);
  // Back to the estimate, with the 80 kg weighed today: 1717.5 × 1.375 × 0.85 = 2007 → 2000.
  assert.equal(lifeJson(dir, ["today"]).data.calories.target, 2000);
  // Nothing set at all: a generic 2000 kcal.
  assert.deepEqual(lib.targets(lib.DEFAULTS), { 熱量: 2000, 蛋白質: 100, 脂肪: 65, 碳水: 255, basis: "一般成人參考值", bmr: null });
});

test("month reads the older skill's files without touching them: totals, categories, last month, one sentence", () => {
  const dir = workspace("legacy-root");
  const before = treeHash(dir);
  const oct = lifeJson(dir, ["month", "2026-10"]).data;
  assert.equal(oct.ledger.expense, 509);
  assert.equal(oct.ledger.income, 30000);
  assert.equal(oct.ledger.legacyRows, 6);
  assert.deepEqual(oct.ledger.byCategory.map((c) => [c.category, c.amount]), [["購物", 199], ["交通", 150], ["手搖飲", 115], ["餐飲", 45]]);
  assert.equal(oct.ledger.previous.expense, 1370, "September's file has positive amounts and no sign: 薪水 is income");
  assert.equal(oct.ledger.previous.income, 30000);
  assert.equal(oct.ledger.sentence, "這個月手搖飲 115 元，比上月多 130%");
  assert.deepEqual(oct.ledger.top[0], { date: "2026-10-20", content: "衛生紙", category: "購物", amount: 199, id: "" });
  assert.deepEqual(oct.calories, { days: 2, avgLow: 900, avgHigh: 975, target: 2000 });
  const sep = lifeJson(dir, ["month", "2026-09"]).data;
  assert.equal(sep.ledger.sentence, "這個月花最多的是娛樂 1,200 元，占 88%");
  assert.match(life(dir, ["month", "2026-10"]).out, /月結：這個月手搖飲 115 元，比上月多 130%/);
  assert.match(life(dir, ["month", "2026-10"]).out, /（含舊格式帳本 6 筆）/);
  for (const cmd of [["today"], ["recent"], ["find", "手搖飲"], ["buckets"], ["settings"], ["migrate"]]) life(dir, cmd);
  assert.equal(treeHash(dir), before, "reading never changes or creates a file");
  assert.equal(fs.existsSync(path.join(dir, "生活")), false);

  // New rows go to 生活/, the old files stay as they are, and both are counted.
  assert.equal(add(dir, { 表: "帳", 金額: 91, 分類: "手搖飲", 內容: "多多綠", 日期: "2026-10-28" }).code, 0);
  assert.equal(read(dir, "帳本/2026-10.csv"), fs.readFileSync(path.join(FIX, "legacy-root/帳本/2026-10.csv"), "utf8"));
  assert.equal(lifeJson(dir, ["month", "2026-10"]).data.ledger.expense, 600);
  // An old row counts for the duplicate check too.
  assert.equal(add(dir, { 表: "帳", 金額: 55, 分類: "手搖飲", 內容: "珍奶", 日期: "2026-10-12" }).code, 3);
  assert.equal(add(dir, { 表: "熱量", 內容: "牛肉麵", 餐別: "晚餐", 熱量: 700, 日期: "2026-10-02" }).code, 3);
});

test("this month is compared with the same days of last month", () => {
  const dir = workspace();
  add(dir, [{ 表: "帳", 金額: 100, 分類: "手搖飲", 內容: "a", 日期: "2026-10-05" }, { 表: "帳", 金額: 900, 分類: "手搖飲", 內容: "b", 日期: "2026-10-25" }, { 表: "帳", 金額: 140, 分類: "手搖飲", 內容: "c", 日期: "2026-11-03" }]);
  const m = lifeJson(dir, ["month"]).data;
  assert.equal(m.partial, true);
  assert.equal(m.ledger.previous.expense, 100);
  assert.equal(m.ledger.sentence, "這個月手搖飲 140 元，比上月同期多 40%");
});

test("an older file inside 生活/ is never rewritten until migrate --yes, which keeps a backup", () => {
  const dir = workspace("legacy-in-life");
  const original = read(dir, "生活/帳本/2026-11.csv");
  let r = add(dir, { 表: "帳", 金額: 120, 分類: "午餐", 內容: "便當" });
  assert.equal(r.code, 3);
  assert.match(r.out, /生活\/帳本\/2026-11\.csv 是舊格式的檔案/);
  assert.match(r.out, /migrate --yes/);
  assert.equal(read(dir, "生活/帳本/2026-11.csv"), original);
  const totals = lifeJson(dir, ["month"]).data.ledger;
  assert.equal(totals.expense, 105);
  r = life(dir, ["migrate"]);
  assert.match(r.out, /生活\/帳本\/2026-11\.csv：3 筆/);
  assert.equal(read(dir, "生活/帳本/2026-11.csv"), original, "migrate without --yes only lists");
  r = life(dir, ["migrate", "--yes"]);
  assert.equal(r.code, 0, r.out);
  assert.equal(read(dir, "生活/舊格式備份/帳本/2026-11.csv"), original);
  const now = lines(dir, "生活/帳本/2026-11.csv");
  assert.equal(now[0], LEDGER_HEADER);
  assert.match(now[1], /^2026-11-01,,支出,45,餐飲,,,早餐,蛋餅,舊帳本,b261101-[0-9a-z]{4}$/);
  assert.match(now[2], /^2026-11-02,,收入,30000,收入,,,薪水,,舊帳本,b261102-/);
  const after = lifeJson(dir, ["month"]).data.ledger;
  assert.equal(after.expense, 105);
  assert.equal(after.income, 30000);
  assert.equal(add(dir, { 表: "帳", 金額: 120, 分類: "午餐", 內容: "便當" }).code, 0);
  assert.equal(life(dir, ["migrate"]).out.trim(), "沒有舊格式的檔案");
});

test("migrate moves the older skill's folders into 生活/ and reports rows it could not read", () => {
  const dir = workspace("legacy-root");
  const r = life(dir, ["migrate", "--yes"]);
  assert.equal(r.code, 0, r.out);
  assert.match(r.out, /有 1 列看不懂，沒有搬/);
  assert.equal(fs.existsSync(path.join(dir, "帳本/2026-10.csv")), false);
  assert.equal(read(dir, "生活/舊格式備份/帳本/2026-09.csv"), fs.readFileSync(path.join(FIX, "legacy-root/帳本/2026-09.csv"), "utf8"));
  assert.match(lines(dir, "生活/熱量/2026-10.csv")[2], /^2026-10-01,,午餐,雞腿便當（外帶）,750,900,,,,舊帳本,k261001-/);
  const oct = lifeJson(dir, ["month", "2026-10"]).data;
  assert.equal(oct.ledger.expense, 509);
  assert.equal(oct.ledger.legacyRows, 0);
  assert.equal(oct.ledger.sentence, "這個月手搖飲 115 元，比上月多 130%");
});

test("buckets split income exactly by the rules and carry balances from month to month", () => {
  const dir = workspace();
  assert.equal(life(dir, ["buckets"]).out.startsWith("還沒設定桶"), true);
  let r = life(dir, ["set", "桶", '[{"名稱":"生活費","比例":92},{"名稱":"娛樂費","比例":8}]', "分類對應桶.手搖飲", "娛樂費", "桶開始月份", "2026-10"]);
  assert.equal(r.code, 0, r.out);
  assert.deepEqual(lifeJson(dir, ["buckets", "--income", "30000"]).data.allocation, [{ name: "生活費", amount: 27600 }, { name: "娛樂費", amount: 2400 }]);
  r = add(dir, [
    { 表: "帳", 類型: "收入", 金額: 30000, 分類: "薪水", 日期: "2026-10-05" },
    { 表: "帳", 金額: 500, 分類: "手搖飲", 內容: "飲料", 日期: "2026-10-10" },
    { 表: "帳", 金額: 1000, 分類: "午餐", 內容: "便當", 日期: "2026-10-11" },
    { 表: "帳", 類型: "收入", 金額: 20000, 分類: "薪水", 日期: "2026-11-05" },
    { 表: "帳", 金額: 300, 分類: "手搖飲", 內容: "飲料", 日期: "2026-11-06" },
    { 表: "帳", 類型: "轉帳", 金額: 200, 桶: "生活費→娛樂費", 日期: "2026-11-07" },
  ]);
  assert.equal(r.code, 0, r.out);
  assert.match(r.out, /收入 30,000 元｜薪水｜現金｜分到 生活費 27,600、娛樂費 2,400/);
  assert.match(lines(dir, "生活/帳本/2026-10.csv")[1], /,薪水,現金,生活費 27600、娛樂費 2400,/);
  assert.match(lines(dir, "生活/帳本/2026-10.csv")[2], /,手搖飲,現金,娛樂費,/);
  const b = lifeJson(dir, ["buckets"]).data.buckets;
  assert.deepEqual(b.map((x) => [x.name, x.carry, x.inflow, x.outflow, x.transfer, x.balance]), [
    ["生活費", 26600, 18400, 0, -200, 44800],
    ["娛樂費", 1900, 1600, 300, 200, 3400],
  ]);
  assert.deepEqual(lifeJson(dir, ["today"]).data.ledger.buckets, [{ name: "生活費", spentToday: 0, balance: 44800 }, { name: "娛樂費", spentToday: 0, balance: 3400 }]);
  // New rules apply to new income only: what was split stays split.
  assert.equal(life(dir, ["set", "桶", '[{"名稱":"生活費","比例":50},{"名稱":"娛樂費","比例":50}]']).code, 0);
  assert.deepEqual(lifeJson(dir, ["buckets"]).data.buckets.map((x) => x.balance), [44800, 3400]);
  // Without carry-over a month starts from zero.
  assert.equal(life(dir, ["set", "桶結轉", "關"]).code, 0);
  assert.deepEqual(lifeJson(dir, ["buckets"]).data.buckets.map((x) => x.balance), [18200, 1500]);
  // Rules over 100% are refused; under 100% the rest is 未分配.
  assert.equal(life(dir, ["set", "桶.娛樂費", "60"]).code, 2);
  r = life(dir, ["set", "桶.娛樂費", "30"]);
  assert.match(r.out, /剩下的 20% 會算在「未分配」/);
  assert.deepEqual(lifeJson(dir, ["buckets", "--income", "1000"]).data.allocation.map((p) => [p.name, p.amount]), [["生活費", 500], ["娛樂費", 300], ["未分配", 200]]);
  assert.match(add(dir, { 表: "帳", 金額: 1, 分類: "其他", 桶: "旅遊" }).out, /沒有「旅遊」這個桶/);
  // Today's spending per bucket.
  assert.equal(add(dir, [{ 表: "帳", 金額: 50, 分類: "手搖飲", 內容: "紅茶" }, { 表: "帳", 金額: 90, 分類: "午餐", 內容: "麵" }]).code, 0);
  assert.deepEqual(lifeJson(dir, ["today"]).data.ledger.buckets.map((x) => [x.name, x.spentToday]), [["生活費", 90], ["娛樂費", 50]]);
  assert.match(life(dir, ["today"]).out, /桶：生活費 今天花 90，剩 [\d,]+、娛樂費 今天花 50，剩 [\d,]+/);
});

test("allocate always adds up to the amount", () => {
  const sum = (parts) => parts.reduce((a, p) => a + p.cents, 0);
  const thirds = lib.allocate(100000, [{ 名稱: "a", 比例: 33 }, { 名稱: "b", 比例: 33 }, { 名稱: "c", 比例: 34 }]);
  assert.deepEqual(thirds.map((p) => p.cents), [33000, 33000, 34000]);
  const halves = lib.allocate(100100, [{ 名稱: "a", 比例: 50 }, { 名稱: "b", 比例: 50 }]);
  assert.deepEqual(halves.map((p) => p.cents), [50100, 50000]);
  for (const cents of [1, 99, 101, 12345, 3333300, 9999900]) {
    for (const rules of [[{ 名稱: "a", 比例: 92 }, { 名稱: "b", 比例: 8 }], [{ 名稱: "a", 比例: 33.3 }, { 名稱: "b", 比例: 33.3 }, { 名稱: "c", 比例: 33.4 }], [{ 名稱: "a", 比例: 70 }]]) {
      assert.equal(sum(lib.allocate(cents * 100, rules)), cents * 100);
      assert.equal(sum(lib.allocate(cents, rules, 1)), cents);
    }
  }
});

test("every list and number is a setting; unknown keys and a broken settings file are refused", () => {
  const dir = workspace();
  let r = life(dir, ["set", "支出分類", "+寵物", "帳戶", "+郵局", "預設帳戶", "郵局"]);
  assert.equal(r.code, 0, r.out);
  r = life(dir, ["set", "支出分類", "-宵夜"]);
  let s = lifeJson(dir, ["settings"]).data.settings;
  assert.ok(s.支出分類.includes("寵物") && !s.支出分類.includes("宵夜"));
  assert.deepEqual(s.帳戶, ["現金", "郵局"]);
  assert.match(add(dir, { 表: "帳", 金額: 300, 分類: "寵物", 內容: "飼料" }).out, /｜寵物｜飼料｜郵局/);
  assert.match(add(dir, { 表: "帳", 金額: 30, 分類: "文具", 內容: "筆" }).out, /提示：「文具」不在支出分類清單裡（要加的話：set 支出分類 \+文具）/);
  assert.equal(life(dir, ["set", "幣別", "usd", "單位", "USD"]).code, 0);
  assert.match(add(dir, { 表: "帳", 金額: 3.5, 分類: "咖啡", 內容: "latte" }).out, /支出 3\.50 USD/);
  // Feature switches: a calorie row is skipped while 熱量 is off, and today leaves it out.
  assert.equal(life(dir, ["set", "功能.熱量", "false"]).code, 0);
  r = add(dir, [{ 表: "帳", 金額: 80, 分類: "晚餐", 內容: "麵" }, { 表: "熱量", 內容: "麵", 熱量: 500 }]);
  assert.match(r.out, /已記 1 筆/);
  assert.match(r.out, /沒記：麵（熱量功能關著，沒記）/);
  assert.equal("calories" in lifeJson(dir, ["today"]).data, false);
  r = life(dir, ["set", "提醒.開啟", "開", "提醒.時間", "21:30"]);
  assert.match(r.out, /用排程工具建立每天 21:30 的提醒/);
  assert.match(life(dir, ["set", "不存在", "1"]).out, /沒有「不存在」這個設定/);
  assert.equal(life(dir, ["set", "預設帳戶", "銀行"]).code, 2);
  assert.equal(life(dir, ["set", "外送.每單保底", "-1"]).code, 2);
  assert.equal(life(dir, ["set", "每月預算"]).code, 2);
  // A settings file broken by hand: nothing overwrites it, reading still works with the defaults.
  fs.writeFileSync(path.join(dir, "生活/設定.json"), "{ 這不是 JSON");
  assert.equal(life(dir, ["set", "每月預算", "1000"]).code, 2);
  assert.equal(read(dir, "生活/設定.json"), "{ 這不是 JSON");
  r = life(dir, ["today"]);
  assert.equal(r.code, 0);
  assert.match(r.out, /^注意：生活\/設定\.json 讀不懂/);
  assert.equal(add(dir, { 表: "帳", 金額: 10, 分類: "其他" }).code, 0, "records still work; the file is left as it is");
  assert.equal(read(dir, "生活/設定.json"), "{ 這不是 JSON");
});

test("我的食物庫: the owner's own numbers are used, and food eaten again and again is offered for it", () => {
  const dir = workspace();
  let r = life(dir, ["food", "add", '{"名稱":"雞腿便當","份量":"1 個","熱量":780,"蛋白質":35}']);
  assert.equal(r.code, 0, r.out);
  assert.deepEqual(lines(dir, "生活/我的食物庫.csv"), ["名稱,份量,熱量,蛋白質,脂肪,碳水", "雞腿便當,1 個,780,35,,"]);
  r = lifeJson(dir, ["add", JSON.stringify({ 表: "熱量", 內容: "雞腿便當", 餐別: "午餐" })]);
  assert.equal(r.code, 0);
  assert.equal(r.data.rows[0].熱量下限, 780);
  assert.equal(r.data.rows[0].蛋白質, 35);
  assert.equal(r.data.rows[0].來源, "食物庫");
  assert.equal(lifeJson(dir, ["add", JSON.stringify({ 表: "熱量", 內容: "雞腿便當", 份數: 0.5, 日期: "昨天" })]).data.rows[0].熱量上限, 390);
  for (const [i, day] of ["2026-11-10", "2026-11-11", "2026-11-12"].entries()) {
    r = add(dir, { 表: "熱量", 內容: "茶葉蛋", 熱量: [70, 80], 蛋白質: 6, 日期: day });
    assert.equal(r.code, 0);
    assert.equal(/記過 3 次了.*food learn 茶葉蛋/.test(r.out), i === 2, r.out);
  }
  assert.match(life(dir, ["food", "suggest"]).out, /茶葉蛋 記過 3 次，平均約 75 大卡/);
  r = life(dir, ["food", "learn", "茶葉蛋"]);
  assert.match(r.out, /存進我的食物庫：茶葉蛋（1 份） 75 大卡、蛋白質 6 g/);
  assert.match(life(dir, ["food", "find", "蛋"]).out, /茶葉蛋/);
  assert.equal(life(dir, ["food", "remove", "茶葉蛋"]).code, 0);
  assert.doesNotMatch(life(dir, ["food"]).out, /茶葉蛋/);
});

test("recent lists the latest rows with their ids, and find adds up what it finds", () => {
  const dir = workspace();
  add(dir, [{ 表: "帳", 金額: 65, 分類: "咖啡", 內容: "拿鐵", 日期: "2026-11-03" }, { 表: "帳", 金額: 55, 分類: "咖啡", 內容: "美式", 日期: "2026-11-10" }, { 表: "帳", 金額: 70, 分類: "咖啡", 內容: "拿鐵", 日期: "2026-10-20" }, { 表: "帳", 金額: 120, 分類: "午餐", 內容: "便當, 加蛋 \"大\"" }]);
  const recent = lifeJson(dir, ["recent", "2"]).data.rows;
  assert.deepEqual(recent.map((x) => x.內容), ["便當, 加蛋 \"大\"", "美式"], "a comma and quotes survive the CSV");
  let f = lifeJson(dir, ["find", "咖啡", "--month", "2026-11"]).data;
  assert.equal(f.count, 2);
  assert.equal(f.expense, 120);
  f = lifeJson(dir, ["find", "拿鐵"]).data;
  assert.equal(f.expense, 135);
});

test("uber: the invented statement is read the same in both line shapes and every sum checks", () => {
  const dir = workspace();
  const a = lifeJson(dir, ["uber", "--file", path.join(FIX, "uber-week.txt")], { now: "2026-11-17 09:00" });
  const b = lifeJson(dir, ["uber", "--file", path.join(FIX, "uber-week-oneline.txt")], { now: "2026-11-17 09:00" });
  assert.equal(a.code, 0);
  assert.deepEqual(b.data, a.data);
  const u = a.data;
  assert.equal(u.status, "ok", JSON.stringify(u.checks.filter((c) => !c.ok)) + u.problems.join());
  assert.deepEqual(u.period, ["2026-11-09", "2026-11-16"]);
  assert.deepEqual([u.opening, u.earnings, u.cash, u.closing], [-500, 1690, 2800, -1610]);
  assert.equal(u.owesUber, true);
  assert.equal(u.transferToUber, 1610);
  assert.deepEqual(u.breakdown.fares, { total: 1600, parts: [{ label: "起步價", amount: 960 }, { label: "基礎費+", amount: 640 }] });
  assert.deepEqual(u.breakdown.other, { total: 40, parts: [{ label: "保底收入", amount: 40 }] });
  assert.equal(u.breakdown.tips.total, 50);
  assert.deepEqual(u.transactions, { rows: 15, deliveries: 12, earnings: 1690, cash: 2800, fares: 1600, tips: 50, guarantee: 40, other: 0 });
  assert.ok(u.checks.length >= 10 && u.checks.every((c) => c.ok));
  // The per-order minimum: max(45, minutes × 1.25 × 196 / 60) − fare, summed over the orders that fall short.
  assert.equal(u.estimate.isEstimate, true);
  assert.equal(u.estimate.counted, 12);
  assert.deepEqual(u.estimate.perOrder.filter((o) => o.topUp > 0).map((o) => [o.row, o.minutes, o.minimum, o.topUp]), [[5, 12, 49, 1], [6, 38, 155.17, 25.17], [8, 15, 61.25, 1.25], [12, 26, 106.17, 10.17], [13, 40, 163.33, 24.33]]);
  assert.equal(u.estimate.topUp, 61.92);
  assert.equal(u.estimate.guaranteePaid, 40);
  assert.equal(u.estimate.difference, 21.92);
  const text = life(dir, ["uber", "--file", path.join(FIX, "uber-week.txt")], { now: "2026-11-17 09:00" }).out;
  assert.match(text, /期末餘額 -1,610 元：要轉 1,610 元給 Uber/);
  assert.match(text, /核對：全部對得上/);
  assert.match(text, /估算，僅供參考/);
  assert.match(text, /估計應補約 61\.92 元/);
  assert.match(text, /add '\{"表":"帳","類型":"收入","金額":1690,"分類":"外送收入"/);
  // The text can come on stdin too.
  assert.deepEqual(lifeJson(dir, ["uber"], { now: "2026-11-17 09:00", input: fs.readFileSync(path.join(FIX, "uber-week.txt"), "utf8") }).data, u);
});

test("uber: a statement that does not add up is reported, not guessed", () => {
  const dir = workspace();
  const r = lifeJson(dir, ["uber", "--file", path.join(FIX, "uber-week-mismatch.txt")], { now: "2026-11-17 09:00" });
  assert.equal(r.data.status, "mismatch");
  const failed = r.data.checks.filter((c) => !c.ok).map((c) => [c.name, c.diff]);
  assert.deepEqual(failed, [["交易明細的收入加總 = 您的收入", 5], ["外送單的收入加總 = 車費", 5]]);
  assert.ok(r.data.problems.some((p) => p.includes("交易第 6 列")));
  const text = life(dir, ["uber", "--file", path.join(FIX, "uber-week-mismatch.txt")], { now: "2026-11-17 09:00" }).out;
  assert.match(text, /有對不上的地方/);
  assert.doesNotMatch(text, /要記進帳本/);
});

test("uber: a positive balance is paid out, the law's numbers are settings, and a week before the law says so", () => {
  const week = "初始余额\nNT$0.00\n您的收入\nNT$100.00\n已收取现金\nNT$0.00\n期末余额\nNT$100.00\n车费\nNT$100.00\n交易\n7 月 1 日 周三 下午1:30 Delivery - all 7月1日 下午1:00 NT$100.00 NT$100.00 NT$100.00\n";
  const dir = workspace();
  let r = life(dir, ["uber"], { input: week, now: "2026-07-05 10:00" });
  assert.match(r.out, /期末餘額 100 元：Uber 會付給你 100 元/);
  assert.match(r.out, /核對：全部對得上/);
  // 30 minutes at 196: 122.50 ≥ 100 → 22.50 short.
  assert.match(r.out, /估計應補約 22\.50 元/);
  assert.equal(life(dir, ["set", "外送.最低時薪", "250", "外送.每單保底", "50"]).code, 0);
  assert.equal(lifeJson(dir, ["uber"], { input: week, now: "2026-07-05 10:00" }).data.estimate.topUp, 56.25);
  const dated = "2026年6月29日 - 2026年7月6日\n" + week;
  assert.match(life(dir, ["uber"], { input: dated, now: "2026-07-07 10:00" }).out, /法規施行以前/);
  assert.equal(life(dir, ["uber"], { input: "" }).code, 2);
});

test("small parsers: money, dates, times, CSV", () => {
  assert.equal(lib.parseMoney("NT$1,200.50"), 120050);
  assert.equal(lib.parseMoney("１２０元"), 12000);
  assert.equal(lib.parseMoney("-NT$546.00"), -54600);
  assert.equal(lib.parseMoney("1.2萬"), 1200000);
  assert.equal(lib.parseMoney("3千"), 300000);
  assert.equal(lib.parseMoney("很多"), null);
  assert.equal(lib.parseDate("11/14", "2026-11-14"), "2026-11-14");
  assert.equal(lib.parseDate("12/30", "2026-01-03"), "2025-12-30");
  assert.equal(lib.parseDate("昨天", "2026-11-01"), "2026-10-31");
  assert.equal(lib.parseDate("11月3日", "2026-11-14"), "2026-11-03");
  assert.equal(lib.parseDate("2026-02-29", "2026-11-14"), null);
  assert.equal(lib.parseTime("下午3:20"), "15:20");
  assert.equal(lib.parseTime("中午12:05"), "12:05");
  assert.equal(lib.parseTime("上午12:25"), "00:25");
  assert.equal(lib.parseTime("25:00"), null);
  assert.deepEqual(lib.parseRange("650～800"), [650, 800]);
  assert.deepEqual(lib.parseCsv('a,"b,c","d ""e"""\r\n1,2,3\n'), [["a", "b,c", 'd "e"'], ["1", "2", "3"]]);
  assert.equal(lib.csvLine(["a", "b,c", 'd "e"']), 'a,"b,c","d ""e"""');
});

test("the workspace comes from --dir, else DSHM_WORKSPACE, else the current folder; help names every command", () => {
  const dir = workspace();
  const r = spawnSync(process.execPath, [SCRIPT, "add", '{"表":"帳","金額":5,"分類":"其他"}'], { encoding: "utf8", env: { ...process.env, LIFE_NOW: "2026-11-14 12:30", DSHM_WORKSPACE: dir }, cwd: os.tmpdir() });
  assert.equal(r.status, 0, r.stdout);
  assert.ok(fs.existsSync(path.join(dir, "生活/帳本/2026-11.csv")));
  const other = workspace();
  const c = spawnSync(process.execPath, [SCRIPT, "today"], { encoding: "utf8", env: { ...process.env, LIFE_NOW: "2026-11-14 12:30", DSHM_WORKSPACE: "" }, cwd: other });
  assert.match(c.stdout, /還沒記帳/);
  const help = life(null, ["help"]).out;
  for (const cmd of lib.COMMAND_NAMES.filter((x) => x !== "help")) assert.match(help, new RegExp(`^  ${cmd}\\b`, "m"), cmd);
});
