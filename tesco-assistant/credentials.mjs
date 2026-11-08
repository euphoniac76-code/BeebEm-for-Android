/**
 * Read the Tesco email and password saved in Windows Credential Manager by
 * windows/credential.ps1 (entry "tesco-assistant:tesco.com").
 *
 * Returns { username, password } or null when nothing is saved (or not on Windows).
 * The values stay in memory only: never logged, only typed into tesco.com's login page.
 */

import { execFile } from "node:child_process";
import { existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));

/** Testing only: a function returning fake credentials, used instead of Windows. */
export const credentialTestHooks = { provide: null };

export async function getTescoCredentials() {
  if (credentialTestHooks.provide) return credentialTestHooks.provide();
  if (process.platform !== "win32") return null;
  const script = [join(here, "credential.ps1"), join(here, "windows", "credential.ps1")].find((p) => existsSync(p));
  if (!script) return null;
  return new Promise((resolve) => {
    execFile(
      "powershell.exe",
      ["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script, "-Read"],
      { windowsHide: true, timeout: 30_000 },
      (err, stdout) => {
        if (err) return resolve(null);
        try {
          const j = JSON.parse(Buffer.from(String(stdout).trim(), "base64").toString("utf8"));
          resolve(j.u && j.p ? { username: j.u, password: j.p } : null);
        } catch {
          resolve(null);
        }
      },
    );
  });
}
