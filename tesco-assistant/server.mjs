#!/usr/bin/env node
/**
 * tesco-assistant: an MCP server that lets Claude
 *   - read your past Tesco orders and work out what you usually buy, and
 *   - search the catalogue and add items to your basket.
 *
 * Built on the unofficial `basketeer` SDK, which calls Tesco's own website
 * GraphQL gateway (xapi.tesco.com). Tesco has no public API.
 *
 * Authentication (your password is never given to this server or to Claude):
 *  - Computer: `npm run login` opens a real Chrome window and you sign in. The
 *    session is refreshed automatically (Chrome briefly opens about hourly).
 *  - Android / no Chrome: sign in on tesco.com in your phone browser, export
 *    the cookies, and run `npm run import-cookies -- <file>`; start the server
 *    with TESCO_AUTH=cookies. Re-import when the session expires (~1 hour).
 * Either way the session is saved to ~/.basketeer/session.json.
 *
 * Safety: this server can only read orders and change the basket. It has no
 * checkout, payment, delivery-slot or order-cancel tools. You review the
 * basket and pay on tesco.com yourself.
 */

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import * as basketeer from "basketeer";
// Note: HttpAuthBackend only exists in basketeer releases newer than 0.4.2, so it is looked up lazily.
const { AuthExpiredError, Basketeer, BasketeerError, FileTokenStore } = basketeer;
import { z } from "zod";
import { loadFormat, nearestOption, sendWeighted, soldBy, soldByText } from "./weights.mjs";
import { closeSync, createWriteStream, openSync, readSync, renameSync } from "node:fs";
import { format as formatLog } from "node:util";

// Command-line options (so Windows can start the helper directly, without a PowerShell wrapper):
//   --http <port>   serve MCP over HTTP on 127.0.0.1:<port> (same as TESCO_HTTP_PORT)
//   --log <file>    append all output to <file>
const argValue = (name) => {
  const i = process.argv.indexOf(name);
  return i > 0 ? process.argv[i + 1] : undefined;
};
if (argValue("--http")) process.env.TESCO_HTTP_PORT = argValue("--http");
const logFile = argValue("--log");
if (logFile) {
  // A log started by the old PowerShell wrapper is UTF-16; appending UTF-8 to it makes the new
  // lines unreadable in PowerShell. Keep it as server-old.log and start a fresh file.
  try {
    const head = Buffer.alloc(2);
    const fd = openSync(logFile, "r");
    readSync(fd, head, 0, 2, 0);
    closeSync(fd);
    if (head[0] === 0xff && head[1] === 0xfe) renameSync(logFile, logFile.replace(/(\.log)?$/i, "-old.log"));
  } catch {
    // no log yet
  }
  const out = createWriteStream(logFile, { flags: "a" });
  const write = (...a) => out.write(formatLog(...a) + "\n");
  console.log = console.info = console.warn = console.error = write;
  process.on("uncaughtException", (err) => {
    write(new Date().toISOString(), "[fatal]", err?.stack ?? err);
    out.end(() => process.exit(1)); // Windows restarts the helper
  });
  process.on("unhandledRejection", (err) => write(new Date().toISOString(), "[unhandled]", err?.stack ?? err));
}

// Auth mode. "browser" (computer with Chrome): refresh by briefly opening Chrome.
// "cookies" (Android/Termux, or anywhere without Chrome): no browser; when the
// session expires the user re-imports cookies with import-cookies.mjs.
const AUTH_MODE = process.env.TESCO_AUTH === "cookies" || process.env.TESCO_AUTH === "http" ? "cookies" : "browser";
const RELOGIN_HINT =
  AUTH_MODE === "cookies"
    ? "Ask the user to open www.tesco.com in their phone browser (signed in), export the cookies, and run " +
      "`npm run import-cookies -- <file>` (or `termux-clipboard-get | npm run import-cookies -- -`) in the tesco-assistant folder, then try again."
    : process.env.TESCO_HTTP_PORT
      ? "The Tesco sign-in on the user's Windows laptop has expired. Ask the user to " +
        "double-click 'Tesco sign-in' on the laptop's desktop and sign in in the Chrome window that opens, then try again."
      : "Ask the user to run `npm run login` in the tesco-assistant folder, sign in in the Chrome window, then try again.";

const PRODUCT_BATCH = 15; // Tesco's max products per lookup request
const HISTORY_PAGE = 25;

