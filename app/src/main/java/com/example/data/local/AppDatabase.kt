package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PairedTvEntity::class,
        FavoriteAppEntity::class,
        RecentTvAppEntity::class,
        CustomButtonEntity::class,
        AppPreferencesEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pairedTvDao(): PairedTvDao
    abstract fun favoriteAppDao(): FavoriteAppDao
    abstract fun recentTvAppDao(): RecentTvAppDao
    abstract fun customButtonDao(): CustomButtonDao
    abstract fun appPreferencesDao(): AppPreferencesDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE app_preferences ADD COLUMN hasPromptedAdbKeyboardSetup INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `app_preferences_new` (" +
                        "`id` INTEGER NOT NULL, " +
                        "`hapticEnabled` INTEGER NOT NULL, " +
                        "`autoShowKeyboard` INTEGER NOT NULL, " +
                        "`brightnessPercent` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT INTO `app_preferences_new` (`id`, `hapticEnabled`, `autoShowKeyboard`, `brightnessPercent`) " +
                        "SELECT `id`, `hapticEnabled`, `autoShowKeyboard`, `brightnessPercent` FROM `app_preferences`"
                )
                db.execSQL("DROP TABLE `app_preferences`")
                db.execSQL("ALTER TABLE `app_preferences_new` RENAME TO `app_preferences`")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tv_remote_db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
