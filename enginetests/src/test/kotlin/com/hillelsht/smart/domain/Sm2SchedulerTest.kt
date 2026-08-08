package com.hillelsht.smart.domain

import com.hillelsht.smart.domain.model.Phase
import com.hillelsht.smart.domain.model.Rating
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Sm2SchedulerTest {

    private val today: LocalDate = LocalDate.of(2026, 1, 1)

    private fun fresh() = Sm2Scheduler.initial("f1", today)

    @Test
    fun `a new fact is due immediately with default ease`() {
        val state = fresh()
        assertEquals(Phase.NEW, state.phase)
        assertEquals(0, state.intervalDays)
        assertEquals(Sm2Scheduler.DEFAULT_EASE, state.easeFactor)
        assertTrue(state.isDue(today))
        assertEquals(null, state.accuracy)
    }

    @Test
    fun `the canonical good progression is 1 then 6 then interval times ease`() {
        var state = Sm2Scheduler.schedule(fresh(), Rating.GOOD, today)
        assertEquals(1, state.intervalDays)
        assertEquals(today.plusDays(1), state.dueDate)

        state = Sm2Scheduler.schedule(state, Rating.GOOD, today.plusDays(1))
        assertEquals(6, state.intervalDays)

        state = Sm2Scheduler.schedule(state, Rating.GOOD, today.plusDays(7))
        // 6 * 2.5 = 15, and Good leaves ease untouched.
        assertEquals(15, state.intervalDays)
        assertEquals(Sm2Scheduler.DEFAULT_EASE, state.easeFactor, 1e-9)
        assertEquals(Phase.REVIEW, state.phase)
    }

    @Test
    fun `again resets repetitions, records a lapse, and returns the fact the same day`() {
        var state = Sm2Scheduler.schedule(fresh(), Rating.GOOD, today)
        state = Sm2Scheduler.schedule(state, Rating.GOOD, today.plusDays(1))
        assertEquals(6, state.intervalDays)

        val lapsedOn = today.plusDays(7)
        state = Sm2Scheduler.schedule(state, Rating.AGAIN, lapsedOn)

        assertEquals(Phase.LEARNING, state.phase)
        assertEquals(0, state.repetitions)
        assertEquals(0, state.intervalDays)
        assertEquals(lapsedOn, state.dueDate)
        assertTrue(state.isDue(lapsedOn))
        assertEquals(1, state.lapses)
    }

    @Test
    fun `ease moves by the SM-2 polynomial and never drops below the floor`() {
        assertEquals(2.6, Sm2Scheduler.nextEase(2.5, Rating.EASY), 1e-9)
        assertEquals(2.5, Sm2Scheduler.nextEase(2.5, Rating.GOOD), 1e-9)
        assertEquals(2.36, Sm2Scheduler.nextEase(2.5, Rating.HARD), 1e-9)
        assertEquals(2.18, Sm2Scheduler.nextEase(2.5, Rating.AGAIN), 1e-9)

        var ease = 2.5
        repeat(20) { ease = Sm2Scheduler.nextEase(ease, Rating.AGAIN) }
        assertEquals(Sm2Scheduler.MIN_EASE, ease, 1e-9)
    }

    @Test
    fun `easy outpaces good, which outpaces hard`() {
        val mature = Sm2Scheduler.schedule(
            Sm2Scheduler.schedule(
                Sm2Scheduler.schedule(fresh(), Rating.GOOD, today),
                Rating.GOOD,
                today.plusDays(1),
            ),
            Rating.GOOD,
            today.plusDays(7),
        )

        val hard = Sm2Scheduler.schedule(mature, Rating.HARD, today.plusDays(22)).intervalDays
        val good = Sm2Scheduler.schedule(mature, Rating.GOOD, today.plusDays(22)).intervalDays
        val easy = Sm2Scheduler.schedule(mature, Rating.EASY, today.plusDays(22)).intervalDays

        assertTrue(hard < good, "hard ($hard) should be shorter than good ($good)")
        assertTrue(good < easy, "good ($good) should be shorter than easy ($easy)")
    }

    @Test
    fun `a passing grade always pushes the interval further out than before`() {
        for (rating in listOf(Rating.HARD, Rating.GOOD, Rating.EASY)) {
            var state = fresh()
            var day = today
            var previous = 0
            repeat(15) {
                state = Sm2Scheduler.schedule(state, rating, day)
                assertTrue(
                    state.intervalDays > previous || state.intervalDays == Sm2Scheduler.MAX_INTERVAL_DAYS,
                    "$rating went from $previous to ${state.intervalDays}",
                )
                previous = state.intervalDays
                day = state.dueDate
            }
        }
    }

    @Test
    fun `intervals are capped so nothing is scheduled past the horizon`() {
        var state = fresh()
        var day = today
        repeat(40) {
            state = Sm2Scheduler.schedule(state, Rating.EASY, day)
            day = state.dueDate
        }
        assertEquals(Sm2Scheduler.MAX_INTERVAL_DAYS, state.intervalDays)
    }

    @Test
    fun `accuracy counts every grade except again`() {
        var state = Sm2Scheduler.schedule(fresh(), Rating.GOOD, today)
        state = Sm2Scheduler.schedule(state, Rating.AGAIN, today.plusDays(1))
        state = Sm2Scheduler.schedule(state, Rating.GOOD, today.plusDays(1))
        state = Sm2Scheduler.schedule(state, Rating.EASY, today.plusDays(2))

        assertEquals(4, state.totalReviews)
        assertEquals(3, state.correctReviews)
        assertEquals(0.75, state.accuracy!!, 1e-9)
    }
}
