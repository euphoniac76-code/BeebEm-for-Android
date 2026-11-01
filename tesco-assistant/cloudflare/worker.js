// Cloudflare Worker: the Tesco connector Claude talks to.
//
//   https://<worker>.workers.dev/<SECRET>/mcp   (MCP, Streamable HTTP, stateless JSON)
//
// Laptop on  -> live Tesco tools are forwarded through Workers VPC + Cloudflare
//               Tunnel to tesco-assistant on the laptop (127.0.0.1:8787).
// Laptop off -> searches use Apify (free plan, if APIFY_TOKEN is set), then the
//               saved price list in D1; basket additions are saved for later.
// Every 10 minutes (cron) -> if the laptop is reachable, saved items are added
//               to the basket and, every 12 hours, the saved price list is refreshed.
//
// Bindings: LAPTOP (vpc_service), DB (d1), SECRET (secret_text),
//           APIFY_TOKEN (secret_text, optional), APIFY_ACTOR (plain_text, optional).

const VERSION = "0.3.0";
const LAPTOP_URL = "http://127.0.0.1:8787";
const SNAPSHOT_EVERY_MS = 12 * 60 * 60 * 1000;
const DEFAULT_ACTOR = "zaiq~tesco-scraper";
const PROTOCOLS = ["2025-06-18", "2025-03-26", "2024-11-05"];

const INSTRUCTIONS = `
You are connected to the user's own Tesco UK grocery account (tesco.com). Prices are in GBP.
The account is reached through a helper on the user's Windows laptop. When the laptop is off,
the same tools still work in a reduced way: searches use a live price service or a saved price
list, and items you add are SAVED FOR LATER and go into the real basket automatically the next
time the laptop is on. Every tool result says which of these happened — tell the user plainly.

How to help with "I need X":
1. Call tesco_usual_purchases (optionally with a filter word such as "milk") to find the exact
   product and quantity the user normally buys.
2. If it's not in their history, use tesco_search and pick a sensible match. Say which product and why.
3. Confirm the list with the user first if it's more than a few items or you had to guess.
   Then add everything with one tesco_basket_add_items call.
4. Report what was added (or saved for later), anything unavailable, and the guide price.

Rules:
- You cannot check out, pay, book delivery slots or cancel orders. The user checks out in the
  Tesco app. If items were only saved for later, remind them to switch the laptop on before checkout.
- Mention Clubcard Prices and offers when they apply, and say how old a saved price is.
- Personal Clubcard coupons are not visible to these tools.
- Past orders are also in the user's Gmail as Tesco receipt emails, if order history is unavailable.
- If a tool says the Tesco sign-in has expired, pass on its instructions.
`.trim();

// --- tool definitions --------------------------------------------------------

const int = (min, max, def, description) => ({ type: "integer", minimum: min, maximum: max, default: def, description });
const str = (description) => ({ type: "string", description });
const obj = (properties, required = []) => ({ type: "object", properties, required });
const RO = { readOnlyHint: true };

