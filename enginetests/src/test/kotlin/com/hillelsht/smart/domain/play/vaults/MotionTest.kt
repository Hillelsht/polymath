package com.hillelsht.smart.domain.play.vaults

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The movement tests, written against the standard Palace failed.
 *
 * Palace's suite proved a jump left the ground and a ledge was grabbed, and every one of those
 * assertions was true while the game was unplayable, because they fed the physics the perfect
 * frame a human cannot produce. These tests instead assert that *imperfect* input works: presses
 * that arrive early, presses that arrive late, and whole rooms replayed through simulated touch
 * latency.
 */
class MotionTest {

    private val tuning = Tuning()

    private fun flatRoom() = Room(
        id = "flat",
        ledges = listOf(Ledge("floor", 0.0, 600.0, 0.0)),
        spawnX = 10.0, spawnY = 0.0, exitX = 590.0, abyssY = 400.0,
    )

    private fun land(room: Room): Runner {
        var r = Motion.spawn(room, tuning)
        repeat(5) { r = Motion.tick(r, room, Buttons(), tuning) }
        return r
    }

    // --- the Palace regression -----------------------------------------------------------

    @Test
    fun `a jump pressed before landing still fires on landing`() {
        // Palace destroyed exactly this input. Every offset inside the buffer must work.
        val room = Room(
            id = "drop",
            ledges = listOf(Ledge("floor", 0.0, 600.0, 0.0)),
            spawnX = 10.0, spawnY = -120.0, exitX = 590.0, abyssY = 400.0,
        )
        // How many frames until touchdown, pressing nothing.
        var probe = Motion.spawn(room, tuning)
        var touchdown = 0
        while (!probe.grounded && touchdown < 200) {
            probe = Motion.tick(probe, room, Buttons(), tuning); touchdown++
        }
        assertTrue(touchdown in 1..200, "the probe should land")

        for (early in 1..tuning.jumpBufferFrames) {
            var r = Motion.spawn(room, tuning)
            var launched = false
            repeat(touchdown + 6) { f ->
                // The tap arrives `early` frames before touchdown and is never repeated.
                val press = f == touchdown - early
                val before = r.vy
                r = Motion.tick(r, room, Buttons(jump = press), tuning)
                if (before >= 0.0 && r.vy < 0.0) launched = true
            }
            assertTrue(launched, "a jump pressed $early frames before landing was swallowed")
        }
    }

    @Test
    fun `a press older than the buffer is discarded rather than fired late`() {
        val room = Room(
            id = "deep",
            ledges = listOf(Ledge("floor", 0.0, 600.0, 0.0)),
            spawnX = 10.0, spawnY = -600.0, exitX = 590.0, abyssY = 900.0,
        )
        var r = Motion.spawn(room, tuning)
        // One press, immediately, while very high up — far more than the buffer from landing.
        r = Motion.tick(r, room, Buttons(jump = true), tuning)
        assertEquals(0, r.jumpPressedAgo)
        repeat(tuning.jumpBufferFrames + 2) { r = Motion.tick(r, room, Buttons(), tuning) }
        assertNull(r.jumpPressedAgo, "a stale press must expire, not queue up for the landing")
    }

    @Test
    fun `holding jump does not re-trigger after landing`() {
        val room = flatRoom()
        var r = land(room)
        r = Motion.tick(r, room, Buttons(jump = true), tuning)
        assertTrue(r.vy < 0.0, "the press should launch")
        // Hold it down through the whole arc and the landing.
        var relaunched = false
        repeat(80) {
            val before = r.vy
            r = Motion.tick(r, room, Buttons(jump = true), tuning)
            if (before >= 0.0 && r.vy < 0.0 && r.frame > 3) relaunched = true
        }
        assertTrue(!relaunched, "a held button must not bounce the player repeatedly")
    }

    // --- coyote time ---------------------------------------------------------------------

    @Test
    fun `walking off an edge still allows a jump for a few frames`() {
        val room = Room(
            id = "edge",
            ledges = listOf(Ledge("floor", 0.0, 200.0, 0.0)),
            spawnX = 150.0, spawnY = 0.0, exitX = 999.0, abyssY = 400.0,
        )
        // Leaving the loop already costs one airborne frame, so `delay` here is the *extra* wait:
        // delay = coyoteFrames - 1 is the last frame still inside the grace window.
        for (delay in 0 until tuning.coyoteFrames) {
            var r = Motion.spawn(room, tuning)
            repeat(4) { r = Motion.tick(r, room, Buttons(), tuning) }
            // Run off the end.
            while (r.grounded) r = Motion.tick(r, room, Buttons(right = true), tuning)
            repeat(delay) { r = Motion.tick(r, room, Buttons(right = true), tuning) }
            val before = r.vy
            r = Motion.tick(r, room, Buttons(right = true, jump = true), tuning)
            assertTrue(
                before >= 0.0 && r.vy < 0.0,
                "a jump $delay frames after leaving the edge should still fire",
            )
        }
    }