// --- helpers ---------------------------------------------------------------

function json(value) {
  const text = JSON.stringify(value, (key, v) => (key === "raw" ? undefined : v), 2);
  return { content: [{ type: "text", text }] };
}

function errorResult(err) {
  const kind = err instanceof BasketeerError ? err.name : "Error";
  let message = err instanceof Error ? err.message : String(err);
  if (err instanceof AuthExpiredError || /unauthori[sz]ed|not signed in|refresh|\b401\b/i.test(message)) {
    message += ` — The Tesco session is missing or expired. ${RELOGIN_HINT}`;
  }
  return { isError: true, content: [{ type: "text", text: JSON.stringify({ error: kind, message }, null, 2) }] };
}

// Tesco calls run one at a time: a token refresh opens Chrome on a shared
// profile, and two refreshes at once would collide.
let queue = Promise.resolve();
function serial(fn) {
  const p = queue.then(fn, fn);
  queue = p.catch(() => {});
  return p;
}

function run(fn) {
  return serial(async () => {
    try {
      return json(await fn());
    } catch (err) {
      return errorResult(err);
    }
  });
}

/** Compact search/favourite result for the model. */
function slimProduct(p) {
  return {
    sku: p.sku,
    title: p.title,
    price: p.price?.actual ?? null,
    unitPrice: p.price?.unitPrice != null ? `${p.price.unitPrice}/${p.price.unitOfMeasure ?? ""}` : null,
    available: p.available ?? null,
    offers: (p.promotions ?? []).map((pr) => pr.description).filter(Boolean),
    ...weightInfo(p),
  };
}

/** For weighed products only: how it's sold, e.g. the weight options Tesco offers. */
function weightInfo(p) {
  const s = soldBy(p);
  if (s.kind === "each") return {};
  return { soldBy: soldByText(s), ...(s.kind === "weight-options" ? { weightOptions: s.options } : {}) };
}

/** Look up full product details (incl. weight rules) for several SKUs, 15 per request. */
async function productMap(client, skus) {
  const map = new Map();
  const unique = [...new Set(skus.filter(Boolean).map(String))];
  for (let i = 0; i < unique.length; i += PRODUCT_BATCH) {
    for (const p of await client.getProducts(unique.slice(i, i + PRODUCT_BATCH))) map.set(p.sku, p);
  }
  return map;
}

const NEEDS_PROBE =
  "This product is sold by weight, and the helper hasn't yet learned how tesco.com adds weighed items. " +
  "Ask the user to run the update command on the laptop (it records this once, automatically).";

/** "500g", "0.5kg", "1.2 kg" in free text -> kg, else undefined. */
function weightFromText(text) {
  const m = /(\d+(?:\.\d+)?)\s*(kg|kilo(?:gram)?s?|g|grams?)\b/i.exec(String(text ?? ""));
  if (!m) return undefined;
  const v = Number(m[1]);
  return /^k/i.test(m[2]) ? v : v / 1000;
}

// Recent orders, cached for 15 minutes (used to find the usual weight of a product).
let historyCache = { at: 0, orders: null };
async function recentOrders(client) {
  if (!historyCache.orders || Date.now() - historyCache.at > 15 * 60 * 1000) {
    historyCache = { at: Date.now(), orders: await fetchHistory(client, 20) };
  }
  return historyCache.orders;
}

/** The weight (kg) this user usually buys of a product, from recent orders. */
async function usualWeight(client, sku) {
  const row = summarisePurchases(await recentOrders(client)).find((u) => u.sku === sku);
  return row?.typicalWeightKg ?? null;
}

/**
 * Add or set one basket line, handling weighed products:
 *  - weight options (e.g. pork fillet): picks Tesco's option nearest weight_kg (or the default)
 *  - loose by kg: uses the recorded by-kg format, else adds the nearest number of pieces
 *  - everything else: an ordinary "N pieces" line
 */
