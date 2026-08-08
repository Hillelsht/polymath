package com.hillelsht.smart.domain.model

import java.time.LocalDate

/** How well the learner recalled a fact. Maps onto the SM-2 quality scale in [q]. */
enum class Rating(val q: Int, val label: String) {
    AGAIN(2, "Again"),
    HARD(3, "Hard"),
    GOOD(4, "Good"),
    EASY(5, "Easy"),
}

/** Where a fact sits in its lifecycle. */
enum class Phase {
    /** Never taught. */
    NEW,

    /** Taught, but failed at least once since — comes back inside the same session. */
    LEARNING,

    /** Scheduled on a growing interval. */
    REVIEW,
}

/**
 * The scheduler's memory of a single fact.
 *
 * Everything needed to compute the next due date lives here, so scheduling is a pure function
 * of (state, rating, today) and can be tested without a database or a clock.
 */
data class ReviewState(
    val factId: String,
    val phase: Phase,
    val repetitions: Int,
    val intervalDays: Int,
    val easeFactor: Double,
    val dueDate: LocalDate,
    val lapses: Int,
    val lastReviewed: LocalDate?,
    val totalReviews: Int,
    val correctReviews: Int,
) {
    fun isDue(today: LocalDate): Boolean = !dueDate.isAfter(today)

    /** Share of reviews recalled without a lapse. Null until the fact has been reviewed once. */
    val accuracy: Double?
        get() = if (totalReviews == 0) null else correctReviews.toDouble() / totalReviews
}
