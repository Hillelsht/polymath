package com.hillelsht.smart.domain.play.palace

import kotlin.math.abs

enum class PalacePhase {
    RUNNING,
    HANGING,
    WON,
    GAME_OVER,
}

enum class Facing { LEFT, RIGHT }

/**
 * One tick's worth of held input.
 *
 * [jump], [climb] and [drop] are edge-triggered — true only on the frame the button was pressed,
 * not for as long as it is held — so a jump cannot be re-triggered by holding the button through
 * a landing.
 */
data class PalaceInput(
    val moveLeft: Boolean = false,
    val moveRight: Boolean = false,
    val jump: Boolean = false,
    val climb: Boolean = false,
    val drop: Boolean = false,
)

/**
 * Everything about a run through a wing, in progress.
 *
 * A plain immutable value with no behaviour of its own — every transition lives in
 * [PalacePhysics] and [PalaceRules] as pure functions, the same split
 * [com.hillelsht.smart.domain.play.climb.ClimbRun] uses between state and rules.
 */
data class PalaceRun(
    val level: PalaceLevel,
    val x: Float,
    val y: Float,
    val vx: Float = 0f,
    val vy: Float = 0f,
    val facing: Facing = Facing.RIGHT,
    val grounded: Boolean = false,
    val groundedPlatformId: String? = null,
    val hangingPlatformId: String? = null,
    val phase: PalacePhase = PalacePhase.RUNNING,
    /** Milliseconds a collapsing platform has been stood on, keyed by platform id. Resets if the
     * player steps off before it gives way. */
    val collapseTimers: Map<String, Float> = emptyMap(),
    val brokenPlatformIds: Set<String> = emptySet(),
    val openGateIds: Set<String> = emptySet(),
    val lives: Int = PalaceRules.STARTING_LIVES,
    val elapsedMs: Long = 0,
) {
    val over: Boolean get() = phase == PalacePhase.WON || phase == PalacePhase.GAME_OVER

    companion object {
        fun start(level: PalaceLevel): PalaceRun =
            PalaceRun(level = level, x = level.startX, y = level.startY)
    }
}

/**
 * The palace's mechanics: run, leap, hang off a ledge, and the one hazard a wing can place in
 * the way — a floor that gives out from under you.
 *
 * [tick] is `(state, input, dt) -> state` with no clock of its own, so a run is exercised in
 * tests by feeding it a script of ticks instead of by playing it on a device — the same
 * discipline [com.hillelsht.smart.domain.play.climb.ClimbRules] applies to a question rate,
 * applied here to a frame rate.
 */
object PalacePhysics {

    const val GRAVITY = 2_200f // px/s^2
    const val MOVE_SPEED = 260f // px/s
    const val JUMP_VELOCITY = -820f // px/s; negative is up
    const val MAX_FALL_SPEED = 1_400f // px/s
    const val COLLAPSE_DELAY_MS = 600f
    const val LEDGE_GRAB_TOLERANCE = 18f // px
    const val DROP_KICK = 60f // px/s given on releasing a ledge, so the next tick clears it

    /**
     * Advances the run by [dtMs].
     *
     * Callers must keep [dtMs] small relative to the narrowest gap in the level — a frame-driven
     * UI naturally does this, but a dt spanning several platform widths (say, after the process
     * was backgrounded) would tunnel through them uncaught. Clamping a resumed session's first
     * tick is a UI concern, not something this function guards against.
     */
    fun tick(run: PalaceRun, input: PalaceInput, dtMs: Long): PalaceRun {
        if (run.over || dtMs <= 0) return run
        return if (run.phase == PalacePhase.HANGING) {
            tickHanging(run, input)
        } else {
            tickRunning(run, input, dtMs)
        }
    }

    private fun tickHanging(run: PalaceRun, input: PalaceInput): PalaceRun {
        val ledge = run.level.ledges.firstOrNull { it.platformId == run.hangingPlatformId }
            ?: return run.copy(phase = PalacePhase.RUNNING, hangingPlatformId = null)
        return when {
            input.climb -> {
                val platform = run.level.platforms.first { it.id == ledge.platformId }
                run.copy(
                    phase = PalacePhase.RUNNING,
                    hangingPlatformId = null,
                    x = ledge.x,
                    y = platform.y,
                    vx = 0f,
                    vy = 0f,
                    grounded = true,
                    groundedPlatformId = platform.id,
                )
            }

            input.drop -> run.copy(
                phase = PalacePhase.RUNNING,
                hangingPlatformId = null,
                vy = DROP_KICK,
                grounded = false,
                groundedPlatformId = null,
            )

            else -> run
        }
    }

