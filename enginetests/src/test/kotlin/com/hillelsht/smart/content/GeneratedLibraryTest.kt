package com.hillelsht.smart.content

import com.hillelsht.smart.data.seed.ContentParser
import com.hillelsht.smart.domain.LibraryShard
import com.hillelsht.smart.domain.LibraryTopUp
import com.hillelsht.smart.domain.PackInstall
import com.hillelsht.smart.domain.QuizGenerator
import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.domain.model.Fact
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs the app's real parser over the library CI actually published.
 *
 * `tools/generate_facts.py` writes these files from a machine that can reach Wikidata, and the
 * app reads them on a phone. Nothing in between had ever checked that the two agree — a
 * renamed field or a missing category header would have shown up as a Library that silently
 * stopped growing, with `ContentSeeder` logging "corrupt downloaded pack" where nobody looks.
 *
 * These read the committed files off disk rather than a fixture, so they fail the moment a
 * generated shard stops being something the device can install.
 */
class GeneratedLibraryTest {

    private val libraryDir = File("../packs/library")

    private val shardFiles: List<File>
        get() = libraryDir.listFiles { f -> f.name.endsWith(".json") && f.name != "index.json" }
            ?.sortedBy { it.name }
            .orEmpty()

    /** The index without a JSON library: the fields the device reads, pulled out by hand. */
    private data class IndexEntry(
        val id: String,
        val category: String,
        val file: String,
        val facts: Int,
        val shard: Int,
        val version: String,
    )

    private fun index(): List<IndexEntry> {
        val raw = File(libraryDir, "index.json").readText()
        // Deliberately not a serializer: this asserts on the literal bytes CI committed, so a
        // lenient decoder cannot paper over a field that went missing.
        return Regex("""\{[^{}]*"file"[^{}]*}""").findAll(raw).map { match ->
            fun str(key: String) =
                Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(match.value)?.groupValues?.get(1)
                    ?: error("index entry has no $key: ${match.value}")

            fun int(key: String) =
                Regex("\"$key\"\\s*:\\s*(\\d+)").find(match.value)?.groupValues?.get(1)?.toInt()
                    ?: error("index entry has no $key: ${match.value}")

            IndexEntry(str("id"), str("category"), str("file"), int("facts"), int("shard"),
                str("version"))
        }.toList()
    }

    private fun parsedPacks(): List<ContentParser.ParsedPack> =
        shardFiles.map { ContentParser.parsePack(it.readText(), it.name) }

    // --- the contract between the generator and the device ------------------------------------

    @Test
    fun `every published shard parses with the parser the device uses`() {
        val packs = parsedPacks()
        assertTrue(packs.isNotEmpty(), "no library shards are published")
        packs.forEach { pack ->
            assertTrue(pack.facts.isNotEmpty(), "${pack.packId} parsed to no facts")
            assertTrue(pack.version.isNotBlank(), "${pack.packId} has no version to re-seed by")
        }
    }

    @Test
    fun `the index describes the shards that are actually there`() {
        val entries = index()
        val byId = parsedPacks().associateBy { it.packId }

        assertEquals(byId.size, entries.size, "index and shard files disagree on how many exist")
        entries.forEach { entry ->
            val pack = byId[entry.id] ?: error("index lists ${entry.id}, which has no shard file")
            assertEquals(entry.facts, pack.facts.size, "${entry.id} fact count is wrong")
            assertEquals(entry.version, pack.version, "${entry.id} version is wrong")
            assertEquals(entry.category, pack.category.id, "${entry.id} category is wrong")
            assertTrue(
                File(libraryDir, entry.file.removePrefix("library/")).exists(),
                "${entry.id} points at ${entry.file}, which is not there",
            )
        }
    }

    @Test
    fun `the index paths are relative to packs, which is where the app looks`() {
        // PackService appends `file` to .../packs/ — a leading slash or a bare filename here
        // is a 404 on every device, and nothing in the app would say so.
        index().forEach { entry ->
            assertTrue(
                entry.file.startsWith("library/") && !entry.file.startsWith("/"),
                "${entry.id} has file '${entry.file}', which will not resolve",
            )
        }
    }

    @Test
    fun `every category in the index is one the app knows`() {
        index().forEach { entry ->
            assertNotNull(
                Category.fromId(entry.category),
                "${entry.id} declares category '${entry.category}', which the app would reject",
            )
        }
    }

