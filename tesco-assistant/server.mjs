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
  };
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
        row = { sku: it.productId, title: it.title, unit: it.unit, orders: new Set(), qtys: [], lastBought: null };
        bySku.set(key, row);
      }
      row.orders.add(o.id);
      row.qtys.push(it.quantity);
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
      'in the basket; mode "set" sets the exact quantity (0 removes). Items are processed one by one; ' +
      "the result reports each item's outcome and the new basket total.",
    {
      items: z
        .array(
          z.object({
            sku: z.string().describe("Product SKU from tesco_search / tesco_usual_purchases."),
            quantity: z.number().int().min(0).max(50).default(1),
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
        let basket = null;
        for (const { sku, quantity } of items) {
          try {
            if (mode === "add") {
              if (quantity === 0) {
                results.push({ sku, ok: false, error: 'quantity 0 only makes sense with mode "set"' });
                continue;
              }
              basket = await client.basket.add(sku, quantity);
            } else {
              basket = await client.basket.set(sku, quantity);
            }
            const line = basket.items.find((l) => l.sku === sku);
            results.push({ sku, ok: true, quantityNow: line?.quantity ?? 0, available: line?.available ?? null });
          } catch (err) {
            results.push({ sku, ok: false, error: err instanceof Error ? err.message : String(err) });
            if (err instanceof AuthExpiredError) break;
          }
        }
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
      const basket = it.mode === "set" ? await client.basket.set(sku, qty) : await client.basket.add(sku, Math.max(1, qty));
      const line = basket.items.find((l) => l.sku === sku);
      results.push({ id: it.id, ok: true, sku, title, quantityNow: line?.quantity ?? 0, available: line?.available ?? null });
    } catch (err) {
      results.push({ id: it.id, ok: false, error: err instanceof Error ? err.message : String(err) });
      if (err instanceof AuthExpiredError) break;
    }
  }
  return { results };
}

// --- start -----------------------------------------------------------------

const store = new FileTokenStore(); // ~/.basketeer/session.json
const client = new Basketeer({
  session: await store.load(),
  store,
  authBackend:
    AUTH_MODE === "cookies"
      ? basketeer.HttpAuthBackend
        ? new basketeer.HttpAuthBackend() // best effort; Tesco UK usually refuses this, then the user re-imports cookies
        : undefined // older basketeer: no refresh, the user re-imports cookies when the session expires
      : new (await import("./tesco-auth.mjs")).TescoBrowserAuth(), // renews via Chrome, keeping the full cookie jar
});
const httpPort = Number(process.env.TESCO_HTTP_PORT || 0);
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
