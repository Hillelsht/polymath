package com.hillelsht.smart.domain.play.chess

/**
 * The rules of chess.
 *
 * A 0x88 board: 128 squares laid out as `rank * 16 + file`, of which the right-hand half is
 * off the board. The trick that buys is bounds checking without a lookup — `square and 0x88`
 * is non-zero for exactly the squares that do not exist, so a knight sliding off the edge is
 * caught by an `and` rather than by four comparisons. Bitboards would be faster; this has to be
 * *correct* first, and it is answering a phone's questions, not a supercomputer's.
 *
 * Correctness here is not a matter of opinion. `perft` counts the leaf nodes at a given depth
 * and the counts for standard positions are published to the node, so an engine that generates
 * one illegal move or misses one legal one comes out with a different number. That is the whole
 * reason chess was scheduled early among the games: it is the one that can be proved right from
 * a machine that cannot run the app.
 */
object Chess {

    const val WHITE = 1
    const val BLACK = -1

    const val EMPTY = 0
    const val PAWN = 1
    const val KNIGHT = 2
    const val BISHOP = 3
    const val ROOK = 4
    const val QUEEN = 5
    const val KING = 6

    /** Castling rights, one bit each. */
    const val WHITE_KING_SIDE = 1
    const val WHITE_QUEEN_SIDE = 2
    const val BLACK_KING_SIDE = 4
    const val BLACK_QUEEN_SIDE = 8

    const val NO_SQUARE = -1

    val KNIGHT_OFFSETS = intArrayOf(33, 31, 18, 14, -33, -31, -18, -14)
    val BISHOP_OFFSETS = intArrayOf(17, 15, -17, -15)
    val ROOK_OFFSETS = intArrayOf(16, 1, -16, -1)
    val KING_OFFSETS = intArrayOf(17, 16, 15, 1, -17, -16, -15, -1)

    fun onBoard(square: Int): Boolean = square and 0x88 == 0

    fun square(file: Int, rank: Int): Int = rank * 16 + file

    fun fileOf(square: Int): Int = square and 7

    fun rankOf(square: Int): Int = square shr 4

    fun colourOf(piece: Int): Int = if (piece > 0) WHITE else if (piece < 0) BLACK else 0

    fun typeOf(piece: Int): Int = if (piece < 0) -piece else piece

    // Named apart rather than overloaded: a square and a move are both Int here, so `name(Int)`
    // twice is the same signature twice and will not compile.
    fun squareName(square: Int): String = "${'a' + fileOf(square)}${rankOf(square) + 1}"

    /** "e2e4", "e7e8q" — the coordinate notation used for tests and for saving a game. */
    fun moveName(move: Int): String {
        val promotion = Move.promotion(move)
        val suffix = if (promotion == EMPTY) "" else "nbrq"[promotion - KNIGHT].toString()
        return squareName(Move.from(move)) + squareName(Move.to(move)) + suffix
    }
}

/**
 * A move packed into an `Int`.
 *
 * Packed rather than a data class because perft allocates one object per move per node, and at
 * five ply from the opening that is nearly five million of them. The accessors below cost
 * nothing and keep the call sites readable.
 */
object Move {

    const val FLAG_EN_PASSANT = 1
    const val FLAG_CASTLE = 2
    const val FLAG_DOUBLE_PUSH = 4

    fun of(from: Int, to: Int, promotion: Int = Chess.EMPTY, flags: Int = 0): Int =
        from or (to shl 8) or (promotion shl 16) or (flags shl 20)

    fun from(move: Int): Int = move and 0xFF

    fun to(move: Int): Int = (move shr 8) and 0xFF

    fun promotion(move: Int): Int = (move shr 16) and 0xF

    fun flags(move: Int): Int = (move shr 20) and 0xF

    fun isEnPassant(move: Int): Boolean = flags(move) and FLAG_EN_PASSANT != 0

    fun isCastle(move: Int): Boolean = flags(move) and FLAG_CASTLE != 0

    fun isDoublePush(move: Int): Boolean = flags(move) and FLAG_DOUBLE_PUSH != 0
}

/**
 * A position, and the moves out of it.
 *
 * Mutable with make/unmake rather than persistent-and-copied. A search visits far more positions
 * than it keeps, and copying a 128-square array at every node is the difference between an
 * engine that thinks and one that stutters.
 */
class Board {

    val squares = IntArray(128)
    var sideToMove = Chess.WHITE
        private set
    var castling = 0
        private set
    var epSquare = Chess.NO_SQUARE
        private set
    var halfmoveClock = 0
        private set
    var fullmove = 1
        private set

