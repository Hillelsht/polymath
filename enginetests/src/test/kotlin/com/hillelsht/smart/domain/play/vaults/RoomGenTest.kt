package com.hillelsht.smart.domain.play.vaults

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What has to be true of a room nobody read before it was published.
 *
 * `RoomsTest` gates seven rooms a person built and looked at. Nothing looks at these, so the
 * properties a glance would have caught — the exit standing on solid ground, the abyss below the
 * floor, one idea per screen — have to be asserted instead. [RoomGen.curate] is the last gate and
 * measures the timing; everything here is about the geometry it measures.
 */
class RoomGenTest {

    private val tuning = Tuning()

    /** A spread of seeds wide enough to hit every shape in the table several times over. */
    private val sample = (0 until 120).map { RoomGen.seedFor(it, 0) }

    @Test
    fun `a seed means exactly one room`() {
        sample.take(20).forEach { seed ->
            assertEquals(
                RoomGen.room(seed, tuning), RoomGen.room(seed, tuning),
                "seed $seed built two different rooms, so a published pack means nothing",
            )
        }
        // And not the same room every time, which would pass the check above just as happily.
        assertTrue(
            sample.map { RoomGen.room(it, tuning) }.distinct().size > sample.size / 2,
            "the generator repeats itself: a month would be the same room over and over",
        )
    }

    @Test
    fun `every generated room is geometrically sound`() {
        sample.forEach { seed ->
            val room = RoomGen.room(seed, tuning)
            val ledges = room.ledges.sortedBy { it.x0 }

            assertEquals(ledges, room.ledges, "${room.id} lists its ledges out of order")
            assertTrue(
                room.ledges.map { it.id }.distinct().size == room.ledges.size,
                "${room.id} has two ledges with the same id, so breaking one breaks both",
            )
            ledges.zipWithNext().forEach { (a, b) ->
                assertTrue(a.x1 <= b.x0, "${room.id}: ledges ${a.id} and ${b.id} overlap")
            }

            val spawn = room.ledges.first()
            assertTrue(
                spawn.spans(room.spawnX) && spawn.y == room.spawnY && !spawn.collapsing,
                "${room.id} spawns the runner somewhere other than on solid entrance floor",
            )

            val exit = room.ledges.last()
            assertTrue(
                exit.spans(room.exitX) && !exit.collapsing,
                "${room.id} puts its exit line off the end of the last ledge, or on stone that goes",
            )
            assertTrue(
                exit.width >= 150.0,
                "${room.id} ends on ${exit.width} units — too little to land on and stop on",
            )
            assertTrue(
                room.ledges.all { room.abyssY > it.y + tuning.fatalFall },
                "${room.id} floats a floor over the abyss line, so a fall would end on it",
            )

            // A room is drawn whole, on one screen, with no camera — that is what makes a blade's
            // rhythm readable before you commit to it. The browser scales to fit, so a wider room
            // is not clipped; it is simply too small to read, which is worse than being cut off
            // because nothing tells you it happened.
            assertTrue(
                ledges.last().x1 <= 1_300.0,
                "${room.id} is ${ledges.last().x1} units wide — too wide to read at a glance",
            )
            assertTrue(
                ledges.maxOf { it.y } <= 200.0,
                "${room.id} descends ${ledges.maxOf { it.y }} units, deeper than one screen holds",
            )
        }
    }

    @Test
    fun `a generated room introduces at most one idea of each kind`() {
        sample.forEach { seed ->
            val room = RoomGen.room(seed, tuning)
            assertTrue(
                room.chompers.size <= 1,
                "${room.id} has ${room.chompers.size} blades — two out of step is theNarrow's job",
            )
            // Loose stone comes in runs. More than one run in a screen stops being a decision.
            val runs = room.ledges
                .map { it.collapsing }
                .zipWithNext()
                .count { (before, now) -> now && !before } + if (room.ledges.first().collapsing) 1 else 0
            assertTrue(runs <= 1, "${room.id} has $runs separate stretches of loose stone")
        }
    }

