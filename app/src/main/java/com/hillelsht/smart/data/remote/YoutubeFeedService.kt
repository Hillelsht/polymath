package com.hillelsht.smart.data.remote

import android.util.Log
import android.util.Xml
import com.hillelsht.smart.domain.FeedEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Reads channel upload feeds straight from YouTube, on the device.
 *
 * This is what makes the Watch tab live rather than a snapshot: nothing about which videos
 * exist ships in the APK, and a video uploaded an hour ago appears the next time the tab is
 * opened. The feed endpoint is public, keyless, and small — a few tens of KB per channel.
 */
class YoutubeFeedService(
    private val client: OkHttpClient,
    private val userAgent: String,
) {

    /**
     * Fetches every channel at once, bounded so a phone on mobile data opens a handful of
     * sockets rather than thirty. A channel that fails is simply absent from the shelf; one
     * dead feed must never empty the tab.
     */
    suspend fun fetchAll(channelIds: List<String>): Map<String, List<FeedEntry>> = coroutineScope {
        val gate = Semaphore(MAX_CONCURRENT)
        channelIds
            .map { id -> async { id to gate.withPermit { fetch(id) } } }
            .awaitAll()
            .filter { (_, entries) -> entries.isNotEmpty() }
            .toMap()
    }

    suspend fun fetch(channelId: String): List<FeedEntry> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$FEED$channelId")
            .header("User-Agent", userAgent)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Feed for $channelId returned HTTP ${response.code}")
                    return@withContext emptyList()
                }
                parse(response.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Feed for $channelId failed: ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
    }

    /**
     * Pulls the fields the shelf needs out of the Atom/MRSS feed.
     *
     * Written against the parser's event stream rather than a DOM: these feeds are small but
     * there are thirty of them per refresh, and streaming keeps the whole thing off the heap.
     */
    internal fun parse(xml: String): List<FeedEntry> {
        if (xml.isBlank()) return emptyList()

        val entries = mutableListOf<FeedEntry>()
        var id: String? = null
        var title: String? = null
        var thumbnail: String? = null
        var views = 0L
        var inEntry = false

        try {
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setInput(StringReader(xml))
            }

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "entry" -> {
                            inEntry = true
                            id = null; title = null; thumbnail = null; views = 0L
                        }
                        "videoId" -> if (inEntry) id = parser.nextText().trim()
                        "title" -> if (inEntry && title == null) title = parser.nextText().trim()
                        "thumbnail" -> if (inEntry) thumbnail = parser.getAttributeValue(null, "url")
                        "statistics" -> if (inEntry) {
                            views = parser.getAttributeValue(null, "views")?.toLongOrNull() ?: 0L
                        }
                    }

                    XmlPullParser.END_TAG -> if (parser.name == "entry") {
                        val videoId = id
                        val entryTitle = title
                        if (inEntry && !videoId.isNullOrBlank() && !entryTitle.isNullOrBlank()) {
                            entries += FeedEntry(
                                youtubeId = videoId,
                                title = entryTitle,
                                thumbnailUrl = thumbnail
                                    ?: "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
                                views = views,
                            )
                        }
                        inEntry = false
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Malformed feed: ${e.message}")
            return entries
        }
        return entries
    }

    private companion object {
        const val TAG = "YoutubeFeedService"
        const val FEED = "https://www.youtube.com/feeds/videos.xml?channel_id="
        const val MAX_CONCURRENT = 6
    }
}