async function addLine(client, product, { sku, quantity = 1, weight_kg, by, name, mode = "add" }) {
  const s = soldBy(product);
  // A chat may have an old copy of the tool list without weight_kg: accept "500g"/"0.5kg" in the name too.
  let weightKg = weight_kg ?? weightFromText(name);
  if (s.kind === "weight-options") {
    const fmt = (await loadFormat())?.weightOptions;
    if (!fmt?.weightKey) throw new Error(NEEDS_PROBE);
    let chosenBy = weightKg ? "requested" : "default";
    if (!weightKg) {
      // No weight asked for: use the weight this user usually buys, not Tesco's default.
      const usual = await usualWeight(client, sku).catch(() => null);
      if (usual) [weightKg, chosenBy] = [usual, "your usual weight"];
    }
    const opt = nearestOption(s.options, weightKg);
    let qty = quantity;
    if (mode === "add") qty += (await client.basket.get()).items.find((l) => l.sku === sku)?.quantity ?? 0;
    const r = await sendWeighted(client, fmt, { sku, quantity: qty, weightKg: opt.weightKg });
    const notes = [`weight: ${opt.weightKg}kg (${chosenBy === "default" ? "Tesco's default" : chosenBy}), £${Number(opt.price).toFixed(2)} each`];
    if (weightKg && Math.abs(opt.weightKg - weightKg) > 0.001) notes.push(`${weightKg}kg isn't offered; nearest option used`);
    return {
      quantityNow: r.line?.quantity ?? qty,
      weightKg: opt.weightKg,
      price: opt.price,
      note: notes.join("; "),
      otherWeights: s.options.map((o) => `${o.weightKg}kg £${Number(o.price).toFixed(2)}`).join(", "),
    };
  }
  if (s.kind === "loose" && (by === "kg" || weightKg)) {
    const kg = weightKg ?? quantity * (s.averageWeightKg ?? 0);
    const fmt = (await loadFormat())?.loose;
    if (fmt) {
      const step = s.stepKg || 0.05;
      const want = Math.max(s.minKg || step, Math.round(kg / step) * step);
      const r = await sendWeighted(client, fmt, { sku, quantity: 1, weightKg: want });
      return { weightKg: Math.round(want * 1000) / 1000, quantityNow: r.line?.quantity ?? null, note: "set to this total weight" };
    }
    // Not learned yet: buy the nearest number of pieces instead.
    const pieces = Math.max(1, Math.round(kg / (s.averageWeightKg || kg || 1)));
    const basket = await client.basket.set(sku, pieces);
    return {
      quantityNow: basket.items.find((l) => l.sku === sku)?.quantity ?? pieces,
      note: `added ${pieces} piece(s), about ${Math.round(pieces * s.averageWeightKg * 1000) / 1000}kg (buying by kg isn't set up yet)`,
    };
  }
  if (mode === "add" && quantity === 0) throw new Error('quantity 0 only makes sense with mode "set"');
  const basket = mode === "add" ? await client.basket.add(sku, quantity) : await client.basket.set(sku, quantity);
  const line = basket.items.find((l) => l.sku === sku);
  return { quantityNow: line?.quantity ?? 0, available: line?.available ?? null };
}

function orderDate(o) {
  return o.slot?.start ?? null;
}

function slimOrder(o, includeItems) {
  const out = {
    orderNo: o.orderNo,
    date: orderDate(o),
    status: o.status,
    totalPrice: o.totalPrice,
    totalItems: o.totalItems,
    shoppingMethod: o.shoppingMethod,
  };
  if (includeItems) {
    out.items = o.items.map((i) => ({ sku: i.productId, title: i.title, quantity: i.quantity, unit: i.unit }));
  }
  return out;
}

/** Fetch up to `max` completed orders, newest first, deduped by id. */
async function fetchHistory(client, max) {
  const seen = new Map();
  let offset = 0;
  while (seen.size < max) {
    const limit = Math.min(HISTORY_PAGE, max - seen.size);
    const page = await client.orders.history({ offset, limit });
    for (const o of page.orders) if (!seen.has(o.id)) seen.set(o.id, o);
    if (page.nextOffset === null) break;
    offset = page.nextOffset;
  }
  return [...seen.values()].slice(0, max);
}

function median(nums) {
  const s = [...nums].sort((a, b) => a - b);
  const m = Math.floor(s.length / 2);
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}

