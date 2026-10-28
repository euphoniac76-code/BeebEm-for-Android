package uk.org.beebem.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Elite cockpit overlay ──────────────────────────────────────────────────
//
// Touch buttons for Elite's less common keys, in the black bands either side
// of the picture (never over it). The controller flies the ship (see
// ELITE_MAPPING); these cover views, combat, navigation, trading and game
// keys, grouped by use. Key meanings are from Elite's own source
// (elite.bbcelite.com: KYTB flight keys, TT102 function keys, hyp for CTRL-H).
// The function keys mean different things when docked, so those buttons show
// both: e.g. f1 is the rear view in flight and Buy Cargo when docked.

private data class EliteKey(
    val label: String,          // what it does
    val keys: List<String>,     // BBC keys pressed together (picker labels), e.g. CTL + H
    val hint: String,           // the BBC key(s), as printed
    val docked: String? = null, // its meaning when docked, if different
    val sequence: List<String>? = null,  // keys tapped one after another, instead of held
    val action: EliteAction? = null,     // an app feature instead of BBC keys
)

private enum class EliteAction { MANUAL, TRADE, SAVE }

private data class EliteGroup(val title: String, val keys: List<EliteKey>, val columns: Int = 2)

private val LEFT_GROUPS = listOf(
    EliteGroup("VIEWS", listOf(
        EliteKey("FRONT", listOf("f0"), "f0", docked = "LAUNCH"),
        EliteKey("REAR", listOf("f1"), "f1", docked = "BUY"),
        EliteKey("LEFT", listOf("f2"), "f2", docked = "SELL"),
        EliteKey("RIGHT", listOf("f3"), "f3", docked = "EQUIP"),
    )),
    EliteGroup("COMBAT", listOf(
        EliteKey("TARGET MSL", listOf("T"), "T"),
        EliteKey("FIRE MSL", listOf("M"), "M"),
        EliteKey("UNARM MSL", listOf("U"), "U"),
        EliteKey("E.C.M.", listOf("E"), "E"),
        EliteKey("ENERGY BOMB", listOf("TAB"), "TAB"),
        EliteKey("ESCAPE POD", listOf("ESC"), "ESC"),
    )),
    EliteGroup("FLIGHT AIDS", listOf(
        EliteKey("DOCK COMP", listOf("C"), "C"),
        EliteKey("DOCK OFF", listOf("P"), "P"),
        EliteKey("IN-SYS JUMP", listOf("J"), "J"),
        // Elite switches between keyboard and joystick flight with K while
        // paused (COPY to pause, DELETE to resume); this taps all three.
        EliteKey("STICK MODE", emptyList(), "COPY K DEL", sequence = listOf("COPY", "K", "DEL")),
    )),
)

private val RIGHT_GROUPS = listOf(
    EliteGroup("NAVIGATION", listOf(
        EliteKey("GALACTIC CHART", listOf("f4"), "f4"),
        EliteKey("LOCAL CHART", listOf("f5"), "f5"),
        EliteKey("HYPER SPACE", listOf("H"), "H"),
        EliteKey("GALACTIC HYP", listOf("CTL", "H"), "CTRL+H"),
        EliteKey("DISTANCE", listOf("D"), "D"),
        EliteKey("HOME", listOf("O"), "O"),
    )),
    EliteGroup("CHART CURSOR", listOf(
        EliteKey("◄", listOf("←"), ""),
        EliteKey("▲", listOf("↑"), ""),
        EliteKey("▼", listOf("↓"), ""),
        EliteKey("►", listOf("→"), ""),
    ), columns = 4),
    EliteGroup("DATA", listOf(
        EliteKey("SYSTEM", listOf("f6"), "f6"),
        EliteKey("MARKET", listOf("f7"), "f7"),
        EliteKey("STATUS", listOf("f8"), "f8"),
        EliteKey("CARGO", listOf("f9"), "f9"),
        EliteKey("TRADE ADVICE", emptyList(), "BEST BUYS", action = EliteAction.TRADE),
    )),
    EliteGroup("GAME", listOf(
        EliteKey("PAUSE", listOf("COPY"), "COPY"),
        EliteKey("RESUME", listOf("DEL"), "DEL"),
        // Saves to the current commander's file (eliteSaveCommander); the disc
        // menu itself is for loading another commander.
        EliteKey("SAVE CMDR", emptyList(), "@ 2 RETURN", action = EliteAction.SAVE),
        EliteKey("DISC MENU", listOf("@"), "@ (LOAD)"),
        EliteKey("MANUAL", emptyList(), "ORIGINAL", action = EliteAction.MANUAL),   // the flight training manual
    )),
)

