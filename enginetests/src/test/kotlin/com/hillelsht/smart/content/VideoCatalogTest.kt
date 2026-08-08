package com.hillelsht.smart.content

import com.hillelsht.smart.data.seed.ContentParser
import com.hillelsht.smart.data.seed.VideoParser
import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.domain.model.LengthClass
import com.hillelsht.smart.domain.model.Video
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Validates the curated video catalog through the production parser.
 *
 * The catalog is the app's entire content-safety mechanism: a video can only be watched
 * because it is on this list. A malformed or mislinked entry therefore has to fail the build,
 * not the user's evening.
 */
class VideoCatalogTest {

    private val catalog: VideoParser.ParsedCatalog by lazy {
        val raw = javaClass.getResource("/content/videos.json")?.readText()
            ?: fail("Missing /content/videos.json")
        VideoParser.parse(raw, "videos.json")
    }

    private val videos: List<Video> get() = catalog.videos

    private val factIds: Set<String> by lazy {
        Category.entries.flatMap { category ->
            val raw = javaClass.getResource("/content/${category.id}.json")?.readText()
                ?: fail("Missing curriculum file for ${category.id}")
            ContentParser.parseFile(raw, "${category.id}.json")
        }.map { it.id }.toSet()
    }

    @Test
    fun `the catalog parses and ships a real shelf of videos`() {
        assertTrue(videos.size >= 100, "expected at least 100 videos, found ${videos.size}")
        println("Video catalog: ${videos.size} videos")
        Category.entries.forEach { category ->
            val count = videos.count { it.category == category }
            println("  ${category.displayName.padEnd(20)} $count")
            assertTrue(count >= 20, "${category.displayName} has only $count videos")
        }
    }

    @Test
    fun `video ids are unique`() {
        val duplicates = videos.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertTrue(duplicates.isEmpty(), "duplicate video ids: $duplicates")
    }

    @Test
    fun `no video appears twice under different entries`() {
        // The pipeline rewrites titles from YouTube's canonical metadata, so two entries
        // sharing an id become two identical-looking cards on the shelf.
        val duplicates = videos.groupBy { it.youtubeId }.filterValues { it.size > 1 }
        assertTrue(
            duplicates.isEmpty(),
            "same video listed more than once: ${duplicates.mapValues { it.value.map { v -> v.id } }}",
        )
    }

    @Test
    fun `every youtube id is a well-formed 11-character id`() {
        val malformed = videos.filterNot { Video.YOUTUBE_ID_REGEX.matches(it.youtubeId) }
        assertTrue(malformed.isEmpty(), "malformed ids: ${malformed.map { it.id to it.youtubeId }}")
    }

    @Test
    fun `every related fact actually exists in the curriculum`() {
        val dangling = videos.flatMap { video ->
            video.relatedFactIds.filterNot { it in factIds }.map { "${video.id} -> $it" }
        }
        assertTrue(dangling.isEmpty(), "videos linked to non-existent facts: $dangling")
    }

    @Test
    fun `most videos teach something the app can quiz on`() {
        val linked = videos.count { it.relatedFactIds.isNotEmpty() }
        assertTrue(
            linked >= videos.size * 0.9,
            "only $linked of ${videos.size} videos link to facts; the Quiz me button needs them",
        )
    }

    @Test
    fun `the shelf offers a range of lengths, not one format`() {
        val spread = videos.groupingBy { it.lengthClass }.eachCount()
        println("Length spread: $spread")
        LengthClass.entries.forEach { length ->
            assertTrue((spread[length] ?: 0) > 0, "no $length videos in the catalog")
        }
    }

    @Test
    fun `length classification follows the documented thresholds`() {
        assertEquals(LengthClass.SHORT, LengthClass.fromMinutes(4))
        assertEquals(LengthClass.MEDIUM, LengthClass.fromMinutes(5))
        assertEquals(LengthClass.MEDIUM, LengthClass.fromMinutes(15))
        assertEquals(LengthClass.LONG, LengthClass.fromMinutes(16))
    }

    @Test
    fun `a thumbnail is always available, resolved or derived`() {
        videos.forEach { video ->
            assertTrue(
                video.bestThumbnailUrl.contains(video.youtubeId) ||
                    video.bestThumbnailUrl.startsWith("https://"),
                "${video.id} has no usable thumbnail",
            )
        }
    }
}
