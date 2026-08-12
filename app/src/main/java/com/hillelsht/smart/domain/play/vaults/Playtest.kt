package com.hillelsht.smart.domain.play.vaults

/**
 * Proving a room is playable, rather than merely solvable.
 *
 * Palace's level test scripted the exact inputs that completed a level and asserted it completed.
 * That is a tautology: it proves a solution exists, not that a person can execute one. The room
 * it certified was unplayable.
 *
 * What matters instead is how much **timing slack** a solution has. If a gap can only be cleared
 * by jumping on one specific frame, no human clears it, however green the test. If it can be
 * cleared by jumping on any of twenty consecutive frames, almost anyone can. [jumpWindow] measures
 * that width, and [margin] is the number CI can fail on.
 *
 * Scope: plans of the form "run in one direction, jump once". That is the whole movement
 * vocabulary of the opening rooms, and it keeps the search exhaustive and instant. Rooms needing
 * several jumps will need this widened to a search over jump *sequences* — the metric stays the
 * same, only the plan space grows.
 *
 * **What this metric does not see.** It presses jump on a chosen frame while the runner is on the
 * ground, so it measures room geometry and [Tuning.coyoteFrames] — dropping coyote time to zero
 * visibly narrows the window — but it is blind to [Tuning.jumpBufferFrames], because it never
 * presses jump while airborne. Buffering is what saves a press that arrives *before* a landing,
 * and it is covered by `MotionTest`, not here. Two checks, two failure modes; treating this one
 * number as proof of playability would repeat exactly the mistake that shipped Palace.
 */
object Playtest {

    /** How a plan ended, for reporting rather than just a boolean. */
    enum class Outcome { EXITED, DIED, STUCK }

    data class Result(val outcome: Outcome, val frames: Int, val runner: Runner)

    const val MAX_FRAMES = 1_200

    /**
     * Replays "hold [right], and press jump on frame [jumpAt]" (or never, if null).
     *
     * The press is held for a single frame, which is the hardest case: if a one-frame tap works,
     * a longer press works too, because the buffer keeps it alive either way.
     */
    fun replay(
        room: Room,
        jumpAt: Int?,
        right: Boolean = true,
        tuning: Tuning = Tuning(),
        maxFrames: Int = MAX_FRAMES,
    ): Result {
        var r = Motion.spawn(room, tuning)
        for (f in 0 until maxFrames) {
            val buttons = Buttons(left = !right, right = right, jump = f == jumpAt)
            r = Motion.tick(r, room, buttons, tuning)
            when (r.phase) {
                Phase.EXITED -> return Result(Outcome.EXITED, f + 1, r)
                Phase.DEAD -> return Result(Outcome.DIED, f + 1, r)
                else -> Unit
            }
        }
        return Result(Outcome.STUCK, maxFrames, r)
    }

    /**
     * Every frame on which pressing jump completes the room, as contiguous runs.
     *
     * More than one run can exist — an early jump and a late jump may both clear a gap while a
     * middle one lands in it — so the caller gets them all and [margin] takes the widest.
     */
    fun jumpWindows(room: Room, tuning: Tuning = Tuning(), maxFrames: Int = MAX_FRAMES): List<IntRange> {
        val good = (0 until maxFrames).filter {
            replay(room, it, tuning = tuning, maxFrames = maxFrames).outcome == Outcome.EXITED
        }
        if (good.isEmpty()) return emptyList()
        val runs = mutableListOf<IntRange>()
        var start = good.first()
        var prev = start
        good.drop(1).forEach { f ->
            if (f != prev + 1) { runs += start..prev; start = f }
            prev = f
        }
        runs += start..prev
        return runs
    }

    /**
     * The widest run of consecutive frames on which a jump completes the room, in frames at
     * [Tuning.FPS]. Zero means the room cannot be completed by jumping at all — either it needs no
     * jump, or it is impossible.
     */
    fun margin(room: Room, tuning: Tuning = Tuning()): Int =
        jumpWindows(room, tuning).maxOfOrNull { it.last - it.first + 1 } ?: 0

    /** Whether the room can be crossed with no jump at all, e.g. a corridor. */
    fun completableWithoutJumping(room: Room, tuning: Tuning = Tuning()): Boolean =
        replay(room, null, tuning = tuning).outcome == Outcome.EXITED

    /**
     * The bar a shipped room must clear.
     *
     * Six frames is 100ms at 60fps. Below that a player is being asked for precision that ordinary
     * reaction jitter cannot deliver, and the room reads as broken rather than hard.
     */
    const val MIN_MARGIN_FRAMES = 6
}
