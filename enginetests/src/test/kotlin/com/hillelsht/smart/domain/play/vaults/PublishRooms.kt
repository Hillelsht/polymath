package com.hillelsht.smart.domain.play.vaults

import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/**
 * Writes the daily Vaults rooms to `packs/play/vaults/`.
 *
 * Run by CI as `gradle -p enginetests publishRooms -Pmonths=4`.
 *
 * This lives in the test source set rather than in `tools/` — where every other content pipeline
 * lives — because it is the one pipeline that cannot be written in Python: choosing a room means
 * running [Playtest.solve] over it a few hundred times, and the physics is Kotlin. Putting it next
 * to the tests that check its output is the next best thing, and it is the only file here allowed
 * to touch `java.time` or the filesystem, both of which `domain/` is forbidden.
 *
 * **A published day is never rewritten.** The room someone played on Tuesday has to still be
 * Tuesday's room when they come back to compare, and a ghost link carries a date rather than a
 * room, so changing one silently invalidates every link anyone shared for it. Reruns only fill
 * gaps. The single exception is [RoomGen.VERSION] moving, which makes every old seed mean a
 * different room — then the month is rewritten wholesale, and it has to be, because leaving it
 * alone would publish difficulty labels measured on geometry that no longer exists.
 */
fun main(args: Array<String>) {
    val months = args.pairFlag("--months")?.toIntOrNull() ?: 4
    val from = args.pairFlag("--from")?.let { YearMonth.parse(it) } ?: YearMonth.now()
    val dir = File(args.pairFlag("--out") ?: "../packs/play/vaults")
    dir.mkdirs()

    var written = 0
    var kept = 0
    var missed = 0

    repeat(months) { m ->
        val month = from.plusMonths(m.toLong())
        val file = File(dir, "$month.json")
        val existing = if (file.exists()) readMonth(file) else emptyMap()
        val reusable = if (existing["generator"] == RoomGen.VERSION.toString()) existing else {
            if (existing.isNotEmpty()) {
                println("$month: generator ${existing["generator"]} != ${RoomGen.VERSION}, rewriting")
            }
            emptyMap()
        }

        val days = (1..month.lengthOfMonth()).map { month.atDay(it) }
        val entries = days.mapNotNull { date ->
            val already = reusable[date.toString()]
            if (already != null) {
                kept++
                return@mapNotNull already
            }
            val band = RoomGen.bandFor(date.dayOfWeek.value - 1)
            val room = RoomGen.curate(date.toEpochDay().toInt(), band)
            if (room == null) {
                // Nothing in the seed stream landed in the band. The portal falls back to an
                // authored room for that day, which is a worse daily but a real one — publishing
                // an out-of-band room instead would make the difficulty label meaningless, and the
                // label is the entire claim this pipeline makes.
                println("$date: no room in $band after the seed stream ran out")
                missed++
                return@mapNotNull null
            }
            written++
            entry(date, room)
        }

        file.writeText(render(month.toString(), entries))
        println("$month: ${entries.size} rooms")
    }

    println("wrote $written, kept $kept, missed $missed")
    // A month with holes in it is a bug in the bands or in the grammar, not a bad day at the
    // office, and it must not slip through green.
    check(missed == 0) { "$missed days have no room" }
}

/** One published day, already rendered — reused verbatim when a rerun finds it already there. */
private fun entry(date: LocalDate, curated: RoomGen.Curated): String = buildString {
    append("""{"date":"$date",""")
    append(""""seed":${curated.seed},""")
    append(""""margin":${curated.margin},""")
    append(""""difficulty":"${RoomGen.difficulty(curated.margin)}",""")
    append(""""wait":${curated.plan.waitFrames},""")
    append(""""presses":[${curated.plan.presses.joinToString(",")}]}""")
}

private fun render(month: String, entries: List<String>): String =
    """{"month":"$month","generator":${RoomGen.VERSION},"rooms":[${entries.joinToString(",")}]}"""

/**
 * The rendered entry for each date already in a file, plus `generator`.
 *
 * Deliberately not a real parse: the only questions asked of an existing pack are "which generator
 * wrote it" and "which dates does it already have", and the answer to the second is handed straight
 * back out as text. Re-serialising through a JSON library would be a chance to change a published
 * day by accident, which is the one thing this program must never do.
 */
private fun readMonth(file: File): Map<String, String> {
    val text = file.readText()
    val out = mutableMapOf<String, String>()
    Regex("""\{"date":"(\d{4}-\d{2}-\d{2})".*?}""").findAll(text).forEach {
        out[it.groupValues[1]] = it.value
    }
    Regex(""""generator":(\d+)""").find(text)?.let { out["generator"] = it.groupValues[1] }
    return out
}

private fun Array<String>.pairFlag(name: String): String? =
    indexOf(name).takeIf { it >= 0 && it + 1 < size }?.let { this[it + 1] }
