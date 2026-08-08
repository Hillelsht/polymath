package com.hillelsht.smart.content

import com.hillelsht.smart.data.seed.VideoParser
import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.domain.model.LengthClass
import com.hillelsht.smart.domain.model.Video
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards the Watch tab's content.
 *
 * The first version of this feature shipped a hand-written list of YouTube ids, and CI found
 * that only 30 of 187 existed — most of the rest pointed at unrelated videos. Ids are no
 * longer authored at all: `channels.json` names channels, and the pipeline discovers real
 * uploads from them. So the thing worth testing here is the allowlist and the parser, since
 * the catalog itself is machine-generated and verified in CI.
 */
class VideoCatalogTest {

    @Serializable
    private data class ChannelFile(val channels: List<Channel>)

    @Serializable
    private data class Channel(val handle: String, val category: String)

    private val json = Json { ignoreUnknownKeys = true }

    private val channels: List<Channel> by lazy {
        val raw = javaClass.getResource("/content/channels.json")?.readText()
            ?: fail("Missing /content/channels.json")
        json.decodeFromString<ChannelFile>(raw).channels
    }

    @Test
    fun `every allowlisted channel names a real category`() {
        val bad = channels.filter { Category.fromId(it.category) == null }
        assertTrue(bad.isEmpty(), "channels with unknown categories: ${bad.map { it.handle }}")
    }

    @Test
    fun `every subject has channels feeding it`() {
        val byCategory = channels.groupingBy { it.category }.eachCount()
        println("Allowlisted channels: ${channels.size}")
        Category.entries.forEach { category ->
            val count = byCategory[category.id] ?: 0
            println("  ${category.displayName.padEnd(20)} $count")
            assertTrue(count >= 3, "${category.displayName} has only $count channels")
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
    fun `the parser reads a pipeline-shaped catalog`() {
        val raw = """
            {
              "packId": "videos",
              "version": "abc123",
              "videos": [
                {
                  "id": "vid-aircAruvnKk",
                  "youtubeId": "aircAruvnKk",
                  "category": "science",
                  "title": "But what is a neural network?",
                  "channel": "3blue1brown",
                  "minutes": 19,
                  "thumbnailUrl": "https://i.ytimg.com/vi/aircAruvnKk/hqdefault.jpg",
                  "relatedFactIds": ["sci-022"]
                }
              ]
            }
        """.trimIndent()

        val catalog = VideoParser.parse(raw, "videos.json")
        assertEquals("videos", catalog.packId)
        assertEquals("abc123", catalog.version)

        val video = catalog.videos.single()
        assertEquals("aircAruvnKk", video.youtubeId)
        assertEquals(Category.SCIENCE, video.category)
        assertEquals(LengthClass.LONG, video.lengthClass)
        assertTrue(Video.YOUTUBE_ID_REGEX.matches(video.youtubeId))
    }

    @Test
    fun `a video with no duration still parses and simply has no length`() {
        // YouTube does not always expose lengthSeconds; an unknown runtime must not drop
        // the video or crash the shelf.
        val raw = """
            {
              "packId": "videos",
              "videos": [
                {
                  "id": "vid-aircAruvnKk",
                  "youtubeId": "aircAruvnKk",
                  "category": "science",
                  "title": "Something",
                  "channel": "3blue1brown"
                }
              ]
            }
        """.trimIndent()

        val video = VideoParser.parse(raw, "videos.json").videos.single()
        assertNull(video.minutes)
        assertNull(video.lengthClass)
        assertTrue(video.bestThumbnailUrl.contains("aircAruvnKk"))
    }

    @Test
    fun `length classification follows the documented thresholds`() {
        assertEquals(LengthClass.SHORT, LengthClass.fromMinutes(4))
        assertEquals(LengthClass.MEDIUM, LengthClass.fromMinutes(5))
        assertEquals(LengthClass.MEDIUM, LengthClass.fromMinutes(15))
        assertEquals(LengthClass.LONG, LengthClass.fromMinutes(16))
    }
}
