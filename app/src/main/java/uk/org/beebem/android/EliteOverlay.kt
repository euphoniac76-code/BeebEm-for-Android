package uk.org.beebem.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
)

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
    )),
    EliteGroup("GAME", listOf(
        EliteKey("PAUSE", listOf("COPY"), "COPY"),
        EliteKey("RESUME", listOf("DEL"), "DEL"),
        EliteKey("SAVE / LOAD", listOf("@"), "@"),
        EliteKey("MANUAL", emptyList(), "ORIGINAL"),   // opens the flight training manual
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
        ElitePanel(LEFT_GROUPS, Modifier.align(Alignment.CenterStart).width(panelWidth).fillMaxHeight(), maxHeight, onManual)
        ElitePanel(RIGHT_GROUPS, Modifier.align(Alignment.CenterEnd).width(panelWidth).fillMaxHeight(), maxHeight, onManual)
    }
}

@Composable
private fun ElitePanel(groups: List<EliteGroup>, modifier: Modifier, height: Dp, onManual: () -> Unit) {
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
                    row.forEach { key -> EliteButton(key, Modifier.weight(1f).fillMaxHeight(), onManual) }
                    repeat(group.columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun EliteButton(key: EliteKey, modifier: Modifier, onManual: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
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
                    if (key.keys.isEmpty() && up != null) onManual()   // the MANUAL button
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
