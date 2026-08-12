package com.hillelsht.smart.domain.play.vaults

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/** What the player is holding this frame. Raw button state — edge detection happens in [Runner]. */
data class Buttons(
    val left: Boolean = false,
    val right: Boolean = false,
    val jump: Boolean = false,
    /** Let go of a ledge you are hanging from. */
    val down: Boolean = false,
)

enum class Facing { LEFT, RIGHT }

enum class Phase { GROUNDED, AIRBORNE, HANGING, EXITED, DEAD }

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

    /** The lip being hung from, and its x, while [phase] is [Phase.HANGING]. */
    val hangingLedgeId: String? = null,
    val hangingLipX: Double = 0.0,
    /** Consecutive frames spent standing on the current collapsing tile. */
    val standingOn: String? = null,
    val standingFrames: Int = 0,
    /** Tiles that have already given way. Persist for the whole visit to a room. */
    val broken: Set<String> = emptySet(),
    val health: Int = 3,
    /** Frames left in which no lip may be caught, set on letting go of one. */
    val grabLockout: Int = 0,
) {
    val grounded: Boolean get() = phase == Phase.GROUNDED
    val hanging: Boolean get() = phase == Phase.HANGING
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
            health = tuning.startingHealth,
        )
    }

    fun tick(runner: Runner, room: Room, buttons: Buttons, tuning: Tuning = Tuning()): Runner {
        if (runner.over) return runner
        return if (runner.hanging) {
            tickHanging(runner, room, buttons, tuning)
        } else {
            tickFree(runner, room, buttons, tuning)
        }
    }

    // --- hanging ---------------------------------------------------------------------------

    /**
     * Hanging is a *rest*, not a timer. There is no grip meter and no drift: you hang until you
     * decide to climb or let go. That makes the grab somewhere to think, which is what turns a
     * vertical room from a reflex test into a route-reading one.
     */
    private fun tickHanging(runner: Runner, room: Room, buttons: Buttons, tuning: Tuning): Runner {
        val ledge = room.ledges.firstOrNull { it.id == runner.hangingLedgeId }
            ?: return runner.copy(phase = Phase.AIRBORNE, hangingLedgeId = null)

        val pressedNow = buttons.jump && !runner.jumpHeld
        val base = runner.copy(jumpHeld = buttons.jump, frame = runner.frame + 1)

        // Climbing up puts you a little inside the lip, so you are standing rather than teetering.
        if (pressedNow) {
            val inward = if (runner.hangingLipX <= ledge.x0) 1.0 else -1.0
            val landX = (runner.hangingLipX + inward * 8.0).coerceIn(ledge.x0, ledge.x1)
            return base.copy(
                phase = Phase.GROUNDED,
                x = landX,
                y = ledge.y,
                vx = 0.0,
                vy = 0.0,
                hangingLedgeId = null,
                framesOffGround = 0,
                fallFromY = ledge.y,
                jumpPressedAgo = null,
            )
        }
        if (buttons.down) {
            return base.copy(
                phase = Phase.AIRBORNE,
                hangingLedgeId = null,
                vy = 0.0,
                framesOffGround = tuning.coyoteFrames + 1,
                fallFromY = runner.y,
                grabLockout = tuning.grabLockoutFrames,
            )
        }
        return checkHazards(base, room)
    }

    // --- running, jumping, falling -----------------------------------------------------------

    private fun tickFree(runner: Runner, room: Room, buttons: Buttons, tuning: Tuning): Runner {
        val dt = Tuning.DT

        // Input bookkeeping, before any physics. A press is recorded on the rising edge and then
        // ages. It survives frames on which it cannot be used, which is precisely what Palace
        // failed to do.
        val pressedNow = buttons.jump && !runner.jumpHeld
        var jumpAgo = when {
            pressedNow -> 0
            runner.jumpPressedAgo != null -> runner.jumpPressedAgo + 1
            else -> null
        }
        if (jumpAgo != null && jumpAgo > tuning.jumpBufferFrames) jumpAgo = null

        // Horizontal: accelerate toward the held direction, brake toward rest.
        val want = (if (buttons.right) 1.0 else 0.0) - (if (buttons.left) 1.0 else 0.0)
        var vx = if (want != 0.0) {
            val target = want * tuning.runSpeed
            // Reversing brakes first, so a turn costs time and a run stays committed.
            val rate = if (sign(runner.vx) != 0.0 && sign(runner.vx) != want) tuning.friction else tuning.accel
            approach(runner.vx, target, rate * dt)
        } else {
            approach(runner.vx, 0.0, tuning.friction * dt)
        }

        val facing = when {
            want > 0 -> Facing.RIGHT
            want < 0 -> Facing.LEFT
            else -> runner.facing
        }

        // Vertical: spend a buffered jump if coyote time still allows it.
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

        val nx = (runner.x + vx * dt).coerceAtLeast(0.0)
        var ny = runner.y + vy * dt

        // Landing.
        val landing = if (vy > 0.0) room.landingAt(nx, runner.y, ny, runner.broken) else null
        var phase = if (landing != null) Phase.GROUNDED else Phase.AIRBORNE
        var framesOff = if (landing != null) 0 else runner.framesOffGround + 1
        var fallFrom = runner.fallFromY
        var health = runner.health
        var hangId: String? = null
        var hangLip = 0.0

        if (landing != null) {
            ny = landing.y
            val drop = ny - runner.fallFromY
            vy = 0.0
            if (drop >= tuning.fatalFall) {
                health = 0
                phase = Phase.DEAD
            } else if (drop >= tuning.safeFall) {
                health -= 1
                if (health <= 0) phase = Phase.DEAD
            }
        } else {
            // No floor here — is there a lip within reach? Only while falling, and only if the
            // player is not actively holding away from it, so a grab is never a surprise.
            if (vy > 0.0 && !buttons.down && runner.grabLockout == 0) {
                val grab = room.grabbableAt(nx, runner.y, ny, tuning, runner.broken)
                if (grab != null) {
                    phase = Phase.HANGING
                    hangId = grab.first.id
                    hangLip = grab.second
                    vy = 0.0
                    vx = 0.0
                    ny = grab.first.y
                    framesOff = 0
                }
            }
            if (phase == Phase.AIRBORNE) {
                if (spentJump || (runner.grounded && vy > 0.0)) fallFrom = runner.y
                else if (runner.vy <= 0.0 && vy > 0.0) fallFrom = ny
            }
        }

        // Collapsing floor: standing still on one is what breaks it, and it stays broken.
        var standingOn = runner.standingOn
        var standingFrames = runner.standingFrames
        var broken = runner.broken
        if (phase == Phase.GROUNDED && landing != null && landing.collapsing) {
            standingFrames = if (standingOn == landing.id) standingFrames + 1 else 1
            standingOn = landing.id
            if (standingFrames >= tuning.collapseFrames) {
                broken = broken + landing.id
                standingOn = null
                standingFrames = 0
                phase = Phase.AIRBORNE
                framesOff = 1
                fallFrom = ny
            }
        } else if (phase != Phase.GROUNDED || landing?.id != standingOn) {
            standingOn = if (phase == Phase.GROUNDED) landing?.id else null
            standingFrames = 0
        }

        if (phase == Phase.AIRBORNE && ny > room.abyssY) phase = Phase.DEAD
        if (phase == Phase.GROUNDED && nx >= room.exitX) phase = Phase.EXITED

        val next = runner.copy(
            x = nx,
            y = ny,
            vx = if (phase == Phase.HANGING) 0.0 else vx,
            vy = vy,
            facing = facing,
            phase = phase,
            jumpPressedAgo = if (spentJump) null else jumpAgo,
            framesOffGround = framesOff,
            jumpHeld = buttons.jump,
            fallFromY = fallFrom,
            frame = runner.frame + 1,
            hangingLedgeId = hangId,
            hangingLipX = hangLip,
            standingOn = standingOn,
            standingFrames = standingFrames,
            broken = broken,
            health = health,
            grabLockout = (runner.grabLockout - 1).coerceAtLeast(0),
        )
        return checkHazards(next, room)
    }

    /** Blades are resolved after movement, against the frame the runner has arrived on. */
    private fun checkHazards(runner: Runner, room: Room): Runner {
        if (runner.over) return runner
        val caught = room.chompers.any { it.catches(runner.frame, runner.x, runner.y) }
        return if (caught) runner.copy(health = 0, phase = Phase.DEAD) else runner
    }

    /** Moves [from] toward [to] by at most [step]. */
    private fun approach(from: Double, to: Double, step: Double): Double =
        if (abs(to - from) <= step) to else from + sign(to - from) * step
}