const TOOLS = [
  { name: "tesco_order_history", description: "List the user's past (completed) Tesco orders, newest first: order number, date, total and item count. Set include_items to get each order's products. Needs the laptop.", inputSchema: obj({ max_orders: int(1, 100, 10, "How many past orders to return."), include_items: { type: "boolean", default: false } }), annotations: RO },
  { name: "tesco_order_details", description: "Every item in one past order, by order number. Needs the laptop.", inputSchema: obj({ order_no: str("Order number."), search_last: int(1, 100, 50, "How many recent orders to search.") }, ["order_no"]), annotations: RO },
  { name: "tesco_upcoming_orders", description: "Upcoming (not yet delivered) orders with items and amend deadline. Needs the laptop.", inputSchema: obj({}), annotations: RO },
  { name: "tesco_usual_purchases", description: "Products the user buys most often, with how often, typical quantity and last bought date. Use it to pick the user's preferred product for a need. Uses the saved list when the laptop is off.", inputSchema: obj({ max_orders: int(1, 100, 20, "Recent orders to analyse (live only)."), filter: str('Only products whose name contains this text, e.g. "milk".'), limit: int(1, 500, 100, "Max products to return.") }), annotations: RO },
  { name: "tesco_favourites", description: "The user's Tesco favourites. Uses the saved list when the laptop is off.", inputSchema: obj({ limit: int(1, 100, 48) }), annotations: RO },
  { name: "tesco_search", description: "Search Tesco products: SKU, title, price, unit price, Clubcard Price and offers, availability. Live when the laptop is on; otherwise a live price service or the saved price list (the result says which, and how fresh).", inputSchema: obj({ query: str('What to search for, e.g. "semi skimmed milk 4 pints".'), limit: int(1, 48, 10) }, ["query"]), annotations: RO },
  { name: "tesco_basket_get", description: "The current basket (items, quantities, guide price), plus anything saved for later.", inputSchema: obj({}), annotations: RO },
  { name: "tesco_basket_add_items", description: 'Add products to the basket. Give each item a sku (from search/usual purchases) or, if unknown, a name to search for. mode "add" (default) adds on top of what is there; "set" sets the exact quantity (0 removes). If the laptop is off, items are saved and added automatically when it is next on.', inputSchema: obj({ items: { type: "array", minItems: 1, maxItems: 60, items: obj({ sku: str("Product SKU, if known."), name: str("Product name, if the SKU is not known."), quantity: int(0, 50, 1) }) }, mode: { type: "string", enum: ["add", "set"], default: "add" } }, ["items"]), annotations: { readOnlyHint: false, destructiveHint: false } },
  { name: "tesco_basket_remove", description: "Remove a product from the basket (or from the saved-for-later list when the laptop is off).", inputSchema: obj({ sku: str("Product SKU.") }, ["sku"]), annotations: { readOnlyHint: false, destructiveHint: true } },
  { name: "tesco_saved_prices", description: "Look up the saved price list (the user's regular products and recent searches) with prices, Clubcard Prices, offers and when each was checked. Works whether or not the laptop is on.", inputSchema: obj({ filter: str("Text to match in product names; omit for all."), limit: int(1, 300, 50) }), annotations: RO },
  { name: "tesco_saved_for_later", description: "Items saved for later (waiting for the laptop), and recently applied ones with their outcome.", inputSchema: obj({}), annotations: RO },
  { name: "tesco_saved_for_later_remove", description: "Delete a waiting saved-for-later item by its id.", inputSchema: obj({ id: { type: "integer" } }, ["id"]), annotations: { readOnlyHint: false, destructiveHint: true } },
  { name: "tesco_status", description: "Is the laptop reachable, how fresh is the saved price list, how many items are waiting, and is the live price service set up.", inputSchema: obj({}), annotations: RO },
];
const LIVE = new Set(TOOLS.slice(0, 9).map((t) => t.name));

// --- entry points -------------------------------------------------------------

export default {
  async fetch(request, rawEnv, ctx) {
    const env = safeEnv(rawEnv);
    const url = new URL(request.url);
    if (!env.SECRET || url.pathname !== `/${env.SECRET}/mcp`) return new Response("Not found", { status: 404 });
    if (request.method !== "POST") return new Response("Method not allowed", { status: 405, headers: { allow: "POST" } });
    await ensureSchema(env);

    let body;
    try {
      body = await request.json();
    } catch {
      return Response.json(rpcError(null, -32700, "Parse error"), { status: 400 });
    }
    const msgs = Array.isArray(body) ? body : [body];
    const replies = [];
    for (const m of msgs) {
      const r = await handle(m, env, ctx);
      if (r) replies.push(r);
    }
    if (!replies.length) return new Response(null, { status: 202 });
    return Response.json(Array.isArray(body) ? replies : replies[0]);
  },

  async scheduled(_event, rawEnv, ctx) {
    const env = safeEnv(rawEnv);
    await ensureSchema(env);
    if (!(await laptopUp(env))) return;
    await flushSaved(env);
    const last = await meta(env, "snapshot_at");
    if (!last || Date.now() - Date.parse(last) > SNAPSHOT_EVERY_MS) await takeSnapshot(env);
  },
};

