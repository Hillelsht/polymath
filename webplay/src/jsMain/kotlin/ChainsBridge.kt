@file:OptIn(ExperimentalJsExport::class)

package com.hillelsht.smart.domain.play.chains

/**
 * The browser's entire surface onto a daily grid.
 *
 * Same discipline as `Bridge.kt`: everything crossing the boundary is a number, a boolean or a
 * string, because `@JsExport` cannot carry Kotlin collections or data classes — and because the
 * alternative is letting a second platform's needs leak back into `domain/play/chains`, which
 * ships to the phone.
 *
 * The grid arrives through [addTile] and [addGroup] rather than as parsed JSON. That is deliberate:
 * it means the page hands over sixteen strings and four groups and nothing else, so a malformed
 * pack is caught by [begin] — the same [ChainsPuzzle.problems] the content pipeline runs — instead
 * of by a rendering glitch halfway through someone's daily.
 */
@JsExport
class ChainsSession(private val date: String) {

    private val tiles = mutableListOf<String>()
    private val groups = mutableListOf<ChainsGroup>()
    private var game: ChainsState? = null

    private val state: ChainsState
        get() = game ?: error("begin() has not been called, or the grid was rejected")

    // --- building the day's grid ----------------------------------------------------------------

    fun addTile(tile: String) {
        tiles += tile
    }

    fun addGroup(id: String, label: String, difficulty: Int, a: String, b: String, c: String, d: String) {
        groups += ChainsGroup(id, label, listOf(a, b, c, d), difficulty)
    }

    /**
     * Starts the grid, returning `""` when it is sound and every reason it is not otherwise.
     *
     * A grid where one tile fits two groups has no single right answer, and a solver who spots the
     * wrong-but-defensible grouping is told they are wrong by a puzzle that is itself wrong. It
     * cannot be seen by looking, so it is proved here before a single tile is drawn.
     */
    fun begin(): String {
        val puzzle = ChainsPuzzle(date, groups.toList(), tiles.toList())
        val problems = puzzle.problems()
        if (problems.isNotEmpty()) return problems.joinToString("\n")
        game = ChainsRules.start(puzzle)
        return ""
    }

    // --- playing --------------------------------------------------------------------------------

    fun select(tile: String) {
        game = ChainsRules.select(state, tile)
    }

    fun clear() {
        game = ChainsRules.clear(state)
    }

    fun submit() {
        game = ChainsRules.submit(state)
    }

    /** Rebuilds a part-played grid from its saved guesses. See [ChainsState.guesses]. */
    fun replay(guess: Array<String>) {
        clear()
        guess.forEach { select(it) }
        submit()
    }

    // --- what the page draws ---------------------------------------------------------------------

    val verdict: String get() = state.verdict.name
    val mistakes: Int get() = state.mistakes
    val mistakesLeft: Int get() = ChainsRules.MAX_MISTAKES - state.mistakes
    val won: Boolean get() = state.won
    val lost: Boolean get() = state.lost
    val over: Boolean get() = state.over
    val score: Int get() = ChainsRules.score(state)

    val selectedCount: Int get() = state.selected.size
    fun isSelected(tile: String): Boolean = tile in state.selected

    val tileCount: Int get() = state.remaining.size
    fun tileAt(i: Int): String = state.remaining[i]

    /**
     * The stack of groups above the board: what has been found, and — once the run is over — the
     * whole answer, found or not.
     *
     * One accessor family rather than two because that *is* the rule: withholding the solution
     * from someone who has just spent four guesses on it teaches them nothing and is the surest
     * way to stop them coming back tomorrow.
     */
    private val rows: List<ChainsGroup>
        get() = if (state.over) ChainsRules.reveal(state) else state.solved

    val rowCount: Int get() = rows.size
    fun rowLabel(i: Int): String = rows[i].label
    fun rowDifficulty(i: Int): Int = rows[i].difficulty
    fun rowMember(i: Int, j: Int): String = rows[i].members[j]

    /** True for a row the solver never found — drawn differently, because it is not a win. */
    fun rowMissed(i: Int): Boolean = rows[i].id !in state.solved.map { it.id }

    // --- the shareable result ---------------------------------------------------------------------

    val guessCount: Int get() = state.guesses.size
    fun guessTile(g: Int, t: Int): String = state.guesses[g][t]

    /**
     * Which group the tile in a given guess actually belonged to, 1–4, or 0 if it belonged to none
     * — the one number a shared result grid is made of. The share is coloured by the truth rather
     * than by what the solver believed, which is what makes a near miss legible to a reader.
     */
    fun guessTileDifficulty(g: Int, t: Int): Int =
        state.puzzle.groupOf(state.guesses[g][t])?.difficulty ?: 0

    companion object {
        val maxMistakes: Int get() = ChainsRules.MAX_MISTAKES
        val groupCount: Int get() = ChainsPuzzle.GROUP_COUNT
        val groupSize: Int get() = ChainsPuzzle.GROUP_SIZE
    }
}
