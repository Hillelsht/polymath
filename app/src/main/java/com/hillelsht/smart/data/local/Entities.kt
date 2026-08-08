package com.hillelsht.smart.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.domain.model.Fact
import com.hillelsht.smart.domain.model.Phase
import com.hillelsht.smart.domain.model.ReviewState
import java.time.LocalDate

@Entity(tableName = "facts", indices = [Index("categoryId")])
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
