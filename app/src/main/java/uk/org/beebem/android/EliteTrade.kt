package uk.org.beebem.android

import kotlin.math.abs
import kotlin.math.sqrt

// ── Elite trade advice ─────────────────────────────────────────────────────
//
// Reads the commander's state from the emulated BBC's memory and recommends
// what to buy here and where to sell it. Everything follows BBC Micro disc
// Elite's own code (elite.bbcelite.com, Mark Moxon's annotated source):
//
//   * Prices (TT151): (base + (QQ26 AND mask) ± economy × factor) mod 256,
//     times 4 for tenths of a credit. Elite buys and sells at the same price.
//   * QQ26 is a random byte rolled on arrival from hyperspace (GVL), so the
//     prices here are exact but a destination's are only known as a range.
//   * Systems come from the galaxy seeds by Elite's twist (TT54, TT20), with
//     economy and government from TT24 and names from cpl.
//   * Distances follow TT111; cargo limits follow tnpr.

/** One of Elite's 17 market items, from the QQ23 table. */
class EliteItem(
    val name: String,
    val basePrice: Int,
    val factor: Int,        // economic factor: signed, × economy
    val unit: String,       // "t", "kg" or "g"
    val mask: Int,          // AND'd with QQ26 to vary the price
    val illegal: Boolean = false,
)

val ELITE_ITEMS = listOf(
    EliteItem("FOOD", 19, -2, "t", 0x01),
    EliteItem("TEXTILES", 20, -1, "t", 0x03),
    EliteItem("RADIOACTIVES", 65, -3, "t", 0x07),
    EliteItem("SLAVES", 40, -5, "t", 0x1F, illegal = true),
    EliteItem("LIQUOR/WINES", 83, -5, "t", 0x0F),
    EliteItem("LUXURIES", 196, 8, "t", 0x03),
    EliteItem("NARCOTICS", 235, 29, "t", 0x78, illegal = true),
    EliteItem("COMPUTERS", 154, 14, "t", 0x03),
    EliteItem("MACHINERY", 117, 6, "t", 0x07),
    EliteItem("ALLOYS", 78, 1, "t", 0x1F),
    EliteItem("FIREARMS", 124, 13, "t", 0x07, illegal = true),
    EliteItem("FURS", 176, -9, "t", 0x3F),
    EliteItem("MINERALS", 32, -1, "t", 0x03),
    EliteItem("GOLD", 97, -1, "kg", 0x07),
    EliteItem("PLATINUM", 171, -2, "kg", 0x1F),
    EliteItem("GEM-STONES", 45, -1, "g", 0x0F),
    EliteItem("ALIEN ITEMS", 53, 15, "t", 0x07),
)

val ELITE_ECONOMIES = listOf(
    "RICH INDUSTRIAL", "AVERAGE INDUSTRIAL", "POOR INDUSTRIAL", "MAINLY INDUSTRIAL",
    "MAINLY AGRICULTURAL", "RICH AGRICULTURAL", "AVERAGE AGRICULTURAL", "POOR AGRICULTURAL",
)

val ELITE_GOVERNMENTS = listOf(
    "ANARCHY", "FEUDAL", "MULTI-GOVERNMENT", "DICTATORSHIP",
    "COMMUNIST", "CONFEDERACY", "DEMOCRACY", "CORPORATE STATE",
)

/** Price of an item in tenths of a credit, for a system's economy and market byte (TT151). */
fun elitePrice(item: EliteItem, economy: Int, random: Int): Int {
    val change = economy * abs(item.factor)
    val p = item.basePrice + (random and item.mask) + if (item.factor < 0) -change else change
    return (p and 0xFF) * 4
}

class EliteSystem(
    val index: Int,
    val name: String,
    val x: Int,
    val y: Int,
    val economy: Int,
    val government: Int,
) {
    val dangerous get() = government <= 1     // anarchy or feudal
}

