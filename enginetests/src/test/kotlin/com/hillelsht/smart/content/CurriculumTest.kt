package com.hillelsht.smart.content

import com.hillelsht.smart.data.seed.ContentParser
import com.hillelsht.smart.domain.QuizGenerator
import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.domain.model.Fact
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Validates the shipped curriculum through the same parser the app uses on first launch.
 *
 * A broken or low-quality fact should fail the build here rather than surface as a confusing
 * question — or a crash during seeding — on someone's phone.
 */
class CurriculumTest {

    private val files = Category.entries.associateWith { "/content/${it.id}.json" }

    private fun load(category: Category): List<Fact> {
        val path = files.getValue(category)
        val raw = javaClass.getResource(path)?.readText()
            ?: fail("Missing curriculum file $path")
        return ContentParser.parseFile(raw, path)
    }

    private val corpus: List<Fact> by lazy { Category.entries.flatMap(::load) }

    @Test
    fun `every category file parses and belongs to its category`() {
        Category.entries.forEach { category ->
            val facts = load(category)
            assertTrue(facts.isNotEmpty(), "${category.id}.json has no facts")
            assertTrue(
                facts.all { it.category == category },
                "${category.id}.json contains facts from another category",
            )
        }
    }

    @Test
    fun `the curriculum ships at least 500 facts across all six categories`() {
        assertEquals(6, Category.entries.size)
        assertTrue(
            corpus.size >= 500,
            "expected at least 500 facts, found ${corpus.size}",
        )
        println("Curriculum: ${corpus.size} facts")
        Category.entries.forEach { category ->
            val count = corpus.count { it.category == category }
            println("  ${category.displayName.padEnd(20)} $count")
            assertTrue(count >= 60, "${category.displayName} has only $count facts")
        }
    }

    @Test
    fun `fact ids are unique across the whole corpus`() {
        val duplicates = corpus.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertTrue(duplicates.isEmpty(), "duplicate fact ids: $duplicates")
    }

    @Test
    fun `no question is asked twice`() {
        val duplicates = corpus
            .groupBy { it.question.trim().lowercase() }
            .filterValues { it.size > 1 }
            .keys
        assertTrue(duplicates.isEmpty(), "duplicate questions: $duplicates")
    }

    @Test
    fun `every fact carries an image reference and a teaching statement`() {
        val missingImage = corpus.filter { it.wikiTitle.isNullOrBlank() }.map { it.id }
        assertTrue(missingImage.isEmpty(), "facts without a wikiTitle: $missingImage")

        // The statement is what Learn teaches; a statement that just restates the answer
        // teaches nothing, so require it to carry real context.
        val thin = corpus.filter { it.statement.length < 40 }.map { it.id }
        assertTrue(thin.isEmpty(), "facts with a too-thin statement: $thin")
    }

    @Test
    fun `every answer type has enough distinct answers to build a clean quiz`() {
        val needed = QuizGenerator.DEFAULT_OPTION_COUNT
        val thin = corpus
            .groupBy { it.answerType }
            .mapValues { (_, facts) -> facts.map { it.answer.trim().lowercase() }.toSet() }
            .filterValues { it.size < needed }

        assertTrue(
            thin.isEmpty(),
            "answer types with fewer than $needed distinct answers, which forces the quiz to " +
                "borrow mismatched distractors: ${thin.mapValues { it.value.size }}",
        )
    }

    @Test
    fun `a quiz can be generated for every single fact in the corpus`() {
        val questions = QuizGenerator.generate(
            subjects = corpus,
            pool = corpus,
            count = corpus.size,
            random = Random(2026),
        )

        assertEquals(corpus.size, questions.size, "some facts could not be made into questions")

        questions.forEach { question ->
            assertEquals(4, question.options.size, "${question.factId} has ${question.options.size} options")
            val normalized = question.options.map { it.trim().lowercase() }
            assertEquals(normalized.size, normalized.toSet().size, "${question.factId} has duplicate options")

            val subject = corpus.first { it.id == question.factId }
            assertEquals(subject.answer, question.correctAnswer)
        }
    }

    @Test
    fun `quiz distractors come from the same answer type as the question`() {
        val questions = QuizGenerator.generate(corpus, corpus, corpus.size, random = Random(99))
        val answersByType = corpus
            .groupBy { it.answerType }
            .mapValues { (_, facts) -> facts.map { it.answer.trim().lowercase() }.toSet() }

        var mismatches = 0
        questions.forEach { question ->
            val subject = corpus.first { it.id == question.factId }
            val sameType = answersByType.getValue(subject.answerType)
            question.options.forEach { option ->
                if (option.trim().lowercase() !in sameType) mismatches++
            }
        }

        assertEquals(0, mismatches, "$mismatches options were drawn from outside the answer type")
    }

    @Test
    fun `difficulty is spread rather than all set to the same value`() {
        val spread = corpus.groupingBy { it.difficulty }.eachCount()
        assertTrue(spread.size >= 2, "every fact has the same difficulty: $spread")
        println("Difficulty spread: $spread")
    }
}
