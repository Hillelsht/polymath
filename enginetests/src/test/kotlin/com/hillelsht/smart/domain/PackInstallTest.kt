package com.hillelsht.smart.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the rule that keeps a regeneration from eating someone's progress.
 *
 * The failure this exists to prevent has already happened once in the pipeline, just not yet on
 * a phone: one library run published 4,160 facts and the next published 3,501, because a single
 * template's query timed out. Under the old replace-on-install behaviour, that second run would
 * have deleted 335 facts from a shard on every device that had it — including any the learner
 * had studied — leaving their review schedule pointing at rows that no longer existed.
 */
class PackInstallTest {

    @Test
    fun `a curated pack is replaced, because a new version of it is a correction`() {
        listOf("geography", "science", "arts", "history", "sports", "culture").forEach { packId ->
            assertTrue(PackInstall.replacesExistingFacts(packId), "$packId should be replaced")
        }
    }

    @Test
    fun `a generated shard is never cleared`() {
        listOf("library-geography-000", "library-science-007", "library-culture-012").forEach {
            assertFalse(PackInstall.replacesExistingFacts(it), "$it must not be cleared")
        }
    }

    @Test
    fun `the shard ids the generator actually publishes are recognised`() {
        // Same shape as tools/generate_facts.py writes: library-<category>-<NNN>.
        listOf("geography", "science", "arts", "history", "sports", "culture").forEach { category ->
            (0..12).forEach { shard ->
                val packId = "library-$category-${shard.toString().padStart(3, '0')}"
                assertTrue(PackInstall.isGeneratedLibrary(packId), "$packId not recognised")
            }
        }
    }

    @Test
    fun `a pack that merely mentions the word library is not one`() {
        // The prefix has to be the prefix. "librarything" is not a generated shard, and
        // treating it as one would quietly stop corrections to it from ever landing.
        assertTrue(PackInstall.replacesExistingFacts("librarything"))
        assertTrue(PackInstall.replacesExistingFacts("my-library-geography-000"))
        assertTrue(PackInstall.replacesExistingFacts(""))
    }

    @Test
    fun `the watch channel pack is replaced like any other curated content`() {
        // Channels are probed weekly and a channel that starts refusing embeds must actually
        // leave the allowlist, which additive installation would prevent.
        assertTrue(PackInstall.replacesExistingFacts("watch-channels"))
    }
}
