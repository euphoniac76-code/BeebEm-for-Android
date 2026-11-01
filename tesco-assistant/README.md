# tesco-assistant

Lets Claude use your **past Tesco orders** and help **fill your Tesco basket**.

## Option A (recommended): phone or anywhere, no install

This works in the Claude app on Android with **no terminal, no server and no Tesco login handling**:

* **Past orders**: Claude reads the Tesco **receipt emails in your Gmail**, using the Gmail connector.
  Every receipt lists each item, quantity and price, so Claude can work out your usual products.
* **Basket**: Claude replies with **one tappable link per item**. Each link opens that exact product on
  Tesco, in the app or the website, where you're already signed in. You tap **Add**.

**Setup (once, about 2 minutes):**

1. Make sure Gmail is connected in Claude: Settings → Connectors → Gmail.
2. Optional, for more consistent results: install the skill. Download
   [`claude-skill/tesco-shopping.zip`](claude-skill/tesco-shopping.zip). Then, in your phone's browser,
   go to **claude.ai → Customize → Skills → + → Upload a skill** and choose the zip. Code execution must
   be on in Settings → Capabilities.

**Use:** ask Claude something like *"We're out of milk, bread and teabags. Give me my usual Tesco ones."*
Then tap each link, tap Add, and check out in the Tesco app as normal.

