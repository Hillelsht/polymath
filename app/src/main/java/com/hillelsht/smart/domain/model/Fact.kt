package com.hillelsht.smart.domain.model

/**
 * One atomic piece of knowledge.
 *
 * A fact is deliberately taught in two directions: [statement] is the teaching form shown in
 * Learn, and [question]/[answer] is the recall form used by Review and Quiz. Authoring both
 * means the app never has to auto-generate a question out of a sentence, which is where most
 * flashcard content turns clumsy.
 *
 * [answerType] is what makes the quiz feel handmade: distractors are drawn only from other
 * answers of the same type, so a question about a capital city never offers a chemical element
 * as one of its four choices.
 */
data class Fact(
    val id: String,
    val category: Category,
    val title: String,
    val statement: String,
    val question: String,
    val answer: String,
    val answerType: String,
    val hook: String? = null,
    val wikiTitle: String? = null,
    val difficulty: Int = 1,
    /** A longer teaching passage (up to ~10 sentences), pulled from Wikipedia by the pipeline. */
    val details: String? = null,
    /** Direct thumbnail URL on upload.wikimedia.org, resolved at build time. */
    val imageUrl: String? = null,
    /** Canonical Wikipedia article URL for "Read more". */
    val pageUrl: String? = null,
    /** The content pack this fact arrived in; the seeder replaces facts per pack. */
    val packId: String = "",
    /**
     * The language this fact teaches in. A Russian fact is a distinct row with its own id and
     * its own spaced-repetition history — never the same id as its English counterpart — so a
     * language switch cannot suddenly change what a fact already in review says.
     */
    val language: Language = Language.default,
) {
    init {
        require(id.isNotBlank()) { "Fact id must not be blank" }
        require(difficulty in 1..3) { "Fact $id has difficulty $difficulty, expected 1..3" }
    }
}
