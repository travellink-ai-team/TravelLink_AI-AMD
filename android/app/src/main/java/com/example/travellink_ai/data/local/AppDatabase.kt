package com.example.travellink_ai.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [LocalItinerary::class, LocalTripMemory::class], version = 11)
@TypeConverters(Converters::class) // 告訴 Room 怎麼轉換 List
abstract class AppDatabase : RoomDatabase() {
    abstract fun itineraryDao(): ItineraryDao
    abstract fun tripMemoryDao(): TripMemoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // v3 → v4：新增 firestoreDocId 欄位
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE itineraries ADD COLUMN firestoreDocId TEXT DEFAULT NULL"
                )
            }
        }

        // v4 → v5：新增 feedbackSubmitted 欄位
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE itineraries ADD COLUMN feedbackSubmitted INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // v5 → v6：新增 overallRating 欄位（回饋整體評分 1-5，0 表示未評）
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE itineraries ADD COLUMN overallRating INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // v6 → v7：新增 joinPin、joinPinExpiresAt 欄位（協作邀請 PIN 碼）
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE itineraries ADD COLUMN joinPin TEXT DEFAULT NULL"
                )
                database.execSQL(
                    "ALTER TABLE itineraries ADD COLUMN joinPinExpiresAt INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // v7 → v8：新增 roadSegmentsEncoded、transitTimesJson 欄位
        // （景點座標 lat/lng 已內嵌於 stops 的 JSON 中，由 Converters/Gson 處理，不需 SQL migration）
        // 用途：歷史行程重新開啟時可直接重用生成當下算好的路線資料，
        //       跳過 Geocoding／Places／Directions 的重複呼叫。
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE itineraries ADD COLUMN roadSegmentsEncoded TEXT DEFAULT NULL"
                )
                database.execSQL(
                    "ALTER TABLE itineraries ADD COLUMN transitTimesJson TEXT DEFAULT NULL"
                )
            }
        }

        // v8 → v9：新增 userId 欄位，綁定 Firebase Auth UID
        // 舊資料 userId 預設為空字串，視為本機行程（不會被其他帳號的過濾條件撈到）
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE itineraries ADD COLUMN userId TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        // v9 → v10：新增 transportMode 欄位（計程車/步行/自行開車）
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE itineraries ADD COLUMN transportMode TEXT NOT NULL DEFAULT 'taxi'"
                )
            }
        }

        // v10 → v11：W4 C6 旅遊回憶離線快取（trip_memories 表）
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS trip_memories (
                        tripId TEXT NOT NULL PRIMARY KEY,
                        userId TEXT NOT NULL DEFAULT '',
                        tripTitle TEXT NOT NULL DEFAULT '',
                        region TEXT NOT NULL DEFAULT '',
                        coverUrl TEXT NOT NULL DEFAULT '',
                        updatedAt INTEGER NOT NULL DEFAULT 0,
                        spotsJson TEXT NOT NULL DEFAULT '{}'
                    )
                    """.trimIndent()
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "travellink_db"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}