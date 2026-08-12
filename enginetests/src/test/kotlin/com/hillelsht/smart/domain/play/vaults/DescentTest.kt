package com.hillelsht.smart.domain.play.vaults

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The run structure: one clock, free deaths, and a room that resets rather than degrades. */
class DescentTest {

    private val tuning = Tuning()

    /** Plays a room with its solved plan, returning the descent once it has moved on or died. */
    private fun playRoom(start: Descent, plan: Playtest.Plan, budget: Int = 500): Descent {
        var d = start
        val room = d.room
        val absolute = plan.presses.map { it + plan.waitFrames }.toSet()
        for (f in 0 until budget) {
            val moving = f >= plan.waitFrames
            d = DescentRules.tick(d, Buttons(right = moving, jump = f in absolute), tuning)
            if (d.room != room || d.finished || d.runner.phase == Phase.DEAD) break
        }
        return d
    }

    @Test
    fun `a solved plan for every room carries a run to the end`() {
        var d = DescentRules.start(Rooms.all, tuning)
        Rooms.all.forEach { room ->
            assertEquals(room.id, d.room.id, "the descent should be in ${room.id}")
            val plan = Playtest.solve(room, tuning)
            requireNotNull(plan) { "no plan for ${room.id}" }
            d = playRoom(d, plan)
            assertTrue(
                d.runner.phase != Phase.DEAD,
                "the solved plan for ${room.id} died inside a full run",
            )
        }
        assertTrue(d.finished, "the descent should be finished")
        assertEquals(Rooms.all.size, d.roomsCleared)
        println("  full descent: ${"%.1f".format(d.elapsedSeconds)}s, splits=${d.splits}")
    }

    @Test
    fun `the clock keeps running while you are dead`() {
        var d = DescentRules.start(listOf(Rooms.threshold), tuning)
        // Walk straight off the edge and do nothing.
        repeat(200) { d = DescentRules.tick(d, Buttons(right = true), tuning) }
        assertEquals(Phase.DEAD, d.runner.phase)
        val atDeath = d.elapsedFrames
        repeat(60) { d = DescentRules.tick(d, Buttons(), tuning) }
        assertTrue(d.elapsedFrames > atDeath, "time must pass while dead, or dying would be free")
        assertEquals(1, d.deaths)
    }

    @Test
    fun `respawning restores stone that gave way`() {
        var d = DescentRules.start(listOf(Rooms.theNarrow), tuning)
        // Stand on the loose stone until it drops out.
        repeat(400) { d = DescentRules.tick(d, Buttons(right = d.runner.x < 200), tuning) }
        assertEquals(Phase.DEAD, d.runner.phase)
        assertTrue(d.runner.broken.isNotEmpty(), "the stone should have given way")
        d = DescentRules.respawn(d, tuning)
        assertTrue(d.runner.broken.isEmpty(), "a room is re-attemptable, not exhaustible")
    }

    @Test
    fun `a deeper run always outscores a shallower one`() {
        // bestScore keeps the maximum, so progress must dominate speed — otherwise a fast failure
        // in room one could sit above a slow run that nearly finished.
        val shallowAndFast = Descent(
            rooms = Rooms.all, runner = Motion.spawn(Rooms.threshold, tuning),
            splits = List(2) { 60 }, elapsedFrames = 120,
        )
        val deepAndSlow = Descent(
            rooms = Rooms.all, runner = Motion.spawn(Rooms.threshold, tuning),
            splits = List(5) { 1_200 }, elapsedFrames = 6_000,
        )
        assertTrue(
            DescentRules.score(deepAndSlow) > DescentRules.score(shallowAndFast),
            "five rooms slowly must beat two rooms quickly",
        )
    }

    @Test
    fun `finishing faster scores higher, and a slow finish still scores`() {
        fun finish(seconds: Double) = Descent(
            rooms = Rooms.all, runner = Motion.spawn(Rooms.threshold, tuning),
            splits = List(Rooms.all.size) { 1 },
            elapsedFrames = (seconds * Tuning.FPS).toInt(), finished = true,
        )
        assertTrue(DescentRules.score(finish(30.0)) > DescentRules.score(finish(60.0)))
        // Past par the bonus floors at zero rather than eating into the depth already earned.
        val crawl = DescentRules.score(finish(600.0))
        assertEquals(Rooms.all.size * DescentRules.ROOM_VALUE, crawl)
        assertTrue(crawl > 0)
    }

    @Test
    fun `an unfinished run earns no speed bonus at all`() {
        val partway = Descent(
            rooms = Rooms.all, runner = Motion.spawn(Rooms.threshold, tuning),
            splits = List(3) { 100 }, elapsedFrames = 300,
        )
        assertEquals(3 * DescentRules.ROOM_VALUE, DescentRules.score(partway))
    }

    @Test
    fun `a finished run stops advancing`() {
        var d = DescentRules.start(listOf(Rooms.threshold), tuning)
        val plan = Playtest.solve(Rooms.threshold, tuning)!!
        d = playRoom(d, plan)
        assertTrue(d.finished)
        val frozen = d
        assertEquals(frozen, DescentRules.tick(d, Buttons(right = true), tuning))
    }
}
