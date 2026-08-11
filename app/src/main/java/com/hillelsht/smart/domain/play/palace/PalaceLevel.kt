package com.hillelsht.smart.domain.play.palace

/**
 * A floor segment. The player's feet rest at [y] while `x` is within `[x0, x1]`; a [collapsing]
 * platform gives way after being stood on for [PalacePhysics.COLLAPSE_DELAY_MS].
 */
data class Platform(
    val id: String,
    val x0: Float,
    val x1: Float,
    val y: Float,
    val collapsing: Boolean = false,
)

/**
 * A grabbable edge. Always the top of some [platformId], so climbing up from a hang lands the
 * player exactly on that platform's surface rather than needing a second, separate coordinate.
 */
data class Ledge(
    val platformId: String,
    val x: Float,
    val y: Float,
)

/** A vertical barrier at [x] that blocks passage until answered correctly. */
data class Gate(
    val id: String,
    val x: Float,
    /** Restricts the question asked here to one category; null draws from any. */
    val categoryId: String? = null,
)

/**
 * One wing of the palace.
 *
 * Pure data, the same way [com.hillelsht.smart.domain.play.climb.ClimbMap]'s nodes are — so a
 * level can be authored, stored and replayed with nothing Android-specific, and its mechanics
 * ([PalacePhysics]) can be proven against it headlessly.
 */
data class PalaceLevel(
    val id: String,
    val title: String,
    /** The wing's theme; also the default category asked at its gates. */
    val categoryId: String?,
    val platforms: List<Platform>,
    val ledges: List<Ledge> = emptyList(),
    val gates: List<Gate> = emptyList(),
    val startX: Float,
    val startY: Float,
    val goalX: Float,
    /** Falling past this height, on the downward-positive axis, costs a life. */
    val deathY: Float,
    /** How far left of [startX] the player may run; keeps a leap backwards off the level. */
    val minX: Float = startX,
) {
    init {
        require(platforms.isNotEmpty()) { "a level needs at least one platform" }
    }
}
