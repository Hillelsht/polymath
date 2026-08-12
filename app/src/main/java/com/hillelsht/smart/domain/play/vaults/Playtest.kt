package com.hillelsht.smart.domain.play.vaults

/**
 * Proving a room is playable, rather than merely solvable.
 *
 * Palace's level test scripted the exact inputs that completed a level and asserted it completed.
 * That is a tautology: it proves a solution exists, not that a person can execute one. The room it
 * certified was unplayable.
 *
 * What matters instead is how much **timing slack** a solution has. If a gap can only be cleared by
 * jumping on one specific frame, no human clears it, however green the test. [margin] is that
 * slack, in frames, and it is the number CI can fail on.
 */
object Playtest {

    enum class Outcome { EXITED, DIED, STUCK }

    /**
     * A way of playing a room: wait, then run, pressing jump at [presses] frames after setting off.
     *
     * Timings are relative to the moment the run starts rather than to frame zero, which is what
     * makes [waitFrames] and [presses] independent things to be tolerant of. A player who meets a
     * blade learns "set off on the beat, then jump two-thirds of a second later" — they do not
     * re-derive an absolute frame number when they start late.
     */
    data class Plan(
        val waitFrames: Int = 0,
        val presses: List<Int> = emptyList(),
        val right: Boolean = true,
    )

    data class Result(val outcome: Outcome, val frames: Int, val runner: Runner)

    const val MAX_FRAMES = 420

    /**
     * The bar a shipped room must clear. Six frames is 100ms at 60fps; below that a player is being
     * asked for precision ordinary reaction jitter cannot deliver, and the room reads as broken
     * rather than hard.
     */
    const val MIN_MARGIN_FRAMES = 6

    fun replay(room: Room, plan: Plan, tuning: Tuning = Tuning(), maxFrames: Int = MAX_FRAMES): Result {
        var r = Motion.spawn(room, tuning)
        val absolute = plan.presses.map { it + plan.waitFrames }.toSet()
        for (f in 0 until maxFrames) {
            val moving = f >= plan.waitFrames
            val buttons = Buttons(
                left = moving && !plan.right,
                right = moving && plan.right,
                jump = f in absolute,
            )
            r = Motion.tick(r, room, buttons, tuning)
            when (r.phase) {
                Phase.EXITED -> return Result(Outcome.EXITED, f + 1, r)
                Phase.DEAD -> return Result(Outcome.DIED, f + 1, r)
                else -> Unit
            }
        }
        return Result(Outcome.STUCK, maxFrames, r)
    }

    fun completes(room: Room, plan: Plan, tuning: Tuning = Tuning()): Boolean =
        replay(room, plan, tuning).outcome == Outcome.EXITED

    /**
     * How many consecutive values each input could take and still finish the room; the smallest
     * such window is the plan's slack.
     *
     * Each input is varied on its own, around the working plan, exactly as a player's timing varies
     * around what they intended. The minimum is what matters: a plan forgiving in every respect but
     * one is only as playable as its tightest moment.
     */
    fun slack(room: Room, plan: Plan, tuning: Tuning = Tuning(), span: Int = 40): Int {
        if (!completes(room, plan, tuning)) return 0
        val windows = mutableListOf<Int>()

        // When nothing in the room is on a clock, *when* you set off is not an input the player
        // has to get right, so measuring its tolerance would inflate every score with a free
        // dimension. Only rooms with blades charge for starting on the wrong beat.
        if (room.chompers.isNotEmpty()) {
            windows += windowAround(plan.waitFrames, span) { v ->
                v >= 0 && completes(room, plan.copy(waitFrames = v), tuning)
            }
        }
        plan.presses.indices.forEach { i ->
            windows += windowAround(plan.presses[i], span) { v ->
                v >= 0 && completes(room, plan.copy(presses = plan.presses.toMutableList().also { it[i] = v }), tuning)
            }
        }
        return windows.minOrNull() ?: 0
    }

    /** Width of the run of values containing [centre] for which [ok] holds. */
    private fun windowAround(centre: Int, span: Int, ok: (Int) -> Boolean): Int {
        if (!ok(centre)) return 0
        var lo = centre
        while (lo - 1 >= centre - span && ok(lo - 1)) lo--
        var hi = centre
        while (hi + 1 <= centre + span && ok(hi + 1)) hi++
        return hi - lo + 1
    }

