#!/usr/bin/env node
/**
 * Sign in to Tesco once in a real Chrome window and save the session.
 * Used by the "Tesco sign-in" desktop shortcut (and the Windows setup).
 *
 * Unlike `basketeer login`, this also saves Tesco's full cookie jar, including
 * cookies Chrome would otherwise forget when it closes, so the helper can keep
 * renewing the sign-in afterwards. See tesco-auth.mjs.
 */

import { createInterface } from "node:readline/promises";
import { FileTokenStore, sessionFromCookies } from "basketeer";
import { clearRenewalBackoff, describeCookies, GROCERIES_URL, launchChrome, LOGIN_URL, saveJar } from "./tesco-auth.mjs";

const ctx = await launchChrome();
try {
  const page = ctx.pages()[0] ?? (await ctx.newPage());
  await page.goto(LOGIN_URL).catch(() => {});

  console.log(`\n${"=".repeat(70)}`);
  console.log("TESCO SIGN-IN: a Chrome window has opened.");
  console.log("  1. Sign in normally (including any code Tesco sends you).");
  console.log("     If there is a 'Keep me signed in' / 'Remember me' option, tick it.");
  console.log("  2. Wait until you can see the Tesco groceries page.");
  console.log("  3. Come back to this window and press Enter.");
  console.log("=".repeat(70));
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  await rl.question("\n  >>> Press Enter once you are signed in <<<\n");
  rl.close();

  await page.goto(GROCERIES_URL, { waitUntil: "domcontentloaded", timeout: 20_000 }).catch(() => {});
  const cookies = await saveJar(ctx);
  const session = sessionFromCookies(cookies); // throws if the sign-in did not complete
  await new FileTokenStore().save(session);
  await clearRenewalBackoff();

  console.log("\nSigned in. Tesco cookies saved:");
  console.log(`  ${describeCookies(cookies)}`);
  console.log("\n(These show how long Tesco keeps you signed in. No cookie values are shown.)");
} catch (err) {
  console.error(`\nSign-in did not complete: ${err instanceof Error ? err.message : err}`);
  console.error("Run the 'Tesco sign-in' shortcut again and make sure the Tesco page has loaded before pressing Enter.");
  process.exitCode = 1;
} finally {
  await ctx.close().catch(() => {});
}
