@file:OptIn(ExperimentalJsExport::class)

package com.hillelsht.smart.domain.play.vaults

/**
 * The browser harness's entire surface onto the game.
 *
 * This is the only file in the project that knows JavaScript exists. Everything it exposes is a
 * number, a boolean or a string, because `@JsExport` cannot carry Kotlin collections or data
 * classes across the boundary — which is a useful discipline: it keeps `domain/play/vaults` free of
 * any annotation or type chosen to please a second platform.
 *
 * Lives in `webplay/` rather than in the app so nothing shipping to the phone depends on it.
 */
@JsExport
class Session {

    private var tuning: Tuning = Tuning()
    private var descent: Descent = DescentRules.start(Rooms.all, tuning)
    private var framesSinceDeath: Int = 0

    /** Set to play a single room on repeat instead of the whole descent, or -1 for the descent. */
    private var soloRoom: Int = -1

    /**
     * Every frame's buttons, in order — which is the whole run, because the engine is
     * deterministic. See [Ghost]. Raw player input is recorded even on frames spent dead, when it
     * is ignored: [Ghost.replay] ignores those frames in exactly the same way, and dropping them
     * here would leave a replay a death out of step with the run it came from.
     */
    private val recorded = mutableListOf<Buttons>()

    private var ghost: MutableList<Buttons> = mutableListOf()
    private var ghostDescent: Descent? = null
    private var ghostFrame: Int = 0
    private var ghostSinceDeath: Int = 0

    // --- the run ---------------------------------------------------------------------------

    fun tick(left: Boolean, right: Boolean, jump: Boolean, down: Boolean) {
        val buttons = Buttons(left, right, jump, down)
        if (recorded.size < Ghost.MAX_FRAMES) recorded += buttons
        stepGhost()

        if (descent.runner.phase == Phase.DEAD) {
            framesSinceDeath++
            descent = DescentRules.tick(descent, Buttons(), tuning)
            if (DescentRules.readyToRespawn(descent, framesSinceDeath)) {
                descent = DescentRules.respawn(descent, tuning)
                framesSinceDeath = 0
            }
            return
        }
        descent = DescentRules.tick(descent, buttons, tuning)
    }

    fun restart() {
        framesSinceDeath = 0
        recorded.clear()
        descent = if (soloRoom >= 0) {
            DescentRules.start(listOf(Rooms.all[soloRoom]), tuning)
        } else {
            DescentRules.start(Rooms.all, tuning)
        }
        rewindGhost()
    }

    /** Jump to a room and stay there, for working on one piece of geometry. */
    fun playRoom(index: Int) {
        soloRoom = index.coerceIn(0, Rooms.all.size - 1)
        restart()
    }

    fun playDescent() {
        soloRoom = -1
        restart()
    }

    // --- ghosts ------------------------------------------------------------------------------
    //
    // A race against a link. The opponent is not simulated or approximated: their button stream is
    // replayed through the same rules on the same room, so what you are watching is the run they
    // actually had. Nothing about it needs a server, which is the point.

    /** The run just played, as text short enough to put in a URL. Empty if nothing was played. */
    fun recording(): String = Ghost.encode(recorded)

    val recordedFrames: Int get() = recorded.size

    /** Loads a shared run. False — and no ghost — if the text is not one. */
    fun loadGhost(text: String): Boolean {
        val frames = Ghost.decode(text) ?: return false
        ghost = frames.toMutableList()
        rewindGhost()
        return true
    }

    fun clearGhost() {
        ghost = mutableListOf()
        ghostDescent = null
    }

    private fun rewindGhost() {
        ghostFrame = 0
        ghostSinceDeath = 0
        ghostDescent = if (ghost.isEmpty()) null else {
            DescentRules.start(if (soloRoom >= 0) listOf(Rooms.all[soloRoom]) else Rooms.all, tuning)
        }
    }

    /**
     * One frame of the ghost, in lockstep with the live run.
     *
     * The death handling is a copy of [Ghost.replay]'s rather than a call to it, because that
     * function replays a whole run at once and this has to stop after each frame so the runner can
     * be drawn. `GhostTest` is what keeps the two honest: it asserts a replayed run matches the
     * one it came from frame for frame, deaths included.
     */
    private fun stepGhost() {
        val current = ghostDescent ?: return
        if (ghostFrame >= ghost.size || current.finished) return
        val buttons = ghost[ghostFrame]
        ghostFrame++
        if (current.runner.phase == Phase.DEAD) {
            ghostSinceDeath++
            val ticked = DescentRules.tick(current, Buttons(), tuning)
            ghostDescent = if (DescentRules.readyToRespawn(ticked, ghostSinceDeath)) {
                ghostSinceDeath = 0
                DescentRules.respawn(ticked, tuning)
            } else {
                ticked
            }
            return
        }
        ghostDescent = DescentRules.tick(current, buttons, tuning)
    }

