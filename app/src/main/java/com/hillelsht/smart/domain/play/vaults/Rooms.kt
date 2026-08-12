package com.hillelsht.smart.domain.play.vaults

/**
 * The descent, in Kotlin.
 *
 * These will move to `packs/play/vaults/` as authored JSON once the mechanics settle — a room is
 * content, and content should ship without an app release. They are code for now on purpose: the
 * geometry is still moving while the physics is being tuned, and there is no sense building a
 * publishing pipeline around numbers that change every hour.
 * [Relics][com.hillelsht.smart.domain.play.climb.Relics] made the same trade.
 *
 * Every room is gated by `RoomsTest` on [Playtest.MIN_MARGIN_FRAMES], so one demanding
 * frame-perfect timing fails the build rather than reaching a player.
 *
 * The order is a teaching order. Each room introduces exactly one idea and then the next room
 * assumes it: jump, then height, then *keep moving*, then rhythm, then rhythm under pressure.
 */
object Rooms {

    /** One gap, one jump, a long runway to reach full speed. The only verb that matters. */
    val threshold = Room(
        id = "threshold",
        ledges = listOf(
            Ledge("near", 0.0, 200.0, 0.0),
            // Wider than a walk-off can catch (see Playtest.walkOffGrabReach) so the room really
            // does teach the jump, and comfortably inside the 96-unit arc so it teaches it kindly.
            Ledge("far", 276.0, 620.0, 0.0),
        ),
        spawnX = 20.0, spawnY = 0.0, exitX = 600.0, abyssY = 400.0,
        epigraph = "The stair ends. The vault begins.",
    )

    /** The far side sits lower: height is a resource, not only a hazard. */
    val stepDown = Room(
        id = "step-down",
        ledges = listOf(
            Ledge("high", 0.0, 220.0, 0.0),
            Ledge("low", 300.0, 640.0, 64.0),
        ),
        spawnX = 20.0, spawnY = 0.0, exitX = 620.0, abyssY = 400.0,
        epigraph = "Down is easier than across.",
    )

    /**
     * Loose stones. Standing still is the mistake — the floor keeps faith only while you move.
     *
     * Three short collapsing spans rather than one long one, so hesitating costs you a single
     * tile and a rethink rather than the whole room.
     */
    val looseStones = Room(
        id = "loose-stones",
        ledges = listOf(
            Ledge("sill", 0.0, 150.0, 0.0),
            Ledge("loose-a", 150.0, 250.0, 0.0, collapsing = true),
            Ledge("loose-b", 250.0, 350.0, 0.0, collapsing = true),
            Ledge("loose-c", 350.0, 450.0, 0.0, collapsing = true),
            Ledge("far", 450.0, 640.0, 0.0),
        ),
        spawnX = 20.0, spawnY = 0.0, exitX = 620.0, abyssY = 400.0,
        epigraph = "The floor keeps faith only while you move.",
    )

    /** The first blade. A long approach, so the cycle can be read before it has to be beaten. */
    val firstBlade = Room(
        id = "first-blade",
        ledges = listOf(Ledge("floor", 0.0, 640.0, 0.0)),
        chompers = listOf(
            Chomper("blade-1", x = 330.0, y = 0.0, periodFrames = 96, openFrames = 34),
        ),
        spawnX = 20.0, spawnY = 0.0, exitX = 620.0, abyssY = 400.0,
        epigraph = "It keeps time. So can you.",
    )

    /** Two blades, deliberately out of step, so one rhythm will not carry you through both. */
    val twoBeats = Room(
        id = "two-beats",
        ledges = listOf(Ledge("floor", 0.0, 720.0, 0.0)),
        chompers = listOf(
            Chomper("blade-1", x = 260.0, y = 0.0, periodFrames = 110, openFrames = 44),
            Chomper("blade-2", x = 470.0, y = 0.0, periodFrames = 110, openFrames = 44, phase = 55),
        ),
        spawnX = 20.0, spawnY = 0.0, exitX = 700.0, abyssY = 400.0,
        epigraph = "Two clocks, and neither is yours.",
    )

    /** A blade guarding the near lip of a gap: the rhythm and the leap are the same decision. */
    val theSill = Room(
        id = "the-sill",
        ledges = listOf(
            Ledge("near", 0.0, 260.0, 0.0),
            Ledge("far", 316.0, 660.0, 0.0),
        ),
        chompers = listOf(
            Chomper("blade-1", x = 170.0, y = 0.0, periodFrames = 104, openFrames = 40),
        ),
        spawnX = 20.0, spawnY = 0.0, exitX = 640.0, abyssY = 400.0,
        epigraph = "Past the blade, and then nothing underfoot.",
    )

    /**
     * The Narrow. Everything the descent has taught, arranged so the lessons argue with each other.
     *
     * A blade stands over ground that will not hold you. The safe place to read its rhythm is the
     * sill, *before* committing — once you are on the loose stones, waiting is the one thing you
     * cannot do, and the gap at the far end means the crossing has to end in a jump rather than a
     * skid. Hesitation is the only way to lose, which is the note to end an act on.
     */
    val theNarrow = Room(
        id = "the-narrow",
        ledges = listOf(
            Ledge("sill", 0.0, 150.0, 0.0),
            Ledge("loose-a", 150.0, 260.0, 0.0, collapsing = true),
            Ledge("loose-b", 260.0, 370.0, 0.0, collapsing = true),
            Ledge("far", 404.0, 680.0, 0.0),
        ),
        chompers = listOf(
            Chomper("blade-1", x = 320.0, y = 0.0, periodFrames = 120, openFrames = 42),
        ),
        spawnX = 20.0, spawnY = 0.0, exitX = 660.0, abyssY = 400.0,
        epigraph = "Read it from the sill. There is no reading it from the stones.",
    )

    /** The order they are met in. */
    val all = listOf(threshold, stepDown, looseStones, firstBlade, twoBeats, theSill, theNarrow)
}
