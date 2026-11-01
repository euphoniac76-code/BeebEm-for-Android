---
name: tesco-shopping
description: Help with the user's Tesco grocery shopping using their past Tesco orders, which are read from the Tesco receipt emails in their Gmail. Use when the user asks what they usually buy at Tesco, asks about past Tesco orders or spending, or wants items added to their Tesco basket or a Tesco shopping list built ("we're out of milk", "add my usual bread", "get what I need for a lasagne", "reorder last week's fruit").
---

# Tesco shopping from Gmail receipts

The user's past Tesco orders are in Gmail as receipt emails, each with a full itemised list. Tesco
has no public API, and you have no direct access to the Tesco account. So:

- **Read** past orders from Gmail (the Gmail connector).
- **Add to basket** by giving the user one tappable Tesco link per item. Each link opens that exact
  product's search on Tesco (the Tesco app if installed, otherwise the website, where the user is
  already signed in). The user taps **Add** once per item.

Never say items have been added to the basket. You can't add them. You give links, and the user taps Add.

## Finding orders in Gmail

All searches use `from:no-reply@mail.tesco.com`. Don't filter on a "to:" address, because the
emails may be forwarded from another address.

| What | Gmail query |
| --- | --- |
| Delivered orders (what was actually received) | `from:no-reply@mail.tesco.com subject:receipt` |
| Receipts containing a product | `from:no-reply@mail.tesco.com subject:receipt "semi skimmed"` |
| Upcoming order (placed, not yet delivered) | `from:no-reply@mail.tesco.com subject:confirmed` plus the latest `subject:"made changes"` email for that order number |

Search results come newest first. Read emails with `get_message` and `messageFormat: PLAIN_TEXT`.
Each receipt is large (about 30 KB), so be economical:

- **One product** ("my usual milk"): use the full-text query with 1–3 distinctive words, and read only
  the **newest 1–2** matching receipts. The result count shows how often it's bought (each receipt is
  one order).
- **"What do I usually buy" or a full restock**: read the **newest 3–5** receipts and treat items that
  appear in most of them as staples. Only read more if the user asks for a longer view.
- **A whole past order**: find it by date or order number (for example `"6831-8942-482"`) and read that one email.

## Reading a receipt

The plain-text body contains markdown tables:

- Item rows look like `| 2 | Tesco British Semi Skimmed Milk 2.272L, 4 Pints | £1.65 | £3.20 | -£0.10 |`.
  The columns are quantity, product, unit price, line total after offers, and amount saved.
- Ignore the `Was £x, now £y` rows, the section labels (`Fridge`, `Freezer`, `Cupboard`), and the `†`/`‡` VAT markers.
- **Substitutions**: an item followed by `Substituted with:` and another item. The first item is what
  the user *chose*, so use it as their preference. The substitute is only what they happened to receive.
- **Loose produce** has a weight in the name, such as `Tesco Bananas Loose 0.556KG`. Drop the weight
  when searching (`Tesco Bananas Loose`).
- Delivery date: the `### Delivery ...` line. Total: `Total paid`.
- Never repeat the address, phone number, card details or Clubcard balance back to the user unless they ask.

## Turning a need into a basket list

1. Work out the items. For a recipe or occasion, list the ingredients and scale them to the number of
   people. Skip store-cupboard basics they've bought recently unless asked, and mention which ones you skipped.
2. For each item, check Gmail for what they normally buy, and use **that exact product name** and
   their **usual quantity**.
3. If they've never bought it, choose a sensible Tesco own-brand product and mark it **(new)**.
4. Check whether an upcoming order already contains it. If so, say so instead of adding it again.
5. Reply with the list in this format:

```
**Tap each item, then tap Add on the top result.** Use + for more than 1.

1. [Tesco British Semi Skimmed Milk 2.272L, 4 Pints](https://www.tesco.com/groceries/en-GB/search?query=Tesco%20British%20Semi%20Skimmed%20Milk%202.272L%204%20Pints) × 2 · usually £1.65
2. [Hovis Soft White Medium Sliced Bread 800g](https://www.tesco.com/groceries/en-GB/search?query=Hovis%20Soft%20White%20Medium%20Sliced%20Bread%20800g) × 1 · usually £1.25
3. [Tesco Lasagne Sheets 250G](https://www.tesco.com/groceries/en-GB/search?query=Tesco%20Lasagne%20Sheets%20250G) × 1 · (new)

Rough total: £6.80. When you're done, check out in the Tesco app as normal.
```

Link rules:
- The URL is `https://www.tesco.com/groceries/en-GB/search?query=` followed by the product name,
  URL-encoded (spaces → `%20`). Remove commas, `&` (or encode it as `%26`), `†`, `‡` and loose-produce weights.
- Use the full product name from the receipt, so the right product is the top result.
- Always put the quantity next to the link. The user sets it with + on the Tesco page.
- The prices are what the user paid last time. Call the total "rough", because prices change.

Keep the reply short. Put the list first, then one line of notes (skipped items, anything already in
an upcoming order).
