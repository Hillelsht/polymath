@file:OptIn(ExperimentalJsExport::class)

package com.hillelsht.smart.domain.play.vaults

/**
 * The browser harness's entire surface onto the game.
 *
 * This is the only file in the project that knows JavaScript exists. Everything it exposes is a
 * number, a boolean or a string, because `@JsExport` cannot carry Kotlin collections or data
 * classes across the boundary — which is a useful discipline: it keeps `domain/play/vaults` free
 * of any annotation or type chosen to please a second platform.
 *
 * Lives in `webplay/` rather than in the app so that nothing shipping to the phone depends on it.
 */
@JsExport
class Session(roomIndex: Int) {

    private var room: Room = Rooms.all[roomIndex.coerceIn(0, Rooms.all.size - 1)]
    private var tuning: Tuning = Tuning()
    private var runner: Runner = Motion.spawn(room, tuning)

    // --- what the renderer draws ---------------------------------------------------------

    val x: Double get() = runner.x
    val y: Double get() = runner.y
    val vx: Double get() = runner.vx
    val vy: Double get() = runner.vy
    val grounded: Boolean get() = runner.grounded
    val facingRight: Boolean get() = runner.facing == Facing.RIGHT
    val frame: Int get() = runner.frame
    val phase: String get() = runner.phase.name
    val over: Boolean get() = runner.over

    /** Frames since an unspent jump press, or -1. Drawn as a HUD bar so buffering is *visible*. */
    val jumpBufferedAgo: Int get() = runner.jumpPressedAgo ?: -1
    val framesOffGround: Int get() = runner.framesOffGround

    val roomId: String get() = room.id
    val exitX: Double get() = room.exitX
    val abyssY: Double get() = room.abyssY
    val spawnX: Double get() = room.spawnX
    val spawnY: Double get() = room.spawnY

    val ledgeCount: Int get() = room.ledges.size
    fun ledgeX0(i: Int): Double = room.ledges[i].x0
    fun ledgeX1(i: Int): Double = room.ledges[i].x1
    fun ledgeY(i: Int): Double = room.ledges[i].y

    // --- the loop -------------------------------------------------------------------------

    fun tick(left: Boolean, right: Boolean, jump: Boolean) {
        runner = Motion.tick(runner, room, Buttons(left, right, jump), tuning)
    }

    fun reset() {
        runner = Motion.spawn(room, tuning)
    }

    fun loadRoom(index: Int) {
        room = Rooms.all[index.coerceIn(0, Rooms.all.size - 1)]
        reset()
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
     * The current room's timing margin under the current tuning, in frames.
     *
     * Recomputed on demand rather than per frame — it replays the room once per candidate jump
     * frame. Showing it live is the point: a tuning change that makes the game feel snappier while
     * quietly halving the margin is exactly the mistake this harness exists to catch.
     */
    fun margin(): Int = Playtest.margin(room, tuning)

    companion object {
        val roomCount: Int get() = Rooms.all.size
        fun roomIdAt(i: Int): String = Rooms.all[i].id
        val minMargin: Int get() = Playtest.MIN_MARGIN_FRAMES
        val fps: Int get() = Tuning.FPS
    }
}
