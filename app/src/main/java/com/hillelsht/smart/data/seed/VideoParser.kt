package com.hillelsht.smart.data.seed

import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.domain.model.Video
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The video catalog file — authoring and enriched flavours share this shape. */
@Serializable
data class VideoFile(
    val packId: String = "videos",
    val version: String? = null,
    val videos: List<SeedVideo>,
)

@Serializable
data class SeedVideo(
    val id: String,
    val youtubeId: String,
    val category: String,
    val title: String,
    val channel: String,
    val minutes: Int,
    val relatedFactIds: List<String> = emptyList(),
    /** Filled by the pipeline from oEmbed; absent in authoring files. */
    val thumbnailUrl: String? = null,
)

/**
 * Parses the curated video catalog. Pure Kotlin for the same reason as [ContentParser]:
 * the exact code that runs on device is the code the engine tests exercise on the JVM.
 */
object VideoParser {

    private val json = Json { ignoreUnknownKeys = true }

    data class ParsedCatalog(
        val packId: String,
        val version: String,
        val videos: List<Video>,
    )

    fun parse(raw: String, source: String): ParsedCatalog {
        val file = try {
            json.decodeFromString<VideoFile>(raw)
        } catch (e: Exception) {
            throw ContentParser.ContentException("$source is not a valid video catalog: ${e.message}")
        }

        val videos = file.videos.map { seed ->
            val category = Category.fromId(seed.category)
                ?: throw ContentParser.ContentException(
                    "$source: video '${seed.id}' has unknown category '${seed.category}'",
                )
            try {
                Video(
                    id = seed.id,
                    youtubeId = seed.youtubeId,
                    category = category,
                    title = seed.title,
                    channel = seed.channel,
                    minutes = seed.minutes,
                    thumbnailUrl = seed.thumbnailUrl?.takeIf { it.isNotBlank() },
                    relatedFactIds = seed.relatedFactIds.filter { it.isNotBlank() },
                )
            } catch (e: IllegalArgumentException) {
                throw ContentParser.ContentException("$source: ${e.message}")
            }
        }

        return ParsedCatalog(
            packId = file.packId,
            version = file.version ?: "text-${raw.hashCode()}",
            videos = videos,
        )
    }
}
