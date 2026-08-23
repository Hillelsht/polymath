package com.hillelsht.smart.content

import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.domain.play.chains.ChainsGroup
import com.hillelsht.smart.domain.play.chains.ChainsPuzzle
import com.hillelsht.smart.domain.play.chains.ChainsRules
import com.hillelsht.smart.domain.play.climb.RelicEffect
import com.hillelsht.smart.domain.play.climb.Relics
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the game content CI publishes against the rules the device will apply to it.
 *
 * `tools/build_chains.py` writes these grids and `ClimbRules` reads that roster, and neither
 * knows anything about the other. A grid that breaks its own rules or a relic naming an effect
 * that does not exist would surface on a phone as a puzzle that cannot be solved or a cache
 * with nothing in it — both silent, both worse than a red build.
 */
class PlayContentTest {

    private val playDir = File("../packs/play")

    // The published JSON is read with regexes rather than a serializer on purpose: this is
    // asserting on the literal bytes committed, and a lenient decoder would paper over exactly
    // the kind of missing field these tests exist to catch.
    private fun textBetween(source: String, key: String): List<String> =
        Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(source)
            .map { it.groupValues[1] }.toList()

    // --- the relic roster ----------------------------------------------------------------

    @Test
    fun `every published relic is one this build can actually grant`() {
        val raw = File(playDir, "climb.json").readText()
        val entries = Regex("\\{[^{}]*\"effect\"[^{}]*}").findAll(raw).map { it.value }.toList()
        assertTrue(entries.isNotEmpty(), "no relics are published")

        entries.forEach { entry ->
            val effect = textBetween(entry, "effect").single()
            assertTrue(
                RelicEffect.entries.any { it.name == effect },
                "relic effect '$effect' does not exist, so the relic would be silently dropped",
            )
        }
    }

    @Test
    fun `a mastery relic names a category the app has`() {
        val raw = File(playDir, "climb.json").readText()
        Regex("\\{[^{}]*\"CATEGORY_MASTERY\"[^{}]*}").findAll(raw).forEach { match ->
            val categoryId = textBetween(match.value, "categoryId").singleOrNull()
            assertTrue(categoryId != null, "a mastery relic names no category and can never fire")
            assertTrue(
                Category.fromId(categoryId!!) != null,
                "mastery relic names category '$categoryId', which the app would never match",
            )
        }
    }

    @Test
    fun `the roster has more relics than a cache offers`() {
        // A cache offers three. With fewer than that in the roster, every cache is the same
        // cache and the choice is not a choice.
        val raw = File(playDir, "climb.json").readText()
        val ids = textBetween(raw, "id")
        assertTrue(ids.size > Relics.FALLBACK.size, "the published roster adds nothing")
        assertEquals(ids.size, ids.toSet().size, "two relics share an id")
    }

    // --- the daily grids ------------------------------------------------------------------

    /**
     * Every language that has published grids: `en` at the root, the rest one folder down.
     *
     * The daily exists once per language because its content is text — `build_chains.py` builds a
     * grid per language from that language's own library. An earlier version of this test read
     * only the root, so Russian and Hebrew grids went to devices having passed nothing: the
     * overlap rule, the one that decides whether a puzzle has a single right answer, was checked
     * in English and taken on trust everywhere else.
     */
    private fun publishedLanguages(): List<Pair<String, File>> {
        val root = File(playDir, "chains")
        val translated = root.listFiles { f -> f.isDirectory }.orEmpty()
            .sortedBy { it.name }
            .map { it.name to it }
        return listOf("en" to root) + translated
    }

    private fun publishedPuzzles(language: String = "en"): List<ChainsPuzzle> {
        val dir = publishedLanguages().firstOrNull { it.first == language }?.second
            ?: return emptyList()
        val months = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
        return months.flatMap { file ->
            val raw = file.readText()
            // One object per puzzle: date, tiles, then a nested groups array.
            Regex("\\{\"date\":\"([^\"]+)\",\"tiles\":\\[([^\\]]*)],\"groups\":\\[(.*?)]}")
                .findAll(raw)
                .map { match ->
                    val (date, tilesRaw, groupsRaw) = match.destructured
                    ChainsPuzzle(
                        date = date,
                        tiles = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(tilesRaw)
                            .map { it.groupValues[1] }.toList(),
                        groups = Regex("\\{[^{}]*\"members\":\\[([^\\]]*)][^{}]*}")
                            .findAll(groupsRaw)
                            .mapIndexed { index, group ->
                                ChainsGroup(
                                    id = textBetween(group.value, "id").firstOrNull() ?: "g$index",
                                    label = textBetween(group.value, "label").firstOrNull().orEmpty(),
                                    members = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
                                        .findAll(group.groupValues[1])
                                        .map { it.groupValues[1] }.toList(),
                                    difficulty = Regex("\"difficulty\":(\\d+)")
                                        .find(group.value)?.groupValues?.get(1)?.toInt() ?: 1,
                                )
                            }.toList(),
                    )
                }.toList()
        }
    }

    @Test
    fun `grids are published at all`() {
        publishedLanguages().forEach { (language, _) ->
            val puzzles = publishedPuzzles(language)
            assertTrue(
                puzzles.size >= 28,
                "only ${puzzles.size} $language grids published, under a month",
            )
            assertEquals(
                puzzles.size, puzzles.map { it.date }.toSet().size,
                "two $language grids share a date",
            )
            println("  $language: ${puzzles.size} grids")
        }
    }

