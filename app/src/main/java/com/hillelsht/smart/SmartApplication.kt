package com.hillelsht.smart

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.hillelsht.smart.data.SmartRepository
import com.hillelsht.smart.data.local.ContentSeeder
import com.hillelsht.smart.data.local.SmartDatabase
import com.hillelsht.smart.data.remote.PackService
import com.hillelsht.smart.data.remote.WikiImageService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manual dependency wiring.
 *
 * The graph is a handful of objects deep; a DI framework here would add build complexity and
 * annotation processing for no benefit a constructor call does not already provide.
 */
class AppContainer(context: Context) {

    private val database = SmartDatabase.build(context)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // Wikimedia's API policy asks for a descriptive User-Agent with a contact point.
    private val userAgent = "SmartTriviaApp/2.0 (https://github.com/hillelsht/smart)"

    private val seeder = ContentSeeder(context, database.factDao(), database.packDao())

    val repository = SmartRepository(
        factDao = database.factDao(),
        packDao = database.packDao(),
        reviewDao = database.reviewDao(),
        quizDao = database.quizDao(),
        activityDao = database.activityDao(),
        imageCacheDao = database.imageCacheDao(),
        seeder = seeder,
        wikiImageService = WikiImageService(httpClient, userAgent),
        packService = PackService(httpClient, userAgent),
        packStorage = { fileName, content ->
            File(ContentSeeder.downloadedPacksDir(context), fileName).writeText(content)
        },
    )
}

class SmartApplication : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Seeding 500+ facts takes a moment; doing it here means the first screen is never
        // blocked waiting for the curriculum to land.
        scope.launch { container.repository.initialise() }
    }

    /**
     * The app-wide Coil loader with an explicit, bounded disk cache. This is the half of
     * "infinite pictures, small app" that lives on the device: images stream from Wikipedia's
     * servers and only the most recent 256 MB stay local, oldest evicted first.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("fact_images"))
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .crossfade(true)
        .build()
}
