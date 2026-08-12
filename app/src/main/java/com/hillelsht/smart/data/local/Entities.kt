package com.hillelsht.smart.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.hillelsht.smart.data.seed.WatchChannel
import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.domain.model.Fact
import com.hillelsht.smart.domain.model.Language
import com.hillelsht.smart.domain.model.Phase
import com.hillelsht.smart.domain.model.ReviewState
import com.hillelsht.smart.domain.model.Video
import java.time.LocalDate

@Entity(tableName = "facts", indices = [Index("categoryId"), Index("packId"), Index("language")])
data class FactEntity(
    @PrimaryKey val id: String,
    val categoryId: String,
    val title: String,
    val statement: String,
    val question: String,
    val answer: String,
    val answerType: String,
    val hook: String?,
    val wikiTitle: String?,
    val difficulty: Int,
    val details: String?,
    val imageUrl: String?,
    val pageUrl: String?,
    val packId: String,
    /** [Language.tag], e.g. "en" or "ru". See the migration for why every existing row defaults here. */
    @ColumnInfo(defaultValue = "en")
    val language: String = Language.default.tag,
)

/**
 * An installed content pack. The seeder compares [version] against the bundled or downloaded
 * file and replaces the pack's facts only when it changed; review progress is keyed by fact id
 * and never touched.
 */
@Entity(tableName = "packs")
data class PackEntity(
    @PrimaryKey val id: String,
    val version: String,
    val factCount: Int,
    /** "bundled" or "remote". */
    val source: String,
)

@Entity(tableName = "videos", indices = [Index("categoryId")])
data class VideoEntity(
    @PrimaryKey val id: String,
    val youtubeId: String,
    val categoryId: String,
    val title: String,
    val channel: String,
    val minutes: Int?,
    val thumbnailUrl: String?,
    /** Comma-joined fact ids; simple enough that a join table would be ceremony. */
    val relatedFactIds: String,
    /**
     * Where this video sits on the shelf. The refresh interleaves channels so the tab opens
     * with a spread rather than eight videos from whoever was fetched first, and that ordering
     * is worth nothing if the read-back leaves it to SQLite's row order.
     *
     * The default is declared here as well as in the migration: SQLite demands one when adding
     * a NOT NULL column to an existing table, and Room compares the two schemas on open.
     */
    @ColumnInfo(defaultValue = "0")
    val position: Int = 0,
)

@Entity(tableName = "watch_channels", indices = [Index("categoryId")])
data class ChannelEntity(
    @PrimaryKey val id: String,
    val handle: String,
    val categoryId: String,
    val displayName: String,
    /**
     * [Language.tag]. Deliberately has **no Kotlin default**, so every construction site is
     * forced to say which language it means. It had one, and the second of the two places
     * that build this entity quietly took it — storing all 53 channels as English, which made
     * the Russian shelf fall back to the English one. The Room default below is a different
     * thing: it backfills existing rows during the v6→v7 migration.
     */
    @ColumnInfo(defaultValue = "en")
    val language: String,
)

/**
 * Videos that failed to play — almost always because the owner disabled embedding, which the
 * IFrame player reports as error 152. Recording them is what stops the same dead card being
 * offered again tomorrow.
 */
@Entity(tableName = "blocked_videos")
data class BlockedVideoEntity(
    @PrimaryKey val youtubeId: String,
    val reason: String,
)

/** Durations the player told us about; a feed never carries them. */
@Entity(tableName = "video_durations")
data class VideoDurationEntity(
    @PrimaryKey val youtubeId: String,
    val seconds: Int,
)

@Entity(tableName = "watched_videos")
data class WatchedVideoEntity(
    @PrimaryKey val videoId: String,
    val watchedOn: LocalDate,
)

@Entity(tableName = "review_states", indices = [Index("dueDate")])
data class ReviewStateEntity(
    @PrimaryKey val factId: String,
    val phase: String,
    val repetitions: Int,
    val intervalDays: Int,
    val easeFactor: Double,
    val dueDate: LocalDate,
    val lapses: Int,
    val lastReviewed: LocalDate?,
    val totalReviews: Int,
    val correctReviews: Int,
)

/** One completed quiz run, kept so Stats can show whether scores are actually improving. */
@Entity(tableName = "quiz_results")
data class QuizResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val takenOn: LocalDate,
    val categoryId: String?,
    val total: Int,
    val correct: Int,
)

/** A day on which something was studied — the raw material for streaks. */
@Entity(tableName = "daily_activity")
data class DailyActivityEntity(
    @PrimaryKey val date: LocalDate,
    val newLearned: Int,
    val reviewsDone: Int,
)

/**
 * Resolved Wikipedia image URLs.
 *
 * Wikipedia's summary endpoint is hit once per fact and the result kept forever; Coil then
 * caches the image bytes. After the first pass through a fact, its picture is available
 * offline.
 */
