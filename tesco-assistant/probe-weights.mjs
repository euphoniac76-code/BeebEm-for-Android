#!/usr/bin/env node
/**
 * One-off: learn how tesco.com adds weighed products to the basket.
 *
 * Opens the signed-in Chrome profile, adds one product that has weight options
 * (pork fillet) and one loose product by kg (bananas) through Tesco's own web
 * page, records the basket request the page sends (product, quantity, weight
 * and unit fields only, never cookies or tokens), removes the test items again,
 * and saves the format to ~/.basketeer/weight-format.json for the helper to use.
 *
 * Run it while the helper is stopped (update.ps1 does this).
 */

import { writeFile } from "node:fs/promises";
import { join } from "node:path";
import * as basketeer from "basketeer";
import { TescoBrowserAuth, launchChrome, restoreJar, saveJar } from "./tesco-auth.mjs";
import { FORMAT_FILE, gql, loadFormat, saveFormat, sendWeighted, soldBy } from "./weights.mjs";

const { Basketeer, FileTokenStore } = basketeer;
const OUT = join(process.cwd(), "probe-capture.json");
const say = (...a) => console.log(...a);

const store = new FileTokenStore();
const client = new Basketeer({ session: await store.load(), store, authBackend: new TescoBrowserAuth() });
const report = { startedAt: new Date().toISOString(), steps: [] };
const step = (name, data) => {
  report.steps.push({ name, ...data });
  say(`- ${name}: ${data.ok === false ? "FAILED" : "ok"}${data.note ? ` (${data.note})` : ""}`);
};

// 0. Make sure we're signed in, and note what is already in the basket.
const before = await client.basket.get();
const inBasket = new Set(before.items.map((l) => l.sku));
step("signed in", { ok: true, note: `${before.items.length} items already in the basket` });

// 1. Ask Tesco's API to describe the basket line fields (may be switched off).
let inputFields = null;
try {
  const d = await gql(client, {
    operationName: "BasketInputFields",
    query: `query BasketInputFields { __type(name: "BasketLineItemInputType") { inputFields { name type { name kind ofType { name kind } } } } }`,
  });
  inputFields = d.__type?.inputFields?.map((f) => ({ name: f.name, type: f.type?.name ?? f.type?.ofType?.name ?? f.type?.kind })) ?? null;
  step("basket field list from Tesco", { ok: Boolean(inputFields), note: inputFields ? inputFields.map((f) => f.name).join(", ") : "not available" });
} catch (err) {
  step("basket field list from Tesco", { ok: false, note: String(err.message ?? err).slice(0, 120) });
}

// 2. Pick test products.
async function pick(queries, want) {
  for (const q of queries) {
    const { results } = await client.search(q, { limit: 24 });
    const hit = results.find((r) => r.available !== false && soldBy(r).kind === want && !inBasket.has(r.sku));
    if (hit) return hit;
  }
  return null;
}
const weighted = await pick(["pork fillet", "pork tenderloin", "beef roasting joint", "salmon fillet"], "weight-options");
const loose = await pick(["loose bananas", "loose brown onions", "loose carrots"], "loose");
step("test products", { ok: Boolean(weighted || loose), note: `${weighted?.title ?? "no weight-options product"} / ${loose?.title ?? "no loose product"}` });

