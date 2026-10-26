package uk.org.beebem.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ── Citadel guide overlay ──────────────────────────────────────────────────
//
// Shows citadelAdvice (CitadelGuide.kt) for the game in BBC memory, re-read
// twice a second: the next objective, which way to go from this screen, and
// how far through the route the player is. It sits in the black band beside
// the picture, as a narrow column on a 16:9 screen (about 90 dp), else in the
// top corner; tapping its title folds it down to the essentials.

// Citadel's MODE 2 colours.
private val CIT_YELLOW = Color(0xFFFFFF40)
private val CIT_CYAN = Color(0xFF40FFFF)
private val CIT_MAGENTA = Color(0xFFFF50FF)
private val CIT_GREEN = Color(0xFF50FF50)
private val CIT_PANEL = Color(0xE0101018)
private val CIT_FONT = FontFamily.Monospace

@Composable
fun CitadelOverlay(pictureWidthFraction: Float, modifier: Modifier = Modifier) {
    var state by remember { mutableStateOf<CitadelState?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            state = readCitadelState { addr, len -> BeebEmNative.nativeReadMemory(addr, len) }
            delay(500)
        }
    }
    var expanded by remember { mutableStateOf(true) }
    val advice = remember(state) { state?.let { citadelAdvice(it) } }

    BoxWithConstraints(modifier) {
        val side = maxWidth * ((1f - pictureWidthFraction) / 2f) - 6.dp
        val inBand = side >= 72.dp
        val width = if (inBand) min(side, 260.dp) else 260.dp
        val panel = if (inBand)
            Modifier.align(Alignment.CenterEnd).padding(end = 3.dp).width(width).heightIn(max = maxHeight - 8.dp)
        else
            Modifier.align(Alignment.TopEnd).padding(top = 36.dp, end = 8.dp).width(width)
        CitadelPanel(state, advice, expanded, { expanded = !expanded }, compact = width < 160.dp, modifier = panel)
    }
}

@Composable
private fun CitadelPanel(
    state: CitadelState?, advice: CitadelAdvice?, expanded: Boolean, onToggle: () -> Unit,
    compact: Boolean, modifier: Modifier,
) {
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier = modifier
            .background(CIT_PANEL, shape)
            .border(1.dp, CIT_MAGENTA.copy(alpha = 0.7f), shape)
            .padding(horizontal = if (compact) 5.dp else 8.dp, vertical = 6.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
                awaitEachGesture { awaitFirstDown(); if (waitForUpOrCancellation() != null) onToggle() }
            },
        ) {
            CitText(if (compact) "GUIDE" else "CITADEL GUIDE", CIT_YELLOW, 10, bold = true, modifier = Modifier.weight(1f))
            if (advice != null && state?.onTitleScreen == false) CitText("${advice.percent}%", CIT_GREEN, 11, bold = true)
            CitText(if (expanded) " ▾" else " ▸", Color.White, 11)
        }
        when {
            state == null -> CitText("Waiting for the game to start…", Color.White, 9)
            state.onTitleScreen -> CitText("Title screen: press ACTION to start.", Color.White, 9)
            advice != null -> {
                ProgressBar(advice.percent)
                CitadelDetails(state, advice, expanded, compact)
            }
        }
    }
}

@Composable
private fun CitadelDetails(s: CitadelState, a: CitadelAdvice, expanded: Boolean, compact: Boolean) {
    if (expanded) CitText("STEP ${a.stepNumber} OF ${CITADEL_ROUTE.size}", CIT_CYAN.copy(alpha = 0.8f), 8)
    CitText(a.action, Color.White, if (compact) 9 else 11, bold = true)
    if (a.direction.isNotEmpty()) {
        val room = a.targetRoom
        val xy = a.targetCoords
        val place = if (room != null && xy != null)
            "${citadelRoomName(room)} (${citadelCoordText(xy.first, xy.second)})" else null
        if (compact) {
            // Narrow column: arrow on its own, then the rest underneath.
            CitText(a.arrow, CIT_YELLOW, 26, bold = true)
            CitText(a.direction, CIT_YELLOW, 10, bold = true)
            place?.let { CitText(it, CIT_CYAN, 8) }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CitText(a.arrow, CIT_YELLOW, 22, bold = true)
                Column {
                    CitText(a.direction, CIT_YELLOW, 10, bold = true)
                    place?.let { CitText(it, CIT_CYAN, 9) }
                }
            }
        }
    }
    if (!expanded) return
    val tip = a.step?.tip.orEmpty()
    if (tip.isNotEmpty()) CitText(tip, Color.White.copy(alpha = 0.75f), if (compact) 7 else 8)
    CitText("You: ${citadelRoomName(s.room)} (${citadelCoordText(s.x, s.y)})", CIT_MAGENTA, if (compact) 7 else 8)
    val stats = listOf("Score ${s.score}", "Crystals ${s.crystalsPlaced}/5", "Crowns ${s.crownsTaken}/3")
    if (compact) stats.forEach { CitText(it, CIT_GREEN, 7) }
    else CitText(stats.joinToString(" · "), CIT_GREEN, 8)
    // 99 points shows the game's hidden completion message; flasks are 2 each.
    if (s.score < 99) CitText("Flasks left ${a.flasksLeft.size}/16 · 99 points unlocks the secret ending",
        Color.White.copy(alpha = 0.55f), 7)
}

@Composable
private fun ProgressBar(percent: Int) {
    Box(Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(2.dp))) {
        Box(Modifier.fillMaxWidth(percent / 100f).fillMaxHeight().background(CIT_GREEN, RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun CitText(
    text: String, color: Color, size: Int, bold: Boolean = false, maxLines: Int = Int.MAX_VALUE,
    modifier: Modifier = Modifier,
) = Text(text, color = color, fontSize = size.sp, lineHeight = (size + 3).sp, fontFamily = CIT_FONT,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = maxLines, modifier = modifier)
