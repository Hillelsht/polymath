package com.hillelsht.smart.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the rule that makes the Library unbounded.
 *
 * The failure this exists to prevent is silent: a learner finishes the bundled 517 facts and
 * the Today screen simply stops offering new material, with no error and nothing to click.
 */
class LibraryTopUpTest {

    private fun shards(category: String, count: Int) =
        (0 until count).map { LibraryShard("library-$category-$it", category, it) }

    private val library = shards("geography", 4) + shards("science", 4) + shards("history", 4)

    private val plentiful = mapOf("geography" to 200, "science" to 200, "history" to 200)

    // --- when to fetch -----------------------------------------------------------------------

    @Test
    fun `a full pool is left alone`() {
        assertEquals(emptyList(), LibraryTopUp.next(library, emptySet(), plentiful))
    }

    @Test
    fun `a draining category is topped up`() {
        val chosen = LibraryTopUp.next(
            library,
            installed = emptySet(),
            unseenByCategory = plentiful + ("science" to 3),
        )
        assertEquals(listOf("library-science-0"), chosen.map { it.packId })
    }

    @Test
    fun `the threshold sits where it is documented`() {
        val at = plentiful + ("science" to LibraryTopUp.MIN_UNSEEN_PER_CATEGORY)
        assertTrue(LibraryTopUp.next(library, emptySet(), at).isEmpty(), "at the floor is fine")
        val below = plentiful + ("science" to LibraryTopUp.MIN_UNSEEN_PER_CATEGORY - 1)
        assertEquals(1, LibraryTopUp.next(library, emptySet(), below).size)
    }

    @Test
    fun `a category absent from the map counts as empty`() {
        // A brand-new install has no facts at all in a category the generator covers.
        val chosen = LibraryTopUp.next(library, emptySet(), mapOf("geography" to 200, "science" to 200))
        assertEquals(listOf("library-history-0"), chosen.map { it.packId })
    }

    // --- which shards ------------------------------------------------------------------------

    @Test
    fun `the most important shard comes first`() {
        // Shard 0 holds the highest-sitelink facts, so a learner meets the famous ones first.
        val chosen = LibraryTopUp.next(library, emptySet(), plentiful + ("science" to 0))
        assertEquals(0, chosen.single().order)
    }

    @Test
    fun `an installed shard is never fetched twice`() {
        val chosen = LibraryTopUp.next(
            library,
            installed = setOf("library-science-0", "library-science-1"),
            unseenByCategory = plentiful + ("science" to 0),
        )
        assertEquals(listOf("library-science-2"), chosen.map { it.packId })
    }

    @Test
    fun `the neediest category is served first`() {
        val chosen = LibraryTopUp.next(
            library,
            installed = emptySet(),
            unseenByCategory = mapOf("geography" to 10, "science" to 1, "history" to 200),
        )
        assertEquals(listOf("library-science-0", "library-geography-0"), chosen.map { it.packId })
    }

    @Test
    fun `two starving categories get one each before either gets two`() {
        // Fairness matters more than depth: a day's batch is dealt round-robin, so a second
        // Science shard is worth less than a first History one.
        val chosen = LibraryTopUp.next(
            library,
            installed = emptySet(),
            unseenByCategory = mapOf("science" to 0, "history" to 0, "geography" to 200),
        )
        assertEquals(listOf("library-history-0", "library-science-0"), chosen.map { it.packId })
    }

    // --- bounds ------------------------------------------------------------------------------

    @Test
    fun `a top-up is a trickle, not a sync`() {
        val everythingEmpty = mapOf("geography" to 0, "science" to 0, "history" to 0)
        assertEquals(
            LibraryTopUp.MAX_SHARDS_PER_TOPUP,
            LibraryTopUp.next(library, emptySet(), everythingEmpty).size,
        )
    }

    @Test
    fun `a caller may ask for fewer`() {
        val everythingEmpty = mapOf("geography" to 0, "science" to 0, "history" to 0)
        assertEquals(1, LibraryTopUp.next(library, emptySet(), everythingEmpty, limit = 1).size)
        assertTrue(LibraryTopUp.next(library, emptySet(), everythingEmpty, limit = 0).isEmpty())
    }

    @Test
    fun `a category with nothing left is skipped, not waited on`() {
        // Science is fully installed and still empty — there is simply no more Science. That
        // must not cost History its shard.
        val chosen = LibraryTopUp.next(
            library,
            installed = shards("science", 4).map { it.packId }.toSet(),
            unseenByCategory = mapOf("science" to 0, "history" to 0, "geography" to 200),
        )
        assertEquals(listOf("library-history-0"), chosen.map { it.packId })
    }

    @Test
    fun `no category gets two shards in one round`() {
        // 150 facts is ten days of new material at the daily cap; a second shard for the same
        // category in the same round buys nothing the learner can reach for a fortnight.
        val everythingEmpty = mapOf("geography" to 0, "science" to 0, "history" to 0)
        val chosen = LibraryTopUp.next(library, emptySet(), everythingEmpty, limit = 99)
        assertEquals(3, chosen.size)
        assertEquals(3, chosen.map { it.categoryId }.toSet().size)
        assertTrue(chosen.all { it.order == 0 })
    }

    @Test
    fun `an empty index asks for nothing`() {
        assertTrue(LibraryTopUp.next(emptyList(), emptySet(), emptyMap()).isEmpty())
    }

    @Test
    fun `a fully installed library asks for nothing`() {
        val chosen = LibraryTopUp.next(
            library,
            installed = library.map { it.packId }.toSet(),
            unseenByCategory = mapOf("geography" to 0, "science" to 0, "history" to 0),
        )
        assertTrue(chosen.isEmpty())
    }

    @Test
    fun `never asks for the same shard twice in one round`() {
        val everythingEmpty = mapOf("geography" to 0, "science" to 0, "history" to 0)
        val chosen = LibraryTopUp.next(library, emptySet(), everythingEmpty, limit = 12)
        assertEquals(chosen.size, chosen.map { it.packId }.toSet().size)
    }

    // --- the end of the road -----------------------------------------------------------------

    @Test
    fun `exhaustion is reported honestly`() {
        assertFalse(LibraryTopUp.exhausted(library, emptySet()))
        assertFalse(LibraryTopUp.exhausted(library, setOf("library-science-0")))
        assertTrue(LibraryTopUp.exhausted(library, library.map { it.packId }.toSet()))
        assertTrue(LibraryTopUp.exhausted(emptyList(), emptySet()))
    }
}