/** Aggregate order items into "what I usually buy". */
function summarisePurchases(orders, filter) {
  const needle = filter?.toLowerCase();
  const bySku = new Map();
  for (const o of orders) {
    const date = orderDate(o);
    for (const it of o.items) {
      const key = it.productId ?? it.title;
      if (needle && !it.title.toLowerCase().includes(needle)) continue;
      let row = bySku.get(key);
      if (!row) {
        row = { sku: it.productId, title: it.title, unit: it.unit, orders: new Set(), qtys: [], weights: [], lastBought: null };
        bySku.set(key, row);
      }
      row.orders.add(o.id);
      row.qtys.push(it.quantity);
      if (it.weight > 0) row.weights.push(it.weight);
      if (date && (!row.lastBought || date > row.lastBought)) row.lastBought = date;
    }
  }
  return [...bySku.values()]
    .map((r) => ({
      sku: r.sku,
      title: r.title,
      timesBought: r.orders.size,
      boughtInPercentOfOrders: Math.round((100 * r.orders.size) / Math.max(orders.length, 1)),
      typicalQuantity: median(r.qtys),
      ...(r.weights.length ? { typicalWeightKg: Math.round(median(r.weights) * 1000) / 1000 } : {}),
      unit: r.unit,
      lastBought: r.lastBought,
    }))
    .sort((a, b) => b.timesBought - a.timesBought || (b.lastBought ?? "").localeCompare(a.lastBought ?? ""));
}

/** Basket lines carry no titles; look them up so the model can read the basket back. */
async function basketWithTitles(client) {
  const basket = await client.basket.get();
  const skus = basket.items.map((l) => l.sku).filter(Boolean);
  const titles = new Map();
  for (let i = 0; i < skus.length; i += PRODUCT_BATCH) {
    try {
      for (const p of await client.getProducts(skus.slice(i, i + PRODUCT_BATCH))) titles.set(p.sku, p);
    } catch {
      // Titles are a convenience; never fail the basket read because of them.
    }
  }
  return {
    guidePrice: basket.guidePrice,
    isAmendingAnOrder: basket.isInAmend,
    itemCount: basket.items.length,
    items: basket.items.map((l) => {
      const p = titles.get(l.sku);
      return {
        sku: l.sku,
        title: p?.title ?? null,
        quantity: l.quantity,
        unit: l.unit,
        lineCost: l.cost,
        available: l.available,
      };
    }),
  };
}

// --- server ----------------------------------------------------------------

const INSTRUCTIONS = `
You are connected to the user's own Tesco UK grocery account (tesco.com). Prices are in GBP.

How to help with "I need X" requests:
1. First check what they normally buy: call tesco_usual_purchases (optionally with a filter word,
   e.g. "milk") so you pick the exact product, size and quantity they usually choose.
2. If nothing in their history fits, use tesco_search and choose a sensible match; say which
   product you chose and why (price, size, offer).
3. Before adding, briefly confirm the list with the user if it is more than a few items or you
   had to guess between products. Then add everything in one tesco_basket_add_items call.
4. Report back what was added, anything unavailable or rejected, and the basket guide price.

Rules:
- You cannot check out, pay, book delivery slots or cancel orders. Tell the user to review the
  basket and pay on tesco.com (or the Tesco app) themselves.
- Use mode "add" to add on top of what is already in the basket; use "set" only when the user
  wants an exact quantity. Quantity 0 with mode "set" removes an item.
- Call tesco_basket_get before large changes so you do not duplicate items already in the basket.
- Weighed products show soldBy/weightOptions. Pass weight_kg (the user's typicalWeightKg if known) and
  say which weight was chosen and its price.
- If a tool reports an expired session, pass on the sign-in instructions in the error message.
`.trim();