    /**
     * Searches for ways to finish the room, best-first by slack.
     *
     * The plan space is "wait, run one way, press jump up to [maxPresses] times" — the entire
     * vocabulary of these rooms, since climbing off a ledge-grab is also a jump press. The second
     * press is only tried at frames where a press would actually be consumed, which keeps the
     * search small enough to run on every build.
     *
     * **This searches, it does not prove.** Finding nothing means the search found nothing, not
     * that the room is impossible — so the gate fails closed, which is the safe direction.
     */
    fun solve(
        room: Room,
        tuning: Tuning = Tuning(),
        maxPresses: Int = 2,
        maxFrames: Int = 420,
    ): Plan? {
        // Waiting only changes anything when something in the room is on a clock.
        val period = room.chompers.maxOfOrNull { it.periodFrames } ?: 1
        val waits = if (room.chompers.isEmpty()) listOf(0) else (0 until period step 3).toList()

        // Coarse pass. Scoring every hit with `slack` would cost hundreds of replays each, so the
        // grid only records *what works*; slack is measured later, on a handful of finalists.
        val hits = mutableListOf<Plan>()
        for (wait in waits) {
            if (completes(room, Plan(wait), tuning)) hits += Plan(wait)
            for (p1 in 0 until maxFrames step 3) {
                if (completes(room, Plan(wait, listOf(p1)), tuning)) hits += Plan(wait, listOf(p1))
            }
        }

        // Only reach for a second press if nothing at all worked with one — climbing off a
        // ledge-grab is the main reason a room needs two, and such rooms are rare.
        if (hits.isEmpty() && maxPresses >= 2) {
            for (wait in waits) {
                for (p1 in 0 until maxFrames step 6) {
                    val one = Plan(wait, listOf(p1))
                    for (p2 in pressableFrames(room, one, tuning, maxFrames)) {
                        if (p2 <= p1) continue
                        val two = Plan(wait, listOf(p1, p2))
                        if (completes(room, two, tuning)) hits += two
                    }
                    if (hits.size >= 24) break
                }
                if (hits.size >= 24) break
            }
        }
        if (hits.isEmpty()) return null

        // Score a spread of finalists rather than all of them, then polish the winner locally —
        // the coarse grid can land just off the centre of a window and understate its width.
        val stride = maxOf(1, hits.size / 12)
        var best = hits.first()
        var bestSlack = -1
        hits.filterIndexed { i, _ -> i % stride == 0 }.forEach { p ->
            val s = slack(room, p, tuning)
            if (s > bestSlack) { bestSlack = s; best = p }
        }
        return polish(room, best, bestSlack, tuning)
    }

    /** Nudges each timing a few frames either way, keeping any move that widens the slack. */
    private fun polish(room: Room, start: Plan, startSlack: Int, tuning: Tuning): Plan {
        var best = start
        var bestSlack = startSlack
        repeat(2) {
            val neighbours = mutableListOf<Plan>()
            (-4..4).forEach { d ->
                if (room.chompers.isNotEmpty() && best.waitFrames + d >= 0) {
                    neighbours += best.copy(waitFrames = best.waitFrames + d)
                }
                best.presses.indices.forEach { i ->
                    val moved = best.presses.toMutableList()
                    moved[i] = moved[i] + d
                    if (moved[i] >= 0) neighbours += best.copy(presses = moved)
                }
            }
            neighbours.forEach { p ->
                val s = slack(room, p, tuning)
                if (s > bestSlack) { bestSlack = s; best = p }
            }
        }
        return best
    }

    /**
     * Frames at which pressing jump would actually do something — standing on a floor, hanging
     * from a lip, or still inside coyote time. Anywhere else a press is only stored, so branching
     * there would multiply the search without changing any outcome.
     */
    private fun pressableFrames(
        room: Room,
        plan: Plan,
        tuning: Tuning,
        maxFrames: Int,
    ): List<Int> {
        var r = Motion.spawn(room, tuning)
        val absolute = plan.presses.map { it + plan.waitFrames }.toSet()
        val out = mutableListOf<Int>()
        for (f in 0 until maxFrames) {
            val moving = f >= plan.waitFrames
            r = Motion.tick(
                r, room,
                Buttons(moving && !plan.right, moving && plan.right, f in absolute),
                tuning,
            )
            if (r.over) break
            if (r.grounded || r.hanging || r.framesOffGround <= tuning.coyoteFrames) {
                if (out.isEmpty() || f - out.last() >= 2) out += f - plan.waitFrames
            }
        }
        return out.filter { it >= 0 }
    }

    /**
     * How far past a lip a runner can step and still catch it, without jumping at all.
     *
     * The grab is a safety net, and a net this wide will quietly bridge a narrow gap on its own —
     * a room whose gap is inside this distance can be crossed by walking off the edge and climbing
     * up the other side, which is a fine route to allow but a poor way to *teach* jumping. Rooms
     * that exist to teach the jump assert their gap is wider than this.
     */
    fun walkOffGrabReach(tuning: Tuning = Tuning()): Double {
        val fallTime = kotlin.math.sqrt(2.0 * tuning.grabMaxDrop / tuning.gravity)
        return tuning.runSpeed * fallTime + tuning.grabReach
    }

    /** The best slack any discovered plan achieves, in frames. Zero if nothing was found. */
    fun margin(room: Room, tuning: Tuning = Tuning()): Int =
        solve(room, tuning)?.let { slack(room, it, tuning) } ?: 0
}
