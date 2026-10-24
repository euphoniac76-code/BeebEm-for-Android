package uk.org.beebem.android

import android.content.Context
import org.json.JSONObject
import java.io.File

// ── BBC keys by name ───────────────────────────────────────────────────────
//
// Labels as used by the key picker (PICKER_KEY_ROWS): "Z", ":", "RET", "SPC",
// "SHF", "CTL", "CAPS", "TAB", "ESC", "COPY", "DEL", "f0".."f9", "↑" ...

private val KEYS_BY_LABEL: Map<String, JoypadKey> =
    PICKER_KEY_ROWS.flatten().filter { !it.shifted }.associateBy { it.label }

/** The BBC key with this picker label. */
fun bbcKey(label: String): JoypadKey =
    KEYS_BY_LABEL[label] ?: error("Unknown BBC key label: $label")

/** The key with SHIFT held (e.g. shifted(":") gives "*" sent as SHIFT + :). */
fun shiftedKey(label: String, shownAs: String): JoypadKey =
    bbcKey(label).copy(label = shownAs, shifted = true)

// "*" and "?" are shown on the BBC's : and / keys; the picker labels are ":" and "/".
private val PRESET_LABEL_ALIASES = mapOf("*" to ":", "?" to "/")

// ── Building a mapping ─────────────────────────────────────────────────────

/**
 * A full controller mapping. Directions drive both the D-pad/stick and the
 * on-screen arrows; the face buttons are by position (see GamepadInput), and
 * the on-screen A/B/C buttons copy the bottom/right/left face buttons.
 */
fun controlMapping(
    up: String, down: String, left: String, right: String,
    south: JoypadKey, east: JoypadKey, west: JoypadKey,
    north: JoypadKey? = null,
    l1: JoypadKey? = bbcKey("COPY"),    // BBC convention: COPY freezes a game...
    r1: JoypadKey? = bbcKey("DEL"),     // ...and DELETE unfreezes it
    l2: JoypadKey? = null, r2: JoypadKey? = null,
    l3: JoypadKey? = null, r3: JoypadKey? = null,
    start: JoypadKey? = bbcKey("RET"),
): JoypadMapping = JoypadMapping(
    up = dirKey(up), down = dirKey(down), left = dirKey(left), right = dirKey(right),
    fire1 = south, fire2 = east, fire3 = west,
    extras = buildMap {
        put("south", south); put("east", east); put("west", west)
        north?.let { put("north", it) }
        l1?.let { put("l1", it) }; r1?.let { put("r1", it) }
        l2?.let { put("l2", it) }; r2?.let { put("r2", it) }
        l3?.let { put("l3", it) }; r3?.let { put("r3", it) }
        start?.let { put("start", it) }
    },
)

private fun k(label: String) = bbcKey(label)

// "*" and "?" are printed on the BBC's : and / keys, and games read the key,
// so a direction given as "*" presses : (shown as "*").
private fun dirKey(label: String): JoypadKey =
    PRESET_LABEL_ALIASES[label]?.let { bbcKey(it).copy(label = label) } ?: bbcKey(label)

// ── Quick presets ──────────────────────────────────────────────────────────
//
// The five most common BBC keyboard schemes, measured from the instruction
// screens of ~1,800 games in a 3,000-disc collection: Z/X for left/right in
// over 500 of them, with "* ?" (171) or ": /" (106) for up/down - the same
// two keys, as * and ? are SHIFT + : and / on the BBC keyboard and games
// read the key, not the character - then A/Z with < > (72), Acornsoft's
// CAPS LOCK / CTRL with SHIFT thrust, and the cursor keys.

data class ControlPreset(val id: String, val name: String, val detail: String, val mapping: JoypadMapping)

