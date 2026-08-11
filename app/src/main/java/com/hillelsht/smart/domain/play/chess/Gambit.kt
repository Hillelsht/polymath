package com.hillelsht.smart.domain.play.chess

/** How a Gambit game currently stands. */
enum class GambitOutcome { IN_PROGRESS, PLAYER_WON, ENGINE_WON, DRAW }

/** What a banked tempo token can be spent on. */
enum class TempoAction {
    /** Drops the engine's next search by one ply. Knowledge makes the opponent weaker. */
    WEAKEN,

    /** Reveals what the engine considers the strongest reply available right now. */
    HINT,

    /** Undoes the last full move pair — the player's move and the engine's answer to it. */
    TAKEBACK,
}

/**
 * A game in progress.
 *
 * Holds the position as a FEN string rather than a live [Board], the same choice [Fen]'s own
 * docstring anticipates: a game is one short string in `game_progress`, so it survives closing
 * the app without a table of its own. [moveHistory] is coordinate notation, kept for takeback
 * and for showing the game so far — reconstructing a [Board] from it and from the FEN is cheap
 * next to a search, so nothing here needs the mutable board to stay alive between calls.
 */
data class GambitMatch(
    val fen: String = Fen.START,
    val playerSide: Int = Chess.WHITE,
    val baseDepth: Int = Gambit.DEFAULT_DEPTH,
    val tempo: Int = 0,
    /** Plies the engine's *next* search will be weakened by. Spent the moment it moves. */
    val weakenBanked: Int = 0,
    val moveHistory: List<String> = emptyList(),
    val outcome: GambitOutcome = GambitOutcome.IN_PROGRESS,
)

/** What trying to apply a move or spend a tempo action actually did. */
sealed interface GambitResult {
    data class Applied(val match: GambitMatch) : GambitResult

    /** The move named was not among the legal moves in this position. */
    data object Illegal : GambitResult

    /** Nothing to do — the game already has an [GambitOutcome] other than IN_PROGRESS. */
    data object GameOver : GambitResult
}

/**
 * The engine side of Gambit: how strong it plays, and what tempo buys against it.
 *
 * The chess is untouched by any of this — [Search] plays exactly as well as its depth allows,
 * with nothing softened or faked. What tempo changes is the *question the engine gets asked*:
 * a weakened search is a search told to think less far ahead, not a search that plays worse
 * moves on purpose within the depth it has. That distinction matters because it means every
 * game stays honest chess, never a scripted loss.
 */
object Gambit {

    /** Plies the engine searches with no tempo spent against it. Beatable, not passive. */
    const val DEFAULT_DEPTH = 5

    const val MIN_DEPTH = 1
    const val MAX_DEPTH = 6

    /** Kept small on purpose — this runs on a phone, between quiz questions, not on a server. */
    const val NODE_BUDGET = 300_000L

    const val TEMPO_PER_CORRECT_ANSWER = 1
    const val COST_WEAKEN = 2
    const val COST_HINT = 3
    const val COST_TAKEBACK = 4

    /** However much is banked, the engine still has to play *some* game. */
    const val MAX_WEAKEN_BANKED = MAX_DEPTH - MIN_DEPTH

    fun costOf(action: TempoAction): Int = when (action) {
        TempoAction.WEAKEN -> COST_WEAKEN
        TempoAction.HINT -> COST_HINT
        TempoAction.TAKEBACK -> COST_TAKEBACK
    }

    fun engineDepth(match: GambitMatch): Int =
        (match.baseDepth - match.weakenBanked).coerceIn(MIN_DEPTH, MAX_DEPTH)

    /** A correct answer banks tempo; a wrong one buys nothing, but costs nothing either. */
    fun bankTempo(match: GambitMatch, correct: Boolean): GambitMatch =
        if (correct) match.copy(tempo = match.tempo + TEMPO_PER_CORRECT_ANSWER) else match

    fun canAfford(match: GambitMatch, action: TempoAction): Boolean {
        if (match.outcome != GambitOutcome.IN_PROGRESS) return false
        if (match.tempo < costOf(action)) return false
        return when (action) {
            TempoAction.WEAKEN -> match.weakenBanked < MAX_WEAKEN_BANKED
            TempoAction.TAKEBACK -> match.moveHistory.size >= 2
            TempoAction.HINT -> true
        }
    }

    /** Spending is a no-op, not a crash, when [canAfford] would say no — callers gate on it. */
    fun spend(match: GambitMatch, action: TempoAction): GambitMatch {
        if (!canAfford(match, action)) return match
        val charged = match.copy(tempo = match.tempo - costOf(action))
        return when (action) {
            TempoAction.WEAKEN -> charged.copy(weakenBanked = charged.weakenBanked + 1)
            TempoAction.HINT -> charged
            TempoAction.TAKEBACK -> {
                val trimmed = charged.moveHistory.dropLast(2)
                charged.copy(moveHistory = trimmed, fen = replay(trimmed), outcome = GambitOutcome.IN_PROGRESS)
            }
        }
    }

