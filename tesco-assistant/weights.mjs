/**
 * Weighed products (e.g. pork fillet, loose fruit) for tesco-assistant.
 *
 * basketeer always adds a basket line as "N pieces", which Tesco rejects or
 * misreads for products sold by weight. Tesco's web app sends extra fields for
 * these, and they are not documented anywhere. probe-weights.mjs records what
 * tesco.com itself sends when a weighed item is added and saves it to
 * ~/.basketeer/weight-format.json; this module reuses that recorded request as a
 * template, changing only the product, quantity and weight.
 */

import { readFile, writeFile, mkdir } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";
import { AuthExpiredError, ENDPOINT, PUBLIC_API_KEY } from "basketeer";

const DIR = join(homedir(), ".basketeer");
export const FORMAT_FILE = join(DIR, "weight-format.json");
const UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36";

// --- describing how a product is sold ----------------------------------------

/**
 * "each": normal product. "weight-options": pick one of Tesco's weights
 * (catch-weight, e.g. pork fillet). "loose": can be bought by the piece or by kg.
 */
export function soldBy(p) {
  const q = p?.quantityRules ?? {};
  const options = (q.catchWeightOptions ?? []).filter((o) => o && o.weight > 0);
  if (options.length) {
    return {
      kind: "weight-options",
      options: options.map((o) => ({ weightKg: o.weight, price: o.price, default: Boolean(o.default) })),
    };
  }
  const perKg = String(p?.price?.unitOfMeasure ?? "").toLowerCase() === "kg";
  if (perKg && (q.averageWeight ?? 0) > 0) {
    return {
      kind: "loose",
      averageWeightKg: q.averageWeight,
      minKg: q.minWeight || null,
      maxKg: q.maxWeight || null,
      stepKg: q.increment || null,
      pricePerKg: p.price?.unitPrice ?? null,
    };
  }
  return { kind: "each" };
}

/** Human-readable summary for Claude, e.g. "choose a weight: 0.4kg £3.60 (default), 0.6kg £5.40". */
export function soldByText(s) {
  if (!s || s.kind === "each") return undefined;
  if (s.kind === "weight-options") {
    return (
      "choose a weight: " +
      s.options.map((o) => `${o.weightKg}kg £${Number(o.price).toFixed(2)}${o.default ? " (default)" : ""}`).join(", ")
    );
  }
  return `loose: by the piece (about ${s.averageWeightKg}kg each) or by kg${s.pricePerKg ? ` at £${s.pricePerKg}/kg` : ""}`;
}

export function nearestOption(options, weightKg) {
  if (!options.length) return null;
  if (!weightKg) return options.find((o) => o.default) ?? options[0];
  return options.reduce((best, o) => (Math.abs(o.weightKg - weightKg) < Math.abs(best.weightKg - weightKg) ? o : best));
}

// --- raw GraphQL with the helper's session ------------------------------------

/**
 * POST one GraphQL operation to Tesco with the client's current session.
 * `client.basket.get()` first, so an expired token is renewed by basketeer.
 */
export async function gql(client, { operationName, query, variables, mfeName = "mfe-basket" }) {
  for (let attempt = 0; attempt < 2; attempt++) {
    if (attempt === 1 || !client.session) await client.basket.get();
    const s = client.session;
    if (!s) throw new AuthExpiredError("Not signed in to Tesco.");
    const cookie = Object.entries(s.cookies ?? {})
      .map(([k, v]) => `${k}=${v}`)
      .join("; ");
    const res = await fetch(ENDPOINT, {
      method: "POST",
      headers: {
        "user-agent": UA,
        accept: "application/json",
        "content-type": "application/json",
        "accept-language": "en-GB",
        "x-apikey": process.env.TESCO_API_KEY || PUBLIC_API_KEY,
        region: "UK",
        language: "en-GB",
        traceid: `${crypto.randomUUID()}:${crypto.randomUUID()}`,
        trkid: crypto.randomUUID(),
        authorization: `Bearer ${s.accessToken}`,
        "customer-uuid": s.customerUuid,
        ...(cookie ? { cookie } : {}),
      },
      body: JSON.stringify([{ operationName, variables, extensions: { mfeName }, query }]),
    });
    const text = await res.text();
    if (res.status === 401 && attempt === 0) continue;
    if (!res.ok) throw new Error(`Tesco HTTP ${res.status}: ${text.slice(0, 200)}`);
    const json = JSON.parse(text);
    const first = Array.isArray(json) ? json[0] : json;
    const unauth = (first?.errors ?? []).some((e) => /unauth|401/i.test(e?.message ?? "") || e?.extensions?.http?.status === 401);
    if (unauth && attempt === 0) continue;
    if (first?.errors?.length) throw new Error(`Tesco error: ${first.errors.map((e) => e.message).join("; ")}`);
    return first?.data ?? {};
  }
  throw new AuthExpiredError("Tesco auth expired and could not be refreshed.");
}

// --- the learned format --------------------------------------------------------

export async function loadFormat() {
  try {
    return JSON.parse(await readFile(FORMAT_FILE, "utf8"));
  } catch {
    return null;
  }
}

export async function saveFormat(format) {
  await mkdir(DIR, { recursive: true, mode: 0o700 });
  await writeFile(FORMAT_FILE, JSON.stringify(format, null, 2), { mode: 0o600 });
}

/** Build a basket line from a recorded template, changing only product, quantity and weight/unit. */
function fromTemplate(t, { sku, quantity, weightKg }) {
  const item = structuredClone(t.itemTemplate);
  item[t.idKey ?? "id"] = String(sku);
  if (t.kind === "weight-options") {
    item[t.quantityKey ?? "newValue"] = quantity;
    if (t.weightKey) item[t.weightKey] = Math.round(weightKg * (t.weightScale ?? 1) * 1000) / 1000;
  } else {
    // loose by kg: Tesco's quantity is the weight in kg
    item[t.quantityKey ?? "newValue"] = Math.round(weightKg * 1000) / 1000;
    if (t.unitKey) item[t.unitKey] = t.unitValue;
  }
  return item;
}

/** Send one weighed line using Tesco's own recorded request. Returns the updated basket line (raw). */
export async function sendWeighted(client, template, args) {
  const item = fromTemplate(template, args);
  const variables = structuredClone(template.variables ?? {});
  variables[template.itemsVar ?? "items"] = [item];
  const data = await gql(client, {
    operationName: template.operationName,
    query: template.query,
    variables,
    mfeName: template.mfeName ?? "mfe-basket",
  });
  const basket = data.basket ?? {};
  const update = (basket.updates?.items ?? []).find((u) => String(u.id) === String(args.sku));
  if (update && update.successful === false) throw new Error(`Tesco rejected the weighed line for ${args.sku}`);
  const line = (basket.items ?? []).find((l) => String(l.product?.id ?? l.id) === String(args.sku));
  return { sent: item, line: line ? { quantity: line.quantity, weight: line.weight ?? null, unit: line.unit ?? null, cost: line.cost ?? null } : null };
}
