package com.hillelsht.smart.domain

/**
 * Whether installing a new version of a pack should remove the facts already in it.
 *
 * Two kinds of pack now arrive at the seeder and they need opposite treatment.
 *
 * A **curated pack** is hand-authored. A new version of it is a correction — a fixed typo, a
 * reworded question, a fact that turned out to be wrong — and a correction is worthless unless
 * it replaces what it corrects. So the pack's facts are cleared and rewritten.
 *
 * A **generated library shard** is not authored at all. Its facts are derived from Wikidata by
 * construction, and the set that comes back varies between monthly runs for reasons that have
 * nothing to do with the facts: one regeneration returned 4,160 facts and the next 3,501,
 * because a single template's query timed out at the endpoint. Clearing a shard on that basis
 * would delete facts the learner had already studied and leave their review schedule pointing
 * at rows that no longer exist — losing exactly the history the app is built to protect. A
 * shard therefore only ever adds and updates, and a fact that has arrived on a device stays.
 */
object PackInstall {

    /** Every generated shard is published under this prefix; nothing else uses it. */
    const val LIBRARY_PREFIX = "library-"

    fun replacesExistingFacts(packId: String): Boolean = !isGeneratedLibrary(packId)

    fun isGeneratedLibrary(packId: String): Boolean = packId.startsWith(LIBRARY_PREFIX)
}
