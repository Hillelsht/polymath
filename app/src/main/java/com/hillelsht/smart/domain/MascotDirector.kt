package com.hillelsht.smart.domain

import kotlin.random.Random

/**
 * Decides what Aryeh does next.
 *
 * The character is meant to have a life rather than a loop, so what he is doing is chosen here
 * — from which screen you are on, how long he has been at the current thing, and a seeded RNG —
 * and the UI layer only draws the answer. Keeping it out of Compose means the behaviour is
 * covered by the JVM test suite alongside [Sm2Scheduler] and [VideoCuration], and that a change
 * to how he acts cannot be made accidentally while adjusting how he looks.
 */
object MascotDirector {

    /**
     * Where he is. Only the browsing surfaces; the study flows deliberately have no mascot.
     *
     * [PLAY] is the game picker, not a game in progress. He is absent inside a run for the same
     * reason he is absent from Learn and Review, and more so in a game where he is meant to be
     * the one climbing.
     */
    enum class Surface { TODAY, LIBRARY, WATCH, PLAY, PROGRESS }

    enum class Activity {
        /** Ambling along the bottom of the screen. */
        WANDER,

        /** Crouch, spring, and knock the ball away. */
        POUNCE,

        /** Sitting with a book, turning pages. */
        READ,

        /** Sitting facing the screen. */
        WATCH,

        /** Rearing up — reserved for something you actually achieved. */
        CELEBRATE,

        /** Curled up asleep. */
        NAP,
    }

    data class Beat(val activity: Activity, val durationMs: Long)

    /**
     * What each surface is willing to show. He does what you do — reading on Library, watching
     * on Watch — because a companion doing something unrelated to the screen reads as wallpaper.
     */
    private val repertoire: Map<Surface, List<Activity>> = mapOf(
        Surface.TODAY to listOf(Activity.WANDER, Activity.POUNCE, Activity.WANDER, Activity.NAP),
        Surface.LIBRARY to listOf(Activity.READ, Activity.WANDER, Activity.READ),
        Surface.WATCH to listOf(Activity.WATCH, Activity.WATCH, Activity.WANDER),
        // He is restless where the games are, which is the point of the tab.
        Surface.PLAY to listOf(Activity.POUNCE, Activity.WANDER, Activity.POUNCE, Activity.NAP),
        Surface.PROGRESS to listOf(Activity.WANDER, Activity.READ, Activity.NAP),
    )

    private val durationMs: Map<Activity, LongRange> = mapOf(
        Activity.WANDER to 9_000L..16_000L,
        Activity.POUNCE to 4_000L..5_000L,
        Activity.READ to 12_000L..20_000L,
        Activity.WATCH to 14_000L..24_000L,
        Activity.CELEBRATE to 3_000L..3_500L,
        Activity.NAP to 20_000L..45_000L,
    )

    /** Long enough that he settles down only when you have genuinely stopped touching anything. */
    const val IDLE_BEFORE_NAP_MS = 45_000L

    /**
     * The next thing he does.
     *
     * @param surface the tab currently on screen
     * @param previous what he was just doing, so he does not repeat himself
     * @param idleMs how long since you last interacted with the app
     */
    fun next(
        surface: Surface,
        previous: Activity? = null,
        idleMs: Long = 0L,
        random: Random = Random.Default,
    ): Beat {
        // Left alone long enough, he sleeps wherever he is — but only once, then he keeps
        // sleeping rather than waking every cycle to fall asleep again.
        if (idleMs >= IDLE_BEFORE_NAP_MS) return beat(Activity.NAP, random)

        val options = repertoire.getValue(surface)
        // Never twice running, unless the surface offers nothing else.
        val fresh = options.filter { it != previous }.ifEmpty { options }
        return beat(fresh[random.nextInt(fresh.size)], random)
    }

    /**
     * Interrupts whatever he is doing with a celebration. Called when a session ends or a streak
     * grows — the one activity that is a response to you rather than a way to pass the time.
     */
    fun celebrate(random: Random = Random.Default): Beat = beat(Activity.CELEBRATE, random)

    /**
     * Whether an activity survives a move to another tab. Sleeping does — waking a sleeping
     * animal because someone tapped Progress is worse than letting him lie.
     */
    fun carriesOver(activity: Activity, to: Surface): Boolean =
        activity == Activity.NAP || activity in repertoire.getValue(to)

    private fun beat(activity: Activity, random: Random): Beat {
        val range = durationMs.getValue(activity)
        return Beat(activity, random.nextLong(range.first, range.last + 1))
    }
}
