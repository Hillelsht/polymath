package com.hillelsht.smart.domain.play.vaults

import kotlin.math.sqrt

/**
 * Rooms nobody wrote, and the search that decides which of them deserve a day.
 *
 * [Rooms] holds seven hand-built rooms, and a daily that cycles seven rooms is a daily you have
 * already seen by the second week. Generating them is the obvious fix and, on this project's
 * history, the obvious risk: Aryeh's Palace shipped one level nobody could play, so shipping
 * thousands of rooms nobody has played is that mistake with a multiplier on it.
 *
 * What makes it safe is that playability here is already a number. [Playtest.solve] measures how
 * far the deciding input can drift and still get through, so the generator does not have to be
 * clever — it has to be prolific, and then be judged. This file is the prolific half: a small
 * grammar of the ideas the authored rooms teach, laid out left to right from a seed. [curate] is
 * the judging half, and it throws away every candidate whose margin falls outside the band asked
 * for. Nothing reaches a player without a measured margin, which is more than the seven authored
 * rooms could say before `RoomsTest` existed.
 *
 * **The seed is the room.** A published day carries three things — a seed, its margin and the plan
 * that achieved it — and every client rebuilds the geometry from the first of them, exactly as a
 * ghost link carries a run rather than a recording of one. Publishing the geometry itself would be
 * more flexible and much worse: two copies of the same room that can disagree.
 *
 * Kotlin stdlib only, like the rest of this package. It compiles to JavaScript for the browser.
 */
object RoomGen {

    /**
     * The grammar's version, stamped into every published pack.
     *
     * Bump it for any change that moves geometry, and republish in the same commit. The same seed
     * will not mean the same room afterwards, so a pack left behind would serve a room whose
     * published difficulty was measured on a different one — a lie the player would experience as
     * the daily being arbitrary. `DailyRoomsTest` refuses any pack whose version is not this one,
     * which turns that from a thing to remember into a thing that fails the build.
     */
    const val VERSION = 1

    // --- the dice ---------------------------------------------------------------------------

    /**
     * A 32-bit xorshift, written out rather than taken from the standard library.
     *
     * `kotlin.random.Random(seed)` would be shorter, but this has to produce identical values on
     * the JVM that publishes a pack and in the JavaScript that plays it, and "the stdlib's
     * algorithm happens to agree across both today" is not a promise a test can hold onto. Integer
     * arithmetic is exact on both targets, so writing the four lines out makes the guarantee ours.
     */
    private class Dice(seed: Int) {
        private var s: Int =
            (seed * 0x9E3779B9.toInt() + 0x85EBCA6B.toInt()).let { if (it == 0) 0x2545F491 else it }

        fun next(): Int {
            var x = s
            x = x xor (x shl 13)
            x = x xor (x ushr 17)
            x = x xor (x shl 5)
            s = x
            return x
        }

        /** A value in `lo..hi`, inclusive. */
        fun int(lo: Int, hi: Int): Int = lo + (next() ushr 1) % (hi - lo + 1)

        fun <T> pick(from: List<T>): T = from[(next() ushr 1) % from.size]
    }

    /** Scatters an integer, so neighbouring days do not start from neighbouring rooms. */
    private fun mix(v: Int): Int {
        var x = v * 0x9E3779B9.toInt()
        x = x xor (x ushr 16)
        x *= 0x7FEB352D
        x = x xor (x ushr 15)
        return x
    }

    // --- what the numbers can actually be -----------------------------------------------------
    //
    // Two measurements shape this whole file, and both come from the shipped tuning rather than
    // from taste:
    //
    //   * A jump carries 96 units at full speed, and the ledge-grab already bridges 57 of them
    //     unaided. So a gap that genuinely demands a jump has about 39 units of leeway in where
    //     you leave the ground — ten frames, plus coyote time. **No room containing a real leap
    //     can be more forgiving than about sixteen frames**, however it is arranged.
    //   * A blade's open window is as wide as it is authored to be. Thirty per cent of the cycle
    //     or forty-five is the difference between a room that hurries you and one that does not,
    //     and it moves the margin smoothly between roughly twelve frames and forty.
    //
    // So the blade is the dial and the leap is a fixed cost, which is also true of the authored
    // descent: only `Rooms.threshold` demands a jump the grab cannot cover. Kind days are rhythm
    // rooms; hard days ask for the leap as well. The bands in [bandFor] are set to what those two
    // facts allow, not to a tidy ramp that nothing could satisfy.

    /** One idea, in the order the authored rooms introduce them. */
    private enum class Beat {
        /** A gap wider than the grab can bridge. The room's hardest single moment, when present. */
        LEAP,