val CONTROL_PRESETS: List<ControlPreset> = listOf(
    ControlPreset(
        "zx", "Z X  * ?  (: /)",
        "Left Z, Right X, Up * (:), Down ? (/). Bottom RETURN, right SPACE, left SHIFT. " +
            "Repton, Snapper, Labyrinth, Citadel, Frak and most Superior and Acornsoft games.",
        controlMapping("*", "?", "Z", "X", south = k("RET"), east = k("SPC"), west = k("SHF"), north = k("CTL")),
    ),
    ControlPreset(
        "capsctrl", "CAPS / CTRL rotate",
        "Rotate CAPS LOCK / CTRL, Up SHIFT (thrust), Down SPACE (shield). Bottom RETURN (fire). " +
            "Thrust, Meteors, Arcadians, Zalaga and other Acornsoft arcade games.",
        controlMapping("SHF", "SPC", "CAPS", "CTL", south = k("RET"), east = k("SPC"), west = k("SHF"), north = k("TAB")),
    ),
    ControlPreset(
        "azcomma", "A Z  < >",
        "Up A, Down Z, Left < (,), Right > (.). Bottom SPACE, right RETURN, left SHIFT. Chuckie Egg style.",
        controlMapping("A", "Z", ",", ".", south = k("SPC"), east = k("RET"), west = k("SHF")),
    ),
    ControlPreset(
        "zxjump", "Z X  jump on RETURN",
        "Left Z, Right X, Up RETURN (jump), Down ? (/). Bottom RETURN, right SPACE, left SHIFT. " +
            "Manic Miner, Jet Set Willy, Stryker's Run style platformers.",
        controlMapping("RET", "?", "Z", "X", south = k("RET"), east = k("SPC"), west = k("SHF")),
    ),
    ControlPreset(
        "cursor", "Cursor keys",
        "D-pad sends the cursor keys. Bottom RETURN, right SPACE, left SHIFT. Menus and cursor-key games.",
        controlMapping("↑", "↓", "←", "→", south = k("RET"), east = k("SPC"), west = k("SHF")),
    ),
)

private fun bbcKeyOrAlias(label: String) = bbcKey(PRESET_LABEL_ALIASES[label] ?: label)

// ── Elite ──────────────────────────────────────────────────────────────────
//
// Flight keys from Elite's own key table (KYTB, see elite.bbcelite.com):
// ? slow down, SPACE speed up, < > roll, X pull up, S pitch down, A fire,
// TAB energy bomb, ESCAPE escape pod, T target missile, U unarm, M fire
// missile, E E.C.M., J in-system jump, C docking computer, P cancel docking.
// The controller flies the ship like a joystick (push up to dive); the rest
// of the keys are on the Elite cockpit overlay (EliteOverlay.kt).

val ELITE_MAPPING: JoypadMapping = controlMapping(
    up = "S", down = "X", left = ",", right = ".",     // dive / climb, roll < >
    south = k("A"),                                     // fire lasers
    east  = k("M"),                                     // fire missile
    north = k("T"),                                     // target missile
    west  = k("E"),                                     // E.C.M.
    l1 = k("/"),                                        // ? slow down
    r1 = k("SPC"),                                      // speed up
    l2 = k("J"),                                        // in-system jump
    r2 = k("H"),                                        // hyperspace
    start = k("f0"),                                    // front view / launch
)

// ── Known games ────────────────────────────────────────────────────────────
//
// Matched against the disc's file name with everything but letters and
// digits removed, so "Repton2.ssd", "repton_2.ssd" and "REPTON 2.SSD" all
// match "repton". The first entry whose name appears in the file name wins.
// Keys are from each game's instruction screen (or the publisher's notes).

private fun preset(id: String) = CONTROL_PRESETS.first { it.id == id }.mapping

