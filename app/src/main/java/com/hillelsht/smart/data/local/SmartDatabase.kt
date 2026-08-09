package com.hillelsht.smart.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        FactEntity::class,
        PackEntity::class,
        VideoEntity::class,
        ChannelEntity::class,
        BlockedVideoEntity::class,
        VideoDurationEntity::class,
        WatchedVideoEntity::class,
        ReviewStateEntity::class,
        QuizResultEntity::class,
        DailyActivityEntity::class,
        ImageCacheEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class SmartDatabase : RoomDatabase() {

    abstract fun factDao(): FactDao
    abstract fun packDao(): PackDao
    abstract fun videoDao(): VideoDao
    abstract fun channelDao(): ChannelDao
    abstract fun blockedVideoDao(): BlockedVideoDao
    abstract fun videoDurationDao(): VideoDurationDao
    abstract fun watchedDao(): WatchedDao
    abstract fun reviewDao(): ReviewDao
    abstract fun quizDao(): QuizDao
    abstract fun activityDao(): ActivityDao
    abstract fun imageCacheDao(): ImageCacheDao

    companion object {

        /**
         * v1 → v2: enrichment columns on facts plus the packs table. Purely additive so
         * review history — the one thing that cannot be rebuilt — survives the update. The
         * facts themselves are refreshed by the seeder on next launch (the packs table starts
         * empty, so every bundled pack reads as "not installed" and gets re-seeded enriched).
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE facts ADD COLUMN details TEXT")
                db.execSQL("ALTER TABLE facts ADD COLUMN imageUrl TEXT")
                db.execSQL("ALTER TABLE facts ADD COLUMN pageUrl TEXT")
                db.execSQL("ALTER TABLE facts ADD COLUMN packId TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_facts_packId ON facts(packId)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS packs (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "version TEXT NOT NULL, " +
                        "factCount INTEGER NOT NULL, " +
                        "source TEXT NOT NULL)",
                )
            }
        }

        /** v2 → v3: the Watch tab's tables. Purely additive; nothing existing is touched. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS videos (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "youtubeId TEXT NOT NULL, " +
                        "categoryId TEXT NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "channel TEXT NOT NULL, " +
                        "minutes INTEGER, " +
                        "thumbnailUrl TEXT, " +
                        "relatedFactIds TEXT NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_videos_categoryId ON videos(categoryId)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS watched_videos (" +
                        "videoId TEXT NOT NULL PRIMARY KEY, " +
                        "watchedOn TEXT NOT NULL)",
                )
            }
        }

        /**
         * v3 → v4: the Watch tab stops shipping a video catalogue and starts discovering
         * videos live, so it needs the channel allowlist plus the two things only the device
         * can learn — which videos refuse to embed, and how long each one runs.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS watch_channels (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "handle TEXT NOT NULL, " +
                        "categoryId TEXT NOT NULL, " +
                        "displayName TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_watch_channels_categoryId " +
                        "ON watch_channels(categoryId)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS blocked_videos (" +
                        "youtubeId TEXT NOT NULL PRIMARY KEY, " +
                        "reason TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS video_durations (" +
                        "youtubeId TEXT NOT NULL PRIMARY KEY, " +
                        "seconds INTEGER NOT NULL)",
                )
                // The shelf is now an ordered, interleaved list rather than a catalogue.
                db.execSQL("ALTER TABLE videos ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun build(context: Context): SmartDatabase =
            // Schemas are exported to app/schemas so that future releases can keep shipping
            // real migrations — review history must survive every update.
            Room.databaseBuilder(context, SmartDatabase::class.java, "smart.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
