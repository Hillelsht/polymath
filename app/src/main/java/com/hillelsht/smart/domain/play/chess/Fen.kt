package com.hillelsht.smart.domain.play.chess

/**
 * Forsyth–Edwards notation: a whole position in one line of text.
 *
 * Needed for two things that both matter. The published perft positions are given as FEN, so
 * without a parser the engine cannot be checked against them at all; and a game in progress is
 * one short string in `game_progress`, which means a game survives closing the app without a
 * table of its own.
 */
object Fen {

    const val START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    private const val PIECES = "pnbrqk"

    class InvalidFen(message: String) : IllegalArgumentException(message)

    fun parse(fen: String): Board {
        val parts = fen.trim().split(Regex("\\s+"))
        if (parts.size < 4) throw InvalidFen("expected at least four fields, got ${parts.size}")

        val squares = IntArray(128)
        val ranks = parts[0].split("/")
        if (ranks.size != 8) throw InvalidFen("expected eight ranks, got ${ranks.size}")

        ranks.forEachIndexed { index, row ->
            // FEN starts at rank 8 and counts down; the board indexes rank 1 upwards.
            val rank = 7 - index
            var file = 0
            for (symbol in row) {
                when {
                    symbol.isDigit() -> file += symbol - '0'
                    else -> {
                        val type = PIECES.indexOf(symbol.lowercaseChar())
                        if (type < 0) throw InvalidFen("'$symbol' is not a piece")
                        if (file > 7) throw InvalidFen("rank ${rank + 1} is too long")
                        val colour = if (symbol.isUpperCase()) Chess.WHITE else Chess.BLACK
                        squares[Chess.square(file, rank)] = (type + 1) * colour
                        file++
                    }
                }
            }
            if (file != 8) throw InvalidFen("rank ${rank + 1} covers $file files, expected 8")
        }

        val side = when (parts[1]) {
            "w" -> Chess.WHITE
            "b" -> Chess.BLACK
            else -> throw InvalidFen("'${parts[1]}' is not a side to move")
        }

        var castling = 0
        if (parts[2] != "-") {
            for (right in parts[2]) {
                castling = castling or when (right) {
                    'K' -> Chess.WHITE_KING_SIDE
                    'Q' -> Chess.WHITE_QUEEN_SIDE
                    'k' -> Chess.BLACK_KING_SIDE
                    'q' -> Chess.BLACK_QUEEN_SIDE
                    else -> throw InvalidFen("'$right' is not a castling right")
                }
            }
        }

        val ep = if (parts[3] == "-") {
            Chess.NO_SQUARE
        } else {
            val file = parts[3][0] - 'a'
            val rank = parts[3][1] - '1'
            if (file !in 0..7 || rank !in 0..7) throw InvalidFen("'${parts[3]}' is not a square")
            Chess.square(file, rank)
        }

        return Board().apply {
            reset(
                squares = squares,
                sideToMove = side,
                castling = castling,
                epSquare = ep,
                halfmoveClock = parts.getOrNull(4)?.toIntOrNull() ?: 0,
                fullmove = parts.getOrNull(5)?.toIntOrNull() ?: 1,
            )
        }
    }

    fun of(board: Board): String {
        val rows = (7 downTo 0).joinToString("/") { rank ->
            val row = StringBuilder()
            var empty = 0
            for (file in 0..7) {
                val piece = board.squares[Chess.square(file, rank)]
                if (piece == Chess.EMPTY) {
                    empty++
                } else {
                    if (empty > 0) {
                        row.append(empty)
                        empty = 0
                    }
                    val symbol = PIECES[Chess.typeOf(piece) - 1]
                    row.append(if (piece > 0) symbol.uppercaseChar() else symbol)
                }
            }
            if (empty > 0) row.append(empty)
            row.toString()
        }

        val rights = buildString {
            if (board.castling and Chess.WHITE_KING_SIDE != 0) append('K')
            if (board.castling and Chess.WHITE_QUEEN_SIDE != 0) append('Q')
            if (board.castling and Chess.BLACK_KING_SIDE != 0) append('k')
            if (board.castling and Chess.BLACK_QUEEN_SIDE != 0) append('q')
        }.ifEmpty { "-" }

        val ep = if (board.epSquare == Chess.NO_SQUARE) "-" else Chess.squareName(board.epSquare)
        val side = if (board.sideToMove == Chess.WHITE) "w" else "b"
        return "$rows $side $rights $ep ${board.halfmoveClock} ${board.fullmove}"
    }
}

/**
 * Counts the leaf nodes at a given depth.
 *
 * The published counts for standard positions are exact, so this is a complete test of move
 * generation: an engine that invents one move or misses one comes out with a different number,
 * and no amount of playing it by hand would find the same bugs.
 */
object Perft {

    fun count(board: Board, depth: Int): Long {
        if (depth == 0) return 1L
        val moves = board.legalMoves()
        if (depth == 1) return moves.size.toLong()

        var nodes = 0L
        for (move in moves) {
            board.make(move)
            nodes += count(board, depth - 1)
            board.unmake()
        }
        return nodes
    }

    /**
     * Node counts per first move.
     *
     * The standard way to find *which* move is generated wrongly: compare this against a known
     * engine's divide and the disagreement points straight at the rule that is broken, instead
     * of leaving a single wrong total to be bisected by hand.
     */
    fun divide(board: Board, depth: Int): Map<String, Long> {
        val result = LinkedHashMap<String, Long>()
        for (move in board.legalMoves()) {
            board.make(move)
            result[Chess.moveName(move)] = count(board, depth - 1)
            board.unmake()
        }
        return result
    }
}
