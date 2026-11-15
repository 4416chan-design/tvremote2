package com.example.data.model

/**
 * Standard Android TV Remote Protocol v2 KeyCodes (matching android.view.KeyEvent).
 */
object TvKeyCodes {
    const val HOME = 3
    const val BACK = 4
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val POWER = 26
    const val MENU = 82
    const val SEARCH = 84
    const val MEDIA_PLAY_PAUSE = 85
    const val MEDIA_STOP = 86
    const val MEDIA_NEXT = 87
    const val MEDIA_PREVIOUS = 88
    const val MEDIA_REWIND = 89
    const val MEDIA_FAST_FORWARD = 90
    const val CHANNEL_UP = 166
    const val CHANNEL_DOWN = 167
    const val INFO = 165
    const val TV_INPUT = 178
    const val CAPTIONS = 175
    const val SETTINGS = 176
    const val MUTE = 164
    const val GUIDE = 172
    const val BRIGHTNESS_DOWN = 220
    const val BRIGHTNESS_UP = 221
    const val VOICE_ASSIST = 231
    const val ALL_APPS = 284
    const val SLEEP = 223
    const val SPACE = 62
    const val DEL = 67
    const val ENTER = 66

    // Physical keyboard keys (android.view.KeyEvent constants)
    const val KEYCODE_0 = 7
    const val KEYCODE_1 = 8
    const val KEYCODE_2 = 9
    const val KEYCODE_3 = 10
    const val KEYCODE_4 = 11
    const val KEYCODE_5 = 12
    const val KEYCODE_6 = 13
    const val KEYCODE_7 = 14
    const val KEYCODE_8 = 15
    const val KEYCODE_9 = 16

    const val KEYCODE_A = 29
    const val KEYCODE_B = 30
    const val KEYCODE_C = 31
    const val KEYCODE_D = 32
    const val KEYCODE_E = 33
    const val KEYCODE_F = 34
    const val KEYCODE_G = 35
    const val KEYCODE_H = 36
    const val KEYCODE_I = 37
    const val KEYCODE_J = 38
    const val KEYCODE_K = 39
    const val KEYCODE_L = 40
    const val KEYCODE_M = 41
    const val KEYCODE_N = 42
    const val KEYCODE_O = 43
    const val KEYCODE_P = 44
    const val KEYCODE_Q = 45
    const val KEYCODE_R = 46
    const val KEYCODE_S = 47
    const val KEYCODE_T = 48
    const val KEYCODE_U = 49
    const val KEYCODE_V = 50
    const val KEYCODE_W = 51
    const val KEYCODE_X = 52
    const val KEYCODE_Y = 53
    const val KEYCODE_Z = 54

    const val STAR = 17
    const val POUND = 18
    const val COMMA = 55
    const val PERIOD = 56
    const val SHIFT_LEFT = 59
    const val SHIFT_RIGHT = 60
    const val GRAVE = 68
    const val MINUS = 69
    const val EQUALS = 70
    const val LEFT_BRACKET = 71
    const val RIGHT_BRACKET = 72
    const val BACKSLASH = 73
    const val SEMICOLON = 74
    const val APOSTROPHE = 75
    const val SLASH = 76
    const val AT = 77
    const val PLUS = 81
    const val CTRL_LEFT = 113
    const val MOVE_HOME = 122
    const val MOVE_END = 123
    const val LANGUAGE_SWITCH = 204
}

enum class CustomActionType {
    KEYCODE,
    APP_LAUNCH
}

data class TvAppCatalogItem(
    val packageName: String,
    val appName: String,
    val launchUri: String,
    val iconKey: String,
    val accentHex: Long,
    val category: String
) {
    val marketFallbackUri: String
        get() = "market://details?id=$packageName"
}

