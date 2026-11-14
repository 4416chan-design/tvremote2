package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "paired_tvs")
data class PairedTvEntity(
    @PrimaryKey val host: String,
    val name: String,
    val remotePort: Int = 6466,
    val pairingPort: Int = 6467,
    val lastConnectedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "favorite_apps")
data class FavoriteAppEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val launchUri: String,
    val iconKey: String,
    val accentHex: Long,
    val sortOrder: Int
)

@Entity(tableName = "recent_tv_apps")
data class RecentTvAppEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val launchUri: String,
    val iconKey: String,
    val accentHex: Long,
    val lastUsedAt: Long = System.currentTimeMillis(),
    val source: String = "TV"
)

@Entity(tableName = "custom_buttons")
data class CustomButtonEntity(
    @PrimaryKey val slotIndex: Int,
    val label: String,
    val iconKey: String,
    val actionType: String,
    val keyCode: Int = 0,
    val launchUri: String = "",
    val packageName: String = ""
)

@Entity(tableName = "app_preferences")
data class AppPreferencesEntity(
    @PrimaryKey val id: Int = 1,
    val hapticEnabled: Boolean = true,
    val autoShowKeyboard: Boolean = true,
    val brightnessPercent: Int = 60,
    val hasPromptedAdbKeyboardSetup: Boolean = false
)
