package com.hillelsht.smart.domain

import com.hillelsht.smart.domain.MascotDirector.Activity
import com.hillelsht.smart.domain.MascotDirector.Surface
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Aryeh is meant to have a life rather than a loop. These pin the parts of that which are
 * decisions rather than drawing: that he does what the screen is for, that he does not repeat
 * himself, and that being left alone eventually settles him rather than cycling forever.
 */
class MascotDirectorTest {

    private val seeded = Random(20260809)

    @Test
    fun `he does what the screen is for`() {
        val expected = mapOf(
            Surface.LIBRARY to setOf(Activity.READ, Activity.WANDER),
            Surface.WATCH to setOf(Activity.WATCH, Activity.WANDER),
            Surface.TODAY to setOf(Activity.WANDER, Activity.POUNCE, Activity.NAP),
        )
        expected.forEach { (surface, allowed) ->
            val seen = (1..200).map { MascotDirector.next(surface, random = seeded).activity }.toSet()
            assertTrue(seen.all { it in allowed }, "$surface produced $seen, expected within $allowed")
        }
    }

    @Test
    fun `he never does the same thing twice running`() {
        Surface.entries.forEach { surface ->
            repeat(200) {
                val previous = Activity.WANDER
                val next = MascotDirector.next(surface, previous = previous, random = seeded).activity
                assertTrue(next != previous, "$surface repeated $previous")
            }
        }
    }

    @Test
    fun `left alone he goes to sleep`() {
        Surface.entries.forEach { surface ->
            val beat = MascotDirector.next(surface, idleMs = MascotDirector.IDLE_BEFORE_NAP_MS)
            assertEquals(Activity.NAP, beat.activity, "$surface should let him sleep")
        }
    }

    @Test
    fun `still being used he stays awake`() {
        // Watch is the one surface with no nap in its repertoire, so a mistaken idle rule there
        // would be obvious; check the boundary is respected rather than approximated.
        val beat = MascotDirector.next(Surface.WATCH, idleMs = MascotDirector.IDLE_BEFORE_NAP_MS - 1)
        assertTrue(beat.activity != Activity.NAP)
    }

    @Test
    fun `sleeping survives a change of tab`() {
        // Waking an animal because someone tapped Progress is worse than letting him lie.
        Surface.entries.forEach { surface ->
            assertTrue(MascotDirector.carriesOver(Activity.NAP, surface))
        }
    }

    @Test
    fun `an activity the new tab does not offer is dropped`() {
        assertFalse(MascotDirector.carriesOver(Activity.READ, Surface.WATCH))
        assertFalse(MascotDirector.carriesOver(Activity.POUNCE, Surface.LIBRARY))
        assertTrue(MascotDirector.carriesOver(Activity.WANDER, Surface.PROGRESS))
    }

    @Test
    fun `every beat has a sane duration`() {
        Surface.entries.forEach { surface ->
            repeat(100) {
                val beat = MascotDirector.next(surface, random = seeded)
                assertTrue(
                    beat.durationMs in 3_000L..45_000L,
                    "${beat.activity} ran for ${beat.durationMs}ms",
                )
            }
        }
    }

    @Test
    fun `celebrating is short and never chosen at random`() {
        // It is a response to something you did, so it must not turn up as idle filler.
        val idle = Surface.entries.flatMap { s ->
            (1..300).map { MascotDirector.next(s, random = seeded).activity }
        }
        assertFalse(Activity.CELEBRATE in idle, "celebration should only be triggered explicitly")
        assertTrue(MascotDirector.celebrate().durationMs <= 3_500L)
    }

    @Test
    fun `the same seed gives the same performance`() {
        // Determinism is what makes the previous tests meaningful rather than flaky.
        fun run() = (1..20).map { MascotDirector.next(Surface.TODAY, random = Random(7)) }
        assertEquals(run(), run())
    }
}
