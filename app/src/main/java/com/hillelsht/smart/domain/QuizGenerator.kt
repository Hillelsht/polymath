package com.hillelsht.smart.domain

import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.domain.model.Fact
import com.hillelsht.smart.domain.model.QuizQuestion
import kotlin.random.Random

/**
 * Builds multiple-choice questions that feel authored rather than generated.
 *
 * The trick is distractor selection. Pulling three random answers from the whole corpus
 * produces giveaway questions ("Which river flows through Cairo?" — Nile, Beethoven, 1969,
 * Tungsten). So candidates are drawn in widening rings:
 *  1. other facts sharing the same [Fact.answerType] — a capital against other capitals,
 *  2. then the same [Category],
 *  3. then anything, as a last resort for tiny corpora.
 *
 * Comparison is case- and whitespace-insensitive so "Mount Everest" can never appear twice.
 */
object QuizGenerator {

    const val DEFAULT_OPTION_COUNT = 4

    /**
     * @param subjects the facts to be asked about, in priority order.
     * @param pool every fact available to draw distractors from (normally the whole corpus).
     * @param count how many questions to build.
     * @param random injected so tests are deterministic.
     */
    fun generate(
        subjects: List<Fact>,
        pool: List<Fact>,
        count: Int,
        optionCount: Int = DEFAULT_OPTION_COUNT,
        random: Random = Random.Default,
    ): List<QuizQuestion> {
        if (subjects.isEmpty() || count <= 0) return emptyList()

        val byType = pool.groupBy { it.answerType }
        val byCategory = pool.groupBy { it.category }

        return subjects.asSequence()
            .distinctBy { it.id }
            .take(count)
            .mapNotNull { subject ->
                buildQuestion(subject, byType, byCategory, pool, optionCount, random)
            }
            .toList()
    }

    /** Convenience for the "quiz me on everything" entry point. */
    fun generateFromPool(
        pool: List<Fact>,
        count: Int,
        category: Category? = null,
        random: Random = Random.Default,
    ): List<QuizQuestion> {
        val subjects = pool.filter { category == null || it.category == category }.shuffled(random)
        return generate(subjects, pool, count, DEFAULT_OPTION_COUNT, random)
    }

    private fun buildQuestion(
        subject: Fact,
        byType: Map<String, List<Fact>>,
        byCategory: Map<Category, List<Fact>>,
        pool: List<Fact>,
        optionCount: Int,
        random: Random,
    ): QuizQuestion? {
        val taken = mutableSetOf(subject.answer.normalized())
        val distractors = mutableListOf<String>()

        val rings = listOf(
            byType[subject.answerType].orEmpty(),
            byCategory[subject.category].orEmpty(),
            pool,
        )

        for (ring in rings) {
            if (distractors.size == optionCount - 1) break
            for (candidate in ring.shuffled(random)) {
                if (distractors.size == optionCount - 1) break
                if (candidate.id == subject.id) continue
                val key = candidate.answer.normalized()
                if (!taken.add(key)) continue
                distractors += candidate.answer
            }
        }

        // A question with nothing to choose between is worse than no question at all.
        if (distractors.isEmpty()) return null

        val options = (distractors + subject.answer).shuffled(random)
        return QuizQuestion(
            factId = subject.id,
            category = subject.category,
            prompt = subject.question,
            options = options,
            correctIndex = options.indexOf(subject.answer),
            hook = subject.hook,
            wikiTitle = subject.wikiTitle,
            imageUrl = subject.imageUrl,
        )
    }

    private fun String.normalized(): String = trim().lowercase()
}
