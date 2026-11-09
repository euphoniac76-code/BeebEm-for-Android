/**
 * The Tesco email and password the helper uses to sign in again when Tesco
 * refuses a renewal. Stored in Windows Credential Manager (entry
 * "tesco-assistant:login", encrypted for the Windows user) through
 * @napi-rs/keyring, so no PowerShell is involved. Save them with save-password.mjs.
 *
 * The values stay in memory only: never logged, only typed into tesco.com's login page.
 */

export const TARGET = "tesco-assistant:login";
const SERVICE = "tesco-assistant";
const ACCOUNT = "tesco.com";

/** Testing only: a function returning fake credentials, used instead of the real store. */
export const credentialTestHooks = { provide: null };

async function entry(target = TARGET) {
  const { Entry } = await import("@napi-rs/keyring");
  return Entry.withTarget(target, SERVICE, ACCOUNT);
}

/** { username, password } or null when nothing is saved (or the store can't be read). */
export async function getTescoCredentials() {
  if (credentialTestHooks.provide) return credentialTestHooks.provide();
  try {
    const raw = (await entry()).getPassword();
    if (!raw) return null;
    const j = JSON.parse(raw);
    return j.u && j.p ? { username: j.u, password: j.p } : null;
  } catch (err) {
    // Never includes the password: only the store's error message.
    console.log(new Date().toISOString(), "[credentials] couldn't read the saved Tesco login:", err instanceof Error ? err.message : String(err));
    return null;
  }
}

export async function saveTescoCredentials(username, password) {
  (await entry()).setPassword(JSON.stringify({ u: username, p: password }));
}

/** Removes the saved login, and the entry an earlier version created with PowerShell. */
export async function deleteTescoCredentials() {
  let removed = false;
  for (const t of [TARGET, "tesco-assistant:tesco.com"]) {
    try {
      removed = (await entry(t)).deleteCredential() || removed;
    } catch {
      // nothing saved under this name
    }
  }
  return removed;
}
