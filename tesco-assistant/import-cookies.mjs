#!/usr/bin/env node
/**
 * Save a Tesco session from cookies you exported from your own browser.
 * This is for devices where an automated Chrome can't run, such as an Android phone.
 *
 * Usage:
 *   node import-cookies.mjs cookies.json     # JSON array exported by a cookie extension
 *   node import-cookies.mjs cookies.txt      # or a "name=value; name2=value2" header string
 *   termux-clipboard-get | node import-cookies.mjs -    # read from stdin
 *
 * Only Tesco's sign-in and bot-check cookies are kept (OAuth.*, UUID, _abck, bm_*, ak_bmsc).
 * They are written to ~/.basketeer/session.json with owner-only permissions.
 */

import { readFile } from "node:fs/promises";
import { FileTokenStore, sessionFromCookies } from "basketeer";

// Keep only tesco.com (UK) cookies; cookies without a domain are kept.
const isUkTesco = (c) => !c.domain || /(^|\.)tesco\.com$/i.test(String(c.domain).replace(/^\./, ""));

async function readInput(arg) {
  if (!arg || arg === "-") {
    const chunks = [];
    for await (const c of process.stdin) chunks.push(c);
    return Buffer.concat(chunks).toString("utf8");
  }
  return readFile(arg, "utf8");
}

function parseCookies(text) {
  const t = text.trim();
  if (t.startsWith("[") || t.startsWith("{")) {
    const data = JSON.parse(t);
    const list = Array.isArray(data) ? data : (data.cookies ?? []);
    return list.map((c) => ({ name: c.name, value: c.value, domain: c.domain }));
  }
  // Header string: "a=1; b=2" (a leading "Cookie:" is allowed)
  return t
    .replace(/^cookie:\s*/i, "")
    .split(/;\s*/)
    .filter((p) => p.includes("="))
    .map((p) => {
      const i = p.indexOf("=");
      return { name: p.slice(0, i).trim(), value: p.slice(i + 1).trim() };
    });
}

try {
  const session = sessionFromCookies(parseCookies(await readInput(process.argv[2])).filter(isUkTesco));
  await new FileTokenStore().save(session);
  const mins = session.accessTokenExpiry ? Math.round((session.accessTokenExpiry - Date.now()) / 60000) : null;
  console.log("Saved Tesco session to ~/.basketeer/session.json");
  if (mins !== null) {
    console.log(
      mins > 0
        ? `Access token valid for about ${mins} more minutes.`
        : "Warning: this access token has already expired. Reload tesco.com in your browser and export again.",
    );
  }
} catch (err) {
  console.error(`Could not import cookies: ${err instanceof Error ? err.message : err}`);
  console.error("Make sure you were signed in on www.tesco.com when you exported, and exported ALL cookies for the site.");
  process.exit(1);
}