    val hasGhost: Boolean get() = ghostDescent != null
    val ghostX: Double get() = ghostDescent?.runner?.x ?: 0.0
    val ghostY: Double get() = ghostDescent?.runner?.y ?: 0.0
    val ghostHanging: Boolean get() = ghostDescent?.runner?.hanging ?: false
    val ghostDead: Boolean get() = ghostDescent?.runner?.phase == Phase.DEAD
    val ghostFinished: Boolean get() = ghostDescent?.finished ?: false
    val ghostElapsedFrames: Int get() = ghostDescent?.elapsedFrames ?: 0

    /** True once the ghost's own recording runs out, so a page can stop drawing a frozen figure. */
    val ghostSpent: Boolean get() = ghostDescent != null && ghostFrame >= ghost.size

    // --- what the renderer draws -------------------------------------------------------------

    val x: Double get() = descent.runner.x
    val y: Double get() = descent.runner.y
    val vx: Double get() = descent.runner.vx
    val vy: Double get() = descent.runner.vy
    val grounded: Boolean get() = descent.runner.grounded
    val hanging: Boolean get() = descent.runner.hanging
    val facingRight: Boolean get() = descent.runner.facing == Facing.RIGHT
    val phase: String get() = descent.runner.phase.name
    val health: Int get() = descent.runner.health

    /** Frames since an unspent jump press, or -1. Drawn in the HUD so buffering is *visible*. */
    val jumpBufferedAgo: Int get() = descent.runner.jumpPressedAgo ?: -1
    val framesOffGround: Int get() = descent.runner.framesOffGround

    val roomId: String get() = descent.room.id
    val roomIndex: Int get() = descent.index
    val epigraph: String get() = descent.room.epigraph
    val exitX: Double get() = descent.room.exitX
    val abyssY: Double get() = descent.room.abyssY

    val elapsedFrames: Int get() = descent.elapsedFrames
    val deaths: Int get() = descent.deaths
    val roomsCleared: Int get() = descent.roomsCleared
    val finished: Boolean get() = descent.finished
    val soloing: Boolean get() = soloRoom >= 0

    val ledgeCount: Int get() = descent.room.ledges.size
    fun ledgeX0(i: Int): Double = descent.room.ledges[i].x0
    fun ledgeX1(i: Int): Double = descent.room.ledges[i].x1
    fun ledgeY(i: Int): Double = descent.room.ledges[i].y
    fun ledgeCollapsing(i: Int): Boolean = descent.room.ledges[i].collapsing
    fun ledgeBroken(i: Int): Boolean = descent.room.ledges[i].id in descent.runner.broken

    /** 0 while untouched, rising to 1 as a collapsing tile runs out of patience. */
    fun ledgeStrain(i: Int): Double {
        val l = descent.room.ledges[i]
        if (!l.collapsing || descent.runner.standingOn != l.id) return 0.0
        return (descent.runner.standingFrames.toDouble() / tuning.collapseFrames).coerceIn(0.0, 1.0)
    }

    val chomperCount: Int get() = descent.room.chompers.size
    fun chomperX(i: Int): Double = descent.room.chompers[i].x
    fun chomperY(i: Int): Double = descent.room.chompers[i].y
    fun chomperReach(i: Int): Double = descent.room.chompers[i].reach
    fun chomperHalfWidth(i: Int): Double = descent.room.chompers[i].halfWidth
    fun chomperOpen(i: Int): Boolean = descent.room.chompers[i].isOpen(descent.runner.frame)

    /** How far through its cycle a blade is, 0..1 — enough to draw an anticipation cue. */
    fun chomperPhase(i: Int): Double {
        val c = descent.room.chompers[i]
        val p = ((descent.runner.frame + c.phase) % c.periodFrames + c.periodFrames) % c.periodFrames
        return p.toDouble() / c.periodFrames
    }

    // --- live tuning, which is the reason this harness exists ------------------------------

    fun setTuning(
        gravity: Double,
        runSpeed: Double,
        accel: Double,
        friction: Double,
        jumpVelocity: Double,
        jumpBufferFrames: Int,
        coyoteFrames: Int,
    ) {
        tuning = tuning.copy(
            gravity = gravity,
            runSpeed = runSpeed,
            accel = accel,
            friction = friction,
            jumpVelocity = jumpVelocity,
            jumpBufferFrames = jumpBufferFrames,
            coyoteFrames = coyoteFrames,
        )
    }

    val gravity: Double get() = tuning.gravity
    val runSpeed: Double get() = tuning.runSpeed
    val accel: Double get() = tuning.accel
    val friction: Double get() = tuning.friction
    val jumpVelocity: Double get() = tuning.jumpVelocity
    val jumpBufferFrames: Int get() = tuning.jumpBufferFrames
    val coyoteFrames: Int get() = tuning.coyoteFrames

    /**
     * The current room's timing slack under the current tuning, in frames.
     *
     * Deliberately *not* recomputed every frame: it replays the room hundreds of times. Showing it
     * live is the point — a tuning change that makes the game feel snappier while quietly halving
     * the margin is exactly the mistake this harness exists to catch.
     */
    fun margin(): Int = Playtest.margin(descent.room, tuning)

    companion object {
        val roomCount: Int get() = Rooms.all.size
        fun roomIdAt(i: Int): String = Rooms.all[i].id
        val minMargin: Int get() = Playtest.MIN_MARGIN_FRAMES
        val fps: Int get() = Tuning.FPS
    }
}
