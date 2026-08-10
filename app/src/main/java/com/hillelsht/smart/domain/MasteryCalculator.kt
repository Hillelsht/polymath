package com.hillelsht.smart.domain

import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.domain.model.Phase
import com.hillelsht.smart.domain.model.ReviewState

/** What the mastery ring for one category is showing. */
data class CategoryMastery(
    val category: Category,
    val totalFacts: Int,
    val seenFacts: Int,
    val masteredFacts: Int,
    /** 0f..1f — the ring fill. */
    val mastery: Float,
    /** What the ring is measured against: everything seen, plus the next stretch of new. */
    val reachableFacts: Int = totalFacts,
) {
    val untouched: Boolean get() = seenFacts == 0
}

/**
 * Turns raw scheduler state into the numbers shown on Home and Stats.
 *
 * Mastery is measured by *scheduled interval*, not by how many times something was answered
 * correctly. A fact you got right three times in one evening is not learned; a fact the
 * scheduler is willing to leave alone for three weeks is. Each fact contributes
 * `min(1, interval / MATURE_INTERVAL_DAYS)`, so the ring moves a little from the first
 * successful review and reaches full only at genuine retention.
 *
 * It is measured against a **horizon**, not against the whole corpus, and that matters now
 * that the corpus has no end. [LibraryTopUp] keeps pulling more facts down as the pool drains,
 * so a ring reading "fraction of everything" would fall every time a shard arrived and would
 * converge on zero however much was learned — the app would punish the learner for having more
 * to learn. Measuring against what has been started plus the next stretch of new material
 * gives a number that only moves when the learner does.
 */
object MasteryCalculator {

    /**
     * How far past the facts already started the ring looks.
     *
     * Roughly a fortnight of new material: the daily cap is dealt round-robin across six
     * categories, so a category supplies two or so new facts a day. Small enough that a
     * category with a shard waiting behind it does not read as barely begun; large enough that
     * the number is not simply "how well do I know the twelve I did yesterday".
     */
    const val LOOKAHEAD = 30

    fun factMastery(state: ReviewState?): Float {
        if (state == null || state.phase == Phase.NEW) return 0f
        val ratio = state.intervalDays.toFloat() / Sm2Scheduler.MATURE_INTERVAL_DAYS
        return ratio.coerceIn(0f, 1f)
    }

    fun isMastered(state: ReviewState?): Boolean =
        state != null && state.intervalDays >= Sm2Scheduler.MATURE_INTERVAL_DAYS

    /**
     * @param totals fact counts per category, from the content corpus.
     * @param states scheduler state for every fact that has been touched, keyed by fact id.
     * @param categoryOf resolves a fact id to its category.
     */
    fun byCategory(
        totals: Map<Category, Int>,
        states: Map<String, ReviewState>,
        categoryOf: (String) -> Category?,
    ): List<CategoryMastery> {
        val grouped = states.values.groupBy { categoryOf(it.factId) }

        return Category.entries.map { category ->
            val total = totals[category] ?: 0
            val forCategory = grouped[category].orEmpty().filter { it.phase != Phase.NEW }
            val masteredCount = forCategory.count(::isMastered)
            val summed = forCategory.sumOf { factMastery(it).toDouble() }
            val seen = forCategory.size
            val reachable = seen + (total - seen).coerceAtMost(LOOKAHEAD)

            CategoryMastery(
                category = category,
                totalFacts = total,
                seenFacts = seen,
                masteredFacts = masteredCount,
                mastery = if (reachable == 0) 0f else (summed / reachable).toFloat().coerceIn(0f, 1f),
                reachableFacts = reachable,
            )
        }
    }

    /**
     * Mastery across every category, weighted by size rather than by category count.
     *
     * Weighted by *reachable* size for the same reason the ring is: weighting by total would
     * hand a category all the influence for having had a shard downloaded into it.
     */
    fun overall(byCategory: List<CategoryMastery>): Float {
        val total = byCategory.sumOf { it.reachableFacts }
        if (total == 0) return 0f
        val weighted = byCategory.sumOf { it.mastery.toDouble() * it.reachableFacts }
        return (weighted / total).toFloat().coerceIn(0f, 1f)
    }
}
