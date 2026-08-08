package com.hillelsht.smart.domain

import com.hillelsht.smart.domain.model.Fact
import com.hillelsht.smart.domain.model.Phase
import com.hillelsht.smart.domain.model.ReviewState
import java.time.LocalDate

/** The shape of today's session, computed before anything is shown. */
data class DailyPlan(
    val newFactIds: List<String>,
    val dueFactIds: List<String>,
) {
    val totalItems: Int get() = newFactIds.size + dueFactIds.size
    val isEmpty: Boolean get() = totalItems == 0
}

/**
 * Decides what to study today.
 *
 * Two rules keep the workload humane and the learning ordered:
 *  - **Reviews come before new material.** Debt first; there is no point learning twelve new
 *    facts while forty are slipping away.
 *  - **New facts are capped per day.** Every new fact is a review obligation for months, so an
 *    unbounded first session quietly builds a backlog the learner can never clear.
 *
 * New material is dealt round-robin across categories so that a day's batch is varied rather
 * than forty consecutive capital cities, and within a category the easiest facts come first.
 */
object SessionPlanner {

    const val DEFAULT_NEW_PER_DAY = 12
    const val DEFAULT_REVIEW_CAP = 60

    fun plan(
        allFacts: List<Fact>,
        states: Map<String, ReviewState>,
        today: LocalDate,
        newPerDay: Int = DEFAULT_NEW_PER_DAY,
        reviewCap: Int = DEFAULT_REVIEW_CAP,
        newLearnedToday: Int = 0,
    ): DailyPlan {
        val due = states.values
            .filter { it.phase != Phase.NEW && it.isDue(today) }
            .sortedWith(compareBy({ it.dueDate }, { -it.lapses }))
            .take(reviewCap)
            .map { it.factId }

        val remainingNew = (newPerDay - newLearnedToday).coerceAtLeast(0)
        val unseen = allFacts.filter { fact ->
            states[fact.id]?.phase.let { it == null || it == Phase.NEW }
        }
        val newIds = interleaveByCategory(unseen).take(remainingNew).map { it.id }

        return DailyPlan(newFactIds = newIds, dueFactIds = due)
    }

    /** Round-robins across categories so a batch never comes from a single subject. */
    internal fun interleaveByCategory(facts: List<Fact>): List<Fact> {
        val queues = facts
            .groupBy { it.category }
            .mapValues { (_, group) -> group.sortedWith(compareBy({ it.difficulty }, { it.id })) }
            .toList()
            .sortedBy { (category, _) -> category.ordinal }
            .map { (_, group) -> ArrayDeque(group) }

        val result = mutableListOf<Fact>()
        while (queues.any { it.isNotEmpty() }) {
            for (queue in queues) {
                if (queue.isNotEmpty()) result += queue.removeFirst()
            }
        }
        return result
    }
}