    private fun tickRunning(run: PalaceRun, input: PalaceInput, dtMs: Long): PalaceRun {
        val dt = dtMs / 1_000f
        val vx = when {
            input.moveLeft && !input.moveRight -> -MOVE_SPEED
            input.moveRight && !input.moveLeft -> MOVE_SPEED
            else -> 0f
        }
        val facing = when {
            vx < 0 -> Facing.LEFT
            vx > 0 -> Facing.RIGHT
            else -> run.facing
        }

        val vy = if (input.jump && run.grounded) {
            JUMP_VELOCITY
        } else {
            (run.vy + GRAVITY * dt).coerceAtMost(MAX_FALL_SPEED)
        }

        var x = (run.x + vx * dt).coerceAtLeast(run.level.minX)
        var y = run.y + vy * dt
        x = clampAtGates(run, x)

        val landing = if (vy >= 0) {
            run.level.platforms
                .asSequence()
                .filter { it.id !in run.brokenPlatformIds }
                .filter { x >= it.x0 && x <= it.x1 }
                .filter { it.y in run.y..y }
                .minByOrNull { it.y }
        } else {
            null
        }

        var grounded = landing != null
        var groundedPlatformId = landing?.id
        var finalVy = vy
        if (landing != null) {
            y = landing.y
            finalVy = 0f
        }

        var collapseTimers = run.collapseTimers
        var brokenPlatformIds = run.brokenPlatformIds
        val previousGroundedId = run.groundedPlatformId
        if (previousGroundedId != null && groundedPlatformId != previousGroundedId) {
            collapseTimers = collapseTimers - previousGroundedId
        }
        if (grounded && groundedPlatformId != null) {
            val platform = run.level.platforms.first { it.id == groundedPlatformId }
            if (platform.collapsing) {
                val elapsed = (collapseTimers[platform.id] ?: 0f) + dtMs
                if (elapsed >= COLLAPSE_DELAY_MS) {
                    brokenPlatformIds = brokenPlatformIds + platform.id
                    collapseTimers = collapseTimers - platform.id
                    grounded = false
                    groundedPlatformId = null
                } else {
                    collapseTimers = collapseTimers + (platform.id to elapsed)
                }
            }
        }

        if (!grounded) {
            val ledge = if (finalVy > 0) findLedge(run, x, run.y, y, input) else null
            if (ledge != null) {
                return run.copy(
                    phase = PalacePhase.HANGING,
                    hangingPlatformId = ledge.platformId,
                    x = ledge.x,
                    y = ledge.y,
                    vx = 0f,
                    vy = 0f,
                    grounded = false,
                    groundedPlatformId = null,
                    collapseTimers = collapseTimers,
                    brokenPlatformIds = brokenPlatformIds,
                    elapsedMs = run.elapsedMs + dtMs,
                )
            }
        }

        if (!grounded && y > run.level.deathY) {
            return respawn(run, brokenPlatformIds)
        }

        if (grounded && x >= run.level.goalX) {
            return run.copy(
                x = x, y = y, vx = 0f, vy = 0f, facing = facing,
                grounded = true, groundedPlatformId = groundedPlatformId,
                phase = PalacePhase.WON,
                collapseTimers = collapseTimers, brokenPlatformIds = brokenPlatformIds,
                elapsedMs = run.elapsedMs + dtMs,
            )
        }

        return run.copy(
            x = x, y = y, vx = vx, vy = finalVy, facing = facing,
            grounded = grounded, groundedPlatformId = groundedPlatformId,
            collapseTimers = collapseTimers, brokenPlatformIds = brokenPlatformIds,
            elapsedMs = run.elapsedMs + dtMs,
        )
    }

    private fun findLedge(run: PalaceRun, x: Float, yFrom: Float, yTo: Float, input: PalaceInput): Ledge? {
        if (!input.moveLeft && !input.moveRight) return null
        return run.level.ledges
            .asSequence()
            .filter { it.platformId !in run.brokenPlatformIds }
            .filter { abs(it.x - x) <= LEDGE_GRAB_TOLERANCE }
            .filter { it.y in (yFrom - LEDGE_GRAB_TOLERANCE)..(yTo + LEDGE_GRAB_TOLERANCE) }
            .minByOrNull { abs(it.y - yTo) }
    }

    private fun clampAtGates(run: PalaceRun, x: Float): Float {
        var clamped = x
        run.level.gates.forEach { gate ->
            if (gate.id !in run.openGateIds) {
                if (run.x <= gate.x && clamped > gate.x) clamped = gate.x
                if (run.x >= gate.x && clamped < gate.x) clamped = gate.x
            }
        }
        return clamped
    }

    private fun respawn(run: PalaceRun, brokenPlatformIds: Set<String>): PalaceRun {
        val lives = run.lives - 1
        return if (lives <= 0) {
            run.copy(lives = 0, phase = PalacePhase.GAME_OVER, vx = 0f, vy = 0f)
        } else {
            run.copy(
                x = run.level.startX,
                y = run.level.startY,
                vx = 0f,
                vy = 0f,
                grounded = false,
                groundedPlatformId = null,
                hangingPlatformId = null,
                phase = PalacePhase.RUNNING,
                lives = lives,
                brokenPlatformIds = brokenPlatformIds,
                collapseTimers = emptyMap(),
            )
        }
    }
}

/** Rules that sit outside the frame-by-frame physics: gates, and what answering one does. */
object PalaceRules {
    const val STARTING_LIVES = 3

    /** The closed gate the player is standing at, if any — the UI's cue to ask a question. */
    fun gateAt(run: PalaceRun): Gate? =
        run.level.gates.firstOrNull { it.id !in run.openGateIds && run.x == it.x }

    /** A correct answer opens the gate; a miss leaves it shut and the run unchanged. */
    fun answerGate(run: PalaceRun, gateId: String, correct: Boolean): PalaceRun {
        if (!correct) return run
        val gate = run.level.gates.firstOrNull { it.id == gateId } ?: return run
        return run.copy(openGateIds = run.openGateIds + gate.id)
    }
}
