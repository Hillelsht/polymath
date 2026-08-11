package com.hillelsht.smart.domain.play.palace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The physics have no device to run on here, so every mechanic the plan calls for — running,
 * leaping, hanging off a ledge, a floor that gives out, a gate that only opens for a right
 * answer — is driven by a script of ticks instead, exactly as [PalacePhysics.tick]'s own
 * signature invites: `(state, input, dt) -> state` needs nothing but a clock a test can hold
 * still or advance by hand.
 */
class PalacePhysicsTest {

    private val hold = PalaceInput(moveRight = true)
    private val still = PalaceInput()

    private fun flatLevel(goalX: Float = 400f, deathY: Float = 300f) = PalaceLevel(
        id = "flat",
        title = "Flat",
        categoryId = null,
        platforms = listOf(Platform("floor", x0 = 0f, x1 = 500f, y = 0f)),
        startX = 0f,
        startY = 0f,
        goalX = goalX,
        deathY = deathY,
    )

    // --- running and leaping -------------------------------------------------------------------

    @Test
    fun `running right moves forward and stays on the ground`() {
        var run = PalaceRun.start(flatLevel())
        run = run.copy(grounded = true, groundedPlatformId = "floor")
        repeat(10) { run = PalacePhysics.tick(run, hold, dtMs = 100) } // 1 second total
        // Compared against the named constant rather than a literal, so tuning MOVE_SPEED for
        // feel on a device doesn't leave this assertion quietly wrong.
        assertEquals(PalacePhysics.MOVE_SPEED, run.x, absoluteTolerance = 1f)
        assertEquals(0f, run.y, absoluteTolerance = 0.01f)
        assertTrue(run.grounded)
    }

    @Test
    fun `a jump leaves the ground immediately`() {
        var run = PalaceRun.start(flatLevel())
        run = run.copy(grounded = true, groundedPlatformId = "floor")
        run = PalacePhysics.tick(run, PalaceInput(jump = true), dtMs = 16)
        assertFalse(run.grounded)
        assertTrue(run.y < 0f, "a jump should move up, i.e. decrease y; was ${run.y}")
    }

    @Test
    fun `standing still does not drift horizontally`() {
        var run = PalaceRun.start(flatLevel())
        run = run.copy(grounded = true, groundedPlatformId = "floor")
        repeat(20) { run = PalacePhysics.tick(run, still, dtMs = 50) }
        assertEquals(0f, run.x, absoluteTolerance = 0.01f)
        assertTrue(run.grounded)
    }

    @Test
    fun `running off a ledge with nothing below starts a fall`() {
        val level = flatLevel().copy(
            platforms = listOf(Platform("floor", x0 = 0f, x1 = 50f, y = 0f)),
        )
        var run = PalaceRun.start(level).copy(x = 40f, grounded = true, groundedPlatformId = "floor")
        run = PalacePhysics.tick(run, hold, dtMs = 100)
        assertFalse(run.grounded)
        assertTrue(run.vy > 0f, "should be falling, vy was ${run.vy}")
    }

    // --- collapsing floors -----------------------------------------------------------------------

    @Test
    fun `a collapsing floor gives way after standing on it long enough`() {
        val level = flatLevel().copy(
            platforms = listOf(Platform("floor", x0 = 0f, x1 = 200f, y = 0f, collapsing = true)),
        )
        var run = PalaceRun.start(level).copy(grounded = true, groundedPlatformId = "floor")
        val dtStep = 50L

        // Well under the delay, referenced by name rather than a hardcoded tick count so tuning
        // COLLAPSE_DELAY_MS later doesn't leave this half of the test silently checking nothing.
        val halfway = (PalacePhysics.COLLAPSE_DELAY_MS / 2 / dtStep).toInt()
        repeat(halfway) { run = PalacePhysics.tick(run, still, dtMs = dtStep) }
        assertTrue(run.grounded, "should still be standing at roughly half the collapse delay")
        assertFalse("floor" in run.brokenPlatformIds)

        var ticks = 0
        while ("floor" !in run.brokenPlatformIds && ticks < 1_000) {
            run = PalacePhysics.tick(run, still, dtMs = dtStep)
            ticks++
        }
        assertTrue("floor" in run.brokenPlatformIds, "the floor should eventually give way")
        assertFalse(run.grounded)
    }

