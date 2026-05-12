package com.example.gpstracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Session::class, LocationPoint::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun locationPointDao(): LocationPointDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE sessions ADD COLUMN movementThresholdM REAL NOT NULL DEFAULT 20.0")
                database.execSQL("ALTER TABLE sessions ADD COLUMN dwellTimeS INTEGER NOT NULL DEFAULT 15")
                database.execSQL("ALTER TABLE sessions ADD COLUMN trackingIntervalS INTEGER NOT NULL DEFAULT 5")
                database.execSQL("ALTER TABLE sessions ADD COLUMN dwellingIntervalS INTEGER NOT NULL DEFAULT 30")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "gps_tracker_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
