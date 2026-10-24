package uk.org.beebem.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Quick control set-up for the current disc: the automatic mapping (known
 * game, or read from the disc's instructions), the five common BBC schemes,
 * the default, and removing a custom mapping. Picking one saves it for the
 * disc, like a mapping made in CFG mode.
 */
@Composable
fun ControlsScreen(
    currentSource: String,
    detected: Pair<JoypadMapping, String>?,
    hasCustom: Boolean,
    discName: String?,
    onPick: (JoypadMapping, String) -> Unit,
    onClearCustom: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF20E0E1E))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Controls", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    (discName ?: "No disc loaded") + "  ·  now: " + currentSource,
                    color = Color(0xFF8888AA), fontSize = 11.sp,
                )
            }
            TextButton(onClick = onDismiss) { Text("Close", color = Color(0xFF8888AA), fontSize = 13.sp) }
        }
        Spacer(Modifier.height(6.dp))

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (detected != null) {
                ControlChoice(
                    title = "Automatic for this game",
                    detail = detected.second + ". " + describe(detected.first),
                    highlight = true,
                ) { onPick(detected.first, detected.second) }
            }
            CONTROL_PRESETS.forEach { preset ->
                ControlChoice(preset.name, preset.detail) { onPick(preset.mapping, preset.name) }
            }
            ControlChoice("Default", describe(DefaultJoypadMapping)) { onPick(DefaultJoypadMapping, "Default") }
            if (hasCustom) {
                ControlChoice(
                    title = "Remove my custom controls for this disc",
                    detail = "Go back to the automatic controls (or the default if there are none).",
                ) { onClearCustom() }
            }
            Text(
                "Picking a scheme saves it for this disc. You can still change single buttons in PAD → CFG.",
                color = Color(0xFF666688), fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun ControlChoice(title: String, detail: String, highlight: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (highlight) Color(0xFF1F2A55) else Color(0xFF1A1A2E), shape)
            .border(1.dp, if (highlight) Color(0xFF4466CC) else Color(0xFF2E2E4A), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(detail, color = Color(0xFFAAAACC), fontSize = 11.sp)
    }
}

// "D-pad Z X : /, bottom RET, right SPC, left SHF" etc.
private fun describe(m: JoypadMapping): String {
    val dirs = "D-pad ↑${m.up.label} ↓${m.down.label} ←${m.left.label} →${m.right.label}"
    val face = listOf("bottom" to "south", "right" to "east", "left" to "west", "top" to "north")
        .mapNotNull { (name, id) -> m.extras[id]?.let { "$name ${it.label}" } }
    return (listOf(dirs) + face).joinToString(", ")
}
