package com.hillelsht.smart.domain.play.chains

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChainsTest {

    private fun group(id: String, vararg members: String, difficulty: Int = 1) =
        ChainsGroup(id, "group $id", members.toList(), difficulty)

    private val capitals = group("capitals", "Paris", "Lisbon", "Oslo", "Sofia", difficulty = 1)
    private val rivers = group("rivers", "Nile", "Amazon", "Danube", "Volga", difficulty = 2)
    private val painters = group("painters", "Goya", "Vermeer", "Rubens", "Turner", difficulty = 3)
    private val elements = group("elements", "Argon", "Radon", "Xenon", "Neon", difficulty = 4)

    private val puzzle = ChainsPuzzle(
        date = "2026-08-11",
        groups = listOf(capitals, rivers, painters, elements),
        tiles = listOf(capitals, rivers, painters, elements).flatMap { it.members }.shuffled(),
    )

    private fun ChainsState.pick(vararg tiles: String) =
        tiles.fold(this) { state, tile -> ChainsRules.select(state, tile) }

    // --- the grid has to be a grid -------------------------------------------------------------

    @Test
    fun `a well-formed grid has nothing to complain about`() {
        assertEquals(emptyList(), puzzle.problems())
    }

    @Test
    fun `a tile in two groups is caught`() {
        // The defining failure of this format: a solver who spots the wrong-but-defensible
        // grouping is told they are wrong by a puzzle that is itself wrong. It cannot be seen by
        // looking at a grid, which is exactly why it is asserted rather than eyeballed.
        val overlapping = ChainsPuzzle(
            date = "2026-08-11",
            groups = listOf(capitals, rivers, painters, group("clash", "Argon", "Radon", "Xenon", "Nile")),
            tiles = (capitals.members + rivers.members + painters.members +
                listOf("Argon", "Radon", "Xenon", "Nile")).distinct(),
        )
        assertTrue(overlapping.problems().any { "more than one group" in it }, overlapping.problems().toString())
    }

    @Test
    fun `a grid of the wrong shape is caught`() {
        assertTrue(puzzle.copy(groups = puzzle.groups.drop(1)).problems().isNotEmpty())
        assertTrue(puzzle.copy(tiles = puzzle.tiles.drop(1)).problems().isNotEmpty())
        assertTrue(
            puzzle.copy(groups = puzzle.groups.map { it.copy(members = it.members.drop(1)) })
                .problems().isNotEmpty(),
        )
    }

    @Test
    fun `tiles that do not match the groups are caught`() {
        val mismatched = puzzle.copy(tiles = puzzle.tiles.dropLast(1) + "Something Else")
        assertTrue(mismatched.problems().any { "same sixteen" in it })
    }

    @Test
    fun `a repeated tile is caught`() {
        val repeated = puzzle.copy(tiles = puzzle.tiles.dropLast(1) + puzzle.tiles.first())
        assertTrue(repeated.problems().any { "repeats a tile" in it })
    }

    // --- playing it ----------------------------------------------------------------------------

    @Test
    fun `finding a group takes it off the board`() {
        val state = ChainsRules.submit(ChainsRules.start(puzzle).pick(*capitals.members.toTypedArray()))
        assertEquals(Verdict.SOLVED, state.verdict)
        assertEquals(listOf(capitals), state.solved)
        assertEquals(12, state.remaining.size)
        assertTrue(state.selected.isEmpty())
        assertEquals(0, state.mistakes)
    }

    @Test
    fun `three of four is called out as one away`() {
        // Without this the format is opaque: a near miss and a wild stab cost the same, so being
        // wrong teaches nothing and the grid stops being solvable by reasoning.
        val state = ChainsRules.submit(
            ChainsRules.start(puzzle).pick("Paris", "Lisbon", "Oslo", "Nile"),
        )
        assertEquals(Verdict.ONE_AWAY, state.verdict)
        assertEquals(1, state.mistakes)
    }

    @Test
    fun `a wild guess is just wrong`() {
        val state = ChainsRules.submit(
            ChainsRules.start(puzzle).pick("Paris", "Lisbon", "Nile", "Goya"),
        )
        assertEquals(Verdict.WRONG, state.verdict)
        assertEquals(1, state.mistakes)
    }

    @Test
    fun `the same wrong guess is not punished twice`() {
        // Being charged again for a combination you have already been told is wrong is not
        // difficulty, it is bookkeeping.
        val once = ChainsRules.submit(ChainsRules.start(puzzle).pick("Paris", "Lisbon", "Nile", "Goya"))
        val twice = ChainsRules.submit(ChainsRules.clear(once).pick("Paris", "Lisbon", "Nile", "Goya"))
        assertEquals(Verdict.ALREADY_TRIED, twice.verdict)
        assertEquals(1, twice.mistakes)
    }

    @Test
    fun `four mistakes ends it`() {
        var state = ChainsRules.start(puzzle)
        val wrong = listOf(
            listOf("Paris", "Lisbon", "Nile", "Goya"),
            listOf("Paris", "Lisbon", "Nile", "Argon"),
            listOf("Paris", "Lisbon", "Amazon", "Goya"),
            listOf("Paris", "Oslo", "Nile", "Goya"),
        )
        wrong.forEach { guess ->
            state = ChainsRules.submit(ChainsRules.clear(state).pick(*guess.toTypedArray()))
        }
        assertEquals(ChainsRules.MAX_MISTAKES, state.mistakes)
        assertTrue(state.lost)
        assertTrue(state.over)
        assertFalse(state.won)
    }

    @Test
    fun `finding all four wins it`() {
        var state = ChainsRules.start(puzzle)
        listOf(capitals, rivers, painters, elements).forEach { group ->
            state = ChainsRules.submit(state.pick(*group.members.toTypedArray()))
        }
        assertTrue(state.won)
        assertTrue(state.over)
        assertEquals(0, state.mistakes)
        assertTrue(state.remaining.isEmpty())
    }

    @Test
    fun `a finished grid ignores everything after`() {
        var state = ChainsRules.start(puzzle)
        listOf(capitals, rivers, painters, elements).forEach { group ->
            state = ChainsRules.submit(state.pick(*group.members.toTypedArray()))
        }
        assertEquals(state, ChainsRules.select(state, "Paris"))
        assertEquals(state, ChainsRules.submit(state))
    }

    // --- what was guessed, and in what order ----------------------------------------------------

    @Test
    fun `every guess is kept in the order it was made`() {
        var state = ChainsRules.submit(ChainsRules.start(puzzle).pick("Paris", "Nile", "Goya", "Argon"))
        state = ChainsRules.submit(ChainsRules.clear(state).pick(*capitals.members.toTypedArray()))
        assertEquals(2, state.guesses.size)
        assertEquals(setOf("Paris", "Nile", "Goya", "Argon"), state.guesses[0].toSet())
        assertEquals(capitals.members.toSet(), state.guesses[1].toSet())
    }

    @Test
    fun `a guess keeps the order the tiles were tapped in`() {
        // The shared result grid is one row per guess, drawn straight off this list. Tap order is
        // a property of Set.plus rather than of anything written down, so it is asserted here.
        val state = ChainsRules.submit(ChainsRules.start(puzzle).pick("Goya", "Paris", "Argon", "Nile"))
        assertEquals(listOf("Goya", "Paris", "Argon", "Nile"), state.guesses.single())
    }

    @Test
    fun `a repeated guess is not recorded a second time`() {
        val once = ChainsRules.submit(ChainsRules.start(puzzle).pick("Paris", "Lisbon", "Nile", "Goya"))
        val twice = ChainsRules.submit(ChainsRules.clear(once).pick("Paris", "Lisbon", "Nile", "Goya"))
        assertEquals(Verdict.ALREADY_TRIED, twice.verdict)
        assertEquals(1, twice.guesses.size)
    }

    @Test
    fun `replaying the guesses rebuilds the same game`() {
        // This is what lets a half-finished grid survive a refresh without storing derived state
        // that can fall out of step with the rules: the guess list *is* the saved game.
        var played = ChainsRules.start(puzzle)
        played = ChainsRules.submit(played.pick("Paris", "Nile", "Goya", "Argon"))
        played = ChainsRules.submit(ChainsRules.clear(played).pick(*rivers.members.toTypedArray()))
        played = ChainsRules.submit(ChainsRules.clear(played).pick("Paris", "Lisbon", "Oslo", "Goya"))

        val replayed = played.guesses.fold(ChainsRules.start(puzzle)) { state, guess ->
            ChainsRules.submit(guess.fold(ChainsRules.clear(state)) { s, t -> ChainsRules.select(s, t) })
        }
        assertEquals(played.solved, replayed.solved)
        assertEquals(played.mistakes, replayed.mistakes)
        assertEquals(played.guesses, replayed.guesses)
        assertEquals(played.attempts, replayed.attempts)
        assertEquals(played.remaining, replayed.remaining)
    }

    // --- selection ------------------------------------------------------------------------------

    @Test
    fun `selecting is a toggle`() {
        val state = ChainsRules.start(puzzle).pick("Paris")
        assertEquals(setOf("Paris"), state.selected)
        assertTrue(ChainsRules.select(state, "Paris").selected.isEmpty())
    }

    @Test
    fun `a fifth tile is refused rather than silently swapping one out`() {
        val full = ChainsRules.start(puzzle).pick("Paris", "Lisbon", "Oslo", "Sofia")
        val after = ChainsRules.select(full, "Nile")
        assertEquals(full.selected, after.selected)
    }

    @Test
    fun `a solved tile cannot be picked again`() {
        val solved = ChainsRules.submit(ChainsRules.start(puzzle).pick(*capitals.members.toTypedArray()))
        assertEquals(solved.selected, ChainsRules.select(solved, "Paris").selected)
    }

    @Test
    fun `submitting an incomplete selection does nothing`() {
        val partial = ChainsRules.start(puzzle).pick("Paris", "Lisbon")
        assertEquals(partial, ChainsRules.submit(partial))
    }

    // --- afterwards -----------------------------------------------------------------------------

    @Test
    fun `a loss still shows the whole answer`() {
        // Withholding the solution from someone who has just spent four guesses on it teaches
        // them nothing and is the surest way to stop them coming back tomorrow.
        var state = ChainsRules.start(puzzle)
        repeat(ChainsRules.MAX_MISTAKES) { attempt ->
            state = ChainsRules.submit(
                ChainsRules.clear(state).pick("Paris", "Nile", "Goya", puzzle.tiles[attempt + 8]),
            )
        }
        val revealed = ChainsRules.reveal(state)
        assertEquals(ChainsPuzzle.GROUP_COUNT, revealed.size)
        assertEquals(puzzle.groups.map { it.id }.toSet(), revealed.map { it.id }.toSet())
    }

    @Test
    fun `the reveal puts what you found first, then the rest in order`() {
        val state = ChainsRules.submit(ChainsRules.start(puzzle).pick(*painters.members.toTypedArray()))
        val revealed = ChainsRules.reveal(state)
        assertEquals(painters.id, revealed.first().id)
        assertEquals(listOf(capitals.id, rivers.id, elements.id), revealed.drop(1).map { it.id })
    }

    @Test
    fun `a clean sheet scores best and a mistake costs`() {
        var perfect = ChainsRules.start(puzzle)
        listOf(capitals, rivers, painters, elements).forEach {
            perfect = ChainsRules.submit(perfect.pick(*it.members.toTypedArray()))
        }
        var scruffy = ChainsRules.submit(ChainsRules.start(puzzle).pick("Paris", "Nile", "Goya", "Argon"))
        listOf(capitals, rivers, painters, elements).forEach {
            scruffy = ChainsRules.submit(ChainsRules.clear(scruffy).pick(*it.members.toTypedArray()))
        }
        assertTrue(scruffy.won)
        assertTrue(ChainsRules.score(perfect) > ChainsRules.score(scruffy))
        assertTrue(ChainsRules.score(scruffy) > 0, "finishing at all should be worth something")
    }
}