private const val NAME_PAIRS = "ALLEXEGEZACEBISOUSESARMAINDIREA?ERATENBERALAVETIEDORQUANTEISRION"

/** Elite's twist (TT54): the next step of the seeds' Fibonacci-like sequence. */
private fun twist(s: IntArray) {
    val w0 = s[0] or (s[1] shl 8)
    val w1 = s[2] or (s[3] shl 8)
    val w2 = s[4] or (s[5] shl 8)
    val w3 = (w0 + w1 + w2) and 0xFFFF
    s[0] = s[2]; s[1] = s[3]; s[2] = s[4]; s[3] = s[5]
    s[4] = w3 and 0xFF; s[5] = w3 shr 8
}

/** The 256 systems of a galaxy, from its six seed bytes (QQ21). */
fun eliteGalaxy(seeds: IntArray): List<EliteSystem> {
    val s = seeds.copyOf()
    return List(256) { i ->
        var economy = s[1] and 7
        val government = (s[2] shr 3) and 7
        if (government <= 1) economy = economy or 2
        // Name (cpl): three or four letter pairs, from s2_hi of successive twists.
        val n = s.copyOf()
        val sb = StringBuilder()
        repeat(if (n[0] and 0x40 != 0) 4 else 3) {
            val pair = n[5] and 0x1F
            if (pair != 0) sb.append(NAME_PAIRS, pair * 2, pair * 2 + 2)
            twist(n)
        }
        val system = EliteSystem(i, sb.toString().replace("?", ""), s[3], s[1], economy, government)
        repeat(4) { twist(s) }      // TT20: on to the next system
        system
    }
}

/** Distance in tenths of a light year (TT111): y counts half, floor of the root, × 4. */
fun eliteDistance(x0: Int, y0: Int, x1: Int, y1: Int): Int {
    val dx = abs(x1 - x0)
    val dy = abs(y1 - y0) / 2
    return sqrt((dx * dx + dy * dy).toDouble()).toInt() * 4
}

/** The commander's state, as read from Elite's workspace. */
class EliteState(
    val docked: Boolean,
    val inWitchspace: Boolean,
    val x: Int,
    val y: Int,
    val galaxySeeds: IntArray,
    val galaxy: Int,
    val cash: Long,             // tenths of a credit
    val fuel: Int,              // tenths of a light year
    val cargoBay: Int,          // tonnes (CRGO less 2)
    val hold: IntArray,         // units of each item
    val available: IntArray,    // units of each item for sale here
    val marketRandom: Int,      // QQ26
    val economy: Int,           // QQ28
)

// Addresses in disc Elite (the same in the docked and flight code).
private const val QQ12 = 0x008E     // docked flag: &FF docked
private const val WORKSPACE = 0x0300
private const val MJ = 0x0341       // in witchspace
private const val QQ0 = 0x0359      // galactic x, then QQ1 y
private const val QQ21 = 0x035B     // galaxy seeds
private const val CASH = 0x0361     // 4 bytes, big-endian, tenths
private const val QQ14 = 0x0365     // fuel
private const val GCNT = 0x0367
private const val CRGO = 0x036E     // cargo bay + 2
private const val QQ20 = 0x036F     // hold, 17 bytes
private const val AVL = 0x038D      // availability, 17 bytes
private const val QQ26 = 0x039E     // market random byte
private const val QQ28 = 0x03AC     // economy
private const val WORKSPACE_END = 0x03B8

/**
 * The commander's state from BBC memory, or null if it doesn't look like disc
 * Elite (another version keeps these elsewhere, or the game hasn't started).
 */