async function handle(msg, env, ctx) {
  const { id, method, params } = msg ?? {};
  if (id === undefined || id === null) return null; // notification
  try {
    switch (method) {
      case "initialize": {
        const asked = params?.protocolVersion;
        return rpcResult(id, {
          protocolVersion: PROTOCOLS.includes(asked) ? asked : PROTOCOLS[0],
          capabilities: { tools: { listChanged: false } },
          serverInfo: { name: "tesco", version: VERSION },
          instructions: INSTRUCTIONS,
        });
      }
      case "ping":
        return rpcResult(id, {});
      case "tools/list":
        return rpcResult(id, { tools: TOOLS });
      case "tools/call":
        return rpcResult(id, await callTool(params?.name, params?.arguments ?? {}, env, ctx));
      default:
        return rpcError(id, -32601, `Method not found: ${method}`);
    }
  } catch (err) {
    return rpcResult(id, toolError(err instanceof Error ? err.message : String(err)));
  }
}

// --- tool dispatch ------------------------------------------------------------

async function callTool(name, args, env, ctx) {
  if (!TOOLS.some((t) => t.name === name)) return toolError(`Unknown tool: ${name}`);

  switch (name) {
    case "tesco_saved_prices":
      return text(await savedPrices(env, args.filter, args.limit ?? 50));
    case "tesco_saved_for_later":
      return text(await savedForLater(env));
    case "tesco_saved_for_later_remove": {
      const r = await env.DB.prepare("DELETE FROM saved_items WHERE id = ? AND status = 'pending'").bind(args.id).run();
      return text({ removed: r.meta.changes > 0, id: args.id });
    }
    case "tesco_status":
      return text(await status(env));
  }

  const up = await laptopUp(env);
  if (up && LIVE.has(name)) {
    // Anything saved while the laptop was off goes in first, so the basket is complete.
    const flushed = await flushSaved(env).catch(() => null);
    if (name === "tesco_basket_add_items" && args.items?.some((i) => !i.sku)) {
      const saved = await saveForLater(env, args.items, args.mode);
      const applied = await flushSaved(env).catch(() => null);
      if (applied) return text({ source: "laptop (live)", ...applied, note: "Nothing has been ordered or paid." });
      return text({ savedForLater: saved, note: "The laptop didn't respond, so these are SAVED and will be added automatically when it next responds." });
    }
    try {
      const result = await forward(env, name, args);
      if (flushed?.results?.length) result.content = [...(result.content ?? []), { type: "text", text: JSON.stringify({ alsoAddedFromSavedForLater: flushed.results }) }];
      ctx.waitUntil(cacheFromLive(env, name, result));
      return result;
    } catch {
      // Fall through to the offline behaviour if the laptop dropped mid-call.
    }
  }
  return offline(name, args, env);
}

