package uk.org.beebem.android

import kotlin.math.abs

// ── Citadel guide ──────────────────────────────────────────────────────────
//
// Follows a game of Citadel (Michael Jakobsen, Superior Software, 1985) from
// the emulated BBC's memory and says what to do next, which way to go from the
// current screen and roughly how far through the game the player is.
//
// Memory layout from the level7.org.uk disassembly of the BBC version,
// checked against the original disc and an "immune to death" cheat version
// running in BeebEm: both keep the game state at the same addresses.
// The route is the published solution (8bs.com "Citadel Solution", "the best
// way"), turned into milestones that are each ticked off from game state, so
// it keeps up however the player gets there.

/** The game state the guide needs, read from BBC RAM. */
class CitadelState(
    val room: Int,                  // player_room (&37): row * 20 + column
    val x: Int,                     // room co-ordinates relative to the Main Hall,
    val y: Int,                     //   as the game shows them (room_x, room_y)
    val entryX: Int,                // where the player came into this room
    val entryY: Int,                //   (player_entry_x/y, pixels)
    val score: Int,                 // 0-99 (BCD at &3E)
    val energy: Int,
    val pockets: IntArray,          // object numbers in the two pockets
    val objectRoom: IntArray,       // 40 objects: room, 0 while carried
    val objectSprite: IntArray,     // 40 objects: what each is now, &FF once used up
    val energyCollected: BooleanArray,  // 16 flasks
    val switches: IntArray,         // right of Central Tower, The Well Wheel
    val crystalsInSanctuary: BooleanArray,  // by crystal 0-4
    val cannonLoaded: Int,          // 0-2: cannonball and gunpowder added; &9B once fired
    val guardianFed: Boolean,
    val bonesNeeded: Int,           // 3 at start; &FF once the witch is dead
    val starPortDestroyed: Boolean,
    val doorsOpened: BooleanArray,  // 8 doors by colour
    val onTitleScreen: Boolean,
) {
    fun carrying(obj: Int) = pockets[0] == obj || pockets[1] == obj
    fun usedUp(obj: Int) = objectSprite[obj] == 0xFF
    /** Moved from where the game put it: picked up, carried off or used. */
    fun moved(obj: Int, from: Int) = objectRoom[obj] != from || usedUp(obj)
    val witchDead get() = bonesNeeded >= 0x80
    val crystalsPlaced get() = crystalsInSanctuary.count { it }
    val crownsTaken get() = (35..37).count { usedUp(it) }
    val onPlanet get() = room in CITADEL_PLANET
}

// Addresses (BBC Micro version).
private const val PLAYER_ROOM = 0x37
private const val SCORE = 0x3E
private const val POCKETS = 0x2E
private const val ENERGY = 0x96         // low byte BCD, then hundreds
private const val ROOM_XY = 0x012D
private const val PLAYER_ENTRY = 0x0124
private const val OBJECT_ROOM = 0x0380
private const val OBJECT_SPRITE = 0x03A8
private const val ENERGY_COLLECTED = 0x04F0
private const val PUZZLES = 0x07F4      // switches(2), crystals(5), cannon, guardian, bones, star port
private const val INITIAL_OBJECT_ROOMS = 0x0880
private const val DOORS_OPENED = 0x08A8
private const val ESCAPE_KEY = 0x270A   // &1B in play, &FF on the title screen

// initial_object_room_table: read only when a game starts, so it is a steady
// signature of Citadel being in memory (cheat versions keep it too).
private val SIGNATURE = intArrayOf(
    0x72, 0xD5, 0x97, 0x6E, 0x01, 0x5E, 0x1C, 0x6F, 0x0B, 0xA6, 0xC4, 0x84, 0x68, 0x81, 0x36, 0x6C,
    0x87, 0x95, 0x05, 0x86, 0x85, 0x5C, 0x64, 0x82, 0xCB, 0xE4, 0xEA, 0x17, 0x70, 0xE1, 0x58, 0x83,
    0xA3, 0x29, 0x5A, 0x90, 0x94, 0xD6, 0x00, 0x00,
)

