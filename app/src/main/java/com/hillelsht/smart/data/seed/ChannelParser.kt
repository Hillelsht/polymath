package com.hillelsht.smart.data.seed

import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.domain.model.Language
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The allowlist the app ships and refreshes: which channels may appear in Watch.
 *
 * This is the only content the Watch tab carries — a few KB of channel ids. Which *videos*
 * exist is discovered live from those channels' feeds on the device, so the shelf is never a
 * snapshot and the APK never grows with it.
 */
@Serializable
data class ChannelFile(
    val version: String? = null,
    val channels: List<SeedChannel> = emptyList(),
)

@Serializable
data class SeedChannel(
    /** The stable UC… id, resolved by CI from the human-friendly handle. */
    val id: String,
    val handle: String,
    val category: String,
    val displayName: String? = null,
    /**
     * The language this channel teaches in. Absent means English — which every channel in this
     * allowlist was before Russian existed, so an older published file still parses correctly.
     */
    val language: String = Language.default.tag,
)

/** A channel the Watch tab may draw from. */
data class WatchChannel(
    val id: String,
    val handle: String,
    val category: Category,
    val displayName: String,
    val language: Language = Language.default,
)

object ChannelParser {

    private val json = Json { ignoreUnknownKeys = true }

    data class ParsedChannels(val version: String, val channels: List<WatchChannel>)

    fun parse(raw: String, source: String): ParsedChannels {
        val file = try {
            json.decodeFromString<ChannelFile>(raw)
        } catch (e: Exception) {
            throw ContentParser.ContentException("$source is not a valid channel list: ${e.message}")
        }

        val channels = file.channels.mapNotNull { seed ->
            val category = Category.fromId(seed.category) ?: return@mapNotNull null
            if (!seed.id.startsWith("UC")) return@mapNotNull null
            WatchChannel(
                id = seed.id,
                handle = seed.handle,
                category = category,
                displayName = seed.displayName?.takeIf { it.isNotBlank() } ?: seed.handle,
                language = Language.fromTag(seed.language),
            )
        }

        return ParsedChannels(
            version = file.version ?: "text-${raw.hashCode()}",
            channels = channels,
        )
    }
}
