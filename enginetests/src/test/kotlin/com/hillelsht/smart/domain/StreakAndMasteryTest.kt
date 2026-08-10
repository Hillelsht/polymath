package com.hillelsht.smart.domain

import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.domain.model.Phase
import com.hillelsht.smart.domain.model.ReviewState
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreakCalculatorTest {

    private val today: LocalDate = LocalDate.of(2026, 3, 10)

    @Test
    fun `no activity means no streak`() {
        val streak = StreakCalculator.compute(emptyList(), today)
        assertEquals(0, streak.current)
        assertEquals(0, streak.longest)
        assertFalse(streak.studiedToday)
    }

    @Test
    fun `consecutive days ending today count up`() {
        val dates = (0..4).map { today.minusDays(it.toLong()) }
        val streak = StreakCalculator.compute(dates, today)
        assertEquals(5, streak.current)
        assertTrue(streak.studiedToday)
    }

    @Test
    fun `a streak survives until a full day is missed`() {
        val dates = (1..3).map { today.minusDays(it.toLong()) }
        val streak = StreakCalculator.compute(dates, today)

        assertEquals(3, streak.current, "yesterday's streak should still be alive today")
        assertFalse(streak.studiedToday)

        val stale = StreakCalculator.compute(dates, today.plusDays(1))
        assertEquals(0, stale.current, "a skipped day ends the streak")
    }

    @Test
    fun `longest streak is remembered after the current one breaks`() {
        val dates = listOf(
            today.minusDays(20), today.minusDays(19), today.minusDays(18), today.minusDays(17),
            today.minusDays(1), today,
        )
        val streak = StreakCalculator.compute(dates, today)
        assertEquals(2, streak.current)
        assertEquals(4, streak.longest)
    }

    @Test
    fun `duplicate dates do not inflate the count`() {
        val dates = listOf(today, today, today.minusDays(1), today.minusDays(1))
        assertEquals(2, StreakCalculator.compute(dates, today).current)
    }
}

class MasteryCalculatorTest {

    private val today: LocalDate = LocalDate.of(2026, 3, 10)

    private fun state(id: String, interval: Int, phase: Phase = Phase.REVIEW) = ReviewState(
        factId = id,
        phase = phase,
        repetitions = 3,
        intervalDays = interval,
        easeFactor = 2.5,
        dueDate = today.plusDays(interval.toLong()),
        lapses = 0,
        lastReviewed = today,
        totalReviews = 3,
        correctReviews = 3,
    )

    @Test
    fun `mastery scales with scheduled interval and saturates at maturity`() {
        assertEquals(0f, MasteryCalculator.factMastery(null))
        assertEquals(0f, MasteryCalculator.factMastery(state("a", 0, Phase.NEW)))
        assertEquals(10f / Sm2Scheduler.MATURE_INTERVAL_DAYS, MasteryCalculator.factMastery(state("b", 10)), 1e-6f)
        assertEquals(1f, MasteryCalculator.factMastery(state("c", 21)))
        assertEquals(1f, MasteryCalculator.factMastery(state("d", 400)))
    }

    @Test
    fun `only intervals at or past the mature threshold count as mastered`() {
        assertFalse(MasteryCalculator.isMastered(state("a", 20)))
        assertTrue(MasteryCalculator.isMastered(state("b", 21)))
    }

    @Test
    fun `category mastery counts the unseen against you, not just what was seen`() {
        val totals = mapOf(Category.GEOGRAPHY to 10, Category.SCIENCE to 10)
        val states = mapOf(
            "g1" to state("g1", 21),
            "g2" to state("g2", 21),
        )
        val categoryOf = { _: String -> Category.GEOGRAPHY }

        val result = MasteryCalculator.byCategory(totals, states, categoryOf)
        val geography = result.first { it.category == Category.GEOGRAPHY }

        assertEquals(2, geography.seenFacts)
        assertEquals(2, geography.masteredFacts)
        assertEquals(0.2f, geography.mastery, 1e-4f)

        val science = result.first { it.category == Category.SCIENCE }
        assertTrue(science.untouched)
        assertEquals(0f, science.mastery)
    }