/** True if Citadel's game code is in memory (any version with the BBC layout). */
fun isCitadelInMemory(read: (addr: Int, len: Int) -> ByteArray?): Boolean {
    val sig = read(INITIAL_OBJECT_ROOMS, SIGNATURE.size) ?: return false
    return SIGNATURE.indices.all { (sig[it].toInt() and 0xFF) == SIGNATURE[it] }
}

private fun bcd(b: Int) = (b shr 4) * 10 + (b and 0x0F)

/** The game state, or null if Citadel isn't running. */
fun readCitadelState(read: (addr: Int, len: Int) -> ByteArray?): CitadelState? {
    if (!isCitadelInMemory(read)) return null
    fun bytes(addr: Int, n: Int): IntArray? = read(addr, n)?.let { b -> IntArray(n) { b[it].toInt() and 0xFF } }
    val zp = bytes(0, 0xA0) ?: return null
    val xy = bytes(ROOM_XY, 2) ?: return null
    val entry = bytes(PLAYER_ENTRY, 2) ?: return null
    val objects = bytes(OBJECT_ROOM, 80) ?: return null
    val flasks = bytes(ENERGY_COLLECTED, 16) ?: return null
    val puzzles = bytes(PUZZLES, 11) ?: return null
    val doors = bytes(DOORS_OPENED, 8) ?: return null
    val escape = bytes(ESCAPE_KEY, 1) ?: return null
    val room = zp[PLAYER_ROOM]
    if (room >= 240) return null
    // room_x counts in BCD (L1 is &99), room_y in two's complement.
    val x = bcd(xy[0]).let { if (it >= 50) it - 100 else it }
    val y = xy[1].toByte().toInt()
    return CitadelState(
        room = room, x = x, y = y, entryX = entry[0], entryY = entry[1],
        score = bcd(zp[SCORE]),
        energy = zp[ENERGY + 1] * 100 + bcd(zp[ENERGY]),
        pockets = intArrayOf(zp[POCKETS], zp[POCKETS + 1]),
        objectRoom = objects.copyOfRange(0, 40),
        objectSprite = objects.copyOfRange(40, 80),
        energyCollected = BooleanArray(16) { flasks[it] != 0 },
        switches = puzzles.copyOfRange(0, 2),
        crystalsInSanctuary = BooleanArray(5) { puzzles[2 + it] != 0 },
        cannonLoaded = puzzles[7],
        guardianFed = puzzles[8] != 0,
        bonesNeeded = puzzles[9],
        starPortDestroyed = puzzles[10] != 0,
        doorsOpened = BooleanArray(8) { doors[it] != 0 },
        // The title screen tours rooms with ESCAPE disabled and the
        // co-ordinates at the Main Hall.
        onTitleScreen = escape[0] == 0xFF && x == 0 && y == 0 && room != 0x83,
    )
}

// ── The map ────────────────────────────────────────────────────────────────

