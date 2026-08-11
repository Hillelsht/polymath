package com.hillelsht.smart.domain.play.chains

/**
 * One hidden group of four, and what they have in common.
 *
 * [difficulty] orders the reveal from most obvious to most devious, which is what gives a grid
 * its shape: the easy group is the foothold, the hard one is the reason the grid was worth
 * setting.
 */
data class ChainsGroup(
    val id: String,
    val label: String,
    val members: List<String>,
    val difficulty: Int,
)

/**
 * A day's grid: sixteen tiles concealing four groups of four.
 *
 * The tile order is **published rather than shuffled on the device**, because the whole appeal of
 * a daily puzzle is that it is the same one everybody else got. A device that shuffled locally
 * would be playing a different puzzle to the person it is being compared with.
 */
data class ChainsPuzzle(
    val date: String,
    val groups: List<ChainsGroup>,
    val tiles: List<String>,
) {

    /**
     * Everything that would make this grid unplayable, named.
     *
     * The one that matters is the last: **a tile belonging to two groups has no single right
     * answer**, and a solver who spots the wrong-but-defensible grouping is told they are wrong
     * by a puzzle that is itself wrong. It is the defining failure of this format, it cannot be
     * seen by looking at a grid, and it is why the generator proves the property rather than
     * trusting it.
     */
    fun problems(): List<String> {
        val problems = mutableListOf<String>()
        if (groups.size != GROUP_COUNT) {
            problems += "has ${groups.size} groups, expected $GROUP_COUNT"
        }
        groups.filter { it.members.size != GROUP_SIZE }.forEach {
            problems += "group '${it.label}' has ${it.members.size} members, expected $GROUP_SIZE"
        }
        if (tiles.size != GROUP_COUNT * GROUP_SIZE) {
            problems += "has ${tiles.size} tiles, expected ${GROUP_COUNT * GROUP_SIZE}"
        }
        if (tiles.distinct().size != tiles.size) {
            problems += "repeats a tile"
        }

        val members = groups.flatMap { it.members }
        if (members.toSet() != tiles.toSet()) {
            problems += "the tiles and the groups do not describe the same sixteen things"
        }
        members.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach {
            problems += "'$it' belongs to more than one group, so the grid has no single solution"
        }
        return problems
    }

    fun groupOf(tile: String): ChainsGroup? = groups.firstOrNull { tile in it.members }

    companion object {
        const val GROUP_COUNT = 4
        const val GROUP_SIZE = 4
    }
}
