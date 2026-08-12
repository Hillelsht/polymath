package com.hillelsht.smart.domain.play.vaults

/**
 * The opening rooms, in Kotlin.
 *
 * These will move to `packs/play/vaults/` as authored JSON once the movement is settled — a room
 * is content, and content should ship without an app release. They are code for now on purpose:
 * the geometry is still moving while the physics is being tuned, and there is no sense building a
 * publishing pipeline around numbers that change every hour. [Relics][com.hillelsht.smart.domain.play.climb.Relics]
 * made the same trade and kept a hand-built fallback roster.
 *
 * Every room here is gated by `RoomsTest` on [Playtest.MIN_MARGIN_FRAMES], so a room that demands
 * frame-perfect timing fails the build rather than reaching a player.
 */
object Rooms {

    /**
     * The Threshold — one gap, one jump, nothing else.
     *
     * The first room teaches the only verb that matters and gives generous room to learn it: a
     * long runway to reach full speed, and a gap narrow enough that the jump window is wide.
     */
    val threshold = Room(
        id = "threshold",
        ledges = listOf(
            Ledge("near", x0 = 0.0, x1 = 200.0, y = 0.0),
            Ledge("far", x0 = 256.0, x1 = 600.0, y = 0.0),
        ),
        spawnX = 20.0,
        spawnY = 0.0,
        exitX = 580.0,
        abyssY = 400.0,
    )

    /**
     * The Step Down — the far side sits lower, so the jump is more forgiving than it looks and the
     * player learns that height is a resource rather than only a hazard.
     */
    val stepDown = Room(
        id = "step-down",
        ledges = listOf(
            Ledge("high", x0 = 0.0, x1 = 220.0, y = 0.0),
            Ledge("low", x0 = 300.0, x1 = 640.0, y = 64.0),
        ),
        spawnX = 20.0,
        spawnY = 0.0,
        exitX = 620.0,
        abyssY = 400.0,
    )

    val all = listOf(threshold, stepDown)
}
