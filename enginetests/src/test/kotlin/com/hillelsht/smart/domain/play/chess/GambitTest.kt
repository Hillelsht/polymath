package com.hillelsht.smart.domain.play.chess

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Guards the tempo economy — the part of Gambit that is not chess at all, and the part with no
 * external oracle to check it against, so every rule here is asserted directly.
 */
class GambitTest {

    // --- earning and spending tempo -----------------------------------------------------------

    @Test
    fun `tempo is banked only for a correct answer`() {
        val match = GambitMatch()
        assertEquals(1, Gambit.bankTempo(match, correct = true).tempo)
        assertEquals(0, Gambit.bankTempo(match, correct = false).tempo)
    }

    @Test
    fun `each action can only be afforded once enough tempo is banked`() {
        TempoAction.entries.forEach { action ->
            val cost = Gambit.costOf(action)
            val poor = GambitMatch(tempo = cost - 1, moveHistory = listOf("e2e4", "e7e5"))
            val rich = GambitMatch(tempo = cost, moveHistory = listOf("e2e4", "e7e5"))
            assertFalse(Gambit.canAfford(poor, action), "$action was affordable one short of its cost")
            assertTrue(Gambit.canAfford(rich, action), "$action was not affordable at its exact cost")
        }
    }

    @Test
    fun `spending an action that cannot be afforded changes nothing`() {
        val match = GambitMatch(tempo = 0)
        assertEquals(match, Gambit.spend(match, TempoAction.WEAKEN))
        assertEquals(match, Gambit.spend(match, TempoAction.HINT))
        assertEquals(match, Gambit.spend(match, TempoAction.TAKEBACK))
    }

    @Test
    fun `weaken drops the engine's search by one ply and can be stacked`() {
        val match = GambitMatch(tempo = 100)
        val once = Gambit.spend(match, TempoAction.WEAKEN)
        assertEquals(Gambit.DEFAULT_DEPTH - 1, Gambit.engineDepth(once))
        val twice = Gambit.spend(once, TempoAction.WEAKEN)
        assertEquals(Gambit.DEFAULT_DEPTH - 2, Gambit.engineDepth(twice))
    }

    @Test
    fun `weakening cannot push the engine below the minimum depth`() {
        var match = GambitMatch(tempo = 1000)
        repeat(20) { match = Gambit.spend(match, TempoAction.WEAKEN) }
        assertEquals(Gambit.MIN_DEPTH, Gambit.engineDepth(match))
        assertFalse(Gambit.canAfford(match, TempoAction.WEAKEN), "weaken stayed affordable past the floor")
    }

    @Test
    fun `weaken is consumed the moment the engine actually moves`() {
        val weakened = Gambit.spend(GambitMatch(tempo = 100), TempoAction.WEAKEN)
        assertEquals(1, weakened.weakenBanked)
        val after = Gambit.engineMove(weakened)
        assertIs<GambitResult.Applied>(after)
        assertEquals(0, (after as GambitResult.Applied).match.weakenBanked)
    }

    @Test
    fun `a takeback needs a full move pair on the board`() {
        assertFalse(Gambit.canAfford(GambitMatch(tempo = 100, moveHistory = emptyList()), TempoAction.TAKEBACK))
        assertFalse(Gambit.canAfford(GambitMatch(tempo = 100, moveHistory = listOf("e2e4")), TempoAction.TAKEBACK))
        assertTrue(Gambit.canAfford(GambitMatch(tempo = 100, moveHistory = listOf("e2e4", "e7e5")), TempoAction.TAKEBACK))
    }

    @Test
    fun `a takeback restores exactly the position two plies ago`() {
        // Built two ways and compared: spend()'s own replay, against a second game played only
        // as far as the point being returned to. A bug in either path shows up as a mismatch.
        fun play(moves: List<String>): GambitMatch {
            var match = GambitMatch()
            moves.forEach { move ->
                match = (Gambit.playerMove(match, move) as GambitResult.Applied).match
            }
            return match
        }

        val played = play(listOf("e2e4", "e7e5", "g1f3", "b8c6"))
        val stoppedEarlier = play(listOf("e2e4", "e7e5"))

        val afterTakeback = Gambit.spend(played.copy(tempo = 100), TempoAction.TAKEBACK)
        assertEquals(stoppedEarlier.fen, afterTakeback.fen)
        assertEquals(stoppedEarlier.moveHistory, afterTakeback.moveHistory)
    }

    @Test
    fun `nothing can be spent once the game is over`() {
        val over = GambitMatch(tempo = 100, outcome = GambitOutcome.PLAYER_WON)
        TempoAction.entries.forEach { assertFalse(Gambit.canAfford(over, it), "$it was affordable after the game ended") }
    }

    // --- playing moves -------------------------------------------------------------------------