Why links rather than adding directly? Tesco has no public API. Its login is protected by bot
detection that blocks servers (including Claude's), and its login token expires hourly. Anything that
adds items for you has to carry your login out of your browser, which needs a computer or a lot of
setup (Options B and C below). A link opens in your own signed-in browser or app, so nothing has to be
set up or kept logged in.

## Option B: computer (Claude adds items directly)

> Unofficial. Not affiliated with Tesco. Use it only for your own account, and only within Tesco's terms.

## How it works (and why this approach)

Tesco has no public API. The developer API it once had was shut down years ago. The options are:

| Approach | Verdict |
| --- | --- |
| Scrape the website HTML with a bot | Fragile. It breaks with every redesign, and Akamai bot protection blocks it. |
| Give Claude your password and let it type it in | Unsafe and unreliable. It fails on CAPTCHAs and 2-step verification, and your password ends up in a chat log. |
| Claude drives your browser (Claude in Chrome) | Works for occasional use, but it's slow and expensive to run, and it's hard to analyse many past orders. |
| **Call the GraphQL API that tesco.com itself uses** (`xapi.tesco.com`) with your own signed-in session | **Chosen.** It's fast and structured, and orders, basket and search all work. |

This project uses [`basketeer`](https://github.com/tobyandrews1985/basketeer), an actively maintained,
MIT-licensed, open-source TypeScript SDK for that API. It talks only to `tesco.com` hosts. It is
throttled to 1 request a second and stops on rate-limit errors. On top of it, this folder adds a small
MCP server shaped for the job:

* Order-history tools, including a **"usual purchases"** analysis (how often you buy each product,
  your typical quantity, when you last bought it). Claude uses this to pick *your* milk, not just any milk.
* Basket tools that add several items in one go.
* **No checkout, payment, slot-booking or order-cancel tools at all.** Claude fills the basket. You
  review it and pay on tesco.com or in the app.

### Authentication: you sign in yourself, once

You never give your password to Claude or to this server. Tesco's sign-in is protected by Akamai bot
checks that only a real browser passes, so:

1. `npm run login` opens a real Google Chrome window on the Tesco login page.
2. You sign in normally (including any 2-step code), then press Enter in the terminal.
3. The session (an access token and cookies) is saved to `~/.basketeer/session.json`. A dedicated
   Chrome profile is kept in `~/.basketeer/chrome-profile`.

After that, every request is plain HTTP. The access token lasts about an hour. When it expires, a
Chrome window pops up briefly to refresh it, using the saved profile. The underlying session lasts
about **30 days**, after which you run `npm run login` again.

`session.json` holds a live login token in plain text. Treat it like a password: don't commit it,
share it or put it in cloud storage.

**It must run on your own device**: a computer with Google Chrome, or an Android phone (see
[Running on an Android phone](#running-on-an-android-phone)). Tesco blocks sign-in from cloud and
datacenter IP addresses, so it won't work from a cloud server or a Claude web session.

## Setup (computer)

Requires Node.js 18+ and Google Chrome.

```bash
cd tesco-assistant
npm install
npm run login          # sign in to Tesco in the Chrome window, then press Enter
```

### Connect to Claude Desktop

Edit `claude_desktop_config.json`. In Claude Desktop, go to Settings → Developer → Edit Config.

```json
{
  "mcpServers": {
    "tesco": {
      "command": "node",
      "args": ["/full/path/to/tesco-assistant/server.mjs"]
    }
  }
}
```

Restart Claude Desktop. On Windows, use a path like `"C:\\Users\\you\\tesco-assistant\\server.mjs"`.

### Connect to Claude Code

```bash
claude mcp add tesco -- node /full/path/to/tesco-assistant/server.mjs
```

## Option C: Claude Code on an Android phone (advanced, not recommended)

The Claude Android app can't run local add-ons like this one, and an automated Chrome doesn't run on
Android. So on a phone you run **Claude Code** in a terminal app, and you sign in to Tesco by copying
the login cookies from your phone's browser.

**The catch:** Tesco's login token lasts about **1 hour**, and on a phone it can't be refreshed
automatically. So each time you sit down to shop, you re-copy the cookies first (about a minute).
One hour is plenty for a shopping session.

### One-time setup

1. **Install Termux** from [F-Droid](https://f-droid.org/packages/com.termux/). The Play Store version
   is out of date.
2. **Install Ubuntu inside Termux.** Claude Code has no native Android build, so it runs in Ubuntu:
   ```bash
   pkg update && pkg install -y proot-distro
   proot-distro install ubuntu
   proot-distro login ubuntu
   ```
   From now on, run `proot-distro login ubuntu` each time you open Termux.
3. **Inside Ubuntu, install Node.js, git and Claude Code**, then sign in to Claude:
   ```bash
   apt update && apt install -y nodejs npm git curl
   node --version                                   # needs v18 or newer
   curl -fsSL https://claude.ai/install.sh | bash   # official Claude Code installer
   claude                                           # sign in with your Claude account, then /exit
   ```
4. **Get this project and install it:**
   ```bash
   git clone -b ccr-56360ad8-iesdrj https://github.com/euphoniac76-code/BeebEm-for-Android ~/BeebEm-for-Android
   cd ~/BeebEm-for-Android/tesco-assistant
   npm install --omit=optional        # skips Playwright, which isn't needed on a phone
   ```
   If the repository is private, git asks for your GitHub username and a
   [personal access token](https://github.com/settings/tokens) as the password.
5. **Tell Claude Code about it:**
   ```bash
   claude mcp add tesco -e TESCO_AUTH=cookies -- node ~/BeebEm-for-Android/tesco-assistant/server.mjs
   ```
6. **Install a cookie extension in Firefox for Android.** Install
   [Firefox](https://play.google.com/store/apps/details?id=org.mozilla.firefox), then add a
   cookie-export add-on from its Add-ons menu, such as **Cookie-Editor**. Chrome for Android doesn't
   support extensions, which is why this step uses Firefox.

### Each time you shop

1. In Firefox, go to **www.tesco.com** and make sure you're signed in. Sign in normally if not,
   including any 2-step code.
2. Open the cookie add-on (Firefox menu → Add-ons → Cookie-Editor) → **Export** → **JSON**. This
   copies the cookies to the clipboard.
3. In Termux (inside Ubuntu), save them and import them:
   ```bash
   cd ~/BeebEm-for-Android/tesco-assistant
   cat > ~/tesco-cookies.json      # long-press → Paste, then press Enter, then Ctrl+D
   npm run import-cookies -- ~/tesco-cookies.json
   rm ~/tesco-cookies.json
   ```
   It tells you how many minutes the login is valid for.
4. Run `claude` and ask, for example: *"We're out of milk, bread and bin bags. Add my usual ones to my
   Tesco basket."*
5. When you're done, check out in the Tesco app or on tesco.com as normal.

If Claude reports that the session has expired, repeat steps 1–3. You don't need to restart Claude.

> **Easier option if you also have a computer at home.** Set it up on the computer instead (see
> Setup above). Then use Claude Code's Remote Control to drive that computer from the Claude app on
> your phone. The login then refreshes automatically for about 30 days, with no cookie copying.

## Tools Claude gets

| Tool | What it does |
| --- | --- |
| `tesco_order_history` | Past orders: number, date, total, item count (optionally all items) |
| `tesco_order_details` | Every item in one past order |
| `tesco_upcoming_orders` | Orders not yet delivered, with their amend deadline |
| `tesco_usual_purchases` | What you buy most often, typical quantity, last bought (filterable, e.g. "milk") |
| `tesco_favourites` | Your saved favourites on tesco.com |
| `tesco_search` | Catalogue search with price, unit price, availability and Clubcard/offer text |
| `tesco_basket_get` | Current basket with product names and guide price |
| `tesco_basket_add_items` | Add (or set exact quantities of) one or more products |
| `tesco_basket_remove` | Remove a product from the basket |

## Things to ask Claude

* "What have I bought from Tesco in the last two months? Any patterns?"
* "We're out of milk, bread, and washing-up liquid. Add my usual ones."
* "I'm making a lasagne for 6 on Saturday. Add what I need, using brands I normally buy."
* "Add everything from my last order except the wine."
* "What's in my basket and how much is it?"

Then open tesco.com or the Tesco app, check the basket, book a slot and pay.

## Troubleshooting

* **"session missing or expired"**: on a computer, run `npm run login` again. On a phone, re-copy
  the cookies (see "Each time you shop").
* **"No OAuth.AccessToken + UUID cookies"** when importing: you weren't signed in when you exported,
  or the add-on exported only some cookies. Reload www.tesco.com while signed in and export all
  cookies for the site.
* **"Chrome channel not found"**: install Google Chrome, or run `npx playwright install chrome`.
* **`ApiKeyError`**: Tesco rotated the public web API key. Update basketeer (`npm update basketeer`).
  Or copy the `x-apikey` header from a request to `xapi.tesco.com` in your browser's dev tools, and set
  it as `TESCO_API_KEY` in the MCP config's `env`.
* **Something stops working after a Tesco site update**: the API is unofficial and can change.
  Run `npm update basketeer` and check the [basketeer issues](https://github.com/tobyandrews1985/basketeer/issues).

## Want more?

basketeer also ships its own broader MCP server (`npx -p basketeer basketeer-mcp`). It has
delivery-slot booking, order amend and cancel, nutrition search and a checkout hand-off. It's left out
here on purpose to keep Claude's powers to "read orders and fill the basket".
