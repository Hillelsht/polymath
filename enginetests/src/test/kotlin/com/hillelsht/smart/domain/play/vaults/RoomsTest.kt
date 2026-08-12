package com.hillelsht.smart.domain.play.vaults

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
        val report = StringBuilder("\n  room            margin   plan\n")
        Rooms.all.forEach { room ->
            val plan = Playtest.solve(room, tuning)
            assertNotNull(plan, "no way through ${room.id} was found")
            val margin = Playtest.slack(room, plan, tuning)
            val ms = margin * 1_000 / Tuning.FPS
            report.append(
                "  ${room.id.padEnd(15)} ${margin.toString().padStart(2)}f ${ms.toString().padStart(4)}ms  " +
                    "wait=${plan.waitFrames} presses=${plan.presses}\n",
            )
            assertTrue(
                margin >= Playtest.MIN_MARGIN_FRAMES,
                "${room.id} allows only $margin frames of slack — below the " +
                    "${Playtest.MIN_MARGIN_FRAMES}-frame floor, so it will read as broken",
            )
        }
        println(report)
    }

    @Test
    fun `the rooms teach one idea at a time`() {
        // The opening room must be crossable with a single press: it is where the verb is learned.
        val first = Playtest.solve(Rooms.threshold, tuning)
        assertNotNull(first)
        assertTrue(first.presses.size <= 1, "the first room should need one jump, not ${first.presses}")
        assertTrue(Rooms.threshold.chompers.isEmpty(), "nothing in the first room is on a clock")
        // And nothing may introduce two new ideas at once: a blade room is never also the room
        // where collapsing stone first appears.
        val firstBlade = Rooms.all.first { it.chompers.isNotEmpty() }
        val firstLoose = Rooms.all.first { r -> r.ledges.any { it.collapsing } }
        assertTrue(
            Rooms.all.indexOf(firstBlade) != Rooms.all.indexOf(firstLoose),
            "blades and loose stone are introduced in the same room",
        )
    }

    @Test
    fun `every trap in the descent can actually kill`() {
        // A trap that cannot catch anyone is scenery, and a margin measured against scenery
        // certifies nothing. Each mechanic gets a proof that it bites.
        Rooms.all.filter { it.chompers.isNotEmpty() }.forEach { room ->
            val period = room.chompers.maxOf { it.periodFrames }
            val died = (0 until period).count {
                Playtest.replay(room, Playtest.Plan(waitFrames = it)).outcome == Playtest.Outcome.DIED
            }
            assertTrue(died > 0, "no start time is caught by a blade in ${room.id}")
            assertTrue(died < period, "every start time is caught by a blade in ${room.id}")
            println("  ${room.id.padEnd(14)} $died/$period start frames are caught")
        }
    }

    @Test
    fun `loose stone gives way under anyone who stops on it`() {
        val room = Rooms.theNarrow
        val loose = room.ledges.first { it.collapsing }
        // Walk on, then stand still well past the collapse delay.
        var r = Motion.spawn(room, tuning)
        var frames = 0
        while (r.x < loose.x0 + 20 && frames < 400) {
            r = Motion.tick(r, room, Buttons(right = true), tuning); frames++
        }
        assertTrue(r.grounded, "the runner should be standing on the loose stone")
        repeat(tuning.collapseFrames + 40) { r = Motion.tick(r, room, Buttons(), tuning) }
        assertEquals(Phase.DEAD, r.phase, "standing still on loose stone should drop the runner")
    }

    @Test
    fun `the room that teaches jumping cannot be crossed without jumping`() {
        // The ledge-grab is generous enough to bridge a narrow gap unaided: step off, catch the far
        // lip, climb up. A fine route to allow, and a terrible way to teach the jump — so the first
        // room's gap is asserted to be out of that reach.
        val room = Rooms.threshold
        val gap = room.ledges[1].x0 - room.ledges[0].x1
        val reach = Playtest.walkOffGrabReach(tuning)
        println("  threshold gap ${gap}, walk-off grab reaches ${"%.1f".format(reach)}")
        assertTrue(
            gap > reach,
            "the threshold's ${gap}-unit gap is inside the ${"%.1f".format(reach)}-unit walk-off " +
                "grab, so it can be crossed without ever jumping",
        )
    }

    @Test
    fun `a room with a real gap cannot be walked across`() {
        assertTrue(
            !Playtest.completes(Rooms.threshold, Playtest.Plan(), tuning),
            "the threshold should require a jump, or its margin proves nothing",
        )
    }

    @Test
    fun `a gap wider than the jump arc is reported as impossible, not merely hard`() {
        val impossible = Room(
            id = "too-far",
            ledges = listOf(Ledge("near", 0.0, 200.0, 0.0), Ledge("far", 520.0, 800.0, 0.0)),
            spawnX = 20.0, spawnY = 0.0, exitX = 780.0, abyssY = 400.0,
        )
        assertEquals(0, Playtest.margin(impossible, tuning))
    }

    @Test
    fun `the margin metric would have failed a frame-perfect room`() {
        // A gap tuned to sit right at the edge of the arc: completable, but only just. This is the
        // shape Palace shipped, and the shape this metric exists to reject.
        var tightest: Room? = null
        for (gap in 60..140) {
            val room = Room(
                id = "gap-$gap",
                ledges = listOf(Ledge("near", 0.0, 200.0, 0.0), Ledge("far", 200.0 + gap, 600.0, 0.0)),
                spawnX = 20.0, spawnY = 0.0, exitX = 580.0, abyssY = 400.0,
            )
            val m = Playtest.margin(room, tuning)
            if (m in 1 until Playtest.MIN_MARGIN_FRAMES) { tightest = room; break }
        }
        assertNotNull(tightest, "some gap width should be completable but below the margin floor")
        println("  rejected: ${tightest.id} slack=${Playtest.margin(tightest, tuning)} frames")
    }

}