    @Test
    fun `stepping off a collapsing floor before it breaks resets its timer`() {
        val level = flatLevel().copy(
            platforms = listOf(
                Platform("collapsing", x0 = 0f, x1 = 100f, y = 0f, collapsing = true),
                Platform("safe", x0 = 100f, x1 = 300f, y = 0f),
            ),
        )
        var run = PalaceRun.start(level).copy(grounded = true, groundedPlatformId = "collapsing")

        run = PalacePhysics.tick(run, still, dtMs = 100) // 100ms stood on it
        assertEquals(100f, run.collapseTimers["collapsing"])

        // Cross onto the safe platform. Crossing itself takes a few more ticks standing on the
        // collapsing platform, on top of the 100ms already banked above — comfortably under the
        // 600ms delay, so nothing should give way underfoot before the far side is reached.

        var crossingTicks = 0
        while (run.groundedPlatformId != "safe" && crossingTicks < 200) {
            run = PalacePhysics.tick(run, hold, dtMs = 100)
            crossingTicks++
        }
        assertEquals("safe", run.groundedPlatformId)
        assertTrue("collapsing" !in run.collapseTimers, "timer should have been cleared on leaving")

        // Walking back and standing another 300ms should read as a fresh 300ms, not a
        // continuation of whatever had accumulated before crossing — which it would if the
        // timer had not actually been cleared, and 300ms on top of that leftover would be
        // enough to break the floor before this assertion ever ran.
        run = run.copy(x = 50f, groundedPlatformId = "collapsing")
        repeat(3) { run = PalacePhysics.tick(run, still, dtMs = 100) }
        assertEquals(300f, run.collapseTimers["collapsing"])
        assertFalse("collapsing" in run.brokenPlatformIds)
    }

    // --- gates -----------------------------------------------------------------------------------

    @Test
    fun `a closed gate blocks passage until answered correctly`() {
        val level = flatLevel().copy(gates = listOf(Gate("gate1", x = 100f)))
        var run = PalaceRun.start(level).copy(grounded = true, groundedPlatformId = "floor")

        repeat(20) { run = PalacePhysics.tick(run, hold, dtMs = 100) }
        assertEquals(100f, run.x, absoluteTolerance = 0.01f)
        assertEquals(Gate("gate1", x = 100f), PalaceRules.gateAt(run))

        val stillWrong = PalaceRules.answerGate(run, "gate1", correct = false)
        assertTrue("gate1" !in stillWrong.openGateIds)

        run = PalaceRules.answerGate(run, "gate1", correct = true)
        assertTrue("gate1" in run.openGateIds)
        assertNull(PalaceRules.gateAt(run))

        run = PalacePhysics.tick(run, hold, dtMs = 100)
        assertTrue(run.x > 100f, "should have walked past the now-open gate, x was ${run.x}")
    }

    // --- ledges ----------------------------------------------------------------------------------

    // The gap and drop between A and B are sized against the current MOVE_SPEED/GRAVITY so a
    // player holding right off A's edge passes close enough to B's corner to grab it — if those
    // constants are retuned for feel on a device, this geometry (not the physics) is what needs
    // adjusting to keep the ledge reachable.
    private fun ledgeLevel() = PalaceLevel(
        id = "ledge",
        title = "Ledge",
        categoryId = null,
        platforms = listOf(
            Platform("A", x0 = 0f, x1 = 100f, y = 50f),
            Platform("B", x0 = 150f, x1 = 400f, y = 80f),
        ),
        ledges = listOf(Ledge(platformId = "B", x = 150f, y = 80f)),
        startX = 50f,
        startY = 50f,
        goalX = 380f,
        deathY = 600f,
    )

    private fun runUntilHanging(level: PalaceLevel): PalaceRun {
        var run = PalaceRun.start(level).copy(x = 100f, grounded = true, groundedPlatformId = "A")
        repeat(60) {
            if (run.phase == PalacePhase.HANGING) return run
            run = PalacePhysics.tick(run, hold, dtMs = 16)
        }
        return run
    }

