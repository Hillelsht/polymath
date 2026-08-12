package com.hillelsht.smart.data

import com.hillelsht.smart.data.local.ActivityDao
import com.hillelsht.smart.data.local.BlockedVideoDao
import com.hillelsht.smart.data.local.BlockedVideoEntity
import com.hillelsht.smart.data.local.ChannelDao
import com.hillelsht.smart.data.local.ContentSeeder
import com.hillelsht.smart.data.local.DailyActivityEntity
import com.hillelsht.smart.data.local.FactDao
import com.hillelsht.smart.data.local.GameDailyEntity
import com.hillelsht.smart.data.local.GameDao
import com.hillelsht.smart.data.local.GameProgressEntity
import com.hillelsht.smart.data.local.GameRunEntity
import com.hillelsht.smart.data.local.ImageCacheDao
import com.hillelsht.smart.data.local.ImageCacheEntity
import com.hillelsht.smart.data.local.PackDao
import com.hillelsht.smart.data.local.PackEntity
import com.hillelsht.smart.data.local.QuizDao
import com.hillelsht.smart.data.local.QuizResultEntity
import com.hillelsht.smart.data.local.ReviewDao
import com.hillelsht.smart.data.local.VideoDao
import com.hillelsht.smart.data.local.VideoDurationDao
import com.hillelsht.smart.data.local.VideoDurationEntity
import com.hillelsht.smart.data.local.WatchedDao
import com.hillelsht.smart.data.local.WatchedVideoEntity
import com.hillelsht.smart.data.local.toDomain
import com.hillelsht.smart.data.local.toEntity
import com.hillelsht.smart.data.remote.PackManifest
import com.hillelsht.smart.data.remote.PackService
import com.hillelsht.smart.data.remote.RemotePack
import com.hillelsht.smart.data.remote.WikiImageService
import com.hillelsht.smart.data.remote.YoutubeFeedService
import com.hillelsht.smart.data.seed.ChannelParser
import com.hillelsht.smart.data.seed.ContentParser
import com.hillelsht.smart.domain.CategoryMastery
import com.hillelsht.smart.domain.DailyPlan
import com.hillelsht.smart.domain.FeedEntry
import com.hillelsht.smart.domain.LibraryShard
import com.hillelsht.smart.domain.LibraryTopUp
import com.hillelsht.smart.domain.MasteryCalculator
import com.hillelsht.smart.domain.SessionPlanner
import com.hillelsht.smart.domain.Sm2Scheduler
import com.hillelsht.smart.domain.Streak
import com.hillelsht.smart.domain.StreakCalculator
import com.hillelsht.smart.domain.VideoCuration
import com.hillelsht.smart.domain.play.GameId
import com.hillelsht.smart.domain.play.chains.ChainsGroup
import com.hillelsht.smart.domain.play.chains.ChainsPuzzle
import com.hillelsht.smart.domain.play.chess.Chess
import com.hillelsht.smart.domain.play.chess.Fen
import com.hillelsht.smart.domain.play.chess.Gambit
import com.hillelsht.smart.domain.play.chess.GambitMatch
import com.hillelsht.smart.domain.play.chess.GambitOutcome
import com.hillelsht.smart.domain.play.climb.Relic
import com.hillelsht.smart.domain.play.climb.Relics
import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.domain.model.Fact
import com.hillelsht.smart.domain.model.Language
import com.hillelsht.smart.domain.model.Phase
import com.hillelsht.smart.domain.model.Rating
import com.hillelsht.smart.domain.model.ReviewState
import com.hillelsht.smart.domain.model.Video
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The single place the UI talks to.
 *
 * Scheduling decisions are delegated to the pure domain objects; this class only persists what
 * they compute and joins the results back together for the screens.
 */