    /**
     * The move [Search] currently likes best for whoever is to move, spending nothing itself —
     * [TempoAction.HINT] is charged by the caller before this is shown, exactly once, to the
     * player who paid for it.
     */
    fun suggest(match: GambitMatch): String? {
        val board = Fen.parse(match.fen)
        val result = Search.search(board, DEFAULT_DEPTH, NODE_BUDGET)
        return if (result.bestMove == Search.NO_MOVE) null else Chess.moveName(result.bestMove)
    }

    /** Applies a move typed or tapped in coordinate notation ("e2e4", "e7e8q"). */
    fun playerMove(match: GambitMatch, moveName: String): GambitResult {
        if (match.outcome != GambitOutcome.IN_PROGRESS) return GambitResult.GameOver
        val board = Fen.parse(match.fen)
        val move = board.legalMoves().firstOrNull { Chess.moveName(it) == moveName }
            ?: return GambitResult.Illegal

        board.make(move)
        val history = match.moveHistory + moveName
        return GambitResult.Applied(
            match.copy(fen = Fen.of(board), moveHistory = history, outcome = outcomeOf(board, match.playerSide, history)),
        )
    }

    /** Lets the engine play its move, at whatever depth [weakenBanked] leaves it. */
    fun engineMove(match: GambitMatch): GambitResult {
        if (match.outcome != GambitOutcome.IN_PROGRESS) return GambitResult.GameOver
        val board = Fen.parse(match.fen)
        val result = Search.search(board, engineDepth(match), NODE_BUDGET)
        if (result.bestMove == Search.NO_MOVE) {
            // legalMoves() was already empty — the game ended on the move that just landed
            // here, and outcomeOf should already have caught it. Reached only if it did not.
            return GambitResult.Applied(
                match.copy(outcome = outcomeOf(board, match.playerSide, match.moveHistory)),
            )
        }

        board.make(result.bestMove)
        val history = match.moveHistory + Chess.moveName(result.bestMove)
        return GambitResult.Applied(
            match.copy(
                fen = Fen.of(board),
                weakenBanked = 0,
                moveHistory = history,
                outcome = outcomeOf(board, match.playerSide, history),
            ),
        )
    }

    /**
     * Board placement, side to move, castling rights and the en-passant target — the four FEN
     * fields that identify a position for repetition, deliberately excluding the two that do
     * not: the halfmove clock and the move number would make every occurrence of an otherwise
     * repeated position look unique.
     */
    private fun positionKey(board: Board): String = Fen.of(board).split(' ').take(4).joinToString(" ")

    /**
     * Whether the current position — reached by [moveHistory] from the start — has occurred
     * three times.
     *
     * Found by a self-play test rather than reasoned out in advance: two engines searching one
     * ply deep have nothing better to do than repeat themselves, and without this a match
     * between two weak players — human or engine — simply never ends. Replaying the whole game
     * to answer the question is not free, but a casual match is dozens of moves, not thousands,
     * and this runs once per move rather than once per search node.
     */
    private fun isThreefoldRepetition(moveHistory: List<String>): Boolean {
        val board = Fen.parse(Fen.START)
        val seen = HashMap<String, Int>()
        seen[positionKey(board)] = 1
        for (name in moveHistory) {
            val move = board.legalMoves().first { Chess.moveName(it) == name }
            board.make(move)
            val key = positionKey(board)
            seen[key] = (seen[key] ?: 0) + 1
        }
        return (seen[positionKey(board)] ?: 0) >= 3
    }

    private fun outcomeOf(board: Board, playerSide: Int, moveHistory: List<String>): GambitOutcome {
        if (board.isCheckmate()) {
            // The side that just moved delivered mate; sideToMove is who is now stuck.
            return if (board.sideToMove == playerSide) GambitOutcome.ENGINE_WON else GambitOutcome.PLAYER_WON
        }
        if (board.isStalemate() || board.isInsufficientMaterial() || board.halfmoveClock >= 100 ||
            isThreefoldRepetition(moveHistory)
        ) {
            return GambitOutcome.DRAW
        }
        return GambitOutcome.IN_PROGRESS
    }

    private fun replay(moveNames: List<String>): String {
        val board = Fen.parse(Fen.START)
        for (name in moveNames) {
            val move = board.legalMoves().first { Chess.moveName(it) == name }
            board.make(move)
        }
        return Fen.of(board)
    }
}
