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
 */
object MasteryCalculator {

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

            CategoryMastery(
                category = category,
                totalFacts = total,
                seenFacts = forCategory.size,
                masteredFacts = masteredCount,
                mastery = if (total == 0) 0f else (summed / total).toFloat().coerceIn(0f, 1f),
            )
        }
    }

    /** Corpus-wide mastery, weighted by category size rather than by category count. */
    fun overall(byCategory: List<CategoryMastery>): Float {
        val total = byCategory.sumOf { it.totalFacts }
        if (total == 0) return 0f
        val weighted = byCategory.sumOf { it.mastery.toDouble() * it.totalFacts }
        return (weighted / total).toFloat().coerceIn(0f, 1f)
    }
}