private val KNOWN_GAMES: List<Pair<String, () -> JoypadMapping>> = listOf(
    "elite" to { ELITE_MAPPING },
    // Thrust: CAPS LOCK / CTRL rotate, SHIFT thrust, RETURN fire, SPACE shield/tractor.
    "thrust" to { preset("capsctrl") },
    // Meteors: CAPS LOCK / CTRL rotate, SHIFT thrust, RETURN fire, SPACE hyperspace.
    "meteors" to { preset("capsctrl") },
    // Arcadians, Zalaga, Hunchback: CAPS LOCK / CTRL left/right, RETURN fire.
    "arcadians" to { preset("capsctrl") },
    "zalaga" to { preset("capsctrl") },
    "hunchback" to { preset("capsctrl") },
    // Planetoid: A up, Z down, SPACE reverse, SHIFT thrust, RETURN fire, TAB smart bomb, H hyperspace.
    "planetoid" to {
        controlMapping("A", "Z", "SPC", "SHF", south = k("RET"), east = k("SHF"), west = k("SPC"),
            north = k("TAB"), r2 = k("H"))
    },
    "defender" to {
        controlMapping("A", "Z", "SPC", "SHF", south = k("RET"), east = k("SHF"), west = k("SPC"),
            north = k("TAB"), r2 = k("H"))
    },
    // Revs (Geoff Crammond): keys L / + steer, S accelerate, A brake, Q / TAB
    // gear up / down, SPACE stronger steering; SHIFT+f1 keyboard, SHIFT+f2
    // joystick mode (The Micro User's guide to Revs). The left stick is a real
    // analogue BBC joystick (steer; forward accelerates, back brakes; fire is
    // the clutch for gear changes) once SHIFT+f2 selects joystick mode. The
    // D-pad and triggers drive the keyboard controls.
    "revs" to {
        controlMapping(
            up = "S", down = "A", left = "L", right = ";",
            south = JOYSTICK_FIRE, east = k("SPC"), west = k("T"),
            north = shiftedKey("f2", "JOY MODE"),
            l1 = k("TAB"), r1 = k("Q"),                 // gear down / up
            l2 = k("A"), r2 = k("S"),                   // brake / accelerate
            l3 = shiftedKey("f1", "KEYS MODE"),
        ).copy(analogueStick = true)
    },
    // Chuckie Egg: A up, Z down, < left, > right, SPACE jump.
    "chuckie" to { preset("azcomma") },
    // Stryker's Run / Codename Droid: Z X run, RETURN jump, ] fire blaster.
    "stryker" to {
        controlMapping("RET", "/", "Z", "X", south = k("RET"), east = k("]"), west = k("SPC"))
    },
    "codenamedroid" to {
        controlMapping("RET", "/", "Z", "X", south = k("RET"), east = k("]"), west = k("SPC"))
    },
    // Paperboy: Z X steer, * accelerate, ? brake, RETURN throw, SHIFT+* turbo.
    "paperboy" to {
        controlMapping(":", "/", "Z", "X", south = k("RET"), east = shiftedKey(":", "*"), west = k("SPC"))
    },
    // Manic Miner, Jet Set Willy: Z X, RETURN jump.
    "manicminer" to { preset("zxjump") },
    "jetsetwilly" to { preset("zxjump") },
    // Space Invaders clones: Z X, RETURN (Super Invaders) or SPACE (Invaders) fire.
    "superinvaders" to { preset("zx") },
    "invaders" to {
        controlMapping(":", "/", "Z", "X", south = k("SPC"), east = k("RET"), west = k("SHF"))
    },
    // Z X * ? / Z X : / games (fire or action on RETURN).
    "repton" to { preset("zx") },
    "snapper" to { preset("zx") },
    "labyrinth" to { preset("zx") },
    "snake" to { preset("zx") },
    "frak" to { preset("zx") },             // * jump, RETURN yo-yo
    "spitfire" to { preset("zx") },
    "runemaker" to { preset("zx") },        // Codename Druid: * jump, RETURN spells
    "citadel" to { preset("zx") },
    "aciddrops" to { preset("zx") },
)

private fun normalise(name: String) =
    name.substringBeforeLast('.').lowercase().filter { it.isLetterOrDigit() }

/** True if this disc is Elite (any version). */
fun isEliteDisc(file: File?): Boolean = file != null && "elite" in normalise(file.name)

/**
 * The mapping to use for a disc that has no saved custom mapping: a known
 * game by file name, else the checked list for Rob's collection, else
 * controls read from the disc's own instructions, else null (the default).
 */
fun detectGameMapping(context: Context, file: File): Pair<JoypadMapping, String>? {
    val name = normalise(file.name)
    KNOWN_GAMES.firstOrNull { (key, _) -> key in name }?.let { (key, build) ->
        return build() to "Known game ($key)"
    }
    val checked = CheckedGameList.get(context)
    if (checked.containsKey(name)) {
        // null: the game is listed but has no reliable keys (e.g. it asks for them).
        return checked[name]?.let { it to "Checked list" }
    }
    return runCatching { DiscInstructions.read(file) }.getOrNull()?.let { it to "Read from the disc's instructions" }
}

// ── Checked list of games ──────────────────────────────────────────────────
//
// assets/game_controls.json: the controls of ~700 games in Rob's disc
// collection, read from their instruction screens and checked by hand
// (tools/build_db.js). Keyed by the normalised file name; each entry has
// l/r/u/d direction keys, a = main action, b = second action, c = thrust,
// as key-picker labels. A null entry means "no automatic keys".

