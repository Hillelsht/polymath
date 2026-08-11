package com.hillelsht.smart.domain.play

/**
 * The Play tab's catalogue.
 *
 * Ids are persisted in `game_runs` and `game_progress` and published in `packs/play/`, so
 * **[id] is a wire format**: renaming one orphans every score and unlock a player has earned.
 * The display strings can change freely.
 */
enum class GameId(
    val id: String,
    val title: String,
    val blurb: String,
    /** Whether it is one grid a day rather than something you can play until you drop. */
    val daily: Boolean = false,
) {
    CLIMB(
        id = "climb",
        title = "The Climb",
        blurb = "Fight your way up a tower with what you know. Collect relics, push your luck.",
    ),
    CHAINS(
        id = "chains",
        title = "Chains",
        blurb = "Sixteen tiles, four hidden groups. One grid a day, the same one for everybody.",
        daily = true,
    ),
    QUIZ(
        id = "quiz",
        title = "Quiz",
        blurb = "A quick round on what you have been learning.",
    ),
    ;

    companion object {
        fun fromId(id: String): GameId? = entries.firstOrNull { it.id == id }
    }
}
