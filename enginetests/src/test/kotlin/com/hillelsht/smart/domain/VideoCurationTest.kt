package com.hillelsht.smart.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shelf is now built on the device from raw channel feeds, and a channel feed is not a
 * curriculum — it also carries livestreams, Shorts, community chatter and merch drops. These
 * rules are the only thing standing between "the latest 15 uploads" and "something worth
 * watching", so they get tested directly rather than being trusted to a screenshot.
 */
class VideoCurationTest {

    private fun entry(title: String, id: String = "id-$title", views: Long = 0L) =
        FeedEntry(youtubeId = id, title = title, thumbnailUrl = null, views = views)

    @Test
    fun `filler uploads are rejected`() {
        val junk = listOf(
            "LIVE: Q&A with the team",
            "Livestream — Friday hangout",
            "The Roman Empire in 60 seconds #shorts",
            "Shorts: quick fact",
            "Channel announcement: new merch is here",
            "Subscribe for more!",
            "Podcast Ep. 42 with a guest",
            "Behind the scenes of our last video",
            "Official Trailer",
            "Teaser for next week",
            "Vlog: a day in my life",
            "A historian reacts to the movie",
            "Live chat with the crew",
            "Unboxing the new telescope",
            "AMA — ask me anything",
            "Support us on Patreon",
            "Giveaway time!",
            "Upload schedule update",
        )
        junk.forEach {
            assertTrue(VideoCuration.isJunkTitle(it), "should have been filtered: $it")
        }
    }

    @Test
    fun `real lessons are kept`() {
        val lessons = listOf(
            "Why is the Sahara so dry?",
            "The Fall of Constantinople, explained",
            "But what is a neural network?",
            "How the Renaissance changed European art",
            "Every country in South America compared",
            "The physics behind a curveball",
            "What caused the French Revolution?",
        )
        lessons.forEach {
            assertFalse(VideoCuration.isJunkTitle(it), "should have been kept: $it")
        }
    }

    @Test
    fun `a title too short to describe anything is rejected`() {
        assertTrue(VideoCuration.isJunkTitle("Ep. 12"))
        assertTrue(VideoCuration.isJunkTitle("Rome"))
        // Exactly at the threshold is fine — the rule is "shorter than", not "shorter or equal".
        val atThreshold = "Why deserts form"
        assertEquals(VideoCuration.MIN_TITLE_LENGTH, atThreshold.length)
        assertFalse(VideoCuration.isJunkTitle(atThreshold))
    }

    @Test
    fun `the most watched uploads win`() {
        // View count is the one quality signal a feed gives away, and it is a good one: a
        // channel's most-watched recent uploads are reliably its real teaching videos.
        val curated = VideoCuration.curate(
            listOf(
                entry("Why is the Sahara so dry?", "a", views = 1_000),
                entry("The Fall of Constantinople, explained", "b", views = 90_000),
                entry("How the Renaissance changed art", "c", views = 12_000),
            ),
            limit = 2,
        )
        assertEquals(listOf("b", "c"), curated.map { it.youtubeId })
    }

    @Test
    fun `no channel may swamp the shelf`() {
        val prolific = (1..40).map { entry("Explaining topic number $it", "v$it", views = it.toLong()) }
        assertEquals(
            VideoCuration.PER_CHANNEL_LIMIT,
            VideoCuration.curate(prolific).size,
        )
    }

    @Test
    fun `videos that failed to play never come back`() {
        // The player writes an id here after a 152 error; the shelf must honour it on every
        // later refresh, or the same dead card reappears the next time the tab opens.
        val curated = VideoCuration.curate(
            listOf(
                entry("Why is the Sahara so dry?", "dead", views = 999_999),
                entry("The Fall of Constantinople, explained", "alive", views = 10),
            ),
            blocked = setOf("dead"),
        )
        assertEquals(listOf("alive"), curated.map { it.youtubeId })
    }

    @Test
    fun `a video listed twice appears once`() {
        val curated = VideoCuration.curate(
            listOf(
                entry("Why is the Sahara so dry?", "dup", views = 10),
                entry("Why is the Sahara so dry? (re-upload)", "dup", views = 5),
            ),
        )
        assertEquals(1, curated.size)
    }

    @Test
    fun `the shelf opens with a spread of channels`() {
        val mixed = VideoCuration.interleave(
            listOf(
                listOf(entry("t", "a1"), entry("t", "a2"), entry("t", "a3")),
                listOf(entry("t", "b1")),
                listOf(entry("t", "c1"), entry("t", "c2")),
            ),
        )
        assertEquals(listOf("a1", "b1", "c1", "a2", "c2", "a3"), mixed.map { it.youtubeId })
    }

    @Test
    fun `interleaving keeps every video and invents none`() {
        val channels = listOf(
            listOf(entry("t", "a1"), entry("t", "a2")),
            emptyList(),
            listOf(entry("t", "c1")),
        )
        val mixed = VideoCuration.interleave(channels)
        assertEquals(3, mixed.size)
        assertEquals(channels.flatten().map { it.youtubeId }.toSet(), mixed.map { it.youtubeId }.toSet())
    }

    @Test
    fun `an empty feed is survivable`() {
        // One dead channel must never empty the tab.
        assertTrue(VideoCuration.curate(emptyList()).isEmpty())
        assertTrue(VideoCuration.interleave(emptyList()).isEmpty())
        assertTrue(VideoCuration.interleave(listOf(emptyList(), emptyList())).isEmpty())
    }
}
