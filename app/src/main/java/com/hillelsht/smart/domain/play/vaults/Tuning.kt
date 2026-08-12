package com.hillelsht.smart.domain.play.vaults

/**
 * Every number that decides how the Vaults feel.
 *
 * A data class rather than an `object` of constants so a room, a test or the browser harness can
 * run the same physics at different settings — tuning is then a value to pass, not a recompile.
 * The defaults here are the shipping ones; `packs/play/vaults/tuning.json` overrides them so a
 * feel fix can go out as content rather than as an app release.
 *
 * Distances are world units, where one tile is [TILE]. Times are seconds. `y` grows **downward**,
 * matching both the canvas and the phone's coordinate space, so gravity is positive and a jump
 * impulse is negative.
 *
 * Kotlin stdlib only — no `java.*` and no `android.*`. This whole package compiles to JavaScript
 * for the browser harness (see `webplay/`), and a single `java.time` import would end that.
 */
data class Tuning(
    val gravity: Double = 2_400.0,
    val runSpeed: Double = 240.0,
    /** Reaching [runSpeed] takes `runSpeed / accel` ≈ 0.25s, so a run has to be committed to. */
    val accel: Double = 960.0,
    /** Stopping is faster than starting, which is what makes a careful step feel precise. */
    val friction: Double = 1_600.0,
    val jumpVelocity: Double = -480.0,
    val maxFallSpeed: Double = 1_200.0,

    /**
     * Frames a jump press is remembered for. Palace had no equivalent: it cleared the press every
     * tick whether or not the physics could use it, so any tap arriving mid-air was destroyed and
     * anticipating a landing never worked. This is that bug's fix, expressed as a number.
     */
    val jumpBufferFrames: Int = 8,

    /** Frames after walking off an edge during which a jump is still allowed. */
    val coyoteFrames: Int = 6,

    /** A fall shorter than this is free; beyond it costs health, and [fatalFall] kills. */
    val safeFall: Double = 96.0,
    val fatalFall: Double = 240.0,
) {
    companion object {
        const val TILE = 32.0

        /** The frame budget the whole game is simulated at. Fixed, so runs are reproducible. */
        const val FPS = 60
        const val DT = 1.0 / FPS
    }
}
