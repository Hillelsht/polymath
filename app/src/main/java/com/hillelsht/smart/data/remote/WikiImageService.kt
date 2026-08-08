package com.hillelsht.smart.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/** What the app needs from a Wikipedia page: a picture and a link back to the source. */
data class WikiImage(val imageUrl: String?, val pageUrl: String?)

/**
 * Resolves a Wikipedia article title to an image.
 *
 * Uses the REST summary endpoint rather than guessing Wikimedia Commons filenames: article
 * titles are stable and knowable ("Mona Lisa"), whereas the exact file name behind a picture
 * is neither. The endpoint hands back a pre-scaled thumbnail, so the app is not downloading
 * 40-megapixel scans of paintings onto a phone.
 */
class WikiImageService(
    private val client: OkHttpClient,
    private val userAgent: String,
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(wikiTitle: String): WikiImage? = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(wikiTitle.replace(' ', '_'), "UTF-8")
            .replace("+", "_")
        val request = Request.Builder()
            .url("$ENDPOINT$encoded")
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val summary = json.decodeFromString<Summary>(body)
                WikiImage(
                    imageUrl = summary.thumbnail?.source ?: summary.originalImage?.source,
                    pageUrl = summary.contentUrls?.desktop?.page,
                )
            }
        } catch (e: Exception) {
            // Offline or rate-limited: the UI falls back to its gradient placeholder.
            null
        }
    }

    @Serializable
    private data class Summary(
        val thumbnail: Image? = null,
        @SerialName("originalimage") val originalImage: Image? = null,
        @SerialName("content_urls") val contentUrls: ContentUrls? = null,
    )

    @Serializable
    private data class Image(val source: String)

    @Serializable
    private data class ContentUrls(val desktop: Desktop? = null)

    @Serializable
    private data class Desktop(val page: String? = null)

    private companion object {
        const val ENDPOINT = "https://en.wikipedia.org/api/rest_v1/page/summary/"
    }
}