        /** A drop with a break narrow enough to run straight off. Altitude, and no input. */
        STEP,

        /** Collapsing stone. Costs nothing in timing and everything in nerve. */
        LOOSE,

        /** A blade over open floor. The difficulty dial. */
        BLADE,
    }

    /**
     * Every arrangement a generated room may take.
     *
     * A table rather than a random walk over [Beat], because a random walk produces rooms that are
     * merely different and this has to produce rooms that are *legible*: one idea at a time, in an
     * order where the second lands on the first. Every entry obeys rules `RoomGenTest` asserts —
     * at most two leaps, since [Playtest.solve] searches two presses and a room needing three would
     * be thrown away only after the expensive part of the search; at most one blade, because two
     * blades out of step is `Rooms.theNarrow`'s job and the end of an act; and at most one run of
     * loose stone, which stops being a decision the second time you meet it in a screen.
     *
     * Roughly half the table is leap-free. That is not squeamishness about jumping — it is the only
     * way the kind end of [bandFor] is reachable at all.
     */
    private val SHAPES: List<List<Beat>> = listOf(
        // Rhythm rooms. The blade decides how kind they are.
        listOf(Beat.BLADE),
        listOf(Beat.LOOSE, Beat.BLADE),
        listOf(Beat.BLADE, Beat.LOOSE),
        listOf(Beat.STEP, Beat.BLADE),
        listOf(Beat.BLADE, Beat.STEP),
        listOf(Beat.LOOSE, Beat.BLADE, Beat.STEP),
        listOf(Beat.STEP, Beat.BLADE, Beat.LOOSE),
        listOf(Beat.BLADE, Beat.STEP, Beat.LOOSE),

        // Rooms that ask for the leap as well.
        listOf(Beat.LEAP),
        listOf(Beat.LEAP, Beat.STEP),
        listOf(Beat.STEP, Beat.LEAP),
        listOf(Beat.LOOSE, Beat.LEAP),
        listOf(Beat.LEAP, Beat.LOOSE),
        listOf(Beat.BLADE, Beat.LEAP),
        listOf(Beat.LEAP, Beat.BLADE),
        listOf(Beat.LEAP, Beat.STEP, Beat.LEAP),
        listOf(Beat.LOOSE, Beat.BLADE, Beat.LEAP),
        listOf(Beat.BLADE, Beat.LOOSE, Beat.LEAP),
        listOf(Beat.STEP, Beat.BLADE, Beat.LEAP),
    )

    /**
     * The most forgiving a room containing a real leap has ever measured, in frames.
     *
     * Not a rule the generator enforces — a consequence of the arithmetic above, confirmed over
     * several hundred sampled rooms and re-confirmed against every published day by
     * `DailyRoomsTest`. [curate] trusts it to skip candidates for free: a band that starts above
     * this can never be satisfied by a room with a leap in it, and a band that ends at or below it
     * is a day that ought to ask for one.
     *
     * Trusting it wrongly costs nothing but variety — a discarded candidate, never a published
     * room outside its band, because the measured margin is still what decides.
     */
    const val LEAP_CEILING = 20

    /** The entrance. Long enough to reach [Tuning.runSpeed] before anything is asked of you. */
    private const val SILL = 150.0

    /** How much of a landing lies beyond the exit line, so clearing a room is not a cliff edge. */
    private const val RUN_OUT = 20.0

    /** The narrowest ledge a room may end on: room to land, and room to stop. */
    private const val VAULT = 150.0

    /**
     * How far a jump carries at full speed while also dropping [drop] units.
     *
     * Derived rather than measured so the grammar tracks [Tuning]: a designer who raises gravity in
     * the browser harness gets narrower gaps out of the generator, instead of gaps that were
     * correct for the old numbers and are now uncrossable.
     */
    private fun reach(tuning: Tuning, drop: Double): Double {
        val v = -tuning.jumpVelocity
        val t = (v + sqrt(v * v + 2.0 * tuning.gravity * drop)) / tuning.gravity
        return tuning.runSpeed * t
    }

    /**
     * How far a runner carries by simply walking off a lip and falling [drop] units onto the floor.
     *
     * Shorter than [Playtest.walkOffGrabReach], and the difference is the whole reason both exist:
     * that one measures how far the *grab* reaches, and a grab leaves you hanging until you press
     * jump to climb out. A gap between the two distances is crossed without timing but not without
     * a button, which is neither of the two things this grammar wants a break in the floor to mean.
     * [Beat.STEP] stays inside this one so it costs nothing at all.
     */
    private fun stepAcross(tuning: Tuning, drop: Double): Double =
        tuning.runSpeed * sqrt(2.0 * drop / tuning.gravity)

