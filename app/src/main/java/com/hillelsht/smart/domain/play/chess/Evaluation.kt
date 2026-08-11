package com.hillelsht.smart.domain.play.chess

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Scores a position in centipawns, positive meaning better for White.
 *
 * The positional terms below are **formulas, not a transcribed table**. Named piece-square
 * tables exist and are well known, but copying one from memory into a 0x88 array carries a real
 * risk this codebase does not take lightly elsewhere: a table is 64 numbers with no structure
 * to check them against, so a transposed row or an off-by-one in the rank ordering would sit
 * there silently, scoring positions confidently and wrongly, with nothing short of playing
 * hundreds of games to notice. A formula derived from "how far is this square from the centre"
 * is checkable by inspection, and the tests below assert the comparative properties directly —
 * a knight belongs more in the centre than the corner — rather than trusting specific numbers.
 */
object Evaluation {

    const val PAWN = 100
    const val KNIGHT = 320
    const val BISHOP = 330
    const val ROOK = 500
    const val QUEEN = 900

    /** Material below which the game is treated as an endgame for king safety purposes. */
    private const val ENDGAME_MATERIAL = ROOK * 2 + BISHOP

    fun materialValue(type: Int): Int = when (type) {
        Chess.PAWN -> PAWN
        Chess.KNIGHT -> KNIGHT
        Chess.BISHOP -> BISHOP
        Chess.ROOK -> ROOK
        Chess.QUEEN -> QUEEN
        else -> 0
    }

    /** 0 at the four central squares, rising to 3 at a corner. Chebyshev-shaped on purpose: a
     * piece a file away from centre and a piece a rank away from centre are equally exposed. */
    private fun centreDistance(square: Int): Int {
        val file = Chess.fileOf(square)
        val rank = Chess.rankOf(square)
        val fileDistance = min(abs(file - 3), abs(file - 4))
        val rankDistance = min(abs(rank - 3), abs(rank - 4))
        return max(fileDistance, rankDistance)
    }

    /** 0 on the back rank, rising to 3 in the centre. Board edge in the other direction. */
    private fun edgeDistance(square: Int): Int {
        val file = Chess.fileOf(square)
        val rank = Chess.rankOf(square)
        return min(min(file, 7 - file), min(rank, 7 - rank))
    }

    fun evaluate(board: Board): Int {
        var nonPawnMaterial = 0
        for (square in 0..119) {
            if (!Chess.onBoard(square)) continue
            val piece = board.squares[square]
            if (piece == Chess.EMPTY) continue
            val type = Chess.typeOf(piece)
            if (type != Chess.PAWN && type != Chess.KING) nonPawnMaterial += materialValue(type)
        }
        val endgame = nonPawnMaterial <= ENDGAME_MATERIAL

        var score = 0
        for (square in 0..119) {
            if (!Chess.onBoard(square)) continue
            val piece = board.squares[square]
            if (piece == Chess.EMPTY) continue
            val colour = Chess.colourOf(piece)
            val type = Chess.typeOf(piece)
            score += colour * (materialValue(type) + positional(board, square, type, colour, endgame))
        }
        return score
    }

    private fun positional(board: Board, square: Int, type: Int, colour: Int, endgame: Boolean): Int =
        when (type) {
            Chess.PAWN -> pawnBonus(square, colour)
            Chess.KNIGHT -> (3 - centreDistance(square)) * 8
            Chess.BISHOP -> (3 - centreDistance(square)) * 5
            Chess.ROOK -> rookBonus(board, square, colour)
            Chess.QUEEN -> (3 - centreDistance(square)) * 2
            // Midgame: a king near the board's edge (small edgeDistance) is rewarded, since
            // that is where castling puts it. The sign here was wrong on the first pass — it
            // rewarded wandering toward the centre instead, which the test below caught:
            // a king on the back rank scored *worse* than the same king pushed forward to f4.
            Chess.KING -> if (endgame) (3 - centreDistance(square)) * 10 else (3 - edgeDistance(square)) * 10
            else -> 0
        }

    /** Rewards advancing and staying central, without ever outweighing losing the pawn. */
    private fun pawnBonus(square: Int, colour: Int): Int {
        val rank = Chess.rankOf(square)
        // Ranks from the pawn's own second rank, 0..5, so both colours advance symmetrically.
        val advance = if (colour == Chess.WHITE) rank - 1 else 6 - rank
        val fileDistance = min(abs(Chess.fileOf(square) - 3), abs(Chess.fileOf(square) - 4))
        return advance.coerceIn(0, 5) * 6 + (3 - fileDistance) * 3
    }

    /** An open file (no pawns at all) is worth more than one merely clear of the rook's own. */
    private fun rookBonus(board: Board, square: Int, colour: Int): Int {
        val file = Chess.fileOf(square)
        var ownPawn = false
        var enemyPawn = false
        for (rank in 0..7) {
            val piece = board.squares[Chess.square(file, rank)]
            if (Chess.typeOf(piece) != Chess.PAWN) continue
            if (Chess.colourOf(piece) == colour) ownPawn = true else enemyPawn = true
        }
        val fileBonus = when {
            !ownPawn && !enemyPawn -> 20
            !ownPawn -> 10
            else -> 0
        }
        val seventhRank = if (colour == Chess.WHITE) 6 else 1
        val rankBonus = if (Chess.rankOf(square) == seventhRank) 15 else 0
        return fileBonus + rankBonus
    }
}
