package com.hillelsht.smart.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface FactDao {
    @Query("SELECT * FROM facts")
    fun observeAll(): Flow<List<FactEntity>>

    @Query("SELECT * FROM facts WHERE categoryId = :categoryId")
    fun observeByCategory(categoryId: String): Flow<List<FactEntity>>

    @Query("SELECT * FROM facts WHERE id = :id")
    suspend fun byId(id: String): FactEntity?

    @Query("SELECT COUNT(*) FROM facts")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(facts: List<FactEntity>)

    @Query("DELETE FROM facts WHERE packId = :packId")
    suspend fun clearPack(packId: String)

    @Query("DELETE FROM facts")
    suspend fun clear()
}

@Dao
interface VideoDao {
    @Query("SELECT * FROM videos ORDER BY position")
    fun observeAll(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE id = :id")
    suspend fun byId(id: String): VideoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(videos: List<VideoEntity>)

    @Query("DELETE FROM videos WHERE youtubeId = :youtubeId")
    suspend fun deleteByYoutubeId(youtubeId: String)

    @Query("UPDATE videos SET minutes = :minutes WHERE youtubeId = :youtubeId")
    suspend fun updateMinutes(youtubeId: String, minutes: Int)

    @Query("DELETE FROM videos")
    suspend fun clear()

    /**
     * Swaps the whole shelf in one transaction.
     *
     * A bare clear-then-insert would emit an empty list to the tab in between, so every
     * refresh would blink the shelf away and back — the exact thing keeping the previous
     * shelf on screen was meant to avoid.
     */
    @Transaction
    suspend fun replaceShelf(videos: List<VideoEntity>) {
        clear()
        insertAll(videos)
    }
}

@Dao
interface ChannelDao {
    @Query("SELECT * FROM watch_channels")
    fun observeAll(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM watch_channels")
    suspend fun all(): List<ChannelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM watch_channels")
    suspend fun clear()
}

@Dao
interface BlockedVideoDao {
    @Query("SELECT youtubeId FROM blocked_videos")
    fun observeAll(): Flow<List<String>>

    @Query("SELECT youtubeId FROM blocked_videos")
    suspend fun all(): List<String>

    @Upsert
    suspend fun upsert(blocked: BlockedVideoEntity)
}

@Dao
interface VideoDurationDao {
    @Query("SELECT * FROM video_durations")
    suspend fun all(): List<VideoDurationEntity>

    @Upsert
    suspend fun upsert(duration: VideoDurationEntity)
}

@Dao
interface WatchedDao {
    @Query("SELECT videoId FROM watched_videos")
    fun observeAll(): Flow<List<String>>

    @Upsert
    suspend fun upsert(watched: WatchedVideoEntity)
}

@Dao
interface PackDao {
    @Query("SELECT * FROM packs")
    fun observeAll(): Flow<List<PackEntity>>

    @Query("SELECT * FROM packs WHERE id = :id")
    suspend fun byId(id: String): PackEntity?

    @Upsert
    suspend fun upsert(pack: PackEntity)
}

@Dao
interface ReviewDao {
    @Query("SELECT * FROM review_states")
    fun observeAll(): Flow<List<ReviewStateEntity>>

    @Query("SELECT * FROM review_states WHERE factId = :factId")
    suspend fun byId(factId: String): ReviewStateEntity?

    @Query("SELECT COUNT(*) FROM review_states WHERE dueDate <= :today AND phase != 'NEW'")
    fun observeDueCount(today: LocalDate): Flow<Int>

    @Upsert
    suspend fun upsert(state: ReviewStateEntity)
}

@Dao
interface QuizDao {
    @Insert
    suspend fun insert(result: QuizResultEntity)

    @Query("SELECT * FROM quiz_results ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<QuizResultEntity>>
}

@Dao
interface ActivityDao {
    @Query("SELECT * FROM daily_activity ORDER BY date DESC")
    fun observeAll(): Flow<List<DailyActivityEntity>>

    @Query("SELECT * FROM daily_activity WHERE date = :date")
    suspend fun byDate(date: LocalDate): DailyActivityEntity?

    @Upsert
    suspend fun upsert(activity: DailyActivityEntity)
}

@Dao
interface ImageCacheDao {
    @Query("SELECT * FROM image_cache WHERE wikiTitle = :wikiTitle")
    suspend fun byTitle(wikiTitle: String): ImageCacheEntity?

    @Upsert
    suspend fun upsert(entry: ImageCacheEntity)
}