private object CheckedGameList {
    @Volatile private var cache: Map<String, JoypadMapping?>? = null

    fun get(context: Context): Map<String, JoypadMapping?> = cache ?: synchronized(this) {
        cache ?: load(context).also { cache = it }
    }

    private fun load(context: Context): Map<String, JoypadMapping?> = runCatching {
        val json = JSONObject(context.assets.open("game_controls.json").bufferedReader().use { it.readText() })
        val out = HashMap<String, JoypadMapping?>(json.length())
        for (name in json.keys()) {
            val e = json.optJSONObject(name)
            out[name] = e?.let { runCatching { toMapping(it) }.getOrNull() }
        }
        out
    }.getOrDefault(emptyMap())

    private fun toMapping(e: JSONObject): JoypadMapping {
        fun s(k: String) = e.optString(k).takeIf { it.isNotEmpty() }
        val action = s("a") ?: "RET"
        return controlMapping(
            up = s("u") ?: action, down = s("d") ?: "/", left = s("l")!!, right = s("r")!!,
            south = bbcKeyOrAlias(action),
            east = bbcKeyOrAlias(s("b") ?: "SPC"),
            west = bbcKeyOrAlias(s("c") ?: "SHF"),
        )
    }
}

// ── Controls from a disc's instruction screen ──────────────────────────────
//
// Many games print their keys ("Z - Left", "CAPS LOCK _ Rotate left",
// "'X' to move right") in their loader programs. This reads the text out of
// the files on an Acorn DFS disc image (.ssd, or side 0 of .dsd) and turns
// it into a mapping. Only used when the game is not in KNOWN_GAMES.

private object DiscInstructions {
    private const val SECTOR = 256
    private const val TRACK_SECTORS = 10

    fun read(file: File): JoypadMapping? {
        val name = file.name.lowercase()
        if (!name.endsWith(".ssd") && !name.endsWith(".dsd")) return null
        if (file.length() > 1_000_000) return null
        val raw = file.readBytes()
        val side0 = if (name.endsWith(".dsd")) deinterleaveSide0(raw) else raw

        val found = HashMap<String, String>()
        for (data in files(side0)) for (s in strings(data)) for ((act, key) in extract(s)) {
            if (act !in found) found[act] = key
        }
        return toMapping(found)
    }

    private fun deinterleaveSide0(img: ByteArray): ByteArray {
        val trackBytes = TRACK_SECTORS * SECTOR
        val tracks = (img.size + 2 * trackBytes - 1) / (2 * trackBytes)
        val out = ByteArray(tracks * trackBytes)
        for (t in 0 until tracks) {
            val src = t * 2 * trackBytes
            if (src >= img.size) break
            System.arraycopy(img, src, out, t * trackBytes, minOf(trackBytes, img.size - src))
        }
        return out
    }

    private fun files(img: ByteArray): List<ByteArray> {
        if (img.size < 2 * SECTOR) return emptyList()
        val u = { i: Int -> img[i].toInt() and 0xFF }
        val n = u(SECTOR + 5) shr 3
        return (0 until minOf(n, 31)).mapNotNull { i ->
            val b = SECTOR + 8 + i * 8
            val len = u(b + 4) or (u(b + 5) shl 8) or (((u(b + 6) shr 4) and 3) shl 16)
            val start = (u(b + 7) or ((u(b + 6) and 3) shl 8)) * SECTOR
            if (start >= img.size) null else img.copyOfRange(start, minOf(start + len, img.size))
        }
    }

