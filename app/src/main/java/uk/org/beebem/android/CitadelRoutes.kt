package uk.org.beebem.android

import java.util.PriorityQueue

// ── Citadel routes ─────────────────────────────────────────────────────────
//
// The best way from the player's screen to a target screen, over the map in
// CitadelMapData.kt. That map was made from the game's own room drawings by
// simulating its movement rules (2-pixel steps, a 4-frame jump, falling,
// ropes and ladders; trampolines and lifts carry the player higher), so it
// knows which exits of each screen can really be reached from each entrance
// rather than just which way the target lies. Rooms change as puzzles are
// solved (doors, the Well Wheel draining the well, the cannon's hole), and a
// few moves can't be seen in the drawings at all; those are added by hand.

/**
 * One step of a route: leave [room] by [way] (L R U D, or a special move) for
 * [next]. [where] says which exit on that side when there is more than one,
 * e.g. "on the left" for a way up.
 */
class CitadelMove(val room: Int, val way: Char, val next: Int, val hint: String?, val where: String? = null)

private class Span(val edge: Char, val from: Int, val to: Int)

private class Drawing(val conds: IntArray, val ports: List<Span>, val exits: Map<Int, List<Span>>)

// Conditions: 1-8 door 0-7 opened, 9 Well Wheel switch, 10 cannon fired, 11 all crystals placed.
private const val COND_WELL = 9
private const val COND_CANNON = 10
private const val COND_CRYSTALS = 11
private const val COND_BUCKET = 12     // carrying the full bucket

private const val BUCKET = 20          // object number; sprite &09 empty, &0A full
private const val FULL_BUCKET = 0x0A

/**
 * Exits the drawings allow but the room's enemies don't: touching them hurts,
 * and enough hurt sends the player back to where they came in. [unless] is a
 * condition that clears the way (0: never).
 */
private class Blocked(val room: Int, val edge: Char, val from: Int, val to: Int, val unless: Int)

private val BLOCKED = listOf(
    // Three fires patrol under the climbable pillar in the East Fireplace; a full
    // bucket puts them all out (until the player leaves the room).
    Blocked(0x5D, 'U', 0, 10, COND_BUCKET),
    Blocked(0x49, 'D', 0, 10, COND_BUCKET),
    // The monk on the Central Tower floor turns the player back.
    Blocked(0x47, 'R', 0, 40, 0),
    Blocked(0x48, 'L', 0, 40, 0),
)

private class Special(val from: Int, val to: Int, val way: Char, val cond: Int, val hint: String?)

// Moves the room drawings can't show.
private val SPECIALS = listOf(
    Special(0x47, 0x0B, 'C', 0, "Go through the Central Tower doorway"),
    Special(0x0B, 0x47, 'C', 0, "Go back out through the doorway"),
    Special(0x68, 0x91, 'H', 0, "Bounce onto the roof and go down the chimney"),
    Special(0x91, 0x68, 'U', 0, "Climb back up the chimney"),
    Special(0xB7, 0x19, 'T', COND_CRYSTALS, "Stand on the table to teleport"),
    Special(0x19, 0xB7, 'T', 0, "Stand on the table to teleport back"),
    Special(0xED, 0x01, 'S', 0, "Secret entrance near the top right"),
    Special(0x95, 0x94, 'S', 0, "Bounce off the trampoline into the stone, top left"),
    Special(0x91, 0x90, 'S', 0, "Jump into the top-left corner"),
    Special(0xD5, 0xD6, 'R', 0, "Freeze the water, then into the gap on the right"),
    Special(0xD6, 0xD5, 'L', 0, null),
)

private val REMAP = mapOf(0x7A to 0xC9, 0xC8 to 0x79, 0x63 to 0xEF, 0xF0 to 0x64, 0x7D to 0x68)
private val OPPOSITE = mapOf('L' to 'R', 'R' to 'L', 'U' to 'D', 'D' to 'U')
private const val VOID_REACH = 12   // byte columns a jump up into empty space can drift

private val MAP: Map<Int, List<Drawing>> by lazy { parseMap(CITADEL_MAP_DATA) }

private fun spans(text: String): List<Span> =
    if (text.isEmpty()) emptyList()
    else text.split(',').map { Span(it[0], it.substring(1).substringBefore('-').toInt(), it.substringAfter('-').toInt()) }

private fun parseMap(data: String): Map<Int, List<Drawing>> {
    val out = HashMap<Int, MutableList<Drawing>>()
    for (line in data.lineSequence()) {
        if (line.isBlank()) continue
        val (head, ports, exits) = line.split('|')
        val room = head.substringBefore(' ').toInt(16)
        val conds = head.substringAfter(' ').let { if (it == "-") IntArray(0) else it.split(',').map(String::toInt).toIntArray() }
        val byPort = exits.split(' ').filter { it.isNotEmpty() }.associate {
            it.substringBefore('=').toInt() to spans(it.substringAfter('='))
        }
        out.getOrPut(room) { ArrayList() }.add(Drawing(conds, spans(ports), byPort))
    }
    return out
}

private fun neighbour(room: Int, edge: Char): Int? {
    val n = (room + when (edge) { 'L' -> -1; 'R' -> 1; 'U' -> -20; else -> 20 }) and 0xFF
    val m = REMAP[n] ?: n
    return m.takeIf { it < 240 }
}