// Elite's palette: yellow headings, cyan scanner lines, white text.
private val ELITE_YELLOW = Color(0xFFFFE000)
private val ELITE_CYAN   = Color(0xFF00E0FF)
private val ELITE_FONT   = FontFamily.Monospace

// Elite draws its screen in the middle 256 of the 320 pixels of its mode
// (32 of 40 character columns), so the panels may use the black margin of
// the BBC picture outside Elite's frame.
private const val ELITE_SCREEN_WIDTH = 256f / 320f

@Composable
fun EliteOverlay(pictureWidthFraction: Float, onManual: () -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val side = maxWidth * ((1f - pictureWidthFraction * ELITE_SCREEN_WIDTH) / 2f) - 6.dp
        if (side < 96.dp) return@BoxWithConstraints     // no room beside the picture
        val panelWidth = min(side, 220.dp)
        var showTrade by remember { mutableStateOf(false) }
        // A purchase, sale, refuel or save in progress, and its outcome.
        val scope = rememberCoroutineScope()
        var buying by remember { mutableStateOf(false) }
        var buyStatus by remember { mutableStateOf<String?>(null) }
        val read = { addr: Int, len: Int -> BeebEmNative.nativeReadMemory(addr, len) }
        val press: suspend (String, Long) -> Unit = { label, holdMs ->
            val k = bbcKey(label)
            BeebEmNative.nativeKeyDown(k.row, k.col)
            try { delay(holdMs) } finally { BeebEmNative.nativeKeyUp(k.row, k.col) }
        }
        fun trade(job: suspend (onProgress: (String) -> Unit) -> EliteTradeResult) {
            if (buying) return
            showTrade = false       // so the Elite screen being used can be seen
            buying = true
            scope.launch {
                val result = job { buyStatus = it }
                buyStatus = result.message
                buying = false
                delay(if (result.ok) 4000 else 8000)
                if (!buying) buyStatus = null
            }
        }
        val onBuy: (BuyAdvice) -> Unit = { b -> trade { eliteBuy(b.item, b.units, read, press, it) } }
        val onSell: (Set<Int>) -> Unit = { items -> trade { eliteSell(items, read, press, it) } }
        val onSellAllAndRefuel: () -> Unit = { trade { eliteSellAllAndRefuel(read, press, it) } }
        val onAction: (EliteAction) -> Unit = { action ->
            when (action) {
                EliteAction.MANUAL -> onManual()
                EliteAction.TRADE -> showTrade = !showTrade
                EliteAction.SAVE -> trade { eliteSaveCommander(read, press, it) }
            }
        }
        ElitePanel(LEFT_GROUPS, Modifier.align(Alignment.CenterStart).width(panelWidth).fillMaxHeight(), maxHeight, onAction)
        ElitePanel(RIGHT_GROUPS, Modifier.align(Alignment.CenterEnd).width(panelWidth).fillMaxHeight(), maxHeight, onAction)
        if (showTrade) {
            TradePanel(
                onClose = { showTrade = false },
                onBuy = onBuy,
                onSell = onSell,
                onSellAllAndRefuel = onSellAllAndRefuel,
                buying = buying,
                modifier = Modifier.align(Alignment.Center)
                    .widthIn(max = 460.dp)
                    .fillMaxWidth(pictureWidthFraction * ELITE_SCREEN_WIDTH)
                    .heightIn(max = maxHeight - 16.dp),
            )
        }
        buyStatus?.let { BuyBanner(it, buying, Modifier.align(Alignment.BottomCenter)) }
    }
}