    @Test
    fun `coyote time does not extend indefinitely`() {
        val room = Room(
            id = "edge",
            ledges = listOf(Ledge("floor", 0.0, 200.0, 0.0)),
            spawnX = 150.0, spawnY = 0.0, exitX = 999.0, abyssY = 400.0,
        )
        var r = Motion.spawn(room, tuning)
        repeat(4) { r = Motion.tick(r, room, Buttons(), tuning) }
        while (r.grounded) r = Motion.tick(r, room, Buttons(right = true), tuning)
        repeat(tuning.coyoteFrames + 3) { r = Motion.tick(r, room, Buttons(right = true), tuning) }
        val before = r.vy
        r = Motion.tick(r, room, Buttons(right = true, jump = true), tuning)
        assertTrue(r.vy >= before, "long after the edge, a jump must not fire")
    }

    // --- momentum ------------------------------------------------------------------------

    @Test
    fun `reaching full speed takes time, and so does turning around`() {
        val room = flatRoom()
        var r = land(room)
        r = Motion.tick(r, room, Buttons(right = true), tuning)
        assertTrue(r.vx > 0.0 && r.vx < tuning.runSpeed, "one frame must not reach full speed")

        var frames = 1
        while (r.vx < tuning.runSpeed - 0.001 && frames < 120) {
            r = Motion.tick(r, room, Buttons(right = true), tuning); frames++
        }
        assertTrue(frames in 10..25, "a run should take roughly a quarter second to build: $frames")

        // Now reverse: the runner must pass through zero rather than flip instantly.
        val turning = Motion.tick(r, room, Buttons(left = true), tuning)
        assertTrue(turning.vx > 0.0, "a turn cannot reverse velocity in a single frame")
    }

    @Test
    fun `releasing everything brings the runner to a complete stop`() {
        val room = flatRoom()
        var r = land(room)
        repeat(30) { r = Motion.tick(r, room, Buttons(right = true), tuning) }
        repeat(30) { r = Motion.tick(r, room, Buttons(), tuning) }
        assertEquals(0.0, r.vx, "friction must settle exactly to rest, not drift")
    }

    // --- determinism ---------------------------------------------------------------------

    @Test
    fun `the same inputs always produce the same run`() {
        val room = flatRoom()
        fun play(): List<Double> {
            var r = Motion.spawn(room, tuning)
            return (0 until 120).map { f ->
                r = Motion.tick(r, room, Buttons(right = f % 3 != 0, jump = f % 17 == 0), tuning)
                r.x
            }
        }
        assertEquals(play(), play(), "a fixed timestep must give a reproducible run")
    }

    @Test
    fun `a fall past the abyss kills`() {
        // A long runway, so the runner leaves the edge at full speed and is carried out of reach of
        // its own lip. Stepping off slowly is a different move entirely — see the test below.
        val room = Room(
            id = "pit",
            ledges = listOf(Ledge("floor", 0.0, 200.0, 0.0)),
            spawnX = 20.0, spawnY = 0.0, exitX = 999.0, abyssY = 300.0,
        )
        var r = Motion.spawn(room, tuning)
        repeat(200) { r = Motion.tick(r, room, Buttons(right = true), tuning) }
        assertEquals(Phase.DEAD, r.phase)
    }

    @Test
    fun `stepping off an edge slowly catches it, running off it does not`() {
        // The grab is a safety net whose width is a *speed* decision rather than a timing one: a
        // body moving fast is already beyond the lip by the time it has fallen far enough to reach
        // for it. That is what keeps the net from quietly bridging every gap in the game.
        val room = Room(
            id = "lip",
            ledges = listOf(Ledge("floor", 0.0, 200.0, 0.0)),
            spawnX = 190.0, spawnY = 0.0, exitX = 999.0, abyssY = 300.0,
        )
        // Creeping: tap and coast, the way anyone edges up to a drop. Friction is stronger than
        // acceleration, so this never builds real speed.
        var slow = Motion.spawn(room, tuning)
        repeat(200) { f ->
            if (!slow.over && slow.phase != Phase.HANGING) {
                slow = Motion.tick(slow, room, Buttons(right = f % 9 < 2), tuning)
            }
        }
        assertEquals(Phase.HANGING, slow.phase, "a slow step off the lip should catch it")

        // At speed, from far enough back to reach full pace.
        val long = room.copy(ledges = listOf(Ledge("floor", 0.0, 200.0, 0.0)), spawnX = 20.0)
        var fast = Motion.spawn(long, tuning)
        repeat(120) { fast = Motion.tick(fast, long, Buttons(right = true), tuning) }
        assertTrue(fast.phase != Phase.HANGING, "a full-speed run off the lip must not catch it")
    }

    @Test
    fun `a run that is over stops advancing`() {
        val room = flatRoom()
        var r = land(room)
        repeat(400) { r = Motion.tick(r, room, Buttons(right = true), tuning) }
        assertEquals(Phase.EXITED, r.phase)
        val frozen = r
        assertNotNull(frozen)
        assertEquals(frozen, Motion.tick(r, room, Buttons(right = true), tuning))
    }
}