    @Test
    fun `every published grid is one the device would accept`() {
        // The device refuses a grid whose problems() is non-empty and shows nothing that day.
        // A grid failing here is a day with no puzzle for everyone who has the app.
        publishedLanguages().forEach { (language, _) ->
            publishedPuzzles(language).forEach { puzzle ->
                assertEquals(
                    emptyList(), puzzle.problems(),
                    "$language grid ${puzzle.date} would be refused",
                )
            }
        }
    }

    /**
     * A translated grid must actually be in its language.
     *
     * The failure this catches is the quiet one: a build that reads the English library while
     * writing to `chains/ru/` produces a file that passes every structural check above and serves
     * English tiles to Russian players. `build_chains.py` had a Latin-only tile filter when the
     * translated builds were added, which would have produced the opposite failure — no grids at
     * all — but "wrong language, right shape" is the one nothing else would notice.
     */
    @Test
    fun `a translated grid is written in its own script`() {
        val scripts = mapOf(
            "ru" to Regex("[\\u0400-\\u04FF]"),
            "he" to Regex("[\\u0590-\\u05FF]"),
        )
        publishedLanguages().forEach { (language, _) ->
            val script = scripts[language] ?: return@forEach
            publishedPuzzles(language).forEach { puzzle ->
                val text = (puzzle.tiles + puzzle.groups.map { it.label }).joinToString(" ")
                assertTrue(
                    script.containsMatchIn(text),
                    "$language grid ${puzzle.date} contains no $language at all — " +
                        "it was almost certainly built from the English library",
                )
            }
        }
    }

    /**
     * Two languages are two different puzzles on the same day, and the share text says so.
     *
     * Asserted rather than assumed because the alternative — one grid translated — is what
     * everybody pictures, and it is not what this builds: the three corpora do not hold the same
     * facts, so a shared grid would reduce every language to what the thinnest supports.
     */
    @Test
    fun `each language's grid for a day is its own puzzle`() {
        val english = publishedPuzzles("en").associateBy { it.date }
        publishedLanguages().filter { it.first != "en" }.forEach { (language, _) ->
            publishedPuzzles(language).forEach { puzzle ->
                val sameDay = english[puzzle.date] ?: return@forEach
                assertTrue(
                    puzzle.tiles.toSet() != sameDay.tiles.toSet(),
                    "$language grid ${puzzle.date} has the same tiles as the English one",
                )
            }
        }
    }

    @Test
    fun `no tile in any grid belongs to two groups`() {
        // Stated separately from the check above because it is the one that matters: a solver
        // who spots the wrong-but-defensible grouping would be told they are wrong by a puzzle
        // that is itself wrong.
        publishedLanguages().flatMap { publishedPuzzles(it.first) }.forEach { puzzle ->
            val members = puzzle.groups.flatMap { it.members }
            assertEquals(
                members.size, members.toSet().size,
                "grid ${puzzle.date} has a tile in two groups",
            )
        }
    }

    @Test
    fun `every published grid can be solved`() {
        // Walks each grid the way a perfect solver would and requires it to end in a win. A
        // grid that cannot be won by submitting its own answer is unplayable by anyone.
        publishedPuzzles().forEach { puzzle ->
            var state = ChainsRules.start(puzzle)
            puzzle.groups.forEach { group ->
                state = ChainsRules.submit(
                    group.members.fold(state) { acc, tile -> ChainsRules.select(acc, tile) },
                )
            }
            assertTrue(state.won, "grid ${puzzle.date} could not be solved with its own answer")
            assertEquals(0, state.mistakes, "solving grid ${puzzle.date} correctly cost a mistake")
        }
    }

    @Test
    fun `each grid reveals in a sensible order`() {
        publishedPuzzles().forEach { puzzle ->
            assertEquals(
                (1..ChainsPuzzle.GROUP_COUNT).toList(),
                puzzle.groups.map { it.difficulty }.sorted(),
                "grid ${puzzle.date} does not have one group at each difficulty",
            )
        }
    }

    @Test
    fun `tiles are short enough to read on a phone`() {
        // A tile is a quarter of the screen. This is the one property of a grid that cannot be
        // checked by any rule in the domain layer and would only show up as clipped text.
        publishedPuzzles().forEach { puzzle ->
            puzzle.tiles.forEach { tile ->
                assertTrue(tile.length <= 18, "grid ${puzzle.date} has a ${tile.length}-char tile: $tile")
                assertTrue(tile.isNotBlank(), "grid ${puzzle.date} has a blank tile")
            }
        }
    }

    @Test
    fun `a group's label never gives its own members away`() {
        // "Rivers empty into these" is a fair clue. A label containing one of its own answers
        // is not a puzzle.
        //
        // Matched on word boundaries rather than as a substring: the first version of this
        // check failed on "Chemical symbols" for containing the tile "Al", which it does, in
        // the middle of "Chemical". Two-letter element symbols make substring matching useless
        // here.
        publishedPuzzles().forEach { puzzle ->
            puzzle.groups.forEach { group ->
                group.members.forEach { member ->
                    val whole = Regex("\\b${Regex.escape(member)}\\b", RegexOption.IGNORE_CASE)
                    assertTrue(
                        !whole.containsMatchIn(group.label),
                        "grid ${puzzle.date}: label '${group.label}' gives away its answer '$member'",
                    )
                }
            }
        }
    }
}