    @Test
    fun `a legal move updates the position and the history`() {
        val result = Gambit.playerMove(GambitMatch(), "e2e4")
        assertIs<GambitResult.Applied>(result)
        val match = (result as GambitResult.Applied).match
        assertEquals(listOf("e2e4"), match.moveHistory)
        assertEquals(GambitOutcome.IN_PROGRESS, match.outcome)
        assertTrue(match.fen != GambitMatch().fen)
    }

    @Test
    fun `a move that is not legal here is refused`() {
        // A perfectly legal chess move — just not from the position the game is actually in.
        assertEquals(GambitResult.Illegal, Gambit.playerMove(GambitMatch(), "e2e5"))
    }

    @Test
    fun `nothing can be played once the game is over`() {
        val over = GambitMatch(outcome = GambitOutcome.DRAW)
        assertEquals(GambitResult.GameOver, Gambit.playerMove(over, "e2e4"))
        assertEquals(GambitResult.GameOver, Gambit.engineMove(over))
    }

    @Test
    fun `fool's mate is recognised the moment it lands on the board`() {
        // 1. f3 e5 2. g4 Qh4# — playerMove is used to make every move here regardless of
        // colour; the domain layer does not care who calls it, only whether the move is legal
        // in the position on the board, which is exactly what makes it usable this way.
        var match = GambitMatch()
        for (move in listOf("f2f3", "e7e5", "g2g4", "d8h4")) {
            val result = Gambit.playerMove(match, move)
            assertIs<GambitResult.Applied>(result, "expected $move to be legal")
            match = (result as GambitResult.Applied).match
        }

        // Independently verified against the machinery PerftTest already trusts, not only
        // against Gambit's own bookkeeping.
        assertTrue(Fen.parse(match.fen).isCheckmate(), "the final position was assumed to be checkmate")
        assertEquals(GambitOutcome.ENGINE_WON, match.outcome)
    }

    @Test
    fun `the engine always replies with a legal move of its own`() {
        var match = (Gambit.playerMove(GambitMatch(), "e2e4") as GambitResult.Applied).match
        val result = Gambit.engineMove(match)
        assertIs<GambitResult.Applied>(result)
        match = (result as GambitResult.Applied).match
        assertEquals(2, match.moveHistory.size)
        assertEquals(GambitOutcome.IN_PROGRESS, match.outcome)
    }

    @Test
    fun `a hint suggests a real legal move without spending anything itself`() {
        val match = GambitMatch(tempo = 100)
        val hint = Gambit.suggest(match)
        assertTrue(hint != null)
        val legal = Fen.parse(match.fen).legalMoves().map { Chess.moveName(it) }
        assertTrue(hint in legal, "the suggested move $hint is not legal in the starting position")
        // suggest() itself is free; only spend(..., HINT) — called by the caller before
        // showing it — actually deducts tempo.
        assertEquals(100, match.tempo)
    }

    @Test
    fun `shuffling back to the same position three times is a draw`() {
        // Knights out and back, twice: the position after move 4 is identical to the start
        // (same placement, same side to move, same rights, no en passant target), and it is
        // the third time that exact position has occurred (the start counts as the first).
        var match = GambitMatch()
        for (move in listOf("g1f3", "g8f6", "f3g1", "f6g8", "g1f3", "g8f6", "f3g1", "f6g8")) {
            val result = Gambit.playerMove(match, move)
            assertIs<GambitResult.Applied>(result, "expected $move to be legal")
            match = (result as GambitResult.Applied).match
        }
        assertEquals(GambitOutcome.DRAW, match.outcome)
    }

    @Test
    fun `the same position twice is not yet a repetition draw`() {
        var match = GambitMatch()
        for (move in listOf("g1f3", "g8f6", "f3g1", "f6g8")) {
            val result = Gambit.playerMove(match, move)
            assertIs<GambitResult.Applied>(result)
            match = (result as GambitResult.Applied).match
        }
        assertEquals(GambitOutcome.IN_PROGRESS, match.outcome)
    }

    // --- a full game never crashes and always terminates in a real outcome --------------------

    @Test
    fun `engine self-play reaches a real outcome within a bounded number of moves`() {
        // Both "sides" played by the engine at a shallow, fast depth — this is a plumbing test,
        // not a strength test, so it only has to finish, not finish well.
        var match = GambitMatch(baseDepth = Gambit.MIN_DEPTH)
        var plies = 0
        while (match.outcome == GambitOutcome.IN_PROGRESS && plies < 120) {
            val result = Gambit.engineMove(match)
            assertIs<GambitResult.Applied>(result, "move $plies produced no move on a game still in progress")
            match = (result as GambitResult.Applied).match
            plies++
        }
        assertTrue(plies < 120, "self-play did not reach an outcome within 120 plies")
        assertTrue(
            match.outcome in setOf(GambitOutcome.PLAYER_WON, GambitOutcome.ENGINE_WON, GambitOutcome.DRAW),
        )
    }
}
