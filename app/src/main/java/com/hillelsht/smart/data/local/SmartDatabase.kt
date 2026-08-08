package com.hillelsht.smart.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        FactEntity::class,
        ReviewStateEntity::class,
        QuizResultEntity::class,
        DailyActivityEntity::class,
        ImageCacheEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class SmartDatabase : RoomDatabase() {

    abstract fun factDao(): FactDao
    abstract fun reviewDao(): ReviewDao
    abstract fun quizDao(): QuizDao
    abstract fun activityDao(): ActivityDao
    abstract fun imageCacheDao(): ImageCacheDao

    companion object {
        fun build(context: Context): SmartDatabase =
            // Schemas are exported to app/schemas so that future curriculum releases can ship
            // real migrations — review history is the one thing in here that cannot be rebuilt.
            Room.databaseBuilder(context, SmartDatabase::class.java, "smart.db").build()
    }
}
