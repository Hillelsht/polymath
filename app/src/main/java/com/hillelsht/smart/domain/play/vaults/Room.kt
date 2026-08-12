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
) {
    val width: Double get() = x1 - x0
    fun spans(x: Double): Boolean = x >= x0 && x <= x1
}

data class Room(
    val id: String,
    val ledges: List<Ledge>,
    val spawnX: Double,
    val spawnY: Double,
    /** Crossing this x on a solid surface leaves the room. */
    val exitX: Double,
    /** Falling past this is a death, however the room is shaped. */
    val abyssY: Double,
) {
    /**
     * The surface a body at [x] would land on when falling from [fromY] to [toY], or null.
     *
     * Picks the highest qualifying surface so a fall through several ledges in one frame lands on
     * the first one crossed rather than tunnelling to the bottom.
     */
    fun landingAt(x: Double, fromY: Double, toY: Double): Ledge? =
        ledges.filter { it.spans(x) && it.y >= fromY && it.y <= toY }.minByOrNull { it.y }

    /** The surface directly under [x] at [y], if the body is resting on it. */
    fun groundAt(x: Double, y: Double): Ledge? =
        ledges.firstOrNull { it.spans(x) && it.y == y }
}
