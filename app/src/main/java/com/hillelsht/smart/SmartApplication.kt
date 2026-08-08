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
import com.hillelsht.smart.util.CrashLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Wikimedia's User-Agent policy rejects generic agents with 403 — including okhttp's default.
 * Every request the app makes to a Wikimedia host, API *or* image, must carry this.
 */
const val USER_AGENT = "SmartTriviaApp/2.1 (https://github.com/hillelsht/smart)"

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

    private val seeder = ContentSeeder(
        context = context,
        factDao = database.factDao(),
        packDao = database.packDao(),
        videoDao = database.videoDao(),
    )

    val repository = SmartRepository(
        factDao = database.factDao(),
        packDao = database.packDao(),
        reviewDao = database.reviewDao(),
        quizDao = database.quizDao(),
        activityDao = database.activityDao(),
        imageCacheDao = database.imageCacheDao(),
        videoDao = database.videoDao(),
        watchedDao = database.watchedDao(),
        seeder = seeder,
        wikiImageService = WikiImageService(httpClient, USER_AGENT),
        packService = PackService(httpClient, USER_AGENT),
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
        // Installed before anything else so a crash during startup is still captured.
        CrashLog.install(this)
        container = AppContainer(this)
        // Seeding 500+ facts takes a moment; doing it here means the first screen is never
        // blocked waiting for the curriculum to land.
        scope.launch { container.repository.initialise() }
    }

    /**
     * The app-wide Coil loader.
     *
     * Two things matter here. The [USER_AGENT] header is mandatory: without it Wikimedia
     * answers image requests with 403 and every card silently falls back to its gradient.
     * The bounded disk cache is the device half of "infinite pictures, small app" — images
     * stream from Wikimedia and only the most recent 256 MB stay local, oldest evicted first.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient {
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("User-Agent", USER_AGENT)
                            .build(),
                    )
                }
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("fact_images"))
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .crossfade(true)
        .build()
}
