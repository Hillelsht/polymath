package com.hillelsht.smart.domain.play.chess

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the move generator against published node counts.
 *
 * These six positions are the standard perft suite, chosen decades ago because between them
 * they exercise every rule that engines get wrong: castling through an attacked square, an en
 * passant capture that would expose the king along a rank, promotion with check, castling
 * rights lost by a rook being *captured* rather than moved. The counts are exact. An engine
 * that invents one move or misses one comes out with a different number, so this is not a
 * sample of the behaviour — it is all of it, to the node.
 *
 * This is why chess was scheduled ahead of the other games: it is the only one whose
 * correctness can be established from a machine that cannot run the app.
 */
class PerftTest {

    private fun perft(fen: String, depth: Int): Long = Perft.count(Fen.parse(fen), depth)

    private fun check(fen: String, expected: List<Long>) {
        expected.forEachIndexed { index, nodes ->
            val depth = index + 1
            val actual = perft(fen, depth)
            assertEquals(
                nodes, actual,
                "perft($depth) of $fen\n  divide: ${Perft.divide(Fen.parse(fen), depth)}",
            )
        }
    }

    @Test
    fun `the opening position`() {
        check(Fen.START, listOf(20L, 400L, 8_902L, 197_281L))
    }

    @Test
    fun `the opening position to five ply`() {
        assertEquals(4_865_609L, perft(Fen.START, 5))
    }

    @Test
    fun `kiwipete — castling, pins and a crowded middlegame`() {
        check(
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            listOf(48L, 2_039L, 97_862L),
        )
    }

    @Test
    fun `an endgame that turns on en passant`() {
        check("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1", listOf(14L, 191L, 2_812L, 43_238L))
    }

    @Test
    fun `promotions under check`() {
        check(
            "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
            listOf(6L, 264L, 9_467L),
        )
    }

    @Test
    fun `the mirrored promotion position`() {
        check(
            "r2q1rk1/pP1p2pp/Q4n2/bbp1p3/Np6/1B3NBn/pPPP1PPP/R3K2R b KQ - 0 1",
            listOf(6L, 264L, 9_467L),
        )
    }

    @Test
    fun `a position with no castling rights left`() {
        check("rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8", listOf(44L, 1_486L, 62_379L))
    }

    // --- the rules those counts are really testing -------------------------------------------

    @Test
    fun `a king may not castle out of check`() {
        val board = Fen.parse("r3k2r/8/8/8/8/8/8/4R3 b kq - 0 1")
        assertTrue(board.legalMoves().none { Move.isCastle(it) }, "castled while in check")
    }

    @Test
    fun `a king may not castle through an attacked square`() {
        // The rook on f1 covers f8, which the black king would cross going king-side.
        val board = Fen.parse("r3k2r/8/8/8/8/8/8/5R2 b kq - 0 1")
        val castles = board.legalMoves().filter { Move.isCastle(it) }.map { Chess.moveName(it) }
        assertEquals(listOf("e8c8"), castles, "castling through f8 should be refused")
    }

    @Test
    fun `a king may castle into a square that is merely adjacent to danger`() {
        val board = Fen.parse("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        val castles = board.legalMoves().filter { Move.isCastle(it) }.map { Chess.moveName(it) }.sorted()
        assertEquals(listOf("e1c1", "e1g1"), castles)
    }

    @Test
    fun `capturing a rook on its home square costs the other side its castling right`() {
        // Easy to miss: the side losing the right never moved a piece.
        val board = Fen.parse("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        val takeRook = board.legalMoves().first { Chess.moveName(it) == "a1a8" }
        board.make(takeRook)
        assertEquals(0, board.castling and Chess.BLACK_QUEEN_SIDE, "black kept a right it lost")
        assertTrue(board.castling and Chess.BLACK_KING_SIDE != 0, "black lost the wrong right")
    }

    @Test
    fun `an en passant capture that would expose the king is illegal`() {
        // Both pawns leave the fifth rank at once, unveiling the rook on the king. Generators
        // that check pins piece-by-piece rather than by playing the move miss exactly this.
        val board = Fen.parse("8/8/8/K2pP2q/8/8/8/3k4 w - d6 0 1")
        assertTrue(
            board.legalMoves().none { Move.isEnPassant(it) },
            "en passant exposed the king and was allowed",
        )
    }

    @Test
    fun `a pawn reaching the last rank promotes four ways`() {
        val board = Fen.parse("8/P6k/8/8/8/8/8/K7 w - - 0 1")
        val promotions = board.legalMoves()
            .filter { Move.promotion(it) != Chess.EMPTY }
            .map { Chess.moveName(it) }
            .sorted()
        assertEquals(listOf("a7a8b", "a7a8n", "a7a8q", "a7a8r"), promotions)
    }

    // --- make and unmake are exact inverses ---------------------------------------------------

    @Test
    fun `unmaking every move restores the position exactly`() {
        // Perft would still pass with a leaky unmake as long as the leak were symmetric, so the
        // property is asserted directly, over positions chosen to include castling, promotion
        // and en passant.
        listOf(
            Fen.START,
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
            "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
        ).forEach { fen ->
            val board = Fen.parse(fen)
            walk(board, 3, fen)
        }
    }

    private fun walk(board: Board, depth: Int, fen: String) {
        if (depth == 0) return
        val before = Fen.of(board)
        for (move in board.legalMoves()) {
            board.make(move)
            walk(board, depth - 1, fen)
            board.unmake()
            assertEquals(before, Fen.of(board), "unmaking ${Chess.moveName(move)} did not restore $fen")
        }
    }

    // --- FEN round-trips ----------------------------------------------------------------------

    @Test
    fun `a position survives being written out and read back`() {
        listOf(
            Fen.START,
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
            "4k3/8/8/8/8/8/8/4K3 b - - 13 42",
        ).forEach { fen ->
            assertEquals(fen, Fen.of(Fen.parse(fen)))
        }
    }

    @Test
    fun `nonsense is refused rather than half-parsed`() {
        listOf(
            "",
            "not a fen",
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP w KQkq - 0 1",
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNX w KQkq - 0 1",
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR x KQkq - 0 1",
            "rnbqkbnr/ppppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        ).forEach { fen ->
            val failed = runCatching { Fen.parse(fen) }.isFailure
            assertTrue(failed, "'$fen' was accepted")
        }
    }

    // --- terminal positions -------------------------------------------------------------------

    @Test
    fun `mate and stalemate are told apart`() {
        val foolsMate = Fen.parse("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3")
        assertTrue(foolsMate.isCheckmate())
        assertTrue(!foolsMate.isStalemate())

        val stalemate = Fen.parse("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")
        assertTrue(stalemate.isStalemate())
        assertTrue(!stalemate.isCheckmate())
    }

    @Test
    fun `a game nobody can win is recognised`() {
        assertTrue(Fen.parse("8/8/4k3/8/8/3K4/8/8 w - - 0 1").isInsufficientMaterial())
        assertTrue(Fen.parse("8/8/4k3/8/8/3K1B2/8/8 w - - 0 1").isInsufficientMaterial())
        assertTrue(!Fen.parse("8/8/4k3/8/8/3K1R2/8/8 w - - 0 1").isInsufficientMaterial())
        assertTrue(!Fen.parse(Fen.START).isInsufficientMaterial())
    }
}