@Composable
private fun ElitePanel(groups: List<EliteGroup>, modifier: Modifier, height: Dp, onAction: (EliteAction) -> Unit) {
    // Share the height between the button rows, after the group titles.
    val rows = groups.sumOf { (it.keys.size + it.columns - 1) / it.columns }
    val titleH = 17.dp
    val gap = 3.dp
    val rowH = ((height - 8.dp - titleH * groups.size - gap * (rows + groups.size)) / rows).coerceIn(22.dp, 46.dp)

    Column(
        modifier = modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically),
    ) {
        groups.forEach { group ->
            Text(
                "◤ ${group.title} ◢",
                color = ELITE_YELLOW.copy(alpha = 0.85f),
                fontSize = 9.sp,
                lineHeight = 12.sp,
                fontFamily = ELITE_FONT,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.fillMaxWidth().height(titleH),
                textAlign = TextAlign.Center,
            )
            group.keys.chunked(group.columns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(rowH),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    row.forEach { key -> EliteButton(key, Modifier.weight(1f).fillMaxHeight(), onAction) }
                    // A lone button on the last row spans it (TRADE ADVICE).
                    if (row.size > 1) repeat(group.columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun EliteButton(key: EliteKey, modifier: Modifier, onAction: (EliteAction) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val shape = CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp)
    Box(
        modifier = modifier
            .background(if (pressed) ELITE_CYAN.copy(alpha = 0.35f) else Color(0x66000814), shape)
            .border(1.dp, ELITE_CYAN.copy(alpha = if (pressed) 1f else 0.45f), shape)
            .pointerInput(key) {
                // Hold the BBC key(s) for as long as the button is held.
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    val bbc = key.keys.map { bbcKey(it) }
                    bbc.forEach { BeebEmNative.nativeKeyDown(it.row, it.col) }
                    val up = waitForUpOrCancellation()
                    bbc.asReversed().forEach { BeebEmNative.nativeKeyUp(it.row, it.col) }
                    pressed = false
                    if (key.keys.isEmpty() && up != null) {
                        val seq = key.sequence
                        if (key.action != null) onAction(key.action)
                        else if (seq != null) scope.launch {
                            // Tap each key long enough for the game to see it.
                            for (label in seq) {
                                val k = bbcKey(label)
                                BeebEmNative.nativeKeyDown(k.row, k.col)
                                delay(150)
                                BeebEmNative.nativeKeyUp(k.row, k.col)
                                delay(250)
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                key.label,
                color = Color.White.copy(alpha = if (pressed) 1f else 0.85f),
                fontSize = 9.sp,
                lineHeight = 10.sp,
                fontFamily = ELITE_FONT,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            if (key.hint.isNotEmpty()) {
                Text(
                    key.hint,
                    color = ELITE_YELLOW.copy(alpha = 0.7f),
                    fontSize = 7.sp,
                    lineHeight = 8.sp,
                    fontFamily = ELITE_FONT,
                    maxLines = 1,
                )
            }
            // Its meaning when docked, in cyan on its own line.
            key.docked?.let {
                Text(
                    "DOCKED: $it",
                    color = ELITE_CYAN.copy(alpha = 0.8f),
                    fontSize = 7.sp,
                    lineHeight = 8.sp,
                    fontFamily = ELITE_FONT,
                    maxLines = 1,
                )
            }
        }
    }
}

// ── Trade advice panel ─────────────────────────────────────────────────────
//
// Shows eliteTradeAdvice (EliteTrade.kt) for the commander's state in BBC
// memory, re-read every second so it follows purchases and sales.

private val ELITE_RED = Color(0xFFFF5050)

@Composable
private fun TradePanel(
    onClose: () -> Unit, onBuy: (BuyAdvice) -> Unit, onSell: (Set<Int>) -> Unit, onSellAllAndRefuel: () -> Unit,
    buying: Boolean, modifier: Modifier,
) {
    var includeIllegal by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<EliteState?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            state = readEliteState { addr, len -> BeebEmNative.nativeReadMemory(addr, len) }
            delay(1000)
        }
    }
    // On Data on System for another system, the advice is for a trip there.
    val advice = remember(state, includeIllegal) {
        state?.let { eliteTradeAdvice(it, includeIllegal, destination = eliteChosenDestination(it)) }
    }
    val shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp)

    Column(
        modifier = modifier
            .background(Color(0xF2000814), shape)
            .border(1.dp, ELITE_CYAN.copy(alpha = 0.8f), shape)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TradeText("◤ TRADE ADVICE ◢", ELITE_YELLOW, 12, bold = true, modifier = Modifier.weight(1f))
            TradeText("✕", Color.White, 16, modifier = Modifier.tap(onClose).padding(horizontal = 6.dp))
        }
        val st = state
        when {
            st == null -> TradeText(
                "No Elite commander found. Trade advice works with the BBC disc version " +
                    "of Elite once a game is under way.", Color.White, 10)
            st.inWitchspace -> TradeText("IN WITCHSPACE · no market here.", Color.White, 10)
            advice != null -> TradeAdviceBody(st, advice, includeIllegal, onBuy, onSell, onSellAllAndRefuel, buying) { includeIllegal = !includeIllegal }
        }
    }
}

@Composable
private fun TradeAdviceBody(
    st: EliteState, advice: TradeAdvice, includeIllegal: Boolean,
    onBuy: (BuyAdvice) -> Unit, onSell: (Set<Int>) -> Unit, onSellAllAndRefuel: () -> Unit,
    buying: Boolean, onToggleIllegal: () -> Unit,
) {
    val here = advice.here
    TradeText("${eliteName(here.name)} · ${ELITE_ECONOMIES[here.economy]} · ${ELITE_GOVERNMENTS[here.government]}",
        ELITE_CYAN, 10, bold = true)
    val trip = advice.destination
    if (trip != null) {
        Row {
            TradeText("TRIP TO ${eliteName(trip.name).uppercase()} · ${eliteTenths(advice.destinationDistance)} LY · " +
                "${ELITE_ECONOMIES[trip.economy]} · ${ELITE_GOVERNMENTS[trip.government]}", ELITE_YELLOW, 10, bold = true,
                modifier = Modifier.weight(1f, fill = false))
            if (trip.dangerous) TradeText(" ⚠ DANGER", ELITE_RED, 9, bold = true)
        }
        if (advice.destinationDistance > advice.range) {
            TradeText("Out of range: your ${if (st.docked) "full tank" else "fuel"} reaches ${eliteTenths(advice.range)} LY.",
                ELITE_RED, 9, bold = true)
        }
        TradeText("From Data on System. Leave that screen for every destination in range.",
            Color.White.copy(alpha = 0.6f), 8)
    }
    val held = (0..12).sumOf { st.hold[it] }
    TradeText("CASH ${eliteTenths(st.cash)} CR · HOLD ${held}/${st.cargoBay}t · FUEL ${eliteTenths(st.fuel)} LY",
        Color.White, 9)
    // Docked, the advice plans for a full tank, as fuel is on sale here.
    if (st.docked && st.fuel < 70) {
        TradeText("Destinations up to ${eliteTenths(advice.range)} LY: refuel first (EQUIP SHIP, f3)",
            ELITE_YELLOW.copy(alpha = 0.85f), 9)
    }
    if (!st.docked) TradeText("Prices for this system. Dock at the station to trade.", Color.White.copy(alpha = 0.7f), 9)
    // Sells every item in the hold, then fills the tank (eliteSellAllAndRefuel).
    if (st.docked && (st.hold.any { it > 0 } || st.fuel < 70)) {
        Row(Modifier.padding(top = 2.dp)) { TradeButton("SELL ALL & REFUEL ▶", buying, onSellAllAndRefuel) }
    }

    if (advice.sell.isNotEmpty()) {
        // SELL ALL sells everything recommended for sale here in one visit to Sell Cargo.
        val sellHere = advice.sell.filter { it.sellHere }.map { it.item }.toSet()
        Row(verticalAlignment = Alignment.CenterVertically) {
            TradeHeading("YOUR CARGO", Modifier.weight(1f))
            if (st.docked && sellHere.size > 1) TradeButton("SELL ALL ▶", buying) { onSell(sellHere) }
        }
        advice.sell.forEach { s ->
            val item = ELITE_ITEMS[s.item]
            val line = if (s.sellHere) "SELL ${s.units}${item.unit} ${item.name} @ ${eliteTenths(s.price)}"
                else "KEEP ${s.units}${item.unit} ${item.name} · here ${eliteTenths(s.price)}"
            Row(verticalAlignment = Alignment.CenterVertically) {
                TradeText(line, Color.White, 10, bold = true, modifier = Modifier.weight(1f))
                // Sells all of it through Elite's Sell Cargo screen (eliteSell); docked only.
                if (st.docked) TradeButton("SELL ▶", buying) { onSell(setOf(s.item)) }
            }
            if (!s.sellHere) {
                val better = s.better!!
                TradeText("  sells ${eliteTenths(s.betterPrice!!.min)}–${eliteTenths(s.betterPrice.max)} at ${eliteName(better.name)}",
                    ELITE_CYAN, 9)
            }
        }
    }

    val buys = if (trip != null) "BUYS FOR ${eliteName(trip.name).uppercase()}" else "BEST BUYS"
    TradeHeading(if (advice.sell.any { it.sellHere }) "$buys (AFTER SELLING)" else buys)
    if (advice.buy.isEmpty()) {
        TradeText(
            when {
                advice.tonnesFree == 0 && advice.cash > 0 -> "Your hold is full."
                trip != null -> "Nothing here sells for more at ${eliteName(trip.name)}."
                else -> "No profitable trade within ${eliteTenths(advice.range)} light years."
            },
            Color.White, 10)
    }
    advice.buy.forEachIndexed { i, b ->
        val item = ELITE_ITEMS[b.item]
        val dest = b.destination
        Column(Modifier.fillMaxWidth().border(1.dp, ELITE_CYAN.copy(alpha = 0.3f)).padding(horizontal = 6.dp, vertical = 3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TradeText("${i + 1}  BUY ${b.units}${item.unit} ${item.name} @ ${eliteTenths(b.price)}", Color.White, 10,
                    bold = true, modifier = Modifier.weight(1f))
                // Buys it through Elite's Buy Cargo screen (eliteBuy); docked only.
                if (st.docked) TradeButton("BUY ▶", buying) { onBuy(b) }
            }
            Row {
                TradeText("→ ${eliteName(dest.name)} ${eliteTenths(b.distance)} LY · ${ELITE_ECONOMIES[dest.economy]} · " +
                    ELITE_GOVERNMENTS[dest.government], ELITE_CYAN, 9, modifier = Modifier.weight(1f, fill = false))
                if (dest.dangerous) TradeText(" ⚠ DANGER", ELITE_RED, 9, bold = true)
            }
            TradeText("SELLS ${eliteTenths(b.sale.min)}–${eliteTenths(b.sale.max)} · PROFIT " +
                "${signedTenths(b.profitMin)} TO ${signedTenths(b.profitMax)} CR", ELITE_YELLOW, 9)
        }
    }

    PlansSection(st, advice, onBuy, buying)
    EquipmentSection(st, advice)

    TradeText("ILLEGAL GOODS: ${if (includeIllegal) "ON" else "OFF"}  (tap to change)",
        if (includeIllegal) ELITE_RED else ELITE_CYAN, 9, bold = true,
        modifier = Modifier.tap(onToggleIllegal).padding(vertical = 4.dp))
    TradeText("Prices elsewhere are set when you arrive, so each shows the lowest and highest Elite can choose.",
        Color.White.copy(alpha = 0.55f), 8)
}

/**
 * Two-stop plans (TwoStopPlan): a load from here to a first stop, then a load
 * from there, so a good outward trade isn't followed by an empty return.
 */
@Composable
private fun PlansSection(st: EliteState, advice: TradeAdvice, onBuy: (BuyAdvice) -> Unit, buying: Boolean) {
    if (advice.plans.isEmpty()) return
    val trip = advice.destination
    TradeHeading(if (trip != null) "AND ONWARD FROM ${eliteName(trip.name).uppercase()}" else "TWO-STOP PLANS")
    TradeText(
        if (trip != null) "The best load there, whose prices are set when you arrive, and where to take it."
        else "Ranked by the expected profit of both loads together. The first stop's prices are set when you arrive.",
        Color.White.copy(alpha = 0.6f), 8)
    advice.plans.forEachIndexed { i, plan ->
        val via = plan.via
        Column(Modifier.fillMaxWidth().border(1.dp, ELITE_CYAN.copy(alpha = 0.3f)).padding(horizontal = 6.dp, vertical = 3.dp)) {
            val last = plan.second?.destination
            Row(verticalAlignment = Alignment.CenterVertically) {
                TradeText("${i + 1}  → ${eliteName(via.name)}" + (last?.let { " → ${eliteName(it.name)}" } ?: "") +
                    "   ≈ ${signedTenths(plan.expectedProfit.toLong())} CR", Color.White, 10, bold = true,
                    modifier = Modifier.weight(1f, fill = false))
                if (via.dangerous || last?.dangerous == true) TradeText(" ⚠ DANGER", ELITE_RED, 9, bold = true)
            }
            val first = plan.first
            if (first != null) {
                val item = ELITE_ITEMS[first.item]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TradeText("Here: buy ${first.units}${item.unit} ${item.name} @ ${eliteTenths(first.price)}, " +
                        "sells ${eliteTenths(first.sale.min)}–${eliteTenths(first.sale.max)} at ${eliteName(via.name)} " +
                        "(${eliteTenths(first.distance)} LY)", ELITE_CYAN, 9, modifier = Modifier.weight(1f))
                    if (st.docked) TradeButton("BUY ▶", buying) { onBuy(first) }
                }
            } else {
                TradeText("Here: fly to ${eliteName(via.name)} empty (${eliteTenths(plan.viaDistance)} LY)", ELITE_CYAN, 9)
            }
            val second = plan.second
            if (second != null) {
                val item = ELITE_ITEMS[second.item]
                TradeText("At ${eliteName(via.name)}: buy about ${second.units}${item.unit} ${item.name} " +
                    "(${eliteTenths(second.buy.min)}–${eliteTenths(second.buy.max)}), sells " +
                    "${eliteTenths(second.sale.min)}–${eliteTenths(second.sale.max)} at ${eliteName(second.destination.name)} " +
                    "(${eliteTenths(second.distance)} LY)", ELITE_CYAN, 9)
            } else {
                TradeText("At ${eliteName(via.name)}: nothing there is worth carrying on", ELITE_CYAN, 9)
            }
        }
    }
}

/**
 * What to fit next (eliteEquipmentAdvice), whether it's sold here, and
 * whether it can be bought without dipping into the trading float.
 */
@Composable
private fun EquipmentSection(st: EliteState, advice: TradeAdvice) {
    if (advice.equipment.isEmpty()) return
    TradeHeading("NEXT EQUIPMENT")
    val best = advice.buy.firstOrNull()
    TradeText("Keep a trading float of ${eliteTenths(advice.tradingFloat)} CR" +
        (best?.let { " (a full hold of ${ELITE_ITEMS[it.item].name}, and fuel)" } ?: " (fuel)"),
        Color.White.copy(alpha = 0.7f), 8)
    advice.equipment.forEachIndexed { i, e ->
        Column(Modifier.fillMaxWidth().border(1.dp, ELITE_CYAN.copy(alpha = 0.3f)).padding(horizontal = 6.dp, vertical = 3.dp)) {
            TradeText("${i + 1}  ${e.name} · ${eliteTenths(e.price)} CR", Color.White, 10, bold = true)
            TradeText(e.why, ELITE_CYAN, 9)
            val here = advice.here.techLevel
            val spare = st.cash - advice.tradingFloat
            val (text, color) = when {
                here < e.techNeeded -> "Needs tech level ${e.techNeeded} (here $here)" to Color.White.copy(alpha = 0.6f)
                st.cash < e.price -> "On sale here (EQUIP SHIP, f3, item ${e.menuNumber}) · need ${eliteTenths(e.price - st.cash)} CR more" to Color.White.copy(alpha = 0.6f)
                spare < e.price -> "On sale here (f3, item ${e.menuNumber}) · affordable, but dips into your float" to ELITE_YELLOW
                else -> "On sale here (f3, item ${e.menuNumber}) · affordable, and keeps your float" to ELITE_YELLOW
            }
            TradeText(text, color, 9, bold = color == ELITE_YELLOW)
        }
    }
}

/** Progress and outcome of a BUY, along the bottom of the screen. */
@Composable
private fun BuyBanner(text: String, buying: Boolean, modifier: Modifier) {
    val shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp)
    Column(
        modifier = modifier
            .padding(bottom = 12.dp)
            .background(Color(0xE6000814), shape)
            .border(1.dp, ELITE_CYAN.copy(alpha = 0.7f), shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TradeText(text, ELITE_YELLOW, 11, bold = true)
        if (buying) TradeText("Hands off the keys until it's done", Color.White.copy(alpha = 0.7f), 9)
    }
}

private fun signedTenths(v: Long) = (if (v >= 0) "+" else "-") + eliteTenths(kotlin.math.abs(v))

@Composable
private fun TradeHeading(title: String, modifier: Modifier = Modifier) =
    TradeText("◤ $title ◢", ELITE_YELLOW.copy(alpha = 0.85f), 9, bold = true, modifier = modifier.padding(top = 4.dp))

/** BUY / SELL: greyed out while a purchase or sale is under way. */
@Composable
private fun TradeButton(label: String, busy: Boolean, onTap: () -> Unit) {
    val shape = CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp)
    val color = if (busy) Color.Gray else ELITE_YELLOW
    TradeText(label, color, 10, bold = true,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.8f), shape)
            .tap { if (!busy) onTap() }
            .padding(horizontal = 8.dp, vertical = 2.dp))
}

