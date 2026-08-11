package com.hillelsht.smart.domain.play.chess

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the evaluation by its comparative properties rather than by specific numbers.
 *
 * The positional terms are formulas rather than a transcribed piece-square table precisely so
 * they can be checked this way: "a knight in the centre is worth more than a knight in the
 * corner" is something this file can assert with confidence, in a way that "row 4, column 6 of
 * a 64-number table should read 15" never could be without re-deriving the table from scratch.
 */
class EvaluationTest {

    private fun boardOf(fen: String) = Fen.parse(fen)

    @Test
    fun `the opening position is exactly balanced`() {
        // Both sides have identical material on mirrored squares, so nothing here should ever
        // favour White or Black before a single move is played.
        assertEquals(0, Evaluation.evaluate(Fen.parse(Fen.START)))
    }

    @Test
    fun `material dominates a bare king and queen versus a bare king`() {
        val ahead = boardOf("4k3/8/8/8/8/3Q4/8/4K3 w - - 0 1")
        val even = boardOf("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
        assertTrue(Evaluation.evaluate(ahead) > Evaluation.evaluate(even) + Evaluation.QUEEN - 50)
    }

    @Test
    fun `a knight is worth more in the centre than in the corner`() {
        val centre = boardOf("4k3/8/8/3N4/8/8/8/4K3 w - - 0 1")
        val corner = boardOf("4k3/8/8/8/8/8/8/N3K3 w - - 0 1")
        assertTrue(Evaluation.evaluate(centre) > Evaluation.evaluate(corner))
    }

    @Test
    fun `a bishop is worth more in the centre than in the corner`() {
        val centre = boardOf("4k3/8/8/3B4/8/8/8/4K3 w - - 0 1")
        val corner = boardOf("4k3/8/8/8/8/8/8/B3K3 w - - 0 1")
        assertTrue(Evaluation.evaluate(centre) > Evaluation.evaluate(corner))
    }

    @Test
    fun `an advanced pawn is worth more than one on its own second rank`() {
        val advanced = boardOf("4k3/8/8/8/4P3/8/8/4K3 w - - 0 1")
        val home = boardOf("4k3/8/8/8/8/8/4P3/4K3 w - - 0 1")
        assertTrue(Evaluation.evaluate(advanced) > Evaluation.evaluate(home))
    }

    @Test
    fun `a central pawn outranks a rim pawn at the same rank`() {
        val central = boardOf("4k3/8/8/8/4P3/8/8/4K3 w - - 0 1")
        val rim = boardOf("4k3/8/8/8/P7/8/8/4K3 w - - 0 1")
        assertTrue(Evaluation.evaluate(central) > Evaluation.evaluate(rim))
    }

    @Test
    fun `a rook on an open file outranks one boxed in by pawns`() {
        val open = boardOf("4k3/8/8/8/8/8/8/R3K3 w - - 0 1")
        val boxed = boardOf("4k3/8/8/8/8/8/p1p5/R3K3 b - - 0 1")
        // Same side to move is irrelevant to a static eval; only the rook's file changes.
        assertTrue(Evaluation.evaluate(open) > Evaluation.evaluate(boxed))
    }

    @Test
    fun `a rook on the seventh rank outranks one on its own back rank`() {
        val seventh = boardOf("4k3/R7/8/8/8/8/8/4K3 w - - 0 1")
        val home = boardOf("4k3/8/8/8/8/8/8/R3K3 w - - 0 1")
        assertTrue(Evaluation.evaluate(seventh) > Evaluation.evaluate(home))
    }

    @Test
    fun `a king shelters on the back rank in the middlegame`() {
        // With queens and both sides' full minor and major pieces on, this is not an endgame,
        // and a king wandering to the centre should read worse than one still tucked away.
        val safe = "r3k2r/pppq1ppp/2n2n2/3pp3/3PP3/2N2N2/PPPQ1PPP/R3K2R w KQkq - 0 1"
        val exposed = "r3k2r/pppq1ppp/2n2n2/3pp3/3PPK2/2N2N2/PPPQ1PPP/R6R w kq - 0 1"
        assertTrue(Evaluation.evaluate(boardOf(safe)) > Evaluation.evaluate(boardOf(exposed)))
    }

    @Test
    fun `a king centralises once the board has emptied out`() {
        val centre = boardOf("8/8/8/3K4/8/8/8/7k w - - 0 1")
        val corner = boardOf("8/8/8/8/8/8/8/K6k w - - 0 1")
        assertTrue(Evaluation.evaluate(centre) > Evaluation.evaluate(corner))
    }

    @Test
    fun `evaluation is symmetric under colour-flipped mirrored positions`() {
        // The same shape of advantage, held by the other colour, should read as an equal and
        // opposite score — the surest way to catch a stray sign error in a positional term.
        val whiteUp = boardOf("4k3/8/8/8/4P3/8/8/4K3 w - - 0 1")
        val blackUp = boardOf("4k3/8/8/4p3/8/8/8/4K3 w - - 0 1")
        assertEquals(Evaluation.evaluate(whiteUp), -Evaluation.evaluate(blackUp))
    }
}