function buildServer(client) {
  const server = new McpServer({ name: "tesco-assistant", version: "0.1.0" }, { instructions: INSTRUCTIONS });

  server.tool(
    "tesco_order_history",
    "List the user's past (completed) Tesco orders, newest first: order number, delivery date, total and item count. " +
      "Set include_items to also get each order's products.",
    {
      max_orders: z.number().int().min(1).max(100).default(10).describe("How many past orders to return."),
      include_items: z.boolean().default(false).describe("Include each order's line items."),
    },
    { readOnlyHint: true },
    ({ max_orders, include_items }) =>
      run(async () => (await fetchHistory(client, max_orders)).map((o) => slimOrder(o, include_items))),
  );

  server.tool(
    "tesco_order_details",
    "Get every item in one past order, by order number (from tesco_order_history).",
    {
      order_no: z.string().describe("Order number."),
      search_last: z.number().int().min(1).max(100).default(50).describe("How many recent orders to search."),
    },
    { readOnlyHint: true },
    ({ order_no, search_last }) =>
      run(async () => {
        const order = (await fetchHistory(client, search_last)).find((o) => o.orderNo === order_no);
        if (!order) throw new Error(`Order ${order_no} not found in the last ${search_last} orders.`);
        return slimOrder(order, true);
      }),
  );

  server.tool(
    "tesco_upcoming_orders",
    "List upcoming (not yet delivered) orders with their items and amend deadline.",
    {},
    { readOnlyHint: true },
    () =>
      run(async () =>
        (await client.orders.list()).map((o) => ({ ...slimOrder(o, true), amendBefore: o.amendExpiry })),
      ),
  );

  server.tool(
    "tesco_usual_purchases",
    "Analyse recent past orders and list the products the user buys, most frequent first, with how often, " +
      "their typical quantity and when they last bought it. Use this to pick the user's preferred product for a need.",
    {
      max_orders: z.number().int().min(1).max(100).default(20).describe("How many recent orders to analyse."),
      filter: z.string().optional().describe('Only products whose name contains this text, e.g. "milk".'),
      limit: z.number().int().min(1).max(500).default(100).describe("Max products to return."),
    },
    { readOnlyHint: true },
    ({ max_orders, filter, limit }) =>
      run(async () => {
        const orders = await fetchHistory(client, max_orders);
        return {
          ordersAnalysed: orders.length,
          from: orders.at(-1) ? orderDate(orders.at(-1)) : null,
          to: orders[0] ? orderDate(orders[0]) : null,
          products: summarisePurchases(orders, filter).slice(0, limit),
        };
      }),
  );

  server.tool(
    "tesco_favourites",
    "List the user's Tesco favourites ('my usuals') saved on their account.",
    { limit: z.number().int().min(1).max(100).default(48) },
    { readOnlyHint: true },
    ({ limit }) => run(async () => (await client.favourites({ limit })).results.map(slimProduct)),
  );

  server.tool(
    "tesco_search",
    "Search the Tesco catalogue. Returns SKU, title, price, unit price, availability and offers.",
    {
      query: z.string().min(1).describe('What to search for, e.g. "semi skimmed milk 4 pints".'),
      limit: z.number().int().min(1).max(48).default(10),
    },
    { readOnlyHint: true },
    ({ query, limit }) => run(async () => (await client.search(query, { limit })).results.map(slimProduct)),
  );

  server.tool(
    "tesco_basket_get",
    "Show the current basket: items (with titles), quantities, line costs and guide price.",
    {},
    { readOnlyHint: true },
    () => run(() => basketWithTitles(client)),
  );

  server.tool(
    "tesco_basket_add_items",
    'Add one or more products to the basket by SKU. mode "add" (default) adds on top of any quantity already ' +
      'in the basket; mode "set" sets the exact quantity (0 removes). Weighed products: for items with ' +
      "weightOptions (e.g. pork fillet) give weight_kg to pick the nearest option (default option otherwise; " +
      "use typicalWeightKg from tesco_usual_purchases); for loose items give by:\"kg\" with weight_kg to buy by " +
      "weight, otherwise they're bought by the piece. Items are processed one by one; the result reports each item's outcome.",
    {
      items: z
        .array(
          z.object({
            sku: z.string().describe("Product SKU from tesco_search / tesco_usual_purchases."),
            quantity: z.number().int().min(0).max(50).default(1),
            weight_kg: z.number().positive().max(10).optional().describe("Weighed products only: wanted weight in kg, e.g. 0.5."),
            name: z.string().optional().describe('Optional product name or note; a weight in it such as "500g" is used if weight_kg is not given.'),
            by: z.enum(["each", "kg"]).optional().describe('Loose products only: "kg" to buy by weight.'),
          }),
        )
        .min(1)
        .max(60),
      mode: z.enum(["add", "set"]).default("add"),
    },
    { readOnlyHint: false, destructiveHint: false },
    ({ items, mode }) =>
      run(async () => {
        const results = [];
        const products = await productMap(client, items.map((i) => i.sku)).catch(() => new Map());
        for (const it of items) {
          try {
            const r = await addLine(client, products.get(it.sku), { ...it, mode });
            results.push({ sku: it.sku, ok: true, title: products.get(it.sku)?.title, ...r });
          } catch (err) {
            results.push({ sku: it.sku, ok: false, error: err instanceof Error ? err.message : String(err) });
            if (err instanceof AuthExpiredError) break;
          }
        }
        const basket = await client.basket.get().catch(() => null);
        return {
          results,
          basketItemCount: basket?.items.length ?? null,
          guidePrice: basket?.guidePrice ?? null,
          note: "Nothing has been ordered or paid. The user reviews the basket and checks out on tesco.com.",
        };
      }),
  );

  server.tool(
    "tesco_basket_remove",
    "Remove a product from the basket entirely.",
    { sku: z.string() },
    { readOnlyHint: false, destructiveHint: true },
    ({ sku }) =>
      run(async () => {
        const basket = await client.basket.remove(sku);
        return { removed: sku, basketItemCount: basket.items.length, guidePrice: basket.guidePrice };
      }),
  );

  return server;
}