    private class Undo(
        val move: Int,
        val captured: Int,
        val castling: Int,
        val epSquare: Int,
        val halfmoveClock: Int,
        val fullmove: Int,
    )

    private val history = ArrayList<Undo>(256)

    /** How many plies have been played onto this board and can still be taken back. */
    val ply: Int get() = history.size

    fun reset(
        squares: IntArray,
        sideToMove: Int,
        castling: Int,
        epSquare: Int,
        halfmoveClock: Int,
        fullmove: Int,
    ) {
        squares.copyInto(this.squares)
        this.sideToMove = sideToMove
        this.castling = castling
        this.epSquare = epSquare
        this.halfmoveClock = halfmoveClock
        this.fullmove = fullmove
        history.clear()
    }

    fun copy(): Board = Board().also {
        it.reset(squares, sideToMove, castling, epSquare, halfmoveClock, fullmove)
    }

    fun kingSquare(side: Int): Int {
        val king = Chess.KING * side
        for (square in 0..119) {
            if (Chess.onBoard(square) && squares[square] == king) return square
        }
        return Chess.NO_SQUARE
    }

    /**
     * Whether [side] attacks [square].
     *
     * Asked of the square the king stands on to find check, and of the squares a castling king
     * crosses — a king may not castle out of, through, or into check, and forgetting the
     * "through" is the classic way to generate an illegal move that perft catches instantly.
     */
    fun isAttacked(square: Int, side: Int): Boolean {
        // Pawns attack forwards, so look backwards from the target square.
        val pawnDirection = -16 * side
        for (offset in intArrayOf(pawnDirection - 1, pawnDirection + 1)) {
            val from = square + offset
            if (Chess.onBoard(from) && squares[from] == Chess.PAWN * side) return true
        }

        for (offset in Chess.KNIGHT_OFFSETS) {
            val from = square + offset
            if (Chess.onBoard(from) && squares[from] == Chess.KNIGHT * side) return true
        }

        for (offset in Chess.KING_OFFSETS) {
            val from = square + offset
            if (Chess.onBoard(from) && squares[from] == Chess.KING * side) return true
        }

        for (offset in Chess.BISHOP_OFFSETS) {
            if (slidingAttack(square, offset, side, Chess.BISHOP)) return true
        }
        for (offset in Chess.ROOK_OFFSETS) {
            if (slidingAttack(square, offset, side, Chess.ROOK)) return true
        }
        return false
    }

    private fun slidingAttack(square: Int, offset: Int, side: Int, piece: Int): Boolean {
        var current = square + offset
        while (Chess.onBoard(current)) {
            val occupant = squares[current]
            if (occupant != Chess.EMPTY) {
                if (Chess.colourOf(occupant) != side) return false
                val type = Chess.typeOf(occupant)
                return type == piece || type == Chess.QUEEN
            }
            current += offset
        }
        return false
    }

    fun inCheck(side: Int = sideToMove): Boolean {
        val king = kingSquare(side)
        return king != Chess.NO_SQUARE && isAttacked(king, -side)
    }

    /**
     * Every legal move for the side to move.
     *
     * Generated pseudo-legally and then filtered by playing each one and asking whether the
     * king is left attacked. Slower than computing pins up front and far harder to get wrong —
     * pins, discovered checks and the en-passant capture that exposes a rank all fall out of it
     * for free, and the last of those is the single most commonly botched rule in chess engines.
     */
    fun legalMoves(): IntArray {
        val pseudo = pseudoLegalMoves()
        var count = 0
        val legal = IntArray(pseudo.size)
        for (move in pseudo) {
            make(move)
            if (!inCheck(-sideToMove)) legal[count++] = move
            unmake()
        }
        return legal.copyOf(count)
    }