fun readEliteState(read: (addr: Int, len: Int) -> ByteArray?): EliteState? {
    val zp = read(QQ12, 1) ?: return null
    val ws = read(WORKSPACE, WORKSPACE_END - WORKSPACE) ?: return null
    fun b(addr: Int) = ws[addr - WORKSPACE].toInt() and 0xFF
    fun bytes(addr: Int, n: Int) = IntArray(n) { b(addr + it) }

    val galaxy = b(GCNT)
    val fuel = b(QQ14)
    val crgo = b(CRGO)
    val economy = b(QQ28)
    if (galaxy > 7 || fuel > 70 || crgo !in 22..37 || economy > 7) return null
    val state = EliteState(
        docked = zp[0].toInt() != 0,
        inWitchspace = b(MJ) != 0,
        x = b(QQ0),
        y = b(QQ0 + 1),
        galaxySeeds = bytes(QQ21, 6),
        galaxy = galaxy,
        cash = (b(CASH).toLong() shl 24) or (b(CASH + 1).toLong() shl 16) or
            (b(CASH + 2).toLong() shl 8) or b(CASH + 3).toLong(),
        fuel = fuel,
        cargoBay = crgo - 2,
        hold = bytes(QQ20, 17),
        available = bytes(AVL, 17),
        marketRandom = b(QQ26),
        economy = economy,
    )
    // The seeds must generate a system where we are, with the economy Elite
    // says this system has.
    val here = eliteGalaxy(state.galaxySeeds).firstOrNull { it.x == state.x && it.y == state.y }
    if (here == null || here.economy != economy) return null
    return state
}

/** A destination's sale price for an item: the range Elite may roll, and its average. */
class PriceRange(val min: Int, val max: Int, val average: Double)

fun elitePriceRange(item: EliteItem, economy: Int): PriceRange {
    val prices = IntArray(256) { elitePrice(item, economy, it) }
    return PriceRange(prices.min(), prices.max(), prices.average())
}

class SellAdvice(
    val item: Int,
    val units: Int,
    val price: Int,                 // here, tenths
    val sellHere: Boolean,
    val better: EliteSystem?,       // where it sells for more on average, if not here
    val betterPrice: PriceRange?,
)

class BuyAdvice(
    val item: Int,
    val units: Int,
    val price: Int,                 // here, tenths per unit
    val destination: EliteSystem,
    val distance: Int,              // tenths of a light year
    val sale: PriceRange,           // tenths per unit there
) {
    val profitMin get() = units.toLong() * (sale.min - price)
    val profitMax get() = units.toLong() * (sale.max - price)
    val profitAverage get() = units * (sale.average - price)
}

class TradeAdvice(
    val here: EliteSystem,
    val range: Int,                 // tenths of a light year
    val cash: Long,                 // after the recommended sales
    val tonnesFree: Int,            // after the recommended sales
    val sell: List<SellAdvice>,
    val buy: List<BuyAdvice>,       // alternatives, best first
)

// Elite measures distances to a tenth of a light year and holds 7.0 of fuel.
private const val FULL_TANK = 70

/**
 * Trade advice for the commander. Docked, the range assumes a full tank (fuel
 * is on sale here); in flight it is the fuel left. Cargo in the hold is
 * recommended for sale here unless some system in range pays more on average,
 * and the purchases assume those sales have been made.
 */