    /**
     * The room a seed means.
     *
     * Deterministic in [seed] and in [tuning], and in nothing else — no clock, no locale, no
     * platform. That is what lets a pack carry a number instead of a shape.
     */
    fun room(seed: Int, tuning: Tuning = Tuning()): Room {
        val d = Dice(seed)
        val ledges = mutableListOf<Ledge>()
        val chompers = mutableListOf<Chomper>()

        ledges += Ledge("sill", 0.0, SILL, 0.0)
        var x = SILL
        var y = 0.0

        d.pick(SHAPES).forEachIndexed { i, beat ->
            when (beat) {
                // Wider than the grab reaches at this drop, so it is a jump rather than a step
                // into thin air, and inside the arc with enough to spare that it asks for a jump
                // rather than for the best jump available.
                Beat.LEAP -> {
                    val drop = d.pick(listOf(0.0, 0.0, 0.0, 32.0, 64.0))
                    val gap = d.int(
                        (Playtest.walkOffGrabReach(tuning, drop) + 6).toInt(),
                        (reach(tuning, drop) * 0.86).toInt(),
                    )
                    y += drop
                    ledges += Ledge("leap-$i", x + gap, x + gap + d.int(130, 210), y)
                    x = ledges.last().x1
                }

                // Altitude, and nothing else: the break is narrow enough that running off it
                // lands you on the lower floor, with no button pressed and nothing to time. Drops
                // stay well inside [Tuning.fatalFall] even with the rise of a jump added, so no
                // generated room kills by dropping the runner onto its own floor.
                Beat.STEP -> {
                    val drop = d.pick(listOf(32.0, 64.0))
                    y += drop
                    val gap = d.int(12, (stepAcross(tuning, drop) - 12).toInt())
                    ledges += Ledge("step-$i", x + gap, x + gap + d.int(130, 210), y)
                    x = ledges.last().x1
                }

                // Loose stone, then a lip barely long enough to gather yourself on. The threat is
                // not precision, it is hesitation, and a generous landing would remove it.
                Beat.LOOSE -> {
                    val tiles = d.int(2, 3)
                    val tile = d.int(96, 116)
                    repeat(tiles) { k ->
                        ledges += Ledge(
                            "loose-$i$k", x + k * tile, x + (k + 1) * tile, y, collapsing = true,
                        )
                    }
                    x += tiles * tile
                    ledges += Ledge("lip-$i", x, x + d.int(44, 96), y)
                    x = ledges.last().x1
                }

                // A blade over open floor, with the near side long enough to read the cycle from a
                // standstill. `Rooms.firstBlade` is the same idea by hand. The open fraction is
                // this grammar's only smooth control over difficulty, so it gets the widest range
                // the room can survive.
                Beat.BLADE -> {
                    val floor = d.int(230, 310)
                    val period = d.int(92, 124)
                    ledges += Ledge("floor-$i", x, x + floor, y)
                    chompers += Chomper(
                        id = "blade-$i",
                        x = x + floor * (d.int(52, 68) / 100.0),
                        y = y,
                        periodFrames = period,
                        openFrames = d.int(period * 24 / 100, period * 48 / 100),
                        phase = d.int(0, period - 1),
                    )
                    x += floor
                }
            }
        }

        // Whatever the last beat left underfoot has to be wide enough to land on and stop on.
        val last = ledges.last()
        if (last.width < VAULT) ledges[ledges.lastIndex] = last.copy(x1 = last.x0 + VAULT)

        return Room(
            id = "gen-$seed",
            ledges = ledges,
            chompers = chompers,
            spawnX = 20.0,
            spawnY = 0.0,
            exitX = ledges.last().x1 - RUN_OUT,
            // Far enough below the lowest floor that falling into any gap is a death rather than a
            // long drop onto geometry the room does not have.
            abyssY = y + 320.0,
            epigraph = epigraph(d, chompers.isNotEmpty(), ledges.any { it.collapsing }),
        )
    }

    /**
     * Whether crossing [room] needs a jump the ledge-grab cannot substitute for.
     *
     * Read off the geometry rather than from the plan, so it costs nothing and can be asked before
     * the expensive part of [curate] — a kind day and a room with a leap in it can never agree, and
     * finding that out for free is worth the fifteen lines.
     */
    fun forcesLeap(room: Room, tuning: Tuning = Tuning()): Boolean {
        val ordered = room.ledges.sortedBy { it.x0 }
        return ordered.zipWithNext().any { (from, to) ->
            val gap = to.x0 - from.x1
            gap > 0 && gap > Playtest.walkOffGrabReach(tuning, (to.y - from.y).coerceAtLeast(0.0))
        }
    }