async function offline(name, args, env) {
  const offlineNote = "The laptop is off or unreachable.";
  switch (name) {
    case "tesco_search": {
      if (env.APIFY_TOKEN) {
        try {
          const results = await apifySearch(env, args.query, args.limit ?? 10);
          return text({ source: "live price service (Apify), laptop off", checkedAt: new Date().toISOString(), results });
        } catch (err) {
          const saved = await savedPrices(env, args.query, args.limit ?? 10);
          return text({ source: "saved price list (live price service failed)", error: String(err.message ?? err), ...saved });
        }
      }
      return text({ source: "saved price list (laptop off)", ...(await savedPrices(env, args.query, args.limit ?? 10)) });
    }
    case "tesco_usual_purchases": {
      const f = args.filter ? `%${args.filter}%` : "%";
      const rows = (await env.DB.prepare("SELECT * FROM prices WHERE times_bought > 0 AND title LIKE ? ORDER BY times_bought DESC, last_bought DESC LIMIT ?").bind(f, args.limit ?? 100).all()).results;
      return text({ source: "saved list (laptop off)", asOf: await meta(env, "snapshot_at"), ordersAnalysed: Number((await meta(env, "snapshot_orders")) ?? 0), products: rows.map(rowOut) });
    }
    case "tesco_favourites": {
      const rows = (await env.DB.prepare("SELECT * FROM prices WHERE is_favourite = 1 ORDER BY title LIMIT ?").bind(args.limit ?? 48).all()).results;
      return text({ source: "saved list (laptop off)", asOf: await meta(env, "snapshot_at"), favourites: rows.map(rowOut) });
    }
    case "tesco_basket_add_items": {
      const saved = await saveForLater(env, args.items, args.mode);
      return text({ savedForLater: saved, note: `${offlineNote} These items are SAVED and will be added to the Tesco basket automatically within about 10 minutes of the laptop being switched on and signed in to Windows. Nothing has been ordered or paid.` });
    }
    case "tesco_basket_get":
      return text({ basket: null, note: `${offlineNote} The real basket can't be read right now.`, ...(await savedForLater(env)) });
    case "tesco_basket_remove": {
      const r = await env.DB.prepare("DELETE FROM saved_items WHERE sku = ? AND status = 'pending'").bind(args.sku).run();
      return r.meta.changes > 0
        ? text({ removedFromSavedForLater: args.sku })
        : toolError(`${offlineNote} That item isn't in the saved-for-later list, and the real basket can't be changed until the laptop is on.`);
    }
    default:
      return toolError(`${offlineNote} Order history needs the laptop. Past orders are also in the user's Gmail as Tesco receipt emails (from no-reply@mail.tesco.com, subject "Receipt").`);
  }
}

// --- laptop -------------------------------------------------------------------

async function laptopUp(env) {
  try {
    const r = await env.LAPTOP.fetch(`${LAPTOP_URL}/health`, { signal: AbortSignal.timeout(4000) });
    return r.ok;
  } catch {
    return false;
  }
}

async function forward(env, name, args) {
  const r = await env.LAPTOP.fetch(`${LAPTOP_URL}/mcp`, {
    method: "POST",
    headers: { "content-type": "application/json", accept: "application/json, text/event-stream" },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name, arguments: args } }),
  });
  if (!r.ok) throw new Error(`laptop HTTP ${r.status}`);
  const j = await r.json();
  if (j.error) throw new Error(j.error.message);
  return j.result;
}

async function laptopPost(env, path, body) {
  const r = await env.LAPTOP.fetch(`${LAPTOP_URL}${path}`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body ?? {}) });
  const j = await r.json();
  if (!r.ok) throw new Error(j.error ?? `laptop HTTP ${r.status}`);
  return j;
}

async function flushSaved(env) {
  const pending = (await env.DB.prepare("SELECT * FROM saved_items WHERE status = 'pending' ORDER BY id").all()).results;
  if (!pending.length) return null;
  const { results } = await laptopPost(env, "/internal/apply", { items: pending.map((p) => ({ id: p.id, sku: p.sku, name: p.name, quantity: p.quantity, mode: p.mode })) });
  const now = new Date().toISOString();
  const stmts = (results ?? []).map((r) =>
    env.DB.prepare("UPDATE saved_items SET status = ?, result = ?, done_at = ? WHERE id = ?").bind(r.ok ? "added" : "failed", JSON.stringify(r), now, r.id),
  );
  if (stmts.length) await env.DB.batch(stmts);
  return { results };
}

