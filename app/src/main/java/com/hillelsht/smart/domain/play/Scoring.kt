package com.hillelsht.smart.domain.play

/**
 * What an answer is worth, for every game in the Play tab.
 *
 * One place on purpose. The Climb and the games after it all reward the same thing — knowing an
 * answer and knowing it quickly — and if each game worked that out for itself they would drift
 * into paying differently for identical play, which reads to a player as one of them being
 * broken.
 *
 * Speed is rewarded on a curve rather than a cliff. A linear "points for time remaining" makes
 * every question a race and punishes reading the question properly; this gives most of the bonus
 * away in the first few seconds and then tails off, so thinking for four seconds instead of two
 * costs a little and thinking for twelve costs a lot.
 */
object Scoring {

    /** What a correct answer is worth before speed and combo. */
    const val BASE = 100

    /** The most a fast answer can add on top of [BASE]. */
    const val MAX_SPEED_BONUS = 100

    /** After this long an answer earns no speed bonus at all — but it still counts. */
    const val ANSWER_WINDOW_MS = 15_000L

    /** Beyond this the multiplier stops growing, so one long streak cannot dwarf a whole run. */
    const val MAX_COMBO = 8

    /**
     * How much of the speed bonus an answer earns, 1f the instant the question appears and 0f
     * at [ANSWER_WINDOW_MS]. Squared, which is what puts the value in the first few seconds.
     */
    fun speedFraction(elapsedMs: Long): Float {
        if (elapsedMs <= 0L) return 1f
        if (elapsedMs >= ANSWER_WINDOW_MS) return 0f
        val remaining = 1f - elapsedMs.toFloat() / ANSWER_WINDOW_MS
        return remaining * remaining
    }

    /**
     * The multiplier a streak of [combo] correct answers earns. 1x at zero, rising to 3x at
     * [MAX_COMBO] — enough to make a streak feel like something without making the first mistake
     * of a run catastrophic.
     */
    fun multiplier(combo: Int): Float =
        1f + 2f * (combo.coerceIn(0, MAX_COMBO).toFloat() / MAX_COMBO)

    /** Points for one answer. A wrong answer is worth nothing; it is never worth less. */
    fun points(correct: Boolean, elapsedMs: Long, combo: Int): Int {
        if (!correct) return 0
        val raw = (BASE + MAX_SPEED_BONUS * speedFraction(elapsedMs)) * multiplier(combo)
        return raw.toInt()
    }

    /** The streak after an answer: one longer, or back to nothing. */
    fun nextCombo(combo: Int, correct: Boolean): Int =
        if (correct) (combo + 1).coerceAtMost(MAX_COMBO) else 0
}
