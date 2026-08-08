package com.hillelsht.smart.domain.model

/**
 * A single multiple-choice question, fully resolved: the options are already shuffled and
 * [correctIndex] points into them, so the UI never has to know how the answer was chosen.
 */
data class QuizQuestion(
    val factId: String,
    val category: Category,
    val prompt: String,
    val options: List<String>,
    val correctIndex: Int,
    val hook: String? = null,
    val wikiTitle: String? = null,
    val imageUrl: String? = null,
) {
    val correctAnswer: String get() = options[correctIndex]

    init {
        require(options.size >= 2) { "Question for $factId has ${options.size} options" }
        require(correctIndex in options.indices) { "correctIndex out of range for $factId" }
    }
}

/** One graded answer, used to score a run and to feed misses back into the review queue. */
data class QuizAnswer(
    val factId: String,
    val selectedIndex: Int,
    val correct: Boolean,
)
