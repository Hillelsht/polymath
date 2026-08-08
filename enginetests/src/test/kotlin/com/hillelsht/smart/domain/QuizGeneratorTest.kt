package com.hillelsht.smart.domain

import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.domain.model.Fact
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuizGeneratorTest {

    private fun fact(
        id: String,
        answer: String,
        answerType: String,
        category: Category = Category.GEOGRAPHY,
    ) = Fact(
        id = id,
        category = category,
        title = id,
        statement = "$id statement",
        question = "What is $id?",
        answer = answer,
        answerType = answerType,
    )

    private val capitals = listOf(
        fact("c1", "Paris", "capital"),
        fact("c2", "Rome", "capital"),
        fact("c3", "Madrid", "capital"),
        fact("c4", "Lisbon", "capital"),
        fact("c5", "Vienna", "capital"),
        fact("c6", "Oslo", "capital"),
    )

    private val elements = listOf(
        fact("e1", "Tungsten", "element", Category.SCIENCE),
        fact("e2", "Helium", "element", Category.SCIENCE),
        fact("e3", "Carbon", "element", Category.SCIENCE),
        fact("e4", "Iron", "element", Category.SCIENCE),
    )

    private val pool = capitals + elements

    @Test
    fun `every question has four distinct options containing the right answer`() {
        val questions = QuizGenerator.generate(capitals, pool, count = 6, random = Random(7))

        assertEquals(6, questions.size)
        questions.forEach { question ->
            assertEquals(4, question.options.size)
            assertEquals(4, question.options.map { it.lowercase() }.toSet().size)
            assertTrue(question.correctIndex in question.options.indices)
            val subject = pool.first { it.id == question.factId }
            assertEquals(subject.answer, question.correctAnswer)
        }
    }

    @Test
    fun `distractors are drawn from the same answer type`() {
        val questions = QuizGenerator.generate(capitals, pool, count = 6, random = Random(11))
        val elementAnswers = elements.map { it.answer }.toSet()

        questions.forEach { question ->
            val leaked = question.options.filter { it in elementAnswers }
            assertTrue(leaked.isEmpty(), "capital question offered elements: $leaked")
        }
    }

    @Test
    fun `generation is deterministic for a given seed`() {
        val first = QuizGenerator.generate(capitals, pool, count = 5, random = Random(42))
        val second = QuizGenerator.generate(capitals, pool, count = 5, random = Random(42))
        assertEquals(first, second)
    }

    @Test
    fun `the requested question count is an upper bound, never exceeded`() {
        val questions = QuizGenerator.generate(capitals, pool, count = 3, random = Random(1))
        assertEquals(3, questions.size)

        val everything = QuizGenerator.generate(capitals, pool, count = 99, random = Random(1))
        assertEquals(capitals.size, everything.size)
    }

    @Test
    fun `a thin answer type falls back to wider rings rather than dropping the question`() {
        val lonely = fact("solo", "Ulaanbaatar", "obscure-capital")
        val questions = QuizGenerator.generate(listOf(lonely), pool + lonely, count = 1, random = Random(3))

        assertEquals(1, questions.size)
        assertEquals(4, questions.single().options.size)
        assertEquals("Ulaanbaatar", questions.single().correctAnswer)
    }

    @Test
    fun `a question is skipped when there is nothing to contrast it with`() {
        val only = fact("only", "Paris", "capital")
        assertTrue(QuizGenerator.generate(listOf(only), listOf(only), count = 1).isEmpty())
    }

    @Test
    fun `duplicate answers across facts never appear twice in one question`() {
        val duplicated = pool + fact("c7", "paris", "capital")
        val questions = QuizGenerator.generate(capitals, duplicated, count = 6, random = Random(5))

        questions.forEach { question ->
            val normalized = question.options.map { it.trim().lowercase() }
            assertEquals(normalized.size, normalized.toSet().size, "duplicate option in $normalized")
        }
    }

    @Test
    fun `pool generation can be narrowed to a single category`() {
        val questions = QuizGenerator.generateFromPool(
            pool = pool,
            count = 10,
            category = Category.SCIENCE,
            random = Random(9),
        )
        assertTrue(questions.isNotEmpty())
        assertTrue(questions.all { it.category == Category.SCIENCE })
    }
}
