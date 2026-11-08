/**
 * Tesco sign-in and token renewal with a real Chrome, keeping the full cookie jar.
 *
 * basketeer's BrowserAuthBackend relies on the Chrome profile to remember Tesco's
 * cookies between launches. Chrome drops session cookies (no expiry) whenever it
 * closes, so after the first close the profile can no longer renew the sign-in.
 * Here every Tesco cookie (including session cookies, with domain, path, expiry and
 * flags) is saved to ~/.basketeer/tesco-cookies.json after sign-in and after each
 * renewal, and put back into Chrome before the next renewal.
 *
 * Only cookie names and expiry times are ever logged, never their values.
 */

import { readFile, writeFile, mkdir } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";
import { AuthExpiredError, sessionFromCookies } from "basketeer";

const DIR = join(homedir(), ".basketeer");
const PROFILE = join(DIR, "chrome-profile");
const JAR = join(DIR, "tesco-cookies.json");
export const LOGIN_URL = "https://www.tesco.com/account/login";
const GROCERIES_URL = "https://www.tesco.com/groceries/";
const REFRESH_URL =
  "https://www.tesco.com/account/auth/en-GB/refresh-token?soft-refresh=false" +
  "&from=https%3A%2F%2Fwww.tesco.com%2Fshop%2Fen-GB%2Flanding%2Fgroceries";
const STEALTH = `() => {
  Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
  Object.defineProperty(navigator, 'languages', { get: () => ['en-GB', 'en'] });
  Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3] });
}`;

const isTesco = (c) => /(^|\.)tesco\.com$/i.test(String(c.domain ?? "").replace(/^\./, ""));
const log = (...a) => console.log(new Date().toISOString(), "[tesco-auth]", ...a);

export async function launchChrome() {
  let chromium;
  try {
    ({ chromium } = await import("playwright"));
  } catch {
    throw new Error("Playwright is not installed. Run `npm install` in the tesco-assistant folder.");
  }
  const channel = process.env.TESCO_CHROME_CHANNEL ?? "chrome"; // "" = Playwright's bundled Chromium (testing)
  const ctx = await chromium.launchPersistentContext(PROFILE, {
    channel: channel || undefined,
    // Playwright adds --no-sandbox by default, which shows a warning banner and makes
    // Chrome look less like an ordinary browser to Tesco's bot checks.
    chromiumSandbox: process.env.TESCO_CHROME_SANDBOX !== "0",
    headless: process.env.TESCO_CHROME_HEADLESS === "1", // testing only; Tesco blocks headless Chrome
    viewport: { width: 1280, height: 800 },
    locale: "en-GB",
    timezoneId: "Europe/London",
    args: ["--disable-blink-features=AutomationControlled", "--no-first-run", "--no-default-browser-check"],
    ignoreDefaultArgs: ["--enable-automation"],
  });
  await ctx.addInitScript(STEALTH);
  if (testHooks.onContext) await testHooks.onContext(ctx);
  return ctx;
}

/** Testing only: lets a test intercept Chrome's requests (e.g. a fake tesco.com). */
export const testHooks = { onContext: null };

/** Save every Tesco cookie from Chrome. Returns them. */
export async function saveJar(ctx) {
  const cookies = (await ctx.cookies()).filter(isTesco);
  await mkdir(DIR, { recursive: true, mode: 0o700 });
  await writeFile(JAR, JSON.stringify(cookies, null, 1), { mode: 0o600 });
  return cookies;
}

const STATE = join(DIR, "renewal-state.json");
const BACKOFF_MS = 15 * 60 * 1000;

async function readJarFile() {
  try {
    return JSON.parse(await readFile(JAR, "utf8"));
  } catch {
    return [];
  }
}
async function readState() {
  try {
    return JSON.parse(await readFile(STATE, "utf8"));
  } catch {
    return {};
  }
}
async function writeState(s) {
  await mkdir(DIR, { recursive: true, mode: 0o700 });
  await writeFile(STATE, JSON.stringify(s), { mode: 0o600 });
}
/** Called after a successful sign-in: allow renewals again straight away. */
export async function clearRenewalBackoff() {
  await writeState({});
}

// The hourly token cookies. A browser that stayed open would still be sending
// these when it renews, so they're put back even after they've expired.
const HOURLY_AUTH = new Set(["OAuth.AccessToken", "UUID"]);

/**
 * Put saved Tesco cookies back into Chrome. Expired cookies are skipped, except
 * the hourly sign-in ones when `withExpiredAuth` is set. Returns how many.
 */
export async function restoreJar(ctx, { withExpiredAuth = false } = {}) {
  let jar;
  try {
    jar = JSON.parse(await readFile(JAR, "utf8"));
  } catch {
    return 0;
  }
  const now = Date.now() / 1000;
  const keep = [];
  for (const c of jar) {
    const live = c.expires === -1 || c.expires === undefined || c.expires > now + 30;
    if (live) keep.push(c);
    else if (withExpiredAuth && HOURLY_AUTH.has(c.name)) keep.push({ ...c, expires: Math.floor(now) + 15 * 60 });
  }
  if (keep.length) await ctx.addCookies(keep);
  return keep.length;
}

/** One line per cookie: name, domain, and when it expires ("browser close" for session cookies). */
export function describeCookies(cookies) {
  return cookies
    .map((c) => {
      const exp = c.expires === -1 || c.expires === undefined ? "browser close" : new Date(c.expires * 1000).toISOString();
      return `${c.name} (${c.domain}) expires ${exp}`;
    })
    .sort()
    .join("\n  ");
}