    @Test
    fun `every shard is named so the seeder installs it additively`() {
        // PackInstall recognises a generated shard by its id, and gets it wrong the shard is
        // cleared and reinstalled on every regeneration — deleting facts the learner studied.
        // The generator's naming and the seeder's rule have to agree, so they are checked here
        // against each other rather than each being trusted separately.
        parsedPacks().forEach { pack ->
            assertTrue(
                PackInstall.isGeneratedLibrary(pack.packId),
                "${pack.packId} would be treated as a curated pack and cleared on every run",
            )
        }
    }

    @Test
    fun `fact ids are unique across the whole library`() {
        // Shards install independently and facts are keyed by id, so a collision across two
        // shards would have them overwrite each other's review history.
        val ids = parsedPacks().flatMap { pack -> pack.facts.map { it.id } }
        assertEquals(ids.size, ids.toSet().size, "the library repeats a fact id")
    }

    @Test
    fun `no generated fact collides with the bundled curriculum`() {
        val bundled = File("../app/src/main/assets/packs")
            .listFiles { f -> f.name.endsWith(".json") && f.name !in setOf("channels.json", "durations.json") }
            .orEmpty()
            .flatMap { ContentParser.parsePack(it.readText(), it.name).facts }
            .map { it.id }
            .toSet()
        val generated = parsedPacks().flatMap { pack -> pack.facts.map { it.id } }.toSet()
        assertEquals(emptySet(), bundled intersect generated)
    }

    // --- the facts have to be usable, not merely parseable -------------------------------------

    @Test
    fun `every answer type can furnish a quiz`() {
        // QuizGenerator draws distractors from facts sharing an answerType. A type with three
        // answers in it offers the same three every time; one with a single answer cannot make
        // a question at all. The generator prunes these, and this is what proves it did.
        val byType = allFacts().groupBy { it.answerType }
        byType.forEach { (type, facts) ->
            val distinct = facts.map { it.answer }.distinct().size
            assertTrue(distinct >= 4, "answerType '$type' has only $distinct distinct answers")
        }
    }

    @Test
    fun `the quiz can actually build questions from the generated corpus`() {
        val facts = allFacts()
        val questions = QuizGenerator.generate(
            subjects = facts.shuffled(Random(7)),
            pool = facts,
            count = 40,
            random = Random(7),
        )
        assertTrue(questions.isNotEmpty(), "no question could be built from the whole library")
        val byId = facts.associateBy { it.id }
        questions.forEach { question ->
            assertTrue(question.options.size >= 2, "a question offered ${question.options.size} options")
            assertEquals(
                byId.getValue(question.factId).answer,
                question.correctAnswer,
                "${question.factId} is marked correct on the wrong option",
            )
            assertEquals(
                question.options.size,
                question.options.distinct().size,
                "an option is offered twice",
            )
        }
    }

    @Test
    fun `a generated fact reads like something worth learning`() {
        allFacts().forEach { fact ->
            assertTrue(fact.title.isNotBlank(), "${fact.id} has no title")
            assertTrue(fact.question.endsWith("?"), "${fact.id} asks '${fact.question}'")
            assertTrue(fact.statement.endsWith("."), "${fact.id} states '${fact.statement}'")
            assertTrue(fact.difficulty in 1..3, "${fact.id} has difficulty ${fact.difficulty}")
            // An unresolved Wikidata label reaching a phone reads as "Who painted Q12345?".
            assertTrue(
                !Regex("\\bQ\\d{3,}\\b").containsMatchIn(fact.question + fact.answer),
                "${fact.id} carries a raw Q-number: ${fact.question} -> ${fact.answer}",
            )
        }
    }

    @Test
    fun `most of the library carries the extras that make Learn worth opening`() {
        val facts = allFacts()
        val withDetails = facts.count { !it.details.isNullOrBlank() }
        val withImages = facts.count { !it.imageUrl.isNullOrBlank() }
        assertTrue(
            withDetails > facts.size * 0.9,
            "only $withDetails of ${facts.size} facts have a passage to read",
        )
        assertTrue(
            withImages > facts.size / 2,
            "only $withImages of ${facts.size} facts have a picture",
        )
    }

    // --- the device's top-up rule against the real index ---------------------------------------

