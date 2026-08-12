package com.hillelsht.smart.domain.play.vaults

/**
 * A run through the whole descent: the rooms in order, against one clock.
 *
 * The clock is the point. *Prince of Persia* gave the entire game a single hour shared across every
 * death, and that one decision is what turned a set of obstacle rooms into something people replayed
 * for years — a death costs you nothing you can see except the thing you cannot get back. So deaths
 * here are unlimited and free, respawning at the room's entrance, and the only score is
 * [elapsedFrames]. Dying is not punished; dithering is.
 *
 * Health is deliberately *not* global. It absorbs fall damage inside a room and resets at the next
 * one, so a bruising landing is a local problem rather than a slow drain that makes a run
 * unwinnable twenty minutes in without telling you.
 */
data class Descent(
    val rooms: List<Room>,
    val index: Int = 0,
    val runner: Runner,
    /** Frames since the run began, counting time spent dead. The score, and the pressure. */
    val elapsedFrames: Int = 0,
    val deaths: Int = 0,
    /** Frames each completed room took, for a split-by-split reading of a run. */
    val splits: List<Int> = emptyList(),
    val finished: Boolean = false,
) {
    val room: Room get() = rooms[index.coerceAtMost(rooms.size - 1)]
    val elapsedSeconds: Double get() = elapsedFrames.toDouble() / Tuning.FPS
    val roomsCleared: Int get() = splits.size
}

object DescentRules {

    /** How long the runner lies still before the room resets, so a death reads as an event. */
    const val DEATH_PAUSE_FRAMES = 26

    fun start(rooms: List<Room> = Rooms.all, tuning: Tuning = Tuning()): Descent =
        Descent(rooms = rooms, runner = Motion.spawn(rooms.first(), tuning))

    /**
     * One frame of a run.
     *
     * The clock advances on every frame including the ones spent dead, which is what makes a death
     * cost something without needing a lives counter to enforce it.
     */
    fun tick(descent: Descent, buttons: Buttons, tuning: Tuning = Tuning()): Descent {
        if (descent.finished) return descent
        val elapsed = descent.elapsedFrames + 1

        // Already lying there. The clock is the only thing still moving, and the death has already
        // been counted — [Motion.tick] returns a finished runner unchanged, so testing the new
        // phase here instead would tally the same death on every frame until the room reset.
        if (descent.runner.phase == Phase.DEAD) return descent.copy(elapsedFrames = elapsed)

        val next = Motion.tick(descent.runner, descent.room, buttons, tuning)

        return when {
            next.phase == Phase.EXITED -> {
                val cleared = descent.index + 1
                val splits = descent.splits + (elapsed - descent.splits.sum())
                if (cleared >= descent.rooms.size) {
                    descent.copy(runner = next, elapsedFrames = elapsed, splits = splits, finished = true)
                } else {
                    descent.copy(
                        index = cleared,
                        runner = Motion.spawn(descent.rooms[cleared], tuning),
                        elapsedFrames = elapsed,
                        splits = splits,
                    )
                }
            }

            next.phase == Phase.DEAD -> descent.copy(
                runner = next,
                elapsedFrames = elapsed,
                deaths = descent.deaths + 1,
            )

            else -> descent.copy(runner = next, elapsedFrames = elapsed)
        }
    }

    /**
     * A run that gets nowhere quickly must never outscore one that got further.
     *
     * `SmartRepository.bestScore` keeps the maximum, so the score has to rise with progress rather
     * than fall with time — depth first, speed only as a tie-break within it. Making time the
     * headline number would be the speedrunner's framing and the wrong one for a Play tab, where
     * most runs end partway down and should still read as better than the last one.
     */
    const val ROOM_VALUE = 1_000

    /** Finishing inside this earns the full speed bonus; slower still finishes, for less. */
    const val PAR_SECONDS = 90.0

    fun score(descent: Descent): Int {
        val depth = descent.roomsCleared * ROOM_VALUE
        val speed = if (!descent.finished) 0 else {
            ((PAR_SECONDS - descent.elapsedSeconds).coerceAtLeast(0.0) * 10).toInt()
        }
        return depth + speed
    }

    /** Whether enough frames have passed since dying to put the runner back at the entrance. */
    fun readyToRespawn(descent: Descent, framesSinceDeath: Int): Boolean =
        descent.runner.phase == Phase.DEAD && framesSinceDeath >= DEATH_PAUSE_FRAMES

    fun respawn(descent: Descent, tuning: Tuning = Tuning()): Descent =
        descent.copy(runner = Motion.spawn(descent.room, tuning))
}
