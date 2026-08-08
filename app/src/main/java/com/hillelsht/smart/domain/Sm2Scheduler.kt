package com.hillelsht.smart.domain

import com.hillelsht.smart.domain.model.Phase
import com.hillelsht.smart.domain.model.Rating
import com.hillelsht.smart.domain.model.ReviewState
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Spaced repetition scheduling: SM-2 with the two refinements that every modern
 * implementation adopts.
 *
 * Plain SM-2 multiplies the previous interval by the ease factor for every passing grade,
 * which makes "Hard" and "Good" behave almost identically and gives "Easy" no real reward.
 * So, as Anki and its descendants do:
 *  - **Hard** advances by a flat [HARD_MULTIPLIER] rather than by ease, and still drops ease.
 *  - **Easy** gets an [EASY_BONUS] on top of ease.
 *
 * Everything else is textbook SM-2: ease starts at [DEFAULT_EASE], moves by the standard
 * quality polynomial, and never falls below [MIN_EASE].
 *
 * The whole class is a pure function of (state, rating, today) — no clock, no storage — which
 * is why it can be unit-tested exhaustively.
 */
object Sm2Scheduler {

    const val DEFAULT_EASE = 2.5
    const val MIN_EASE = 1.3
    const val HARD_MULTIPLIER = 1.2
    const val EASY_BONUS = 1.3

    /** Two years. Past this, review frequency stops meaning anything useful. */
    const val MAX_INTERVAL_DAYS = 730

    /** Interval in days at which a fact counts as "mastered" — the standard mature threshold. */
    const val MATURE_INTERVAL_DAYS = 21

    /** A fact that has never been studied: due immediately, no history. */
    fun initial(factId: String, today: LocalDate): ReviewState = ReviewState(
        factId = factId,
        phase = Phase.NEW,
        repetitions = 0,
        intervalDays = 0,
        easeFactor = DEFAULT_EASE,
        dueDate = today,
        lapses = 0,
        lastReviewed = null,
        totalReviews = 0,
        correctReviews = 0,
    )

    /**
     * Grade a fact and produce its next state.
     *
     * A lapse ([Rating.AGAIN]) resets the repetition count and returns the fact to
     * [Phase.LEARNING] due the same day, so it reappears before the session ends rather than
     * tomorrow — failing a card and then not seeing it again for 24 hours is how facts get
     * lost.
     */
    fun schedule(state: ReviewState, rating: Rating, today: LocalDate): ReviewState {
        val nextEase = nextEase(state.easeFactor, rating)
        val nextInterval = nextInterval(state, rating)
        val lapsed = rating == Rating.AGAIN

        return state.copy(
            phase = if (lapsed) Phase.LEARNING else Phase.REVIEW,
            repetitions = if (lapsed) 0 else state.repetitions + 1,
            intervalDays = nextInterval,
            easeFactor = nextEase,
            dueDate = today.plusDays(nextInterval.toLong()),
            lapses = if (lapsed) state.lapses + 1 else state.lapses,
            lastReviewed = today,
            totalReviews = state.totalReviews + 1,
            correctReviews = state.correctReviews + if (lapsed) 0 else 1,
        )
    }

    /**
     * The SM-2 ease update: `EF' = EF + (0.1 - (5-q) * (0.08 + (5-q) * 0.02))`.
     *
     * Good is neutral, Easy adds 0.10, Hard subtracts 0.14, Again subtracts 0.32.
     */
    internal fun nextEase(current: Double, rating: Rating): Double {
        val delta = 5 - rating.q
        val updated = current + (0.1 - delta * (0.08 + delta * 0.02))
        return max(MIN_EASE, updated)
    }

    /**
     * Next interval in days. Zero means "again today".
     *
     * Each passing grade is forced to advance by at least one day past the previous interval,
     * so a long-standing fact can never silently shrink its schedule after a Hard.
     */
    internal fun nextInterval(state: ReviewState, rating: Rating): Int {
        if (rating == Rating.AGAIN) return 0

        val previous = state.intervalDays
        val ease = nextEase(state.easeFactor, rating)

        val raw = when (state.repetitions) {
            0 -> if (rating == Rating.EASY) 4 else 1
            1 -> when (rating) {
                Rating.HARD -> 4
                Rating.GOOD -> 6
                else -> 10
            }
            else -> {
                val factor = when (rating) {
                    Rating.HARD -> HARD_MULTIPLIER
                    Rating.GOOD -> ease
                    else -> ease * EASY_BONUS
                }
                max(previous + 1, (previous * factor).roundToInt())
            }
        }
        return raw.coerceIn(1, MAX_INTERVAL_DAYS)
    }
}