/** Room names from the game's room data; copies of other rooms take their name. */
private val ROOM_NAMES: Map<Int, String> = mapOf(
    0x01 to "The Pyramid", 0x05 to "Star Port", 0x06 to "The Palace", 0x0B to "Central Tower",
    0x15 to "The Pyramid", 0x16 to "The Palace", 0x17 to "The Palace", 0x18 to "The Palace",
    0x19 to "Star Port", 0x1A to "The Palace", 0x1C to "West Tower", 0x22 to "East Tower",
    0x29 to "The Pyramid", 0x30 to "West Tower", 0x36 to "East Tower", 0x3D to "The Pyramid",
    0x44 to "West Tower", 0x47 to "Central Tower", 0x49 to "Top of Castle", 0x4A to "East Tower",
    0x58 to "West Wing", 0x59 to "West Wing", 0x5A to "The Kitchen", 0x5B to "Thorn Maze",
    0x5C to "West Wing", 0x5D to "East Fireplace", 0x5E to "East Wing", 0x64 to "The Desert",
    0x65 to "Stonehenge", 0x66 to "The Wasteland", 0x67 to "The Wasteland", 0x68 to "Witch's House",
    0x69 to "The Wasteland", 0x6A to "The Wasteland", 0x6B to "West Gate", 0x6C to "West Wing",
    0x6D to "Freezer", 0x6E to "Freezer", 0x6F to "Central Castle", 0x70 to "Arena",
    0x71 to "The Well Wheel", 0x72 to "East Wing", 0x78 to "The Island", 0x79 to "The Island",
    0x80 to "West Wing", 0x81 to "West Wing", 0x82 to "Central Castle", 0x83 to "Main Hall",
    0x84 to "Central Castle", 0x85 to "The Well", 0x86 to "The Prison", 0x87 to "On The Beach",
    0x88 to "On The Beach", 0x89 to "The Ocean", 0x8A to "The Island", 0x8B to "The Island",
    0x8C to "The Island", 0x91 to "Witch's House", 0x92 to "Witch's House", 0x95 to "The Cellar",
    0x96 to "The Cellar", 0x97 to "The Cellar", 0x99 to "The Well", 0x9C to "The Ocean",
    0x9D to "The Ocean", 0x9E to "The Ocean", 0xA3 to "Star Port", 0xA6 to "Witch's House",
    0xA9 to "The Cellar", 0xAD to "The Well", 0xB6 to "The Sanctuary", 0xB7 to "Star Port",
    0xC0 to "The Drain", 0xC1 to "The Well", 0xC2 to "The Lab", 0xC3 to "The Lab",
    0xC4 to "The Lab", 0xC9 to "The Island", 0xCA to "The Temple", 0xCB to "The Temple",
    0xCC to "The Island", 0xCD to "The Island", 0xCE to "The Island", 0xD5 to "The Well",
    0xD6 to "The Well", 0xE2 to "The Island", 0xE3 to "The Island", 0xE4 to "The Ocean",
    0xEA to "The Mountains", 0xEB to "The Desert", 0xEC to "The Desert", 0xED to "The Pyramid",
    0xEE to "The Desert", 0xEF to "The Desert",
)

fun citadelRoomName(room: Int): String = ROOM_NAMES[room] ?: "Outside"

/** Marduk's palace on the alien planet, reached from the Star Port. */
val CITADEL_PLANET = setOf(0x05, 0x06, 0x16, 0x17, 0x18, 0x19, 0x1A)

/**
 * A room's co-ordinates as the game shows them (screens from the Main Hall,
 * or from the palace Star Port on the planet). Room numbers step by 1 across
 * and 20 down, but the game stitches some areas on elsewhere (the desert and
 * pyramid west of the Wasteland, the island past the Ocean, the inside of the
 * Witch's House), so those are mapped by area.
 */
fun citadelCoords(room: Int): Pair<Int, Int> {
    val r = room / 20
    val c = room % 20
    return when {
        room in CITADEL_PLANET -> (c - 5) to (1 - r)
        room == 0x01 || room == 0x15 || room == 0x29 || room == 0x3D -> -14 to -r   // inside the pyramid
        r == 11 && c >= 9 -> (c - 31) to 1                  // desert and mountains, west of the Wasteland
        r in 7..8 && c in 4..7 -> (c - 12) to (7 - r)       // inside the Witch's House
        room == 0x78 || room == 0x79 -> (c + 9) to 1        // the island, past the Ocean
        room == 0x8C -> 9 to 0
        r in 8..11 && c <= 8 -> (c + 10) to (11 - r)        // the island, temple, sanctuary and star port
        else -> (c - 11) to (6 - r)                         // the castle, wasteland, well and beach
    }
}

