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

private enum class EliteAction { MANUAL, TRADE }

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
        EliteKey("SAVE / LOAD", listOf("@"), "@"),
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
        // A purchase in progress (BUY on the trade panel), and its outcome.
        val scope = rememberCoroutineScope()
        var buying by remember { mutableStateOf(false) }
        var buyStatus by remember { mutableStateOf<String?>(null) }
        val onBuy: (BuyAdvice) -> Unit = { b ->
            if (!buying) {
                showTrade = false       // so the Buy Cargo screen can be seen
                buying = true
                scope.launch {
                    val result = eliteBuy(
                        b.item, b.units,
                        read = { addr, len -> BeebEmNative.nativeReadMemory(addr, len) },
                        press = { label, holdMs ->
                            val k = bbcKey(label)
                            BeebEmNative.nativeKeyDown(k.row, k.col)
                            try { delay(holdMs) } finally { BeebEmNative.nativeKeyUp(k.row, k.col) }
                        },
                        onProgress = { buyStatus = it },
                    )
                    buyStatus = result.message
                    buying = false
                    delay(if (result.ok) 4000 else 8000)
                    if (!buying) buyStatus = null
                }
            }
        }
        val onAction: (EliteAction) -> Unit = { action ->
            when (action) {
                EliteAction.MANUAL -> onManual()
                EliteAction.TRADE -> showTrade = !showTrade
            }
        }
        ElitePanel(LEFT_GROUPS, Modifier.align(Alignment.CenterStart).width(panelWidth).fillMaxHeight(), maxHeight, onAction)
        ElitePanel(RIGHT_GROUPS, Modifier.align(Alignment.CenterEnd).width(panelWidth).fillMaxHeight(), maxHeight, onAction)
        if (showTrade) {
            TradePanel(
                onClose = { showTrade = false },
                onBuy = onBuy,
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
private fun TradePanel(onClose: () -> Unit, onBuy: (BuyAdvice) -> Unit, buying: Boolean, modifier: Modifier) {
    var includeIllegal by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<EliteState?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            state = readEliteState { addr, len -> BeebEmNative.nativeReadMemory(addr, len) }
            delay(1000)
        }
    }
    val advice = remember(state, includeIllegal) { state?.let { eliteTradeAdvice(it, includeIllegal) } }
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
            advice != null -> TradeAdviceBody(st, advice, includeIllegal, onBuy, buying) { includeIllegal = !includeIllegal }
        }
    }
}

@Composable
private fun TradeAdviceBody(
    st: EliteState, advice: TradeAdvice, includeIllegal: Boolean,
    onBuy: (BuyAdvice) -> Unit, buying: Boolean, onToggleIllegal: () -> Unit,
) {
    val here = advice.here
    TradeText("${eliteName(here.name)} · ${ELITE_ECONOMIES[here.economy]} · ${ELITE_GOVERNMENTS[here.government]}",
        ELITE_CYAN, 10, bold = true)
    val held = (0..12).sumOf { st.hold[it] }
    TradeText("CASH ${eliteTenths(st.cash)} CR · HOLD ${held}/${st.cargoBay}t · RANGE ${eliteTenths(advice.range)} LY" +
        if (st.docked && st.fuel < 70) " (FULL TANK)" else "", Color.White, 9)
    if (!st.docked) TradeText("Prices for this system. Dock at the station to trade.", Color.White.copy(alpha = 0.7f), 9)

    if (advice.sell.isNotEmpty()) {
        TradeHeading("YOUR CARGO")
        advice.sell.forEach { s ->
            val item = ELITE_ITEMS[s.item]
            if (s.sellHere) {
                TradeText("SELL ${s.units}${item.unit} ${item.name} @ ${eliteTenths(s.price)}", Color.White, 10, bold = true)
            } else {
                TradeText("KEEP ${s.units}${item.unit} ${item.name} · here ${eliteTenths(s.price)}", Color.White, 10, bold = true)
                val better = s.better!!
                TradeText("  sells ${eliteTenths(s.betterPrice!!.min)}–${eliteTenths(s.betterPrice.max)} at ${eliteName(better.name)}",
                    ELITE_CYAN, 9)
            }
        }
    }

    TradeHeading(if (advice.sell.any { it.sellHere }) "BEST BUYS (AFTER SELLING)" else "BEST BUYS")
    if (advice.buy.isEmpty()) {
        TradeText(
            if (advice.tonnesFree == 0 && advice.cash > 0) "Your hold is full."
            else "No profitable trade within ${eliteTenths(advice.range)} light years.",
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
                if (st.docked) {
                    val shape = CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp)
                    TradeText("BUY ▶", if (buying) Color.Gray else ELITE_YELLOW, 10, bold = true,
                        modifier = Modifier
                            .border(1.dp, (if (buying) Color.Gray else ELITE_YELLOW).copy(alpha = 0.8f), shape)
                            .tap { if (!buying) onBuy(b) }
                            .padding(horizontal = 8.dp, vertical = 2.dp))
                }
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

    TradeText("ILLEGAL GOODS: ${if (includeIllegal) "ON" else "OFF"}  (tap to change)",
        if (includeIllegal) ELITE_RED else ELITE_CYAN, 9, bold = true,
        modifier = Modifier.tap(onToggleIllegal).padding(vertical = 4.dp))
    TradeText("Prices elsewhere are set when you arrive, so each shows the lowest and highest Elite can choose.",
        Color.White.copy(alpha = 0.55f), 8)
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
private fun TradeHeading(title: String) =
    TradeText("◤ $title ◢", ELITE_YELLOW.copy(alpha = 0.85f), 9, bold = true, modifier = Modifier.padding(top = 4.dp))

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
