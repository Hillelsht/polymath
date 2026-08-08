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
        ReviewStateEntity::class,
        QuizResultEntity::class,
        DailyActivityEntity::class,
        ImageCacheEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class SmartDatabase : RoomDatabase() {

    abstract fun factDao(): FactDao
    abstract fun packDao(): PackDao
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

        fun build(context: Context): SmartDatabase =
            // Schemas are exported to app/schemas so that future releases can keep shipping
            // real migrations — review history must survive every update.
            Room.databaseBuilder(context, SmartDatabase::class.java, "smart.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
