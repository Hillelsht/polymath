package com.hillelsht.smart.domain.play.chess

/**
 * Picks a move.
 *
 * Negamax with alpha-beta pruning, iterative deepening, and a quiescence search at the leaves
 * so the engine does not stop mid-capture and misjudge a position that is about to lose a
 * piece. Move generation is [Board.legalMoves] — the one already proven exact by [PerftTest] —
 * so nothing here can hand the engine a move it should not have, only choose badly among the
 * ones it is allowed.
 *
 * This runs on a phone between quiz questions, not on a server: node budgets are small on
 * purpose, and depth is a dial the caller turns down for a weaker opponent rather than a fixed
 * constant.
 */
object Search {

    const val MATE_SCORE = 1_000_000
    const val DRAW_SCORE = 0

    /** No legal move existed to search from — the position was already decided. */
    const val NO_MOVE = -1

    data class Result(val bestMove: Int, val score: Int, val depth: Int, val nodes: Long)

    /**
     * @param maxDepth plies to search, before quiescence extends captures further.
     * @param nodeBudget a soft cap: the current iteration finishes, then deepening stops.
     */
    fun search(board: Board, maxDepth: Int, nodeBudget: Long = 300_000L): Result {
        val rootMoves = board.legalMoves()
        if (rootMoves.isEmpty()) {
            val score = if (board.inCheck()) -MATE_SCORE else DRAW_SCORE
            return Result(NO_MOVE, score, 0, 0)
        }

        var best = Result(orderRoot(rootMoves, board)[0], 0, 0, 0)
        val nodes = LongArray(1)

        for (depth in 1..maxDepth.coerceAtLeast(1)) {
            val killers = Array(depth + 1) { IntArray(2) { NO_MOVE } }
            val (move, score) = searchRoot(board, depth, killers, nodes)
            best = Result(move, score, depth, nodes[0])
            if (nodes[0] >= nodeBudget) break
            // A forced mate has been found; no shallower question is left to answer by
            // searching deeper, and the position only gets more expensive to search from here.
            if (kotlin.math.abs(score) >= MATE_SCORE - 1000) break
        }
        return best
    }

    private fun searchRoot(board: Board, depth: Int, killers: Array<IntArray>, nodes: LongArray): Pair<Int, Int> {
        val moves = orderRoot(board.legalMoves(), board)
        var alpha = -MATE_SCORE - 1
        val beta = MATE_SCORE + 1
        var bestMove = moves[0]
        var bestScore = -MATE_SCORE - 1

        for (move in moves) {
            board.make(move)
            val score = -negamax(board, depth - 1, -beta, -alpha, 1, killers, nodes)
            board.unmake()
            if (score > bestScore) {
                bestScore = score
                bestMove = move
            }
            if (score > alpha) alpha = score
        }
        return bestMove to bestScore
    }

    private fun negamax(
        board: Board,
        depth: Int,
        alphaIn: Int,
        beta: Int,
        ply: Int,
        killers: Array<IntArray>,
        nodes: LongArray,
    ): Int {
        nodes[0]++
        if (board.isInsufficientMaterial() || board.halfmoveClock >= 100) return DRAW_SCORE

        val moves = board.legalMoves()
        if (moves.isEmpty()) {
            // A mate found deeper in the tree is worth less than one found here, so the score
            // carries `ply` — the search prefers the fastest forced mate rather than any mate.
            return if (board.inCheck()) -MATE_SCORE + ply else DRAW_SCORE
        }
        if (depth <= 0) return quiescence(board, alphaIn, beta, nodes)

        var alpha = alphaIn
        val ordered = order(moves, board, ply, killers)
        var bestScore = -MATE_SCORE - 1

        for (move in ordered) {
            board.make(move)
            val score = -negamax(board, depth - 1, -beta, -alpha, ply + 1, killers, nodes)
            board.unmake()

            if (score > bestScore) bestScore = score
            if (score > alpha) alpha = score
            if (alpha >= beta) {
                if (!isNoisy(board, move) && ply < killers.size) {
                    val slot = killers[ply]
                    if (slot[0] != move) {
                        slot[1] = slot[0]
                        slot[0] = move
                    }
                }
                break
            }
        }
        return bestScore
    }

    /**
     * Extends the search along captures only, past the nominal depth.
     *
     * Stopping a plain search the instant depth runs out is the classic way an engine hangs a
     * piece: it evaluates mid-exchange, sees itself a queen up, and never looks far enough to
     * see the queen recaptured. Quiescence keeps resolving captures until the position is
     * "quiet" — nothing left worth taking — before trusting the static evaluation.
     */
    private fun quiescence(board: Board, alphaIn: Int, beta: Int, nodes: LongArray): Int {
        nodes[0]++
        val standPat = Evaluation.evaluate(board) * board.sideToMove
        if (standPat >= beta) return beta
        var alpha = maxOf(alphaIn, standPat)

        val captures = board.legalMoves().filter { isNoisy(board, it) }
        for (move in orderCaptures(captures, board)) {
            board.make(move)
            val score = -quiescence(board, -beta, -alpha, nodes)
            board.unmake()
            if (score >= beta) return beta
            if (score > alpha) alpha = score
        }
        return alpha
    }

    private fun isNoisy(board: Board, move: Int): Boolean =
        board.squares[Move.to(move)] != Chess.EMPTY || Move.isEnPassant(move) ||
            Move.promotion(move) != Chess.EMPTY

    /**
     * Most Valuable Victim, Least Valuable Attacker: trying the capture of a queen with a pawn
     * before the capture of a pawn with a queen finds the same eventual score with far fewer
     * nodes, because a strong move refutes a bad line fast enough to prune everything beside it.
     */
    private fun captureScore(board: Board, move: Int): Int {
        val victim = board.squares[Move.to(move)]
        val victimValue = if (Move.isEnPassant(move)) Evaluation.PAWN else Evaluation.materialValue(Chess.typeOf(victim))
        val attackerValue = Evaluation.materialValue(Chess.typeOf(board.squares[Move.from(move)]))
        val promotionBonus = if (Move.promotion(move) != Chess.EMPTY) Evaluation.QUEEN else 0
        return victimValue * 16 - attackerValue + promotionBonus
    }

    private fun orderCaptures(moves: List<Int>, board: Board): List<Int> =
        moves.sortedByDescending { captureScore(board, it) }

    private fun order(moves: IntArray, board: Board, ply: Int, killers: Array<IntArray>): List<Int> {
        val killerSlots = if (ply < killers.size) killers[ply] else IntArray(0)
        return moves.sortedByDescending { move ->
            when {
                isNoisy(board, move) -> 1_000_000 + captureScore(board, move)
                move == killerSlots.getOrElse(0) { NO_MOVE } -> 900_000
                move == killerSlots.getOrElse(1) { NO_MOVE } -> 800_000
                else -> 0
            }
        }
    }

    private fun orderRoot(moves: IntArray, board: Board): List<Int> =
        moves.sortedByDescending { if (isNoisy(board, it)) captureScore(board, it) else -1 }
}
