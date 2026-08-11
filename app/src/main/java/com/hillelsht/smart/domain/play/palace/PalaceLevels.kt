package com.hillelsht.smart.domain.play.palace

import com.hillelsht.smart.domain.model.Category

/**
 * The palace's authored levels.
 *
 * Plain Kotlin, the same way [com.hillelsht.smart.domain.play.climb.Relics]' fallback roster is
 * — hand-built rather than generated, because a jump gap that is actually reachable and a
 * collapse timer that is actually survivable are level-design judgement calls, not something a
 * generator can be trusted with on the first pass.
 */
object PalaceLevels {

    /**
     * A pit that needs an actual leap, a floor that punishes lingering, a ledge that has to be
     * grabbed rather than walked onto, and two gates asking from different corners of the
     * library — one lap through every mechanic [PalacePhysics] and [PalaceRules] provide.
     */
    val theHall = PalaceLevel(
        id = "the-hall",
        title = "The Long Hall",
        categoryId = null,
        platforms = listOf(
            Platform("floor1", x0 = 0f, x1 = 300f, y = 0f),
            Platform("floor2", x0 = 420f, x1 = 650f, y = 0f),
            Platform("collapse1", x0 = 650f, x1 = 750f, y = 0f, collapsing = true),
            Platform("floor3", x0 = 750f, x1 = 1_100f, y = 0f),
            Platform("floorB", x0 = 1_150f, x1 = 1_700f, y = 30f),
        ),
        ledges = listOf(Ledge(platformId = "floorB", x = 1_150f, y = 30f)),
        gates = listOf(
            Gate("gate1", x = 900f, categoryId = Category.GEOGRAPHY.id),
            Gate("gate2", x = 1_300f, categoryId = Category.SCIENCE.id),
        ),
        startX = 0f,
        startY = 0f,
        goalX = 1_650f,
        deathY = 400f,
    )

    val all = listOf(theHall)
}
