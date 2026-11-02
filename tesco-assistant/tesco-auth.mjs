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
    headless: process.env.TESCO_CHROME_HEADLESS === "1", // testing only; Tesco blocks headless Chrome
    viewport: { width: 1280, height: 800 },
    locale: "en-GB",
    timezoneId: "Europe/London",
    args: ["--disable-blink-features=AutomationControlled", "--no-first-run", "--no-default-browser-check"],
    ignoreDefaultArgs: ["--enable-automation"],
  });
  await ctx.addInitScript(STEALTH);
  return ctx;
}

/** Save every Tesco cookie from Chrome. Returns them. */
export async function saveJar(ctx) {
  const cookies = (await ctx.cookies()).filter(isTesco);
  await mkdir(DIR, { recursive: true, mode: 0o700 });
  await writeFile(JAR, JSON.stringify(cookies, null, 1), { mode: 0o600 });
  return cookies;
}

/** Put saved Tesco cookies (not yet expired) back into Chrome. Returns how many. */
async function restoreJar(ctx) {
  let jar;
  try {
    jar = JSON.parse(await readFile(JAR, "utf8"));
  } catch {
    return 0;
  }
  const now = Date.now() / 1000;
  const keep = jar.filter((c) => c.expires === -1 || c.expires === undefined || c.expires > now + 30);
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
    const ctx = await launchChrome();
    try {
      const restored = await restoreJar(ctx);
      const page = ctx.pages()[0] ?? (await ctx.newPage());
      const token = async () => (await ctx.cookies()).find((c) => c.name === "OAuth.AccessToken" && isTesco(c))?.value;
      const before = await token();
      await page.goto(REFRESH_URL, { waitUntil: "domcontentloaded", timeout: 30_000 }).catch(() => {});
      let path = "";
      for (let i = 0; i < 25; i++) {
        path = page.url().split("?")[0];
        if (path.includes("/account/login")) break;
        const now = await token();
        if (now && now !== before && !path.includes("/account/auth")) break;
        await new Promise((r) => setTimeout(r, 1000));
      }
      const cookies = (await ctx.cookies()).filter(isTesco);
      log(`renewal: restored ${restored} saved cookies, ended on ${path}, ${cookies.length} Tesco cookies now:\n  ${describeCookies(cookies)}`);
      if (path.includes("/account/login")) {
        throw new AuthExpiredError("Tesco sent the renewal to its login page: the Tesco sign-in has expired.");
      }
      await saveJar(ctx);
      return sessionFromCookies(cookies);
    } finally {
      await ctx.close().catch(() => {});
    }
  }
}

export { GROCERIES_URL };
