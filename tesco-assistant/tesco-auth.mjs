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
import { getTescoCredentials } from "./credentials.mjs";

const DIR = join(homedir(), ".basketeer");
const PROFILE = join(DIR, "chrome-profile");
const JAR = join(DIR, "tesco-cookies.json");
export const LOGIN_URL = "https://www.tesco.com/account/login";
const GROCERIES_URL = "https://www.tesco.com/groceries/";
const REFRESH_URL =
  "https://www.tesco.com/account/auth/en-GB/refresh-token?soft-refresh=false" +
  "&from=https%3A%2F%2Fwww.tesco.com%2Fshop%2Fen-GB%2Flanding%2Fgroceries";
// No "stealth" script: the fake navigator values it used (plugins [1,2,3], webdriver
// undefined) are themselves bot signals now. --disable-blink-features=AutomationControlled
// already makes navigator.webdriver false, as in ordinary Chrome; the rest is real Chrome.

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
const AUTO_BACKOFF_MS = 6 * 60 * 60 * 1000; // at most one automatic password sign-in attempt per 6 hours after a failure
const AUTH_LOGIN_URL =
  "https://www.tesco.com/account/auth/en-GB/login?from=https%3A%2F%2Fwww.tesco.com%2Fgroceries%2Fen-GB%2F";

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
    let state = await readState();
    let problem = null;

    // 1. Renew with the saved cookies. After a failed renewal, don't try again for a while:
    //    each attempt opens Chrome for about a minute, and repeated failures may make
    //    Tesco's security checks stricter.
    if (state.lastFailure && Date.now() - state.lastFailure < BACKOFF_MS) {
      problem = `${state.message ?? "The last Tesco sign-in renewal failed."} (Renewal not retried for 15 minutes.)`;
    } else {
      const saved = await readJarFile();
      const refreshCookie = saved.find((c) => c.name === "OAuth.RefreshToken" && (c.expires === -1 || c.expires > Date.now() / 1000));
      if (!refreshCookie) {
        problem = "There is no valid Tesco refresh cookie to renew the sign-in with.";
        log(`renewal skipped: ${problem} Saved sign-in cookies: ${saved.map((c) => c.name).filter((n) => n.startsWith("OAuth.") || n === "UUID").join(", ") || "none"}`);
      } else {
        try {
          const session = await this.#renew(refreshCookie.value);
          await writeState({});
          return session;
        } catch (err) {
          problem = err instanceof Error ? err.message : String(err);
        }
      }
      state = { ...state, lastFailure: Date.now(), message: problem };
      await writeState(state);
    }

    // 2. Sign in again with the email and password saved in Windows Credential Manager.
    const creds = await getTescoCredentials();
    if (!creds) {
      log("automatic sign-in skipped: no Tesco login is saved (use the 'Tesco password' shortcut)");
      throw new AuthExpiredError(`${problem} No Tesco password is saved for automatic sign-in: double-click 'Tesco password' on the laptop's desktop to save it.`);
    }
    if (state.autoStop) throw new AuthExpiredError(`${problem} Automatic sign-in is paused: ${state.autoStop}`);
    if (state.autoFailure && Date.now() - state.autoFailure < AUTO_BACKOFF_MS) {
      throw new AuthExpiredError(`${problem} Automatic sign-in failed recently (${state.autoMessage ?? "unknown reason"}), so it waits 6 hours before trying again.`);
    }
    try {
      const session = await this.#autoSignIn(creds);
      await writeState({});
      return session;
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      await writeState({ ...state, autoFailure: Date.now(), autoMessage: msg, ...(err?.permanent ? { autoStop: msg } : {}) });
      throw new AuthExpiredError(`Automatic Tesco sign-in didn't work: ${msg}`);
    }
  }

  /**
   * Sign in on Tesco's login page with the saved email and password, typed like a person.
   * Throws (with `permanent` set if the password looks wrong) instead of guessing.
   */
  async #autoSignIn({ username, password }) {
    const ctx = await launchChrome();
    const fail = (message, permanent = false) => Object.assign(new Error(message), { permanent });
    try {
      await restoreJar(ctx); // keep this "device" recognisable to Tesco
      const page = ctx.pages()[0] ?? (await ctx.newPage());
      const pause = (ms) => page.waitForTimeout(ms + Math.floor(Math.random() * 400));
      const bodyText = () => page.locator("body").innerText({ timeout: 3000 }).catch(() => "");
      const blocked = async () => /failed some security checks|something is not right|access denied|unusual traffic/i.test(await bodyText());
      const token = async () =>
        (await ctx.cookies()).filter(isTesco).find((c) => c.name === "OAuth.AccessToken" && c.expires > Date.now() / 1000 + 60)?.value;
      const before = await token();

      await page.goto(AUTH_LOGIN_URL, { waitUntil: "domcontentloaded", timeout: 30_000 });
      await pause(1500);
      if (await blocked()) throw fail("Tesco's security check blocked the sign-in page.");
      const consent = page.locator("#onetrust-accept-btn-handler");
      if (await consent.isVisible({ timeout: 3000 }).catch(() => false)) {
        await consent.click();
        await pause(800);
      }

      // Only ever use visible fields and buttons: sign-in pages often keep hidden copies.
      const emailBox = page.locator('input[type="email"]:visible, input[name="username"]:visible, #username:visible, input[name="email"]:visible').first();
      const passwordBox = page.locator('input[type="password"]:visible, input[name="password"]:visible, #password:visible').first();
      if (await emailBox.isVisible({ timeout: 15_000 }).catch(() => false)) {
        await emailBox.click();
        await emailBox.fill("");
        await emailBox.pressSequentially(username, { delay: 60 });
        await pause(500);
        if (!(await passwordBox.isVisible().catch(() => false))) {
          const next = page.locator('button:visible:has-text("Continue"), button:visible:has-text("Next"), button[type="submit"]:visible').first();
          if (await next.isVisible({ timeout: 2000 }).catch(() => false)) await next.click();
          await pause(1500);
        }
      }
      if (!(await passwordBox.isVisible({ timeout: 20_000 }).catch(() => false))) {
        if (await blocked()) throw fail("Tesco's security check blocked the sign-in.");
        throw fail("Tesco's sign-in page didn't show a password box (its layout may have changed).");
      }
      await passwordBox.click();
      await passwordBox.pressSequentially(password, { delay: 70 });
      await pause(600);
      const submit = page.locator('button[type="submit"]:visible').first();
      if (await submit.isVisible().catch(() => false)) await submit.click();
      else await passwordBox.press("Enter");

      for (let i = 0; i < 45; i++) {
        await page.waitForTimeout(1000);
        const path = page.url().split("?")[0];
        const now = await token();
        if (now && now !== before && !/\/account\/(login|auth)/.test(path)) {
          await page.waitForTimeout(2000); // let the remaining sign-in cookies land
          const cookies = await saveJar(ctx);
          const names = cookies.map((c) => c.name).filter((n) => n.startsWith("OAuth.") || n === "UUID");
          log(`automatic sign-in OK: ended on ${path}; sign-in cookies: ${names.join(", ")}`);
          return sessionFromCookies(cookies);
        }
        if (i % 5 === 4) {
          const text = await bodyText();
          const codeBox = page.locator('input[name="otp"]:visible, input[name="code"]:visible, input[autocomplete="one-time-code"]:visible, #otp:visible, #code:visible').first();
          if ((await codeBox.isVisible().catch(() => false)) || /verification code|enter the code|one-time/i.test(text)) {
            throw fail("Tesco asked for a verification code (sent by email or text), so the user needs to sign in by hand.");
          }
          if (/(password|details).{0,40}(incorrect|not right|didn.t match|don.t match|wrong)|(incorrect|wrong).{0,20}password|not recogni[sz]e/i.test(text)) {
            throw fail("Tesco said the saved email or password is wrong. Save the correct one again.", true);
          }
          if (await blocked()) throw fail("Tesco's security check blocked the sign-in.");
        }
      }
      log(`automatic sign-in FAILED: still on ${page.url().split("?")[0]} after 45 seconds`);
      throw fail("Tesco didn't finish the sign-in within 45 seconds.");
    } finally {
      await ctx.close().catch(() => {});
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
