package com.hillelsht.smart.domain.play

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scoring is shared by every game in the Play tab, so a change here moves the numbers in all of
 * them at once. These pin the properties a player would notice.
 */
class ScoringTest {

    @Test
    fun `a wrong answer is worth nothing, never less`() {
        listOf(0L, 500L, 5_000L, 60_000L).forEach { elapsed ->
            (0..Scoring.MAX_COMBO).forEach { combo ->
                assertEquals(0, Scoring.points(correct = false, elapsedMs = elapsed, combo = combo))
            }
        }
    }

    @Test
    fun `answering faster never scores less`() {
        val scores = (0..15).map { Scoring.points(true, it * 1_000L, combo = 3) }
        scores.zipWithNext { quicker, slower ->
            assertTrue(quicker >= slower, "waiting longer scored more: $scores")
        }
    }

    @Test
    fun `a longer streak never scores less`() {
        val scores = (0..Scoring.MAX_COMBO).map { Scoring.points(true, 3_000L, it) }
        scores.zipWithNext { shorter, longer ->
            assertTrue(longer >= shorter, "a longer streak paid less: $scores")
        }
    }

    @Test
    fun `a slow answer still counts for something`() {
        // The window is a bonus, not a deadline. Someone thinking hard about a hard question
        // must not come away with nothing.
        assertTrue(Scoring.points(true, Scoring.ANSWER_WINDOW_MS * 4, combo = 0) >= Scoring.BASE)
    }

    @Test
    fun `the speed curve pays out early`() {
        // Most of the bonus in the first third of the window is the point: it rewards knowing
        // the answer rather than racing, and four seconds of thought is not punished like twelve.
        assertTrue(Scoring.speedFraction(Scoring.ANSWER_WINDOW_MS / 3) > 0.4f)
        assertTrue(Scoring.speedFraction(Scoring.ANSWER_WINDOW_MS * 2 / 3) < 0.15f)
    }

    @Test
    fun `the speed fraction stays inside its bounds`() {
        listOf(-5_000L, 0L, 1L, 7_500L, 14_999L, 15_000L, 999_999L).forEach {
            val fraction = Scoring.speedFraction(it)
            assertTrue(fraction in 0f..1f, "speedFraction($it) = $fraction")
        }
    }

    @Test
    fun `a streak builds and one mistake ends it`() {
        var combo = 0
        repeat(3) { combo = Scoring.nextCombo(combo, correct = true) }
        assertEquals(3, combo)
        assertEquals(0, Scoring.nextCombo(combo, correct = false))
    }

    @Test
    fun `a streak cannot run away with the game`() {
        var combo = 0
        repeat(200) { combo = Scoring.nextCombo(combo, correct = true) }
        assertEquals(Scoring.MAX_COMBO, combo)
        assertEquals(3f, Scoring.multiplier(combo), 1e-4f)
    }

    @Test
    fun `an out-of-range streak is clamped rather than trusted`() {
        assertEquals(Scoring.multiplier(Scoring.MAX_COMBO), Scoring.multiplier(9_999), 1e-4f)
        assertEquals(1f, Scoring.multiplier(-4), 1e-4f)
    }
}