fun eliteTradeAdvice(state: EliteState, includeIllegal: Boolean, maxOptions: Int = 3): TradeAdvice? {
    val galaxy = eliteGalaxy(state.galaxySeeds)
    val here = galaxy.firstOrNull { it.x == state.x && it.y == state.y } ?: return null
    val range = if (state.docked) FULL_TANK else state.fuel
    val reachable = galaxy.mapNotNull { sys ->
        val d = eliteDistance(here.x, here.y, sys.x, sys.y)
        if (sys !== here && d in 1..range) sys to d else null
    }
    val pricesHere = IntArray(17) { elitePrice(ELITE_ITEMS[it], state.economy, state.marketRandom) }
    fun bestElsewhere(item: Int) = reachable
        .map { (sys, _) -> sys to elitePriceRange(ELITE_ITEMS[item], sys.economy) }
        .maxByOrNull { it.second.average }

    // What to do with the cargo already in the hold.
    val hold = state.hold.copyOf()
    var cash = state.cash
    val sell = (0 until 17).filter { state.hold[it] > 0 }.map { item ->
        val best = bestElsewhere(item)
        val sellHere = best == null || pricesHere[item] >= best.second.average
        if (sellHere) {
            cash += state.hold[item].toLong() * pricesHere[item]
            hold[item] = 0
        }
        SellAdvice(item, state.hold[item], pricesHere[item], sellHere,
            if (sellHere) null else best?.first, if (sellHere) null else best?.second)
    }

    // tnpr: items 0-12 share the bay; the others are limited to 199 units each.
    val tonnesFree = (state.cargoBay - (0..12).sumOf { hold[it] }).coerceAtLeast(0)
    val buy = (0 until 17).mapNotNull { item ->
        val goods = ELITE_ITEMS[item]
        val price = pricesHere[item]
        if (state.available[item] == 0 || price == 0 || (goods.illegal && !includeIllegal)) return@mapNotNull null
        val space = if (item <= 12) tonnesFree else 199 - hold[item]
        val units = minOf(state.available[item].toLong(), cash / price, space.toLong()).toInt()
        if (units <= 0) return@mapNotNull null
        // The best place to sell this item, by average profit.
        reachable.map { (sys, d) -> BuyAdvice(item, units, price, sys, d, elitePriceRange(goods, sys.economy)) }
            .filter { it.sale.average > price }
            .maxByOrNull { it.profitAverage }
    }.sortedByDescending { it.profitAverage }.take(maxOptions)

    return TradeAdvice(here, range, cash, tonnesFree, sell, buy)
}

/** Tenths of a credit (or light year) as Elite prints them, e.g. 1234 → "123.4". */
fun eliteTenths(v: Long): String = "${v / 10}.${abs(v % 10)}"
fun eliteTenths(v: Int): String = eliteTenths(v.toLong())

/** A system name as Elite prints it in sentence case: "Lave". */
fun eliteName(name: String): String = name.lowercase().replaceFirstChar { it.uppercase() }

// ── Buying and selling through Elite's own screens ─────────────────────────
//
// The BUY and SELL buttons press the keys a player would. f1 opens Buy Cargo
// (TT219), which asks "QUANTITY OF ...?" for each item in stock in turn;
// f2 opens Sell Cargo (TT208, TT210), which asks "SELL (Y/N)?" for each item
// in the hold. Items it doesn't ask about are passed without a question.
// Either way RETURN on its own answers none, and digits then RETURN that many
// (gnum). Elite ignores a key that is already down when it starts waiting
// (TT217) and pauses for a second after a purchase, so rather than guess
// timings we watch its item counter QQ29 and press a key only once it has
// stayed put, i.e. Elite is waiting.

private const val QQ11 = 0x0087     // view type: 2 = Buy Cargo, 4 = Sell Cargo
private const val QQ29 = 0x03AD     // item being asked about
private const val VIEW_BUY = 2
private const val VIEW_SELL = 4

private const val KEY_HOLD_MS = 150L        // long enough for Elite's key scan
private const val DIGIT_GAP_MS = 500L       // between the digits of a quantity
private const val SETTLED_MS = 400L         // QQ29 unchanged this long = waiting
private const val POLL_MS = 50L

class EliteTradeResult(val ok: Boolean, val message: String)

