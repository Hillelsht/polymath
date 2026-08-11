package com.hillelsht.smart.domain

/**
 * One downloadable slice of the generated library, as the remote index describes it.
 *
 * Deliberately not `RemotePack`: that type carries serialization annotations and lives in the
 * data layer, and this decision has to stay plain Kotlin so it can be tested off-device.
 */
data class LibraryShard(
    val packId: String,
    val categoryId: String,
    /** Position within its category, ascending. Shard 0 holds the most important facts. */
    val order: Int,
)

/**
 * Keeps the Library from ever running out.
 *
 * The app bundles a fixed 517 facts. Once they are learned there is nothing left to learn, and
 * the Today screen quietly stops offering new material. The generated library fixes that: the
 * repo holds thousands of facts in small shards, and the device pulls the next one down as the
 * pool of unseen facts drains.
 *
 * **Nothing is ever deleted.** A learned fact leaves the *unseen* pool the moment it is learned
 * — [SessionPlanner] stops offering it — which is the "it disappears and a new one appears"
 * behaviour, without touching the fact itself. Deleting it would destroy the spaced-repetition
 * schedule that brings it back tomorrow, next week and next month, which is the whole point of
 * having learned it.
 *
 * The count that matters is **per category, not overall**. [SessionPlanner.interleaveByCategory]
 * deals new material round-robin, so a category that runs dry silently drops out of the daily
 * mix while the total still looks healthy — a learner would notice Geography vanishing from
 * their day long before any global counter looked low.
 *
 * Growth is proportional to what you have learned, at roughly 1.3 KB per fact: the shards only
 * arrive when the pool they refill is nearly empty. The APK itself never grows, because none of
 * this ships inside it.
 */
object LibraryTopUp {

    /**
     * Below this many unseen facts in a category, fetch that category more.
     *
     * This was 15, which was a number chosen without doing the arithmetic. A fresh install
     * holds 70–95 unseen facts per category, and [SessionPlanner] deals twelve new facts a day
     * round-robin across six categories — about two per category. A category would therefore
     * have taken **twenty-seven to forty days** to fall below fifteen, and until then the app
     * looked exactly as it had before: five hundred and seventeen facts, no growth, no sign
     * that anything had been added. A mechanism nobody can see for a month is indistinguishable
     * from one that does not work.
     *
     * A hundred and twenty is above what any category ships with, so the buffer fills on first
     * run, and it is roughly two months of material — deep enough that the pool cannot run dry
     * between one check and the next even if the app goes unopened for weeks.
     */
    const val MIN_UNSEEN_PER_CATEGORY = 120

    /**
     * How many categories one top-up may serve. A top-up is a background trickle, not a sync.
     *
     * Each hungry category gets **one** shard, never two: a shard is 150 facts, which is ten
     * weeks of new material for that category, so fetching a second one for the same category
     * in the same round is 330 KB spent on facts the learner cannot reach for months. If the
     * category is still short at the next check, it gets another one then.
     *
     * Three at a time bounds a single round at about a megabyte. On a fresh install all six
     * categories are hungry at once, and Today and Library each check on open, so the buffer
     * fills over the first couple of screens rather than in one burst.
     */
    const val MAX_SHARDS_PER_TOPUP = 3

    /**
     * Which shards to download right now, most-needed first.
     *
     * @param available every shard the remote index offers.
     * @param installed pack ids already in the database, which must never be fetched again.
     * @param unseenByCategory unseen facts per category id; a category missing from the map is
     *   treated as empty, since "no facts at all" is the emptiest a category can be.
     */
    fun next(
        available: List<LibraryShard>,
        installed: Set<String>,
        unseenByCategory: Map<String, Int>,
        limit: Int = MAX_SHARDS_PER_TOPUP,
    ): List<LibraryShard> {
        if (limit <= 0) return emptyList()

        val hungry = available
            .asSequence()
            .map { it.categoryId }
            .distinct()
            .map { it to (unseenByCategory[it] ?: 0) }
            .filter { (_, unseen) -> unseen < MIN_UNSEEN_PER_CATEGORY }
            .sortedWith(compareBy({ (_, unseen) -> unseen }, { (category, _) -> category }))
            .toList()

        // One shard per hungry category, neediest first. A category that is still short at the
        // next check simply gets another one then.
        return hungry
            .asSequence()
            .mapNotNull { (category, _) ->
                available
                    .filter { it.categoryId == category && it.packId !in installed }
                    .minByOrNull { it.order }
            }
            .take(limit)
            .toList()
    }

    /** Categories with facts still to learn, so the caller can say when the well is truly dry. */
    fun exhausted(available: List<LibraryShard>, installed: Set<String>): Boolean =
        available.none { it.packId !in installed }
}
