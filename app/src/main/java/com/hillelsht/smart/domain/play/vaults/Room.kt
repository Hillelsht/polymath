package com.hillelsht.smart.domain.play.vaults

/**
 * One room of the vaults — a screen you enter, cross and leave, the way *Prince of Persia* is
 * built. Rooms rather than a scrolling world because it makes a trap's rhythm learnable and makes
 * a room the natural unit of authored content.
 *
 * Geometry is a list of solid spans rather than a tile grid: a span carries its own left and right
 * edge, which is exactly what a ledge-grab and a gap-width check need, and it keeps authored JSON
 * small.
 */
data class Ledge(
    val id: String,
    /** Inclusive left edge, in world units. */
    val x0: Double,
    /** Exclusive right edge. */
    val x1: Double,
    /** Surface height. Smaller is higher on screen. */
    val y: Double,
    /**
     * Whether standing here starts a countdown to the floor giving way.
     *
     * A collapsing tile is the cheapest way to turn a safe route into a decision: it does not
     * demand precision, it demands that you stop dithering. Once gone it stays gone for the rest
     * of the run through this room, so a mistake changes the room rather than resetting it.
     */
    val collapsing: Boolean = false,
) {
    val width: Double get() = x1 - x0
    fun spans(x: Double): Boolean = x >= x0 && x <= x1
}

/**
 * A blade that opens and shuts on a fixed cycle.
 *
 * The cycle is a pure function of the frame counter, so it is perfectly predictable and perfectly
 * reproducible — you learn it once and it is yours. That is the whole appeal, and it is why the
 * period is authored per chomper rather than randomised.
 */
data class Chomper(
    val id: String,
    /** Centre of the blade. */
    val x: Double,
    /** The surface it stands on; it occupies [reach] above this. */
    val y: Double,
    val periodFrames: Int = 96,
    /** Frames at the start of each cycle during which it is open and safe to pass. */
    val openFrames: Int = 52,
    /** Offset into the cycle at frame zero, so several blades can be staggered. */
    val phase: Int = 0,
    val halfWidth: Double = 9.0,
    val reach: Double = 72.0,
) {
    fun isOpen(frame: Int): Boolean {
        val p = ((frame + phase) % periodFrames + periodFrames) % periodFrames
        return p < openFrames
    }

    /** Whether a runner standing at [x],[y] is inside the blade on a frame when it is shut. */
    fun catches(frame: Int, rx: Double, ry: Double): Boolean =
        !isOpen(frame) &&
            rx >= x - halfWidth && rx <= x + halfWidth &&
            ry <= y + 0.001 && ry >= y - reach
}

data class Room(
    val id: String,
    val ledges: List<Ledge>,
    val chompers: List<Chomper> = emptyList(),
    val spawnX: Double,
    val spawnY: Double,
    /** Crossing this x on a solid surface leaves the room. */
    val exitX: Double,
    /** Falling past this is a death, however the room is shaped. */
    val abyssY: Double,
    /** A line of prose shown on entry. Rooms are the unit of authorship; this is their voice. */
    val epigraph: String = "",
) {
    /**
     * The surface a body at [x] would land on when falling from [fromY] to [toY], or null.
     *
     * Picks the highest qualifying surface so a fall through several ledges in one frame lands on
     * the first one crossed rather than tunnelling to the bottom.
     */
    fun landingAt(x: Double, fromY: Double, toY: Double, broken: Set<String> = emptySet()): Ledge? =
        ledges.filter { it.id !in broken && it.spans(x) && it.y >= fromY && it.y <= toY }
            .minByOrNull { it.y }

    /** The surface directly under [x] at [y], if the body is resting on it. */
    fun groundAt(x: Double, y: Double, broken: Set<String> = emptySet()): Ledge? =
        ledges.firstOrNull { it.id !in broken && it.spans(x) && it.y == y }

    /**
     * A lip within reach of a falling body, for the ledge-grab.
     *
     * Both ends of every ledge are grabbable. The vertical band is deliberately tall and the
     * horizontal reach deliberately wide — Palace's grab window was six frames, which is why it
     * never felt like a move you could *choose* to make. See `Tuning.grabReach`.
     */
    fun grabbableAt(
        x: Double,
        fromY: Double,
        toY: Double,
        tuning: Tuning,
        broken: Set<String>,
    ): Pair<Ledge, Double>? {
        var best: Pair<Ledge, Double>? = null
        ledges.forEach { ledge ->
            if (ledge.id in broken) return@forEach
            // Already past the lip, but not yet past reaching it. `fromY` rather than `toY` so a
            // fast fall cannot skip straight through the band in a single frame.
            val depth = toY - ledge.y
            val inBand = depth >= tuning.grabMinDrop && fromY - ledge.y <= tuning.grabMaxDrop
            if (!inBand) return@forEach
            listOf(ledge.x0, ledge.x1).forEach { lip ->
                val near = x >= lip - tuning.grabReach && x <= lip + tuning.grabReach
                if (near && (best == null || ledge.y < best!!.first.y)) best = ledge to lip
            }
        }
        return best
    }
}
