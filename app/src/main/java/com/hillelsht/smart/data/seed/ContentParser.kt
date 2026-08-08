package com.hillelsht.smart.data.seed

import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.domain.model.Fact
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One JSON file under `assets/content`: a category header plus its facts. */
@Serializable
data class SeedFile(
    @SerialName("category") val categoryId: String,
    val facts: List<SeedFact>,
)

/**
 * The authoring format. Deliberately flat and human-editable — adding knowledge to this app
 * should never require touching Kotlin.
 */
@Serializable
data class SeedFact(
    val id: String,
    val title: String,
    val statement: String,
    val question: String,
    val answer: String,
    val answerType: String,
    val hook: String? = null,
    val wikiTitle: String? = null,
    val difficulty: Int = 1,
)

/**
 * Parses the bundled curriculum.
 *
 * Kept free of Android types so the exact code path that runs on device is also the one the
 * content-validation tests exercise on the JVM — a malformed fact fails the build, not the
 * first launch.
 */
object ContentParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    class ContentException(message: String) : IllegalArgumentException(message)

    /** @param source a filename or similar label, used only to make errors legible. */
    fun parseFile(raw: String, source: String): List<Fact> {
        val file = try {
            json.decodeFromString<SeedFile>(raw)
        } catch (e: Exception) {
            throw ContentException("$source is not valid content JSON: ${e.message}")
        }

        val category = Category.fromId(file.categoryId)
            ?: throw ContentException("$source declares unknown category '${file.categoryId}'")

        return file.facts.map { seed -> seed.toFact(category, source) }
    }

    private fun SeedFact.toFact(category: Category, source: String): Fact {
        fun require(condition: Boolean, message: String) {
            if (!condition) throw ContentException("$source: fact '$id' $message")
        }

        require(id.isNotBlank(), "has a blank id")
        require(title.isNotBlank(), "has a blank title")
        require(statement.isNotBlank(), "has a blank statement")
        require(question.isNotBlank(), "has a blank question")
        require(answer.isNotBlank(), "has a blank answer")
        require(answerType.isNotBlank(), "has a blank answerType")
        require(difficulty in 1..3, "has difficulty $difficulty, expected 1..3")

        return Fact(
            id = id,
            category = category,
            title = title,
            statement = statement,
            question = question,
            answer = answer,
            answerType = answerType,
            hook = hook?.takeIf { it.isNotBlank() },
            wikiTitle = wikiTitle?.takeIf { it.isNotBlank() },
            difficulty = difficulty,
        )
    }
}
