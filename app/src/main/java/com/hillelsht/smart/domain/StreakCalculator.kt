package com.hillelsht.smart.domain

import java.time.LocalDate

data class Streak(val current: Int, val longest: Int, val studiedToday: Boolean)

/**
 * Streaks from the set of dates on which the learner actually studied.
 *
 * The current streak survives a day that has not ended yet: if you studied yesterday but
 * haven't opened the app today, the streak still reads as alive. It only breaks once a full
 * day has been skipped — punishing someone at 9am for not having studied yet is how streak
 * mechanics turn hostile.
 */
object StreakCalculator {

    fun compute(activeDates: Collection<LocalDate>, today: LocalDate): Streak {
        if (activeDates.isEmpty()) return Streak(current = 0, longest = 0, studiedToday = false)

        val days = activeDates.toSortedSet()
        val studiedToday = today in days

        val anchor = when {
            studiedToday -> today
            today.minusDays(1) in days -> today.minusDays(1)
            else -> null
        }

        var current = 0
        if (anchor != null) {
            var cursor: LocalDate = anchor
            while (cursor in days) {
                current++
                cursor = cursor.minusDays(1)
            }
        }

        var longest = 0
        var run = 0
        var previous: LocalDate? = null
        for (day in days) {
            run = if (previous != null && previous.plusDays(1) == day) run + 1 else 1
            longest = maxOf(longest, run)
            previous = day
        }

        return Streak(current = current, longest = maxOf(longest, current), studiedToday = studiedToday)
    }
}
