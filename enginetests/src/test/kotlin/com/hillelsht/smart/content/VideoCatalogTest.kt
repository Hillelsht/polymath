package com.hillelsht.smart.content

import com.hillelsht.smart.data.seed.ChannelParser
import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.domain.model.LengthClass
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards the Watch tab's content.
 *
 * The first version of this feature shipped a hand-written list of YouTube ids, and CI found
 * that only 30 of 187 existed — most of the rest pointed at unrelated videos. Ids are no longer
 * authored *or* published: `channels.json` names channels, CI checks they resolve and allow
 * embedding, and the app discovers real uploads from their feeds at runtime. So the only
 * content left to guard here is the allowlist and the parser that reads it.
 */
class VideoCatalogTest {

    @Serializable
    private data class AllowlistFile(val channels: List<Entry>)

    @Serializable
    private data class Entry(val handle: String, val category: String)

    private val json = Json { ignoreUnknownKeys = true }

    private val channels: List<Entry> by lazy {
        val raw = javaClass.getResource("/content/channels.json")?.readText()
            ?: fail("Missing /content/channels.json")
        json.decodeFromString<AllowlistFile>(raw).channels
    }

    @Test
    fun `every allowlisted channel names a real category`() {
        val bad = channels.filter { Category.fromId(it.category) == null }
        assertTrue(bad.isEmpty(), "channels with unknown categories: ${bad.map { it.handle }}")
    }

    @Test
    fun `every subject is authored with slack for channels the prober drops`() {
        // This counts what is *authored*, not what survives. The first probe run dropped four
        // of five sports channels at once — all four handles had been renamed — so a subject
        // authored down to the bone loses its filter the moment a channel moves.
        val byCategory = channels.groupingBy { it.category }.eachCount()
        println("Allowlisted channels: ${channels.size}")
        Category.entries.forEach { category ->
            val count = byCategory[category.id] ?: 0
            println("  ${category.displayName.padEnd(20)} $count")
            assertTrue(count >= 5, "${category.displayName} has only $count channels authored")
        }
    }

    @Test
    fun `no channel is listed twice`() {
        val duplicates = channels.groupBy { it.handle.lowercase() }.filterValues { it.size > 1 }.keys
        assertTrue(duplicates.isEmpty(), "duplicate channel handles: $duplicates")
    }

    @Test
    fun `handles are bare, without an at-sign or a URL`() {
        // The pipeline builds youtube.com/@<handle>, so anything decorative breaks resolution.
        val malformed = channels.filter { it.handle.startsWith("@") || it.handle.contains("/") }
        assertTrue(malformed.isEmpty(), "handles must be bare: ${malformed.map { it.handle }}")
    }

    @Test
    fun `the parser reads a probed channel list`() {
        val raw = """
            {
              "version": "abc123",
              "channels": [
                {
                  "id": "UCYO_jab_esuFRV4b17AJtAw",
                  "handle": "3blue1brown",
                  "category": "science",
                  "displayName": "3Blue1Brown"
                }
              ]
            }
        """.trimIndent()

        val parsed = ChannelParser.parse(raw, "channels.json")
        assertEquals("abc123", parsed.version)

        val channel = parsed.channels.single()
        assertEquals("UCYO_jab_esuFRV4b17AJtAw", channel.id)
        assertEquals(Category.SCIENCE, channel.category)
        assertEquals("3Blue1Brown", channel.displayName)
    }

    @Test
    fun `channels the app cannot use are skipped rather than crashing the tab`() {
        // A stale pack could name a subject the app dropped, or carry a handle the prober never
        // resolved to a UC id. Either way the rest of the shelf must still load.
        val raw = """
            {
              "channels": [
                { "id": "UCYO_jab_esuFRV4b17AJtAw", "handle": "good", "category": "science" },
                { "id": "UCsooa4yRKGN", "handle": "unknown-subject", "category": "astrology" },
                { "id": "3blue1brown", "handle": "unresolved", "category": "science" }
              ]
            }
        """.trimIndent()

        val parsed = ChannelParser.parse(raw, "channels.json")
        assertEquals(listOf("good"), parsed.channels.map { it.handle })
        // No displayName in the file: the handle stands in, so a card is never blank.
        assertEquals("good", parsed.channels.single().displayName)
    }

    @Test
    fun `length classification follows the documented thresholds`() {
        // Durations now arrive from the player itself rather than a pack, but the shelf's
        // Short/Medium/Long filters still key off these boundaries.
        assertEquals(LengthClass.SHORT, LengthClass.fromMinutes(4))
        assertEquals(LengthClass.MEDIUM, LengthClass.fromMinutes(5))
        assertEquals(LengthClass.MEDIUM, LengthClass.fromMinutes(15))
        assertEquals(LengthClass.LONG, LengthClass.fromMinutes(16))
    }
}
