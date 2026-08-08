package com.hillelsht.smart.data

import com.hillelsht.smart.data.local.ActivityDao
import com.hillelsht.smart.data.local.ContentSeeder
import com.hillelsht.smart.data.local.DailyActivityEntity
import com.hillelsht.smart.data.local.FactDao
import com.hillelsht.smart.data.local.ImageCacheDao
import com.hillelsht.smart.data.local.ImageCacheEntity
import com.hillelsht.smart.data.local.QuizDao
import com.hillelsht.smart.data.local.QuizResultEntity
import com.hillelsht.smart.data.local.ReviewDao
import com.hillelsht.smart.data.local.toDomain
import com.hillelsht.smart.data.local.toEntity
import com.hillelsht.smart.data.remote.WikiImageService
import com.hillelsht.smart.domain.CategoryMastery
import com.hillelsht.smart.domain.DailyPlan
import com.hillelsht.smart.domain.MasteryCalculator
import com.hillelsht.smart.domain.SessionPlanner
import com.hillelsht.smart.domain.Sm2Scheduler
import com.hillelsht.smart.domain.Streak
import com.hillelsht.smart.domain.StreakCalculator
import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.domain.model.Fact
import com.hillelsht.smart.domain.model.Rating
import com.hillelsht.smart.domain.model.ReviewState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/**
 * The single place the UI talks to.
 *
 * Scheduling decisions are delegated to the pure domain objects; this class only persists what
 * they compute and joins the results back together for the screens.
 */
class SmartRepository(
    private val factDao: FactDao,
    private val reviewDao: ReviewDao,
    private val quizDao: QuizDao,
    private val activityDao: ActivityDao,
    private val imageCacheDao: ImageCacheDao,
    private val seeder: ContentSeeder,
    private val wikiImageService: WikiImageService,
    private val today: () -> LocalDate = LocalDate::now,
) {

    private val imageLock = Mutex()

    suspend fun initialise() = seeder.seedIfNeeded()

    val facts: Flow<List<Fact>> =
        factDao.observeAll().map { rows -> rows.mapNotNull { it.toDomain() } }

    val reviewStates: Flow<Map<String, ReviewState>> =
        reviewDao.observeAll().map { rows -> rows.associate { it.factId to it.toDomain() } }

    fun factsIn(category: Category): Flow<List<Fact>> =
        factDao.observeByCategory(category.id).map { rows -> rows.mapNotNull { it.toDomain() } }

    suspend fun fact(id: String): Fact? = factDao.byId(id)?.toDomain()

    // --- Session planning -------------------------------------------------------------

    val plan: Flow<DailyPlan> = combine(facts, reviewStates, activity) { allFacts, states, days ->
        val date = today()
        SessionPlanner.plan(
            allFacts = allFacts,
            states = states,
            today = date,
            newLearnedToday = days.firstOrNull { it.date == date }?.newLearned ?: 0,
        )
    }

    val mastery: Flow<List<CategoryMastery>> = combine(facts, reviewStates) { allFacts, states ->
        val categoryOf = allFacts.associate { it.id to it.category }
        MasteryCalculator.byCategory(
            totals = allFacts.groupingBy { it.category }.eachCount(),
            states = states,
            categoryOf = { id -> categoryOf[id] },
        )
    }

    val streak: Flow<Streak> = activity.map { days ->
        StreakCalculator.compute(days.map { it.date }, today())
    }

    private val activity: Flow<List<DailyActivityEntity>> get() = activityDao.observeAll()

    val recentQuizzes: Flow<List<QuizResultEntity>> get() = quizDao.observeRecent(RECENT_QUIZ_LIMIT)

    // --- Study actions ----------------------------------------------------------------

    /**
     * Record a first exposure to a fact. Treated as a passing grade, which schedules the first
     * review for tomorrow — the point at which it would otherwise start slipping away.
     */
    suspend fun markLearned(factId: String) {
        val date = today()
        val existing = reviewDao.byId(factId)?.toDomain()
        val base = existing ?: Sm2Scheduler.initial(factId, date)
        reviewDao.upsert(Sm2Scheduler.schedule(base, Rating.GOOD, date).toEntity())
        bumpActivity(date, newLearned = 1, reviewsDone = 0)
    }

    /** Grade a review and reschedule it. */
    suspend fun grade(factId: String, rating: Rating) {
        val date = today()
        val base = reviewDao.byId(factId)?.toDomain() ?: Sm2Scheduler.initial(factId, date)
        reviewDao.upsert(Sm2Scheduler.schedule(base, rating, date).toEntity())
        bumpActivity(date, newLearned = 0, reviewsDone = 1)
    }

    /**
     * Store a finished quiz and feed every wrong answer back into the review queue as a lapse,
     * so a question you missed comes back rather than disappearing with the score screen.
     */
    suspend fun recordQuiz(category: Category?, total: Int, correct: Int, missedFactIds: List<String>) {
        val date = today()
        quizDao.insert(
            QuizResultEntity(takenOn = date, categoryId = category?.id, total = total, correct = correct),
        )
        missedFactIds.forEach { grade(it, Rating.AGAIN) }
        bumpActivity(date, newLearned = 0, reviewsDone = total)
    }

    private suspend fun bumpActivity(date: LocalDate, newLearned: Int, reviewsDone: Int) {
        val current = activityDao.byDate(date)
        activityDao.upsert(
            DailyActivityEntity(
                date = date,
                newLearned = (current?.newLearned ?: 0) + newLearned,
                reviewsDone = (current?.reviewsDone ?: 0) + reviewsDone,
            ),
        )
    }

    // --- Images -----------------------------------------------------------------------

    /**
     * Resolve a Wikipedia title to an image URL, remembering both hits and misses.
     *
     * A miss is cached as a null URL so a fact without a usable picture is not re-requested on
     * every appearance; the network is only consulted once per fact, ever.
     */
    suspend fun imageUrl(wikiTitle: String): String? {
        imageCacheDao.byTitle(wikiTitle)?.let { return it.imageUrl }

        return imageLock.withLock {
            // Another caller may have resolved it while this one waited for the lock.
            imageCacheDao.byTitle(wikiTitle)?.let { return@withLock it.imageUrl }

            val fetched = wikiImageService.fetch(wikiTitle)
            if (fetched == null) {
                // Network failure, as opposed to "this page has no image" — do not cache it,
                // so the picture can still appear once the device is back online.
                return@withLock null
            }
            imageCacheDao.upsert(
                ImageCacheEntity(
                    wikiTitle = wikiTitle,
                    imageUrl = fetched.imageUrl,
                    pageUrl = fetched.pageUrl,
                    fetchedAtEpochDay = today().toEpochDay(),
                ),
            )
            fetched.imageUrl
        }
    }

    suspend fun articleUrl(wikiTitle: String): String? {
        imageCacheDao.byTitle(wikiTitle)?.pageUrl?.let { return it }
        return wikiImageService.fetch(wikiTitle)?.pageUrl
    }

    private companion object {
        const val RECENT_QUIZ_LIMIT = 20
    }
}
