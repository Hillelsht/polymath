package com.hillelsht.smart.data.seed

import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.domain.model.Fact
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One content file: a category header plus its facts.
 *
 * Two flavours share this shape. Authoring files under `content/` carry only the hand-written
 * fields; enriched packs under `packs/` (produced by the CI pipeline) additionally carry
 * [SeedFact.details], [SeedFact.imageUrl] and [SeedFact.pageUrl], plus [packId] and [version]
 * so the seeder can tell whether an installed pack is stale.
 */
@Serializable
data class SeedFile(
    @SerialName("category") val categoryId: String,
    val facts: List<SeedFact>,
    val packId: String? = null,
    val version: String? = null,
    val name: String? = null,
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
    // Enrichment fields, filled in by the CI pipeline rather than by hand.
    val details: String? = null,
    val imageUrl: String? = null,
    val pageUrl: String? = null,
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

    /** A parsed content file with its pack identity resolved. */
    data class ParsedPack(
        val packId: String,
        val version: String,
        val category: Category,
        val facts: List<Fact>,
    )

    /** @param source a filename or similar label, used only to make errors legible. */
    fun parseFile(raw: String, source: String): List<Fact> = parsePack(raw, source).facts

    /**
     * Parses a content file into a pack. Authoring files carry no pack metadata, so identity
     * falls back to the category id and the version to a hash of the raw text — good enough
     * to detect that bundled content changed between app versions.
     */
    fun parsePack(raw: String, source: String): ParsedPack {
        val file = try {
            json.decodeFromString<SeedFile>(raw)
        } catch (e: Exception) {
            throw ContentException("$source is not valid content JSON: ${e.message}")
        }

        val category = Category.fromId(file.categoryId)
            ?: throw ContentException("$source declares unknown category '${file.categoryId}'")

        val packId = file.packId ?: category.id
        return ParsedPack(
            packId = packId,
            version = file.version ?: "text-${raw.hashCode()}",
            category = category,
            facts = file.facts.map { seed -> seed.toFact(category, packId, source) },
        )
    }

    private fun SeedFact.toFact(category: Category, packId: String, source: String): Fact {
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
            details = details?.takeIf { it.isNotBlank() },
            imageUrl = imageUrl?.takeIf { it.isNotBlank() },
            pageUrl = pageUrl?.takeIf { it.isNotBlank() },
            packId = packId,
        )
    }
}