// --- background sync for the Cloudflare Worker -----------------------------

async function readJson(req) {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  return chunks.length ? JSON.parse(Buffer.concat(chunks).toString("utf8")) : {};
}

function clubcardPrice(promotions) {
  for (const p of promotions ?? []) {
    // "£7.50 Clubcard Price" or "£7.50 Save 25% Clubcard Price" (multibuys like "Any 3 for £10" are skipped)
    const m = /^£(\d+(?:\.\d{1,2})?)\b.*Clubcard Price/i.exec((p.description ?? "").trim());
    if (m) return Number(m[1]);
  }
  return null;
}

/**
 * Current prices and offers for the user's regular products (from recent
 * orders) and favourites, plus how often each is bought. The Worker stores
 * this as the "saved price list" used while the laptop is off.
 */
async function snapshot(client) {
  const orders = await fetchHistory(client, 20);
  const usual = summarisePurchases(orders).filter((u) => u.sku).slice(0, 150);
  let favourites = [];
  try {
    favourites = (await client.favourites({ limit: 48 })).results;
  } catch {
    // Favourites are a bonus; carry on without them.
  }
  const stats = new Map(usual.map((u) => [u.sku, u]));
  const favSkus = new Set(favourites.map((f) => f.sku));
  const skus = [...new Set([...usual.map((u) => u.sku), ...favSkus])];
  const products = [];
  for (let i = 0; i < skus.length; i += PRODUCT_BATCH) {
    for (const p of await client.getProducts(skus.slice(i, i + PRODUCT_BATCH))) {
      const u = stats.get(p.sku);
      products.push({
        sku: p.sku,
        title: p.title,
        price: p.price?.actual ?? null,
        unitPrice: p.price?.unitPrice != null ? `${p.price.unitPrice}/${p.price.unitOfMeasure ?? ""}` : null,
        clubcardPrice: clubcardPrice(p.promotions),
        offers: (p.promotions ?? []).map((pr) => ({ text: pr.description, ends: pr.endDate })).filter((o) => o.text),
        available: p.available ?? null,
        timesBought: u?.timesBought ?? 0,
        typicalQuantity: u?.typicalQuantity ?? null,
        lastBought: u?.lastBought ?? null,
        favourite: favSkus.has(p.sku),
      });
    }
  }
  return { generatedAt: new Date().toISOString(), ordersAnalysed: orders.length, products };
}

/** Add items that were saved while the laptop was off. Items without a SKU are found by name. */
async function applySaved(client, items) {
  const results = [];
  for (const it of items) {
    try {
      let sku = it.sku;
      let title = it.name ?? null;
      if (!sku) {
        const hits = (await client.search(it.name, { limit: 5 })).results;
        const pick = hits.find((h) => h.available !== false) ?? hits[0];
        if (!pick) throw new Error(`No Tesco product found for "${it.name}"`);
        sku = pick.sku;
        title = pick.title;
      }
      const qty = Number(it.quantity ?? 1);
      const product = (await productMap(client, [sku]).catch(() => new Map())).get(sku);
      const r = await addLine(client, product, {
        sku,
        quantity: it.mode === "set" ? qty : Math.max(1, qty),
        weight_kg: it.weight_kg ?? undefined,
        by: it.by ?? undefined,
        name: it.name ?? undefined,
        mode: it.mode === "set" ? "set" : "add",
      });
      results.push({ id: it.id, ok: true, sku, title: product?.title ?? title, ...r });
    } catch (err) {
      results.push({ id: it.id, ok: false, error: err instanceof Error ? err.message : String(err) });
      if (err instanceof AuthExpiredError) break;
    }
  }
  return { results };
}