/** Co-ordinates as the game prints them: "L2,d1" for 2 left, 1 down. */
fun citadelCoordText(x: Int, y: Int): String =
    (if (x < 0) "L${-x}" else "$x") + "," + (if (y < 0) "d${-y}" else "$y")

// ── Objects ────────────────────────────────────────────────────────────────

private const val PHARAOH_MASK_A = 0      // above the Prison
private const val PHARAOH_MASK_B = 1      // bottom of the Well
private const val ICE_CRYSTAL = 2
private const val CHICKEN = 3
private const val BONE_CYAN = 4
private const val BONE_MAGENTA = 5
private const val SKULL = 6
private const val CRYSTAL_RKM = 7         // crystals by object: sanctuary slots 0, 1, 3, 4
private const val CRYSTAL_YKW = 8
private const val CRYSTAL_CKG = 9
private const val CRYSTAL_GKY = 10
private const val KEY_MW = 11             // keys 11-18 open doors 0-7
private const val KEY_GY = 12
private const val KEY_RY = 13
private const val KEY_GC = 14
private const val KEY_CW = 15
private const val KEY_RM = 16
private const val KEY_BC = 17
private const val KEY_RW = 18
private const val GUNPOWDER = 21
private const val BARREL = 23
private const val STATUE = 25             // the pyramid figurine: becomes crystal 2
private const val CANNONBALL = 26
private const val FIGURINE = 27           // the jewelled figurine in the palace
private const val TRAMPOLINE = 30
private const val CROWN_3 = 35
private const val CROWN_2 = 36
private const val CROWN_1 = 37

private const val SPRITE_COOKED_CHICKEN = 0x16
private const val SPRITE_CRYSTAL_CKM = 0x02

private val OBJECT_NAMES = mapOf(
    PHARAOH_MASK_A to "Pharaoh mask", PHARAOH_MASK_B to "Pharaoh mask", ICE_CRYSTAL to "ice crystal",
    CHICKEN to "chicken", BONE_CYAN to "cyan bone", BONE_MAGENTA to "magenta bone", SKULL to "skull",
    CRYSTAL_RKM to "crystal", CRYSTAL_YKW to "crystal", CRYSTAL_CKG to "crystal", CRYSTAL_GKY to "crystal",
    KEY_MW to "magenta/white key", KEY_GY to "green/yellow key", KEY_RY to "red/yellow key",
    KEY_GC to "green/cyan key", KEY_CW to "cyan/white key", KEY_RM to "red/magenta key",
    KEY_BC to "blue/cyan key", KEY_RW to "red/white key", GUNPOWDER to "gunpowder", BARREL to "barrel",
    STATUE to "green statue", CANNONBALL to "cannonball", FIGURINE to "jewelled figurine",
    TRAMPOLINE to "trampoline",
)

// ── The route ──────────────────────────────────────────────────────────────

/**
 * One milestone of the route. [room] is where to do it; [needs] are objects to
 * carry there first (the guide sends the player to each in turn, wherever it
 * now is); [fetch] is an object to pick up, when that is the whole step.
 */
class CitadelStep(
    val title: String,
    val tip: String = "",
    val room: Int? = null,
    val needs: List<Int> = emptyList(),
    val fetch: Int? = null,
    val done: (CitadelState) -> Boolean,
)

private fun deposit(obj: Int, slot: Int, title: String, tip: String = "") = CitadelStep(
    title, tip.ifEmpty { "Drop it on the table in the Temple (13,1)." }, room = 0xCB, needs = listOf(obj),
) { it.crystalsInSanctuary[slot] }