/** AuthBackend for basketeer: renewal through Chrome with the saved cookie jar. */
export class TescoBrowserAuth {
  async login() {
    throw new AuthExpiredError("Sign-in needs a person: run login.mjs (the 'Tesco sign-in' shortcut).");
  }

  async refresh() {
    // After a failed renewal, don't try again for a while: each attempt opens Chrome for
    // about a minute, and repeated failures may make Tesco's security checks stricter.
    const state = await readState();
    if (state.lastFailure && Date.now() - state.lastFailure < BACKOFF_MS) {
      throw new AuthExpiredError(`${state.message ?? "The last Tesco sign-in renewal failed."} (Not retried for 15 minutes, or until the user signs in again.)`);
    }
    // Nothing to renew with: ask for a sign-in without contacting Tesco.
    const saved = await readJarFile();
    const refreshCookie = saved.find((c) => c.name === "OAuth.RefreshToken" && (c.expires === -1 || c.expires > Date.now() / 1000));
    if (!refreshCookie) {
      const msg = "There is no valid Tesco refresh cookie to renew the sign-in with.";
      log(`renewal skipped: ${msg} Saved sign-in cookies: ${saved.map((c) => c.name).filter((n) => n.startsWith("OAuth.") || n === "UUID").join(", ") || "none"}`);
      await writeState({ lastFailure: Date.now(), message: msg });
      throw new AuthExpiredError(msg);
    }
    try {
      const session = await this.#renew(refreshCookie.value);
      await writeState({});
      return session;
    } catch (err) {
      await writeState({ lastFailure: Date.now(), message: err instanceof Error ? err.message : String(err) });
      throw err;
    }
  }

  async #renew(refreshBefore) {
    const ctx = await launchChrome();
    try {
      const restored = await restoreJar(ctx, { withExpiredAuth: true });
      const page = ctx.pages()[0] ?? (await ctx.newPage());
      const all = async () => (await ctx.cookies()).filter(isTesco);
      const token = async () => (await all()).find((c) => c.name === "OAuth.AccessToken" && c.expires > Date.now() / 1000 + 60)?.value;
      const refreshNow = async () => (await all()).find((c) => c.name === "OAuth.RefreshToken")?.value;
      const before = await token();

      /** Open a page and wait up to `secs` for a fresh access token (or the login page). */
      const attempt = async (url, secs, label) => {
        await page.goto(url, { waitUntil: "domcontentloaded", timeout: 30_000 }).catch(() => {});
        let path = "";
        for (let i = 0; i < secs; i++) {
          path = page.url().split("?")[0];
          if (path.includes("/account/login")) break;
          const now = await token();
          if (now && now !== before && !path.includes("/account/auth")) return { ok: true, path };
          await new Promise((r) => setTimeout(r, 1000));
        }
        const names = (await all()).map((c) => c.name).filter((n) => n.startsWith("OAuth.") || n === "UUID");
        log(`renewal ${label}: ended on ${path}; sign-in cookies now: ${names.join(", ") || "none"}`);
        return { ok: false, path };
      };

      let r = await attempt(REFRESH_URL, 25, "step 1");
      // Tesco sometimes answers with only a provisional sign-in (OAuth.RefreshToken.Provisional).
      // Let its groceries page finish loading, then ask for the token once more.
      if (!r.ok && !r.path.includes("/account/login")) r = await attempt(GROCERIES_URL, 15, "step 2 (groceries page)");
      if (!r.ok && !r.path.includes("/account/login")) r = await attempt(REFRESH_URL, 20, "step 3 (renew again)");

      // Tesco replaces the refresh cookie on each renewal and the old one stops working.
      // Don't save until the replacement has arrived (or 15 seconds have passed).
      let rotated = false;
      if (r.ok) {
        for (let i = 0; i < 15; i++) {
          const now = await refreshNow();
          const path = page.url().split("?")[0];
          if (now && now !== refreshBefore && !path.includes("/account/auth")) {
            rotated = true;
            break;
          }
          await new Promise((res) => setTimeout(res, 1000));
        }
        await new Promise((res) => setTimeout(res, 1500)); // let any last cookies land
      }

      const cookies = await all();
      const signin = cookies.map((c) => c.name).filter((n) => n.startsWith("OAuth.") || n === "UUID").join(", ");
      log(
        `renewal ${r.ok ? "OK" : "FAILED"}: restored ${restored} saved cookies, ended on ${r.path}; ` +
          `refresh cookie replaced: ${r.ok ? (rotated ? "yes" : "no (kept the same one)") : "n/a"}; sign-in cookies: ${signin || "none"}; ` +
          `${cookies.length} Tesco cookies:\n  ${describeCookies(cookies)}`,
      );
      if (!r.ok) {
        // Don't save: keep the last good cookie jar for the next attempt.
        const provisional = cookies.some((c) => c.name === "OAuth.RefreshToken.Provisional");
        throw new AuthExpiredError(
          r.path.includes("/account/login")
            ? "Tesco sent the renewal to its login page: the Tesco sign-in has expired."
            : provisional
              ? "Tesco gave only a provisional sign-in and wants the user to sign in again to confirm it's them."
              : "Tesco did not issue a new sign-in token.",
        );
      }
      await saveJar(ctx);
      return sessionFromCookies(cookies);
    } finally {
      await ctx.close().catch(() => {});
    }
  }
}

export { GROCERIES_URL };
