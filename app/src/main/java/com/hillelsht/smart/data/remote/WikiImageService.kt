package com.hillelsht.smart.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/** What the app needs from a Wikipedia page: a picture and a link back to the source. */
data class WikiImage(val imageUrl: String?, val pageUrl: String?)

/**
 * Runtime fallback resolver for facts that shipped without a pre-resolved [WikiImage].
 *
 * The primary image path is build-time: the CI pipeline resolves every fact to a direct
 * thumbnail URL and the app just loads it. This class only covers stragglers, via the classic
 * Action API (`w/api.php`), which is the most permissive endpoint Wikipedia has. Failures are
 * logged loudly — a silent image failure already cost one debugging round trip.
 */
class WikiImageService(
    private val client: OkHttpClient,
    private val userAgent: String,
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(wikiTitle: String): WikiImage? = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(wikiTitle, "UTF-8")
        val url = "https://en.wikipedia.org/w/api.php" +
            "?action=query&format=json&redirects=1" +
            "&prop=pageimages%7Cinfo&pithumbsize=640&inprop=url" +
            "&titles=$encoded"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Wikipedia returned HTTP ${response.code} for '$wikiTitle'")
                    return@withContext null
                }
                val body = response.body?.string() ?: return@withContext null
                parse(body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Image lookup failed for '$wikiTitle': ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** The Action API keys pages by numeric id, so this walks the JSON generically. */
    private fun parse(body: String): WikiImage? {
        val pages = json.parseToJsonElement(body)
            .jsonObject["query"]?.jsonObject?.get("pages")?.jsonObject
            ?: return null
        val page = pages.values.firstOrNull()?.jsonObject ?: return null

        val thumb = page["thumbnail"]?.jsonObject?.get("source")?.jsonPrimitive?.content
        val fullUrl = page["fullurl"]?.jsonPrimitive?.content
        return WikiImage(imageUrl = thumb, pageUrl = fullUrl)
    }

    private companion object {
        const val TAG = "WikiImageService"
    }
}