@Composable
private fun TradeText(text: String, color: Color, size: Int, bold: Boolean = false, modifier: Modifier = Modifier) =
    Text(text, color = color, fontSize = size.sp, lineHeight = (size + 3).sp, fontFamily = ELITE_FONT,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, modifier = modifier)

private fun Modifier.tap(onTap: () -> Unit) =
    pointerInput(onTap) { awaitEachGesture { awaitFirstDown(); if (waitForUpOrCancellation() != null) onTap() } }

/** One-off banner when an Elite disc is loaded, offering the cockpit overlay. */
@Composable
fun EliteOffer(onShow: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp)
    Row(
        modifier = modifier
            .padding(bottom = 12.dp)
            .background(Color(0xE6000814), shape)
            .border(1.dp, ELITE_CYAN.copy(alpha = 0.7f), shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ELITE DETECTED · show cockpit keys beside the screen?",
            color = ELITE_YELLOW, fontSize = 11.sp, fontFamily = ELITE_FONT)
        Text("SHOW", color = ELITE_CYAN, fontSize = 12.sp, fontFamily = ELITE_FONT, fontWeight = FontWeight.Bold,
            modifier = Modifier.pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); if (waitForUpOrCancellation() != null) onShow() } })
        Text("✕", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
            modifier = Modifier.pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); if (waitForUpOrCancellation() != null) onDismiss() } })
    }
}