    fun pseudoLegalMoves(): IntArray {
        val moves = IntArray(256)
        var count = 0

        fun add(move: Int) {
            moves[count++] = move
        }

        for (from in 0..119) {
            if (!Chess.onBoard(from)) continue
            val piece = squares[from]
            if (piece == Chess.EMPTY || Chess.colourOf(piece) != sideToMove) continue

            when (Chess.typeOf(piece)) {
                Chess.PAWN -> count = pawnMoves(from, moves, count)

                Chess.KNIGHT -> for (offset in Chess.KNIGHT_OFFSETS) {
                    val to = from + offset
                    if (Chess.onBoard(to) && Chess.colourOf(squares[to]) != sideToMove) {
                        add(Move.of(from, to))
                    }
                }

                Chess.KING -> for (offset in Chess.KING_OFFSETS) {
                    val to = from + offset
                    if (Chess.onBoard(to) && Chess.colourOf(squares[to]) != sideToMove) {
                        add(Move.of(from, to))
                    }
                }

                Chess.BISHOP -> count = slide(from, Chess.BISHOP_OFFSETS, moves, count)
                Chess.ROOK -> count = slide(from, Chess.ROOK_OFFSETS, moves, count)
                Chess.QUEEN -> count = slide(from, Chess.KING_OFFSETS, moves, count)
            }
        }

        count = castlingMoves(moves, count)
        return moves.copyOf(count)
    }

    private fun slide(from: Int, offsets: IntArray, moves: IntArray, start: Int): Int {
        var count = start
        for (offset in offsets) {
            var to = from + offset
            while (Chess.onBoard(to)) {
                val occupant = squares[to]
                if (occupant == Chess.EMPTY) {
                    moves[count++] = Move.of(from, to)
                } else {
                    if (Chess.colourOf(occupant) != sideToMove) moves[count++] = Move.of(from, to)
                    break
                }
                to += offset
            }
        }
        return count
    }

    private fun pawnMoves(from: Int, moves: IntArray, start: Int): Int {
        var count = start
        val forward = 16 * sideToMove
        val startRank = if (sideToMove == Chess.WHITE) 1 else 6
        val lastRank = if (sideToMove == Chess.WHITE) 7 else 0

        fun addPawn(to: Int, flags: Int = 0) {
            if (Chess.rankOf(to) == lastRank) {
                for (promotion in intArrayOf(Chess.QUEEN, Chess.ROOK, Chess.BISHOP, Chess.KNIGHT)) {
                    moves[count++] = Move.of(from, to, promotion, flags)
                }
            } else {
                moves[count++] = Move.of(from, to, Chess.EMPTY, flags)
            }
        }

        val ahead = from + forward
        if (Chess.onBoard(ahead) && squares[ahead] == Chess.EMPTY) {
            addPawn(ahead)
            val twoAhead = ahead + forward
            if (Chess.rankOf(from) == startRank && squares[twoAhead] == Chess.EMPTY) {
                moves[count++] = Move.of(from, twoAhead, Chess.EMPTY, Move.FLAG_DOUBLE_PUSH)
            }
        }

        for (offset in intArrayOf(forward - 1, forward + 1)) {
            val to = from + offset
            if (!Chess.onBoard(to)) continue
            val occupant = squares[to]
            if (occupant != Chess.EMPTY && Chess.colourOf(occupant) != sideToMove) {
                addPawn(to)
            } else if (to == epSquare && occupant == Chess.EMPTY) {
                moves[count++] = Move.of(from, to, Chess.EMPTY, Move.FLAG_EN_PASSANT)
            }
        }
        return count
    }

    private fun castlingMoves(moves: IntArray, start: Int): Int {
        var count = start
        val backRank = if (sideToMove == Chess.WHITE) 0 else 7
        val king = Chess.square(4, backRank)
        if (squares[king] != Chess.KING * sideToMove) return count
        // Castling out of check is illegal, and unlike the other two conditions this one is not
        // caught by the legality filter, which only looks at where the king ends up.
        if (isAttacked(king, -sideToMove)) return count

        val kingSide = if (sideToMove == Chess.WHITE) Chess.WHITE_KING_SIDE else Chess.BLACK_KING_SIDE
        val queenSide = if (sideToMove == Chess.WHITE) Chess.WHITE_QUEEN_SIDE else Chess.BLACK_QUEEN_SIDE

        if (castling and kingSide != 0 &&
            squares[king + 1] == Chess.EMPTY && squares[king + 2] == Chess.EMPTY &&
            squares[king + 3] == Chess.ROOK * sideToMove &&
            !isAttacked(king + 1, -sideToMove)
        ) {
            moves[count++] = Move.of(king, king + 2, Chess.EMPTY, Move.FLAG_CASTLE)
        }

        if (castling and queenSide != 0 &&
            squares[king - 1] == Chess.EMPTY && squares[king - 2] == Chess.EMPTY &&
            squares[king - 3] == Chess.EMPTY &&
            squares[king - 4] == Chess.ROOK * sideToMove &&
            !isAttacked(king - 1, -sideToMove)
        ) {
            moves[count++] = Move.of(king, king - 2, Chess.EMPTY, Move.FLAG_CASTLE)
        }
        return count
    }