@Entity(tableName = "image_cache")
data class ImageCacheEntity(
    @PrimaryKey val wikiTitle: String,
    val imageUrl: String?,
    val pageUrl: String?,
    val fetchedAtEpochDay: Long,
)

/**
 * One finished run of a game, so a personal best means something.
 *
 * [seed] is kept because a run is reproducible from it — the tower [com.hillelsht.smart.domain
 * .play.climb.ClimbMap] builds is a pure function of the seed — which makes a memorable run
 * replayable and a reported bug reproducible.
 */
@Entity(tableName = "game_runs", indices = [Index("gameId")])
data class GameRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameId: String,
    val score: Int,
    val seed: Long,
    val playedOn: LocalDate,
    val durationMs: Long,
    /** A human summary such as "floor 14 · 62 answered". Shown to the player, never parsed. */
    val detail: String,
)

/**
 * Anything a game remembers between runs: unlocked relics, banked insight, a run left in progress.
 *
 * Deliberately a key/value bag rather than a column per game. Every game after The Climb would
 * otherwise need its own migration to store its own progress, and a schema change per game is a
 * schema change too many.
 */
@Entity(tableName = "game_progress", primaryKeys = ["gameId", "entryKey"])
data class GameProgressEntity(
    val gameId: String,
    val entryKey: String,
    val value: String,
)

/**
 * How a daily grid went, so it cannot be replayed for a better score.
 *
 * The point of a daily puzzle is that everyone got the same one and had one go at it. Without
 * this the score is meaningless and so is the streak.
 */
@Entity(tableName = "game_daily", primaryKeys = ["gameId", "date"])
data class GameDailyEntity(
    val gameId: String,
    val date: LocalDate,
    val score: Int,
    val mistakes: Int,
    val won: Boolean,
    /** Comma-separated group ids that were solved, so a finished grid reopens showing your work. */
    val solved: String,
)

class Converters {
    @TypeConverter
    fun dateToString(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    fun stringToDate(value: String?): LocalDate? = value?.let(LocalDate::parse)
}

fun FactEntity.toDomain(): Fact? {
    val category = Category.fromId(categoryId) ?: return null
    return Fact(
        id = id,
        category = category,
        title = title,
        statement = statement,
        question = question,
        answer = answer,
        answerType = answerType,
        hook = hook,
        wikiTitle = wikiTitle,
        difficulty = difficulty,
        details = details,
        imageUrl = imageUrl,
        pageUrl = pageUrl,
        packId = packId,
        language = Language.fromTag(language),
    )
}

fun Fact.toEntity(): FactEntity = FactEntity(
    id = id,
    categoryId = category.id,
    title = title,
    statement = statement,
    question = question,
    answer = answer,
    answerType = answerType,
    hook = hook,
    wikiTitle = wikiTitle,
    difficulty = difficulty,
    details = details,
    imageUrl = imageUrl,
    pageUrl = pageUrl,
    packId = packId,
    language = language.tag,
)

/**
 * The single mapping from a parsed channel to its row.
 *
 * There were two, written weeks apart, and only one of them learned about languages.
 */
fun WatchChannel.toEntity(): ChannelEntity = ChannelEntity(
    id = id,
    handle = handle,
    categoryId = category.id,
    displayName = displayName,
    language = language.tag,
)

fun VideoEntity.toDomain(): Video? {
    val category = Category.fromId(categoryId) ?: return null
    return Video(
        id = id,
        youtubeId = youtubeId,
        category = category,
        title = title,
        channel = channel,
        minutes = minutes,
        thumbnailUrl = thumbnailUrl,
        relatedFactIds = relatedFactIds.split(',').filter { it.isNotBlank() },
    )
}

fun Video.toEntity(position: Int = 0): VideoEntity = VideoEntity(
    id = id,
    youtubeId = youtubeId,
    categoryId = category.id,
    title = title,
    channel = channel,
    minutes = minutes,
    thumbnailUrl = thumbnailUrl,
    relatedFactIds = relatedFactIds.joinToString(","),
    position = position,
)

fun ReviewStateEntity.toDomain(): ReviewState = ReviewState(
    factId = factId,
    phase = runCatching { Phase.valueOf(phase) }.getOrDefault(Phase.NEW),
    repetitions = repetitions,
    intervalDays = intervalDays,
    easeFactor = easeFactor,
    dueDate = dueDate,
    lapses = lapses,
    lastReviewed = lastReviewed,
    totalReviews = totalReviews,
    correctReviews = correctReviews,
)

fun ReviewState.toEntity(): ReviewStateEntity = ReviewStateEntity(
    factId = factId,
    phase = phase.name,
    repetitions = repetitions,
    intervalDays = intervalDays,
    easeFactor = easeFactor,
    dueDate = dueDate,
    lapses = lapses,
    lastReviewed = lastReviewed,
    totalReviews = totalReviews,
    correctReviews = correctReviews,
)