    @Test
    fun `a fresh install is topped up straight away`() {
        // The bug this exists for: the threshold was 15 unseen facts per category, while a
        // fresh install holds 70 to 95 of them and gets through about two a day. The first
        // shard would have landed on day 27, so the app showed 517 facts and no sign of growth
        // for a month — indistinguishable from the feature not working at all.
        val bundled = File("../app/src/main/assets/packs")
            .listFiles { f -> f.name.endsWith(".json") && f.name !in setOf("channels.json", "durations.json") }
            .orEmpty()
            .map { ContentParser.parsePack(it.readText(), it.name) }
            .associate { it.category.id to it.facts.size }

        assertTrue(bundled.isNotEmpty(), "no bundled packs to measure against")
        val shards = index().map { LibraryShard(it.id, it.category, it.shard) }
        val wanted = LibraryTopUp.next(shards, installed = emptySet(), unseenByCategory = bundled)

        assertTrue(
            wanted.isNotEmpty(),
            "a brand-new install with $bundled unseen would be offered nothing to grow into",
        )
    }

    @Test
    fun `the buffer fills within the first few screens`() {
        // Today and Library each check on open, so this is a handful of navigations, not a
        // month. Anything much longer and the learner reasonably concludes it is broken.
        val bundled = File("../app/src/main/assets/packs")
            .listFiles { f -> f.name.endsWith(".json") && f.name !in setOf("channels.json", "durations.json") }
            .orEmpty()
            .map { ContentParser.parsePack(it.readText(), it.name) }
            .associate { it.category.id to it.facts.size }

        val shards = index().map { LibraryShard(it.id, it.category, it.shard) }
        val installed = mutableSetOf<String>()
        val unseen = bundled.toMutableMap()
        var checks = 0

        while (checks < 20) {
            val wanted = LibraryTopUp.next(shards, installed, unseen)
            if (wanted.isEmpty()) break
            checks++
            wanted.forEach { shard ->
                installed += shard.packId
                val facts = index().first { it.id == shard.packId }.facts
                unseen[shard.categoryId] = (unseen[shard.categoryId] ?: 0) + facts
            }
        }

        assertTrue(checks in 1..4, "the buffer took $checks checks to fill")
        assertTrue(
            unseen.values.all { it >= LibraryTopUp.MIN_UNSEEN_PER_CATEGORY } ||
                LibraryTopUp.exhausted(shards, installed),
            "some category settled below the buffer with shards still available: $unseen",
        )
    }

    @Test
    fun `the top-up rule can consume the published index`() {
        val shards = index().map { LibraryShard(it.id, it.category, it.shard) }
        val empty = shards.map { it.categoryId }.distinct().associateWith { 0 }

        val first = LibraryTopUp.next(shards, installed = emptySet(), unseenByCategory = empty)
        assertTrue(first.isNotEmpty(), "a device with nothing learned would be offered nothing")
        assertTrue(first.all { it.order == 0 }, "the first top-up should take the top shard")

        // Walk the whole library down, one round at a time, and confirm it terminates.
        val installed = mutableSetOf<String>()
        var rounds = 0
        while (!LibraryTopUp.exhausted(shards, installed) && rounds < 1_000) {
            val next = LibraryTopUp.next(shards, installed, empty)
            if (next.isEmpty()) break
            installed += next.map { it.packId }
            rounds++
        }
        assertTrue(LibraryTopUp.exhausted(shards, installed), "the library never runs out cleanly")
        assertEquals(shards.size, installed.size, "some shard was never reachable")
    }

    @Test
    fun `shard order within a category is dense and starts at zero`() {
        // LibraryTopUp takes the lowest uninstalled order in a category. A gap would be skipped
        // over silently and those facts would never be delivered.
        index().groupBy { it.category }.forEach { (category, entries) ->
            val orders = entries.map { it.shard }.sorted()
            assertEquals((0 until entries.size).toList(), orders, "$category shards are not 0..n")
        }
    }

    @Test
    fun `the most important facts are in the first shard`() {
        // Shard 0 is what a learner meets first, so it should hold the famous ones.
        index().groupBy { it.category }
            .filterValues { it.size > 1 }
            .forEach { (category, entries) ->
                fun hardestIn(shard: Int): Double {
                    val entry = entries.first { it.shard == shard }
                    val facts = ContentParser
                        .parsePack(File(libraryDir, entry.file.removePrefix("library/")).readText(), entry.file)
                        .facts
                    return facts.map { it.difficulty }.average()
                }
                assertTrue(
                    hardestIn(0) <= hardestIn(entries.size - 1),
                    "$category opens with harder facts than it ends with",
                )
            }
    }

    private fun allFacts(): List<Fact> = parsedPacks().flatMap { it.facts }
}