    fun make(move: Int) {
        val from = Move.from(move)
        val to = Move.to(move)
        val piece = squares[from]
        val captured = if (Move.isEnPassant(move)) {
            squares[to - 16 * sideToMove]
        } else {
            squares[to]
        }

        history.add(Undo(move, captured, castling, epSquare, halfmoveClock, fullmove))

        squares[to] = piece
        squares[from] = Chess.EMPTY

        if (Move.isEnPassant(move)) squares[to - 16 * sideToMove] = Chess.EMPTY

        val promotion = Move.promotion(move)
        if (promotion != Chess.EMPTY) squares[to] = promotion * sideToMove

        if (Move.isCastle(move)) {
            // The rook hops the king. Which rook is decided by which way the king went.
            if (to > from) {
                squares[to - 1] = squares[to + 1]
                squares[to + 1] = Chess.EMPTY
            } else {
                squares[to + 1] = squares[to - 2]
                squares[to - 2] = Chess.EMPTY
            }
        }

        // One rank is 16 on a 0x88 board, not 8. Written as 8 first, which put the en passant
        // square off the board every time, so en passant was never generated at all — worth 258
        // missing moves at five ply from the opening.
        epSquare = if (Move.isDoublePush(move)) from + 16 * sideToMove else Chess.NO_SQUARE

        // Rights are lost by moving the king or a rook, and also by *capturing* a rook on its
        // home square — the side that never moved still loses the right, which is easy to miss.
        castling = castling and rightsMask(from) and rightsMask(to)

        halfmoveClock =
            if (Chess.typeOf(piece) == Chess.PAWN || captured != Chess.EMPTY) 0 else halfmoveClock + 1
        if (sideToMove == Chess.BLACK) fullmove++
        sideToMove = -sideToMove
    }

    fun unmake() {
        val undo = history.removeAt(history.size - 1)
        sideToMove = -sideToMove
        val move = undo.move
        val from = Move.from(move)
        val to = Move.to(move)

        val moved = if (Move.promotion(move) != Chess.EMPTY) Chess.PAWN * sideToMove else squares[to]
        squares[from] = moved
        squares[to] = Chess.EMPTY

        if (Move.isEnPassant(move)) {
            squares[to - 16 * sideToMove] = undo.captured
        } else {
            squares[to] = undo.captured
        }

        if (Move.isCastle(move)) {
            if (to > from) {
                squares[to + 1] = squares[to - 1]
                squares[to - 1] = Chess.EMPTY
            } else {
                squares[to - 2] = squares[to + 1]
                squares[to + 1] = Chess.EMPTY
            }
        }

        castling = undo.castling
        epSquare = undo.epSquare
        halfmoveClock = undo.halfmoveClock
        fullmove = undo.fullmove
    }

    /** Which rights survive a piece leaving or landing on this square. */
    private fun rightsMask(square: Int): Int = when (square) {
        Chess.square(4, 0) -> (Chess.WHITE_KING_SIDE or Chess.WHITE_QUEEN_SIDE).inv()
        Chess.square(7, 0) -> Chess.WHITE_KING_SIDE.inv()
        Chess.square(0, 0) -> Chess.WHITE_QUEEN_SIDE.inv()
        Chess.square(4, 7) -> (Chess.BLACK_KING_SIDE or Chess.BLACK_QUEEN_SIDE).inv()
        Chess.square(7, 7) -> Chess.BLACK_KING_SIDE.inv()
        Chess.square(0, 7) -> Chess.BLACK_QUEEN_SIDE.inv()
        else -> -1
    }

    /** Nobody to move and in check: mate. Nobody to move and safe: stalemate. */
    fun isCheckmate(): Boolean = legalMoves().isEmpty() && inCheck()

    fun isStalemate(): Boolean = legalMoves().isEmpty() && !inCheck()

    /**
     * Whether the position cannot be won by anybody.
     *
     * King against king, and king against a single minor piece. A game the engine keeps playing
     * after the last pawn is gone and nothing can mate is not a hard game, it is a broken one.
     */
    fun isInsufficientMaterial(): Boolean {
        var minors = 0
        for (square in 0..119) {
            if (!Chess.onBoard(square)) continue
            when (Chess.typeOf(squares[square])) {
                Chess.EMPTY, Chess.KING -> Unit
                Chess.KNIGHT, Chess.BISHOP -> minors++
                else -> return false
            }
        }
        return minors <= 1
    }
}
