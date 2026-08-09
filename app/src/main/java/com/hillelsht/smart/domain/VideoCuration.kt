package com.hillelsht.smart.domain

/**
 * What a channel feed offers before curation: the raw fields YouTube's RSS actually carries.
 * Deliberately not [com.hillelsht.smart.domain.model.Video] — a feed entry has no category and
 * no duration yet.
 */
data class FeedEntry(
    val youtubeId: String,
    val title: String,
    val thumbnailUrl: String?,
    val views: Long,
)

/**
 * Turns "this channel's latest uploads" into "this channel's lessons".
 *
 * A channel feed is not a curriculum: it also carries livestreams, community chatter, Shorts
 * and merch announcements. These rules run identically on the device and in the CI prober,
 * which is why they live in the domain layer as plain Kotlin rather than being written twice.
 */
object VideoCuration {

    /** Recent uploads that are not lessons. */
    private val JUNK = Regex(
        "live ?stream|\\blive\\b|#shorts|\\bshorts?\\b|\\bchat\\b|q&a|podcast|announce|" +
            "subscribe|merch|schedule|trailer|teaser|behind the scenes|patreon|giveaway|" +
            "vlog|\\breacts?\\b|unboxing|\\bama\\b",
        RegexOption.IGNORE_CASE,
    )

    /** A title this short is a placeholder or a Short, not an explainer. */
    const val MIN_TITLE_LENGTH = 16

    /** Enough for a varied shelf without one prolific channel swamping a subject. */
    const val PER_CHANNEL_LIMIT = 8

    fun isJunkTitle(title: String): Boolean =
        title.length < MIN_TITLE_LENGTH || JUNK.containsMatchIn(title)

    /**
     * Drops the junk, then keeps each channel's most-watched entries.
     *
     * View count is the one quality signal a feed gives away for free, and it is a good one:
     * a channel's most-watched recent uploads are reliably its real teaching videos, while
     * filler sits at the bottom.
     */
    fun curate(
        entries: List<FeedEntry>,
        limit: Int = PER_CHANNEL_LIMIT,
        blocked: Set<String> = emptySet(),
    ): List<FeedEntry> = entries
        .asSequence()
        .filterNot { it.youtubeId in blocked }
        .filterNot { isJunkTitle(it.title) }
        .distinctBy { it.youtubeId }
        .sortedByDescending { it.views }
        .take(limit)
        .toList()

    /**
     * Interleaves per-channel results so the shelf opens with a spread of channels rather than
     * eight videos from whoever happened to be fetched first.
     */
    fun interleave(byChannel: List<List<FeedEntry>>): List<FeedEntry> {
        val queues = byChannel.map { ArrayDeque(it) }
        val result = mutableListOf<FeedEntry>()
        while (queues.any { it.isNotEmpty() }) {
            for (queue in queues) {
                if (queue.isNotEmpty()) result += queue.removeFirst()
            }
        }
        return result
    }
}
