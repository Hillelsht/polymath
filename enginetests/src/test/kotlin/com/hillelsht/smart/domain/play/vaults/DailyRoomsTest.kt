package com.hillelsht.smart.domain.play.vaults

import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Re-measures every published day against the physics this build actually ships.
 *
 * `PublishRooms` chose these rooms; this decides whether they are still the rooms it chose. The two
 * are worth keeping apart because a published day outlives the run that produced it: a tuning
 * change months later moves every margin in the repository, and the only visible symptom would be
 * a daily whose stated difficulty is a fiction. Here it is a red build instead.
 *
 * Verification replays the *published plan* rather than searching for a new one, which costs a few
 * hundred replays a day rather than a few thousand — cheap enough to check every day of every
 * month on every build, which is the property that makes this a gate and not a spot check.
 *
 * The published JSON is read with regexes, as elsewhere in this suite: these tests assert on the
 * literal bytes committed, and a lenient decoder would paper over a missing field.
 */
class DailyRoomsTest {

    private val dir = File("../packs/play/vaults")
    private val tuning = Tuning()

    private data class Day(
        val date: LocalDate,
        val seed: Int,
        val margin: Int,
        val difficulty: String,
        val plan: Playtest.Plan,
    )

    private fun months(): List<Pair<File, String>> =
        (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .sortedBy { it.name }
            .map { it to it.readText() }

    private fun days(raw: String): List<Day> =
        Regex("""\{"date":"(\d{4}-\d{2}-\d{2})","seed":(-?\d+),"margin":(\d+),"difficulty":"(\w+)","wait":(\d+),"presses":\[([\d,]*)]}""")
            .findAll(raw)
            .map { m ->
                val (date, seed, margin, difficulty, wait, presses) = m.destructured
                Day(
                    date = LocalDate.parse(date),
                    seed = seed.toInt(),
                    margin = margin.toInt(),
                    difficulty = difficulty,
                    plan = Playtest.Plan(
                        waitFrames = wait.toInt(),
                        presses = presses.split(",").filter { it.isNotBlank() }.map { it.toInt() },
                    ),
                )
            }
            .toList()

    @Test
    fun `every month is published whole, by this generator`() {
        val files = months()
        assertTrue(files.isNotEmpty(), "no daily rooms are published at all")

        files.forEach { (file, raw) ->
            val month = YearMonth.parse(file.nameWithoutExtension)
            assertEquals(
                RoomGen.VERSION,
                Regex(""""generator":(\d+)""").find(raw)?.groupValues?.get(1)?.toInt(),
                "${file.name} was written by a different grammar, so its seeds mean other rooms " +
                    "than the ones its margins were measured on — republish it",
            )
            assertEquals(
                month.toString(),
                Regex(""""month":"([\d-]+)"""").find(raw)?.groupValues?.get(1),
                "${file.name} disagrees with its own filename about which month it is",
            )

            val dates = days(raw).map { it.date }
            assertEquals(
                (1..month.lengthOfMonth()).map { month.atDay(it) },
                dates,
                "${file.name} is missing days, has them out of order, or repeats one — a gap is a " +
                    "day with no daily",
            )
        }
    }

    @Test
    fun `every published room still plays the way its pack says it does`() {
        val report = StringBuilder("\n  date         margin  difficulty  plan\n")
        var checked = 0

        months().forEach { (file, raw) ->
            days(raw).forEach { day ->
                val room = RoomGen.room(day.seed, tuning)
                assertEquals(
                    Playtest.Outcome.EXITED,
                    Playtest.replay(room, day.plan, tuning).outcome,
                    "${day.date}: the published plan no longer finishes seed ${day.seed}",
                )

                val measured = Playtest.slack(room, day.plan, tuning)
                assertEquals(
                    day.margin, measured,
                    "${day.date}: published as ${day.margin} frames of slack, measures $measured " +
                        "under this build's physics — every difficulty label in ${file.name} is " +
                        "now wrong, so republish rather than editing this number",
                )
                assertTrue(
                    measured >= Playtest.MIN_MARGIN_FRAMES,
                    "${day.date} allows only $measured frames — below the " +
                        "${Playtest.MIN_MARGIN_FRAMES}-frame floor, so it reads as broken",
                )
                assertEquals(
                    RoomGen.difficulty(day.margin), day.difficulty,
                    "${day.date} is labelled '${day.difficulty}' for ${day.margin} frames",
                )

                val band = RoomGen.bandFor(day.date.dayOfWeek.value - 1)
                assertTrue(
                    day.margin in band,
                    "${day.date} is a ${day.date.dayOfWeek}, which asks for $band, and got " +
                        "${day.margin} — the week's ramp is not what the packs contain",
                )

                // The claim [RoomGen.curate] skips candidates on, checked here rather than in
                // `RoomGenTest` because every published day is already being solved: a room with a
                // gap the ledge-grab cannot bridge is never more forgiving than [RoomGen.LEAP_CEILING].
                if (RoomGen.forcesLeap(room, tuning)) {
                    assertTrue(
                        day.margin <= RoomGen.LEAP_CEILING,
                        "${day.date} asks for a leap and still measures ${day.margin} frames, above " +
                            "the ${RoomGen.LEAP_CEILING} curate treats as the ceiling for one — the " +
                            "constant is wrong, not this room",
                    )
                }
                checked++
                if (checked <= 7) {
                    report.append(
                        "  ${day.date}  ${day.margin.toString().padStart(3)}f  " +
                            "${day.difficulty.padEnd(10)}  wait=${day.plan.waitFrames} " +
                            "presses=${day.plan.presses}\n",
                    )
                }
            }
        }
        println(report.append("  … $checked days checked\n"))
    }

    @Test
    fun `the published rooms are not all the same room`() {
        val seeds = months().flatMap { (_, raw) -> days(raw) }.map { it.seed }
        assertEquals(seeds.distinct().size, seeds.size, "two days share a seed, so they share a room")

        // A month of rooms that all measure the same is a month with no ramp in it, which is the
        // failure mode a per-day gate cannot see.
        val margins = months().flatMap { (_, raw) -> days(raw) }.map { it.margin }
        assertTrue(
            margins.distinct().size >= 8,
            "only ${margins.distinct().size} distinct margins across every published day",
        )
    }
}
