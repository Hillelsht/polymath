package com.hillelsht.smart.domain.play.chains

/** How a guess landed, which is the whole feedback the player gets. */
enum class Verdict { NONE, SOLVED, ONE_AWAY, WRONG, ALREADY_TRIED }

data class ChainsState(
    val puzzle: ChainsPuzzle,
    val solved: List<ChainsGroup> = emptyList(),
    val selected: Set<String> = emptySet(),
    val mistakes: Int = 0,
    val verdict: Verdict = Verdict.NONE,
    /** Every combination already submitted, so the same wrong guess is not punished twice. */
    val attempts: Set<Set<String>> = emptySet(),
) {
    val won: Boolean get() = solved.size == ChainsPuzzle.GROUP_COUNT
    val lost: Boolean get() = mistakes >= ChainsRules.MAX_MISTAKES && !won
    val over: Boolean get() = won || lost

    /** Tiles still on the board, in their published order. */
    val remaining: List<String>
        get() {
            val taken = solved.flatMap { it.members }.toSet()
            return puzzle.tiles.filterNot { it in taken }
        }
}

/**
 * The rules of a daily grid.
 *
 * Four mistakes, four groups, and one deliberate kindness: a guess with three of a group right is
 * told so. Without it the format is opaque — a near miss and a wild stab cost exactly the same,
 * so the player learns nothing from being wrong and the puzzle stops being solvable by reasoning.
 */
object ChainsRules {

    const val MAX_MISTAKES = 4

    fun start(puzzle: ChainsPuzzle) = ChainsState(puzzle)

    /** Taps a tile. Selecting past a full group is ignored rather than silently swapping one out. */
    fun select(state: ChainsState, tile: String): ChainsState {
        if (state.over || tile !in state.remaining) return state
        val selected = when {
            tile in state.selected -> state.selected - tile
            state.selected.size >= ChainsPuzzle.GROUP_SIZE -> return state
            else -> state.selected + tile
        }
        return state.copy(selected = selected, verdict = Verdict.NONE)
    }

    fun clear(state: ChainsState): ChainsState =
        state.copy(selected = emptySet(), verdict = Verdict.NONE)

    /**
     * Commits the current selection.
     *
     * Repeating a guess already made costs nothing. Being punished a second time for a
     * combination you have already been told is wrong is not difficulty, it is bookkeeping.
     */
    fun submit(state: ChainsState): ChainsState {
        if (state.over || state.selected.size != ChainsPuzzle.GROUP_SIZE) return state
        if (state.selected in state.attempts) return state.copy(verdict = Verdict.ALREADY_TRIED)

        // Wrapped deliberately: `attempts + selected` would resolve to the element-wise overload
        // and splice the four tile names in individually rather than recording one guess.
        val attempts = state.attempts + setOf(state.selected)
        val match = state.puzzle.groups.firstOrNull { it.members.toSet() == state.selected }
        if (match != null) {
            return state.copy(
                solved = state.solved + match,
                selected = emptySet(),
                verdict = Verdict.SOLVED,
                attempts = attempts,
            )
        }

        val closest = state.puzzle.groups.maxOf { group ->
            state.selected.count { it in group.members }
        }
        return state.copy(
            mistakes = state.mistakes + 1,
            verdict = if (closest == ChainsPuzzle.GROUP_SIZE - 1) Verdict.ONE_AWAY else Verdict.WRONG,
            selected = state.selected,
            attempts = attempts,
        )
    }

    /**
     * Lays out the answer once the run is over, solved groups first.
     *
     * A loss still shows the whole grid. Withholding the answer from someone who has spent four
     * guesses on it teaches them nothing and is the surest way to stop them coming back tomorrow.
     */
    fun reveal(state: ChainsState): List<ChainsGroup> {
        val solvedIds = state.solved.map { it.id }.toSet()
        return state.solved + state.puzzle.groups
            .filterNot { it.id in solvedIds }
            .sortedBy { it.difficulty }
    }

    /** Score for a finished grid: perfect is worth most, and finishing at all is worth something. */
    fun score(state: ChainsState): Int {
        if (!state.won) return state.solved.size * 100
        return 1_000 - state.mistakes * 150
    }
}
