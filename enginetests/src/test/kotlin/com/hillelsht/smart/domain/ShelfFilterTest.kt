package com.hillelsht.smart.domain

import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.domain.model.LengthClass
import com.hillelsht.smart.domain.model.Video
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the Watch tab's filters.
 *
 * The bug these were written for: the length chips turned on as soon as one video had a known
 * duration, and durations only existed for videos you had already opened. Choosing Short then
 * hid everything else and the tab read "Nothing matches" over a shelf full of videos.
 */
class ShelfFilterTest {

    private fun video(
        id: String,
        category: Category = Category.SCIENCE,
        minutes: Int? = null,
    ) = Video(
        id = id,
        youtubeId = id.padEnd(11, 'x').take(11),
        category = category,
        title = "A video about something interesting",
        channel = "Some Channel",
        minutes = minutes,
        thumbnailUrl = null,
        relatedFactIds = emptyList(),
    )

    // --- the reported bug ------------------------------------------------------------------

    @Test
    fun `one watched video does not justify offering a length filter`() {
        // Exactly the shipped shelf: 20 videos, one of which you happened to open.
        val shelf = List(19) { video("v$it") } + video("watched", minutes = 10)
        assertFalse(
            ShelfFilter.offerLengthFilter(shelf),
            "5% coverage must not put chips on screen that would hide the other 95%",
        )
    }

    @Test
    fun `a length filter never empties a shelf it was offered for`() {
        // Whatever the coverage, picking a length must not wipe the tab.
        val shelf = List(10) { video("v$it") } + List(10) { video("m$it", minutes = 30) }
        LengthClass.entries.forEach { length ->
            val visible = ShelfFilter.visible(shelf, length = length)
            assertTrue(visible.isNotEmpty(), "$length emptied the shelf")
        }
    }

    @Test
    fun `videos of unknown length survive a length filter`() {
        val shelf = listOf(video("unknown"), video("short", minutes = 3))
        val visible = ShelfFilter.visible(shelf, length = LengthClass.SHORT)
        assertEquals(setOf("unknown", "short"), visible.map { it.id }.toSet())
    }

    // --- the filter still has to filter ------------------------------------------------------

    @Test
    fun `a length filter excludes videos known to be a different length`() {
        val shelf = listOf(
            video("short", minutes = 3),
            video("medium", minutes = 10),
            video("long", minutes = 40),
        )
        assertEquals(listOf("short"), ShelfFilter.visible(shelf, length = LengthClass.SHORT).map { it.id })
        assertEquals(listOf("long"), ShelfFilter.visible(shelf, length = LengthClass.LONG).map { it.id })
    }

    @Test
    fun `category filtering is exact`() {
        val shelf = listOf(
            video("sci", Category.SCIENCE),
            video("hist", Category.HISTORY),
            video("geo", Category.GEOGRAPHY),
        )
        assertEquals(listOf("hist"), ShelfFilter.visible(shelf, category = Category.HISTORY).map { it.id })
    }

    @Test
    fun `both filters compose`() {
        val shelf = listOf(
            video("a", Category.SCIENCE, minutes = 3),
            video("b", Category.SCIENCE, minutes = 40),
            video("c", Category.HISTORY, minutes = 3),
        )
        val visible = ShelfFilter.visible(shelf, Category.SCIENCE, LengthClass.SHORT)
        assertEquals(listOf("a"), visible.map { it.id })
    }

    // --- the coverage rule -------------------------------------------------------------------

    @Test
    fun `chips appear once most of the shelf has been measured`() {
        val measured = List(8) { video("m$it", minutes = 10) } + List(2) { video("u$it") }
        assertTrue(ShelfFilter.offerLengthFilter(measured), "80% coverage should offer the filter")
    }

    @Test
    fun `coverage is reported honestly`() {
        assertEquals(0.0, ShelfFilter.durationCoverage(emptyList()))
        assertEquals(0.0, ShelfFilter.durationCoverage(List(4) { video("v$it") }))
        assertEquals(0.5, ShelfFilter.durationCoverage(
            List(2) { video("v$it") } + List(2) { video("m$it", minutes = 8) },
        ))
        assertEquals(1.0, ShelfFilter.durationCoverage(List(3) { video("m$it", minutes = 8) }))
    }

    @Test
    fun `an empty shelf offers nothing and crashes on nothing`() {
        assertFalse(ShelfFilter.offerLengthFilter(emptyList()))
        assertTrue(ShelfFilter.visible(emptyList(), Category.SCIENCE, LengthClass.LONG).isEmpty())
    }
}
