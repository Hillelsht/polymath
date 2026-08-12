package com.hillelsht.smart.domain.play.vaults

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/** What the player is holding this frame. Raw button state — edge detection happens in [Runner]. */
data class Buttons(
    val left: Boolean = false,
    val right: Boolean = false,
    val jump: Boolean = false,
)

enum class Facing { LEFT, RIGHT }

enum class Phase { GROUNDED, AIRBORNE, EXITED, DEAD }

/**
 * The player, and the input bookkeeping that makes the controls feel fair.
 *
 * [jumpPressedAgo] and [framesOffGround] are the whole difference between this and Palace. Palace
 * stored a single `pendingJump` boolean and cleared it every tick whether or not the physics could
 * act on it, so a tap arriving while airborne vanished. Here a press is *remembered* for
 * [Tuning.jumpBufferFrames] and only cleared when it is actually spent or when it expires.
 */
data class Runner(
    val x: Double,
    val y: Double,
    val vx: Double = 0.0,
    val vy: Double = 0.0,
    val facing: Facing = Facing.RIGHT,
    val phase: Phase = Phase.AIRBORNE,
    /** Frames since an unspent jump press, or null when there is none outstanding. */
    val jumpPressedAgo: Int? = null,
    /** Frames since last standing on something. 0 while grounded. Drives coyote time. */
    val framesOffGround: Int = 0,
    /** Whether jump was held last frame, so a held button does not re-trigger. */
    val jumpHeld: Boolean = false,
    /** Height at which the current fall began, for fall-damage grading. */
    val fallFromY: Double = 0.0,
    val frame: Int = 0,
) {
    val grounded: Boolean get() = phase == Phase.GROUNDED
    val over: Boolean get() = phase == Phase.EXITED || phase == Phase.DEAD
}

/**
 * The Vaults' movement, as a pure `(state, buttons) -> state` step at a fixed timestep.
 *
 * Fixed rather than delta-timed on purpose: a run is then perfectly reproducible from its input
 * sequence, which is what lets a test replay a room, and what lets the browser harness and the
 * phone agree frame for frame.
 */
object Motion {

    /**
     * Places the runner at the room's entrance.
     *
     * A runner dropped into mid-air must *not* start with coyote time in hand — otherwise a jump
     * pressed on the first frame of a fall fires out of thin air. [Runner.framesOffGround] is
     * therefore seeded past the grace window when there is nothing underfoot, and the fall's
     * origin is the spawn height rather than zero, so fall damage is measured from where the
     * runner actually started.
     */
    fun spawn(room: Room, tuning: Tuning = Tuning()): Runner {
        val ground = room.groundAt(room.spawnX, room.spawnY)
        return Runner(
            x = room.spawnX,
            y = room.spawnY,
            phase = if (ground != null) Phase.GROUNDED else Phase.AIRBORNE,
            framesOffGround = if (ground != null) 0 else tuning.coyoteFrames + 1,
            fallFromY = room.spawnY,
        )
    }

    fun tick(runner: Runner, room: Room, buttons: Buttons, tuning: Tuning = Tuning()): Runner {
        if (runner.over) return runner
        val dt = Tuning.DT

        // --- input bookkeeping, before any physics ---------------------------------------
        // A press is recorded on the rising edge and then ages. It survives frames on which it
        // cannot be used, which is precisely what Palace failed to do.
        val pressedNow = buttons.jump && !runner.jumpHeld
        var jumpAgo = when {
            pressedNow -> 0
            runner.jumpPressedAgo != null -> runner.jumpPressedAgo + 1
            else -> null
        }
        if (jumpAgo != null && jumpAgo > tuning.jumpBufferFrames) jumpAgo = null

        // --- horizontal: accelerate toward the held direction, brake toward rest ----------
        val want = (if (buttons.right) 1.0 else 0.0) - (if (buttons.left) 1.0 else 0.0)
        var vx = runner.vx
        vx = if (want != 0.0) {
            val target = want * tuning.runSpeed
            // Reversing brakes first, so a turn costs time and a run stays committed.
            val rate = if (sign(vx) != 0.0 && sign(vx) != want) tuning.friction else tuning.accel
            approach(vx, target, rate * dt)
        } else {
            approach(vx, 0.0, tuning.friction * dt)
        }

        val facing = when {
            want > 0 -> Facing.RIGHT
            want < 0 -> Facing.LEFT
            else -> runner.facing
        }

        // --- vertical: spend a buffered jump if coyote time still allows it ---------------
        val mayJump = jumpAgo != null && runner.framesOffGround <= tuning.coyoteFrames &&
            runner.vy >= 0.0
        var vy: Double
        var spentJump = false
        if (mayJump) {
            vy = tuning.jumpVelocity
            spentJump = true
        } else {
            vy = min(runner.vy + tuning.gravity * dt, tuning.maxFallSpeed)
        }

        val nx = runner.x + vx * dt
        var ny = runner.y + vy * dt

        // --- landing ----------------------------------------------------------------------
        val landing = if (vy > 0.0) room.landingAt(nx, runner.y, ny) else null
        var phase = if (landing != null) Phase.GROUNDED else Phase.AIRBORNE
        var framesOff = if (landing != null) 0 else runner.framesOffGround + 1
        var fallFrom = runner.fallFromY

        if (landing != null) {
            ny = landing.y
            val drop = ny - runner.fallFromY
            vy = 0.0
            if (drop >= tuning.fatalFall) phase = Phase.DEAD
        } else if (spentJump || (runner.grounded && vy > 0.0)) {
            // A new descent starts at the apex of a jump or the lip of a ledge, not at spawn.
            fallFrom = runner.y
        } else if (runner.vy <= 0.0 && vy > 0.0) {
            fallFrom = ny
        }

        if (phase == Phase.AIRBORNE && ny > room.abyssY) phase = Phase.DEAD
        if (phase == Phase.GROUNDED && nx >= room.exitX) phase = Phase.EXITED

        return runner.copy(
            x = max(nx, 0.0),
            y = ny,
            vx = vx,
            vy = vy,
            facing = facing,
            phase = phase,
            jumpPressedAgo = if (spentJump) null else jumpAgo,
            framesOffGround = framesOff,
            jumpHeld = buttons.jump,
            fallFromY = fallFrom,
            frame = runner.frame + 1,
        )
    }

    /** Moves [from] toward [to] by at most [step]. */
    private fun approach(from: Double, to: Double, step: Double): Double =
        if (abs(to - from) <= step) to else from + sign(to - from) * step
}