// --- start -----------------------------------------------------------------

const store = new FileTokenStore(); // ~/.basketeer/session.json
const authBackend =
  AUTH_MODE === "cookies"
    ? basketeer.HttpAuthBackend
      ? new basketeer.HttpAuthBackend() // best effort; Tesco UK usually refuses this, then the user re-imports cookies
      : undefined // older basketeer: no refresh, the user re-imports cookies when the session expires
    : new (await import("./tesco-auth.mjs")).TescoBrowserAuth(); // renews via Chrome, keeping the full cookie jar
const client = new Basketeer({ session: await store.load(), store, authBackend });
const httpPort = Number(argValue("--http") || process.env.TESCO_HTTP_PORT || 0);

/**
 * Keep-alive (laptop mode): renew the hourly Tesco token ~15 minutes before it
 * expires, so Tesco never sees an expired sign-in while the laptop is on. After a
 * failure, wait 30 minutes before trying again (no Chrome window storm).
 * Off unless TESCO_KEEPALIVE=1 (it opens a Chrome window about every 45 minutes);
 * without it, renewal happens only when Claude next uses Tesco.
 */
if (httpPort && AUTH_MODE === "browser" && process.env.TESCO_KEEPALIVE === "1") {
  let lastFailure = 0;
  const keepAlive = () =>
    serial(async () => {
      const s = await store.load().catch(() => null);
      if (!s?.accessTokenExpiry) return;
      const left = s.accessTokenExpiry - Date.now();
      if (left > 15 * 60 * 1000 || Date.now() - lastFailure < 30 * 60 * 1000) return;
      try {
        await store.save(await authBackend.refresh(s)); // basketeer adopts the newer stored session on its next 401
        console.log(new Date().toISOString(), `[keep-alive] renewed the Tesco sign-in (${Math.round(left / 60000)} min were left)`);
      } catch (err) {
        lastFailure = Date.now();
        console.log(new Date().toISOString(), `[keep-alive] renewal failed: ${err instanceof Error ? err.message : err}`);
      }
    });
  setInterval(keepAlive, 5 * 60 * 1000);
  setTimeout(keepAlive, 60 * 1000);
}
if (!httpPort) {
  await buildServer(client).connect(new StdioServerTransport());
} else {
  // Remote mode: Streamable HTTP on localhost only. Reached from Claude through
  // a Cloudflare Worker + Workers VPC + Cloudflare Tunnel (see cloudflare/).
  const { createServer } = await import("node:http");
  const { StreamableHTTPServerTransport } = await import("@modelcontextprotocol/sdk/server/streamableHttp.js");
  createServer(async (req, res) => {
    const path = (req.url ?? "/").split("?")[0];
    if (path === "/health") {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ ok: true, signedIn: Boolean(client.session) }));
      return;
    }
    if (req.method === "POST" && (path === "/internal/snapshot" || path === "/internal/apply")) {
      // Called only by the Cloudflare Worker (through the private tunnel).
      try {
        const body = path === "/internal/apply" ? await readJson(req) : {};
        const out = await serial(() => (path === "/internal/snapshot" ? snapshot(client) : applySaved(client, body.items ?? [])));
        res.writeHead(200, { "content-type": "application/json" });
        res.end(JSON.stringify(out));
      } catch (err) {
        res.writeHead(500, { "content-type": "application/json" });
        res.end(JSON.stringify({ error: err instanceof Error ? err.message : String(err) }));
      }
      return;
    }
    if (path !== "/mcp") {
      res.writeHead(404).end();
      return;
    }
    if (req.method !== "POST") {
      res.writeHead(405, { allow: "POST" }).end();
      return;
    }
    // Stateless: a fresh MCP server + transport per request, sharing one Tesco client.
    const server = buildServer(client);
    const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
    res.on("close", () => {
      transport.close();
      server.close();
    });
    try {
      await server.connect(transport);
      await transport.handleRequest(req, res);
    } catch (err) {
      console.error(err);
      if (!res.headersSent) res.writeHead(500).end();
    }
  }).listen(httpPort, "127.0.0.1", () => console.log(`tesco-assistant listening on http://127.0.0.1:${httpPort}/mcp`));
}