// 3. Add them through Tesco's own page and record the request.
const ctx = await launchChrome();
const captured = [];
ctx.on("request", (req) => {
  if (req.method() !== "POST" || !req.url().startsWith("https://xapi.tesco.com")) return;
  let body;
  try {
    body = JSON.parse(req.postData() ?? "null");
  } catch {
    return;
  }
  for (const op of Array.isArray(body) ? body : [body]) {
    if (op && typeof op.query === "string" && /mutation[\s\S]*basket\s*\(/i.test(op.query)) captured.push(op);
  }
});

async function dismissCookies(page) {
  for (const re of [/accept all cookies/i, /^accept all$/i, /^accept cookies$/i]) {
    const b = page.getByRole("button", { name: re }).first();
    if (await b.isVisible().catch(() => false)) {
      await b.click().catch(() => {});
      await page.waitForTimeout(800);
      return;
    }
  }
}

async function clickAdd(page) {
  const candidates = [
    page.locator('button[data-auto="ddsweb-quantity-controls-add-button"]').first(),
    page.locator('[data-auto*="add-button" i]').first(),
    page.getByRole("button", { name: /^add( to (basket|trolley))?$/i }).first(),
  ];
  for (const c of candidates) {
    if (await c.isVisible().catch(() => false)) {
      await c.click();
      return true;
    }
  }
  return false;
}

async function addOnPage(product, chooseKg) {
  const page = ctx.pages()[0] ?? (await ctx.newPage());
  const n = captured.length;
  await page.goto(`https://www.tesco.com/groceries/en-GB/products/${product.sku}`, { waitUntil: "domcontentloaded", timeout: 30_000 });
  await page.waitForTimeout(4000);
  await dismissCookies(page);
  if (chooseKg) {
    // Loose items: switch the quantity control from "each" to "kg" if the page offers it.
    const kg = page.locator("label, button, [role=radio], [role=tab], option").filter({ hasText: /^\s*kg\s*$/i }).first();
    const sel = page.locator("select").filter({ has: page.locator("option", { hasText: /^\s*kg\s*$/i }) }).first();
    if (await sel.count()) await sel.selectOption({ label: "kg" }).catch(() => {});
    else if (await kg.isVisible().catch(() => false)) await kg.click().catch(() => {});
    await page.waitForTimeout(800);
  }
  const clicked = await clickAdd(page);
  for (let i = 0; i < 20 && captured.length === n; i++) await page.waitForTimeout(500);
  if (captured.length === n) await page.screenshot({ path: join(process.cwd(), `probe-${product.sku}.png`) }).catch(() => {});
  return { clicked, op: captured.length > n ? captured[captured.length - 1] : null };
}

function lineFor(op, sku) {
  for (const [k, v] of Object.entries(op?.variables ?? {})) {
    if (Array.isArray(v)) {
      const item = v.find((i) => i && String(i.id ?? i.productId ?? "") === String(sku));
      if (item) return { itemsVar: k, item };
    }
  }
  return null;
}

const format = (await loadFormat()) ?? {};
format.learnedAt = new Date().toISOString();
if (inputFields) format.inputFields = inputFields;

try {
  await restoreJar(ctx);
  if (weighted) {
    const s = soldBy(weighted);
    const { clicked, op } = await addOnPage(weighted, false);
    const found = op && lineFor(op, weighted.sku);
    if (found) {
      // Which field holds the weight? The one whose value matches one of the product's weights (kg or g).
      const weights = s.options.map((o) => o.weightKg);
      let weightKey = null;
      let weightScale = 1;
      for (const [k, v] of Object.entries(found.item)) {
        if (typeof v !== "number" || k === "newValue") continue;
        if (weights.some((w) => Math.abs(w - v) < 1e-6)) [weightKey, weightScale] = [k, 1];
        else if (weights.some((w) => Math.abs(w * 1000 - v) < 1e-3)) [weightKey, weightScale] = [k, 1000];
      }
      format.weightOptions = {
        kind: "weight-options",
        operationName: op.operationName,
        query: op.query,
        mfeName: op.extensions?.mfeName,
        variables: Object.fromEntries(Object.entries(op.variables ?? {}).filter(([k]) => k !== found.itemsVar)),
        itemsVar: found.itemsVar,
        itemTemplate: found.item,
        quantityKey: "newValue",
        weightKey,
        weightScale,
        learnedFrom: weighted.title,
      };
      step("recorded weight-options add", { ok: true, note: `fields: ${Object.keys(found.item).join(", ")}; weight field: ${weightKey ?? "none found"}` });
    } else {
      step("recorded weight-options add", { ok: false, note: clicked ? "Add was clicked but no basket request was seen" : "couldn't find the Add button (screenshot saved)" });
    }
  }
  if (loose) {
    const { clicked, op } = await addOnPage(loose, true);
    const found = op && lineFor(op, loose.sku);
    if (found) {
      const unit = found.item.newUnitChoice;
      const byKg = typeof unit === "string" && unit.toLowerCase() !== "pcs";
      if (byKg) {
        format.loose = {
          kind: "loose",
          operationName: op.operationName,
          query: op.query,
          mfeName: op.extensions?.mfeName,
          variables: Object.fromEntries(Object.entries(op.variables ?? {}).filter(([k]) => k !== found.itemsVar)),
          itemsVar: found.itemsVar,
          itemTemplate: found.item,
          quantityKey: "newValue",
          unitKey: "newUnitChoice",
          unitValue: unit,
          learnedFrom: loose.title,
        };
      }
      step("recorded loose add", { ok: true, note: `unit sent: ${unit}; fields: ${Object.keys(found.item).join(", ")}${byKg ? "" : " (page added it by the piece, so by-kg is still unknown)"}` });
    } else {
      step("recorded loose add", { ok: false, note: clicked ? "Add was clicked but no basket request was seen" : "couldn't find the Add button (screenshot saved)" });
    }
  }
  await saveJar(ctx);
} finally {
  await ctx.close().catch(() => {});
}

await saveFormat(format);
await writeFile(OUT, JSON.stringify({ ...report, captured: captured.map((o) => ({ operationName: o.operationName, variables: o.variables, mfeName: o.extensions?.mfeName })) }, null, 2));

// 4. Take the test items out again (only ones that weren't there before).
for (const p of [weighted, loose].filter(Boolean)) {
  if (inBasket.has(p.sku)) continue;
  let removed = false;
  try {
    const t = soldBy(p).kind === "weight-options" ? format.weightOptions : null;
    if (t) await sendWeighted(client, t, { sku: p.sku, quantity: 0, weightKg: soldBy(p).options[0].weightKg });
    else await client.basket.remove(p.sku);
    removed = !(await client.basket.get()).items.some((l) => l.sku === p.sku);
  } catch {
    // fall through to the plain remove
  }
  if (!removed) {
    try {
      await client.basket.remove(p.sku);
      removed = !(await client.basket.get()).items.some((l) => l.sku === p.sku);
    } catch {}
  }
  step(`removed test item ${p.title}`, { ok: removed, note: removed ? undefined : "please remove it from the basket yourself" });
}

say(`\nSaved: ${FORMAT_FILE}`);
say(`Details for Claude: ${OUT}`);
say("Copy the contents of probe-capture.json back to Claude if anything above FAILED.");
