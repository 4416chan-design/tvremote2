package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PairedTvDao {
    @Query("SELECT * FROM paired_tvs ORDER BY lastConnectedAt DESC")
    fun getAllPairedTvs(): Flow<List<PairedTvEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tv: PairedTvEntity)

    @Query("DELETE FROM paired_tvs WHERE host = :host")
    suspend fun deleteByHost(host: String)

    @Query("SELECT * FROM paired_tvs ORDER BY lastConnectedAt DESC LIMIT 1")
    suspend fun getMostRecent(): PairedTvEntity?

    @Query("SELECT * FROM paired_tvs WHERE host = :host LIMIT 1")
    suspend fun getByHost(host: String): PairedTvEntity?
}

@Dao
interface FavoriteAppDao {
    @Query("SELECT * FROM favorite_apps ORDER BY sortOrder ASC")
    fun getAllFavorites(): Flow<List<FavoriteAppEntity>>

    @Query("SELECT * FROM favorite_apps ORDER BY sortOrder ASC")
    suspend fun getAllFavoritesOnce(): List<FavoriteAppEntity>

    @Query("SELECT COUNT(*) FROM favorite_apps")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: FavoriteAppEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(apps: List<FavoriteAppEntity>)

    @Query("DELETE FROM favorite_apps WHERE packageName = :packageName")
    suspend fun deleteByPackage(packageName: String)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM favorite_apps")
    suspend fun maxSortOrder(): Int
}

@Dao
interface RecentTvAppDao {
    @Query("SELECT * FROM recent_tv_apps ORDER BY lastUsedAt DESC")
    fun getRecentApps(): Flow<List<RecentTvAppEntity>>

    @Query("SELECT * FROM recent_tv_apps")
    suspend fun getRecentAppsOnce(): List<RecentTvAppEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: RecentTvAppEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(apps: List<RecentTvAppEntity>)

    @Query("DELETE FROM recent_tv_apps WHERE packageName = :packageName")
    suspend fun deleteByPackage(packageName: String)
}

@Dao
interface CustomButtonDao {
    @Query("SELECT * FROM custom_buttons ORDER BY slotIndex ASC")
    fun getCustomButtons(): Flow<List<CustomButtonEntity>>

    @Query("SELECT COUNT(*) FROM custom_buttons")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(button: CustomButtonEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(buttons: List<CustomButtonEntity>)
}

@Dao
interface AppPreferencesDao {
    @Query("SELECT * FROM app_preferences WHERE id = 1")
    fun observePreferences(): Flow<AppPreferencesEntity?>

    @Query("SELECT * FROM app_preferences WHERE id = 1")
    suspend fun getPreferencesOnce(): AppPreferencesEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(prefs: AppPreferencesEntity)
}
