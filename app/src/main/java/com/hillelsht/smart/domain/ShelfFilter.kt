package com.hillelsht.smart.domain

import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.domain.model.LengthClass
import com.hillelsht.smart.domain.model.Video

/**
 * Which videos the Watch tab shows, and which filters it is honest to offer.
 *
 * This lived inside a composable, which is how it shipped with a bug that emptied the tab: the
 * length chips appeared as soon as a *single* video had a known duration, and choosing one then
 * hid every video whose duration was unknown — nearly all of them, because durations only
 * arrived from the player after you opened something. Pulling it here puts it under the same
 * test suite as [Sm2Scheduler] and [VideoCuration], where a rule this easy to get subtly wrong
 * belongs.
 */
object ShelfFilter {

    /**
     * How much of the shelf must have a known duration before the length chips are worth
     * offering. Below this the filter would hide more than it reveals, and a control that
     * silently discards most of what you are looking at is worse than no control.
     */
    const val LENGTH_FILTER_COVERAGE = 0.6

    /** Fraction of the shelf whose runtime is known. */
    fun durationCoverage(videos: List<Video>): Double {
        if (videos.isEmpty()) return 0.0
        return videos.count { it.lengthClass != null }.toDouble() / videos.size
    }

    /**
     * Whether to show the Short / Medium / Long chips at all.
     *
     * Deliberately not "any video has a duration". One watched video is not grounds for
     * offering a filter that will hide the other two hundred.
     */
    fun offerLengthFilter(videos: List<Video>): Boolean =
        durationCoverage(videos) >= LENGTH_FILTER_COVERAGE

    /**
     * Applies the chips.
     *
     * A video of unknown length is kept rather than hidden when a length is selected. It cannot
     * be *shown* to match, but discarding it would mean the filter quietly deletes everything
     * the app has not measured yet — which is exactly the failure this class exists to prevent.
     * Once coverage is good the unknowns are a handful, and seeing a handful of extra videos is
     * a far smaller harm than seeing none.
     */
    fun visible(
        videos: List<Video>,
        category: Category? = null,
        length: LengthClass? = null,
    ): List<Video> = videos.filter { video ->
        (category == null || video.category == category) &&
            (length == null || video.lengthClass == null || video.lengthClass == length)
    }
}
