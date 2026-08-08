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
)

@Serializable
data class PackManifest(
    val generated: String = "",
    val packs: List<RemotePack> = emptyList(),
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
    }
}
