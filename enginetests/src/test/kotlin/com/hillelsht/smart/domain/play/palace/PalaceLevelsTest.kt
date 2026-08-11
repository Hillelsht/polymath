package com.hillelsht.smart.domain.play.palace

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * There is no device to actually play a level on, so a level's honesty — is the pit really
 * jumpable, does the collapsing floor really give enough time to cross, is the ledge really
 * reachable — is checked the same way the chess engine's balance was: by scripting a player
 * through the real [PalacePhysics] rather than by eyeballing the coordinates.
 */
class PalaceLevelsTest {

    @Test
    fun `the hall can be completed by a player who runs, jumps the pit, and answers every gate`() {
        var run = PalaceRun.start(PalaceLevels.theHall)
        val dtMs = 16L
        var ticks = 0

        while (run.phase != PalacePhase.WON && ticks < 20_000) {
            PalaceRules.gateAt(run)?.let { gate ->
                run = PalaceRules.answerGate(run, gate.id, correct = true)
            }

            val input = when {
                run.phase == PalacePhase.HANGING -> PalaceInput(climb = true)
                // Jump only while still approaching the pit's edge on floor1, so a jump taken
                // anywhere else in the level (where it would do nothing but isn't wrong either)
                // doesn't mask a level that actually requires a jump right here.
                run.grounded && run.groundedPlatformId == "floor1" && run.x >= 240f ->
                    PalaceInput(moveRight = true, jump = true)

                else -> PalaceInput(moveRight = true)
            }
            run = PalacePhysics.tick(run, input, dtMs)
            ticks++
        }

        assertTrue(
            run.phase == PalacePhase.WON,
            "a player holding right, jumping the pit and answering every gate should finish " +
                "the level; stuck after $ticks ticks at x=${run.x}, phase=${run.phase}, " +
                "lives=${run.lives}",
        )
    }

    @Test
    fun `running off the pit without jumping actually costs a life`() {
        var run = PalaceRun.start(PalaceLevels.theHall)
        val dtMs = 16L
        var ticks = 0
        while (run.lives == PalaceRules.STARTING_LIVES && ticks < 2_000) {
            run = PalacePhysics.tick(run, PalaceInput(moveRight = true), dtMs)
            ticks++
        }
        assertTrue(
            run.lives < PalaceRules.STARTING_LIVES,
            "walking off the pit's edge without jumping should fall to the floor below",
        )
    }
}
