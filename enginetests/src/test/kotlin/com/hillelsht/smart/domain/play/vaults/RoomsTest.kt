package com.hillelsht.smart.domain.play.vaults

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The gate that replaces Palace's level test.
 *
 * Palace asserted "a scripted player completes this level", which was true of a level nobody could
 * play. These assert that the *window* of inputs completing each room is wide enough for a human,
 * and print the number so a room getting tighter is visible in the build log before it is visible
 * to a player.
 */
class RoomsTest {

    private val tuning = Tuning()

    @Test
    fun `every room is completable, with enough timing slack for a human`() {
        Rooms.all.forEach { room ->
            val windows = Playtest.jumpWindows(room, tuning)
            val margin = Playtest.margin(room, tuning)
            val ms = margin * 1_000 / Tuning.FPS
            println("  ${room.id}: windows=$windows margin=$margin frames (${ms}ms)")

            assertTrue(windows.isNotEmpty(), "${room.id} cannot be completed by any single jump")
            assertTrue(
                margin >= Playtest.MIN_MARGIN_FRAMES,
                "${room.id} needs a jump within $margin frames — below the " +
                    "${Playtest.MIN_MARGIN_FRAMES}-frame floor, so it will read as broken",
            )
        }
    }

    @Test
    fun `a room with a real gap cannot be walked across`() {
        // Guards the gate above: if a room were accidentally flat, every jump frame would "pass"
        // and the margin would be meaninglessly huge.
        assertTrue(
            !Playtest.completableWithoutJumping(Rooms.threshold, tuning),
            "the threshold should require a jump, or its margin proves nothing",
        )
    }

    @Test
    fun `a gap wider than the jump arc is reported as impossible, not merely hard`() {
        val impossible = Room(
            id = "too-far",
            ledges = listOf(
                Ledge("near", 0.0, 200.0, 0.0),
                Ledge("far", 480.0, 800.0, 0.0),
            ),
            spawnX = 20.0, spawnY = 0.0, exitX = 780.0, abyssY = 400.0,
        )
        assertTrue(
            Playtest.jumpWindows(impossible, tuning).isEmpty(),
            "a 280-unit gap is far beyond the arc and must not be certified",
        )
        assertTrue(Playtest.margin(impossible, tuning) == 0)
    }

    @Test
    fun `the margin metric would have failed a frame-perfect room`() {
        // A gap tuned to sit right at the edge of the arc: completable, but only just. This is the
        // shape Palace shipped, and the shape this metric exists to reject.
        var tightest: Room? = null
        for (gap in 60..120) {
            val room = Room(
                id = "gap-$gap",
                ledges = listOf(
                    Ledge("near", 0.0, 200.0, 0.0),
                    Ledge("far", 200.0 + gap, 600.0, 0.0),
                ),
                spawnX = 20.0, spawnY = 0.0, exitX = 580.0, abyssY = 400.0,
            )
            val m = Playtest.margin(room, tuning)
            if (m in 1 until Playtest.MIN_MARGIN_FRAMES) { tightest = room; break }
        }
        assertTrue(
            tightest != null,
            "there should exist a gap width that is completable but below the margin floor",
        )
        println("  rejected: ${tightest.id} margin=${Playtest.margin(tightest, tuning)} frames")
    }
}