/** The room as drawn in this game state; later drawings are more specific. Null for empty space. */
private fun drawing(room: Int, conds: Set<Int>): Drawing? =
    MAP[room]?.lastOrNull { d -> d.conds.all { it in conds } }

/** The puzzle conditions that change the map, from the game state. */
fun citadelMapConditions(s: CitadelState): Set<Int> = buildSet {
    s.doorsOpened.forEachIndexed { i, open -> if (open) add(1 + i) }
    if (s.switches[1] != 0) add(COND_WELL)
    if (s.cannonLoaded >= 0x80) add(COND_CANNON)
    if (s.crystalsPlaced == 5) add(COND_CRYSTALS)
    if (s.carrying(BUCKET) && s.objectSprite[BUCKET] == FULL_BUCKET) add(COND_BUCKET)
}

/**
 * Which doorway of [room] the player came in by, from where the game put them
 * on entry (player_entry_x/y), or -1 if it can't tell.
 */
fun citadelEntryPort(room: Int, entryX: Int, entryY: Int, conds: Set<Int>): Int {
    val ports = drawing(room, conds)?.ports ?: return -1
    val (edge, at) = when {
        entryY <= 0x40 -> 'U' to entryX / 2
        entryY >= 0xF0 -> 'D' to entryX / 2
        entryX == 0 -> 'L' to (entryY - 72) / 4
        entryX >= 0x96 -> 'R' to (entryY - 72) / 4
        else -> return -1
    }
    return ports.indices.minByOrNull { i ->
        val p = ports[i]
        if (p.edge != edge) Int.MAX_VALUE else maxOf(0, p.from - at, at - p.to)
    }?.takeIf { ports[it].edge == edge && maxOf(0, ports[it].from - at, at - ports[it].to) <= 2 } ?: -1
}

/** Where along a side an exit is: across for up and down (byte column 0-75), else height (block row 0-40). */
private fun whereOnEdge(edge: Char, at: Int): String =
    if (edge == 'U' || edge == 'D') when {
        at < 25 -> "on the left"
        at > 50 -> "on the right"
        else -> "in the middle"
    } else when {
        at < 14 -> "at the top"
        at > 27 -> "at the bottom"
        else -> "halfway up"
    }

private class Edge(val cost: Int, val move: CitadelMove, val nextPort: Int)

private fun moves(room: Int, port: Int, conds: Set<Int>): List<Edge> {
    val out = ArrayList<Edge>()
    val here = drawing(room, conds) ?: return out
    val onPlanet = room in CITADEL_PLANET
    for (e in here.exits[port] ?: here.exits[-1].orEmpty()) {
        val n = neighbour(room, e.edge) ?: continue
        if ((n in CITADEL_PLANET) != onPlanet) continue
        if (BLOCKED.any { b -> b.room == room && b.edge == e.edge && b.from <= e.to && e.from <= b.to &&
                (b.unless == 0 || b.unless !in conds) }) continue
        val there = drawing(n, conds)
        if (there == null) {
            // Empty space: only worth jumping up into, to come down further along.
            if (e.edge != 'U' || neighbour(n, 'D') != room) continue
            here.ports.forEachIndexed { q, p ->
                if (p.edge == 'U' && p.from <= e.to + VOID_REACH && e.from - VOID_REACH <= p.to && q != port)
                    out.add(Edge(3, CitadelMove(room, 'J', room, "Jump up off the top and come down further along"), q))
            }
            continue
        }
        val want = OPPOSITE.getValue(e.edge)
        val several = here.ports.count { it.edge == e.edge } > 1
        there.ports.forEachIndexed { q, p ->
            if (p.edge == want && p.from <= e.to && e.from <= p.to) {
                val where = if (several) whereOnEdge(e.edge, (maxOf(e.from, p.from) + minOf(e.to, p.to)) / 2) else null
                out.add(Edge(if (e.edge == 'U') 2 else 1, CitadelMove(room, e.edge, n, null, where), q))
            }
        }
    }
    for (sp in SPECIALS) if (sp.from == room && (sp.cond == 0 || sp.cond in conds))
        out.add(Edge(1, CitadelMove(room, sp.way, sp.to, sp.hint), -1))
    return out
}

/** The best route from [from] (entered by [port]) to [to], or null if there isn't one. */
fun citadelRoute(from: Int, port: Int, to: Int, conds: Set<Int>): List<CitadelMove>? {
    if (from == to) return emptyList()
    fun key(room: Int, p: Int) = room * 64 + p + 1
    val dist = HashMap<Int, Int>()
    val prev = HashMap<Int, Pair<Int, CitadelMove>>()
    val queue = PriorityQueue<IntArray>(compareBy { it[0] })
    dist[key(from, port)] = 0
    queue.add(intArrayOf(0, from, port))
    while (queue.isNotEmpty()) {
        val (d, room, p) = queue.poll()
        val k = key(room, p)
        if (d > (dist[k] ?: Int.MAX_VALUE)) continue
        if (room == to) {
            val path = ArrayList<CitadelMove>()
            var at = k
            while (true) { val (back, move) = prev[at] ?: break; path.add(move); at = back }
            return path.reversed()
        }
        for (edge in moves(room, p, conds)) {
            val nk = key(edge.move.next, edge.nextPort)
            if (d + edge.cost < (dist[nk] ?: Int.MAX_VALUE)) {
                dist[nk] = d + edge.cost
                prev[nk] = k to edge.move
                queue.add(intArrayOf(d + edge.cost, edge.move.next, edge.nextPort))
            }
        }
    }
    return null
}