object TvAppCatalog {
    val apps: List<TvAppCatalogItem> = listOf(
        TvAppCatalogItem(
            packageName = "com.google.android.youtube.tv",
            appName = "YouTube",
            launchUri = "vnd.youtube://",
            iconKey = "youtube",
            accentHex = 0xFFFF3D3D,
            category = "동영상"
        ),
        TvAppCatalogItem(
            packageName = "com.netflix.ninja",
            appName = "Netflix",
            launchUri = "nflx://",
            iconKey = "netflix",
            accentHex = 0xFFE50914,
            category = "OTT"
        ),
        TvAppCatalogItem(
            packageName = "com.disney.disneyplus",
            appName = "Disney+",
            launchUri = "disneyplus://",
            iconKey = "disney",
            accentHex = 0xFF33A1FD,
            category = "OTT"
        ),
        TvAppCatalogItem(
            packageName = "net.cj.cjhv.gs.tving",
            appName = "TVING",
            launchUri = "tving://",
            iconKey = "tving",
            accentHex = 0xFFFF153C,
            category = "국내 OTT"
        ),
        TvAppCatalogItem(
            packageName = "kr.co.captv.pooqV2",
            appName = "Wavve",
            launchUri = "wavve://",
            iconKey = "wavve",
            accentHex = 0xFF2B67FF,
            category = "국내 OTT"
        ),
        TvAppCatalogItem(
            packageName = "com.coupang.mobile.play",
            appName = "쿠팡플레이",
            launchUri = "coupangplay://",
            iconKey = "coupang",
            accentHex = 0xFF00A7E1,
            category = "국내 OTT"
        ),
        TvAppCatalogItem(
            packageName = "com.frograms.watcha",
            appName = "Watcha",
            launchUri = "watcha://",
            iconKey = "watcha",
            accentHex = 0xFFFF0558,
            category = "국내 OTT"
        ),
        TvAppCatalogItem(
            packageName = "com.spotify.tv.android",
            appName = "Spotify",
            launchUri = "spotify://",
            iconKey = "spotify",
            accentHex = 0xFF1DB954,
            category = "음악"
        ),
        TvAppCatalogItem(
            packageName = "com.amazon.amazonvideo.livingroom",
            appName = "Prime Video",
            launchUri = "market://details?id=com.amazon.amazonvideo.livingroom",
            iconKey = "prime",
            accentHex = 0xFF00A8E1,
            category = "OTT"
        ),
        TvAppCatalogItem(
            packageName = "com.apple.atve.androidtv.appletv",
            appName = "Apple TV",
            launchUri = "market://details?id=com.apple.atve.androidtv.appletv",
            iconKey = "appletv",
            accentHex = 0xFFE5E7EB,
            category = "OTT"
        ),
        TvAppCatalogItem(
            packageName = "tv.twitch.android.app",
            appName = "Twitch",
            launchUri = "twitch://",
            iconKey = "twitch",
            accentHex = 0xFF9146FF,
            category = "스트리밍"
        ),
        TvAppCatalogItem(
            packageName = "com.plexapp.android",
            appName = "Plex",
            launchUri = "plex://",
            iconKey = "plex",
            accentHex = 0xFFE5A00D,
            category = "미디어 서버"
        ),
        TvAppCatalogItem(
            packageName = "org.xbmc.kodi",
            appName = "Kodi",
            launchUri = "market://details?id=org.xbmc.kodi",
            iconKey = "kodi",
            accentHex = 0xFF17B2E7,
            category = "미디어 서버"
        ),
        TvAppCatalogItem(
            packageName = "com.google.android.videos",
            appName = "Google TV",
            launchUri = "market://details?id=com.google.android.videos",
            iconKey = "googletv",
            accentHex = 0xFF6CD98E,
            category = "시스템"
        )
    )

    fun findByPackage(packageName: String): TvAppCatalogItem? =
        apps.firstOrNull { it.packageName.equals(packageName, ignoreCase = true) }

    fun displayNameForPackage(packageName: String): String {
        findByPackage(packageName)?.let { return it.appName }
        val tail = packageName.substringAfterLast('.').ifBlank { packageName }
        return tail.replaceFirstChar { it.uppercaseChar() }
    }

    fun launchUriForPackage(packageName: String): String {
        findByPackage(packageName)?.let { return it.launchUri }
        return marketUriForPackage(packageName)
    }

    fun marketUriForPackage(packageName: String): String =
        "market://details?id=$packageName"

    fun isLegacyBrokenUri(uri: String): Boolean =
        uri.isBlank() ||
            uri.startsWith("market://launch", ignoreCase = true) ||
            uri.startsWith("http://", ignoreCase = true) ||
            uri.startsWith("https://", ignoreCase = true)

    fun iconKeyForPackage(packageName: String): String =
        findByPackage(packageName)?.iconKey ?: "app_generic"

    fun accentForPackage(packageName: String): Long =
        findByPackage(packageName)?.accentHex ?: 0xFF6CD98E
}