    @Test
    fun `running off a high edge grabs a ledge on the way down`() {
        val run = runUntilHanging(ledgeLevel())
        assertEquals(PalacePhase.HANGING, run.phase)
        assertEquals("B", run.hangingPlatformId)
    }

    @Test
    fun `climbing up from a hang stands the player on that platform`() {
        var run = runUntilHanging(ledgeLevel())
        require(run.phase == PalacePhase.HANGING)
        run = PalacePhysics.tick(run, PalaceInput(climb = true), dtMs = 16)
        assertEquals(PalacePhase.RUNNING, run.phase)
        assertTrue(run.grounded)
        assertEquals("B", run.groundedPlatformId)
        assertEquals(80f, run.y)
    }

    @Test
    fun `dropping from a hang resumes falling`() {
        var run = runUntilHanging(ledgeLevel())
        require(run.phase == PalacePhase.HANGING)
        run = PalacePhysics.tick(run, PalaceInput(drop = true), dtMs = 16)
        assertEquals(PalacePhase.RUNNING, run.phase)
        assertFalse(run.grounded)
        assertNull(run.hangingPlatformId)
        assertTrue(run.vy > 0f)
    }

    // --- death and the goal ----------------------------------------------------------------------

    @Test
    fun `falling past the death line costs a life and respawns at the start`() {
        val level = flatLevel(deathY = 200f).copy(
            platforms = listOf(Platform("floor", x0 = 0f, x1 = 50f, y = 0f)),
            startX = 0f,
            startY = 0f,
        )
        var run = PalaceRun.start(level).copy(x = 40f, grounded = true, groundedPlatformId = "floor")

        // Stop the instant lives first drops, rather than assuming how many ticks a fall takes —
        // holding right for too long would walk the respawned player off the edge again and
        // start a second fall before this test ever looked at the result.
        var ticks = 0
        while (run.lives == PalaceRules.STARTING_LIVES && ticks < 500) {
            run = PalacePhysics.tick(run, hold, dtMs = 100)
            ticks++
        }
        assertEquals(PalaceRules.STARTING_LIVES - 1, run.lives)
        assertEquals(0f, run.x)
        assertEquals(0f, run.y)
        assertEquals(PalacePhase.RUNNING, run.phase)
    }

    @Test
    fun `running out of lives ends the run`() {
        val level = flatLevel(deathY = 200f).copy(
            platforms = listOf(Platform("floor", x0 = 0f, x1 = 50f, y = 0f)),
            startX = 0f,
            startY = 0f,
        )
        var run = PalaceRun.start(level).copy(x = 40f, grounded = true, groundedPlatformId = "floor")
        // tick() is a no-op once a run is over, so simply holding right for long enough runs
        // through every fall-and-respawn cycle and settles at GAME_OVER on its own.
        repeat(2_000) { run = PalacePhysics.tick(run, hold, dtMs = 100) }
        assertEquals(0, run.lives)
        assertEquals(PalacePhase.GAME_OVER, run.phase)
    }

    @Test
    fun `reaching the goal while grounded wins`() {
        var run = PalaceRun.start(flatLevel(goalX = 200f))
        run = run.copy(grounded = true, groundedPlatformId = "floor")
        repeat(20) { run = PalacePhysics.tick(run, hold, dtMs = 100) }
        assertEquals(PalacePhase.WON, run.phase)
    }

    @Test
    fun `a run that is over ignores further ticks`() {
        var run = PalaceRun.start(flatLevel(goalX = 200f))
        run = run.copy(grounded = true, groundedPlatformId = "floor")
        repeat(20) { run = PalacePhysics.tick(run, hold, dtMs = 100) }
        require(run.phase == PalacePhase.WON)
        val frozen = run
        val after = PalacePhysics.tick(run, hold, dtMs = 100)
        assertEquals(frozen, after)
    }
}

private fun assertEquals(expected: Float, actual: Float, absoluteTolerance: Float) {
    kotlin.test.assertTrue(
        kotlin.math.abs(expected - actual) <= absoluteTolerance,
        "expected $expected but was $actual (tolerance $absoluteTolerance)",
    )
}
