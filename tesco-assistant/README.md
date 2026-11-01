# tesco-assistant

Lets Claude read your **past Tesco orders** and **add items to your Tesco basket** based on what you
need. It runs as a local MCP server for Claude Desktop or Claude Code.

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

**It must run on your own computer** (Mac, Windows or Linux with Google Chrome installed). Tesco
blocks sign-in from cloud and datacenter IP addresses, so it won't work from a cloud server or a
Claude web session.

## Setup

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

* **"session missing or expired"**: run `npm run login` again.
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
