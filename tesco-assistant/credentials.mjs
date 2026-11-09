/**
 * The Tesco email and password the helper uses to sign in again when Tesco
 * refuses a renewal. Stored in Windows Credential Manager (encrypted for the
 * Windows user) through @napi-rs/keyring, so no PowerShell is involved.
 * Save them with save-password.mjs.
 *
 * Uses the library's standard entry (service "tesco-assistant", account
 * "tesco.com"). Named entries made with Entry.withTarget() could be written but
 * not read back on Windows, so they are only cleaned up here.
 *
 * The values stay in memory only: never logged, only typed into tesco.com's login page.
 */

const SERVICE = "tesco-assistant";
const ACCOUNT = "tesco.com";
export const TARGET = `${SERVICE} (${ACCOUNT})`; // how it's described to the user
const OLD_TARGETS = ["tesco-assistant:login", "tesco-assistant:tesco.com"];

/** Testing only: a function returning fake credentials, used instead of the real store. */
export const credentialTestHooks = { provide: null };

async function entry() {
  const { Entry } = await import("@napi-rs/keyring");
  return new Entry(SERVICE, ACCOUNT);
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

/** Removes the saved login, and the entries earlier versions created. */
export async function deleteTescoCredentials() {
  const { Entry } = await import("@napi-rs/keyring");
  let removed = false;
  try {
    removed = (await entry()).deleteCredential() || removed;
  } catch {
    // nothing saved
  }
  for (const t of OLD_TARGETS) {
    try {
      removed = Entry.withTarget(t, SERVICE, ACCOUNT).deleteCredential() || removed;
    } catch {
      // nothing saved under this name
    }
  }
  return removed;
}