    private fun strings(buf: ByteArray): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (byte in buf) {
            val ch = byte.toInt() and 0x7F          // some games set bit 7 on text
            if (ch in 0x20..0x7E) cur.append(ch.toChar())
            else { if (cur.length >= 3) out.add(cur.toString()); cur.setLength(0) }
        }
        if (cur.length >= 3) out.add(cur.toString())
        return out
    }

    private const val KEYTOK =
        """(caps\s*lock|shift\s*lock|ctrl|control|shift|return|space(?:\s*bar)?|copy|delete|tab|escape|cursor\s*(?:up|down|left|right)|['"]?[A-Z0-9:;*?/,.<>@^\[\]\\]['"]?)"""
    private const val SEP = """\s*(?:-+|_+|=|\.{2,}|:|\bto\b|\bfor\b|\bis\b)\s*"""
    private val P_KEY_ACT = Regex("""(?:^|[\s"'(])""" + KEYTOK + SEP + """([a-z][a-z /,()-]{1,30})""", RegexOption.IGNORE_CASE)
    private val P_ACT_KEY = Regex("""(left|right|up|down|fire|jump|thrust|shoot|climb)[a-z ]{0,20}?""" + SEP + KEYTOK + """(?=$|[\s"',;)])""", RegexOption.IGNORE_CASE)
    private val P_USE = Regex("""(?:use|press|hit)\s+""" + KEYTOK + """\s+(?:to|for)\s+([a-z ]{2,30})""", RegexOption.IGNORE_CASE)

    private val ACTIONS = listOf(
        "left" to Regex("""\b(left|west|anti-?clockwise)\b""", RegexOption.IGNORE_CASE),
        "right" to Regex("""\b(right|east|clockwise)\b""", RegexOption.IGNORE_CASE),
        "up" to Regex("""\b(up|north|climb)\b""", RegexOption.IGNORE_CASE),
        "down" to Regex("""\b(down|south|descend)\b""", RegexOption.IGNORE_CASE),
        "fire" to Regex("""\b(fire|shoot|laser|missiles?)\b""", RegexOption.IGNORE_CASE),
        "jump" to Regex("""\bjump""", RegexOption.IGNORE_CASE),
        "thrust" to Regex("""\b(thrust|accelerat)""", RegexOption.IGNORE_CASE),
    )

    private fun actionOf(text: String) = ACTIONS.firstOrNull { it.second.containsMatchIn(text) }?.first

    // Instruction text -> picker label.
    private fun keyLabel(token: String): String? {
        val t = token.trim().trim('\'', '"', '(', ')', '<', '>', '[', ']').ifEmpty { token.trim() }.lowercase()
        return when {
            t.startsWith("caps") -> "CAPS"
            t.startsWith("shift lock") -> null
            t == "ctrl" || t == "control" -> "CTL"
            t == "shift" -> "SHF"
            t == "return" -> "RET"
            t.startsWith("space") -> "SPC"
            t == "copy" -> "COPY"
            t == "delete" -> "DEL"
            t == "tab" -> "TAB"
            t == "escape" -> null           // never map ESCAPE automatically
            t == "cursor up" -> "↑"
            t == "cursor down" -> "↓"
            t == "cursor left" -> "←"
            t == "cursor right" -> "→"
            t.length == 1 -> when (val c = t.uppercase()) {
                "*" -> ":"; "?" -> "/"; "+" -> ";"; "<" -> ","; ">" -> "."   // shifted legends
                else -> c.takeIf { KEYS_BY_LABEL.containsKey(it) }
            }
            else -> null
        }
    }

    private fun extract(text: String): List<Pair<String, String>> {
        val pairs = ArrayList<Pair<String, String>>()
        fun add(keyTok: String, actText: String) {
            val key = keyLabel(keyTok) ?: return
            val act = actionOf(actText) ?: return
            pairs.add(act to key)
        }
        P_KEY_ACT.findAll(text).forEach { add(it.groupValues[1], it.groupValues[2]) }
        P_USE.findAll(text).forEach { add(it.groupValues[1], it.groupValues[2]) }
        P_ACT_KEY.findAll(text).forEach { add(it.groupValues[2], it.groupValues[1]) }
        return pairs
    }

    // Use the found keys only if they look like a real control set.
    private fun toMapping(f: Map<String, String>): JoypadMapping? {
        val left = f["left"]; val right = f["right"]
        if (left == null || right == null || left == right) return null
        val up = f["up"]?.takeIf { it != f["down"] && it != left && it != right }
        val down = f["down"]?.takeIf { it != f["up"] && it != left && it != right }
        val action = f["fire"] ?: f["jump"] ?: "RET"
        val second = (if (f["fire"] != null) f["jump"] else null) ?: f["thrust"] ?: "SPC"
        return controlMapping(
            up = up ?: action, down = down ?: "/", left = left, right = right,
            south = bbcKeyOrAlias(action), east = bbcKeyOrAlias(second),
            west = bbcKeyOrAlias(f["thrust"]?.takeIf { it != second } ?: "SHF"),
        )
    }
}
