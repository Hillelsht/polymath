package com.hillelsht.smart.domain.play.chess

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests the engine's judgement, not its rules — [PerftTest] already proves every move it
 * considers is legal, so this only has to show it chooses well among them.
 *
 * There is no oracle for "the objectively best move" the way perft's published counts are an
 * oracle for move generation, so every position here is **self-verified against machinery this
 * codebase already trusts** before it is used to judge the search: [Board.isCheckmate] (proven
 * by [PerftTest] itself) confirms a claimed mate actually is one, and [Board.legalMoves]
 * confirms a claimed hanging piece is really capturable, before either fact is used to grade
 * what [Search] does with it. A mistranscribed FEN then fails at that precondition, loudly,
 * rather than quietly validating the wrong thing.
 */
class SearchTest {

    @Test
    fun `a mate in one is found and named as a mate`() {
        // Ra1-a8 is a textbook back-rank mate: the black king on g8 is boxed in by its own
        // pawns on f7, g7, h7, none of which can retreat to block on rank 8.
        val board = Fen.parse("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1")
        val mateMove = board.legalMoves().first { Chess.moveName(it) == "a1a8" }
        board.make(mateMove)
        assertTrue(board.isCheckmate(), "a1a8 was assumed to be mate but is not — fix the FEN, not the search")
        board.unmake()

        val result = Search.search(board, maxDepth = 3)
        assertEquals("a1a8", Chess.moveName(result.bestMove))
        assertTrue(result.score >= Search.MATE_SCORE - 100, "a found mate should score near MATE_SCORE, got ${result.score}")
    }

    @Test
    fun `a hanging piece is captured instead of quietly lost`() {
        // The black bishop on b2 attacks the white queen on e5 along an empty diagonal, and
        // nothing defends b2 in return — Qxb2 both wins the bishop and removes the threat.
        val board = Fen.parse("k7/8/8/4Q3/8/8/1b6/7K w - - 0 1")
        assertTrue(board.isAttacked(Chess.square(4, 4), Chess.BLACK), "the queen on e5 was assumed to be under attack")
        val capture = board.legalMoves().firstOrNull { Chess.moveName(it) == "e5b2" }
        assertNotNull(capture, "Qxb2 was assumed to be legal")

        val result = Search.search(board, maxDepth = 2)
        assertEquals("e5b2", Chess.moveName(result.bestMove))
    }

    @Test
    fun `every move the search returns is legal in the position it was asked about`() {
        listOf(
            Fen.START,
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
        ).forEach { fen ->
            val board = Fen.parse(fen)
            val legal = board.legalMoves().toSet()
            (1..3).forEach { depth ->
                val result = Search.search(Fen.parse(fen), depth, nodeBudget = 50_000L)
                assertTrue(
                    result.bestMove in legal,
                    "depth $depth on $fen returned ${Chess.moveName(result.bestMove)}, which is not legal",
                )
            }
        }
    }

    @Test
    fun `search is deterministic`() {
        val fen = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
        val first = Search.search(Fen.parse(fen), maxDepth = 3)
        val second = Search.search(Fen.parse(fen), maxDepth = 3)
        assertEquals(first.bestMove, second.bestMove)
        assertEquals(first.score, second.score)
    }

    @Test
    fun `a checkmated position has no move to offer`() {
        // Fool's mate, already on the board — the side to move (White) has no legal move.
        val board = Fen.parse("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3")
        assertTrue(board.isCheckmate(), "this position was assumed to already be checkmate")

        val result = Search.search(board, maxDepth = 4)
        assertEquals(Search.NO_MOVE, result.bestMove)
        assertEquals(-Search.MATE_SCORE, result.score)
    }

    @Test
    fun `a stalemated position is scored as a draw, not a loss`() {
        val board = Fen.parse("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")
        assertTrue(board.isStalemate(), "this position was assumed to already be stalemate")

        val result = Search.search(board, maxDepth = 4)
        assertEquals(Search.NO_MOVE, result.bestMove)
        assertEquals(Search.DRAW_SCORE, result.score)
    }

    @Test
    fun `a small node budget still returns a legal move without crashing`() {
        val board = Fen.parse(Fen.START)
        val result = Search.search(board, maxDepth = 6, nodeBudget = 500L)
        assertTrue(result.bestMove in board.legalMoves().toList())
        assertTrue(result.nodes > 0)
    }

    @Test
    fun `deeper search costs more nodes on a position with real choices`() {
        // Not true of every position (a forced sequence can search shallow), but true of the
        // opening, and a search that ignores its depth argument would fail this immediately.
        val shallow = Search.search(Fen.parse(Fen.START), maxDepth = 2, nodeBudget = Long.MAX_VALUE)
        val deep = Search.search(Fen.parse(Fen.START), maxDepth = 4, nodeBudget = Long.MAX_VALUE)
        assertTrue(deep.nodes > shallow.nodes)
    }
}