class SmartRepository(
    private val factDao: FactDao,
    private val packDao: PackDao,
    private val reviewDao: ReviewDao,
    private val quizDao: QuizDao,
    private val activityDao: ActivityDao,
    private val imageCacheDao: ImageCacheDao,
    private val gameDao: GameDao,
    private val videoDao: VideoDao,
    private val channelDao: ChannelDao,
    private val blockedVideoDao: BlockedVideoDao,
    private val videoDurationDao: VideoDurationDao,
    private val watchedDao: WatchedDao,
    private val seeder: ContentSeeder,
    private val wikiImageService: WikiImageService,
    private val packService: PackService,
    private val feedService: YoutubeFeedService,
    private val packStorage: PackStorage,
    private val today: () -> LocalDate = LocalDate::now,
    /**
     * The content language to teach in and fetch packs for. Read fresh wherever it matters
     * rather than captured once, mirroring [today] — but unlike the clock, nothing upstream of
     * [facts] already re-runs on a schedule, so a change here only takes effect on the next app
     * launch (this object is built once per process by `AppContainer`, and Settings' own
     * `Activity.recreate()` only rebuilds the UI, not this repository). Acceptable for now:
     * there is no shipped non-English fact content yet for a live switch to reveal.
     */
    private val currentLanguage: () -> Language = { Language.default },
) {

    /** Persists a downloaded pack's JSON so it survives restarts and re-seeds. */
    fun interface PackStorage {
        fun save(fileName: String, content: String)
    }

    /** `{"durations": {"<youtubeId>": <seconds>}}` as published by the content pipeline. */
    @Serializable
    private data class DurationPack(val durations: Map<String, Int> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true }

    private val imageLock = Mutex()

    suspend fun initialise() = seeder.seedIfNeeded()

    val facts: Flow<List<Fact>> =
        factDao.observeByLanguage(currentLanguage().tag).map { rows -> rows.mapNotNull { it.toDomain() } }

    val reviewStates: Flow<Map<String, ReviewState>> =
        reviewDao.observeAll().map { rows -> rows.associate { it.factId to it.toDomain() } }

    fun factsIn(category: Category): Flow<List<Fact>> =
        factDao.observeByCategoryAndLanguage(category.id, currentLanguage().tag)
            .map { rows -> rows.mapNotNull { it.toDomain() } }

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

    // --- Content packs ----------------------------------------------------------------

    /** Packs currently installed in the database. */
    val installedPacks: Flow<List<PackEntity>> = packDao.observeAll()

    private val manifestState = MutableStateFlow<PackManifest?>(null)

    /** The remote catalog, refreshed by [refreshManifest]; null until first fetched. */
    val manifest: StateFlow<PackManifest?> = manifestState

    /** Fetches the remote catalog. Safe to call repeatedly; failures leave the old value. */
    suspend fun refreshManifest(): Boolean {
        val fetched = packService.fetchManifest(currentLanguage()) ?: return false
        manifestState.value = fetched
        return true
    }

    /**
     * Downloads a pack, persists its JSON for future re-seeds, and installs it into the
     * database. Existing review progress on overlapping fact ids is untouched.
     */
    suspend fun downloadPack(pack: RemotePack): Boolean {
        val raw = packService.fetchPack(pack, currentLanguage()) ?: return false
        val parsed = try {
            ContentParser.parsePack(raw, pack.file)
        } catch (e: Exception) {
            return false
        }
        packStorage.save("${parsed.packId}.json", raw)
        seeder.installIfChanged(parsed, source = ContentSeeder.SOURCE_REMOTE)
        return true
    }

    // --- An unbounded library ------------------------------------------------------------

    /**
     * Refills the pool of unlearned facts before it runs out.
     *
     * The app bundles a fixed 517 facts; learn them all and the Today screen quietly stops
     * offering new material. CI publishes thousands more as small shards, and this pulls the
     * next one down as a category drains — so the collection behaves as if it had no end,
     * while the APK never grows because none of it ships inside the app.
     *
     * Nothing is deleted to make room. A learned fact leaves the *unseen* pool the moment it
     * is learned, which is the visible "it disappears and a new one takes its place"; the fact
     * itself has to stay, because tomorrow's review depends on it.
     *
     * @return how many shards were installed. Zero is the ordinary case and not a failure:
     *   the pool is usually full, and a missing index simply means CI has not published yet.
     */
    suspend fun topUpLibrary(): Int {
        val index = packService.fetchLibraryIndex(currentLanguage()) ?: return 0
        if (index.shards.isEmpty()) return 0

        val states = reviewStates.first()
        val unseenByCategory = facts.first()
            .filter { fact -> states[fact.id]?.phase.let { it == null || it == Phase.NEW } }
            .groupingBy { it.category.id }
            .eachCount()

        val wanted = LibraryTopUp.next(
            available = index.shards.map {
                LibraryShard(packId = it.id, categoryId = it.category, order = it.shard)
            },
            installed = installedPacks.first().map { it.id }.toSet(),
            unseenByCategory = unseenByCategory,
        )

        val byId = index.shards.associateBy { it.id }
        return wanted.count { shard ->
            byId[shard.packId]?.let { downloadPack(it) } ?: false
        }
    }

    // --- Play -----------------------------------------------------------------------------

    @Serializable
    private data class ClimbPack(val relics: List<RelicDto> = emptyList())

    @Serializable
    private data class RelicDto(
        val id: String = "",
        val name: String = "",
        val description: String = "",
        val effect: String = "",
        val magnitude: Int = 0,
        val categoryId: String? = null,
    )

    @Serializable
    private data class ChainsPack(val puzzles: List<ChainsPuzzleDto> = emptyList())

    @Serializable
    private data class ChainsPuzzleDto(
        val date: String = "",
        val tiles: List<String> = emptyList(),
        val groups: List<ChainsGroupDto> = emptyList(),
    )

    @Serializable
    private data class ChainsGroupDto(
        val id: String = "",
        val label: String = "",
        val members: List<String> = emptyList(),
        val difficulty: Int = 1,
    )

    fun bestScore(game: GameId): Flow<Int> =
        gameDao.observeBestScore(game.id).map { it ?: 0 }

    fun runCount(game: GameId): Flow<Int> = gameDao.observeRunCount(game.id)

    suspend fun saveRun(game: GameId, score: Int, seed: Long, durationMs: Long, detail: String) {
        gameDao.insertRun(
            GameRunEntity(
                gameId = game.id,
                score = score,
                seed = seed,
                playedOn = today(),
                durationMs = durationMs,
                detail = detail,
            ),
        )
    }

    /**
     * A question missed in a game comes back to be learned properly.
     *
     * This is what makes the Play tab part of the app rather than an arcade attached to it: the
     * games draw on the same corpus the study flows do, and getting something wrong while
     * playing is exactly the signal the scheduler exists to act on.
     *
     * Two deliberate restraints.
     *
     * A fact you have **never met** is left alone. Grading it here would drop it into the review
     * queue without it ever having been taught, which inverts the Learn-then-Review order the
     * whole app is built around — it will come up in Learn on its own.
     *
     * The daily activity counters are **not** bumped, unlike [grade]. Missing a question is not
     * a completed review, and counting it as one would inflate the Progress figures and let a
     * streak be kept alive by playing badly.
     */
    suspend fun recordGameMiss(factId: String) {
        val existing = reviewDao.byId(factId)?.toDomain() ?: return
        if (existing.phase == Phase.NEW) return
        reviewDao.upsert(Sm2Scheduler.schedule(existing, Rating.AGAIN, today()).toEntity())
    }

    /**
     * The relic roster, newest first: the published list, then the last one seen, then the
     * built-in four.
     *
     * Three sources because a game with no relics is not a game. The network is tried first so a
     * roster added this month is playable this month; the cached copy keeps the tab working on a
     * train; the fallback keeps a first run on a dead connection from being an empty cache.
     */
    suspend fun climbRelics(): List<Relic> {
        val fetched = packService.fetchGamePack(CLIMB_PACK)
        if (fetched != null) {
            val parsed = parseRelics(fetched)
            if (parsed.isNotEmpty()) {
                gameDao.setProgress(GameProgressEntity(GameId.CLIMB.id, KEY_ROSTER, fetched))
                return parsed
            }
        }
        val cached = gameDao.progress(GameId.CLIMB.id, KEY_ROSTER)?.let { parseRelics(it) }
        return cached?.takeIf { it.isNotEmpty() } ?: Relics.FALLBACK
    }

    private fun parseRelics(raw: String): List<Relic> = try {
        Relics.resolve(
            json.decodeFromString<ClimbPack>(raw).relics.map {
                Relics.RelicEntry(it.id, it.name, it.description, it.effect, it.magnitude, it.categoryId)
            },
        )
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * The grid for a given day, or null if none is published.
     *
     * A month is fetched once and kept, because a published grid never changes and because the
     * whole point of a daily puzzle is that it is waiting for you whether or not you have signal.
     */
    suspend fun chainsPuzzle(date: LocalDate = today()): ChainsPuzzle? {
        val month = date.toString().take(7)
        val key = "$KEY_CHAINS_MONTH$month"
        val raw = gameDao.progress(GameId.CHAINS.id, key)
            ?: packService.fetchGamePack("$CHAINS_DIR/$month.json")?.also {
                gameDao.setProgress(GameProgressEntity(GameId.CHAINS.id, key, it))
            }
            ?: return null

        val puzzle = try {
            json.decodeFromString<ChainsPack>(raw).puzzles
                .firstOrNull { it.date == date.toString() }
                ?.let { dto ->
                    ChainsPuzzle(
                        date = dto.date,
                        groups = dto.groups.map {
                            ChainsGroup(it.id, it.label, it.members, it.difficulty)
                        },
                        tiles = dto.tiles,
                    )
                }
        } catch (e: Exception) {
            null
        } ?: return null

        // A grid where a tile fits two groups has no single solution, and the player would be
        // told they are wrong by a puzzle that is itself wrong. The generator proves this before
        // publishing; this is the second lock, because a bad grid is worse than no grid.
        return puzzle.takeIf { it.problems().isEmpty() }
    }

    suspend fun dailyResult(game: GameId, date: LocalDate = today()): GameDailyEntity? =
        gameDao.daily(game.id, date)

    fun recentDailies(game: GameId, limit: Int = 30): Flow<List<GameDailyEntity>> =
        gameDao.observeDaily(game.id, limit)

    suspend fun saveDailyResult(
        game: GameId,
        date: LocalDate,
        score: Int,
        mistakes: Int,
        won: Boolean,
        solvedGroupIds: List<String>,
    ) {
        gameDao.setDaily(
            GameDailyEntity(
                gameId = game.id,
                date = date,
                score = score,
                mistakes = mistakes,
                won = won,
                solved = solvedGroupIds.joinToString(","),
            ),
        )
    }

    /** Insight banked across runs, spent on unlocking relics. */
    suspend fun insight(): Int =
        gameDao.progress(GameId.CLIMB.id, KEY_INSIGHT)?.toIntOrNull() ?: 0

    suspend fun addInsight(amount: Int) {
        val total = (insight() + amount).coerceAtLeast(0)
        gameDao.setProgress(GameProgressEntity(GameId.CLIMB.id, KEY_INSIGHT, total.toString()))
    }

    @Serializable
    private data class GambitMatchDto(
        val fen: String = Fen.START,
        val playerSide: Int = Chess.WHITE,
        val baseDepth: Int = Gambit.DEFAULT_DEPTH,
        val tempo: Int = 0,
        val weakenBanked: Int = 0,
        val moveHistory: List<String> = emptyList(),
        val outcome: String = GambitOutcome.IN_PROGRESS.name,
    )

    /**
     * The game in progress, or null if there is none. A chess game — unlike a Climb run or a
     * daily grid — is exactly the kind of thing worth coming back to a day later, which is why
     * [Fen] carries a whole game as one short string: cheap enough to keep in [GameProgressEntity]
     * without a table of its own.
     */
    suspend fun gambitMatch(): GambitMatch? {
        val raw = gameDao.progress(GameId.GAMBIT.id, KEY_GAMBIT_MATCH) ?: return null
        return try {
            val dto = json.decodeFromString<GambitMatchDto>(raw)
            GambitMatch(
                fen = dto.fen,
                playerSide = dto.playerSide,
                baseDepth = dto.baseDepth,
                tempo = dto.tempo,
                weakenBanked = dto.weakenBanked,
                moveHistory = dto.moveHistory,
                outcome = GambitOutcome.entries.firstOrNull { it.name == dto.outcome }
                    ?: GambitOutcome.IN_PROGRESS,
            )
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveGambitMatch(match: GambitMatch) {
        val dto = GambitMatchDto(
            fen = match.fen,
            playerSide = match.playerSide,
            baseDepth = match.baseDepth,
            tempo = match.tempo,
            weakenBanked = match.weakenBanked,
            moveHistory = match.moveHistory,
            outcome = match.outcome.name,
        )
        gameDao.setProgress(GameProgressEntity(GameId.GAMBIT.id, KEY_GAMBIT_MATCH, json.encodeToString(dto)))
    }

    /** Called once a finished game has been shown and dismissed, so the next open starts fresh. */
    suspend fun clearGambitMatch() {
        gameDao.clearProgress(GameId.GAMBIT.id, KEY_GAMBIT_MATCH)
    }

    // --- Videos: a live shelf, not a catalogue ------------------------------------------

    /**
     * The last shelf that was built, so the tab paints instantly instead of flashing empty
     * while the feeds load. It is replaced wholesale by every refresh.
     */
    val videos: Flow<List<Video>> =
        videoDao.observeAll().map { rows -> rows.mapNotNull { it.toDomain() } }

    val watchedVideoIds: Flow<Set<String>> = watchedDao.observeAll().map { it.toSet() }

    suspend fun video(id: String): Video? = videoDao.byId(id)?.toDomain()

    suspend fun markWatched(videoId: String) {
        watchedDao.upsert(WatchedVideoEntity(videoId = videoId, watchedOn = today()))
    }

    /**
     * Records a video that would not play, so it never reaches the shelf again.
     *
     * Almost always error 152: the owner disallows embedding. Nothing in a channel feed
     * reveals that in advance, so the app learns it the only way it can — by trying — and
     * then remembers.
     */
    suspend fun blockVideo(youtubeId: String, reason: String) {
        blockedVideoDao.upsert(BlockedVideoEntity(youtubeId = youtubeId, reason = reason))
        videoDao.deleteByYoutubeId(youtubeId)
    }

    /** The player reports a duration once a video loads; feeds never carry one. */
    suspend fun recordDuration(youtubeId: String, seconds: Int) {
        if (seconds <= 0) return
        videoDurationDao.upsert(VideoDurationEntity(youtubeId = youtubeId, seconds = seconds))
        videoDao.updateMinutes(youtubeId, (seconds / 60).coerceAtLeast(1))
    }

    /**
     * Rebuilds the shelf from the allowlisted channels' current uploads.
     *
     * This is the whole point of the Watch tab: the videos are whatever those channels have
     * published as of now, fetched over the network, with nothing baked into the app. Returns
     * false only when every feed failed, which the UI reports as an offline state rather than
     * an empty shelf.
     */
    suspend fun refreshShelf(): Boolean {
        val installed = channelDao.all()
        if (installed.isEmpty()) return false

        // Strictly the selected language. This used to fall back to the whole allowlist when
        // the filter matched nothing, which sounds forgiving and is not: it turns "your
        // language has no channels" into a shelf of videos you cannot follow, and hides the
        // real fault instead of showing it. An empty shelf is the honest answer.
        val wanted = currentLanguage().tag
        val channels = installed.filter { it.language == wanted }
        if (channels.isEmpty()) {
            videoDao.replaceShelf(emptyList())
            return true
        }

        val feeds = feedService.fetchAll(channels.map { it.id })
        if (feeds.isEmpty()) return false

        val blocked = blockedVideoDao.all().toSet()
        val durations = videoDurationDao.all().associate { it.youtubeId to it.seconds }
        val factsByCategory = facts.first().groupBy { it.category }

        val curatedByChannel = channels.mapNotNull { channel ->
            val entries = feeds[channel.id] ?: return@mapNotNull null
            val category = Category.fromId(channel.categoryId) ?: return@mapNotNull null
            VideoCuration.curate(entries, blocked = blocked, durations = durations).map { entry ->
                Video(
                    id = "vid-${entry.youtubeId}",
                    youtubeId = entry.youtubeId,
                    category = category,
                    title = entry.title,
                    channel = channel.displayName,
                    minutes = durations[entry.youtubeId]?.let { (it / 60).coerceAtLeast(1) },
                    thumbnailUrl = entry.thumbnailUrl,
                    relatedFactIds = linkFacts(entry.title, factsByCategory[category].orEmpty()),
                )
            }
        }

        val shelf = VideoCuration.interleave(curatedByChannel.map { list -> list.map { it.toEntry() } })
        val byId = curatedByChannel.flatten().associateBy { it.youtubeId }
        val ordered = shelf.mapNotNull { byId[it.youtubeId] }
        if (ordered.isEmpty()) return false

        videoDao.replaceShelf(ordered.mapIndexed { position, video -> video.toEntity(position) })
        return true
    }

    private fun Video.toEntry() = FeedEntry(
        youtubeId = youtubeId,
        title = title,
        thumbnailUrl = thumbnailUrl,
        views = 0,
    )

    /**
     * Links a video to facts by significant word overlap within its own subject. Conservative
     * on purpose: two matching words before a claim is made, so "Quiz me on this" reflects
     * what was actually watched.
     */
    private fun linkFacts(title: String, candidates: List<Fact>): List<String> {
        val words = title.lowercase().split(Regex("[^a-z]+")).filter { it.length >= 4 }.toSet()
        if (words.isEmpty()) return emptyList()
        return candidates
            .map { fact ->
                val target = "${fact.title} ${fact.answer} ${fact.wikiTitle.orEmpty()}"
                    .lowercase().split(Regex("[^a-z]+")).filter { it.length >= 4 }.toSet()
                (words intersect target).size to fact.id
            }
            .filter { it.first >= 2 }
            .sortedByDescending { it.first }
            .take(4)
            .map { it.second }
    }

    /**
     * Pulls the published duration map into the local table.
     *
     * The length filter used to work only on videos you had already opened, because the player
     * was the sole source of a runtime. Neither the channel feed nor the embed page carries one
     * — both were checked from CI — so the pipeline publishes them and this fills the gaps.
     * Player-measured values are never overwritten: `insertMissing` ignores conflicts.
     */
    suspend fun refreshDurations(): Boolean {
        val raw = packService.fetchDurations() ?: return false
        val parsed = try {
            json.decodeFromString<DurationPack>(raw).durations
        } catch (e: Exception) {
            return false
        }
        if (parsed.isEmpty()) return false
        videoDurationDao.insertMissing(
            parsed.mapNotNull { (id, seconds) ->
                if (seconds > 0) VideoDurationEntity(youtubeId = id, seconds = seconds) else null
            },
        )
        return true
    }

    /** The channel allowlist, refreshed from the repo. */
    suspend fun refreshChannels(): Boolean {
        val raw = packService.fetchChannels() ?: return false
        val parsed = try {
            ChannelParser.parse(raw, "channels.json")
        } catch (e: Exception) {
            return false
        }
        if (parsed.channels.isEmpty()) return false

        packStorage.save("channels.json", raw)
        channelDao.clear()
        channelDao.insertAll(parsed.channels.map { it.toEntity() })
        return true
    }

    /** The facts a video teaches, for its "Quiz me on this" button. */
    suspend fun factsByIds(ids: List<String>): List<Fact> =
        ids.mapNotNull { factDao.byId(it)?.toDomain() }

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

        const val CLIMB_PACK = "climb.json"
        const val CHAINS_DIR = "chains"
        const val KEY_ROSTER = "roster"
        const val KEY_INSIGHT = "insight"
        const val KEY_CHAINS_MONTH = "month:"
        const val KEY_GAMBIT_MATCH = "match"
    }
}