async function takeSnapshot(env) {
  const snap = await laptopPost(env, "/internal/snapshot");
  const stmts = [env.DB.prepare("UPDATE prices SET times_bought = 0, is_favourite = 0, typical_qty = NULL")];
  for (const p of snap.products ?? []) {
    stmts.push(
      env.DB.prepare(
        `INSERT INTO prices (sku, title, price, unit_price, clubcard_price, offers, available, times_bought, typical_qty, last_bought, is_favourite, source, checked_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'tesco', ?)
         ON CONFLICT(sku) DO UPDATE SET title=excluded.title, price=excluded.price, unit_price=excluded.unit_price,
           clubcard_price=excluded.clubcard_price, offers=excluded.offers, available=excluded.available,
           times_bought=excluded.times_bought, typical_qty=excluded.typical_qty, last_bought=excluded.last_bought,
           is_favourite=excluded.is_favourite, source='tesco', checked_at=excluded.checked_at`,
      ).bind(p.sku, p.title, p.price, p.unitPrice, p.clubcardPrice, JSON.stringify(p.offers ?? []), bool(p.available), p.timesBought ?? 0, p.typicalQuantity, p.lastBought, p.favourite ? 1 : 0, snap.generatedAt),
    );
  }
  stmts.push(setMeta(env, "snapshot_at", snap.generatedAt), setMeta(env, "snapshot_orders", String(snap.ordersAnalysed ?? 0)));
  await env.DB.batch(stmts);
}

/** Keep live search results in the saved list too, so it grows with use. */
async function cacheFromLive(env, name, result) {
  if (name !== "tesco_search") return;
  try {
    const rows = JSON.parse(result.content?.[0]?.text ?? "[]");
    if (!Array.isArray(rows)) return;
    await upsertPrices(env, rows.map((r) => ({ sku: r.sku, title: r.title, price: r.price, unitPrice: r.unitPrice, clubcardPrice: clubcardFromText(r.offers), offers: (r.offers ?? []).map((t) => ({ text: t })), available: r.available })), "tesco");
  } catch {
    // best effort
  }
}

// --- Apify (free plan) --------------------------------------------------------

