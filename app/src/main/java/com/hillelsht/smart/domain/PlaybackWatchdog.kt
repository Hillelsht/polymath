package com.hillelsht.smart.domain

/**
 * Decides whether a video is actually playing, and what to do when it is not.
 *
 * The Watch tab shipped with a self-healing blocklist that never fired, because it depended on
 * YouTube *telling* the app something. YouTube's error 152 ("this video is unavailable" in an
 * embedded player) does not reach the IFrame API's error callback at all — the player draws its
 * own screen and says nothing. So the app has to notice for itself that nothing happened.
 *
 * The liveness signal is anything that proves YouTube served the video: a duration, or a state
 * change to cued/buffering/playing. If none of those arrive inside [DEADLINE_MS], playback has
 * failed however silently.
 *
 * Kept free of Android types so `enginetests` can cover the rule directly — the same reason
 * [Sm2Scheduler] and [VideoCuration] live here rather than in the UI layer.
 */
object PlaybackWatchdog {

    /** Long enough for a slow connection to answer, short enough not to feel broken. */
    const val DEADLINE_MS = 7_000L

    /**
     * Why a video did not play. The distinction is not cosmetic: it decides whether the video
     * is removed from the shelf forever or merely handed to YouTube this once.
     */
    enum class Failure {
        /** This video refuses to embed. It will refuse tomorrow too. */
        VIDEO_REFUSED,

        /**
         * YouTube rejected *the app's* right to embed anything (its error 153, surfaced by the
         * player library as `REQUEST_MISSING_HTTP_REFERER`). Nothing to do with this video.
         */
        APP_REJECTED,

        /** Nothing came back in time. Could be error 152, could be a tunnel. */
        SILENT,
    }

    sealed interface Verdict {
        /** Something arrived from YouTube; stand down. */
        data object Playing : Verdict

        /** Still inside the deadline with no signal yet. */
        data object Waiting : Verdict

        /**
         * Playback failed. [blocklist] is the whole point of this class: only a per-video
         * refusal earns it.
         */
        data class Failed(val reason: Failure, val blocklist: Boolean) : Verdict
    }

    /**
     * @param elapsedMs since the video was cued
     * @param sawSignal true once a duration or a real player state has arrived
     * @param failure a failure the player reported explicitly, if any
     */
    fun assess(elapsedMs: Long, sawSignal: Boolean, failure: Failure? = null): Verdict {
        if (failure != null) return Verdict.Failed(failure, blocklist = failure.isPerVideo())
        if (sawSignal) return Verdict.Playing
        if (elapsedMs >= DEADLINE_MS) return Verdict.Failed(Failure.SILENT, blocklist = false)
        return Verdict.Waiting
    }

    /**
     * Only a refusal aimed at one video may remove it from the shelf.
     *
     * [Failure.APP_REJECTED] would otherwise be catastrophic: it fires identically for every
     * video, so blocklisting on it would empty the Watch tab one card at a time and the tab
     * would never refill. [Failure.SILENT] is barred for a quieter version of the same reason —
     * a timeout on a bad connection must not permanently delete a video that is perfectly fine.
     */
    private fun Failure.isPerVideo(): Boolean = this == Failure.VIDEO_REFUSED
}
