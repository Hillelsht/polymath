package com.hillelsht.smart.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** One entry in the remote catalog. */
@Serializable
data class RemotePack(
    val id: String,
    val name: String,
    val category: String,
    val version: String,
    val facts: Int,
    val bytes: Long,
    val file: String,
    /**
     * Position within its category for generated library shards, ascending by importance.
     * The six curated packs in `manifest.json` carry no such ordering and leave it at 0.
     */
    val shard: Int = 0,
)

@Serializable
data class PackManifest(
    val generated: String = "",
    val packs: List<RemotePack> = emptyList(),
)

/**
 * The generated library: thousands of facts in small shards, published by CI from Wikidata.
 *
 * Separate from [PackManifest] on purpose. The manifest is a *catalogue* — six named packs a
 * person chooses between in the Library tab. This is a *supply*, consumed automatically as the
 * pool of unlearned facts drains, and nobody should ever have to scroll a list of "Geography
 * 007" to keep learning.
 */
@Serializable
data class LibraryIndex(
    val generated: String = "",
    val shards: List<RemotePack> = emptyList(),
)

/**
 * The content catalog, served straight out of the GitHub repository.
 *
 * `raw.githubusercontent.com` is effectively a free CDN for a public repo: pushing new packs
 * to main updates every installed app on its next check, with no server and no app release.
 * This is what makes the catalog conceptually unbounded while the APK stays the same size.
 */
class PackService(
    private val client: OkHttpClient,
    private val userAgent: String,
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchManifest(): PackManifest? = get("$BASE/manifest.json")?.let { body ->
        try {
            json.decodeFromString<PackManifest>(body)
        } catch (e: Exception) {
            null
        }
    }

    /** Returns the raw JSON of a pack file, or null if unreachable. */
    suspend fun fetchPack(pack: RemotePack): String? = get("$BASE/${pack.file}")

    /**
     * The index of generated library shards. Null until CI has published one, which every
     * caller treats as "no library yet" rather than as an error — the app works exactly as
     * before on the bundled packs alone.
     */
    suspend fun fetchLibraryIndex(): LibraryIndex? = get("$BASE/$LIBRARY_INDEX")?.let { body ->
        try {
            json.decodeFromString<LibraryIndex>(body)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The Watch tab's channel allowlist. Only the channels ship — which videos exist is
     * discovered live on the device from those channels' feeds.
     */
    suspend fun fetchChannels(): String? = get("$BASE/$CHANNELS_FILE")

    /**
     * Runtimes for the videos the shelf can show, keyed by YouTube id.
     *
     * Published by CI because neither the channel feed nor the embed page carries a duration —
     * both were checked — so the only source is the YouTube Data API, and a phone cannot make
     * ~540 lookups per refresh. Absent until the pipeline has a key, which is why every caller
     * treats null as "no durations yet" rather than an error.
     */
    suspend fun fetchDurations(): String? = get("$BASE/$DURATIONS_FILE")

    /**
     * A game's content: the relic roster, a month of daily grids, a level set.
     *
     * Games ship their *pieces* from the repo for the same reason the library does — so adding a
     * relic or a month of puzzles is a commit rather than an app release, and so none of it sits
     * in the APK. Null means "not published yet or unreachable", and every caller is expected to
     * fall back to something playable rather than treat it as an error.
     *
     * @param path relative to `packs/play/`, e.g. `climb.json` or `chains/2026-08.json`.
     */
    suspend fun fetchGamePack(path: String): String? = get("$BASE/$PLAY_DIR/$path")

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string()
            }
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val BASE = "https://raw.githubusercontent.com/Hillelsht/smart/main/packs"
        const val CHANNELS_FILE = "channels.json"
        const val DURATIONS_FILE = "durations.json"
        const val LIBRARY_INDEX = "library/index.json"
        const val PLAY_DIR = "play"
    }
}