/** Watching Elite's memory and pressing its keys, for eliteBuy and eliteSell. */
private class EliteKeys(
    val read: (addr: Int, len: Int) -> ByteArray?,
    val press: suspend (label: String, holdMs: Long) -> Unit,
) {
    fun byte(addr: Int) = read(addr, 1)?.let { it[0].toInt() and 0xFF } ?: -1
    fun held(item: Int) = byte(QQ20 + item)

    suspend fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        var t = 0L
        while (!cond()) {
            if (t >= timeoutMs) return false
            kotlinx.coroutines.delay(POLL_MS); t += POLL_MS
        }
        return true
    }

    /** The item Elite is waiting on: QQ29 unchanged for SETTLED_MS. */
    suspend fun settledItem(timeoutMs: Long): Int? {
        var last = byte(QQ29); var still = 0L; var t = 0L
        while (t < timeoutMs) {
            kotlinx.coroutines.delay(POLL_MS); t += POLL_MS
            val now = byte(QQ29)
            if (now == last) { still += POLL_MS; if (still >= SETTLED_MS) return now } else { last = now; still = 0 }
        }
        return null
    }

    /** Opens a screen with its f key; false if Elite didn't switch to it. */
    suspend fun open(key: String, view: Int): Boolean {
        press(key, KEY_HOLD_MS)
        if (!waitFor(3000) { byte(QQ11) == view }) return false
        // Elite draws the headings before it resets QQ29, so let it get going.
        kotlinx.coroutines.delay(800)
        return true
    }

    /** Answers the question with a quantity: its digits, then RETURN. */
    suspend fun enter(amount: Int) {
        for (digit in amount.toString()) {
            press(digit.toString(), KEY_HOLD_MS)
            kotlinx.coroutines.delay(DIGIT_GAP_MS)
        }
        press("RET", KEY_HOLD_MS)
    }

    suspend fun skip() = press("RET", KEY_HOLD_MS)
}

private val STOPPED = EliteTradeResult(false, "Elite stopped responding. Check the screen.")

/**
 * Buys [units] of [item] at the docked station by driving Elite's Buy Cargo
 * screen, then answers the remaining questions with RETURN so Elite finishes
 * on the Inventory screen. [press] holds a BBC key (picker label) briefly.
 * The amount is capped to what the cash, hold and station allow right now.
 */
suspend fun eliteBuy(
    item: Int,
    units: Int,
    read: (addr: Int, len: Int) -> ByteArray?,
    press: suspend (label: String, holdMs: Long) -> Unit,
    onProgress: (String) -> Unit = {},
): EliteTradeResult {
    val goods = ELITE_ITEMS[item]
    val state = readEliteState(read) ?: return EliteTradeResult(false, "No Elite commander found.")
    if (!state.docked) return EliteTradeResult(false, "Dock at a station to buy.")
    val keys = EliteKeys(read, press)
    if (keys.byte(QQ11) == VIEW_BUY || keys.byte(QQ11) == VIEW_SELL)
        return EliteTradeResult(false, "Finish the Buy or Sell Cargo screen first.")

    // tnpr and LCASH: what Elite will accept now.
    val price = elitePrice(goods, state.economy, state.marketRandom)
    val space = if (item <= 12) state.cargoBay - (0..12).sumOf { state.hold[it] } else 199 - state.hold[item]
    val amount = minOf(units.toLong(), state.available[item].toLong(), space.toLong(),
        if (price > 0) state.cash / price else 0L).toInt()
    if (amount <= 0) return EliteTradeResult(false, when {
        state.available[item] == 0 -> "No ${goods.name} on sale here."
        space <= 0 -> "No room in the hold. Sell cargo first (f2)."
        else -> "Not enough cash for ${goods.name}."
    })
    val before = state.hold[item]

    onProgress("Opening Buy Cargo…")
    if (!keys.open("f1", VIEW_BUY)) return EliteTradeResult(false, "The Buy Cargo screen didn't open. Try again.")

    var bought = false
    var refused = false
    while (true) {
        val asking = keys.settledItem(5000) ?: return STOPPED
        if (keys.byte(QQ11) != VIEW_BUY || asking >= 17) break          // done: on to Inventory
        if (asking == item && !bought && !refused) {
            onProgress("Buying $amount${goods.unit} ${goods.name}…")
            keys.enter(amount)
            bought = keys.waitFor(3000) { keys.held(item) == before + amount }
            if (!bought) {
                // Elite said "Cash?" or "Cargo?" and asks again: buy none.
                refused = true
                keys.settledItem(3000)
                keys.skip()
            }
        } else {
            onProgress(if (bought) "Finishing…" else "Skipping ${ELITE_ITEMS.getOrNull(asking)?.name ?: "item"}…")
            keys.skip()
        }
        // Wait for Elite to move on (after a purchase it pauses for a second).
        if (!keys.waitFor(4000) { keys.byte(QQ29) != asking || keys.byte(QQ11) != VIEW_BUY }) return STOPPED
    }
    return when {
        bought -> EliteTradeResult(true, "Bought $amount${goods.unit} ${goods.name}.")
        refused -> EliteTradeResult(false, "Elite refused the purchase. Nothing bought.")
        else -> EliteTradeResult(false, "${goods.name} wasn't offered. Nothing bought.")
    }
}