    @Test
    fun `forcesLeap reads the geometry the same way the physics does`() {
        var leaping = 0
        sample.forEach { seed ->
            val room = RoomGen.room(seed, tuning)
            val gaps = room.ledges.sortedBy { it.x0 }.zipWithNext()
                .map { (a, b) -> (b.x0 - a.x1) to (b.y - a.y).coerceAtLeast(0.0) }
                .filter { it.first > 0 }

            if (RoomGen.forcesLeap(room, tuning)) {
                leaping++
                assertTrue(
                    gaps.any { (gap, drop) -> gap > Playtest.walkOffGrabReach(tuning, drop) },
                    "${room.id} is called a leap with nothing in it the grab could not bridge",
                )
            } else {
                gaps.forEach { (gap, drop) ->
                    assertTrue(
                        gap <= Playtest.walkOffGrabReach(tuning, drop),
                        "${room.id} has a $gap-unit gap at a $drop-unit drop and is still called calm",
                    )
                }
            }
        }
        // Both kinds have to come out of the table in quantity: the kind end of the week can only
        // be filled by calm rooms and the sharp end only by leaping ones, so a generator that made
        // one of them rare would leave days unpublishable.
        assertTrue(
            leaping in 20..(sample.size - 20),
            "$leaping of ${sample.size} rooms ask for a jump — too lopsided to fill the week",
        )
    }

    @Test
    fun `the week's bands are a ramp the generator can actually satisfy`() {
        val bands = (0..6).map { RoomGen.bandFor(it) }
        bands.forEach { band ->
            assertTrue(!band.isEmpty(), "an empty band can never be filled")
            assertTrue(
                band.first > Playtest.MIN_MARGIN_FRAMES,
                "band $band reaches the ${Playtest.MIN_MARGIN_FRAMES}-frame floor, which is the " +
                    "line below which a room reads as broken, not a difficulty to aim at",
            )
        }
        // Monday is the kindest and Saturday the sharpest; Sunday is deliberately not a second
        // Saturday, and the week is not flat.
        assertEquals(bands.maxByOrNull { it.first }, bands[0], "Monday is not the kindest day")
        assertEquals(bands.minByOrNull { it.first }, bands[5], "Saturday is not the sharpest day")
        assertTrue(bands[6].first > bands[5].first, "Sunday is a second Saturday")
        assertTrue(bands.distinct().size >= 5, "the week barely changes difficulty")
        // Out-of-range days wrap rather than throwing: the caller passes arithmetic, not an enum.
        assertEquals(RoomGen.bandFor(0), RoomGen.bandFor(7))
        assertEquals(RoomGen.bandFor(6), RoomGen.bandFor(-1))
    }

    @Test
    fun `the difficulty word tracks the margin and covers every band`() {
        val words = (Playtest.MIN_MARGIN_FRAMES..60).map { RoomGen.difficulty(it) }
        assertEquals(words.distinct(), words.distinct().distinct())
        // Kinder never reads as harder: the word only ever changes in one direction as slack grows.
        val order = listOf("severe", "sharp", "steady", "kind")
        words.zipWithNext().forEach { (a, b) ->
            assertTrue(order.indexOf(b) >= order.indexOf(a), "'$a' then '$b' as slack grows")
        }
        (0..6).forEach { day ->
            val band = RoomGen.bandFor(day)
            assertTrue(
                band.map { RoomGen.difficulty(it) }.distinct().isNotEmpty(),
                "day $day has a band no word describes",
            )
        }
    }

    @Test
    fun `curating a day returns a room inside the band it asked for`() {
        // Two days rather than seven: each one costs a handful of full solves, and the published
        // packs put every other day through the same search in `DailyRoomsTest`.
        listOf(0, 5).forEach { day ->
            val band = RoomGen.bandFor(day)
            val curated = RoomGen.curate(day = 4_000 + day, band = band, tuning = tuning)
            assertNotNull(curated, "nothing in the seed stream landed in $band for day $day")
            assertTrue(
                curated.margin in band,
                "curate returned ${curated.margin}, outside the $band it was asked for",
            )
            val room = RoomGen.room(curated.seed, tuning)
            assertEquals(
                Playtest.Outcome.EXITED,
                Playtest.replay(room, curated.plan, tuning).outcome,
                "the plan curate published does not finish the room it published",
            )
        }
    }
}
