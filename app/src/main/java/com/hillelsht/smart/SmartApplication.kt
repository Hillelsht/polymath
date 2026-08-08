package com.hillelsht.smart

import android.app.Application
import android.content.Context
import com.hillelsht.smart.data.SmartRepository
import com.hillelsht.smart.data.local.ContentSeeder
import com.hillelsht.smart.data.local.SmartDatabase
import com.hillelsht.smart.data.remote.WikiImageService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency wiring.
 *
 * The graph is six objects deep; a DI framework here would add build complexity and annotation
 * processing for no benefit that a constructor call does not already provide.
 */
class AppContainer(context: Context) {

    private val database = SmartDatabase.build(context)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val wikiImageService = WikiImageService(
        client = httpClient,
        // Wikimedia's API policy asks for a descriptive User-Agent with contact details.
        userAgent = "SmartTriviaApp/1.0 (https://github.com/hillelsht/smart)",
    )

    val repository = SmartRepository(
        factDao = database.factDao(),
        reviewDao = database.reviewDao(),
        quizDao = database.quizDao(),
        activityDao = database.activityDao(),
        imageCacheDao = database.imageCacheDao(),
        seeder = ContentSeeder(context, database.factDao()),
        wikiImageService = wikiImageService,
    )
}

class SmartApplication : Application() {

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
}