async function apifySearch(env, query, limit) {
  const actor = env.APIFY_ACTOR || DEFAULT_ACTOR;
  const url = `https://api.apify.com/v2/acts/${actor}/run-sync-get-dataset-items?timeout=60&clean=true`;
  const r = await fetch(url, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${env.APIFY_TOKEN}` },
    body: JSON.stringify({ searchTerms: [query], query, maxItems: limit, maxResults: limit, maxItemsPerSource: limit, maxPages: 1, includeDetails: false }),
    signal: AbortSignal.timeout(75000),
  });
  if (r.status === 402 || r.status === 403) throw new Error("The free Apify credit for this month is used up (or the token is invalid). Using the saved price list instead.");
  if (!r.ok) throw new Error(`Apify HTTP ${r.status}`);
  const items = await r.json();
  const results = (Array.isArray(items) ? items : []).filter((i) => !i.error).slice(0, limit).map(normaliseApify);
  await upsertPrices(env, results, "apify");
  return results;
}

/** Accepts the zaiq/tesco-scraper format, and blackfalcondata/tesco-scraper as a fallback. */
function normaliseApify(i) {
  const promos = (i.promotions ?? []).map((p) => ({ text: p.text ?? i.promotionText, ends: p.validTo ?? null })).filter((p) => p.text);
  if (!promos.length && i.promotionText) promos.push({ text: i.promotionText, ends: i.loyaltyValidTo ?? i.promotionEndsAt ?? null });
  return {
    sku: String(i.productId ?? i.sku ?? ""),
    title: i.name ?? i.title ?? null,
    price: num(i.price ?? i.priceValue ?? i.priceGbp),
    unitPrice: i.unitPrice != null ? `${i.unitPrice}/${i.unitPriceUnit ?? ""}` : (i.unitPriceText ?? null),
    clubcardPrice: num(i.loyaltyPrice ?? i.clubcardPrice ?? i.clubcardPriceGbp),
    clubcardEnds: i.loyaltyValidTo ?? i.promotionEndsAt ?? null,
    offers: promos,
    available: i.inStock ?? (i.availability ? i.availability === "AvailableForSale" : null),
  };
}

// --- saved data (D1) ----------------------------------------------------------

async function upsertPrices(env, rows, source) {
  const now = new Date().toISOString();
  const stmts = rows
    .filter((p) => p.sku)
    .map((p) =>
      env.DB.prepare(
        `INSERT INTO prices (sku, title, price, unit_price, clubcard_price, offers, available, source, checked_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(sku) DO UPDATE SET title=excluded.title, price=excluded.price, unit_price=excluded.unit_price,
           clubcard_price=excluded.clubcard_price, offers=excluded.offers, available=excluded.available,
           source=excluded.source, checked_at=excluded.checked_at`,
      ).bind(p.sku, p.title, p.price, p.unitPrice, p.clubcardPrice, JSON.stringify(p.offers ?? []), bool(p.available), source, now),
    );
  if (stmts.length) await env.DB.batch(stmts);
}

async function savedPrices(env, filter, limit) {
  const words = String(filter ?? "").toLowerCase().split(/\s+/).filter((w) => w.length > 1).slice(0, 6);
  const where = words.length ? words.map(() => "LOWER(title) LIKE ?").join(" AND ") : "1=1";
  let rows = (await env.DB.prepare(`SELECT * FROM prices WHERE ${where} ORDER BY times_bought DESC, checked_at DESC LIMIT ?`).bind(...words.map((w) => `%${w}%`), limit).all()).results;
  if (!rows.length && words.length > 1) {
    // Loosen: any of the words.
    const any = words.map(() => "LOWER(title) LIKE ?").join(" OR ");
    rows = (await env.DB.prepare(`SELECT * FROM prices WHERE ${any} ORDER BY times_bought DESC, checked_at DESC LIMIT ?`).bind(...words.map((w) => `%${w}%`), limit).all()).results;
  }
  return { priceListUpdated: await meta(env, "snapshot_at"), results: rows.map(rowOut), note: rows.length ? undefined : "Nothing matching in the saved price list." };
}

async function saveForLater(env, items, mode = "add") {
  const now = new Date().toISOString();
  const out = [];
  for (const it of items ?? []) {
    let name = it.name ?? null;
    if (it.sku && !name) name = (await env.DB.prepare("SELECT title FROM prices WHERE sku = ?").bind(it.sku).first())?.title ?? null;
    if (!it.sku && !name) continue;
    const r = await env.DB.prepare("INSERT INTO saved_items (sku, name, quantity, mode, status, created_at) VALUES (?, ?, ?, ?, 'pending', ?)").bind(it.sku ?? null, name, it.quantity ?? 1, mode ?? "add", now).run();
    out.push({ id: r.meta.last_row_id, sku: it.sku ?? null, name, quantity: it.quantity ?? 1 });
  }
  return out;
}

async function savedForLater(env) {
  const waiting = (await env.DB.prepare("SELECT id, sku, name, quantity, mode, created_at FROM saved_items WHERE status = 'pending' ORDER BY id").all()).results;
  const recent = (await env.DB.prepare("SELECT id, name, quantity, status, result, done_at FROM saved_items WHERE status != 'pending' ORDER BY done_at DESC LIMIT 20").all()).results.map((r) => ({ ...r, result: safeJson(r.result) }));
  return { waitingForLaptop: waiting, recentlyProcessed: recent };
}

async function status(env) {
  const [up, count, waiting] = await Promise.all([
    laptopUp(env),
    env.DB.prepare("SELECT COUNT(*) AS n FROM prices").first(),
    env.DB.prepare("SELECT COUNT(*) AS n FROM saved_items WHERE status = 'pending'").first(),
  ]);
  return {
    laptopOnline: up,
    savedPriceListProducts: count?.n ?? 0,
    savedPriceListUpdated: await meta(env, "snapshot_at"),
    itemsWaitingForLaptop: waiting?.n ?? 0,
    livePriceServiceWhenLaptopOff: env.APIFY_TOKEN ? `Apify (${env.APIFY_ACTOR || DEFAULT_ACTOR}), free plan` : "not set up",
    version: VERSION,
  };
}

let schemaReady = false;
async function ensureSchema(env) {
  if (schemaReady) return;
  await env.DB.batch([
    env.DB.prepare(`CREATE TABLE IF NOT EXISTS prices (sku TEXT PRIMARY KEY, title TEXT, price REAL, unit_price TEXT, clubcard_price REAL, offers TEXT, available INTEGER,
      times_bought INTEGER DEFAULT 0, typical_qty REAL, last_bought TEXT, is_favourite INTEGER DEFAULT 0, source TEXT, checked_at TEXT)`),
    env.DB.prepare(`CREATE TABLE IF NOT EXISTS saved_items (id INTEGER PRIMARY KEY AUTOINCREMENT, sku TEXT, name TEXT, quantity INTEGER, mode TEXT,
      status TEXT DEFAULT 'pending', result TEXT, created_at TEXT, done_at TEXT)`),
    env.DB.prepare("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)"),
  ]);
  schemaReady = true;
}

async function meta(env, k) {
  return (await env.DB.prepare("SELECT v FROM meta WHERE k = ?").bind(k).first())?.v ?? null;
}
function setMeta(env, k, v) {
  return env.DB.prepare("INSERT INTO meta (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v").bind(k, v);
}

// --- helpers ------------------------------------------------------------------

/** D1 rejects `undefined` bind values; map them to NULL. */
function safeEnv(env) {
  const db = env.DB;
  const DB = {
    prepare(sql) {
      const stmt = db.prepare(sql);
      const wrap = (st) => ({ bind: (...a) => wrap(st.bind(...a.map((v) => (v === undefined ? null : v)))), all: () => st.all(), first: (c) => st.first(c), run: () => st.run(), raw: () => st, _st: st });
      return wrap(stmt);
    },
    batch: (stmts) => db.batch(stmts.map((x) => x._st ?? x)),
  };
  return { ...env, DB };
}

function rowOut(r) {
  return {
    sku: r.sku,
    title: r.title,
    price: r.price,
    unitPrice: r.unit_price,
    clubcardPrice: r.clubcard_price,
    offers: safeJson(r.offers) ?? [],
    available: r.available === null || r.available === undefined ? null : Boolean(r.available),
    timesBought: r.times_bought || undefined,
    typicalQuantity: r.typical_qty ?? undefined,
    lastBought: r.last_bought ?? undefined,
    favourite: r.is_favourite ? true : undefined,
    priceChecked: r.checked_at,
    priceSource: r.source,
  };
}

function clubcardFromText(offers) {
  for (const t of offers ?? []) {
    // "£7.50 Clubcard Price" or "£7.50 Save 25% Clubcard Price" (multibuys like "Any 3 for £10" are skipped)
    const m = /^£(\d+(?:\.\d{1,2})?)\b.*Clubcard Price/i.exec(String(typeof t === "string" ? t : (t?.text ?? "")).trim());
    if (m) return Number(m[1]);
  }
  return null;
}
const num = (v) => (v === null || v === undefined || v === "" || Number.isNaN(Number(v)) ? null : Number(v));
const bool = (v) => (v === null || v === undefined ? null : v ? 1 : 0);
function safeJson(s) {
  try {
    return s ? JSON.parse(s) : null;
  } catch {
    return null;
  }
}
const text = (value) => ({ content: [{ type: "text", text: JSON.stringify(value, null, 2) }] });
const toolError = (message) => ({ isError: true, content: [{ type: "text", text: JSON.stringify({ error: message }) }] });
const rpcResult = (id, result) => ({ jsonrpc: "2.0", id, result });
const rpcError = (id, code, message) => ({ jsonrpc: "2.0", id, error: { code, message } });