/**
 * Sells all of each of [items] in the hold at the docked station by driving
 * Elite's Sell Cargo screen, keeping the rest of the cargo (RETURN), so Elite
 * finishes on the Inventory screen.
 *
 * Unlike Buy Cargo, QQ29 stays on the last item after the last question while
 * Elite beeps and pauses, so an item counts as asked about only if it is in
 * the hold and not yet answered.
 */
suspend fun eliteSell(
    items: Set<Int>,
    read: (addr: Int, len: Int) -> ByteArray?,
    press: suspend (label: String, holdMs: Long) -> Unit,
    onProgress: (String) -> Unit = {},
): EliteTradeResult {
    val state = readEliteState(read) ?: return EliteTradeResult(false, "No Elite commander found.")
    if (!state.docked) return EliteTradeResult(false, "Dock at a station to sell.")
    val keys = EliteKeys(read, press)
    if (keys.byte(QQ11) == VIEW_BUY || keys.byte(QQ11) == VIEW_SELL)
        return EliteTradeResult(false, "Finish the Buy or Sell Cargo screen first.")
    val toSell = items.filter { state.hold[it] > 0 }.toSet()
    if (toSell.isEmpty()) return EliteTradeResult(false, "Nothing to sell.")

    onProgress("Opening Sell Cargo…")
    if (!keys.open("f2", VIEW_SELL)) return EliteTradeResult(false, "The Sell Cargo screen didn't open. Try again.")

    val answered = mutableSetOf<Int>()
    val sold = mutableListOf<String>()
    var refused = false
    while (true) {
        val asking = keys.settledItem(5000) ?: return STOPPED
        if (keys.byte(QQ11) != VIEW_SELL) break                         // done: on to Inventory
        val question = asking in 0..16 && asking !in answered && keys.held(asking) > 0
        if (question) {
            val goods = ELITE_ITEMS[asking]
            val units = keys.held(asking)
            if (asking in toSell) {
                onProgress("Selling $units${goods.unit} ${goods.name}…")
                keys.enter(units)
                if (keys.waitFor(3000) { keys.held(asking) == 0 }) sold += "$units${goods.unit} ${goods.name}"
                else refused = true
            } else {
                onProgress("Keeping ${goods.name}…")
                keys.skip()
            }
            answered += asking
        }
        // Wait for Elite to move on to the next question, or to finish.
        if (!keys.waitFor(4000) { keys.byte(QQ29) != asking || keys.byte(QQ11) != VIEW_SELL }) return STOPPED
    }
    val after = readEliteState(read)
    val earned = if (after != null) " for ${eliteTenths(after.cash - state.cash)} CR" else ""
    return when {
        sold.isEmpty() -> EliteTradeResult(false, "Elite didn't take the sale. Nothing sold.")
        refused -> EliteTradeResult(false, "Sold ${sold.joinToString()}$earned, but not everything.")
        else -> EliteTradeResult(true, "Sold ${sold.joinToString()}$earned.")
    }
}
