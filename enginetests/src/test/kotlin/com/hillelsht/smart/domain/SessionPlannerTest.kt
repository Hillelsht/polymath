package com.hillelsht.smart.domain

import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.domain.model.Fact
import com.hillelsht.smart.domain.model.Phase
import com.hillelsht.smart.domain.model.ReviewState
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionPlannerTest {

    private val today: LocalDate = LocalDate.of(2026, 5, 1)

    private fun fact(id: String, category: Category, difficulty: Int = 1) = Fact(
        id = id,
        category = category,
        title = id,
        statement = "s",
        question = "q",
        answer = id,
        answerType = "t",
        difficulty = difficulty,
    )

    private fun reviewState(id: String, dueIn: Long, lapses: Int = 0) = ReviewState(
        factId = id,
        phase = Phase.REVIEW,
        repetitions = 2,
        intervalDays = 6,
        easeFactor = 2.5,
        dueDate = today.plusDays(dueIn),
        lapses = lapses,
        lastReviewed = today.minusDays(6),
        totalReviews = 2,
        correctReviews = 2,
    )

    @Test
    fun `only facts that are actually due are scheduled for review`() {
        val facts = listOf(fact("a", Category.GEOGRAPHY), fact("b", Category.GEOGRAPHY))
        val states = mapOf(
            "a" to reviewState("a", dueIn = 0),
            "b" to reviewState("b", dueIn = 3),
        )

        val plan = SessionPlanner.plan(facts, states, today)
        assertEquals(listOf("a"), plan.dueFactIds)
    }

    @Test
    fun `new facts are capped per day and reduced by what was already learned`() {
        val facts = (1..50).map { fact("f$it", Category.GEOGRAPHY) }

        val fullDay = SessionPlanner.plan(facts, emptyMap(), today, newPerDay = 12)
        assertEquals(12, fullDay.newFactIds.size)

        val partialDay = SessionPlanner.plan(facts, emptyMap(), today, newPerDay = 12, newLearnedToday = 9)
        assertEquals(3, partialDay.newFactIds.size)

        val doneForToday = SessionPlanner.plan(facts, emptyMap(), today, newPerDay = 12, newLearnedToday = 20)
        assertTrue(doneForToday.newFactIds.isEmpty())
    }

    @Test
    fun `a day's new facts are spread across categories rather than drawn from one`() {
        val facts = Category.entries.flatMap { category ->
            (1..10).map { fact("${category.id}-$it", category) }
        }

        val plan = SessionPlanner.plan(facts, emptyMap(), today, newPerDay = 6)
        val categories = plan.newFactIds.map { id -> id.substringBeforeLast('-') }.toSet()

        assertEquals(6, categories.size, "expected one fact from each category, got $categories")
    }

    @Test
    fun `easier facts within a category are introduced first`() {
        val facts = listOf(
            fact("hard", Category.SCIENCE, difficulty = 3),
            fact("easy", Category.SCIENCE, difficulty = 1),
            fact("medium", Category.SCIENCE, difficulty = 2),
        )

        val plan = SessionPlanner.plan(facts, emptyMap(), today, newPerDay = 3)
        assertEquals(listOf("easy", "medium", "hard"), plan.newFactIds)
    }

    @Test
    fun `the most overdue and most lapsed facts are reviewed first`() {
        val facts = (1..3).map { fact("f$it", Category.HISTORY) }
        val states = mapOf(
            "f1" to reviewState("f1", dueIn = 0, lapses = 0),
            "f2" to reviewState("f2", dueIn = -5, lapses = 1),
            "f3" to reviewState("f3", dueIn = -5, lapses = 4),
        )

        val plan = SessionPlanner.plan(facts, states, today)
        assertEquals(listOf("f3", "f2", "f1"), plan.dueFactIds)
    }

    @Test
    fun `already-seen facts are never re-introduced as new`() {
        val facts = listOf(fact("a", Category.ARTS), fact("b", Category.ARTS))
        val states = mapOf("a" to reviewState("a", dueIn = 10))

        val plan = SessionPlanner.plan(facts, states, today)
        assertEquals(listOf("b"), plan.newFactIds)
    }

    @Test
    fun `an empty plan is reported as empty`() {
        val plan = SessionPlanner.plan(emptyList(), emptyMap(), today)
        assertTrue(plan.isEmpty)
        assertEquals(0, plan.totalItems)
    }
}