/** The solution as milestones, in the order played (8bs.com, "the best way"). */
val CITADEL_ROUTE: List<CitadelStep> = listOf(
    CitadelStep("Take the red/yellow key", "West Wing, two screens left of the Main Hall.", fetch = KEY_RY) {
        it.carrying(KEY_RY) || it.doorsOpened[2] },
    CitadelStep("Take the red/magenta key", "On the Beach, four screens right.", fetch = KEY_RM) {
        it.carrying(KEY_RM) || it.doorsOpened[5] },
    CitadelStep("Open the red/yellow door", "Up the East Tower; the door leads to its top. Take the energy down the rope.",
        room = 0x4A, needs = listOf(KEY_RY)) { it.doorsOpened[2] },
    CitadelStep("Knock the switch right of the Central Tower",
        "From the East Tower top, jump off left holding LEFT; press ESCAPE before landing when you see Top of " +
            "Castle (2,3), land on the blue wall and go left.", room = 0x48) { it.switches[0] != 0 },
    CitadelStep("Open the red/magenta door", "In the Cellar, below and left of the Main Hall.",
        room = 0x96, needs = listOf(KEY_RM)) { it.doorsOpened[5] },
    CitadelStep("Take the ice crystal", "Right of the red/magenta door in the Cellar. It freezes water you carry it over.",
        fetch = ICE_CRYSTAL) { it.carrying(ICE_CRYSTAL) || it.usedUp(CROWN_1) },
    CitadelStep("Take the green/cyan key", "Down the rope from the top of the East Tower.", fetch = KEY_GC) {
        it.carrying(KEY_GC) || it.doorsOpened[3] },
    CitadelStep("Open the green/cyan door", "From the East Wing (3,1) ride the lift and jump off left at its highest point into the gap.",
        room = 0x71, needs = listOf(KEY_GC)) { it.doorsOpened[3] },
    CitadelStep("Knock the Well Wheel switch", "Behind the green/cyan door.", room = 0x71) { it.switches[1] != 0 },
    CitadelStep("Take the first crown", "Carry the ice crystal to the bottom of the Well (2,d4) so the water freezes, " +
        "jump into the gap on the right and swim for the crown.", room = 0xD6, needs = listOf(ICE_CRYSTAL)) {
        it.usedUp(CROWN_1) },
    CitadelStep("Take the magenta/white key", "Swap the ice crystal for it; you're done with the crystal.", fetch = KEY_MW) {
        it.carrying(KEY_MW) || it.doorsOpened[0] },
    CitadelStep("Take a Pharaoh mask", "At the bottom of the Well, with an energy flask.", fetch = PHARAOH_MASK_B) {
        it.carrying(PHARAOH_MASK_B) || it.objectRoom[PHARAOH_MASK_B] == 0x01 },
    CitadelStep("Open the magenta/white door", "In the Wasteland. Pull the lever in the West Wing (L3,0) to start the lift on the way.",
        room = 0x6A, needs = listOf(KEY_MW)) { it.doorsOpened[0] },
    CitadelStep("Take the cannonball", "Far west in the Mountains.", fetch = CANNONBALL) {
        it.moved(CANNONBALL, 0xEA) },
    CitadelStep("Leave the mask inside the Pyramid", "Enter by the secret way in near the top of the right-hand side. " +
        "Go down to (L14,0) and drop the mask quickly: it stops the mummies.", room = 0x01, needs = listOf(PHARAOH_MASK_B)) {
        it.objectRoom[PHARAOH_MASK_B] == 0x01 || it.moved(BONE_CYAN, 0x01) },
    CitadelStep("Take the cyan bone", "In the Pyramid. Leave the mask behind.", fetch = BONE_CYAN) {
        it.moved(BONE_CYAN, 0x01) },
    CitadelStep("Take the green/yellow key", "Swap the bone for it outside the Witch's House.", fetch = KEY_GY) {
        it.carrying(KEY_GY) || it.doorsOpened[1] },
    CitadelStep("Open the green/yellow door", "Up the West Tower. Take the energy at (L3,4).",
        room = 0x44, needs = listOf(KEY_GY)) { it.doorsOpened[1] },
    CitadelStep("Take the skull", "At the very top of the West Tower; avoid the stars. Jump off right to land at (L2,3).",
        fetch = SKULL) { it.moved(SKULL, 0x1C) },
    CitadelStep("Take the crystal in the Central Tower", "Leave the skull in the Kitchen (L1,2) and the cannonball in the " +
        "Arena (1,1). From (L1,3) ride the lift, jump off right at its top onto the Central Tower and go through the door marked C.",
        fetch = CRYSTAL_YKW) { it.moved(CRYSTAL_YKW, 0x0B) },
    CitadelStep("Take the gunpowder", "Through the Thorn Maze (0,2). Then fetch the cannonball from the Arena and leave the crystal there.",
        fetch = GUNPOWDER) { it.moved(GUNPOWDER, 0x5C) },
    CitadelStep("Fire the cannon in the Lab", "From the Well (2,d2) walk off the bottom-right cranny holding RIGHT, " +
        "alcove to alcove, then right to the Lab. Walk over the cannon twice.", room = 0xC2,
        needs = listOf(CANNONBALL, GUNPOWDER)) { it.cannonLoaded >= 2 },
    CitadelStep("Take the crystal in the Lab", "Through the hole the cannon made. Leave it in the Main Hall.",
        fetch = CRYSTAL_GKY) { it.moved(CRYSTAL_GKY, 0xC4) },
    CitadelStep("Take the cyan/white key", "West Wing (L3,1).", fetch = KEY_CW) {
        it.carrying(KEY_CW) || it.doorsOpened[4] },
    CitadelStep("Take a trampoline", "West Wing, up a screen (L3,2).", fetch = TRAMPOLINE) {
        it.moved(TRAMPOLINE, 0x58) },
    CitadelStep("Open the cyan/white door", "Central Castle, just above the Main Hall.",
        room = 0x6F, needs = listOf(KEY_CW)) { it.doorsOpened[4] },
    CitadelStep("Take the second crown", "At (L2,d1) swim left for the blue/cyan key, drop the trampoline and bounce into " +
        "the corner of roof and pillar; press UP and jump, then walk left.", room = 0x94, needs = listOf(TRAMPOLINE)) {
        it.usedUp(CROWN_2) },
    CitadelStep("Open the blue/cyan door", "In the Prison (3,0). Leave the trampoline inside.",
        room = 0x86, needs = listOf(KEY_BC)) { it.doorsOpened[6] },
    CitadelStep("Take the crystal above the Main Hall", "Through the cyan/white door, left to the Freezer (L2,1), " +
        "down to the bottom and right along the bottom passage.", fetch = CRYSTAL_RKM) { it.moved(CRYSTAL_RKM, 0x6F) },
    CitadelStep("Cook the chicken", "The chicken is in the Freezer (L1,1). Drop it under the fire in the Kitchen and pick it up again.",
        room = 0x5A, needs = listOf(CHICKEN)) { it.objectSprite[CHICKEN] == SPRITE_COOKED_CHICKEN || it.guardianFed },
    CitadelStep("Feed the temple guardian", "On the island past the Ocean. Carry the cooked chicken past it.",
        room = 0xCA, needs = listOf(CHICKEN)) { it.guardianFed },
    deposit(CRYSTAL_RKM, 0, "Place the first crystal"),
    CitadelStep("Take the green statue", "On the far side of the island; run from the white fireball. " +
        "Then climb the rope at (9,1) for energy.", fetch = STATUE) {
        it.moved(STATUE, 0xE4) },
    CitadelStep("Take the other Pharaoh mask", "Bounce on the trampoline in the Prison onto the chain and climb up.",
        fetch = PHARAOH_MASK_A) { it.moved(PHARAOH_MASK_A, 0x72) },
    CitadelStep("Turn the statue into a crystal", "In the Pyramid drop the mask at (L14,d2) to stop the mummies, " +
        "then walk across the tomb at (L14,d3).", room = 0x3D, needs = listOf(STATUE)) {
        it.objectSprite[STATUE] == SPRITE_CRYSTAL_CKM || it.crystalsInSanctuary[2] },
    deposit(CRYSTAL_YKW, 1, "Place the Central Tower crystal", "Collect it from the Arena; drop it on the Temple table."),
    deposit(STATUE, 2, "Place the Pyramid crystal"),
    CitadelStep("Take the magenta bone", "East Wing (3,2).", fetch = BONE_MAGENTA) {
        it.moved(BONE_MAGENTA, 0x5E) },
    CitadelStep("Bring the barrel to the Witch's House", "The barrel is one screen left of the Main Hall. " +
        "Drop it by the house, bounce onto the roof and go down the chimney.", room = 0x68, needs = listOf(BARREL)) {
        it.objectRoom[BARREL] == 0x68 || it.witchDead },
    CitadelStep("Drop both bones in the cauldron", "Inside the Witch's House: jump the first flame and walk over the cauldron.",
        room = 0x92, needs = listOf(BONE_CYAN, BONE_MAGENTA)) {
        (it.usedUp(BONE_CYAN) && it.usedUp(BONE_MAGENTA)) || it.witchDead },
    CitadelStep("Drop the skull in the cauldron", "Fetch the skull from the Kitchen. The witch burns when she passes over the cauldron.",
        room = 0x92, needs = listOf(SKULL)) { it.witchDead },
    CitadelStep("Take the crystal below the Witch's House", "Down at (L6,d1); take the energy at (L5,0) on the way.",
        fetch = CRYSTAL_CKG) { it.moved(CRYSTAL_CKG, 0xA6) },
    CitadelStep("Take the third crown", "From (L7,0) shoot the monk on the ladder, jump into the top-left corner, then walk left.",
        room = 0x90) { it.usedUp(CROWN_3) },
    deposit(CRYSTAL_CKG, 3, "Place the Witch's House crystal"),
    deposit(CRYSTAL_GKY, 4, "Place the Lab crystal", "Collect it from the Main Hall; drop it on the Temple table."),
    CitadelStep("Teleport to the planet", "Go up to the Star Port (13,2) and stand on the table.", room = 0xB7) {
        it.onPlanet || it.carrying(FIGURINE) || it.starPortDestroyed },
    CitadelStep("Take the red/white key", "Right, up, then left: planet (0,1).", fetch = KEY_RW) {
        it.carrying(KEY_RW) || it.doorsOpened[7] },
    CitadelStep("Open the red/white door", "Left under the table from the Star Port.", room = 0x18, needs = listOf(KEY_RW)) {
        it.doorsOpened[7] },
    CitadelStep("Take the jewelled figurine", "In the palace beyond the red/white door.", fetch = FIGURINE) {
        it.carrying(FIGURINE) || it.starPortDestroyed },
    CitadelStep("Destroy the teleport", "Back to the Star Port (0,0) and stand on the table with the figurine.",
        room = 0x19, needs = listOf(FIGURINE)) { it.starPortDestroyed },
)

