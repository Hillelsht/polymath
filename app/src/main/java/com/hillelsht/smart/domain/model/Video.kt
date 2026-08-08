package com.hillelsht.smart.domain.model

/** Rough runtime bucket, so a viewer can pick by the time they actually have. */
enum class LengthClass(val label: String) {
    SHORT("Short"),
    MEDIUM("Medium"),
    LONG("Long"),
    ;

    companion object {
        fun fromMinutes(minutes: Int): LengthClass = when {
            minutes < 5 -> SHORT
            minutes <= 15 -> MEDIUM
            else -> LONG
        }
    }
}

/**
 * One curated video.
 *
 * The catalog is the safety mechanism: a video exists in the app only because a human put it
 * on the allowlist and CI verified it via YouTube's oEmbed endpoint. There is no search of
 * the wider platform and no recommendation feed to wander off through.
 */
data class Video(
    val id: String,
    val youtubeId: String,
    val category: Category,
    val title: String,
    val channel: String,
    /** Null when YouTube did not expose a duration; the UI simply omits it. */
    val minutes: Int?,
    val thumbnailUrl: String?,
    /** Facts from the curriculum this video teaches; powers "Quiz me on this". */
    val relatedFactIds: List<String>,
) {
    val lengthClass: LengthClass? get() = minutes?.let(LengthClass::fromMinutes)

    /** YouTube serves thumbnails keyless by id; used when enrichment supplied none. */
    val bestThumbnailUrl: String
        get() = thumbnailUrl ?: "https://img.youtube.com/vi/$youtubeId/hqdefault.jpg"

    init {
        require(id.isNotBlank()) { "Video id must not be blank" }
        require(YOUTUBE_ID_REGEX.matches(youtubeId)) { "Video $id has malformed youtubeId '$youtubeId'" }
        require(minutes == null || minutes > 0) { "Video $id has non-positive minutes" }
    }

    companion object {
        val YOUTUBE_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")
    }
}
