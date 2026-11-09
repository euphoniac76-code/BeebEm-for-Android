#!/usr/bin/env node
/**
 * Save (or change) the Tesco email and password the helper uses to sign in by itself.
 *
 *   node save-password.mjs            asks for them and saves them
 *   node save-password.mjs --delete   removes them
 *
 * They go into Windows Credential Manager as "tesco-assistant:login", encrypted for
 * your Windows account (Control Panel > Credential Manager > Windows Credentials).
 */

import { rm } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";
import { createInterface } from "node:readline";
import { deleteTescoCredentials, saveTescoCredentials, TARGET } from "./credentials.mjs";

const STATE = join(homedir(), ".basketeer", "renewal-state.json"); // lets automatic sign-in try again at once

function ask(question, hidden = false) {
  return new Promise((resolve) => {
    const rl = createInterface({ input: process.stdin, output: process.stdout, terminal: true });
    if (hidden) {
      rl._writeToOutput = (s) => {
        if (s.includes(question)) rl.output.write(s);
        else if (s === "\r\n" || s === "\n") rl.output.write(s);
        else rl.output.write("*".repeat(s.length));
      };
    }
    rl.question(question, (answer) => {
      rl.close();
      if (hidden) process.stdout.write("\n");
      resolve(answer);
    });
  });
}

try {
  if (process.argv.includes("--delete")) {
    const removed = await deleteTescoCredentials();
    await rm(STATE, { force: true });
    console.log(removed ? "Removed the saved Tesco login." : "No saved Tesco login was found.");
  } else {
    console.log("Your Tesco email and password will be saved in Windows Credential Manager,");
    console.log("encrypted for your Windows account, so the helper can sign in to Tesco by itself.\n");
    const user = (await ask("Tesco email address: ")).trim();
    const pass = await ask("Tesco password: ", true);
    if (!user || !pass) throw new Error("Both the email and the password are needed.");
    await deleteTescoCredentials(); // also clears the older PowerShell-made entry
    await saveTescoCredentials(user, pass);
    await rm(STATE, { force: true });
    console.log(`\nSaved as "${TARGET}". You can view or remove it in Control Panel > Credential Manager.`);
  }
} catch (err) {
  console.error(`\nCouldn't save the Tesco login: ${err instanceof Error ? err.message : err}`);
  process.exitCode = 1;
}