    /**
     * A line of prose for a room nobody wrote.
     *
     * Rooms are the unit of authorship in this game and an epigraph is their voice, so a generated
     * room being silent would read as a generated room. These are keyed to what is actually
     * underfoot rather than drawn from one pool: a line about nerve over stone that does not give
     * way is the tell that nobody is home.
     */
    private fun epigraph(d: Dice, blade: Boolean, loose: Boolean): String = when {
        blade && loose -> d.pick(
            listOf(
                "It keeps time. The floor does not.",
                "Count the beat from somewhere solid.",
                "Two things here are counting, and neither is patient.",
            ),
        )
        blade -> d.pick(
            listOf(
                "It has all night. You do not.",
                "The blade is honest. It always says when.",
                "Wait for the open, then stop thinking.",
            ),
        )
        loose -> d.pick(
            listOf(
                "The floor keeps faith only while you move.",
                "Stone remembers being stood on.",
                "Whatever you decide, decide it running.",
            ),
        )
        else -> d.pick(
            listOf(
                "Nothing here but the drop.",
                "Air, and then the other side.",
                "The vault does not care how you cross it.",
            ),
        )
    }

    // --- the judging ------------------------------------------------------------------------

    /** A room that has been measured and kept: what it is, how kind it is, and how it is done. */
    data class Curated(val seed: Int, val margin: Int, val plan: Playtest.Plan)

    /**
     * How forgiving each weekday's room should be, in frames of timing slack.
     *
     * The ramp is the crossword's, and for the crossword's reason: a daily that is the same
     * difficulty every day is one you either can or cannot do, while a week that starts kind and
     * tightens gives everyone somewhere to be. Sunday steps back out — a wider room to come back
     * to, not a second Saturday.
     *
     * The numbers come from what the grammar can actually reach. Monday through Wednesday sit
     * above sixteen frames, which no room with a real leap can offer, so those days are rhythm
     * rooms by construction; Friday and Saturday sit below it, so those days ask for the jump.
     * Every floor is well clear of [Playtest.MIN_MARGIN_FRAMES] — that constant is the line under
     * which a room reads as broken, not a difficulty to aim at.
     *
     * @param dayOfWeek 0 for Monday through 6 for Sunday, matching ISO numbering less one.
     */
    fun bandFor(dayOfWeek: Int): IntRange = when (((dayOfWeek % 7) + 7) % 7) {
        0 -> 30..46
        1 -> 24..38
        2 -> 21..34
        3 -> 15..26
        4 -> 12..18
        5 -> 8..14
        else -> 22..36
    }

    /**
     * The word a margin earns, which is the room's public difficulty.
     *
     * Published alongside the day rather than computed on the phone: [Playtest.slack] replays a
     * room a few hundred times, which is a fine thing to do once in CI and a poor thing to do while
     * someone is waiting for a puzzle to appear.
     */
    fun difficulty(margin: Int): String = when {
        margin >= 26 -> "kind"
        margin >= 18 -> "steady"
        margin >= 12 -> "sharp"
        else -> "severe"
    }

    /** The seeds tried for a day, in order. Scattered so consecutive days are not near-copies. */
    fun seedFor(day: Int, attempt: Int): Int = mix(day * 1_000_003 + attempt)

    /**
     * The first seed for [day] whose room lands inside [band], or null after [tries] failures.
     *
     * Failing closed matters more than succeeding: a day with no room is a day the portal falls
     * back to an authored one, while a day with an unmeasured room is the Palace mistake again.
     * Callers treat null as "no room today", never as "publish it anyway".
     */
    fun curate(
        day: Int,
        band: IntRange,
        tuning: Tuning = Tuning(),
        tries: Int = 64,
    ): Curated? {
        // A leap caps a room's slack at [LEAP_CEILING] wherever it appears, so a room containing
        // one can never reach the kind end of the week — and a day whose whole band sits under
        // that cap ought to be asking for the jump rather than reaching the same number by being a
        // mean blade. Both questions are answered by geometry, which is free; solving is not.
        val wantsLeap = band.last <= LEAP_CEILING
        val allowsLeap = band.first <= LEAP_CEILING

        for (attempt in 0 until tries) {
            val seed = seedFor(day, attempt)
            val room = room(seed, tuning)
            val leaps = forcesLeap(room, tuning)
            if (leaps && !allowsLeap) continue
            if (!leaps && wantsLeap) continue
            val plan = Playtest.solve(room, tuning) ?: continue
            val margin = Playtest.slack(room, plan, tuning)
            if (margin in band) return Curated(seed, margin, plan)
        }
        return null
    }
}
