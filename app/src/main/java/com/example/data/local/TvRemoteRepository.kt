package com.example.data.local

import com.example.data.model.CustomActionType
import com.example.data.model.TvAppCatalog
import com.example.data.model.TvAppCatalogItem
import com.example.data.model.TvKeyCodes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TvRemoteRepository(private val db: AppDatabase) {
    val pairedTvs: Flow<List<PairedTvEntity>> = db.pairedTvDao().getAllPairedTvs()
    val favoriteApps: Flow<List<FavoriteAppEntity>> = db.favoriteAppDao().getAllFavorites()
    val recentApps: Flow<List<RecentTvAppEntity>> = db.recentTvAppDao().getRecentApps()
    val customButtons: Flow<List<CustomButtonEntity>> = db.customButtonDao().getCustomButtons()
    val preferences: Flow<AppPreferencesEntity> = db.appPreferencesDao().observePreferences()
        .map { it ?: AppPreferencesEntity() }

    suspend fun ensureDefaultsInitialized() {
        if (db.favoriteAppDao().count() == 0) {
            val starter = TvAppCatalog.apps.take(3).mapIndexed { index, app ->
                FavoriteAppEntity(
                    packageName = app.packageName,
                    appName = app.appName,
                    launchUri = app.launchUri,
                    iconKey = app.iconKey,
                    accentHex = app.accentHex,
                    sortOrder = index
                )
            }
            db.favoriteAppDao().upsertAll(starter)
        } else {
            val existingFavs = db.favoriteAppDao().getAllFavoritesOnce()
            val migratedFavs = existingFavs.mapNotNull { fav ->
                if (TvAppCatalog.isLegacyBrokenUri(fav.launchUri)) {
                    fav.copy(launchUri = TvAppCatalog.launchUriForPackage(fav.packageName))
                } else null
            }
            if (migratedFavs.isNotEmpty()) {
                db.favoriteAppDao().upsertAll(migratedFavs)
            }
        }

        val existingRecents = db.recentTvAppDao().getRecentAppsOnce()
        val migratedRecents = existingRecents.mapNotNull { rec ->
            if (TvAppCatalog.isLegacyBrokenUri(rec.launchUri)) {
                rec.copy(launchUri = TvAppCatalog.launchUriForPackage(rec.packageName))
            } else null
        }
        if (migratedRecents.isNotEmpty()) {
            db.recentTvAppDao().upsertAll(migratedRecents)
        }
    }

    suspend fun savePairedTv(host: String, name: String, remotePort: Int, pairingPort: Int) {
        db.pairedTvDao().upsert(
            PairedTvEntity(
                host = host,
                name = name,
                remotePort = remotePort,
                pairingPort = pairingPort,
                lastConnectedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun removePairedTv(host: String) {
        db.pairedTvDao().deleteByHost(host)
    }

    suspend fun getMostRecentTv(): PairedTvEntity? = db.pairedTvDao().getMostRecent()

    suspend fun isHostPaired(host: String): Boolean = db.pairedTvDao().getByHost(host) != null

    suspend fun toggleFavorite(app: TvAppCatalogItem, isCurrentlyFavorite: Boolean) {
        if (isCurrentlyFavorite) {
            db.favoriteAppDao().deleteByPackage(app.packageName)
        } else {
            val nextOrder = db.favoriteAppDao().maxSortOrder() + 1
            db.favoriteAppDao().upsert(
                FavoriteAppEntity(
                    packageName = app.packageName,
                    appName = app.appName,
                    launchUri = app.launchUri,
                    iconKey = app.iconKey,
                    accentHex = app.accentHex,
                    sortOrder = nextOrder
                )
            )
        }
    }

    suspend fun addFavoriteFromRecent(recent: RecentTvAppEntity) {
        val nextOrder = db.favoriteAppDao().maxSortOrder() + 1
        db.favoriteAppDao().upsert(
            FavoriteAppEntity(
                packageName = recent.packageName,
                appName = recent.appName,
                launchUri = recent.launchUri,
                iconKey = recent.iconKey,
                accentHex = recent.accentHex,
                sortOrder = nextOrder
            )
        )
    }

    suspend fun addCustomFavoriteApp(appName: String, launchUri: String, packageName: String) {
        val pkg = packageName.ifBlank { "custom.${appName.lowercase().replace(Regex("[^a-z0-9]"), "")}_${System.currentTimeMillis()}" }
        val nextOrder = db.favoriteAppDao().maxSortOrder() + 1
        db.favoriteAppDao().upsert(
            FavoriteAppEntity(
                packageName = pkg,
                appName = appName,
                launchUri = launchUri,
                iconKey = "app_generic",
                accentHex = 0xFF6CD98E,
                sortOrder = nextOrder
            )
        )
        recordRecentApp(
            packageName = pkg,
            appName = appName,
            launchUri = launchUri,
            iconKey = "app_generic",
            accentHex = 0xFF6CD98E,
            source = "CUSTOM"
        )
    }

    suspend fun removeFavorite(packageName: String) {
        db.favoriteAppDao().deleteByPackage(packageName)
    }

    suspend fun moveFavorite(favorites: List<FavoriteAppEntity>, index: Int, delta: Int) {
        val target = index + delta
        if (index !in favorites.indices || target !in favorites.indices) return
        val mutable = favorites.toMutableList()
        val moved = mutable.removeAt(index)
        mutable.add(target, moved)
        val reordered = mutable.mapIndexed { i, item -> item.copy(sortOrder = i) }
        db.favoriteAppDao().upsertAll(reordered)
    }

    suspend fun recordRecentApp(
        packageName: String,
        appName: String = TvAppCatalog.displayNameForPackage(packageName),
        launchUri: String = TvAppCatalog.launchUriForPackage(packageName),
        iconKey: String = TvAppCatalog.iconKeyForPackage(packageName),
        accentHex: Long = TvAppCatalog.accentForPackage(packageName),
        source: String = "TV"
    ) {
        if (packageName.isBlank()) return
        db.recentTvAppDao().upsert(
            RecentTvAppEntity(
                packageName = packageName,
                appName = appName,
                launchUri = launchUri,
                iconKey = iconKey,
                accentHex = accentHex,
                lastUsedAt = System.currentTimeMillis(),
                source = source
            )
        )
    }

    suspend fun updateCustomButton(button: CustomButtonEntity) {
        db.customButtonDao().upsert(button)
    }

    suspend fun getPreferencesOnce(): AppPreferencesEntity =
        db.appPreferencesDao().getPreferencesOnce() ?: AppPreferencesEntity()

    suspend fun updatePreferences(
        hapticEnabled: Boolean,
        autoShowKeyboard: Boolean,
        brightnessPercent: Int
    ) {
        val current = getPreferencesOnce()
        db.appPreferencesDao().upsert(
            current.copy(
                id = 1,
                hapticEnabled = hapticEnabled,
                autoShowKeyboard = autoShowKeyboard,
                brightnessPercent = brightnessPercent.coerceIn(5, 100)
            )
        )
    }
}