    @Test
    fun `overall mastery weights categories by size`() {
        val summary = listOf(
            CategoryMastery(Category.GEOGRAPHY, totalFacts = 90, seenFacts = 90, masteredFacts = 90, mastery = 1f),
            CategoryMastery(Category.SCIENCE, totalFacts = 10, seenFacts = 0, masteredFacts = 0, mastery = 0f),
        )
        assertEquals(0.9f, MasteryCalculator.overall(summary), 1e-4f)
    }

    // --- the ring under an endless library -----------------------------------------------
    // LibraryTopUp keeps pulling more facts down as the pool drains. Measured against the
    // whole corpus, the ring would fall every time a shard landed and converge on zero however
    // much was learned — the app punishing a learner for having more left to learn.

    @Test
    fun `a shard landing does not undo progress already made`() {
        val states = (1..20).associate { "g$it" to state("g$it", 21) }
        val categoryOf = { _: String -> Category.GEOGRAPHY }

        fun ringWith(total: Int) = MasteryCalculator
            .byCategory(mapOf(Category.GEOGRAPHY to total), states, categoryOf)
            .first { it.category == Category.GEOGRAPHY }
            .mastery

        // 95 bundled facts, then 150 more arrive, then 150 more again.
        val before = ringWith(95)
        assertEquals(before, ringWith(245), 1e-4f)
        assertEquals(before, ringWith(395), 1e-4f)
    }

    @Test
    fun `the ring is measured against what is actually within reach`() {
        val states = (1..20).associate { "g$it" to state("g$it", 21) }
        val result = MasteryCalculator
            .byCategory(mapOf(Category.GEOGRAPHY to 1_000), states, { Category.GEOGRAPHY })
            .first { it.category == Category.GEOGRAPHY }

        assertEquals(20 + MasteryCalculator.LOOKAHEAD, result.reachableFacts)
        assertEquals(1_000, result.totalFacts, "the true size is still reported honestly")
        assertEquals(20f / (20 + MasteryCalculator.LOOKAHEAD), result.mastery, 1e-4f)
    }

    @Test
    fun `a category smaller than the horizon is unaffected`() {
        // The bundled corpus is well inside the lookahead per category, so nothing a learner
        // has already seen on their rings shifts for reasons they cannot see.
        val states = mapOf("g1" to state("g1", 21), "g2" to state("g2", 21))
        val result = MasteryCalculator
            .byCategory(mapOf(Category.GEOGRAPHY to 10), states, { Category.GEOGRAPHY })
            .first { it.category == Category.GEOGRAPHY }
        assertEquals(10, result.reachableFacts)
        assertEquals(0.2f, result.mastery, 1e-4f)
    }

    @Test
    fun `finishing everything still reads as finished`() {
        // The horizon must not stop a completed category short of a full ring.
        val states = (1..40).associate { "g$it" to state("g$it", 21) }
        val result = MasteryCalculator
            .byCategory(mapOf(Category.GEOGRAPHY to 40), states, { Category.GEOGRAPHY })
            .first { it.category == Category.GEOGRAPHY }
        assertEquals(1f, result.mastery, 1e-4f)
    }

    @Test
    fun `an untouched endless category still reads as zero, not as progress`() {
        val result = MasteryCalculator
            .byCategory(mapOf(Category.GEOGRAPHY to 5_000), emptyMap(), { Category.GEOGRAPHY })
            .first { it.category == Category.GEOGRAPHY }
        assertTrue(result.untouched)
        assertEquals(0f, result.mastery)
    }

    @Test
    fun `overall is not hijacked by whichever category got a shard`() {
        // Weighting by raw totals would let one downloaded shard swamp five finished
        // categories, dropping the headline number for a reason the learner never caused.
        val states = (1..20).associate { "g$it" to state("g$it", 21) }
        val small = MasteryCalculator.byCategory(
            Category.entries.associateWith { 20 }, states, { Category.GEOGRAPHY },
        )
        val oneCategoryTopped = MasteryCalculator.byCategory(
            Category.entries.associateWith { if (it == Category.SCIENCE) 2_000 else 20 },
            states,
            { Category.GEOGRAPHY },
        )
        val drop = MasteryCalculator.overall(small) - MasteryCalculator.overall(oneCategoryTopped)
        assertTrue(drop < 0.05f, "a single shard moved the headline number by $drop")
    }
}