// Energy flasks by number (rooms_that_contain_energy); each is worth 2 points.
private val FLASK_ROOMS = intArrayOf(
    0x5B, 0x6E, 0x71, 0x80, 0x9D, 0xA3, 0xA9, 0xEC, 0x01, 0xD5, 0x36, 0x30, 0x92, 0xC3, 0x48, 0x86,
)

// ── Advice ─────────────────────────────────────────────────────────────────

class CitadelAdvice(
    val step: CitadelStep?,         // null once the teleport is destroyed
    val stepNumber: Int,            // 1-based
    val action: String,             // what to do now (may be "Fetch the ..." for a needed object)
    val targetRoom: Int?,
    val targetCoords: Pair<Int, Int>?,
    val direction: String,          // the next move on the best route, e.g. "Go left", or "On this screen"
    val arrow: String,
    val nextRoom: Int?,             // the screen that move leads to
    val screensToGo: Int?,          // screens along the route, null if the route isn't known
    val percent: Int,
    val flasksLeft: List<Int>,      // rooms with flasks still to collect
)

/** What to do next, where it is and how far through the route the player is. */
fun citadelAdvice(s: CitadelState): CitadelAdvice {
    val route = CITADEL_ROUTE
    val percent = route.count { it.done(s) } * 100 / route.size
    val flasksLeft = FLASK_ROOMS.indices.filter { !s.energyCollected[it] }.map { FLASK_ROOMS[it] }
    val index = route.indexOfFirst { !it.done(s) }
    if (index < 0) return CitadelAdvice(null, route.size, "Teleport destroyed: well done!", null, null, "", "★",
        null, null, 100, flasksLeft)
    val step = route[index]

    // An object to fetch first (wherever the player left it), else the step's own room.
    val missing = step.needs.firstOrNull { !s.carrying(it) && !s.usedUp(it) }
    val fetch = step.fetch ?: missing
    val action: String
    val target: Int?
    if (fetch != null && !s.carrying(fetch)) {
        val name = OBJECT_NAMES[fetch] ?: "object"
        action = if (step.fetch != null) step.title else "Fetch the $name"
        target = s.objectRoom[fetch].takeIf { it != 0 }
    } else {
        action = step.title
        target = step.room
    }

    val coords = target?.let { citadelCoords(it) }
    var direction = ""
    var arrow = ""
    var nextRoom: Int? = null
    var screens: Int? = null
    if (target != null && coords != null) {
        val conds = citadelMapConditions(s)
        val port = citadelEntryPort(s.room, s.entryX, s.entryY, conds)
        val path = citadelRoute(s.room, port, target, conds)
            ?: if (port >= 0) citadelRoute(s.room, -1, target, conds) else null
        when {
            target == s.room -> { direction = "On this screen"; arrow = "●" }
            path != null && path.isNotEmpty() -> {
                val move = path.first()
                direction = move.hint ?: listOfNotNull(WAY_TEXT[move.way] ?: "Go on", move.where).joinToString(" ")
                arrow = WAY_ARROW[move.way] ?: "✦"
                nextRoom = move.next.takeIf { it != s.room }
                screens = path.count { it.next != it.room }
            }
            (target in CITADEL_PLANET) != s.onPlanet -> {
                direction = if (target in CITADEL_PLANET) "Teleport from the Star Port (13,2)" else "Teleport back from the Star Port (0,0)"
                arrow = "✦"
            }
            else -> {
                // No known route (a puzzle still to solve on the way): point at it.
                val dx = coords.first - s.x
                val dy = coords.second - s.y
                direction = listOfNotNull(
                    if (dx < 0) "${-dx} left" else if (dx > 0) "$dx right" else null,
                    if (dy > 0) "$dy up" else if (dy < 0) "${-dy} down" else null,
                ).joinToString(", ").ifEmpty { "Nearby" }
                arrow = arrowFor(dx, dy)
            }
        }
    }
    return CitadelAdvice(step, index + 1, action, target, coords, direction, arrow, nextRoom, screens, percent, flasksLeft)
}

private val WAY_TEXT = mapOf('L' to "Go left", 'R' to "Go right", 'U' to "Go up", 'D' to "Go down")
private val WAY_ARROW = mapOf('L' to "←", 'R' to "→", 'U' to "↑", 'D' to "↓", 'J' to "↑", 'H' to "↓", 'T' to "✦")

private fun arrowFor(dx: Int, dy: Int): String {
    // Mostly sideways or mostly vertical gets a straight arrow.
    val ax = abs(dx); val ay = abs(dy)
    return when {
        ay * 2 < ax -> if (dx < 0) "←" else "→"
        ax * 2 < ay -> if (dy > 0) "↑" else "↓"
        dx < 0 && dy > 0 -> "↖"
        dx > 0 && dy > 0 -> "↗"
        dx < 0 -> "↙"
        else -> "↘"
    }
}
